// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors
package com.yourcompany.kafkasqlexplorer.domain;

import lombok.Builder;
import java.util.List;

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
    /** Optional Flink DDL (CREATE TABLE IF NOT EXISTS …) executed before the metric SQL. */
    String createTableSql
) {
    /** Backwards-compatible 11-arg constructor (no DDL). */
    public MetricConfig(String id, String name, String type, String sql, String description,
                        Double warningThreshold, Double criticalThreshold, Double lastValue,
                        Long lastUpdateTime, String errorMessage) {
        this(id, name, type, sql, description, warningThreshold, criticalThreshold,
             lastValue, lastUpdateTime, errorMessage, List.of(), null);
    }

    /** Backwards-compatible 12-arg constructor (history, no DDL). */
    public MetricConfig(String id, String name, String type, String sql, String description,
                        Double warningThreshold, Double criticalThreshold, Double lastValue,
                        Long lastUpdateTime, String errorMessage, List<Double> history) {
        this(id, name, type, sql, description, warningThreshold, criticalThreshold,
             lastValue, lastUpdateTime, errorMessage, history, null);
    }
}
