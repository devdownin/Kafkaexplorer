// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors

import type { PartitionActivityResponse, TopicActivity, TopicActivityResponse } from '../api/types';
import { bucketReading } from './strataTime';

export type StrataLayer = 'activity' | 'lineage' | 'compared' | 'inferred' | 'traces' | 'consumers' | 'anomalies' | 'forecast'
  | 'labels';
export type StrataLayers = Record<StrataLayer, boolean>;

export const DEFAULT_STRATA_LAYERS: StrataLayers = {
  activity: true, lineage: true, traces: true, consumers: true, anomalies: true, forecast: true,
  // The pairs a count-comparison metric declares: an operator's statement, so on by default.
  compared: true,
  // A guess from naming conventions: off until the operator asks for it.
  inferred: false,
  // Every column's name; otherwise only the busiest and the ones being pointed at (`labelledTopics`).
  labels: false,
};

export interface TopicEvidence {
  status: 'measured' | 'partial' | 'unavailable' | 'stale';
  selected: number | null;
  latestRate: number | null;
  total: number | null;
  measuredBuckets: number;
  totalBuckets: number;
  partitionsMeasured: number;
  partitionsTotal: number;
}

/** Every displayed production figure follows the same coverage rule as the spatial rings. */
export function topicEvidence(activity: TopicActivity | undefined, bucket: number,
  receivedAt: number | null = null, now: number = Date.now()): TopicEvidence {
  const duration = activity ? activity.windowEndMs - activity.windowStartMs : 0;
  const expectedBuckets = activity && Number.isFinite(duration) && Number.isFinite(activity.bucketMs)
    && duration > 0 && activity.bucketMs > 0 && duration % activity.bucketMs === 0
    ? duration / activity.bucketMs : 0;
  const structurallyValid = Boolean(activity && Number.isInteger(expectedBuckets)
    && expectedBuckets > 0 && expectedBuckets <= 48
    && activity.counts.length === expectedBuckets
    && activity.partitionsTotal > 0 && activity.partitionsMeasured <= activity.partitionsTotal);
  const readings = structurallyValid && activity
    ? activity.counts.map((_, index) => bucketReading(activity, index)) : [];
  const measuredBuckets = readings.filter(value => value !== null).length;
  const allComplete = Boolean(structurallyValid && activity?.available
    && measuredBuckets === expectedBuckets);
  const latest = readings[readings.length - 1] ?? null;
  const stale = receivedAt !== null && now - receivedAt > 90_000;
  return {
    status: !activity?.available || !structurallyValid ? 'unavailable' : stale ? 'stale' :
      allComplete ? 'measured' : 'partial',
    selected: structurallyValid ? bucketReading(activity, bucket) : null,
    latestRate: latest !== null && activity?.bucketMs && activity.bucketMs > 0
      ? latest * 60000 / activity.bucketMs : null,
    total: allComplete ? readings.reduce<number>((sum, n) => sum + (n ?? 0), 0) : null,
    measuredBuckets,
    totalBuckets: expectedBuckets || 0,
    partitionsMeasured: activity?.partitionsMeasured ?? 0,
    partitionsTotal: activity?.partitionsTotal ?? 0,
  };
}

export function topicMapPositions(items: readonly { name: string; layoutIndex: number; slot?: { x: number; z: number } }[],
  totalTopics: number = items.length, lanes = false) {
  if (lanes && items.length && items.every(item => item.slot)) {
    // Lanes are laid out from the topics in view, so the map frames those same topics.
    const xs = items.map(item => item.slot!.x);
    const zs = items.map(item => item.slot!.z);
    const minX = Math.min(...xs), spanX = Math.max(1, Math.max(...xs) - minX);
    const minZ = Math.min(...zs), spanZ = Math.max(1, Math.max(...zs) - minZ);
    return items.map(item => ({ name: item.name,
      x: 16 + (item.slot!.x - minX) / spanX * 208, y: 14 + (item.slot!.z - minZ) / spanZ * 136 }));
  }
  // Use the full cluster extent even when the operator filters the visible
  // topics. This keeps mini-map coordinates stable across filter changes.
  const radius = Math.max(5, 3.4 * Math.sqrt(Math.max(1, totalTopics)));
  return items.map(item => {
    const distance = 3.4 * Math.sqrt(item.layoutIndex);
    const angle = item.layoutIndex * 2.399963229728653;
    return { name: item.name, x: 120 + Math.cos(angle) * distance / radius * 104,
      y: 82 + Math.sin(angle) * distance / radius * 66 };
  });
}

