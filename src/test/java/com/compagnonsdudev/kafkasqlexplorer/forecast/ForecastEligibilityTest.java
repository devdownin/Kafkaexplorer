// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors
package com.compagnonsdudev.kafkasqlexplorer.forecast;

import static org.junit.jupiter.api.Assertions.*;

import com.compagnonsdudev.kafkasqlexplorer.domain.MetricConfig;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ForecastEligibilityTest {
  private static MetricConfig delta(String labelTopic, List<String> labelFields) {
    return new MetricConfig("m", "Gap", "GAUGE", null, null, null, null, null, null, null, List.of(), Map.of(),
        null, "TOPIC_COUNT_DELTA", Map.of("leftTopic", "orders", "rightTopic", "orders.dlq"),
        "TEMPLATE_BOUNDED_SCAN", labelTopic, labelFields);
  }

  @Test
  void aSourceTopicWithNoLabelFieldIsNotALabelledMetric() {
    // Every metric the suggestions create names its source here, with no field to read from it.
    assertEquals(List.of(), ForecastEligibility.blockers(delta("orders", List.of())));
  }

  @Test
  void labelFieldsStillRefuseTheMetric() {
    assertEquals(
        List.of("Labelled metrics require a separately approved series; the assistant supports unlabelled values"),
        ForecastEligibility.blockers(delta("orders", List.of("region"))));
  }
}
