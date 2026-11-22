// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors

import type { TopicActivity } from '../api/types';
import { bucketReading } from './strataTime';

export type StrataLayer = 'activity' | 'lineage' | 'traces' | 'consumers' | 'anomalies' | 'forecast';
export type StrataLayers = Record<StrataLayer, boolean>;

export const DEFAULT_STRATA_LAYERS: StrataLayers = {
  activity: true, lineage: true, traces: true, consumers: true, anomalies: true, forecast: true,
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
  const readings = activity?.counts.map((_, index) => bucketReading(activity, index)) ?? [];
  const measuredBuckets = readings.filter(value => value !== null).length;
  const allComplete = Boolean(activity?.available && readings.length > 0
    && measuredBuckets === readings.length);
  const latest = readings.at(-1) ?? null;
  const stale = receivedAt !== null && now - receivedAt > 90_000;
  return {
    status: !activity?.available ? 'unavailable' : stale ? 'stale' :
      allComplete ? 'measured' : 'partial',
    selected: bucketReading(activity, bucket),
    latestRate: latest !== null && activity?.bucketMs && activity.bucketMs > 0
      ? latest * 60000 / activity.bucketMs : null,
    total: allComplete ? readings.reduce<number>((sum, n) => sum + (n ?? 0), 0) : null,
    measuredBuckets,
    totalBuckets: readings.length,
    partitionsMeasured: activity?.partitionsMeasured ?? 0,
    partitionsTotal: activity?.partitionsTotal ?? 0,
  };
}

export function topicMapPositions(items: readonly { name: string; layoutIndex: number }[],
  totalTopics: number = items.length) {
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
    return { index, value: readings.reduce((sum, value) => sum + (value ?? 0), 0),
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
