// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors
import type { ForecastQualityWindow, MetricForecast } from '../../api/types';

/**
 * A past forecast set beside what then happened — the comparison an operator makes by eye, done once.
 *
 * The quality the panel already reports (MAE, MASE, coverage) is an aggregate over blocks of
 * forecasts: true, and impossible to picture. This is one forecast, the latest whose whole horizon
 * has been measured, point by point against the measured series on the same grid. A filled gap is
 * not a measurement, so an imputed point is left out rather than scored.
 */
export interface ForecastVsActual {
  /** Predicted points that have a measurement at the same instant. */
  compared: number;
  /** Of those, how many fell inside the forecast's 80 % band. */
  inBand: number;
  /** Mean absolute distance between the median and the measurement, in the series' unit. */
  meanError: number | null;
  /** Mean signed distance (measured − median): positive when the forecast ran low. */
  bias: number | null;
  /** When the forecast was made: the end of the history it read. */
  madeAt: number;
}

export function compareWithActual(
  previous: MetricForecast,
  measured: { endAt: number; value: number | null; imputed: boolean }[],
): ForecastVsActual {
  const byInstant = new Map(measured.filter(p => !p.imputed && p.value !== null && Number.isFinite(p.value))
    .map(p => [p.endAt, p.value as number]));
  let compared = 0, inBand = 0, absolute = 0, signed = 0;
  for (const p of previous.points) {
    const actual = byInstant.get(p.at);
    if (actual === undefined || !Number.isFinite(p.q50)) continue;
    compared++;
    if (Number.isFinite(p.q10) && Number.isFinite(p.q90) && actual >= p.q10 && actual <= p.q90) inBand++;
    absolute += Math.abs(actual - p.q50);
    signed += actual - p.q50;
  }
  return { compared, inBand, meanError: compared ? absolute / compared : null,
    bias: compared ? signed / compared : null, madeAt: previous.historyEndAt };
}

/**
 * The card's one line on how the forecast has done, from the quality window the list already
 * carries — no request per card. The latest scored cohort when one is open, else the last closed
 * block; nothing before anything has been scored.
 */
export function lastCheck(window: ForecastQualityWindow | null | undefined):
  { points: number; coverage: number; meanError: number } | null {
  const cohort = window?.open?.length ? window.open[window.open.length - 1] : null;
  const scored = cohort ?? window?.lastBlock ?? null;
  if (!scored || !scored.points || !Number.isFinite(scored.quality?.q10Q90Coverage) || !Number.isFinite(scored.quality?.mae)) return null;
  return { points: scored.points, coverage: scored.quality.q10Q90Coverage, meanError: scored.quality.mae };
}

/**
 * How far to trust a forecast, in a word. The band is nominally an 80 % interval, so a forecast
 * whose measured points fall inside it about four times in five is doing what it claims.
 *
 * "Reliable" from 70 % (within ten points of nominal), "Fair" from 50 %, "Watch" below: a band that
 * holds half the time or less is not an 80 % band. Fewer than ten scored points say nothing yet
 * ("Early"): three points in the band of four is not a verdict. The numbers stay one hover away —
 * a badge is for the glance, and the detail is for whoever doubts it.
 */
export interface ForecastReliability {
  level: 'reliable' | 'fair' | 'watch' | 'early';
  label: string;
  tone: 'success' | 'warning' | 'error' | 'neutral';
}

export function forecastReliability(check: { points: number; coverage: number }): ForecastReliability {
  if (check.points < 10) return { level: 'early', label: 'Early', tone: 'neutral' };
  if (check.coverage >= 0.7) return { level: 'reliable', label: 'Reliable', tone: 'success' };
  if (check.coverage >= 0.5) return { level: 'fair', label: 'Fair', tone: 'warning' };
  return { level: 'watch', label: 'Watch', tone: 'error' };
}

/**
 * Whether the forecast is getting better or worse at holding its band — the badge reads the last
 * scored cohort only, which says how it did, not where it is going.
 *
 * The open cohorts of the quality window, oldest first, each with its 80 % band coverage. A trend
 * needs two of them; "improving" or "degrading" only past five points of coverage, so the noise of
 * two cohorts of thirty points is not read as a direction.
 */
export interface ReliabilityTrend {
  coverages: number[];
  direction: 'improving' | 'degrading' | 'steady';
}

export function reliabilityTrend(window: ForecastQualityWindow | null | undefined): ReliabilityTrend | null {
  const coverages = (window?.open ?? [])
    .map(cohort => cohort.quality?.q10Q90Coverage)
    .filter((c): c is number => typeof c === 'number' && Number.isFinite(c));
  if (coverages.length < 2) return null;
  const change = coverages[coverages.length - 1] - coverages[0];
  return { coverages, direction: change >= 0.05 ? 'improving' : change <= -0.05 ? 'degrading' : 'steady' };
}
