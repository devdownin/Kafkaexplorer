// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors
package com.compagnonsdudev.kafkasqlexplorer.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.compagnonsdudev.kafkasqlexplorer.config.ClaudeConfig;
import com.compagnonsdudev.kafkasqlexplorer.config.ProcessMiningConfig;
import com.compagnonsdudev.kafkasqlexplorer.domain.AnomalyReport;
import com.compagnonsdudev.kafkasqlexplorer.domain.FieldMapping;
import com.compagnonsdudev.kafkasqlexplorer.domain.KafkaMessage;
import com.compagnonsdudev.kafkasqlexplorer.domain.LlmResponse;
import com.compagnonsdudev.kafkasqlexplorer.domain.PayloadDigest;
import com.compagnonsdudev.kafkasqlexplorer.domain.PayloadShape;
import com.compagnonsdudev.kafkasqlexplorer.domain.ProcessMiningResult;
import com.compagnonsdudev.kafkasqlexplorer.domain.SnapshotConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
public class LlmAnalysisService {

    private static final Logger log = LoggerFactory.getLogger(LlmAnalysisService.class);

    private final KafkaSnapshotReader snapshotReader;
    private final ClaudeConfig claudeConfig;
    private final ProcessMiningConfig processMiningConfig;
    private final PayloadDigestService payloadDigestService;
    private final LlmClient llmClient;
    private final ObjectMapper objectMapper = new ObjectMapper();

    private static final String SYSTEM_PROMPT = """
        Expert Apache Kafka & Process Mining.
        Analyze Kafka messages to produce a Mermaid flowchart and anomaly report.
        Messages arrive as bounded digests (structure + selected values), never as raw payloads:
        reason about flows and shapes, and treat missing values as unobserved, not absent.
        Return ONLY valid JSON (camelCase). NO markdown, NO prose outside JSON.

        JSON structure:
        {
          "flowchart": "flowchart TD\\n...",
          "comments": "Short description",
          "hypotheses": ["..."],
          "blindSpots": ["..."],
          "anomalies": [
            {
              "id": "ANO-001",
              "topic": "topic-name",
              "type": "SEQUENCE|TEMPORAL|STRUCTURAL|CARDINALITY|BUSINESS",
              "severity": "CRITICAL|MAJOR|MINOR",
              "fields": ["$.field"],
              "description": "...",
              "probableCause": "...",
              "ksqlSuggestion": "CREATE STREAM ..."
            }
          ]
        }

        Mermaid Rules:
        - Topics: [TopicName]
        - Services: (ServiceName)
        - Decisions: {condition}
        - Normal flow: -->
        - Anomaly flow: -.->
        """;

    @org.springframework.beans.factory.annotation.Autowired
    public LlmAnalysisService(KafkaSnapshotReader snapshotReader, ClaudeConfig claudeConfig,
                              ProcessMiningConfig processMiningConfig,
                              PayloadDigestService payloadDigestService) {
        this(snapshotReader, claudeConfig, processMiningConfig, payloadDigestService,
            LlmClientFactory.create(claudeConfig));
    }

    /** Test seam: defaults the ingestion tuning so unit tests only have to supply an LlmClient. */
    public LlmAnalysisService(KafkaSnapshotReader snapshotReader, ClaudeConfig claudeConfig, LlmClient llmClient) {
        this(snapshotReader, claudeConfig, new ProcessMiningConfig(),
            new PayloadDigestService(new ProcessMiningConfig()), llmClient);
    }

    public LlmAnalysisService(KafkaSnapshotReader snapshotReader, ClaudeConfig claudeConfig,
                              ProcessMiningConfig processMiningConfig,
                              PayloadDigestService payloadDigestService, LlmClient llmClient) {
        this.snapshotReader = snapshotReader;
        this.claudeConfig = claudeConfig;
        this.processMiningConfig = processMiningConfig;
        this.payloadDigestService = payloadDigestService;
        this.llmClient = llmClient;
    }

    public ProcessMiningResult analyzeSnapshot(List<String> topics, SnapshotConfig depth,
                                                FieldMapping fieldMapping) {
        return analyzeSnapshot(topics, depth, fieldMapping, null);
    }

    public ProcessMiningResult analyzeSnapshot(List<String> topics, SnapshotConfig depth,
                                                FieldMapping fieldMapping, String auditFocus) {
        if (isApiKeyMissing()) {
            return errorResult("LLM API key not configured.");
        }

        // 1. Read and digest in one pass — payloads are summarized as they arrive and never
        //    accumulate in memory nor reach the prompt verbatim (a snapshot of 500 messages per
        //    topic at 1 MB each would be gigabytes of context)
        List<PayloadDigest> digests = snapshotReader.readDigested(
            topics, depth, fieldMapping, processMiningConfig.getMaxSampleFields());

        // 2. Group by topic, sort by timestamp
        Map<String, List<PayloadDigest>> byTopic = groupAndSort(topics, digests);

        // 3. Build user prompt
        String userPrompt = buildSnapshotPrompt(topics, byTopic, fieldMapping, auditFocus);

        // 4. Call the configured LLM and parse
        return callLlmAndParse(userPrompt);
    }

