// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors

/**
 * A production-rate sample uses latest offsets, never retained record counts.
 * Only topics measured in BOTH snapshots contribute; a missing partition or a
 * reset is not zero traffic. The server reads all partitions in one bounded batch.
 */
export interface ProductionSnapshot {
  offsets: Record<string, number>;
  sampledAt: number;
  totalTopics: number;
  available: boolean;
}

export interface ProductionRate {
  messagesPerSecond: number;
  measuredTopics: number;
  totalTopics: number;
  seconds: number;
}

export const PRODUCTION_SAMPLE_MS = 30_000;
export const MAX_PRODUCTION_GAP_MS = 90_000;
export const MAX_PRODUCTION_SAMPLES = 30;

export function calculateProductionRate(
  previous: ProductionSnapshot | null,
  current: ProductionSnapshot | null,
): ProductionRate | null {
  if (!previous || !current || !previous.available || !current.available) return null;
  const elapsed = current.sampledAt - previous.sampledAt;
  if (!Number.isFinite(elapsed) || elapsed < 1000 || elapsed > MAX_PRODUCTION_GAP_MS) return null;

  let delta = 0;
  let measured = 0;
  for (const [topic, end] of Object.entries(current.offsets)) {
    const start = previous.offsets[topic];
    // Kafka topic deletion/recreation and incomplete offset reads are not a burst.
    if (!Number.isSafeInteger(start) || !Number.isSafeInteger(end)
      || start < 0 || end < start) continue;
    delta += end - start;
    measured++;
  }
  if (!measured || !Number.isSafeInteger(delta)) return null;
  return {
    messagesPerSecond: delta / (elapsed / 1000),
    measuredTopics: measured,
    totalTopics: current.totalTopics,
    seconds: elapsed / 1000,
  };
}

/** Keep a new baseline after focus/long sleeps rather than smoothing across hours. */
export function appendProductionSnapshot(
  history: ProductionSnapshot[],
  next: ProductionSnapshot,
): ProductionSnapshot[] {
  if (!next.available || !Number.isFinite(next.sampledAt)) return [];
  const last = history[history.length - 1];
  if (last && next.sampledAt <= last.sampledAt) return history;
  if (last && next.sampledAt - last.sampledAt > MAX_PRODUCTION_GAP_MS) return [next];
  return [...history, next].slice(-MAX_PRODUCTION_SAMPLES);
}
