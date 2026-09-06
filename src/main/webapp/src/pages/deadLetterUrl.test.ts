// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors

import { describe, it, expect } from 'vitest';
import { readScreenState, writeScreenState } from './deadLetterUrl';
import { ACTIVITY_WINDOWS } from './topicActivity';

const DEFAULTS = { window: '24h', sortKey: 'volume' as const, sortDir: 'desc' as const };

describe('readScreenState', () => {
  it('reads every field the screen shares', () => {
    const state = readScreenState(
      new URLSearchParams('window=7d&q=orders&sort=share&dir=asc&open=orders.DLQ'));
    expect(state.window?.id).toBe('7d');
    expect(state).toMatchObject({
      filter: 'orders', sortKey: 'share', sortDir: 'asc', opened: 'orders.DLQ',
    });
  });

  it('ignores a value it does not know rather than erroring on it', () => {
    // Une URL est du texte qu'un tiers a pu tronquer : un identifiant retiré d'une version à
    // l'autre ne doit pas produire un écran vide.
    const state = readScreenState(new URLSearchParams('window=42y&sort=colour&dir=sideways'));
    expect(state.window).toBeUndefined();
    expect(state.sortKey).toBeUndefined();
    expect(state.sortDir).toBeUndefined();
  });

  it('says nothing about fields the URL does not carry', () => {
    expect(readScreenState(new URLSearchParams(''))).toEqual({});
  });
});

describe('writeScreenState', () => {
  const week = ACTIVITY_WINDOWS.find(w => w.id === '7d')!;

  it('writes only what departs from the default', () => {
    const params = writeScreenState(new URLSearchParams(), {
      window: week, filter: 'orders', sortKey: 'volume', sortDir: 'desc',
    }, DEFAULTS);
    expect(params.toString()).toBe('window=7d&q=orders');
  });

  it('drops a field that returns to its default, instead of pinning it', () => {
    const params = writeScreenState(new URLSearchParams('window=7d&q=orders&open=a.DLQ'), {
      window: ACTIVITY_WINDOWS.find(w => w.id === '24h'),
      filter: '   ',
      sortKey: 'volume',
      sortDir: 'desc',
    }, DEFAULTS);
    expect(params.toString()).toBe('');
  });

  it('leaves parameters that are not this screen alone', () => {
    const params = writeScreenState(new URLSearchParams('theme=dark'), { sortKey: 'name' }, DEFAULTS);
    expect(params.get('theme')).toBe('dark');
    expect(params.get('sort')).toBe('name');
  });

  it('round-trips through readScreenState', () => {
    const written = writeScreenState(new URLSearchParams(), {
      window: week, filter: 'pay', sortKey: 'share', sortDir: 'asc', opened: 'p.dlq',
    }, DEFAULTS);
    expect(readScreenState(written)).toMatchObject({
      filter: 'pay', sortKey: 'share', sortDir: 'asc', opened: 'p.dlq',
    });
  });
});
