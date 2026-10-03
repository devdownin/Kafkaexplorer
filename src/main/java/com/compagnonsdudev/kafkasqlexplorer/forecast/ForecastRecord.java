// SPDX-License-Identifier: AGPL-3.0-or-later
package com.compagnonsdudev.kafkasqlexplorer.forecast;

import java.util.Map;

/** Durable provenance, context and realised quality accompany every forecast. */
public record ForecastRecord(
    String key,
    long generatedAt,
    String state,
    String strategy,
    ForecastThresholdPolicy.Visibility visibility,
    PreparedMetricSeries context,
    MetricForecast forecast,
    ForecastBacktestEvaluator.Evaluation quality,
    int evaluatedPoints,
    long evaluatedThrough,
    Map<String, Double> baselineMae,
    String reason) {
  public ForecastRecord {
    baselineMae = Map.copyOf(baselineMae);
  }

  public boolean hasRealisedQuality() {
    return quality != null && strategy.equals("TIMESFM") && !state.equals("DEGRADED");
  }
}
