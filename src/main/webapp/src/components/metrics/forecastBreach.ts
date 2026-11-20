// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors
import type { ForecastPoint } from '../../api/types';
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
