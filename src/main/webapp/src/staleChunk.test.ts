// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors

import { describe, it, expect } from 'vitest';
import { isStaleChunkError, shouldReload, RELOAD_GUARD_MS } from './staleChunk';

/** Minimal in-memory stand-in for sessionStorage — the guard must be testable without a
 *  browser, and without letting one test's reload mark leak into the next. */
function fakeStorage(initial: Record<string, string> = {}) {
  const data = new Map(Object.entries(initial));
  return {
    getItem: (k: string) => data.get(k) ?? null,
    setItem: (k: string, v: string) => void data.set(k, v),
  };
}

describe('isStaleChunkError', () => {
  // The wording differs per engine and there is no error type to match on, so each of
  // these is a real message a real browser produces for the same situation.
  it.each([
    ['Chrome/Edge', 'Failed to fetch dynamically imported module: http://host/assets/Metrics-axy9HlH6.js'],
    ['Firefox', 'error loading dynamically imported module'],
    ['Safari', 'Importing a module script failed.'],
    ['Vite CSS preload', 'Unable to preload CSS for /assets/index-abc123.css'],
  ])('recognises the %s wording', (_engine, message) => {
    expect(isStaleChunkError(new Error(message))).toBe(true);
  });

  it('accepts a bare string and an object carrying a message', () => {
    expect(isStaleChunkError('Failed to fetch dynamically imported module: /a.js')).toBe(true);
    expect(isStaleChunkError({ message: 'Importing a module script failed' })).toBe(true);
  });

  // The consequence of a false positive is reloading the page under a user, so anything
  // that is merely a network or application failure must not match.
  it.each([
    ['a generic network failure', 'Failed to fetch'],
    ['an API error', 'Request failed with status code 500'],
    ['a render error', "Cannot read properties of undefined (reading 'map')"],
    ['an empty message', ''],
  ])('does not match %s', (_case, message) => {
    expect(isStaleChunkError(new Error(message))).toBe(false);
  });

  it('does not throw on null, undefined or a number', () => {
    expect(isStaleChunkError(null)).toBe(false);
    expect(isStaleChunkError(undefined)).toBe(false);
    expect(isStaleChunkError(42)).toBe(false);
  });
});

describe('shouldReload', () => {
  it('allows the first reload and records it', () => {
    const storage = fakeStorage();
    expect(shouldReload(1_000, storage)).toBe(true);
    expect(storage.getItem('kse:stale-chunk-reload')).toBe('1000');
  });

  // The guard is the whole reason this function exists: if the asset is missing because the
  // deployment is broken rather than merely new, an unguarded reload loops forever against
  // a server that is already unwell.
  it('refuses a second reload inside the guard window', () => {
    const storage = fakeStorage();
    expect(shouldReload(1_000, storage)).toBe(true);
    expect(shouldReload(1_000 + RELOAD_GUARD_MS - 1, storage)).toBe(false);
  });

  it('allows another reload once the window has passed', () => {
    const storage = fakeStorage();
    expect(shouldReload(1_000, storage)).toBe(true);
    expect(shouldReload(1_000 + RELOAD_GUARD_MS + 1, storage)).toBe(true);
  });

  it('refuses when storage is unavailable rather than looping blind', () => {
    expect(shouldReload(1_000, null)).toBe(false);
  });

  it('refuses when storage throws, instead of propagating', () => {
    const throwing = {
      getItem: () => { throw new Error('SecurityError'); },
      setItem: () => { throw new Error('SecurityError'); },
    };
    expect(shouldReload(1_000, throwing)).toBe(false);
  });

  it('ignores a corrupt stored value and reloads', () => {
    expect(shouldReload(1_000, fakeStorage({ 'kse:stale-chunk-reload': 'garbage' }))).toBe(true);
  });
});
