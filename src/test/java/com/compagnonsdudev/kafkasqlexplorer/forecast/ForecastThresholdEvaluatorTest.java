// SPDX-License-Identifier: AGPL-3.0-or-later
package com.compagnonsdudev.kafkasqlexplorer.forecast;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class ForecastThresholdEvaluatorTest {
    @Test void evaluatesExplicitAbovePolicyWithConservativeQ10Bound() {
        var forecast = new MetricForecast("r", "a".repeat(64), "definition", "input", "profile", "messages", 10,
                "model", "revision", "adapter", "MEDIAN", 10,
                List.of(new MetricForecast.Point(11, 12, 11, 12, 13), new MetricForecast.Point(12, 13, 12, 13, 14)));
        var policy = new ForecastThresholdPolicy("a".repeat(64), "definition", 10,
                ForecastThresholdPolicy.Direction.ABOVE, 2, .9, "READY", ForecastThresholdPolicy.Visibility.SHADOW);
        var breach = new ForecastThresholdEvaluator().evaluate(forecast, policy);
        assertTrue(breach.breached());
        assertEquals(12, breach.confidenceBound());
        assertEquals(ForecastThresholdPolicy.Visibility.SHADOW, breach.visibility());
    }

    @Test void refusesDifferentDefinitionVersion() {
        var forecast = new MetricForecast("r", "a".repeat(64), "definition", "input", "profile", "messages", 10,
                "model", "revision", "adapter", "MEDIAN", 10, List.of(new MetricForecast.Point(11, 1, 0, 1, 2)));
        var policy = new ForecastThresholdPolicy("a".repeat(64), "other", 10,
                ForecastThresholdPolicy.Direction.ABOVE, 1, .9, "READY", ForecastThresholdPolicy.Visibility.VISIBLE);
        assertThrows(IllegalArgumentException.class, () -> new ForecastThresholdEvaluator().evaluate(forecast, policy));
    }
}
