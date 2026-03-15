package com.yourcompany.kafkasqlexplorer.domain;

import java.util.List;

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
    List<Double> history
) {
    public MetricConfig(String id, String name, String type, String sql, String description,
                        Double warningThreshold, Double criticalThreshold, Double lastValue,
                        Long lastUpdateTime, String errorMessage) {
        this(id, name, type, sql, description, warningThreshold, criticalThreshold, lastValue, lastUpdateTime, errorMessage, List.of());
    }
}
