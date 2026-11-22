// SPDX-License-Identifier: AGPL-3.0-or-later
import { describe, expect, it } from 'vitest';
import type { TopicActivity, TopicActivityResponse } from '../api/types';
import { compareTopicActivity, temporalAssociation, investigateTopic } from './strataDiagnostics';

const samples = [10, 15, 35, 80, 50, 30, 22, 40, 75, 30, 14, 18];
function activity(topic: string, counts = samples, coveredFromMs: number | null = null): TopicActivity {
  return { topic, windowStartMs: 0, windowEndMs: 120000, bucketMs: 10000, counts, total: counts.reduce((a,b)=>a+b,0),
    coveredFromMs, partitionsMeasured: 2, partitionsTotal: 2, available: true, note: null };
}
const snapshot = (a = activity('a'), b = activity('b')): TopicActivityResponse => ({
  topics: { a, b }, windowStartMs: 0, windowEndMs: 120000,
  bucketMs: 10000, buckets: 12, available: true, warnings: [],
});

describe('Kafka Strata comparative evidence', () => {
  it('compares two aligned, fully measured topics without creating a new Kafka read', () => {
    const c = compareTopicActivity(snapshot(), 'a', 'b', 3);
    expect(c?.firstSlice).toBe(80);
    expect(c?.secondSlice).toBe(80);
    expect(c?.pairedBuckets).toBe(12);
    expect(c?.firstRate).toBeCloseTo(samples.reduce((a,b)=>a+b,0)/2);
  });
  it('refuses same-topic or missing-topic comparison', () => {
    expect(compareTopicActivity(snapshot(), 'a', 'a', 3)).toBeNull();
    expect(compareTopicActivity(snapshot(), 'a', 'unknown', 3)).toBeNull();
  });
  it('refuses mismatched time windows, even if the 12 values look plausible', () => {
    expect(compareTopicActivity(snapshot(activity('a'), {...activity('b'), windowStartMs: 1000}), 'a', 'b', 3)).toBeNull();
  });
  it('marks partial retention as unknown rather than summing a lower bound as a total', () => {
    const c = compareTopicActivity(snapshot(activity('a', samples, 15000)), 'a', 'b', 0)!;
    expect(c.firstSlice).toBeNull();
    expect(c.firstTotal).toBeNull();
    expect(c.firstRate).toBeNull();
    expect(c.pairedBuckets).toBeLessThan(12);
    expect(temporalAssociation(c)).toBeNull();
  });
  it('detects only exploratory fully-covered simultaneous association', () => {
    const c = compareTopicActivity(snapshot(), 'a', 'b', 3)!;
    expect(temporalAssociation(c)).toMatchObject({
      lagBuckets: 0, direction: 'simultaneous', coefficient: 1, pairedBuckets: 12,
    });
  });
  it('distinguishes earlier first-topic production from later second-topic production', () => {
    const shifted = [2, 2, ...samples.slice(0, 10)];
    const c = compareTopicActivity(snapshot(activity('a'), activity('b', shifted)), 'a', 'b', 3)!;
    expect(temporalAssociation(c)).toMatchObject({
      lagBuckets: 2, direction: 'first-leads-second', pairedBuckets: 10,
    });
  });
  it('never correlates a perfectly flat series', () => {
    const c = compareTopicActivity(snapshot(activity('a', Array(12).fill(0))), 'a', 'b', 3)!;
    expect(temporalAssociation(c)).toBeNull();
  });
  it('keeps declared job lineage and bounded key matches as different evidence', () => {
    const links = [{from:'upstream',to:'a',via:'flink-job-1'},{from:'a',to:'b',via:'flink-job-2'}];
    const flow = {hits:[{ topic:'a',occurrences:3,firstTimestamp:100,occurrencesCapped:true }],
      stats: { truncated:true,topicsFailed:0,topicsSkipped:0 }};
    const e = investigateTopic('a', links, flow as never);
    expect(e.incoming[0].via).toBe('flink-job-1');
    expect(e.outgoing[0].to).toBe('b');
    expect(e.matchingKey).toMatchObject({ occurrences:3, capped:true });
    expect(e.tracePartial).toBe(true);
  });
});
