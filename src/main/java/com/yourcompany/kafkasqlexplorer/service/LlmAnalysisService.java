// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors
package com.yourcompany.kafkasqlexplorer.service;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.MessageParam;
import com.anthropic.models.messages.TextDelta;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yourcompany.kafkasqlexplorer.config.ClaudeConfig;
import com.yourcompany.kafkasqlexplorer.domain.AnomalyReport;
import com.yourcompany.kafkasqlexplorer.domain.FieldMapping;
import com.yourcompany.kafkasqlexplorer.domain.KafkaMessage;
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
import java.util.stream.Collectors;

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

    public LlmAnalysisService(KafkaSnapshotReader snapshotReader, ClaudeConfig claudeConfig) {
        this(snapshotReader, claudeConfig, claudeConfig.getProvider() == ClaudeConfig.Provider.ANTHROPIC
            ? new AnthropicLlmClient(claudeConfig)
            : new OpenAiCompatibleLlmClient(claudeConfig));
    }

    public LlmAnalysisService(KafkaSnapshotReader snapshotReader, ClaudeConfig claudeConfig, LlmClient llmClient) {
        this.snapshotReader = snapshotReader;
        this.claudeConfig = claudeConfig;
        this.llmClient = llmClient;
    }

    public ProcessMiningResult analyzeSnapshot(List<String> topics, SnapshotConfig depth,
                                                FieldMapping fieldMapping) {
        if (isApiKeyMissing()) {
            return errorResult("LLM API key not configured.");
        }

        // 1. Read messages
        List<KafkaMessage> messages = snapshotReader.read(topics, depth);

        // 2. Group by topic, sort by timestamp
        Map<String, List<KafkaMessage>> byTopic = groupAndSort(topics, messages);

        // 3. Build user prompt
        String userPrompt = buildSnapshotPrompt(topics, byTopic, fieldMapping);

        // 4. Call Claude and parse
        return callClaudeAndParse(userPrompt);
    }

    public ProcessMiningResult analyzeLive(List<KafkaMessage> windowMessages,
                                            FieldMapping fieldMapping,
                                            String referenceFlowchart) {
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

        String userPrompt = buildLivePrompt(topics, byTopic, fieldMapping, referenceFlowchart);
        return callClaudeAndParse(userPrompt);
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
                                        FieldMapping fieldMapping) {
        StringBuilder sb = new StringBuilder();
        sb.append("## MODE: ANALYSE SNAPSHOT\n\n");
        appendCommonSections(sb, topics, byTopic, fieldMapping, null);
        return sb.toString();
    }

    private String buildLivePrompt(List<String> topics,
                                    Map<String, List<KafkaMessage>> byTopic,
                                    FieldMapping fieldMapping,
                                    String referenceFlowchart) {
        StringBuilder sb = new StringBuilder();
        sb.append("## MODE: ANALYSE LIVE\n\n");
        String ref = (referenceFlowchart == null || referenceFlowchart.isBlank())
            ? "INCONNU" : referenceFlowchart;
        appendCommonSections(sb, topics, byTopic, fieldMapping, ref);
        return sb.toString();
    }

    private void appendCommonSections(StringBuilder sb, List<String> topics,
                                       Map<String, List<KafkaMessage>> byTopic,
                                       FieldMapping fieldMapping,
                                       String referenceFlowchart) {
        sb.append("## MAPPING DES CHAMPS\n");
        if (fieldMapping != null) {
            sb.append(fieldMapping.toPromptBlock());
        } else {
            sb.append("(aucun mapping fourni)\n");
        }
        sb.append("\n");

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
        // API key is mandatory for Anthropic
        if (claudeConfig.getProvider() == ClaudeConfig.Provider.ANTHROPIC) {
            return claudeConfig.getApiKey() == null || claudeConfig.getApiKey().isBlank();
        }
        // For OpenAI-compatible providers, the API key is optional
        return false;
    }

    private String callLlm(String userPrompt) {
        try {
            String fullText = llmClient.generate(SYSTEM_PROMPT, userPrompt);
            log.debug("LLM analysis response (first 500 chars): {}",
                fullText.length() > 500 ? fullText.substring(0, 500) : fullText);
            return fullText;
        } catch (Exception e) {
            log.error("Error calling LLM API for analysis: {}", e.getMessage(), e);
            return null;
        }
    }

    private ProcessMiningResult callClaudeAndParse(String userPrompt) {
        String rawResponse = callLlm(userPrompt);

        if (rawResponse == null || rawResponse.isBlank()) {
            return errorResult("LLM returned an empty response.");
        }

        String json = stripMarkdownFences(rawResponse);

        try {
            return objectMapper.readValue(json, ProcessMiningResult.class);
        } catch (Exception e) {
            log.error("Failed to parse Claude analysis response: {}", e.getMessage());
            log.debug("Raw response was: {}", rawResponse);
            return errorResult("Failed to parse Claude response: " + e.getMessage());
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

    private String stripMarkdownFences(String text) {
        String trimmed = text.trim();
        if (trimmed.startsWith("```")) {
            int firstNewline = trimmed.indexOf('\n');
            if (firstNewline >= 0) {
                trimmed = trimmed.substring(firstNewline + 1);
            }
            if (trimmed.endsWith("```")) {
                trimmed = trimmed.substring(0, trimmed.lastIndexOf("```")).trim();
            }
        }
        return trimmed;
    }
}