    public ProcessMiningResult analyzeLive(List<KafkaMessage> windowMessages,
                                            FieldMapping fieldMapping,
                                            String referenceFlowchart) {
        return analyzeLive(windowMessages, fieldMapping, referenceFlowchart, null);
    }

    public ProcessMiningResult analyzeLive(List<KafkaMessage> windowMessages,
                                            FieldMapping fieldMapping,
                                            String referenceFlowchart,
                                            String auditFocus) {
        return analyzeLiveDigests(payloadDigestService.digestAll(windowMessages, fieldMapping),
            fieldMapping, referenceFlowchart, auditFocus);
    }

    /**
     * Live analysis over already-digested records — the path the live consumer uses, so raw
     * payloads are never buffered nor carried across threads.
     */
    public ProcessMiningResult analyzeLiveDigests(List<PayloadDigest> window,
                                                   FieldMapping fieldMapping,
                                                   String referenceFlowchart,
                                                   String auditFocus) {
        if (isApiKeyMissing()) {
            return errorResult("LLM API key not configured.");
        }

        List<String> topics = window.stream()
            .map(PayloadDigest::topic)
            .distinct()
            .sorted()
            .toList();
        Map<String, List<PayloadDigest>> byTopic = groupAndSort(topics, window);

        String userPrompt = buildLivePrompt(topics, byTopic, fieldMapping, referenceFlowchart, auditFocus);
        return callLlmAndParse(userPrompt);
    }

    private Map<String, List<PayloadDigest>> groupAndSort(List<String> topics,
                                                           List<PayloadDigest> digests) {
        Map<String, List<PayloadDigest>> byTopic = new LinkedHashMap<>();
        for (String topic : topics) {
            byTopic.put(topic, new ArrayList<>());
        }
        for (PayloadDigest digest : digests) {
            byTopic.computeIfAbsent(digest.topic(), k -> new ArrayList<>()).add(digest);
        }
        byTopic.values().forEach(list -> list.sort(Comparator.comparingLong(PayloadDigest::timestamp)));
        return byTopic;
    }

    private String buildSnapshotPrompt(List<String> topics,
                                        Map<String, List<PayloadDigest>> byTopic,
                                        FieldMapping fieldMapping,
                                        String auditFocus) {
        StringBuilder sb = new StringBuilder();
        sb.append("## MODE: ANALYSE SNAPSHOT\n\n");
        appendCommonSections(sb, topics, byTopic, fieldMapping, null, auditFocus);
        return sb.toString();
    }

    private String buildLivePrompt(List<String> topics,
                                    Map<String, List<PayloadDigest>> byTopic,
                                    FieldMapping fieldMapping,
                                    String referenceFlowchart,
                                    String auditFocus) {
        StringBuilder sb = new StringBuilder();
        sb.append("## MODE: ANALYSE LIVE\n\n");
        String ref = (referenceFlowchart == null || referenceFlowchart.isBlank())
            ? "INCONNU" : referenceFlowchart;
        appendCommonSections(sb, topics, byTopic, fieldMapping, ref, auditFocus);
        return sb.toString();
    }

    private void appendCommonSections(StringBuilder sb, List<String> topics,
                                       Map<String, List<PayloadDigest>> byTopic,
                                       FieldMapping fieldMapping,
                                       String referenceFlowchart,
                                       String auditFocus) {
        sb.append("## MAPPING DES CHAMPS\n");
        if (fieldMapping != null) {
            sb.append(fieldMapping.toPromptBlock());
        } else {
            sb.append("(aucun mapping fourni)\n");
        }
        sb.append("\n");

        if (auditFocus != null && !auditFocus.isBlank()) {
            sb.append("## AUDIT CIBLÉ\n");
            sb.append("Concentre l'analyse en priorité sur les points d'audit suivants. "
                + "Pour chaque anomalie trouvée, respecte la structure JSON demandée "
                + "(type et sévérité).\n");
            sb.append(auditFocus.strip()).append("\n\n");
        }

        if (referenceFlowchart != null) {
            sb.append("## FLOWCHART DE RÉFÉRENCE\n");
            sb.append(referenceFlowchart).append("\n\n");
            sb.append("Si aucun changement structurel détecté, retourne \"NO_CHANGE\" dans le champ flowchart.\n\n");
        }

        Map<String, List<PayloadDigest>> sampled = new LinkedHashMap<>();
        int perTopicLimit = Math.max(1, processMiningConfig.getMaxMessagesPerTopicInPrompt());
        byTopic.forEach((topic, digests) -> sampled.put(topic, evenSample(digests, perTopicLimit)));

        appendShapes(sb, sampled);
        appendMessages(sb, byTopic, sampled);

        sb.append("""

## INSTRUCTIONS
1. Analyse les flux de messages entre les topics
2. Identifie les patterns de processus (séquences, branchements, erreurs)
3. Génère un flowchart Mermaid décrivant le flux nominal et les anomalies
4. Liste les anomalies détectées avec leur sévérité
5. Propose des hypothèses sur l'architecture sous-jacente
6. Identifie les angles morts (données manquantes, topics non observés)
7. Si une information est incertaine, préfère une liste vide à un texte hors format
8. Les payloads sont résumés, pas complets : ne conclus jamais à l'absence d'un champ
   à partir de son absence dans "sample" — seul "shape" fait foi sur la structure
""");
    }

