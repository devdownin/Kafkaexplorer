// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors
import { describe, expect, it } from 'vitest';
import { placeThresholds } from './thresholdPlacement';

const marks = (warn: number | null, crit: number | null) => [
  { label: 'warn', value: warn, color: '#f5c264' }, { label: 'crit', value: crit, color: '#f58c8c' }];

describe('placeThresholds', () => {
  it('draws a threshold within one span of the data and widens the scale to take it in', () => {
    const placed = placeThresholds(2000, 2400, marks(2600, null));
    expect(placed.lines.map(l => l.label)).toEqual(['warn']);
    expect(placed.max).toBe(2600);
    expect(placed.min).toBe(2000);
    expect(placed.offScale).toEqual([]);
  });

  it('names a threshold further off at the edge it lies beyond, and leaves the scale alone', () => {
    const placed = placeThresholds(2000, 2400, marks(3800, 100));
    expect(placed.lines).toEqual([]);
    expect(placed.offScale.map(o => [o.label, o.up])).toEqual([['warn', true], ['crit', false]]);
    expect([placed.min, placed.max]).toEqual([2000, 2400]);
  });

  it('a threshold on the data is drawn; absent or non-finite ones are skipped', () => {
    const placed = placeThresholds(10, 20, [...marks(15, null), { label: 'x', value: Number.NaN, color: '#000' }]);
    expect(placed.lines.map(l => l.label)).toEqual(['warn']);
    expect(placed.offScale).toEqual([]);
  });

  it('a flat series still has a span, so a near threshold is drawn', () => {
    expect(placeThresholds(5, 5, marks(5.5, null)).lines).toHaveLength(1);
  });
});
