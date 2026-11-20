// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors
package com.compagnonsdudev.kafkasqlexplorer.forecast;

import static org.assertj.core.api.Assertions.assertThat;

import com.compagnonsdudev.kafkasqlexplorer.forecast.ForecastThresholdReach.Level;
import com.compagnonsdudev.kafkasqlexplorer.forecast.ForecastThresholdReach.Likelihood;
import com.compagnonsdudev.kafkasqlexplorer.forecast.ForecastThresholdReach.Reach;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The cases {@code forecastBreach.test.ts} runs against the page's reading, run against this one:
 * the Prometheus series and the card's badge must name the same threshold at the same instant.
 * Edit the two together.
 */
class ForecastThresholdReachTest {

  private static List<MetricForecast.Point> points(double spread, double... medians) {
    var out = new ArrayList<MetricForecast.Point>();
    for (int i = 0; i < medians.length; i++) {
      out.add(new MetricForecast.Point(1000 + i * 60000L, medians[i], medians[i] - spread, medians[i], medians[i] + spread));
    }
    return out;
  }

  @Test
  void findsTheFirstMedianAtOrPastTheCriticalThresholdBeforeLookingAtTheWarning() {
    assertThat(ForecastThresholdReach.reach(points(50, 700, 820, 1650, 1700), 0, 800.0, 1600.0, true))
        .isEqualTo(new Reach(Level.CRITICAL, Likelihood.EXPECTED, 1600, 1000 + 2 * 60000L, 3));
  }

  @Test
  void fallsBackToTheWarningWhenTheMedianNeverReachesCritical() {
    var reach = ForecastThresholdReach.reach(points(50, 700, 820, 900), 0, 800.0, 1600.0, true);
    assertThat(reach.level()).isEqualTo(Level.WARNING);
    assertThat(reach.likelihood()).isEqualTo(Likelihood.EXPECTED);
    assertThat(reach.steps()).isEqualTo(2);
  }

  @Test
  void readsTheCautiousEdgeOfTheIntervalOnlyWhenTheMedianStaysClear() {
    var reach = ForecastThresholdReach.reach(points(50, 700, 760, 770), 0, 800.0, 1600.0, true);
    assertThat(reach.level()).isEqualTo(Level.WARNING);
    assertThat(reach.likelihood()).isEqualTo(Likelihood.POSSIBLE);
    assertThat(reach.steps()).isEqualTo(2);
    // A baseline forecast has no interval: nothing is "possible", only what the median reaches.
    assertThat(ForecastThresholdReach.reach(points(50, 700, 760, 770), 0, 800.0, 1600.0, false)).isNull();
  }

  @Test
  void followsTheDirectionTheTwoThresholdsImply() {
    var reach = ForecastThresholdReach.reach(points(0.01, 0.99, 0.96, 0.9), 0, 0.97, 0.95, true);
    assertThat(reach.level()).isEqualTo(Level.CRITICAL);
    assertThat(reach.steps()).isEqualTo(3);
  }

  @Test
  void saysNothingWithoutThresholdsOrPoints() {
    assertThat(ForecastThresholdReach.reach(points(50, 1e9), 0, null, null, true)).isNull();
    assertThat(ForecastThresholdReach.reach(List.of(), 0, 800.0, 1600.0, true)).isNull();
  }

  @Test
  void readsOnlyThePointsAheadOfTheHistoryInOrder() {
    var mixed = new ArrayList<>(points(50, 900, 700));
    mixed.add(new MetricForecast.Point(500, 2000, 1950, 2000, 2050)); // before the history end: ignored
    var reach = ForecastThresholdReach.reach(mixed, 600, 800.0, 1600.0, true);
    assertThat(reach.level()).isEqualTo(Level.WARNING);
    assertThat(reach.at()).isEqualTo(1000);
    assertThat(reach.steps()).isEqualTo(1);
  }
}