    /**
     * Structures, deduplicated. A window of a thousand documents that share a schema costs one
     * shape block; without this the same ten-level skeleton would be repeated per message.
     */
    private void appendShapes(StringBuilder sb, Map<String, List<PayloadDigest>> sampled) {
        Set<String> shapeIds = new LinkedHashSet<>();
        for (List<PayloadDigest> digests : sampled.values()) {
            for (PayloadDigest digest : digests) {
                if (digest.shapeId() != null) {
                    shapeIds.add(digest.shapeId());
                }
            }
        }
        List<PayloadShape> shapes = payloadDigestService.shapesFor(shapeIds);
        if (shapes.isEmpty()) {
            return;
        }

        sb.append("## STRUCTURES DE PAYLOAD (chemins des feuilles, index de tableau réduits à [])\n");
        for (PayloadShape shape : shapes) {
            sb.append(shape.toPromptBlock(processMiningConfig.getMaxShapePathsInPrompt()));
        }
        sb.append("\n");
    }

    /**
     * Message digests, inlined under a global character budget. Each topic gets an equal share;
     * within a topic the messages are an evenly spaced sample (first and last always kept) so a
     * burst never crowds out the rest of the window.
     */
    private void appendMessages(StringBuilder sb,
                                 Map<String, List<PayloadDigest>> byTopic,
                                 Map<String, List<PayloadDigest>> sampled) {
        sb.append("""
## FORMAT DES MESSAGES
Chaque message est un résumé du payload d'origine :
  shape  = identifiant de structure (section ci-dessus)
  bytes  = taille réelle du payload d'origine
  fields = valeurs aux chemins déclarés dans le mapping
  sample = autres valeurs scalaires, échantillon borné, tronquées au-delà de la limite
  arrays = chemin de tableau -> nombre d'éléments réels
  partial = true quand le résumé est incomplet (payload plus grand que le budget d'analyse)

## MESSAGES PAR TOPIC
""");

        int topicCount = Math.max(1, byTopic.size());
        int perTopicBudget = Math.max(2_000, processMiningConfig.getPromptCharBudget() / topicCount);

        for (Map.Entry<String, List<PayloadDigest>> entry : byTopic.entrySet()) {
            List<PayloadDigest> all = entry.getValue();
            List<PayloadDigest> selected = sampled.getOrDefault(entry.getKey(), List.of());

            long totalBytes = all.stream().mapToLong(PayloadDigest::payloadBytes).sum();
            long maxBytes = all.stream().mapToLong(PayloadDigest::payloadBytes).max().orElse(0);

            sb.append("\n### Topic: ").append(entry.getKey())
              .append(" — ").append(all.size()).append(" message(s), ")
              .append(formatBytes(totalBytes)).append(" au total, ")
              .append("payload max ").append(formatBytes(maxBytes)).append("\n");

            int budgetStart = sb.length();
            int written = 0;
            sb.append("[\n");
            for (PayloadDigest digest : selected) {
                if (sb.length() - budgetStart > perTopicBudget) {
                    break;
                }
                if (written > 0) {
                    sb.append(",\n");
                }
                appendDigest(sb, digest);
                written++;
            }
            sb.append("\n]\n");

            if (written < all.size()) {
                sb.append("(").append(all.size() - written)
                  .append(" message(s) non inclus — échantillon régulier sur la fenêtre)\n");
            }
        }
    }

