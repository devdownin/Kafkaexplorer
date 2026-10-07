// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors
package com.compagnonsdudev.kafkasqlexplorer.forecast;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Realised quality judged over disjoint blocks of cohorts, never over one cohort.
 *
 * <p>A cohort is one realised horizon — ten points in the runbook's example — and its coverage is
 * a coin of ten throws: a perfectly calibrated 80 % interval scores under 0.8 on 32 % of them. The
 * verdict used to be taken on each cohort and latched, so drift was a matter of time, not quality.
 *
 * <p>So cohorts accumulate into a block of at least {@link #BLOCK_POINTS} points, the block is
 * judged once, and {@link #FAILED_BLOCKS_TO_DEGRADE} consecutive failed blocks are drift. Points
 * of one horizon share their errors, so the coverage gate uses the larger of the binomial standard
 * error and the one measured between cohorts: the first assumes independence, the second does not
 * need to. Simulated on a calibrated model (10-point cohorts, 30 days, correlation 0 to 1 inside a
 * cohort): about 1 % of runs latch, against all of them before; a true coverage of 0.6 is caught
 * within two blocks when cohorts are independent, slower when they are not — the evidence is weaker.
 */
public record ForecastQualityWindow(List<Cohort> open, Block lastBlock, int failedBlocks) {
  public static final int BLOCK_POINTS = 120;
  public static final int FAILED_BLOCKS_TO_DEGRADE = 2;
  /** One-sided 99 %. */
  static final double Z = 2.326;
  /** Drift needs the model to do clearly worse than a baseline; activation needs it no worse. */
  static final double DRIFT_BASELINE_TOLERANCE = 0.10;
  /**
   * Absolute slack relative to the series level: on a constant series {@code LAST_VALUE} scores
   * exactly zero and float noise from the model would otherwise count as losing to it.
   */
  static final double LEVEL_EPSILON = 1e-3;

  public static final ForecastQualityWindow EMPTY = new ForecastQualityWindow(List.of(), null, 0);

  /** One realised horizon, scored for TimesFM and every baseline on the same actual values. */
  public record Cohort(
      long through,
      int points,
      ForecastBacktestEvaluator.Evaluation quality,
      Map<String, Double> baselineMae,
      double meanAbsActual) {
    public Cohort {
      baselineMae = Map.copyOf(baselineMae);
    }
  }

  /** A judged block: pooled over its cohorts, weighted by points. */
  public record Block(
      long through,
      int cohorts,
      int points,
      ForecastBacktestEvaluator.Evaluation quality,
      Map<String, Double> baselineMae,
      double meanAbsActual,
      double coverageBound,
      boolean passed) {
    public Block {
      baselineMae = Map.copyOf(baselineMae);
    }

    /** Activation is stricter than drift: no worse than any baseline, with level slack only. */
    public boolean eligibleForActivation(Double maxMae) {
      return activationBlocker(maxMae).isEmpty();
    }

    /** What keeps this block from earning ACTIVE, in the operator's words; empty when nothing. */
    public java.util.Optional<String> activationBlocker(Double maxMae) {
      if (!passed) return java.util.Optional.of("The last quality block failed");
      if (baselineMae.size() != 4)
        return java.util.Optional.of("The last quality block lacks one of the four baseline comparisons");
      if (quality.q10Q90Coverage() < coverageBound)
        return java.util.Optional.of(
            "Interval coverage %.1f%% is below the %.1f%% bound"
                .formatted(100 * quality.q10Q90Coverage(), 100 * coverageBound));
      if (maxMae != null && quality.mae() > maxMae)
        return java.util.Optional.of(
            "MAE %.4g is above the configured maximum %.4g".formatted(quality.mae(), maxMae));
      return baselineMae.entrySet().stream()
          .filter(b -> quality.mae() > b.getValue() + LEVEL_EPSILON * meanAbsActual)
          .sorted(Map.Entry.comparingByValue())
          .findFirst()
          .map(
              b ->
                  "MAE %.4g is worse than the %s baseline (%.4g)"
                      .formatted(quality.mae(), b.getKey(), b.getValue()));
    }
  }

  public int openPoints() {
    return open.stream().mapToInt(Cohort::points).sum();
  }

  public ForecastQualityWindow {
    open = open == null ? List.of() : List.copyOf(open);
  }

  /** Adds a cohort and, once the block is full, judges it against the operator's gates. */
  public ForecastQualityWindow add(Cohort cohort, double minimumCoverage, Double maxMae) {
    var cohorts = Stream.concat(open.stream(), Stream.of(cohort)).toList();
    if (cohorts.stream().mapToInt(Cohort::points).sum() < BLOCK_POINTS)
      return new ForecastQualityWindow(cohorts, lastBlock, failedBlocks);
    var block = judge(cohorts, minimumCoverage, maxMae);
    return new ForecastQualityWindow(List.of(), block, block.passed() ? 0 : failedBlocks + 1);
  }

  public boolean drifted() {
    return failedBlocks >= FAILED_BLOCKS_TO_DEGRADE;
  }

  /** The last judged block, or the cohorts realised so far while the first block fills. */
  public ForecastBacktestEvaluator.Evaluation quality() {
    if (lastBlock != null) return lastBlock.quality();
    return open.isEmpty() ? null : pool(open);
  }

  public Map<String, Double> baselineMae() {
    if (lastBlock != null) return lastBlock.baselineMae();
    return open.isEmpty() ? Map.of() : poolBaselines(open);
  }

  static Block judge(List<Cohort> cohorts, double minimumCoverage, Double maxMae) {
    int points = cohorts.stream().mapToInt(Cohort::points).sum();
    var quality = pool(cohorts);
    var baselines = poolBaselines(cohorts);
    double level = weighted(cohorts, Cohort::meanAbsActual);
    double bound = minimumCoverage - Z * standardError(cohorts, minimumCoverage, points);
    boolean losesToBaseline =
        baselines.values().stream()
            .anyMatch(
                mae -> quality.mae() > mae * (1 + DRIFT_BASELINE_TOLERANCE) + LEVEL_EPSILON * level);
    boolean passed =
        quality.q10Q90Coverage() >= bound
            && (maxMae == null || quality.mae() <= maxMae)
            && !losesToBaseline;
    return new Block(
        cohorts.getLast().through(), cohorts.size(), points, quality, baselines, level, bound, passed);
  }

  private static double standardError(List<Cohort> cohorts, double required, int points) {
    double binomial = Math.sqrt(required * (1 - required) / points);
    if (cohorts.size() < 2) return binomial;
    double mean = cohorts.stream().mapToDouble(c -> c.quality().q10Q90Coverage()).average().orElseThrow();
    double variance =
        cohorts.stream()
                .mapToDouble(c -> Math.pow(c.quality().q10Q90Coverage() - mean, 2))
                .sum()
            / (cohorts.size() - 1);
    return Math.max(binomial, Math.sqrt(variance / cohorts.size()));
  }

  private static ForecastBacktestEvaluator.Evaluation pool(List<Cohort> cohorts) {
    var scaled = cohorts.stream().filter(c -> c.quality().mase() != null).toList();
    return new ForecastBacktestEvaluator.Evaluation(
        weighted(cohorts, c -> c.quality().mae()),
        scaled.isEmpty() ? null : weighted(scaled, c -> c.quality().mase()),
        weighted(cohorts, c -> c.quality().meanPinballLoss()),
        weighted(cohorts, c -> c.quality().q10Q90Coverage()),
        weighted(cohorts, c -> c.quality().meanIntervalWidth()));
  }

  private static Map<String, Double> poolBaselines(List<Cohort> cohorts) {
    var pooled = new LinkedHashMap<String, Double>();
    cohorts.getFirst().baselineMae().keySet().stream()
        .sorted()
        .forEach(name -> pooled.put(name, weighted(cohorts, c -> c.baselineMae().get(name))));
    return pooled;
  }

  /** Every pooled figure is a per-point mean, so weighting by points gives the pooled mean exactly. */
  private static double weighted(
      List<Cohort> cohorts, java.util.function.ToDoubleFunction<Cohort> value) {
    double sum = 0;
    int points = 0;
    for (var c : cohorts) {
      sum += value.applyAsDouble(c) * c.points();
      points += c.points();
    }
    return sum / points;
  }
}
