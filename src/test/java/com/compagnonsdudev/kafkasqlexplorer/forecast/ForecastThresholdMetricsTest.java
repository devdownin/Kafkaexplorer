// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors
package com.compagnonsdudev.kafkasqlexplorer.forecast;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.compagnonsdudev.kafkasqlexplorer.domain.MetricConfig;
import com.compagnonsdudev.kafkasqlexplorer.service.MetricService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

class ForecastThresholdMetricsTest {
  private final ForecastPilotService pilot = mock(ForecastPilotService.class);
  private final MetricService metricService = mock(MetricService.class);
  @SuppressWarnings("unchecked")
  private final ObjectProvider<ForecastPilotService> provider = mock(ObjectProvider.class);
  private final SimpleMeterRegistry registry = new SimpleMeterRegistry();

  private static MetricConfig metric(String id, Double warning, Double critical) {
    return new MetricConfig(id, id, "GAUGE", "SELECT 1 AS metric_value", "", warning, critical, 1.0, 0L,
        null, List.of(), null, null, null, null, null, null, null, null);
  }

  private static ForecastPilotProperties.Series series(String seriesId, String metricId) {
    var s = mock(ForecastPilotProperties.Series.class);
    when(s.seriesId()).thenReturn(seriesId);
    when(s.metricId()).thenReturn(metricId);
    return s;
  }

  private static ForecastRecord record(SeriesPreparationProfile.Transformation transformation, double... medians) {
    var points = new java.util.ArrayList<MetricForecast.Point>();
    for (int i = 0; i < medians.length; i++) {
      points.add(new MetricForecast.Point(60_000L * (i + 1), medians[i], medians[i] - 10, medians[i], medians[i] + 10));
    }
    var forecast = new MetricForecast("r", "s", "v1", "i", "p", "ms", 0, "m", "1", "a", "median", 1, points);
    var context = mock(PreparedMetricSeries.class);
    when(context.profile()).thenReturn(new SeriesPreparationProfile(60_000, 128, transformation));
    return new ForecastRecord("k", 0, "READY", "TIMESFM", ForecastThresholdPolicy.Visibility.SHADOW, context,
        forecast, null, 0, 0, Map.of(), "ok", null);
  }

  @Test
  void publishesTheSecondsUntilTheReachAndNothingForARateOrAMetricWithoutThresholds() throws Exception {
    when(provider.getIfAvailable()).thenReturn(pilot);
    when(metricService.getAllMetrics()).thenReturn(List.of(
        metric("latency", 800.0, 1600.0), metric("orders", 100.0, 200.0), metric("plain", null, null)));
    // Built before stubbing: a mock created inside `thenReturn(…)` leaves the outer stubbing unfinished.
    var all = List.of(series("a", "latency"), series("b", "orders"), series("c", "plain"));
    var a = record(SeriesPreparationProfile.Transformation.GAUGE_MEAN, 700, 900, 1700);
    var b = record(SeriesPreparationProfile.Transformation.COUNTER_RATE, 500, 600);
    var c = record(SeriesPreparationProfile.Transformation.GAUGE_MEAN, 1e9);
    when(pilot.series()).thenReturn(all);
    when(pilot.get("a")).thenReturn(a);
    when(pilot.get("b")).thenReturn(b);
    when(pilot.get("c")).thenReturn(c);

    var metrics = new ForecastThresholdMetrics(provider, metricService, registry);
    var rows = metrics.rows(() -> 60_000L);
    assertThat(rows).hasSize(1);

    // Registered, the row reads the predicted instant against the clock: critical at 180 s, now 60 s.
    io.micrometer.core.instrument.MultiGauge.builder("t").register(registry).register(rows, true);
    var gauge = registry.find("t").tag("metric_id", "latency").tag("level", "critical").tag("likelihood", "expected").gauge();
    assertThat(gauge).isNotNull();
    assertThat(gauge.value()).isEqualTo(120.0);
  }

  @Test
  void publishesNothingWhenForecastingIsOff() {
    when(provider.getIfAvailable()).thenReturn(null);
    assertThat(new ForecastThresholdMetrics(provider, metricService, registry).rows(() -> 0L)).isEmpty();
  }

  @Test
  void oneUnreadableSeriesLeavesTheOthersPublished() throws Exception {
    when(provider.getIfAvailable()).thenReturn(pilot);
    when(metricService.getAllMetrics()).thenReturn(List.of(metric("x", 800.0, 1600.0), metric("y", 800.0, 1600.0)));
    var all = List.of(series("a", "x"), series("b", "y"));
    var b = record(SeriesPreparationProfile.Transformation.GAUGE_MEAN, 900);
    when(pilot.series()).thenReturn(all);
    when(pilot.get("a")).thenThrow(new IllegalStateException("decode failed"));
    when(pilot.get("b")).thenReturn(b);
    assertThat(new ForecastThresholdMetrics(provider, metricService, registry).rows(() -> 0L)).hasSize(1);
  }
}
