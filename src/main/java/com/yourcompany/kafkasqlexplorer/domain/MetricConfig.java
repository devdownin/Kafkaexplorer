package com.yourcompany.kafkasqlexplorer.domain;

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
    String errorMessage
) {}
