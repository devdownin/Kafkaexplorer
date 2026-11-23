// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors

import type { ProductionSnapshot } from './dashboardProduction';
import { MAX_PRODUCTION_GAP_MS } from './dashboardProduction';

/** Strictly comparable produced offsets; not retained message counts or activity curves. */
export interface RankedProducer {
  topic: string;
  messagesPerSecond: number;
}

export function topProducers(
  before: ProductionSnapshot | null,
  after: ProductionSnapshot | null,
  limit = 5,
): RankedProducer[] {
  if (!before?.available || !after?.available) return [];
  const ms = after.sampledAt - before.sampledAt;
  if (!Number.isFinite(ms) || ms < 1000 || ms > MAX_PRODUCTION_GAP_MS) return [];
  const ranked: RankedProducer[] = [];
  for (const [topic, current] of Object.entries(after.offsets)) {
    const previous = before.offsets[topic];
    if (!Number.isSafeInteger(previous) || !Number.isSafeInteger(current)
      || previous < 0 || current < previous) continue;
    ranked.push({ topic, messagesPerSecond: (current - previous) * 1000 / ms });
  }
  return ranked.sort((a, b) => b.messagesPerSecond - a.messagesPerSecond || a.topic.localeCompare(b.topic))
    .slice(0, Math.max(0, limit));
}

export type QuickFilter = 'all' | 'empty' | 'silent';

/**
 * Silence is only known for the page whose activity was sampled. The caller
 * freezes those topic names on click; it must never claim a cluster-wide result.
 */
export function matchesQuickFilter(
  topic: string,
  filter: QuickFilter,
  topicSizes: Record<string, number>,
  unmeasuredTopics: string[] | undefined,
  silentTopics: ReadonlySet<string>,
): boolean {
  if (filter === 'all') return true;
  if (filter === 'silent') return silentTopics.has(topic);
  return !unmeasuredTopics?.includes(topic) && topicSizes[topic] === 0;
}
