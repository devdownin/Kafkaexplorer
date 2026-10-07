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
    String reason,
    ForecastQualityWindow qualityWindow) {
  public ForecastRecord {
    baselineMae = Map.copyOf(baselineMae);
    // Payloads written before the window existed read back as an empty one.
    qualityWindow = qualityWindow == null ? ForecastQualityWindow.EMPTY : qualityWindow;
  }

  public boolean hasRealisedQuality() {
    return quality != null && strategy.equals("TIMESFM") && !state.equals("DEGRADED");
  }
}
