// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors
package com.compagnonsdudev.kafkasqlexplorer.forecast;

import static org.junit.jupiter.api.Assertions.*;

import com.compagnonsdudev.kafkasqlexplorer.domain.MetricConfig;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * The forecast demo's five metrics are defined in {@code scripts/demo-forecast/seed.sh}, as JSON, and
 * their whole point is that the Metrics page offers a forecast for them. Whether it does is decided
 * here, by {@link ForecastEligibility}, and by what the journal records as forecastable — which a
 * shell script cannot see. A first version of the demo was built on a transit latency: it parsed,
 * it saved, and it could never have produced a forecast, because the journal records a latency as a
 * limited-scope measurement. This reads the definitions the script really posts, through the real rule.
 */
class DemoForecastMetricsTest {
  private static final Pattern BODY = Pattern.compile("<<'JSON'\\n(.*?)\\nJSON", Pattern.DOTALL);

  private static java.util.List<MetricConfig> demoMetrics() throws Exception {
    String script = Files.readString(Path.of("scripts/demo-forecast/seed.sh"));
    var metrics = new ArrayList<MetricConfig>();
    var matcher = BODY.matcher(script);
    while (matcher.find()) metrics.add(new ObjectMapper().readValue(matcher.group(1), MetricConfig.class));
    return metrics;
  }

  @Test
  void everyDemoMetricIsOfferedAForecastAndNeverBlockedFromOne() throws Exception {
    var metrics = demoMetrics();
    assertEquals(
        java.util.List.of(
            "demo-live-unshipped", "demo-live-backlog", "demo-live-creep", "demo-live-jitter", "demo-live-shift"),
        metrics.stream().map(MetricConfig::id).toList());
    for (var m : metrics) {
      assertEquals(java.util.List.of(), ForecastEligibility.blockers(m), m.id());
      assertEquals("records", MetricObservation.unit(m), m.id());
      // A count of two topics' offsets is the shape the journal records as complete; a count read
      // record by record is bounded and never forecast (see ForecastEligibility).
      assertEquals("TOPIC_COUNT_DELTA", m.templateType(), m.id());
      assertEquals("OFFSETS", m.templateParams().get("countBy"), m.id());
      assertEquals("GAUGE", m.type(), m.id());
    }
  }

  @Test
  void theirThresholdsStandOnTheirOwnSide() throws Exception {
    for (var m : demoMetrics()) {
      assertNotNull(m.warningThreshold(), m.id());
      assertNotNull(m.criticalThreshold(), m.id());
      // Critical above warning: both are read upwards, which is what the demo's curves approach.
      assertTrue(m.criticalThreshold() > m.warningThreshold(), m.id());
    }
  }
}
