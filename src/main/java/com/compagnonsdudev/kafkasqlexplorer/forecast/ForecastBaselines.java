// SPDX-License-Identifier: AGPL-3.0-or-later
package com.compagnonsdudev.kafkasqlexplorer.forecast;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * All estimates use the training prefix only. Point baselines do not fabricate confidence
 * intervals.
 */
public final class ForecastBaselines {
  public static Map<String, List<Double>> predict(List<Double> training, int horizon, int season) {
    if (training == null
        || training.size() < 2
        || horizon < 1
        || horizon > 60
        || season < 1
        || season > training.size()
        || training.stream().anyMatch(v -> v == null || !Double.isFinite(v)))
      throw new IllegalArgumentException("Invalid baseline context");
    double last = training.getLast();
    double average =
        training.subList(Math.max(0, training.size() - 12), training.size()).stream()
            .mapToDouble(Double::doubleValue)
            .average()
            .orElseThrow();
    int n = training.size();
    double sx = 0, sy = 0, sxx = 0, sxy = 0;
    for (int i = 0; i < n; i++) {
      sx += i;
      sy += training.get(i);
      sxx += (double) i * i;
      sxy += i * training.get(i);
    }
    double slope = (n * sxy - sx * sy) / (n * sxx - sx * sx), intercept = (sy - slope * sx) / n;
    var flat = new ArrayList<Double>();
    var mean = new ArrayList<Double>();
    var seasonal = new ArrayList<Double>();
    var trend = new ArrayList<Double>();
    for (int i = 0; i < horizon; i++) {
      flat.add(last);
      mean.add(average);
      seasonal.add(training.get(n - season + i % season));
      trend.add(intercept + slope * (n + i));
    }
    if (!Double.isFinite(average) || trend.stream().anyMatch(v -> !Double.isFinite(v)))
      throw new IllegalArgumentException("Baseline arithmetic overflow");
    return Map.of(
        "LAST_VALUE",
        List.copyOf(flat),
        "MOVING_AVERAGE",
        List.copyOf(mean),
        "SEASONAL_NAIVE",
        List.copyOf(seasonal),
        "LINEAR_TREND",
        List.copyOf(trend));
  }

  public static double mae(List<Double> actual, List<Double> prediction) {
    if (actual.isEmpty() || actual.size() != prediction.size())
      throw new IllegalArgumentException("Mismatched evaluation horizon");
    double sum = 0;
    for (int i = 0; i < actual.size(); i++) {
      if (!Double.isFinite(actual.get(i)) || !Double.isFinite(prediction.get(i)))
        throw new IllegalArgumentException("Nonfinite evaluation");
      sum += Math.abs(actual.get(i) - prediction.get(i));
    }
    if (!Double.isFinite(sum)) throw new IllegalArgumentException("Evaluation arithmetic overflow");
    return sum / actual.size();
  }
}
