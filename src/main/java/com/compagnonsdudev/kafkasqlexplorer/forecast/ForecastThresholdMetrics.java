// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors
package com.compagnonsdudev.kafkasqlexplorer.forecast;

import com.compagnonsdudev.kafkasqlexplorer.domain.MetricConfig;
import com.compagnonsdudev.kafkasqlexplorer.service.MetricService;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.MultiGauge;
import io.micrometer.core.instrument.Tags;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * {@code explorer_forecast_threshold_eta_seconds}: how long until a metric's forecast reaches one of
 * its own thresholds, one series per metric that has such a reach.
 *
 * <p>This application sends no alert, and says so on every card: a predicted threshold is a reading
 * of the forecast. Publishing that reading is what lets an operator alert on it anyway, in the tool
 * built for it — {@code explorer_forecast_threshold_eta_seconds{level="critical",likelihood="expected"} < 1800}
 * in Alertmanager — rather than in a second alerting path here.
 *
 * <p>A series exists only while there is a reach: absent means no threshold is predicted within the
 * horizon (or the metric has no forecast, or a counter's rate forecast, which is never compared with
 * its thresholds). The value is measured at scrape time from the predicted instant, and never goes
 * below zero — a forecast that is no longer refreshed keeps saying zero, so pair the alert with
 * {@code explorer_metric_last_success_timestamp_seconds} as the card's own rule already does.
 *
 * <p>Labels: {@code metric_id} (the same label every per-metric series here carries), {@code level}
 * ({@code warning} or {@code critical}) and {@code likelihood} ({@code expected} on the median,
 * {@code possible} on the edge of the 80 % band). The reading is {@link ForecastThresholdReach}'s.
 */
@Component
public class ForecastThresholdMetrics {
  public static final String SERIES = "explorer_forecast_threshold_eta_seconds";
  private static final Logger log = LoggerFactory.getLogger(ForecastThresholdMetrics.class);

  private final ObjectProvider<ForecastPilotService> forecasts;
  private final MetricService metrics;
  private final MultiGauge gauge;
  private boolean failureReported;

  public ForecastThresholdMetrics(ObjectProvider<ForecastPilotService> forecasts, MetricService metrics,
                                  MeterRegistry registry) {
    this.forecasts = forecasts;
    this.metrics = metrics;
    this.gauge = MultiGauge.builder(SERIES)
        .description("Seconds until a metric's forecast reaches one of its thresholds; absent when none is predicted")
        .register(registry);
  }

  /**
   * Recomputed after each forecast-pilot cycle, on the pilot's thread ({@link ForecastPilotConfiguration}
   * wires it): a new forecast only exists once a cycle has written it. Never a {@code @Scheduled}
   * method — Spring's scheduler has a single thread, which metric collection owns, and the reads here
   * go to the forecast store. A threshold edited on a metric therefore shows at the next cycle.
   */
  public void publish() {
    gauge.register(rows(System::currentTimeMillis), true);
  }

  List<MultiGauge.Row<?>> rows(java.util.function.LongSupplier clock) {
    var pilot = forecasts.getIfAvailable();
    if (pilot == null) return List.of();
    Map<String, MetricConfig> byId = metrics.getAllMetrics().stream()
        .collect(Collectors.toMap(MetricConfig::id, Function.identity(), (a, b) -> a));
    var rows = new ArrayList<MultiGauge.Row<?>>();
    try {
      for (var series : pilot.series()) {
        MetricConfig metric = byId.get(series.metricId());
        if (metric == null || (metric.warningThreshold() == null && metric.criticalThreshold() == null)) continue;
        ForecastRecord record;
        try {
          record = pilot.get(series.seriesId());
        } catch (Exception e) {
          continue; // one unreadable series must not take the others' rows with it
        }
        var reach = ForecastThresholdReach.reachFor(record, metric.warningThreshold(), metric.criticalThreshold());
        if (reach == null) continue;
        rows.add(MultiGauge.Row.of(
            Tags.of("metric_id", metric.id(),
                "level", reach.level().name().toLowerCase(Locale.ROOT),
                "likelihood", reach.likelihood().name().toLowerCase(Locale.ROOT)),
            reach, r -> Math.max(0, (r.at() - clock.getAsLong()) / 1000.0)));
      }
      failureReported = false;
    } catch (RuntimeException e) {
      if (!failureReported) {
        failureReported = true;
        log.warn("{} could not be computed ({}); its series are withdrawn until it can. Said once until it recovers.",
            SERIES, e.toString());
      }
      return List.of();
    }
    return rows;
  }
}
