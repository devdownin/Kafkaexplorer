// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors

import type { TopicActivity, TopicActivityResponse } from '../api/types';
import type { ParsedFlow } from './streamFlowLogic';
import type { StrataLink } from './strataLineage';
import { bucketReading } from './strataTime';

/** All evidence has a declared provenance; coincident timestamps are not a Kafka edge. */
export interface TopicInvestigation {
  incoming: StrataLink[];
  outgoing: StrataLink[];
  matchingKey: { occurrences: number; firstTimestamp: number; capped: boolean } | null;
  tracePartial: boolean;
}

export function investigateTopic(
  name: string, links: readonly StrataLink[], trace: ParsedFlow | null,
): TopicInvestigation {
  const hit = trace?.hits.find(h => h.topic === name);
  const stats = trace?.stats;
  return {
    incoming: links.filter(l => l.to === name),
    outgoing: links.filter(l => l.from === name),
    matchingKey: hit ? { occurrences: hit.occurrences, firstTimestamp: hit.firstTimestamp,
      capped: hit.occurrencesCapped === true } : null,
    tracePartial: Boolean(trace && (!stats || stats.truncated || stats.topicsFailed > 0 || stats.topicsSkipped > 0)),
  };
}

export interface TopicComparison {
  first: string;
  second: string;
  firstTotal: number | null;
  secondTotal: number | null;
  firstRate: number | null;
  secondRate: number | null;
  firstSlice: number | null;
  secondSlice: number | null;
  pairedBuckets: number;
  totalBuckets: number;
  firstPoints: Array<number | null>;
  secondPoints: Array<number | null>;
  startMs: number;
  endMs: number;
  bucketMs: number;
}

/**
 * A and B must come from the same server snapshot and use exactly aligned
 * time buckets. No extrapolation across retention gaps or failed partitions.
 */
export function compareTopicActivity(
  snapshot: TopicActivityResponse | null, a: string, b: string, index: number,
): TopicComparison | null {
  if (!snapshot || !a || !b || a === b || !snapshot.topics[a] || !snapshot.topics[b]) return null;
  const first = snapshot.topics[a], second = snapshot.topics[b];
  if (!sameWindow(first, second, snapshot)) return null;
  const indices = Array.from({ length: snapshot.buckets }, (_, i) => i);
  const firstPoints = indices.map(i => bucketReading(first, i));
  const secondPoints = indices.map(i => bucketReading(second, i));
  const firstComplete = firstPoints.every(v => v !== null);
  const secondComplete = secondPoints.every(v => v !== null);
  const minutes = (snapshot.windowEndMs - snapshot.windowStartMs) / 60000;
  const sum = (values: Array<number | null>) => values.reduce<number>((t, v) => t + (v ?? 0), 0);
  return {
    first: a, second: b,
    firstTotal: firstComplete ? sum(firstPoints) : null,
    secondTotal: secondComplete ? sum(secondPoints) : null,
    firstRate: firstComplete && minutes > 0 ? sum(firstPoints) / minutes : null,
    secondRate: secondComplete && minutes > 0 ? sum(secondPoints) / minutes : null,
    firstSlice: firstPoints[index] ?? null,
    secondSlice: secondPoints[index] ?? null,
    pairedBuckets: indices.filter(i => firstPoints[i] !== null && secondPoints[i] !== null).length,
    totalBuckets: indices.length,
    firstPoints, secondPoints, startMs: snapshot.windowStartMs, endMs: snapshot.windowEndMs,
    bucketMs: snapshot.bucketMs,
  };
}

function sameWindow(a: TopicActivity, b: TopicActivity, snapshot: TopicActivityResponse): boolean {
  return [a, b].every(t => t.windowStartMs === snapshot.windowStartMs
    && t.windowEndMs === snapshot.windowEndMs && t.bucketMs === snapshot.bucketMs
    && t.counts.length === snapshot.buckets);
}

export interface TemporalAssociation {
  /** Pearson r on measured, aligned offset deltas. Exploratory, never causal evidence. */
  coefficient: number;
  /** Positive: first topic's earlier production aligns with second topic's later production. */
  lagBuckets: number;
  pairedBuckets: number;
  direction: 'first-leads-second' | 'second-leads-first' | 'simultaneous';
}

function pearson(xs: number[], ys: number[]): number | null {
  if (xs.length < 8 || xs.length !== ys.length) return null;
  const mx = xs.reduce((s, x) => s + x, 0) / xs.length;
  const my = ys.reduce((s, x) => s + x, 0) / ys.length;
  let numerator = 0, xx = 0, yy = 0;
  for (let i = 0; i < xs.length; i++) {
    const dx = xs[i] - mx, dy = ys[i] - my;
    numerator += dx * dy;
    xx += dx * dx;
    yy += dy * dy;
  }
  if (xx < 1e-8 || yy < 1e-8) return null;
  return Math.max(-1, Math.min(1, numerator / Math.sqrt(xx * yy)));
}

/**
 * Search only the 0..2 bucket shifts permitted by a 12-point snapshot.
 * Do not cherry-pick incomplete pairs: every bucket in the compared shift
 * must be fully covered. A high r is a visual lead to investigate, not a
 * probability, causal claim, or record-level trace.
 */
export function temporalAssociation(comparison: TopicComparison, maxLag = 2): TemporalAssociation | null {
  if (comparison.totalBuckets < 10 || comparison.pairedBuckets !== comparison.totalBuckets) return null;
  const first = comparison.firstPoints as number[];
  const second = comparison.secondPoints as number[];
  let best: TemporalAssociation | null = null;
  for (let lag = -Math.min(2, maxLag); lag <= Math.min(2, maxLag); lag++) {
    const x: number[] = [], y: number[] = [];
    for (let t = 0; t < first.length; t++) {
      const u = t + lag;
      if (u < 0 || u >= second.length) continue;
      x.push(first[t]); y.push(second[u]);
    }
    const r = pearson(x, y);
    if (r === null || r < 0.65) continue;
    if (best === null || r > best.coefficient + 0.02) {
      best = { coefficient: r, lagBuckets: Math.abs(lag), pairedBuckets: x.length,
        direction: lag > 0 ? 'first-leads-second' : lag < 0 ? 'second-leads-first' : 'simultaneous' };
    }
  }
  return best;
}
