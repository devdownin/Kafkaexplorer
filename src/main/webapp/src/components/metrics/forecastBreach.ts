// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors
import type { ForecastPoint, ForecastSeriesView, MetricForecast } from '../../api/types';
import { thresholdDirection } from '../../pages/metricAlert';

/**
 * When the forecast reaches one of the metric's own thresholds — the thing a forecast is for.
 *
 * Read on the median first: a median past the threshold is "expected". Only if the median stays
 * clear is the cautious edge of the 80 % interval read (q90 for a threshold crossed upwards, q10
 * for one crossed downwards): reaching it there is "possible", which is a weaker sentence and is
 * worded as one. Critical is looked for before warning, since it is the one that matters.
 *
 * This describes the forecast; it is not an alert. Nothing is sent, and the card says the same.
 *
 * **Read twice, edited together:** `ForecastThresholdReach.java` is the same rule on the server, which
 * publishes it as `explorer_forecast_threshold_eta_seconds`; its test runs this file's cases.
 */
export interface ForecastThresholdReach {
  level: 'critical' | 'warning';
  likelihood: 'expected' | 'possible';
  threshold: number;
  /** The first predicted point at or past the threshold. */
  at: number;
  /** How many predicted points come before it, plus one: the step count from the history end. */
  steps: number;
}

export function forecastThresholdReach(
  points: ForecastPoint[],
  warningThreshold: number | null,
  criticalThreshold: number | null,
  withInterval: boolean,
): ForecastThresholdReach | null {
  if (!points.length) return null;
  const below = thresholdDirection(warningThreshold, criticalThreshold) === 'below';
  const past = (value: number, threshold: number) =>
    Number.isFinite(value) && (below ? value <= threshold : value >= threshold);
  const levels: { level: 'critical' | 'warning'; threshold: number | null }[] = [
    { level: 'critical', threshold: criticalThreshold },
    { level: 'warning', threshold: warningThreshold },
  ];
  for (const likelihood of ['expected', 'possible'] as const) {
    if (likelihood === 'possible' && !withInterval) break;
    for (const { level, threshold } of levels) {
      if (threshold === null || !Number.isFinite(threshold)) continue;
      const index = points.findIndex(p =>
        past(likelihood === 'expected' ? p.q50 : below ? p.q10 : p.q90, threshold));
      if (index >= 0) return { level, likelihood, threshold, at: points[index].at, steps: index + 1 };
    }
  }
  return null;
}

/** The predicted points that lie ahead of the history, in order — what a card or a page may read. */
export function predictedPoints(prediction: MetricForecast | null | undefined): ForecastPoint[] {
  return (prediction?.points ?? [])
    .filter(p => Number.isFinite(p.q50) && Number.isFinite(p.at) && p.at > prediction!.historyEndAt)
    .sort((a, b) => a.at - b.at);
}

export interface MetricForecastReach extends ForecastThresholdReach {
  /** How far ahead the first predicted point at or past the threshold lies. */
  inMillis: number;
}

/**
 * The reach of a metric's forecast against the metric's own thresholds, or null.
 *
 * One definition for the card's badge and for the page's filter and sort, so the two cannot
 * disagree about which metric is "about to breach". A counter's forecast is a rate per second
 * while its thresholds were set on the counter's own reading: comparing them is a unit error
 * worded as a prediction, so there is none.
 */
export function forecastReachFor(
  metric: { warningThreshold: number | null; criticalThreshold: number | null },
  series: ForecastSeriesView | null | undefined,
): MetricForecastReach | null {
  const result = series?.result;
  if (!series || !result?.forecast || result.context?.profile?.transformation === 'COUNTER_RATE') return null;
  const reach = forecastThresholdReach(predictedPoints(result.forecast), metric.warningThreshold,
    metric.criticalThreshold, result.strategy === 'TIMESFM');
  return reach && { ...reach, inMillis: reach.steps * series.stepMillis };
}
