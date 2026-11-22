// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors

import type { TopicActivity } from '../../api/types';

/** The activity API is bounded; never send the entire cluster in one request. */
export const PAGE_SIZE = 24;
export const WINDOW_MS = 60 * 60 * 1000;
export const BUCKETS = 12;
export const REFRESH_MS = 45_000;

export type Coverage = 'complete' | 'partial' | 'unavailable';

export interface Column {
  name: string;
  position: { x: number; z: number };
  activity: TopicActivity | undefined;
  coverage: Coverage;
  retainedMessages: number | undefined;
}

export function coverageOf(activity: TopicActivity | undefined): Coverage {
  if (!activity?.available || activity.counts.length !== BUCKETS) return 'unavailable';
  return activity.coveredFromMs === null &&
    activity.partitionsTotal > 0 &&
    activity.partitionsMeasured === activity.partitionsTotal
      ? 'complete'
      : 'partial';
}

/** Deterministic phyllotaxis keeps the layout calm across activity refreshes. */
export function columnPosition(index: number): { x: number; z: number } {
  const angle = index * Math.PI * (3 - Math.sqrt(5));
  const radius = 2.9 * Math.sqrt(index);
  return {
    x: Math.cos(angle) * radius,
    z: Math.sin(angle) * radius,
  };
}

/**
 * Every column is present even when the activity read was cut by a server budget.
 * A missing measurement is never converted into 12 zero buckets.
 */
export function columnsFor(
  names: readonly string[],
  activities: Record<string, TopicActivity> | undefined,
  topicSizes: Record<string, number>,
): Column[] {
  return names.map((name, i) => {
    const activity = activities?.[name];
    return {
      name,
      position: columnPosition(i),
      activity,
      coverage: coverageOf(activity),
      retainedMessages: topicSizes[name],
    };
  });
}

export function activityPeak(columns: readonly Column[]): number {
  return Math.max(1, ...columns.flatMap(column =>
    column.coverage === 'unavailable' ? [] : (column.activity?.counts ?? [])));
}

/** Bucket radius has a square-root scale, with the same reference across this page. */
export function ringRadius(value: number, peak: number): number {
  return 0.45 + 0.45 * Math.sqrt(Math.max(0, value) / Math.max(1, peak));
}

export function formatQuantity(n: number | undefined): string {
  return n === undefined ? 'Unknown' : new Intl.NumberFormat('en', { maximumFractionDigits: 0 }).format(n);
}
