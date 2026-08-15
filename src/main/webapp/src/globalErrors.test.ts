// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors

import { describe, it, expect, vi } from 'vitest';
import {
  describeGlobalError, shouldReport, installGlobalErrorReporting, DEDUPE_MS,
} from './globalErrors';

describe('describeGlobalError', () => {
  it('surfaces the message of a thrown Error', () => {
    expect(describeGlobalError(new TypeError('crypto.randomUUID is not a function')))
      .toBe('crypto.randomUUID is not a function');
  });

  it('accepts a string, and an object carrying a message', () => {
    expect(describeGlobalError('boom')).toBe('boom');
    expect(describeGlobalError({ message: 'boom' })).toBe('boom');
  });

  it('falls back to the error name when the message is empty', () => {
    expect(describeGlobalError(new TypeError(''))).toBe('TypeError');
  });

  // staleChunk.ts already reloads for these; a toast would describe a problem that is
  // being fixed, in words nobody can act on.
  it('stays silent on a stale chunk, which is handled by reloading', () => {
    expect(describeGlobalError(new Error('Failed to fetch dynamically imported module: /a.js')))
      .toBeNull();
  });

  it.each([
    ['ResizeObserver noise', 'ResizeObserver loop completed with undelivered notifications.'],
    ['the opaque cross-origin placeholder', 'Script error.'],
    ['an empty reason', ''],
    ['whitespace only', '   '],
  ])('stays silent on %s', (_case, reason) => {
    expect(describeGlobalError(reason)).toBeNull();
  });

  it('does not throw on null, undefined or a number', () => {
    expect(describeGlobalError(null)).toBeNull();
    expect(describeGlobalError(undefined)).toBeNull();
    expect(describeGlobalError(42)).toBeNull();
  });
});

describe('shouldReport', () => {
  it('reports the first occurrence', () => {
    expect(shouldReport('boom', 1_000, { message: null, at: 0 })).toBe(true);
  });

  // A failure inside a render can fire dozens of times a second; one toast is a report,
  // thirty is a denial of service against the reader.
  it('suppresses the same message inside the window', () => {
    expect(shouldReport('boom', 1_000, { message: 'boom', at: 1_000 })).toBe(false);
    expect(shouldReport('boom', 1_000 + DEDUPE_MS - 1, { message: 'boom', at: 1_000 })).toBe(false);
  });

  it('reports again once the window has passed', () => {
    expect(shouldReport('boom', 1_000 + DEDUPE_MS + 1, { message: 'boom', at: 1_000 })).toBe(true);
  });

  it('never suppresses a different message', () => {
    expect(shouldReport('other', 1_000, { message: 'boom', at: 1_000 })).toBe(true);
  });
});

describe('installGlobalErrorReporting', () => {
  /** A window stand-in that lets a test fire the events it registered. */
  function fakeTarget() {
    const handlers = new Map<string, EventListener[]>();
    return {
      target: {
        addEventListener: (t: string, h: EventListener) =>
          void handlers.set(t, [...(handlers.get(t) ?? []), h]),
        removeEventListener: (t: string, h: EventListener) =>
          void handlers.set(t, (handlers.get(t) ?? []).filter(x => x !== h)),
      },
      fire: (type: string, event: unknown) =>
        (handlers.get(type) ?? []).forEach(h => h(event as Event)),
      count: (type: string) => (handlers.get(type) ?? []).length,
    };
  }

  it('reports an uncaught error — the case that produced "the button is inactive"', () => {
    const { target, fire } = fakeTarget();
    const report = vi.fn();
    installGlobalErrorReporting(report, { target, log: () => {} });
    fire('error', { error: new TypeError('navigator.clipboard is undefined') });
    expect(report).toHaveBeenCalledWith('navigator.clipboard is undefined');
  });

  it('reports an unhandled rejection', () => {
    const { target, fire } = fakeTarget();
    const report = vi.fn();
    installGlobalErrorReporting(report, { target, log: () => {} });
    fire('unhandledrejection', { reason: new Error('request failed') });
    expect(report).toHaveBeenCalledWith('request failed');
  });

  it('falls back to the event message when no error object is attached', () => {
    const { target, fire } = fakeTarget();
    const report = vi.fn();
    installGlobalErrorReporting(report, { target, log: () => {} });
    fire('error', { message: 'something broke' });
    expect(report).toHaveBeenCalledWith('something broke');
  });

  it('logs every occurrence but only reports one per window', () => {
    const { target, fire } = fakeTarget();
    const report = vi.fn();
    const log = vi.fn();
    let clock = 1_000;
    installGlobalErrorReporting(report, { target, log, now: () => clock });

    fire('error', { error: new Error('same') });
    fire('error', { error: new Error('same') });
    expect(report).toHaveBeenCalledTimes(1);
    // The console keeps the record even when the toast is suppressed.
    expect(log).toHaveBeenCalledTimes(2);

    clock += DEDUPE_MS + 1;
    fire('error', { error: new Error('same') });
    expect(report).toHaveBeenCalledTimes(2);
  });

  it('detaches on cleanup, so a remount cannot double-report', () => {
    const { target, fire, count } = fakeTarget();
    const report = vi.fn();
    const cleanup = installGlobalErrorReporting(report, { target, log: () => {} });
    expect(count('error')).toBe(1);
    cleanup();
    expect(count('error')).toBe(0);
    fire('error', { error: new Error('after cleanup') });
    expect(report).not.toHaveBeenCalled();
  });

  it('does nothing, and does not throw, without a usable target', () => {
    const report = vi.fn();
    expect(() => installGlobalErrorReporting(report, { target: undefined as never })()).not.toThrow();
  });
});
