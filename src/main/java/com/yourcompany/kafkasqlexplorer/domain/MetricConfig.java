package com.yourcompany.kafkasqlexplorer.domain;

public record MetricConfig(
    String id,
    String name,
    String type,
    String sql,
    String description
) {}