    private void appendDigest(StringBuilder sb, PayloadDigest digest) {
        sb.append("  {\"offset\": ").append(digest.offset());
        sb.append(", \"partition\": ").append(digest.partition());
        sb.append(", \"timestamp\": ").append(digest.timestamp());
        sb.append(", \"key\": ");
        appendJsonString(sb, digest.key());
        sb.append(", \"bytes\": ").append(digest.payloadBytes());
        sb.append(", \"format\": ");
        appendJsonString(sb, digest.format());
        if (digest.shapeId() != null) {
            sb.append(", \"shape\": ");
            appendJsonString(sb, digest.shapeId());
        }
        appendJsonMap(sb, "fields", digest.fields());
        appendJsonMap(sb, "sample", digest.sample());
        if (digest.arrayCounts() != null && !digest.arrayCounts().isEmpty()) {
            sb.append(", \"arrays\": {");
            boolean first = true;
            for (Map.Entry<String, Integer> entry : digest.arrayCounts().entrySet()) {
                if (!first) sb.append(", ");
                appendJsonString(sb, entry.getKey());
                sb.append(": ").append(entry.getValue());
                first = false;
            }
            sb.append("}");
        }
        if (digest.preview() != null) {
            sb.append(", \"preview\": ");
            appendJsonString(sb, digest.preview());
        }
        if (digest.parseError() != null) {
            sb.append(", \"parseError\": ");
            appendJsonString(sb, digest.parseError());
        }
        if (digest.truncated()) {
            sb.append(", \"partial\": true");
        }
        sb.append("}");
    }

    private void appendJsonMap(StringBuilder sb, String name, Map<String, String> values) {
        if (values == null || values.isEmpty()) {
            return;
        }
        sb.append(", \"").append(name).append("\": {");
        boolean first = true;
        for (Map.Entry<String, String> entry : values.entrySet()) {
            if (!first) sb.append(", ");
            appendJsonString(sb, entry.getKey());
            sb.append(": ");
            appendJsonString(sb, entry.getValue());
            first = false;
        }
        sb.append("}");
    }

    /**
     * Picks at most {@code limit} elements spread evenly over the list, always keeping the first
     * and the last so the window's boundaries stay visible.
     */
    static <T> List<T> evenSample(List<T> values, int limit) {
        if (values.size() <= limit) {
            return values;
        }
        if (limit <= 1) {
            return values.isEmpty() ? values : List.of(values.get(0));
        }
        List<T> sampled = new ArrayList<>(limit);
        double step = (double) (values.size() - 1) / (limit - 1);
        for (int i = 0; i < limit; i++) {
            sampled.add(values.get((int) Math.round(i * step)));
        }
        return sampled;
    }

    private static String formatBytes(long bytes) {
        if (bytes < 1024) return bytes + " o";
        if (bytes < 1024 * 1024) return (bytes / 1024) + " Ko";
        return String.format("%.1f Mo", bytes / (1024.0 * 1024.0));
    }

    /** Single pass: chaining String.replace() copied every 1 MB payload four times over. */
    private void appendJsonString(StringBuilder sb, String value) {
        if (value == null) {
            sb.append("null");
            return;
        }
        sb.append('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        sb.append('"');
    }

    private boolean isApiKeyMissing() {
        return claudeConfig.isApiKeyRequired() && !claudeConfig.isApiKeyConfigured();
    }

    private ProcessMiningResult callLlmAndParse(String userPrompt) {
        LlmResponse response;
        try {
            response = llmClient.generateWithMeta(SYSTEM_PROMPT, userPrompt);
        } catch (Exception e) {
            // Surface the real cause (timeout, bad URL/model/key, provider 5xx) instead of a
            // generic "empty response" — callers show comments() to the user.
            log.error("Error calling LLM API for analysis: {}", e.getMessage(), e);
            return errorResult("LLM call failed: " + e.getMessage());
        }

        String rawResponse = response.text();
        log.debug("LLM analysis response (first 500 chars): {}",
            rawResponse != null && rawResponse.length() > 500 ? rawResponse.substring(0, 500) : rawResponse);

        if (rawResponse == null || rawResponse.isBlank()) {
            return errorResult("LLM returned an empty response.");
        }

        String json = LlmJsonSupport.extractJsonPayload(rawResponse);

        try {
            ProcessMiningResult parsed = objectMapper.readValue(json, ProcessMiningResult.class);
            // Attach RAG citations (SpectraLLM); other providers return none.
            return parsed.withRagSources(response.sources());
        } catch (Exception e) {
            log.error("Failed to parse LLM analysis response: {}", e.getMessage());
            log.debug("Raw response was: {}", rawResponse);
            return errorResult("Failed to parse LLM response: " + e.getMessage());
        }
    }

    private ProcessMiningResult errorResult(String message) {
        return new ProcessMiningResult(
            null,
            message,
            List.of(),
            List.of(),
            List.of()
        );
    }
}
