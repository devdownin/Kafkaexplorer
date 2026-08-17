// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors
package com.compagnonsdudev.kafkasqlexplorer.domain;

public enum MetricTemplateType {
    RAW_SQL,
    TOPIC_COUNT_DELTA,
    TOPIC_TRANSIT_LATENCY,
    /**
     * A consumer group's delay expressed in time rather than in records — no SQL involved: the
     * age of the oldest waiting record comes from committed offsets and record timestamps, which
     * no query over the topic can reach.
     */
    CONSUMER_TIME_LAG;

    public static MetricTemplateType fromValue(String value) {
        if (value == null || value.isBlank()) return RAW_SQL;
        return MetricTemplateType.valueOf(value.trim().toUpperCase());
    }
}
