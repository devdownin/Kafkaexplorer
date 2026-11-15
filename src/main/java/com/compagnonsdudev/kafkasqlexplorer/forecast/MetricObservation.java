// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors
package com.compagnonsdudev.kafkasqlexplorer.forecast;

import com.compagnonsdudev.kafkasqlexplorer.domain.MetricConfig;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;

/** An immutable collection result, not a sample reconstructed from the UI's rolling history. */
public record MetricObservation(
    String observationId, String seriesId, String clusterId, String metricId,
    String definitionVersion, String component, Map<String, String> labels,
    String unit, String semanticKind, String collectorRunId, long observedAt,
    Double value, String qualityState, Map<String, Object> coverage
) {
    private static final ObjectMapper MAPPER = new ObjectMapper()
        .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);

    public MetricObservation {
        if (value != null && !Double.isFinite(value)) throw new IllegalArgumentException("Non-finite observation");
        labels = Map.copyOf(labels);
        // Deep copy: template summaries may be changed by a later refresh while the writer is queued.
        coverage = java.util.Collections.unmodifiableMap(MAPPER.convertValue(coverage, Map.class));
    }

    public static MetricObservation create(String clusterId, String metricId, String version,
        String component, Map<String, String> labels, String unit, String kind,
        String runId, long observedAt, Double value, String quality, Map<String, Object> coverage) {
        String series = seriesId(clusterId, version, metricId, component, labels);
        return new MetricObservation(digest(java.util.List.of(series, runId)), series, clusterId,
            metricId, version, component, labels, unit, kind, runId, observedAt, value, quality, coverage);
    }

    public static String seriesId(String cluster, String version, String metric, String component,
                                  Map<String, String> labels) {
        return digest(java.util.List.of(cluster, version, metric, component, new TreeMap<>(labels)));
    }

    /** Only semantics participate: names, descriptions, thresholds and runtime values do not. */
    /**
     * The kind an observation of this metric is recorded under, which is what preparation checks a
     * transformation against. Produced offsets are published as a {@code GAUGE} — that template
     * accepts no other type — but they only ever grow, and the forecast assistant reads them as a
     * {@code COUNTER_RATE}. Recording them under the metric's type made every point of such a
     * series "incompatible" with the transformation the assistant itself chose: the series was
     * approved, then refused as {@code INVALID_DATA} forever, and the produce-rate covariate with it.
     */
    public static String semanticKind(MetricConfig m) {
        if ("KAFKA_CLUSTER_COUNT".equals(m.templateType()) && m.templateParams() != null
            && "PRODUCED_OFFSETS".equals(m.templateParams().get("measurement"))) return "COUNTER";
        return m.type() == null ? "GAUGE" : m.type();
    }

    /**
     * The unit an observation of this metric carries, inferred from its template where the template
     * fixes it. It was read from {@code templateParams.unit} alone, which no editor writes, so every
     * count metric was captured as {@code UNKNOWN} and refused by the forecast assistant with a
     * blocker its operator had no field to resolve. An explicit unit still wins where the template
     * leaves the choice open; latency templates are milliseconds whatever the parameters say.
     */
    public static String unit(MetricConfig m) {
        String template = String.valueOf(m.templateType());
        if (template.equals("CONSUMER_TIME_LAG") || template.equals("TOPIC_TRANSIT_LATENCY")) return "milliseconds";
        Map<String, Object> p = m.templateParams() == null ? Map.of() : m.templateParams();
        if (p.get("unit") instanceof String u && !u.isBlank()) return u;
        return switch (template) {
            case "KAFKA_CLUSTER_COUNT" -> switch (String.valueOf(p.get("measurement"))) {
                case "BROKER_COUNT" -> "brokers";
                case "PRODUCED_OFFSETS" -> "messages";
                default -> "topics";
            };
            case "TOPIC_COUNT_DELTA" -> switch (String.valueOf(p.getOrDefault("operation", "LEFT_MINUS_RIGHT"))
                    .toUpperCase(java.util.Locale.ROOT)) {
                case "RATIO" -> "ratio";
                case "PERCENT_GAP" -> "percent";
                default -> "records";
            };
            default -> "UNKNOWN";
        };
    }

    public static String definitionVersion(MetricConfig m) {
        Map<String, Object> semantic = new LinkedHashMap<>();
        semantic.put("format", 1);
        semantic.put("type", m.type());
        semantic.put("sql", m.sql());
        semantic.put("ddl", m.createTableSql());
        semantic.put("template", m.templateType());
        semantic.put("params", m.templateParams());
        semantic.put("execution", m.executionMode());
        semantic.put("labelTopic", m.labelTopic());
        semantic.put("labelFields", m.labelFields());
        return digest(semantic);
    }

    public static String collectedVersion(MetricConfig metric, String endpoint, String collector) {
        return digest(java.util.List.of(definitionVersion(metric), endpoint == null ? "" : endpoint, collector));
    }

    static String digest(Object input) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(MAPPER.writeValueAsString(input).getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalArgumentException("Cannot identify observation", e);
        }
    }
}
