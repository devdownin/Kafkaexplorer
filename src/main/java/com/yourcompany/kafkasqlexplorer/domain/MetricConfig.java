// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors
package com.yourcompany.kafkasqlexplorer.domain;

import lombok.Builder;
import java.util.List;
import java.util.Map;

@Builder
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
    List<String> labelFields
) {
    /** Backwards-compatible 11-arg constructor (no DDL). */
    public MetricConfig(String id, String name, String type, String sql, String description,
                        Double warningThreshold, Double criticalThreshold, Double lastValue,
                        Long lastUpdateTime, String errorMessage) {
        this(id, name, type, sql, description, warningThreshold, criticalThreshold,
             lastValue, lastUpdateTime, errorMessage, List.of(), Map.of(), null, null, null, null, null, List.of());
    }

    /** Backwards-compatible 12-arg constructor (history, no DDL). */
    public MetricConfig(String id, String name, String type, String sql, String description,
                        Double warningThreshold, Double criticalThreshold, Double lastValue,
                        Long lastUpdateTime, String errorMessage, List<Double> history) {
        this(id, name, type, sql, description, warningThreshold, criticalThreshold,
             lastValue, lastUpdateTime, errorMessage, history, Map.of(), null, null, null, null, null, List.of());
    }
}
