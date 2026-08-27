// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors
package com.compagnonsdudev.kafkasqlexplorer.domain;

import java.util.List;
import java.util.Map;

public record MetricConfig(
    String id,
    String name,
    String type,
    String sql,
    String description,
    Double warningThreshold,
    Double criticalThreshold,
    Double lastValue,
    Long lastUpdateTime,
    String errorMessage,
    List<Double> history,
    /** Optional persisted summary for template-driven metrics and rich UI details. */
    Map<String, Object> lastSummary,
    /** Optional Flink DDL (CREATE TABLE IF NOT EXISTS …) executed before the metric SQL. */
    String createTableSql,
    /** Optional metric template identifier. When absent, the metric is treated as raw SQL. */
    String templateType,
    /** Free-form template parameters, typically SQL snippets, window sizes or matching keys. */
    Map<String, Object> templateParams,
    /** Execution mode for the metric runtime. */
    String executionMode,
    /** Optional Kafka topic used to resolve labels from the latest received message. */
    String labelTopic,
    /** Field paths extracted from the latest Kafka message and exported as Prometheus labels. */
    List<String> labelFields,
    /**
     * The rolling series of the values that <em>make</em> {@link #lastValue()}, one per name.
     *
     * <p>{@link #history()} carries the metric's own value, which for a two-query template is the
     * comparison and not the measurement: on a gap it is the difference, and what an operator needs
     * to see move is the two counts. The keys are summary keys the server chose
     * ({@code leftValue} / {@code rightValue}, {@code avgLatencyMs} / {@code p95LatencyMs} / …), so
     * a reader renders whatever it is given rather than knowing which template produced it.
     *
     * <p>Two invariants, and both matter to whoever draws it. Every series is exactly as long as
     * {@code history}, so index <em>i</em> is the same refresh in all of them. And a refresh that
     * did not produce a value for a series appends {@code null} rather than {@code 0} — a zero
     * would draw a fall that never happened, which is the shape this codebase refuses everywhere
     * else. A series is therefore drawn with gaps, not with holes in its alignment.
     */
    Map<String, List<Double>> componentHistory
) {
    /**
     * Backwards-compatible 18-arg constructor (no component series).
     *
     * <p>Same idiom as the two below, and for the same reason: a record has no default values, so
     * a new component would otherwise mean editing forty-three construction sites to write the
     * same empty map at each of them.
     */
    public MetricConfig(String id, String name, String type, String sql, String description,
                        Double warningThreshold, Double criticalThreshold, Double lastValue,
                        Long lastUpdateTime, String errorMessage, List<Double> history,
                        Map<String, Object> lastSummary, String createTableSql, String templateType,
                        Map<String, Object> templateParams, String executionMode, String labelTopic,
                        List<String> labelFields) {
        this(id, name, type, sql, description, warningThreshold, criticalThreshold, lastValue,
             lastUpdateTime, errorMessage, history, lastSummary, createTableSql, templateType,
             templateParams, executionMode, labelTopic, labelFields, Map.of());
    }

    /** Backwards-compatible 11-arg constructor (no DDL). */
    public MetricConfig(String id, String name, String type, String sql, String description,
                        Double warningThreshold, Double criticalThreshold, Double lastValue,
                        Long lastUpdateTime, String errorMessage) {
        this(id, name, type, sql, description, warningThreshold, criticalThreshold,
             lastValue, lastUpdateTime, errorMessage, List.of(), Map.of(), null, null, null, null, null, List.of(), Map.of());
    }

    /** Backwards-compatible 12-arg constructor (history, no DDL). */
    public MetricConfig(String id, String name, String type, String sql, String description,
                        Double warningThreshold, Double criticalThreshold, Double lastValue,
                        Long lastUpdateTime, String errorMessage, List<Double> history) {
        this(id, name, type, sql, description, warningThreshold, criticalThreshold,
             lastValue, lastUpdateTime, errorMessage, history, Map.of(), null, null, null, null, null, List.of(), Map.of());
    }
}
