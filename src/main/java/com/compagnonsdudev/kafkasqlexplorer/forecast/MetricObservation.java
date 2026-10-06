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
