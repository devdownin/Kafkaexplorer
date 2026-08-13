// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors
package com.compagnonsdudev.kafkasqlexplorer.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.compagnonsdudev.kafkasqlexplorer.config.ClaudeConfig;
import com.compagnonsdudev.kafkasqlexplorer.config.ProcessMiningConfig;
import com.compagnonsdudev.kafkasqlexplorer.domain.FieldProfileResult;
import com.compagnonsdudev.kafkasqlexplorer.domain.PayloadDigest;
import com.compagnonsdudev.kafkasqlexplorer.domain.PayloadShape;
import com.compagnonsdudev.kafkasqlexplorer.domain.SnapshotConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
public class FieldProfilingService {

    private static final Logger log = LoggerFactory.getLogger(FieldProfilingService.class);

    private final KafkaSnapshotReader snapshotReader;
    private final ClaudeConfig claudeConfig;
    private final ProcessMiningConfig processMiningConfig;
    private final PayloadDigestService payloadDigestService;
    private final LlmClient llmClient;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @org.springframework.beans.factory.annotation.Autowired
    public FieldProfilingService(KafkaSnapshotReader snapshotReader, ClaudeConfig claudeConfig,
                                  ProcessMiningConfig processMiningConfig,
                                  PayloadDigestService payloadDigestService) {
        this(snapshotReader, claudeConfig, processMiningConfig, payloadDigestService,
            LlmClientFactory.create(claudeConfig));
    }

    /** Test seam: defaults the ingestion tuning so unit tests only have to supply an LlmClient. */
    public FieldProfilingService(KafkaSnapshotReader snapshotReader, ClaudeConfig claudeConfig, LlmClient llmClient) {
        this(snapshotReader, claudeConfig, new ProcessMiningConfig(),
            new PayloadDigestService(new ProcessMiningConfig()), llmClient);
    }

    public FieldProfilingService(KafkaSnapshotReader snapshotReader, ClaudeConfig claudeConfig,
                                  ProcessMiningConfig processMiningConfig,
                                  PayloadDigestService payloadDigestService, LlmClient llmClient) {
        this.snapshotReader = snapshotReader;
        this.claudeConfig = claudeConfig;
        this.processMiningConfig = processMiningConfig;
        this.payloadDigestService = payloadDigestService;
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

        // 1. Read samples — 50 messages per topic, digested on the fly. Profiling only needs
        //    paths and example values, so a 1 MB document contributes its leaf paths and a
        //    bounded set of samples instead of a megabyte of prompt.
        SnapshotConfig samplingConfig = SnapshotConfig.latestN(50);
        List<PayloadDigest> digests = snapshotReader.readDigested(
            topics, samplingConfig, null, processMiningConfig.getMaxShapePaths());

        // 2. Group by topic
        Map<String, List<PayloadDigest>> byTopic = new LinkedHashMap<>();
        for (String topic : topics) {
            byTopic.put(topic, new ArrayList<>());
        }
        for (PayloadDigest digest : digests) {
            byTopic.computeIfAbsent(digest.topic(), k -> new ArrayList<>()).add(digest);
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

    /**
     * Builds the profiling prompt from digests: the structure of each topic (leaf paths, array
     * cardinalities) plus a few example values per path. That is exactly what role inference
     * needs, and it stays bounded whatever the payload size.
     */
    private String buildProfilingPrompt(List<String> topics, Map<String, List<PayloadDigest>> byTopic) {
        StringBuilder sb = new StringBuilder();
        sb.append("## TOPICS À ANALYSER\n");
        for (String topic : topics) {
            sb.append("- ").append(topic).append("\n");
        }
        sb.append("""

## CHAMPS OBSERVÉS PAR TOPIC
Les payloads ne sont pas fournis bruts : pour chaque topic tu reçois les structures
observées (chemins des feuilles, index de tableau réduits à []) puis, pour chaque chemin,
quelques valeurs d'exemple. Les chemins sont en notation pointée ; réponds en JSONPath
($.chemin).
""");

        int perTopicBudget = Math.max(4_000,
            processMiningConfig.getPromptCharBudget() / Math.max(1, byTopic.size()));

        for (Map.Entry<String, List<PayloadDigest>> entry : byTopic.entrySet()) {
            List<PayloadDigest> digests = entry.getValue();
            long maxBytes = digests.stream().mapToLong(PayloadDigest::payloadBytes).max().orElse(0);
            String format = digests.isEmpty() ? "?" : digests.get(0).format();

            sb.append("\n### Topic: ").append(entry.getKey())
              .append(" — ").append(digests.size()).append(" message(s) échantillonné(s), format ")
              .append(format).append(", payload max ").append(maxBytes).append(" octets\n");

            if (digests.isEmpty()) {
                sb.append("(aucun message)\n");
                continue;
            }

            Set<String> shapeIds = new LinkedHashSet<>();
            Map<String, Integer> arrayCounts = new LinkedHashMap<>();
            Map<String, Set<String>> valuesByPath = new LinkedHashMap<>();
            for (PayloadDigest digest : digests) {
                if (digest.shapeId() != null) {
                    shapeIds.add(digest.shapeId());
                }
                digest.arrayCounts().forEach((path, count) -> arrayCounts.merge(path, count, Math::max));
                collectValues(valuesByPath, digest.fields());
                collectValues(valuesByPath, digest.sample());
                if (digest.preview() != null) {
                    valuesByPath.computeIfAbsent("(payload brut)", k -> new LinkedHashSet<>())
                        .add(digest.preview());
                }
            }

            List<PayloadShape> shapes = payloadDigestService.shapesFor(shapeIds);
            if (!shapes.isEmpty()) {
                sb.append("#### Structures (").append(shapes.size()).append(")\n");
                for (PayloadShape shape : shapes) {
                    sb.append(shape.toPromptBlock(processMiningConfig.getMaxShapePathsInPrompt()));
                }
            }

            sb.append("#### Valeurs d'exemple par chemin\n");
            int budgetStart = sb.length();
            for (Map.Entry<String, Set<String>> field : valuesByPath.entrySet()) {
                if (sb.length() - budgetStart > perTopicBudget) {
                    sb.append("  … (chemins suivants omis, budget atteint)\n");
                    break;
                }
                sb.append("  ").append(field.getKey()).append(" = [");
                boolean first = true;
                for (String value : field.getValue()) {
                    if (!first) sb.append(", ");
                    appendJsonString(sb, value);
                    first = false;
                }
                sb.append("]\n");
            }

            if (!arrayCounts.isEmpty()) {
                sb.append("#### Cardinalité des tableaux\n");
                arrayCounts.forEach((path, count) ->
                    sb.append("  ").append(path).append(" -> ").append(count).append(" élément(s) max\n"));
            }
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

    /** Keeps up to 4 distinct example values per path — enough to infer a role, bounded in size. */
    private void collectValues(Map<String, Set<String>> valuesByPath, Map<String, String> values) {
        if (values == null) {
            return;
        }
        values.forEach((path, value) -> {
            Set<String> examples = valuesByPath.computeIfAbsent(path, k -> new LinkedHashSet<>());
            if (examples.size() < 4) {
                examples.add(value);
            }
        });
    }

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
