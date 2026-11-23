// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors

import { describe, expect, it } from 'vitest';
import {
  appendProductionSnapshot, calculateProductionRate, MAX_PRODUCTION_GAP_MS,
  type ProductionSnapshot,
} from './dashboardProduction';

const sample = (sampledAt: number, offsets: Record<string, number>, totalTopics = 2): ProductionSnapshot =>
  ({ sampledAt, offsets, totalTopics, available: true });

describe('dashboard production-rate semantics', () => {
  it('measures actual produced offsets per elapsed second, not retained counts', () => {
    expect(calculateProductionRate(sample(1000, { orders: 100, payments: 10 }),
      sample(31_000, { orders: 370, payments: 40 }))).toEqual({
      messagesPerSecond: 10, measuredTopics: 2, totalTopics: 2, seconds: 30,
    });
  });

  it('excludes new, missing and reset topics instead of inventing a burst or zero', () => {
    expect(calculateProductionRate(
      sample(1000, { stable: 100, reset: 800, missing: 300 }),
      sample(31_000, { stable: 160, reset: 10, newlyCreated: 50 }, 4),
    )).toEqual({ messagesPerSecond: 2, measuredTopics: 1, totalTopics: 4, seconds: 30 });
  });

  it('treats absent/incomplete readings, zero coverage and long gaps as unknown', () => {
    expect(calculateProductionRate(null, sample(31_000, { orders: 4 }))).toBeNull();
    expect(calculateProductionRate(sample(1000, { orders: 4 }), sample(31_000, {}))).toBeNull();
    expect(calculateProductionRate(sample(1000, { orders: 4 }),
      { ...sample(31_000, { orders: 5 }), available: false })).toBeNull();
    expect(calculateProductionRate(sample(1000, { orders: 4 }),
      sample(1000 + MAX_PRODUCTION_GAP_MS + 1000, { orders: 10 }))).toBeNull();
  });

  it('reports measured zero traffic only when both offsets are known', () => {
    expect(calculateProductionRate(sample(1000, { orders: 25 }),
      sample(31_000, { orders: 25 }))?.messagesPerSecond).toBe(0);
  });

  it('resets after a long pause and does not repeat a duplicate response', () => {
    const first = sample(1000, { orders: 2 });
    const second = sample(31_000, { orders: 20 });
    const values = appendProductionSnapshot(appendProductionSnapshot([], first), second);
    expect(appendProductionSnapshot(values, second)).toBe(values);
    const afterGap = sample(31_000 + MAX_PRODUCTION_GAP_MS + 1_000, { orders: 30 });
    expect(appendProductionSnapshot(values, afterGap)).toEqual([afterGap]);
  });
});
