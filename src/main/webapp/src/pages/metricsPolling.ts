// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors

/**
 * What the Metrics page keeps between two polls, and the sentences it derives from them — pure, so
 * they are tested without mounting the page.
 */

/**
 * The polled list, with every element that did not change kept as the SAME object.
 *
 * The page compared the whole answer: one metric refreshed and the whole array was replaced, so
 * every card — each with its Recharts graphs — rendered again on every poll. Kept element by
 * element, a memoised card renders only when its own metric moved. The previous array itself is
 * returned when nothing changed at all, order included.
 */
export function keepUnchanged<T>(previous: readonly T[], next: readonly T[], key: (item: T) => string): T[] {
  const before = new Map(previous.map(item => [key(item), item]));
  let changed = previous.length !== next.length;
  const kept = next.map((item, i) => {
    const old = before.get(key(item));
    if (old !== undefined && JSON.stringify(old) === JSON.stringify(item)) {
      if (previous[i] !== old) changed = true;
      return old;
    }
    changed = true;
    return item;
  });
  return changed ? kept : (previous as T[]);
}

/**
 * The note a card carries about what its last refresh cost, or null when there is nothing to say.
 *
 * The refresh loop is single threaded by design, so a metric that takes half the loop's tick
 * delays every other one. The remedy is per metric — a refresh interval of its own — and the card
 * is where it is set, so the card is where it is said.
 */
export function refreshCostNote(durationMs: number | undefined, tickMs: number | undefined):
  { text: string; slow: boolean } | null {
  if (durationMs === undefined || !Number.isFinite(durationMs)) return null;
  const seconds = durationMs / 1000;
  const shown = seconds < 10 ? seconds.toFixed(1) : String(Math.round(seconds));
  const slow = tickMs !== undefined && tickMs > 0 && durationMs > tickMs / 2;
  return {
    text: slow
      ? `Last refresh took ${shown} s of the ${Math.round(tickMs! / 1000)} s cycle, which delays every other metric. Give it its own refresh interval (Edit).`
      : `Last refresh took ${shown} s.`,
    slow,
  };
}

/** The reasons the metrics shown have no forecast, most common first, with how many each covers. */
export function groupReasons(reasons: readonly (string | undefined)[]): { reason: string; count: number }[] {
  const counts = new Map<string, number>();
  for (const reason of reasons) {
    const key = reason ?? 'Ready: press Forecast on the card';
    counts.set(key, (counts.get(key) ?? 0) + 1);
  }
  return [...counts.entries()].map(([reason, count]) => ({ reason, count }))
    .sort((a, b) => b.count - a.count || a.reason.localeCompare(b.reason));
}

/** "14:32", the way the cards date a forecast — an instant, not an age, so it never goes stale on screen. */
export function clockTime(at: number | null | undefined): string | null {
  return at === null || at === undefined || !Number.isFinite(at) ? null
    : new Date(at).toLocaleTimeString(undefined, { hour: '2-digit', minute: '2-digit' });
}

/**
 * Whether the page should read the forecasts on this tick. A forecast only changes when the pilot's
 * cycle writes one, and the server says when the next cycle starts (`nextCycleAt`): reading every
 * 30 s in between fetched the same answer. Read when nothing has been read yet, while a cycle runs
 * (`nextCycleAt` is null then), once the next cycle is due, and at least every `maxGapMs` — the
 * history a series is still collecting moves with each metric refresh, not with the cycle.
 */
export function forecastReadDue(now: number, nextCycleAt: number | null | undefined,
  lastReadAt: number | null, maxGapMs = 120_000): boolean {
  if (lastReadAt === null) return true;
  if (nextCycleAt === null || nextCycleAt === undefined) return true;
  if (now - lastReadAt >= maxGapMs) return true;
  return now >= nextCycleAt;
}

/** What the page remembers of each metric's predicted threshold between two forecasts. */
export interface ReachSeen { key: string; at: number; level: 'warning' | 'critical' }
export interface ReachMemory {
  /** False until the first forecasts have been read: nothing is "new" on the first look. */
  initialized: boolean;
  seen: Record<string, ReachSeen>;
  /** How far the predicted instant moved at the last new forecast (ms, negative = sooner). */
  shifts: Record<string, number>;
}
export const EMPTY_REACH_MEMORY: ReachMemory = { initialized: false, seen: {}, shifts: {} };

/**
 * The memory after one read, and the reaches that are news: a metric whose forecast did not reach a
 * threshold before and does now, or one that escalated from warning to critical. A shift is kept
 * only when a new forecast (another result key) predicts the same level: comparing the instants of
 * two different thresholds says nothing.
 */
export function nextReachMemory(memory: ReachMemory,
  current: { metricId: string; key: string; at: number; level: 'warning' | 'critical' }[]):
  { memory: ReachMemory; news: { metricId: string; level: 'warning' | 'critical'; at: number }[] } {
  const seen: Record<string, ReachSeen> = {};
  const shifts: Record<string, number> = {};
  const news: { metricId: string; level: 'warning' | 'critical'; at: number }[] = [];
  for (const reach of current) {
    const before = memory.seen[reach.metricId];
    seen[reach.metricId] = { key: reach.key, at: reach.at, level: reach.level };
    if (!before) {
      if (memory.initialized) news.push({ metricId: reach.metricId, level: reach.level, at: reach.at });
      continue;
    }
    if (before.key === reach.key) {
      if (memory.shifts[reach.metricId] !== undefined) shifts[reach.metricId] = memory.shifts[reach.metricId];
      continue;
    }
    if (before.level === reach.level) shifts[reach.metricId] = reach.at - before.at;
    else if (reach.level === 'critical' && memory.initialized) news.push({ metricId: reach.metricId, level: reach.level, at: reach.at });
  }
  const unchanged = memory.initialized && JSON.stringify(seen) === JSON.stringify(memory.seen)
    && JSON.stringify(shifts) === JSON.stringify(memory.shifts);
  return { memory: unchanged ? memory : { initialized: true, seen, shifts }, news };
}

/** " · 4 min sooner than the previous forecast", or nothing under a minute's difference. */
export function reachShiftText(shiftMs: number | undefined): string {
  if (shiftMs === undefined || Math.abs(shiftMs) < 60_000) return '';
  const minutes = Math.round(Math.abs(shiftMs) / 60_000);
  const amount = minutes < 90 ? `${minutes} min` : `${Math.round(minutes / 60)} h`;
  return ` · ${amount} ${shiftMs < 0 ? 'sooner' : 'later'} than the previous forecast`;
}
