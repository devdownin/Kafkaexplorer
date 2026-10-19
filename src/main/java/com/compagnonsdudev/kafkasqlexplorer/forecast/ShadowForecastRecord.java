// SPDX-License-Identifier: AGPL-3.0-or-later
package com.compagnonsdudev.kafkasqlexplorer.forecast;

/**
 * One shadow forecast and the quality accumulated so far, kept apart from {@link ForecastRecord}.
 *
 * <p>Both windows hold the same cohorts: a cohort enters only when TimesFM and the shadow were
 * scored on the same realised values, so their blocks close together and compare like for like.
 * {@code revision} is the shadow checkpoint those cohorts were scored for; a new pin starts over.
 *
 * <p>With covariates declared, the shadow also forecasts the same context with them
 * ({@code covariateForecast}), and {@link CovariateTrial} compares three models on the cohorts
 * all three were scored on. The univariate pair above is left untouched by it, so a covariate that
 * is often missing slows the trial, never the plain comparison.
 */
public record ShadowForecastRecord(
    String key,
    long generatedAt,
    String state,
    String reason,
    PreparedMetricSeries context,
    MetricForecast forecast,
    String revision,
    int evaluatedPoints,
    long evaluatedThrough,
    ForecastQualityWindow shadowWindow,
    ForecastQualityWindow primaryWindow,
    String covariateState,
    String covariateReason,
    String covariateFingerprint,
    MetricForecast covariateForecast,
    CovariateTrial trial) {
  public ShadowForecastRecord {
    shadowWindow = shadowWindow == null ? ForecastQualityWindow.EMPTY : shadowWindow;
    primaryWindow = primaryWindow == null ? ForecastQualityWindow.EMPTY : primaryWindow;
    // Rows written before covariates existed read back with no trial.
    trial = trial == null ? CovariateTrial.EMPTY : trial;
  }

  /**
   * Shadow with covariates, shadow without, and TimesFM, on identical cohorts. {@code fingerprint}
   * names the covariate declaration and {@code revision} the shadow checkpoint: either changing
   * starts the trial over, since cohorts of another set of inputs say nothing about this one.
   */
  public record CovariateTrial(
      String fingerprint,
      String revision,
      int evaluatedPoints,
      long evaluatedThrough,
      ForecastQualityWindow multivariateWindow,
      ForecastQualityWindow univariateWindow,
      ForecastQualityWindow primaryWindow) {
    public static final CovariateTrial EMPTY = new CovariateTrial(null, null, 0, 0, null, null, null);

    public CovariateTrial {
      multivariateWindow = multivariateWindow == null ? ForecastQualityWindow.EMPTY : multivariateWindow;
      univariateWindow = univariateWindow == null ? ForecastQualityWindow.EMPTY : univariateWindow;
      primaryWindow = primaryWindow == null ? ForecastQualityWindow.EMPTY : primaryWindow;
    }

    boolean belongsTo(String fingerprint, String revision) {
      return java.util.Objects.equals(this.fingerprint, fingerprint) && java.util.Objects.equals(this.revision, revision);
    }
  }
}
