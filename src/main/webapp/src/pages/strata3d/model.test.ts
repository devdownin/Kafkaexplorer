// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors

import { describe, expect, it } from 'vitest';
import type { TopicActivity } from '../../api/types';
import { BUCKETS, activityPeak, columnsFor, coverageOf, columnPosition, ringRadius } from './model';

const measured: TopicActivity = {
  topic: 'orders',
  windowStartMs: 0, windowEndMs: 3_600_000, bucketMs: 300_000,
  counts: Array.from({ length: BUCKETS }, (_, i) => i),
  total: 66, coveredFromMs: null, partitionsMeasured: 3, partitionsTotal: 3,
  available: true, note: null,
};

describe('Strata 3D activity coverage', () => {
  it('never represents an absent, failed, retained-away or truncated read as a quiet topic', () => {
    expect(coverageOf(undefined)).toBe('unavailable');
    expect(coverageOf({ ...measured, available: false, counts: [] })).toBe('unavailable');
    expect(coverageOf({ ...measured, counts: [0, 0] })).toBe('unavailable');
    expect(coverageOf({ ...measured, coveredFromMs: 900_000 })).toBe('partial');
    expect(coverageOf({ ...measured, partitionsMeasured: 2 })).toBe('partial');
    expect(coverageOf(measured)).toBe('complete');
    expect(coverageOf({ ...measured, counts: Array(BUCKETS).fill(0) })).toBe('complete');
  });

  it('preserves missing columns and does not use unknown values in the activity peak', () => {
    const columns = columnsFor(['orders', 'missing'], { orders: measured }, { orders: 200 });
    expect(columns[1].coverage).toBe('unavailable');
    expect(columns[1].activity).toBeUndefined();
    expect(columns[1].retainedMessages).toBeUndefined();
    expect(activityPeak(columns)).toBe(11);
  });

  it('is repeatable and uses a consistent square-root activity scale', () => {
    expect(columnPosition(5)).toEqual(columnPosition(5));
    expect(columnPosition(1)).not.toEqual(columnPosition(2));
    expect(ringRadius(0, 100)).toBe(0.45);
    expect(ringRadius(100, 100)).toBeCloseTo(0.9);
    expect(ringRadius(25, 100)).toBeCloseTo(0.675);
  });
});
