// SPDX-License-Identifier: AGPL-3.0-or-later
package com.compagnonsdudev.kafkasqlexplorer.forecast;

import java.util.ArrayList;
import java.util.List;

/** Deterministic metrics for offline rolling-origin evaluation. */
public final class ForecastBacktestEvaluator {
    public Evaluation evaluate(List<Double> actual, List<MetricForecast.Point> forecast) {
        if (actual == null || forecast == null || actual.isEmpty() || actual.size() != forecast.size()) {
            throw new IllegalArgumentException("actual and forecast must have equal non-empty sizes");
        }
        double mae = 0, pinball = 0, covered = 0, width = 0;
        for (int i = 0; i < actual.size(); i++) {
            double y = finite(actual.get(i)), p = finite(forecast.get(i).q50());
            double q10 = finite(forecast.get(i).q10()), q90 = finite(forecast.get(i).q90());
            mae += Math.abs(y - p);
            pinball += quantileLoss(y, q10, .10) + quantileLoss(y, p, .50) + quantileLoss(y, q90, .90);
            if (y >= q10 && y <= q90) covered++;
            width += q90 - q10;
        }
        double scale = 0;
        for (int i = 1; i < actual.size(); i++) scale += Math.abs(finite(actual.get(i)) - finite(actual.get(i - 1)));
        double meanAbsNaive = scale / Math.max(1, actual.size() - 1);
        return new Evaluation(mae / actual.size(), meanAbsNaive == 0 ? null : (mae / actual.size()) / meanAbsNaive,
                pinball / (actual.size() * 3), covered / actual.size(), width / actual.size());
    }

    private static double quantileLoss(double actual, double prediction, double q) {
        return (actual >= prediction ? q : q - 1) * (actual - prediction);
    }

    private static double finite(Double value) {
        if (value == null || !Double.isFinite(value)) throw new IllegalArgumentException("evaluation values must be finite");
        return value;
    }

    public record Evaluation(double mae, Double mase, double meanPinballLoss,
                             double q10Q90Coverage, double meanIntervalWidth) { }
}
