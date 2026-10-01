// SPDX-License-Identifier: AGPL-3.0-or-later
package com.compagnonsdudev.kafkasqlexplorer.forecast;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class ForecastBacktestEvaluatorTest {
    @Test void reportsMaeCoverageAndWidthWithoutInventingAQuantile() {
        var points = List.of(
                new MetricForecast.Point(1, 1, 0, 1, 2),
                new MetricForecast.Point(2, 2, 1, 2, 3),
                new MetricForecast.Point(3, 4, 3, 4, 5));
        var result = new ForecastBacktestEvaluator().evaluate(List.of(1d, 3d, 4d), points);
        assertEquals(1d / 3d, result.mae(), 1e-9);
        assertEquals(1d, result.q10Q90Coverage(), 1e-9);
        assertEquals(2d, result.meanIntervalWidth(), 1e-9);
        assertNotNull(result.mase());
    }

    @Test void rejectsMismatchedOrNonFiniteInput() {
        var evaluator = new ForecastBacktestEvaluator();
        assertThrows(IllegalArgumentException.class, () -> evaluator.evaluate(List.of(1d), List.of()));
        assertThrows(IllegalArgumentException.class, () -> evaluator.evaluate(List.of(Double.NaN),
                List.of(new MetricForecast.Point(1, 1, 0, 1, 2))));
    }
}
