// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors
package com.compagnonsdudev.kafkasqlexplorer.forecast;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.compagnonsdudev.kafkasqlexplorer.domain.MetricConfig;
import com.compagnonsdudev.kafkasqlexplorer.mcp.McpProperties;
import com.compagnonsdudev.kafkasqlexplorer.mcp.contract.StopReason;
import com.compagnonsdudev.kafkasqlexplorer.mcp.guard.DlpScrubber;
import com.compagnonsdudev.kafkasqlexplorer.mcp.guard.McpToolException;
import com.compagnonsdudev.kafkasqlexplorer.mcp.guard.ToolGuard;
import com.compagnonsdudev.kafkasqlexplorer.mcp.tools.ForecastMcpTools;
import com.compagnonsdudev.kafkasqlexplorer.mcp.tools.ForecastMcpTools.ReachOutcome;
import com.compagnonsdudev.kafkasqlexplorer.service.MetricService;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * {@code kex_list_forecast_threshold_reaches}: the card's reading for an agent. Every series gets a
 * row that says why, and a series with nothing to read is unknown, never "no reach".
 */
class ForecastThresholdReachMcpToolTest {
  private final ForecastPilotService pilot = mock(ForecastPilotService.class);
  private final MetricService metricService = mock(MetricService.class);

  private static ForecastPilotProperties.Series series(String id, String metricId,
                                                       SeriesPreparationProfile.Transformation transformation) {
    return new ForecastPilotProperties.Series(id, metricId, "production", "v1", "ms", List.of("orders"), List.of(),
        new SeriesPreparationProfile(60_000, 512, transformation), 2, 1, null, null, .8, 2);
  }

  private static MetricConfig metric(String id, Double warning, Double critical) {
    return new MetricConfig(id, id, "GAUGE", "SELECT 1 AS metric_value", "", warning, critical, 1.0, 0L,
        null, List.of(), null, null, null, null, null, null, null, null);
  }

  private static ForecastRecord record(ForecastPilotProperties.Series s, double... medians) {
    var points = new ArrayList<MetricForecast.Point>();
    long now = System.currentTimeMillis();
    for (int i = 0; i < medians.length; i++) {
      points.add(new MetricForecast.Point(now + 60_000L * (i + 1), medians[i], medians[i] - 10, medians[i], medians[i] + 10));
    }
    var forecast = new MetricForecast("r", s.seriesId(), "v1", "i", "p", "ms", now, "m", "1", "a", "median", 1, points);
    var context = new PreparedMetricSeries(PreparedMetricSeries.Status.READY, "ready", s.seriesId(), "v1", "ms", "ms",
        0, now, s.profile(), "p", "i", 512, 0, 0, List.of());
    return new ForecastRecord("k", now, "READY", "TIMESFM", ForecastThresholdPolicy.Visibility.SHADOW, context,
        forecast, null, 0, 0, Map.of(), "ok", null);
  }

  private ForecastMcpTools tools(MetricService metrics) {
    var policy = new McpProperties();
    policy.setAllowedForecastEnvironments(List.of("production"));
    return new ForecastMcpTools(pilot, new ToolGuard(policy, new DlpScrubber(policy)), metrics);
  }

  @Test
  void readsEverySeriesAgainstItsMetricsOwnThresholdsAndSaysWhyWhenItCannot() throws Exception {
    var reaches = series("a".repeat(64), "latency", SeriesPreparationProfile.Transformation.GAUGE_MEAN);
    var calm = series("b".repeat(64), "volume", SeriesPreparationProfile.Transformation.GAUGE_MEAN);
    var rate = series("c".repeat(64), "orders", SeriesPreparationProfile.Transformation.COUNTER_RATE);
    var bare = series("d".repeat(64), "plain", SeriesPreparationProfile.Transformation.GAUGE_MEAN);
    var all = List.of(reaches, calm, rate, bare);
    var a = record(reaches, 700, 900, 1700);
    var b = record(calm, 10, 20);
    var c = record(rate, 500, 600);
    when(pilot.series()).thenReturn(all);
    for (var s : all) when(pilot.resolve(s.seriesId())).thenReturn(s);
    when(pilot.get(reaches.seriesId())).thenReturn(a);
    when(pilot.get(calm.seriesId())).thenReturn(b);
    when(pilot.get(rate.seriesId())).thenReturn(c);
    when(metricService.getAllMetrics()).thenReturn(List.of(metric("latency", 800.0, 1600.0),
        metric("volume", 800.0, 1600.0), metric("orders", 100.0, 200.0), metric("plain", null, null)));

    var result = tools(metricService).thresholdReaches();
    var rows = result.data();
    assertThat(rows).extracting(ForecastMcpTools.ThresholdReach::outcome).containsExactly(
        ReachOutcome.REACH, ReachOutcome.NO_REACH, ReachOutcome.NOT_COMPARABLE, ReachOutcome.NO_THRESHOLDS);
    var reach = rows.get(0);
    assertThat(reach.level()).isEqualTo("critical");
    assertThat(reach.likelihood()).isEqualTo("expected");
    assertThat(reach.threshold()).isEqualTo(1600.0);
    assertThat(reach.inSeconds()).isBetween(170L, 180L);
    assertThat(result.coverage().stopReason()).isEqualTo(StopReason.EXHAUSTED);
  }

  @Test
  void aSeriesWithNoForecastYetIsUnknownNotNegative() throws Exception {
    var s = series("a".repeat(64), "latency", SeriesPreparationProfile.Transformation.GAUGE_MEAN);
    var all = List.of(s);
    when(pilot.series()).thenReturn(all);
    when(pilot.resolve(s.seriesId())).thenReturn(s);
    when(pilot.get(s.seriesId())).thenReturn(null);
    when(pilot.absenceReason(s.seriesId())).thenReturn("No forecast has been persisted yet");
    when(metricService.getAllMetrics()).thenReturn(List.of(metric("latency", 800.0, 1600.0)));

    var result = tools(metricService).thresholdReaches();
    assertThat(result.data()).singleElement().satisfies(r -> {
      assertThat(r.outcome()).isEqualTo(ReachOutcome.NOT_EVALUATED);
      assertThat(r.reason()).isEqualTo("No forecast has been persisted yet");
    });
    assertThat(result.coverage().stopReason()).isEqualTo(StopReason.PARTIAL_FAILURE);
    assertThat(result.warnings()).anySatisfy(w -> assertThat(w.code()).isEqualTo("FORECAST_NOT_EVALUATED"));
  }

  @Test
  void withoutTheMetricServiceTheToolSaysItsDependencyIsUnavailable() {
    assertThatThrownBy(() -> tools(null).thresholdReaches()).isInstanceOf(McpToolException.class);
  }
}
