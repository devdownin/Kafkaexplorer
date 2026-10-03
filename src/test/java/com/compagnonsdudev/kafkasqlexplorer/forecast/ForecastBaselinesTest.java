// SPDX-License-Identifier: AGPL-3.0-or-later
package com.compagnonsdudev.kafkasqlexplorer.forecast;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.api.Test;

class ForecastBaselinesTest {
  @Test
  void fourStrategiesUseOnlyTrainingAndRespectSeason() {
    var p = ForecastBaselines.predict(List.of(1d, 2d, 3d, 4d), 3, 2);
    assertEquals(List.of(4d, 4d, 4d), p.get("LAST_VALUE"));
    assertEquals(List.of(2.5, 2.5, 2.5), p.get("MOVING_AVERAGE"));
    assertEquals(List.of(3d, 4d, 3d), p.get("SEASONAL_NAIVE"));
    assertEquals(List.of(5d, 6d, 7d), p.get("LINEAR_TREND"));
    assertEquals(0, ForecastBaselines.mae(List.of(5d, 6d, 7d), p.get("LINEAR_TREND")));
  }

  @Test
  void rejectsNonFiniteAndUnboundedForecasts() {
    assertThrows(
        IllegalArgumentException.class,
        () -> ForecastBaselines.predict(List.of(1d, Double.NaN), 1, 1));
    assertThrows(
        IllegalArgumentException.class, () -> ForecastBaselines.predict(List.of(1d, 2d), 61, 1));
  }

  @Test
  void maseUsesTrainingSeasonalScaleRatherThanFutureActualChanges() {
    var points =
        List.of(new MetricForecast.Point(1, 5, 3, 5, 7), new MetricForecast.Point(2, 5, 3, 5, 7));
    var result =
        new ForecastBacktestEvaluator().evaluate(List.of(0d, 2d, 4d), List.of(6d, 6d), points, 1);
    assertEquals(1, result.mae());
    assertEquals(.5, result.mase());
    assertEquals(1, result.q10Q90Coverage());
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new ForecastBacktestEvaluator()
                .evaluate(List.of(1d), List.of(new MetricForecast.Point(1, 1, 2, 1, 3))));
  }

  @Test
  void refusesFiniteValuesWhoseArithmeticOverflows() {
    assertThrows(
        IllegalArgumentException.class,
        () -> ForecastBaselines.predict(List.of(Double.MAX_VALUE, Double.MAX_VALUE), 2, 1));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new ForecastBacktestEvaluator()
                .evaluate(
                    List.of(Double.NaN),
                    List.of(1d),
                    List.of(new MetricForecast.Point(1, 1, 0, 1, 2)),
                    2));
  }
}
