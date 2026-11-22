// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors

import type { TopicActivity } from '../api/types';

/** The common sampling window belongs to the response, not the browser clock. */
export interface TimeWindow {
  startMs: number;
  endMs: number;
  bucketMs: number;
  count: number;
}

export function bucketInterval(window: TimeWindow, index: number) {
  const safe = Math.min(Math.max(0, Math.trunc(index)), Math.max(0, window.count - 1));
  const startMs = window.startMs + safe * window.bucketMs;
  return { startMs, endMs: Math.min(startMs + window.bucketMs, window.endMs), index: safe };
}

/** An unavailable or retention-truncated interval cannot truthfully be treated as quiet. */
export function bucketReading(activity: TopicActivity | undefined, index: number): number | null {
  if (!activity?.available || activity.partitionsMeasured !== activity.partitionsTotal) return null;
  if (index < 0 || index >= activity.counts.length) return null;
  const startMs = activity.windowStartMs + index * activity.bucketMs;
  const endMs = startMs + activity.bucketMs;
  if (activity.coveredFromMs !== null && endMs > activity.coveredFromMs) {
    // Oldest surviving record is not proof of the full bucket; require the *start*
    // to be at or after coveredFromMs rather than testing endMs.
    if (startMs < activity.coveredFromMs) return null;
  }
  if (activity.coveredFromMs !== null && startMs < activity.coveredFromMs) return null;
  const value = activity.counts[index];
  return Number.isFinite(value) && value >= 0 ? value : null;
}

export type ActivityAnomaly = { kind: 'spike' | 'drop'; ratio: number; value: number; baseline: number };

/**
 * Conservative local heuristic, never a diagnostic alert: compare a fully
 * covered bucket with at least four prior fully covered, nonnegative buckets.
 * Small idle clusters and missing offsets must not produce fake alerts.
 */
export function detectActivityAnomaly(activity: TopicActivity | undefined, index: number): ActivityAnomaly | null {
  const value = bucketReading(activity, index);
  if (value === null || !activity || index < 4) return null;
  const history = Array.from({ length: 4 }, (_, i) => bucketReading(activity, index - 4 + i));
  if (history.some(x => x === null)) return null;
  const sorted = (history as number[]).sort((a, b) => a - b);
  const baseline = (sorted[1] + sorted[2]) / 2;
  if (baseline < 10) return null;
  const ratio = value / baseline;
  if (value >= 40 && ratio >= 4) return { kind: 'spike', ratio, value, baseline };
  if (baseline >= 40 && ratio <= 0.15) return { kind: 'drop', ratio, value, baseline };
  return null;
}
