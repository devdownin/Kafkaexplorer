// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors
package com.compagnonsdudev.kafkasqlexplorer.forecast;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ForecastQualityWindowTest {
  private static final Map<String, Double> BASELINES =
      Map.of("LAST_VALUE", 1d, "MOVING_AVERAGE", 1d, "SEASONAL_NAIVE", 1d, "LINEAR_TREND", 1d);

  private static ForecastQualityWindow.Cohort cohort(
      double mae, double coverage, Map<String, Double> baselines, double level) {
    return new ForecastQualityWindow.Cohort(
        0, 10, new ForecastBacktestEvaluator.Evaluation(mae, null, 0, coverage, 1), baselines, level);
  }

  private static ForecastQualityWindow block(ForecastQualityWindow window, double... coverages) {
    for (double c : coverages) window = window.add(cohort(1, c, BASELINES, 5), .8, null);
    return window;
  }

  @Test
  void aCohortUnderTheCoverageGateIsNotAVerdict() {
    var window = ForecastQualityWindow.EMPTY.add(cohort(1, .5, BASELINES, 5), .8, null);
    assertNull(window.lastBlock());
    assertEquals(0, window.failedBlocks());
    assertEquals(.5, window.quality().q10Q90Coverage());
  }

  @Test
  void calibratedNoiseAcrossABlockPasses() {
    // Twelve 10-point cohorts averaging 0.78: five of them under 0.8, each a "failure" before.
    var window = block(ForecastQualityWindow.EMPTY, .7, .9, .7, .8, .9, .6, .8, .9, .7, .8, .9, .6);
    assertTrue(window.lastBlock().passed());
    assertEquals(120, window.lastBlock().points());
    assertTrue(window.open().isEmpty());
  }

  @Test
  void driftNeedsTwoConsecutiveFailedBlocksAndAPassResets() {
    double[] poor = {.3, .4, .3, .4, .3, .4, .3, .4, .3, .4, .3, .4};
    double[] good = {.8, .8, .8, .8, .8, .8, .8, .8, .8, .8, .8, .8};
    var once = block(ForecastQualityWindow.EMPTY, poor);
    assertFalse(once.lastBlock().passed());
    assertFalse(once.drifted());
    var recovered = block(once, good);
    assertEquals(0, recovered.failedBlocks());
    assertTrue(block(once, poor).drifted());
  }

  @Test
  void aConstantSeriesDoesNotLoseToAZeroErrorBaseline() {
    var zero = Map.of("LAST_VALUE", 0d, "MOVING_AVERAGE", 0d, "SEASONAL_NAIVE", 0d, "LINEAR_TREND", 0d);
    var window = ForecastQualityWindow.EMPTY;
    for (int i = 0; i < 12; i++) window = window.add(cohort(1e-6, .8, zero, 42), .8, null);
    assertTrue(window.lastBlock().passed());
    assertTrue(window.lastBlock().eligibleForActivation(null));
  }

  @Test
  void activationIsStricterThanDrift() {
    var window = ForecastQualityWindow.EMPTY;
    for (int i = 0; i < 12; i++) window = window.add(cohort(1.05, .8, BASELINES, 5), .8, null);
    assertTrue(window.lastBlock().passed(), "5 % worse than a baseline is not drift");
    assertFalse(window.lastBlock().eligibleForActivation(null), "but it does not earn ACTIVE");
  }

  @Test
  void clearlyWorseThanABaselineOrOverMaxMaeFails() {
    var window = ForecastQualityWindow.EMPTY;
    for (int i = 0; i < 12; i++) window = window.add(cohort(1.2, .8, BASELINES, 5), .8, null);
    assertFalse(window.lastBlock().passed());
    window = ForecastQualityWindow.EMPTY;
    for (int i = 0; i < 12; i++) window = window.add(cohort(.5, .8, BASELINES, 5), .8, .4);
    assertFalse(window.lastBlock().passed());
  }

  @Test
  void roundTripsThroughTheStorePayload() throws Exception {
    var window = block(ForecastQualityWindow.EMPTY, .7, .9, .7, .8, .9, .6, .8, .9, .7, .8, .9, .6)
        .add(cohort(1, .5, BASELINES, 5), .8, null);
    var json = new ObjectMapper();
    assertEquals(window, json.readValue(json.writeValueAsString(window), ForecastQualityWindow.class));
  }

  @Test
  void theActivationBlockerNamesTheBaselineThatDoesBetter() {
    var baselines = Map.of("LAST_VALUE", 1d, "MOVING_AVERAGE", .5, "SEASONAL_NAIVE", 1d, "LINEAR_TREND", 1d);
    var window = ForecastQualityWindow.EMPTY;
    for (int i = 0; i < 12; i++) window = window.add(cohort(.52, .8, baselines, 5), .8, null);
    assertEquals(
        "MAE 0.5200 is worse than the MOVING_AVERAGE baseline (0.5000)",
        window.lastBlock().activationBlocker(null).orElseThrow());
    assertEquals(
        "MAE 0.5200 is above the configured maximum 0.5000",
        window.lastBlock().activationBlocker(.5).orElseThrow());
  }
}
