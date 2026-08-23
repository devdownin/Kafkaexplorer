// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors
package com.compagnonsdudev.kafkasqlexplorer.service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The shapes the two LLM calls are expected to return, as JSON Schema.
 *
 * <p>Until now the format was a request inside the prompt — "Return ONLY valid JSON. NO markdown" —
 * and {@link LlmJsonSupport} existed to dig the object back out of whatever prose or ``` fence the
 * model wrapped it in. When that failed the user got a parse error. Asking is not constraining: a
 * schema sent with the request makes the provider's decoder unable to emit anything else, which
 * removes the failure instead of reporting it better. It matters most on the small models this
 * application is routinely pointed at — a 4-billion-parameter local one, or whichever cheap slug
 * an operator picks on OpenRouter — which are far likelier than a frontier model to editorialise
 * around the JSON they were asked for.
 *
 * <p>The schemas stay deliberately shallow. Constrained decoding narrows the model's choices at
 * every token, so an over-specified schema (enums on every field, minimums, nested requirements)
 * buys validation we do not need and costs quality on the part we do — the prose inside
 * {@code description} and the Mermaid inside {@code flowchart}. What is pinned is the skeleton the
 * parser depends on; the analysis itself is left free.
 *
 * <p>{@code additionalProperties: false} everywhere is not decoration either: it is required by the
 * strict mode of both the Anthropic and OpenAI-compatible implementations.
 */
final class LlmSchemas {

    private LlmSchemas() {
    }

    /** The analysis result — {@code ProcessMiningResult} minus the fields the server fills in. */
    static Map<String, Object> processMiningResult() {
        Map<String, Object> anomaly = object(
            Map.of(
                "id", string("Stable identifier, e.g. ANO-001"),
                "topic", string("The Kafka topic the anomaly was observed on"),
                "type", enumeration("SEQUENCE", "TEMPORAL", "STRUCTURAL", "CARDINALITY", "BUSINESS"),
                "severity", enumeration("CRITICAL", "MAJOR", "MINOR"),
                "fields", array(string("JSONPath of an involved field")),
                "description", string("What was observed"),
                "probableCause", string("Most likely cause"),
                "ksqlSuggestion", string("A Flink/KSQL statement that would surface it")
            ),
            List.of("id", "topic", "type", "severity", "fields", "description",
                "probableCause", "ksqlSuggestion"));

        return object(
            Map.of(
                "flowchart", string("A Mermaid flowchart, starting with 'flowchart TD'"),
                "comments", string("Short narrative describing the flow"),
                "hypotheses", array(string("A hypothesis about the underlying architecture")),
                "blindSpots", array(string("Something the sampled data could not show")),
                "anomalies", array(anomaly)
            ),
            List.of("flowchart", "comments", "hypotheses", "blindSpots", "anomalies"));
    }

    /** The profiling result — {@code FieldProfileResult}. */
    static Map<String, Object> fieldProfileResult() {
        Map<String, Object> field = object(
            Map.of(
                "path", string("JSONPath of the field, e.g. $.orderId"),
                "sampleValues", array(string("An observed value")),
                "inferredType", enumeration("STRING", "NUMBER", "BOOLEAN", "DATE", "UUID", "ENUM"),
                "semanticRole", enumeration("CORRELATION_ID", "TIMESTAMP", "STATUS", "AMOUNT",
                    "ACTOR", "UNKNOWN"),
                "confidence", number("0..1"),
                "reasoning", string("Why this role was inferred")
            ),
            List.of("path", "sampleValues", "inferredType", "semanticRole", "confidence", "reasoning"));

        Map<String, Object> topic = object(
            Map.of(
                "name", string("Topic name"),
                "format", string("JSON, XML, AVRO or AUTO"),
                "fields", array(field),
                "candidateCorrelationKeys", array(string("JSONPath")),
                "candidateTimestamps", array(string("JSONPath")),
                "candidateStatuses", array(string("JSONPath"))
            ),
            List.of("name", "format", "fields", "candidateCorrelationKeys",
                "candidateTimestamps", "candidateStatuses"));

        Map<String, Object> entry = object(
            Map.of(
                "canonicalName", string("Unified name for this role"),
                "mappings", objectOfStrings("Topic name -> JSONPath in that topic"),
                "confidence", number("0..1"),
                "conflicts", array(string("A disagreement between topics"))
            ),
            List.of("canonicalName", "mappings", "confidence", "conflicts"));

        Map<String, Object> proposal = object(
            Map.of(
                "correlationId", entry,
                "timestamp", entry,
                "status", entry,
                "amount", entry,
                "warnings", array(string("Something the proposal could not settle"))
            ),
            List.of("correlationId", "timestamp", "status", "amount", "warnings"));

        return object(
            Map.of(
                "topics", array(topic),
                "unificationProposal", proposal,
                "warnings", array(string("Something the profiling could not settle"))
            ),
            List.of("topics", "unificationProposal", "warnings"));
    }

    private static Map<String, Object> object(Map<String, Object> properties, List<String> required) {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        // LinkedHashMap so the rendered schema is byte-stable across calls: an unordered map would
        // change the request bytes per process and defeat any prompt caching upstream.
        schema.put("properties", new LinkedHashMap<>(new java.util.TreeMap<>(properties)));
        schema.put("required", required);
        schema.put("additionalProperties", false);
        return schema;
    }

    private static Map<String, Object> array(Map<String, Object> items) {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "array");
        schema.put("items", items);
        return schema;
    }

    private static Map<String, Object> string(String description) {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "string");
        schema.put("description", description);
        return schema;
    }

    private static Map<String, Object> number(String description) {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "number");
        schema.put("description", description);
        return schema;
    }

    private static Map<String, Object> enumeration(String... values) {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "string");
        schema.put("enum", List.of(values));
        return schema;
    }

    /**
     * A map keyed by topic name. The key set is not knowable in advance, which is the one place
     * {@code additionalProperties} has to stay open rather than false.
     */
    private static Map<String, Object> objectOfStrings(String description) {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("description", description);
        schema.put("additionalProperties", Map.of("type", "string"));
        return schema;
    }
}
