// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors
package com.yourcompany.kafkasqlexplorer.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yourcompany.kafkasqlexplorer.config.ClaudeConfig;
import com.yourcompany.kafkasqlexplorer.domain.AnomalyReport;
import com.yourcompany.kafkasqlexplorer.domain.FieldMapping;
import com.yourcompany.kafkasqlexplorer.domain.KafkaMessage;
import com.yourcompany.kafkasqlexplorer.domain.LlmResponse;
import com.yourcompany.kafkasqlexplorer.domain.ProcessMiningResult;
import com.yourcompany.kafkasqlexplorer.domain.SnapshotConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class LlmAnalysisService {

    private static final Logger log = LoggerFactory.getLogger(LlmAnalysisService.class);

    private final KafkaSnapshotReader snapshotReader;
    private final ClaudeConfig claudeConfig;
    private final LlmClient llmClient;
    private final ObjectMapper objectMapper = new ObjectMapper();

    private static final String SYSTEM_PROMPT = """
        Expert Apache Kafka & Process Mining.
        Analyze Kafka messages to produce a Mermaid flowchart and anomaly report.
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
    public LlmAnalysisService(KafkaSnapshotReader snapshotReader, ClaudeConfig claudeConfig) {
        this(snapshotReader, claudeConfig, LlmClientFactory.create(claudeConfig));
    }

    public LlmAnalysisService(KafkaSnapshotReader snapshotReader, ClaudeConfig claudeConfig, LlmClient llmClient) {
        this.snapshotReader = snapshotReader;
        this.claudeConfig = claudeConfig;
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

        // 1. Read messages
        List<KafkaMessage> messages = snapshotReader.read(topics, depth);

        // 2. Group by topic, sort by timestamp
        Map<String, List<KafkaMessage>> byTopic = groupAndSort(topics, messages);

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
        if (isApiKeyMissing()) {
            return errorResult("LLM API key not configured.");
        }

        // Group by topic
        List<String> topics = windowMessages.stream()
            .map(KafkaMessage::topic)
            .distinct()
            .sorted()
            .toList();
        Map<String, List<KafkaMessage>> byTopic = groupAndSort(topics, windowMessages);

        String userPrompt = buildLivePrompt(topics, byTopic, fieldMapping, referenceFlowchart, auditFocus);
        return callLlmAndParse(userPrompt);
    }

    private Map<String, List<KafkaMessage>> groupAndSort(List<String> topics,
                                                           List<KafkaMessage> messages) {
        Map<String, List<KafkaMessage>> byTopic = new LinkedHashMap<>();
        for (String topic : topics) {
            byTopic.put(topic, new ArrayList<>());
        }
        for (KafkaMessage msg : messages) {
            byTopic.computeIfAbsent(msg.topic(), k -> new ArrayList<>()).add(msg);
        }
        byTopic.values().forEach(list -> list.sort(Comparator.comparingLong(KafkaMessage::timestamp)));
        return byTopic;
    }

    private String buildSnapshotPrompt(List<String> topics,
                                        Map<String, List<KafkaMessage>> byTopic,
                                        FieldMapping fieldMapping,
                                        String auditFocus) {
        StringBuilder sb = new StringBuilder();
        sb.append("## MODE: ANALYSE SNAPSHOT\n\n");
        appendCommonSections(sb, topics, byTopic, fieldMapping, null, auditFocus);
        return sb.toString();
    }

    private String buildLivePrompt(List<String> topics,
                                    Map<String, List<KafkaMessage>> byTopic,
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
                                       Map<String, List<KafkaMessage>> byTopic,
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

        sb.append("## MESSAGES PAR TOPIC\n");
        for (Map.Entry<String, List<KafkaMessage>> entry : byTopic.entrySet()) {
            sb.append("\n### Topic: ").append(entry.getKey()).append("\n");
            sb.append("[\n");
            List<KafkaMessage> msgs = entry.getValue().stream().limit(100).toList();
            for (int i = 0; i < msgs.size(); i++) {
                KafkaMessage msg = msgs.get(i);
                sb.append("  {\"offset\": ").append(msg.offset());
                sb.append(", \"timestamp\": ").append(msg.timestamp());
                sb.append(", \"key\": ");
                appendJsonString(sb, msg.key());
                sb.append(", \"value\": ");
                appendJsonString(sb, msg.value());
                sb.append("}");
                if (i < msgs.size() - 1) sb.append(",");
                sb.append("\n");
            }
            sb.append("]\n");
        }

        sb.append("""

## INSTRUCTIONS
1. Analyse les flux de messages entre les topics
2. Identifie les patterns de processus (séquences, branchements, erreurs)
3. Génère un flowchart Mermaid décrivant le flux nominal et les anomalies
4. Liste les anomalies détectées avec leur sévérité
5. Propose des hypothèses sur l'architecture sous-jacente
6. Identifie les angles morts (données manquantes, topics non observés)
7. Si une information est incertaine, préfère une liste vide à un texte hors format
""");
    }

    private void appendJsonString(StringBuilder sb, String value) {
        if (value == null) {
            sb.append("null");
        } else {
            sb.append("\"").append(value.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "\\r")).append("\"");
        }
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
