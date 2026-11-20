// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors
import { describe, expect, it } from 'vitest';
import { forecastThresholdReach } from './forecastBreach';

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
