// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors
package com.compagnonsdudev.kafkasqlexplorer.forecast;

import java.util.Comparator;
import java.util.List;

/**
 * When a forecast reaches one of its metric's own thresholds — the same reading as the Metrics
 * page's {@code forecastBreach.ts}, so Prometheus and the card's badge say the same thing.
 *
 * <p><b>One rule read twice, on purpose, and edited together.</b> The page computes it on every
 * render without a round trip; the server has to compute it to publish
 * {@code explorer_forecast_threshold_eta_seconds}, which is what lets an operator alert on a
 * forecast while this application itself sends nothing. {@code ForecastThresholdReachTest} runs the
 * cases {@code forecastBreach.test.ts} runs, so the two readings cannot drift unnoticed.
 *
 * <p>Read on the median first: a median at or past the threshold is {@code EXPECTED}. Only if the
 * median stays clear is the cautious edge of the 80 % interval read (q90 upwards, q10 downwards):
 * reaching it there is {@code POSSIBLE}. Critical is looked for before warning. The direction is the
 * one the two thresholds imply (critical below warning reads the metric downwards). A counter's
 * forecast is a rate per second while its thresholds are on the counter's own reading, so a
 * {@code COUNTER_RATE} forecast is never compared with them.
 */
public final class ForecastThresholdReach {
  private ForecastThresholdReach() {}

  public enum Level { CRITICAL, WARNING }

  public enum Likelihood { EXPECTED, POSSIBLE }

  /** The first predicted point at or past a threshold; {@code steps} counts from the history end. */
  public record Reach(Level level, Likelihood likelihood, double threshold, long at, int steps) {}

  public static Reach reach(List<MetricForecast.Point> points, long historyEndAt, Double warning,
                            Double critical, boolean withInterval) {
    var predicted = predicted(points, historyEndAt);
    if (predicted.isEmpty()) return null;
    boolean below = warning != null && critical != null && critical < warning;
    for (var likelihood : Likelihood.values()) {
      if (likelihood == Likelihood.POSSIBLE && !withInterval) break;
      for (var level : Level.values()) {
        Double threshold = level == Level.CRITICAL ? critical : warning;
        if (threshold == null || !Double.isFinite(threshold)) continue;
        for (int i = 0; i < predicted.size(); i++) {
          var p = predicted.get(i);
          double value = likelihood == Likelihood.EXPECTED ? p.q50() : below ? p.q10() : p.q90();
          if (Double.isFinite(value) && (below ? value <= threshold : value >= threshold)) {
            return new Reach(level, likelihood, threshold, p.at(), i + 1);
          }
        }
      }
    }
    return null;
  }

  /** A record's reach against a metric's thresholds, or null — the {@code forecastReachFor} of the page. */
  public static Reach reachFor(ForecastRecord record, Double warning, Double critical) {
    if (record == null || record.forecast() == null) return null;
    if (record.context() != null && record.context().profile() != null
        && record.context().profile().transformation() == SeriesPreparationProfile.Transformation.COUNTER_RATE) {
      return null;
    }
    return reach(record.forecast().points(), record.forecast().historyEndAt(), warning, critical,
        "TIMESFM".equals(record.strategy()));
  }

  /** The predicted points that lie ahead of the history, in order. */
  static List<MetricForecast.Point> predicted(List<MetricForecast.Point> points, long historyEndAt) {
    return points.stream()
        .filter(p -> Double.isFinite(p.q50()) && p.at() > historyEndAt)
        .sorted(Comparator.comparingLong(MetricForecast.Point::at))
        .toList();
  }
}
