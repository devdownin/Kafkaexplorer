// SPDX-License-Identifier: AGPL-3.0-or-later
import { describe, expect, it } from 'vitest';
import type { PartitionActivityResponse, TopicActivity, TopicActivityResponse } from '../api/types';
import { activityHistogram, comparisonDelta, partitionFrameMatches, topicEvidence, topicMapPositions } from './strataWorkspace';

const topic = (counts = Array(12).fill(20), coveredFromMs: number | null = null): TopicActivity => ({
  topic: 'orders',
  windowStartMs: 0, windowEndMs: 120_000, bucketMs: 10_000,
  counts, total: counts.reduce((sum, value) => sum + value, 0),
  partitionsMeasured: 3, partitionsTotal: 3,
  coveredFromMs, available: true, note: null,
});

describe('Strata workspace evidence', () => {
  it('shows fully measured production and a legitimately zero bucket', () => {
    const values = Array(12).fill(20);
    values[5] = 0;
    const evidence = topicEvidence(topic(values), 5, 1000, 1000);
    expect(evidence).toMatchObject({
      status: 'measured', selected: 0, latestRate: 120, total: 220,
      measuredBuckets: 12, totalBuckets: 12,
    });
  });

  it('withholds totals and rates for missing partitions and retention gaps', () => {
    const partial = { ...topic(), partitionsMeasured: 2 };
    expect(topicEvidence(partial, 5)).toMatchObject({
      status: 'partial', selected: null, latestRate: null, total: null,
      measuredBuckets: 0,
    });
    const retention = topic(undefined, 15_000);
    expect(topicEvidence(retention, 0)).toMatchObject({
      status: 'partial', selected: null, total: null, measuredBuckets: 10,
    });
    expect(topicEvidence(retention, 4).selected).toBe(20);
  });

  it('rejects truncated or inconsistent samples before showing a total', () => {
    expect(topicEvidence({ ...topic(), counts: [10, 20, 30] }, 1)).toMatchObject({
      status: 'unavailable', total: null, latestRate: null, selected: null,
      totalBuckets: 12,
    });
    expect(topicEvidence({ ...topic(), bucketMs: 0 }, 1)).toMatchObject({
      status: 'unavailable', total: null, latestRate: null,
    });
    expect(topicEvidence({ ...topic(), partitionsMeasured: 4 }, 1)).toMatchObject({
      status: 'unavailable', total: null, latestRate: null,
    });
  });

  it('distinguishes stale readings from unavailable readings and retains the timestamp', () => {
    expect(topicEvidence(topic(), 2, 1_000, 100_000).status).toBe('stale');
    expect(topicEvidence(undefined, 2, null).status).toBe('unavailable');
    expect(topicEvidence({ ...topic(), available: false }, 2, null).selected).toBeNull();
  });

  it('keeps unknown offsets out of temporal histogram bars', () => {
    const histogram = activityHistogram([
      { activity: topic() }, { activity: { ...topic(), partitionsMeasured: 0 } },
    ], 12);
    expect(histogram[0]).toEqual({ index: 0, value: 20, measured: 1, total: 2 });
    expect(activityHistogram([{ activity: undefined }], 2)[0]).toEqual({
      index: 0, value: 0, measured: 0, total: 1,
    });
  });

  it('uses one stable cluster coordinate frame when the visible set is filtered', () => {
    const full = topicMapPositions([
      { name: 'first', layoutIndex: 0 }, { name: 'last', layoutIndex: 30 },
    ], 40);
    const filtered = topicMapPositions([{ name: 'last', layoutIndex: 30 }], 40);
    expect(filtered[0]).toEqual(full[1]);
  });

  it('does not fabricate a comparison delta if either rate is unknown', () => {
    expect(comparisonDelta(null, 4)).toBeNull();
    expect(comparisonDelta(8, null)).toBeNull();
    expect(comparisonDelta(12, 8)).toEqual({ absolute: 4, percent: 50 });
    expect(comparisonDelta(8, 0)).toEqual({ absolute: 8, percent: null });
  });
  it('hides partition ribbons when a refresh crosses the shared time window', () => {
    const snapshot: TopicActivityResponse = {
      topics: { orders: topic() }, windowStartMs: 0, windowEndMs: 120_000,
      bucketMs: 10_000, buckets: 12, available: true, warnings: [],
    };
    const partition: PartitionActivityResponse = {
      topic: 'orders', windowStartMs: 0, windowEndMs: 120_000,
      bucketMs: 10_000, buckets: 12, partitionsTotal: 1,
      partitionsMeasured: 1, truncated: false, warnings: [],
      partitions: [{ partition: 0, counts: Array(12).fill(1), coveredFromMs: null,
        available: true, note: null }],
    };
    expect(partitionFrameMatches(partition, snapshot)).toBe(true);
    expect(partitionFrameMatches({ ...partition, windowStartMs: 10_000 }, snapshot)).toBe(false);
    expect(partitionFrameMatches({ ...partition, windowEndMs: 130_000 }, snapshot)).toBe(false);
    expect(partitionFrameMatches({ ...partition, partitions: [
      { ...partition.partitions[0], counts: [1, 2] },
    ] }, snapshot)).toBe(false);
    expect(partitionFrameMatches(null, snapshot)).toBe(false);
  });

});
