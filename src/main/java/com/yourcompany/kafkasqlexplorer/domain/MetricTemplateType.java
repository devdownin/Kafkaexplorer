// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors
package com.yourcompany.kafkasqlexplorer.domain;

public enum MetricTemplateType {
    RAW_SQL,
    TOPIC_COUNT_DELTA,
    TOPIC_TRANSIT_LATENCY;

    public static MetricTemplateType fromValue(String value) {
        if (value == null || value.isBlank()) return RAW_SQL;
        return MetricTemplateType.valueOf(value.trim().toUpperCase());
    }
}
