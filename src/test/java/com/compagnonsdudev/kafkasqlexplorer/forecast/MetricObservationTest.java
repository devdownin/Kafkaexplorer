// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors
package com.compagnonsdudev.kafkasqlexplorer.forecast;

import com.compagnonsdudev.kafkasqlexplorer.domain.MetricConfig;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class MetricObservationTest {
    private MetricConfig metric(String name, String sql, Map<String, Object> params) {
        return new MetricConfig("m1", name, "GAUGE", sql, null, null, null,
            null, null, null, java.util.List.of(), Map.of(), null, "RAW_SQL", params,
            "TEMPLATE_BOUNDED_SCAN", null, java.util.List.of());
    }

    @Test
    void identityIsCanonicalButNeverCollapsesDistinctLabels() {
        var a = new LinkedHashMap<String, String>();
        a.put("topic", "orders"); a.put("group", "billing");
        var b = new LinkedHashMap<String, String>();
        b.put("group", "billing"); b.put("topic", "orders");
        assertEquals(MetricObservation.seriesId("cluster", "version", "m1", "value", a),
            MetricObservation.seriesId("cluster", "version", "m1", "value", b));
        assertNotEquals(MetricObservation.seriesId("cluster", "version", "m1", "value", Map.of("x", "a|y=b")),
            MetricObservation.seriesId("cluster", "version", "m1", "value", Map.of("x", "a", "y", "b")));
        assertNotEquals(MetricObservation.seriesId("cluster-a", "v1", "m1", "value", a),
            MetricObservation.seriesId("cluster-b", "v1", "m1", "value", a));
    }

    @Test
    void semanticEditsStartANewVersionButPresentationEditsDoNot() {
        var a = metric("First name", "SELECT 1 AS metric_value", Map.of("window", 60, "unit", "messages"));
        var reordered = new LinkedHashMap<String, Object>();
        reordered.put("unit", "messages"); reordered.put("window", 60);
        assertEquals(MetricObservation.definitionVersion(a),
            MetricObservation.definitionVersion(metric("Renamed", a.sql(), reordered)));
        assertNotEquals(MetricObservation.definitionVersion(a),
            MetricObservation.definitionVersion(metric("First name", "SELECT 2 AS metric_value", a.templateParams())));
        assertNotEquals(MetricObservation.definitionVersion(a),
            MetricObservation.definitionVersion(metric("First name", a.sql(), Map.of("window", 120, "unit", "messages"))));
    }

    private static MetricConfig templated(String template, Map<String, Object> params) {
        return new MetricConfig("m1", "n", "GAUGE", null, null, null, null, null, null, null,
            java.util.List.of(), Map.of(), null, template, params, "TEMPLATE_BOUNDED_SCAN", null, java.util.List.of());
    }

    @Test
    void theUnitIsTheOneTheTemplateFixesAndAnExplicitOneFillsTheRest() {
        assertEquals("topics", MetricObservation.unit(templated("KAFKA_CLUSTER_COUNT", Map.of("measurement", "TOPIC_COUNT"))));
        assertEquals("brokers", MetricObservation.unit(templated("KAFKA_CLUSTER_COUNT", Map.of("measurement", "BROKER_COUNT"))));
        assertEquals("records", MetricObservation.unit(templated("TOPIC_COUNT_DELTA", Map.of())));
        assertEquals("records", MetricObservation.unit(templated("TOPIC_COUNT_DELTA", Map.of("operation", "abs_diff"))));
        assertEquals("ratio", MetricObservation.unit(templated("TOPIC_COUNT_DELTA", Map.of("operation", "RATIO"))));
        assertEquals("percent", MetricObservation.unit(templated("TOPIC_COUNT_DELTA", Map.of("operation", "PERCENT_GAP"))));
        assertEquals("milliseconds", MetricObservation.unit(templated("CONSUMER_TIME_LAG", Map.of("unit", "seconds"))));
        assertEquals("messages", MetricObservation.unit(templated("TOPIC_COUNT_DELTA", Map.of("unit", "messages"))));
        assertEquals("UNKNOWN", MetricObservation.unit(templated("RAW_SQL", Map.of())));
        assertEquals("UNKNOWN", MetricObservation.unit(templated(null, null)));
    }
}
