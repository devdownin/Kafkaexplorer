// SPDX-License-Identifier: AGPL-3.0-or-later
import { describe, expect, it } from 'vitest';
import type { TopicActivity } from '../api/types';
import { bucketInterval, bucketReading, detectActivityAnomaly } from './strataTime';

const sample: TopicActivity = {
  topic: 'orders', windowStartMs: 1_000, windowEndMs: 13_000, bucketMs: 1_000,
  counts: [50, 60, 50, 60, 280, 52, 51, 50, 52, 0, 60, 58],
  total: 833, coveredFromMs: null, partitionsMeasured: 3, partitionsTotal: 3,
  available: true, note: null,
};

describe('strata temporal slice', () => {
  it('maps a selected bucket to the reported common time window', () => {
    expect(bucketInterval({ startMs: 1000, endMs: 13000, bucketMs: 1000, count: 12 }, 4))
      .toEqual({ index: 4, startMs: 5000, endMs: 6000 });
  });
  it('never turns incomplete coverage into zero activity', () => {
    expect(bucketReading({ ...sample, partitionsMeasured: 2 }, 4)).toBeNull();
    expect(bucketReading({ ...sample, available: false }, 4)).toBeNull();
    expect(bucketReading({ ...sample, coveredFromMs: 5500 }, 4)).toBeNull();
    expect(bucketReading({ ...sample, coveredFromMs: 5000 }, 4)).toBe(280);
  });
  it('flags a strong spike against four preceding complete measurements', () => {
    expect(detectActivityAnomaly(sample, 4)?.kind).toBe('spike');
    expect(detectActivityAnomaly(sample, 9)?.kind).toBe('drop');
  });
  it('does not create false incidents on missing samples or low rates', () => {
    expect(detectActivityAnomaly({ ...sample, partitionsMeasured: 2 }, 4)).toBeNull();
    expect(detectActivityAnomaly({ ...sample, coveredFromMs: 4001 }, 4)).toBeNull();
    expect(detectActivityAnomaly({ ...sample, counts: Array(12).fill(1) }, 4)).toBeNull();
  });
});