export function activityHistogram(topics: readonly { activity?: TopicActivity }[], buckets: number) {
  return Array.from({ length: buckets }, (_, index) => {
    const readings = topics.map(topic => bucketReading(topic.activity, index));
    return { index, value: readings.reduce<number>((sum, value) => sum + (value ?? 0), 0),
      measured: readings.filter(value => value !== null).length, total: topics.length };
  });
}

/** A/B differences are withheld when either operand lacks complete coverage. */
export function comparisonDelta(firstRate: number | null, secondRate: number | null) {
  if (firstRate === null || secondRate === null
    || !Number.isFinite(firstRate) || !Number.isFinite(secondRate)) return null;
  return { absolute: firstRate - secondRate,
    percent: secondRate === 0 ? null : (firstRate - secondRate) / secondRate * 100 };
}

/** Never overlay partition ribbons onto a lens with different Kafka sampling boundaries. */
export function partitionFrameMatches(
  partition: PartitionActivityResponse | null, snapshot: TopicActivityResponse | null,
): boolean {
  return Boolean(partition && snapshot && partition.windowStartMs === snapshot.windowStartMs
    && partition.windowEndMs === snapshot.windowEndMs
    && partition.bucketMs === snapshot.bucketMs && partition.buckets === snapshot.buckets
    && partition.partitionsMeasured <= partition.partitionsTotal
    && partition.partitions.every(row => !row.available || row.counts.length === snapshot.buckets));
}

/**
 * Sunflower slots that survive topic creation and deletion. Ranking every name alphabetically
 * moved every topic sorted after a new one, which changed the scene's signature and re-framed
 * the camera on the next metadata poll, throwing away the operator's orbit. A name keeps the
 * slot it was first given; a new name takes the lowest free slot, in alphabetical order, and a
 * deleted topic's slot is freed for the next one.
 */
export function stableLayout(previous: ReadonlyMap<string, number>, names: readonly string[]): Map<string, number> {
  const present = new Set(names);
  const next = new Map<string, number>();
  const taken = new Set<number>();
  for (const [name, slot] of previous) {
    if (present.has(name) && !taken.has(slot)) { next.set(name, slot); taken.add(slot); }
  }
  let free = 0;
  for (const name of [...names].sort((a, b) => a.localeCompare(b))) {
    if (next.has(name)) continue;
    while (taken.has(free)) free++;
    next.set(name, free);
    taken.add(free);
  }
  return next;
}

/**
 * Whether a change in the visible topics should re-frame the camera. The operator's view is
 * kept unless they changed the filter themselves, a topic they could see is gone (the frame may
 * now point at nothing), or they never moved the camera, in which case the overview simply
 * follows the cluster.
 */
export function shouldRefit(previous: readonly string[], next: readonly string[],
  frameChanged: boolean, userMoved: boolean): boolean {
  if (frameChanged || !userMoved) return true;
  const visible = new Set(next);
  return previous.some(name => !visible.has(name));
}

/**
 * The largest measured bucket among the visible topics: every ring's radius is scaled against
 * it, so the legend must quote the same number. It is relative to what is in view, and the
 * legend says so, since filtering the topics changes it.
 */
export function activityScaleMax(topics: readonly { activity?: TopicActivity }[], buckets: number): number {
  let max = 1;
  for (const topic of topics) {
    for (let index = 0; index < buckets; index++) {
      const value = bucketReading(topic.activity, index);
      if (value !== null && value > max) max = value;
    }
  }
  return max;
}

export interface StrataTap { topic: string; atMs: number }
export const DOUBLE_TAP_MS = 350;

/**
 * A touch screen fires no reliable `dblclick`, so the second tap on the same column within
 * {@link DOUBLE_TAP_MS} is the focus gesture there. Returns whether this tap completes one.
 */
export function isDoubleTap(previous: StrataTap | null, topic: string, atMs: number): boolean {
  return Boolean(previous && previous.topic === topic && atMs - previous.atMs >= 0
    && atMs - previous.atMs <= DOUBLE_TAP_MS);
}
