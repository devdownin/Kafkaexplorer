// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors
import { describe, expect, it } from 'vitest';
import { clockTime, groupReasons, keepUnchanged, refreshCostNote } from './metricsPolling';

describe('keepUnchanged', () => {
  const key = (m: { id: string }) => m.id;
  it('keeps every unchanged element as the same object, and the array itself when nothing moved', () => {
    const a = { id: 'a', v: 1 }, b = { id: 'b', v: 2 };
    const previous = [a, b];
    expect(keepUnchanged(previous, [{ id: 'a', v: 1 }, { id: 'b', v: 2 }], key)).toBe(previous);
    const next = keepUnchanged(previous, [{ id: 'a', v: 1 }, { id: 'b', v: 3 }], key);
    expect(next).not.toBe(previous);
    expect(next[0]).toBe(a);
    expect(next[1]).toEqual({ id: 'b', v: 3 });
  });

  it('reports a new order, an addition and a removal as a change', () => {
    const a = { id: 'a' }, b = { id: 'b' };
    expect(keepUnchanged([a, b], [{ id: 'b' }, { id: 'a' }], key)).toEqual([b, a]);
    expect(keepUnchanged([a], [{ id: 'a' }, { id: 'c' }], key)).toHaveLength(2);
    expect(keepUnchanged([a, b], [{ id: 'a' }], key)).toEqual([a]);
  });
});

describe('refreshCostNote', () => {
  it('says what the last refresh cost, and calls it slow past half the cycle', () => {
    expect(refreshCostNote(1234, 30000)).toEqual({ text: 'Last refresh took 1.2 s.', slow: false });
    expect(refreshCostNote(18000, 30000)).toEqual({ slow: true,
      text: 'Last refresh took 18 s of the 30 s cycle, which delays every other metric. Give it its own refresh interval (Edit).' });
    expect(refreshCostNote(undefined, 30000)).toBeNull();
  });
});

describe('groupReasons', () => {
  it('counts the reasons, most common first, and names the metrics that only wait for a click', () => {
    expect(groupReasons(['Too short', undefined, 'Too short', 'Not eligible'])).toEqual([
      { reason: 'Too short', count: 2 },
      { reason: 'Not eligible', count: 1 },
      { reason: 'Ready: press Forecast on the card', count: 1 },
    ]);
  });
});

describe('clockTime', () => {
  it('formats an instant, and nothing without one', () => {
    expect(clockTime(Date.UTC(2026, 10, 20, 14, 32))).toMatch(/\d{1,2}:\d{2}/);
    expect(clockTime(null)).toBeNull();
  });
});
