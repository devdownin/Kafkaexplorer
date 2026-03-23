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
import java.util.stream.Collectors;

@Service
public class FieldProfilingService {

    private static final Logger log = LoggerFactory.getLogger(FieldProfilingService.class);

    private final KafkaSnapshotReader snapshotReader;
    private final ClaudeConfig claudeConfig;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public FieldProfilingService(KafkaSnapshotReader snapshotReader, ClaudeConfig claudeConfig) {
        this.snapshotReader = snapshotReader;
        this.claudeConfig = claudeConfig;
    }

    public FieldProfileResult profile(List<String> topics, SnapshotConfig config) {
        if (claudeConfig.getApiKey() == null || claudeConfig.getApiKey().isBlank()) {
            log.warn("Claude API key not configured — returning empty profile");
            return new FieldProfileResult(
                List.of(),
                null,
                List.of("Claude API key not configured. Set ANTHROPIC_API_KEY environment variable.")
            );
        }

        // 1. Read samples — 50 messages per topic
        SnapshotConfig samplingConfig = SnapshotConfig.latestN(50 * topics.size());
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

        // 4. Call Claude API
        String rawResponse;
        try {
            rawResponse = callClaude(userPrompt);
        } catch (Exception e) {
            log.error("Claude API call failed during profiling: {}", e.getMessage(), e);
            return new FieldProfileResult(
                List.of(),
                null,
                List.of("Claude API error: " + e.getMessage())
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

    private void appendJsonString(StringBuilder sb, String value) {
        if (value == null) {
            sb.append("null");
        } else {
            sb.append("\"").append(value.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "\\r")).append("\"");
        }
    }

    private String callClaude(String userPrompt) throws Exception {
        AnthropicClient client = AnthropicOkHttpClient.builder()
            .apiKey(claudeConfig.getApiKey())
            .build();

        String systemPrompt = """
            Tu es un expert Apache Kafka et data mining.
            Analyse des échantillons de messages Kafka.
            Réponds UNIQUEMENT avec un JSON valide, sans markdown.
            """;

        MessageCreateParams params = MessageCreateParams.builder()
            .model(claudeConfig.getModel())
            .maxTokens((long) claudeConfig.getMaxTokens())
            .system(systemPrompt)
            .addMessage(MessageParam.builder()
                .role(MessageParam.Role.USER)
                .content(userPrompt)
                .build())
            .build();

        String fullText;
        try (var stream = client.messages().createStreaming(params)) {
            fullText = stream.stream()
                .flatMap(e -> e.contentBlockDelta().stream())
                .flatMap(d -> d.delta().text().stream())
                .map(TextDelta::text)
                .collect(Collectors.joining());
        }

        log.debug("Claude profiling raw response (first 500 chars): {}",
            fullText.length() > 500 ? fullText.substring(0, 500) : fullText);

        if (fullText.isBlank()) {
            log.warn("Claude returned a blank response for model={} maxTokens={}",
                claudeConfig.getModel(), claudeConfig.getMaxTokens());
        }
        return fullText;
    }

    private FieldProfileResult parseProfileResult(String rawResponse) {
        if (rawResponse == null || rawResponse.isBlank()) {
            return new FieldProfileResult(
                List.of(),
                null,
                List.of("Claude returned an empty response.")
            );
        }

        // Strip potential markdown fences
        String json = stripMarkdownFences(rawResponse);

        try {
            return objectMapper.readValue(json, FieldProfileResult.class);
        } catch (Exception e) {
            log.error("Failed to parse Claude profiling response as FieldProfileResult: {}", e.getMessage());
            log.debug("Raw response was: {}", rawResponse);
            return new FieldProfileResult(
                List.of(),
                null,
                List.of("Failed to parse Claude response: " + e.getMessage())
            );
        }
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
