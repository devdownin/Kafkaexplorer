// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors

import { describe, expect, it } from 'vitest';
import { topProducers, matchesQuickFilter } from './dashboardInsights';
import type { ProductionSnapshot } from './dashboardProduction';

const snapshot = (sampledAt: number, offsets: Record<string, number>, available = true): ProductionSnapshot =>
  ({ sampledAt, offsets, available, totalTopics: 6 });

describe('Dashboard producer ranking', () => {
  it('ranks all comparable topics by observed rate, not retained count or page order', () => {
    expect(topProducers(
      snapshot(1000, { a: 1, b: 100, c: 3, d: 0, e: 10, f: 1 }),
      snapshot(31_000, { a: 31, b: 700, c: 3, d: 300, e: 100, f: 2 }),
      3,
    )).toEqual([
      { topic: 'b', messagesPerSecond: 20 },
      { topic: 'd', messagesPerSecond: 10 },
      { topic: 'e', messagesPerSecond: 3 },
    ]);
  });

  it('excludes unreadable, new and reset offsets; resolves ties deterministically', () => {
    expect(topProducers(
      snapshot(1000, { zebra: 10, alpha: 10, reset: 100 }),
      snapshot(11_000, { zebra: 20, alpha: 20, reset: 1, newTopic: 200 }),
    )).toEqual([
      { topic: 'alpha', messagesPerSecond: 1 },
      { topic: 'zebra', messagesPerSecond: 1 },
    ]);
  });

  it('does not invent rankings for missing or stale snapshots', () => {
    expect(topProducers(null, snapshot(31_000, { a: 1 }))).toEqual([]);
    expect(topProducers(snapshot(1000, { a: 1 }, false), snapshot(31_000, { a: 2 }))).toEqual([]);
    expect(topProducers(snapshot(1000, { a: 1 }), snapshot(31_000, { a: 0 }))).toEqual([]);
  });
});

describe('Dashboard diagnostic quick filters', () => {
  const sizes = { empty: 0, populated: 50, unreadable: 0 };
  const unknown = ['unreadable'];
  const silent = new Set(['populated']);

  it('only calls a successfully measured zero count empty', () => {
    expect(matchesQuickFilter('empty', 'empty', sizes, unknown, silent)).toBe(true);
    expect(matchesQuickFilter('unreadable', 'empty', sizes, unknown, silent)).toBe(false);
    expect(matchesQuickFilter('populated', 'empty', sizes, unknown, silent)).toBe(false);
  });

  it('filters silence to the explicit frozen page selection', () => {
    expect(matchesQuickFilter('populated', 'silent', sizes, unknown, silent)).toBe(true);
    expect(matchesQuickFilter('empty', 'silent', sizes, unknown, silent)).toBe(false);
    expect(matchesQuickFilter('empty', 'all', sizes, unknown, silent)).toBe(true);
  });
});
