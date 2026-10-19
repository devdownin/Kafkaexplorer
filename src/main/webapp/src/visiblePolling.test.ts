// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { startVisiblePolling } from './visiblePolling';

function setVisibility(state: 'visible' | 'hidden') {
  Object.defineProperty(document, 'visibilityState', { configurable: true, get: () => state });
  document.dispatchEvent(new Event('visibilitychange'));
}

describe('startVisiblePolling', () => {
  beforeEach(() => { vi.useFakeTimers(); });
  afterEach(() => { vi.useRealTimers(); setVisibility('visible'); });

  it('reads on every period while the tab is visible', () => {
    const read = vi.fn();
    const stop = startVisiblePolling(read, 1000);
    vi.advanceTimersByTime(3000);
    expect(read).toHaveBeenCalledTimes(3);
    stop();
  });

  it('asks nothing of the backend behind a hidden tab, and reads once on return', () => {
    const read = vi.fn();
    const stop = startVisiblePolling(read, 1000);
    setVisibility('hidden');
    read.mockClear();
    vi.advanceTimersByTime(10_000);
    expect(read).not.toHaveBeenCalled();
    setVisibility('visible');
    expect(read).toHaveBeenCalledTimes(1);
    stop();
  });

  it('leaves no timer and no listener behind once stopped', () => {
    const read = vi.fn();
    startVisiblePolling(read, 1000)();
    vi.advanceTimersByTime(5000);
    setVisibility('hidden');
    setVisibility('visible');
    expect(read).not.toHaveBeenCalled();
  });
});
