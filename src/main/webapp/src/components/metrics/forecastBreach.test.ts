// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors
import { describe, expect, it } from 'vitest';
import type { ForecastSeriesView } from '../../api/types';
import { forecastReachFor, forecastThresholdReach, predictedPoints, whatIfReach } from './forecastBreach';

const points = (medians: number[], spread = 50) =>
  medians.map((q50, i) => ({ at: 1000 + i * 60000, central: q50, q50, q10: q50 - spread, q90: q50 + spread }));

describe('forecastThresholdReach', () => {
  it('finds the first median at or past the critical threshold before looking at the warning', () => {
    const reach = forecastThresholdReach(points([700, 820, 1650, 1700]), 800, 1600, true);
    expect(reach).toEqual({ level: 'critical', likelihood: 'expected', threshold: 1600, at: 1000 + 2 * 60000, steps: 3 });
  });

  it('falls back to the warning when the median never reaches critical', () => {
    expect(forecastThresholdReach(points([700, 820, 900]), 800, 1600, true))
      .toMatchObject({ level: 'warning', likelihood: 'expected', steps: 2 });
  });

  it('reads the cautious edge of the interval only when the median stays clear, and says "possible"', () => {
    expect(forecastThresholdReach(points([700, 760, 770]), 800, 1600, true))
      .toMatchObject({ level: 'warning', likelihood: 'possible', steps: 2 });
    // A baseline forecast has no interval: nothing is "possible", only what the median reaches.
    expect(forecastThresholdReach(points([700, 760, 770]), 800, 1600, false)).toBeNull();
  });

  it('follows the direction the two thresholds imply', () => {
    // Critical below warning: the metric breaks by falling (a ratio at 1.0 when healthy).
    const falling = points([0.99, 0.96, 0.9], 0.01);
    expect(forecastThresholdReach(falling, 0.97, 0.95, true)).toMatchObject({ level: 'critical', steps: 3 });
  });

  it('says nothing without thresholds or points', () => {
    expect(forecastThresholdReach(points([1e9]), null, null, true)).toBeNull();
    expect(forecastThresholdReach([], 800, 1600, true)).toBeNull();
  });
});

describe('predictedPoints and forecastReachFor', () => {
  const series = (transformation = 'GAUGE_MEAN') => ({ stepMillis: 60000, result: { strategy: 'TIMESFM', context: { profile: { transformation } },
    forecast: { historyEndAt: 500, points: [{ at: 400, central: 1, q10: 1, q50: 1, q90: 1 }, ...points([700, 900, 1000])] } } }) as unknown as ForecastSeriesView;

  it('keeps the finite points after the end of the history, in order', () => {
    expect(predictedPoints(series().result!.forecast)).toHaveLength(3);
    expect(predictedPoints(undefined)).toEqual([]);
  });

  it('measures the wait in milliseconds, from the series step', () => {
    expect(forecastReachFor({ warningThreshold: 800, criticalThreshold: 1600 }, series(), -59000))
      .toMatchObject({ level: 'warning', steps: 2, inMillis: 120000 });
  });

  it('never announces an elapsed breach, even when an earlier prediction crossed the threshold', () => {
    const elapsed = series();
    elapsed.result!.forecast!.points = points([700, 900, 700]);
    expect(forecastReachFor({ warningThreshold: 800, criticalThreshold: 1600 }, elapsed, 61000)).toBeNull();
    expect(forecastReachFor({ warningThreshold: 800, criticalThreshold: 1600 }, series(), 61000))
      .toMatchObject({ level: 'warning', steps: 1, inMillis: 60000 });
    expect(forecastReachFor({ warningThreshold: 800, criticalThreshold: 1600 }, series(), 121000)).toBeNull();
  });

  it('does not issue actionable breach predictions for stale or degraded results', () => {
    for (const state of ['STALE', 'DEGRADED'] as const) {
      const obsolete = series();
      obsolete.result!.state = state;
      expect(forecastReachFor({ warningThreshold: 800, criticalThreshold: 1600 }, obsolete, -59000)).toBeNull();
    }
  });

  it('never compares the counter thresholds with a rate forecast, and says nothing without a forecast', () => {
    expect(forecastReachFor({ warningThreshold: 800, criticalThreshold: 1600 }, series('COUNTER_RATE'))).toBeNull();
    expect(forecastReachFor({ warningThreshold: 800, criticalThreshold: 1600 }, undefined)).toBeNull();
  });
});

describe('whatIfReach', () => {
  it('reads a tried threshold on the median, then on the band edge, in the direction given', () => {
    expect(whatIfReach(points([700, 820, 900]), 800, false, true)).toMatchObject({ likelihood: 'expected', steps: 2 });
    expect(whatIfReach(points([700, 760, 770]), 800, false, true)).toMatchObject({ likelihood: 'possible', steps: 2 });
    expect(whatIfReach(points([700, 760, 770]), 800, false, false)).toBeNull();
    expect(whatIfReach(points([0.99, 0.96, 0.9], 0.01), 0.95, true, true)).toMatchObject({ likelihood: 'expected', steps: 3 });
    expect(whatIfReach(points([700]), Number.NaN, false, true)).toBeNull();
  });
});
