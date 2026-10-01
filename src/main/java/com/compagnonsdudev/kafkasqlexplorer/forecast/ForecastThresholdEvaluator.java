// SPDX-License-Identifier: AGPL-3.0-or-later
package com.compagnonsdudev.kafkasqlexplorer.forecast;

/** Evaluates only an explicit operator policy against an existing forecast. */
public final class ForecastThresholdEvaluator {
    public Breach evaluate(MetricForecast forecast, ForecastThresholdPolicy policy) {
        if (forecast == null || policy == null) throw new IllegalArgumentException("forecast and policy are required");
        if (!forecast.seriesId().equals(policy.seriesId()) || !forecast.definitionVersion().equals(policy.definitionVersion())) {
            throw new IllegalArgumentException("threshold policy does not match forecast provenance");
        }
        int horizon = Math.min(policy.horizonPoints(), forecast.points().size());
        boolean breached = false;
        double confidenceBound = policy.direction() == ForecastThresholdPolicy.Direction.ABOVE
                ? Double.NEGATIVE_INFINITY : Double.POSITIVE_INFINITY;
        for (int i = 0; i < horizon; i++) {
            var point = forecast.points().get(i);
            if (policy.direction() == ForecastThresholdPolicy.Direction.ABOVE) {
                breached |= point.q10() > policy.threshold();
                confidenceBound = Math.max(confidenceBound, point.q10());
            } else {
                breached |= point.q90() < policy.threshold();
                confidenceBound = Math.min(confidenceBound, point.q90());
            }
        }
        return new Breach(forecast.seriesId(), forecast.definitionVersion(), breached, policy.threshold(),
                policy.direction(), horizon, policy.confidence(), confidenceBound, policy.historyQuality(),
                policy.visibility(), "MEDIAN", "explicit operator threshold; q10/q90 conservative bound");
    }

    public record Breach(String seriesId, String definitionVersion, boolean breached, double threshold,
                          ForecastThresholdPolicy.Direction direction, int horizonPoints, double confidence,
                          double confidenceBound, String historyQuality,
                          ForecastThresholdPolicy.Visibility visibility, String forecastStatus, String basis) { }
}
