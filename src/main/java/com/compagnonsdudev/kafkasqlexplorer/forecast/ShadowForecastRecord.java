// SPDX-License-Identifier: AGPL-3.0-or-later
package com.compagnonsdudev.kafkasqlexplorer.forecast;

/**
 * One shadow forecast and the quality accumulated so far, kept apart from {@link ForecastRecord}.
 *
 * <p>Both windows hold the same cohorts: a cohort enters only when TimesFM and the shadow were
 * scored on the same realised values, so their blocks close together and compare like for like.
 * {@code revision} is the shadow checkpoint those cohorts were scored for; a new pin starts over.
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
    ForecastQualityWindow primaryWindow) {
  public ShadowForecastRecord {
    shadowWindow = shadowWindow == null ? ForecastQualityWindow.EMPTY : shadowWindow;
    primaryWindow = primaryWindow == null ? ForecastQualityWindow.EMPTY : primaryWindow;
  }
}
