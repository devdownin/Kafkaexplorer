// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors
package com.yourcompany.kafkasqlexplorer.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yourcompany.kafkasqlexplorer.config.ClaudeConfig;
import com.yourcompany.kafkasqlexplorer.domain.FieldProfileResult;
import com.yourcompany.kafkasqlexplorer.domain.KafkaMessage;
import com.yourcompany.kafkasqlexplorer.domain.SnapshotConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class FieldProfilingService {

    private static final Logger log = LoggerFactory.getLogger(FieldProfilingService.class);

    private final KafkaSnapshotReader snapshotReader;
    private final ClaudeConfig claudeConfig;
    private final LlmClient llmClient;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @org.springframework.beans.factory.annotation.Autowired
    public FieldProfilingService(KafkaSnapshotReader snapshotReader, ClaudeConfig claudeConfig) {
        this(snapshotReader, claudeConfig, claudeConfig.getProvider() == ClaudeConfig.Provider.ANTHROPIC
            ? new AnthropicLlmClient(claudeConfig)
            : new OpenAiCompatibleLlmClient(claudeConfig));
    }

    public FieldProfilingService(KafkaSnapshotReader snapshotReader, ClaudeConfig claudeConfig, LlmClient llmClient) {
        this.snapshotReader = snapshotReader;
        this.claudeConfig = claudeConfig;
        this.llmClient = llmClient;
    }

    public FieldProfileResult profile(List<String> topics, SnapshotConfig config) {
        if (isApiKeyMissing()) {
            log.warn("LLM API key not configured — returning empty profile");
            return new FieldProfileResult(
                List.of(),
                null,
                List.of("LLM API key not configured.")
            );
        }

        // 1. Read samples — 50 messages per topic
        SnapshotConfig samplingConfig = SnapshotConfig.latestN(50);
        List<KafkaMessage> messages = snapshotReader.read(topics, samplingConfig);

        // 2. Group messages by topic
        Map<String, List<KafkaMessage>> byTopic = new LinkedHashMap<>();
        for (String topic : topics) {
            byTopic.put(topic, new ArrayList<>());
        }
        for (KafkaMessage msg : messages) {
            byTopic.computeIfAbsent(msg.topic(), k -> new ArrayList<>()).add(msg);
        }

        // 3. Build user prompt
        String userPrompt = buildProfilingPrompt(topics, byTopic);

        // 4. Call LLM API
        String rawResponse;
        try {
            String systemPrompt = """
                Expert Kafka & Data Mining. Analyze Kafka message samples.
                Return ONLY valid JSON. NO markdown, NO prose outside JSON.
                If confidence is low, prefer empty arrays and warnings instead of free-form explanations.
                """;
            rawResponse = llmClient.generate(systemPrompt, userPrompt);
        } catch (Exception e) {
            log.error("LLM API call failed during profiling: {}", e.getMessage(), e);
            return new FieldProfileResult(
                List.of(),
                null,
                List.of("LLM API error: " + e.getMessage())
            );
        }

        // 5. Parse response
        return parseProfileResult(rawResponse);
    }

    private String buildProfilingPrompt(List<String> topics, Map<String, List<KafkaMessage>> byTopic) {
        StringBuilder sb = new StringBuilder();
        sb.append("## TOPICS À ANALYSER\n");
        for (String topic : topics) {
            sb.append("- ").append(topic).append("\n");
        }
        sb.append("\n## ÉCHANTILLONS DE MESSAGES (50 par topic)\n");

        for (Map.Entry<String, List<KafkaMessage>> entry : byTopic.entrySet()) {
            sb.append("\n### Topic: ").append(entry.getKey()).append("\n");
            sb.append("[\n");
            List<KafkaMessage> msgs = entry.getValue().stream().limit(50).toList();
            for (int i = 0; i < msgs.size(); i++) {
                KafkaMessage msg = msgs.get(i);
                sb.append("  {\"key\": ");
                appendJsonString(sb, msg.key());
                sb.append(", \"value\": ");
                appendJsonString(sb, msg.value());
                sb.append(", \"timestamp\": ").append(msg.timestamp()).append("}");
                if (i < msgs.size() - 1) sb.append(",");
                sb.append("\n");
            }
            sb.append("]\n");
        }

        sb.append("""

## INSTRUCTIONS
Pour chaque topic et chaque champ JSON, détecte :
- CORRELATION_ID : id/key/ref/saga, UUID ou code stable, présent dans plusieurs topics
- TIMESTAMP : date/time/at/on/created, ISO8601 ou epoch
- STATUS : status/state/step/type, valeurs d'un ensemble fermé
- AMOUNT : amount/price/total/qty, valeur numérique

Réponds avec ce JSON exact (camelCase, sans markdown) :
{
  "topics": [
    {
      "name": "topic-name",
      "format": "JSON",
      "fields": [
        {
          "path": "$.fieldName",
          "sampleValues": ["val1", "val2"],
          "inferredType": "STRING|NUMBER|BOOLEAN|DATE|UUID|ENUM",
          "semanticRole": "CORRELATION_ID|TIMESTAMP|STATUS|AMOUNT|ACTOR|UNKNOWN",
          "confidence": 0.95,
          "reasoning": "explication"
        }
      ],
      "candidateCorrelationKeys": ["$.id"],
      "candidateTimestamps": ["$.createdAt"],
      "candidateStatuses": ["$.status"]
    }
  ],
  "unificationProposal": {
    "correlationId": {
      "canonicalName": "correlationId",
      "mappings": {"topic-name": "$.id"},
      "confidence": 0.9,
      "conflicts": []
    },
    "timestamp": {
      "canonicalName": "timestamp",
      "mappings": {"topic-name": "$.createdAt"},
      "confidence": 0.85,
      "conflicts": []
    },
    "status": {
      "canonicalName": "status",
      "mappings": {"topic-name": "$.status"},
      "confidence": 0.8,
      "conflicts": []
    },
    "amount": {
      "canonicalName": "amount",
      "mappings": {},
      "confidence": 0.0,
      "conflicts": []
    },
    "warnings": []
  },
  "warnings": []
}
""");

        return sb.toString();
    }

    private boolean isApiKeyMissing() {
        return claudeConfig.isApiKeyRequired() && !claudeConfig.isApiKeyConfigured();
    }

    private void appendJsonString(StringBuilder sb, String value) {
        if (value == null) {
            sb.append("null");
        } else {
            sb.append("\"").append(value.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "\\r")).append("\"");
        }
    }


    private FieldProfileResult parseProfileResult(String rawResponse) {
        if (rawResponse == null || rawResponse.isBlank()) {
            return new FieldProfileResult(
                List.of(),
                null,
                List.of("LLM returned an empty response.")
            );
        }

        String json = LlmJsonSupport.extractJsonPayload(rawResponse);

        try {
            return objectMapper.readValue(json, FieldProfileResult.class);
        } catch (Exception e) {
            log.error("Failed to parse LLM profiling response as FieldProfileResult: {}", e.getMessage());
            log.debug("Raw response was: {}", rawResponse);
            return new FieldProfileResult(
                List.of(),
                null,
                List.of("Failed to parse LLM response: " + e.getMessage())
            );
        }
    }
}
