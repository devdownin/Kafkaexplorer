// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors

import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { act, renderHook } from '@testing-library/react';
import { applyTheme, nextPreference, readPreference, resolveTheme, THEME_STORAGE_KEY, useThemePreference } from './theme';

let listeners: Array<() => void> = [];
let systemLight = false;
beforeEach(() => {
  localStorage.clear();
  listeners = [];
  systemLight = false;
  vi.stubGlobal('matchMedia', (q: string) => ({
    get matches() { return q.includes('light') && systemLight; },
    addEventListener: (_: string, fn: () => void) => listeners.push(fn),
    removeEventListener: (_: string, fn: () => void) => { listeners = listeners.filter(l => l !== fn); },
  }));
});
afterEach(() => vi.unstubAllGlobals());

describe('theme preference', () => {
  it('cycles system → light → dark → system', () => {
    expect(nextPreference('system')).toBe('light');
    expect(nextPreference('light')).toBe('dark');
    expect(nextPreference('dark')).toBe('system');
  });

  it('reads an absent or unknown value as system', () => {
    expect(readPreference()).toBe('system');
    localStorage.setItem(THEME_STORAGE_KEY, 'sepia');
    expect(readPreference()).toBe('system');
    localStorage.setItem(THEME_STORAGE_KEY, 'dark');
    expect(readPreference()).toBe('dark');
  });

  it('resolves system against the OS and leaves an explicit choice alone', () => {
    expect(resolveTheme('system', 'light')).toBe('light');
    expect(resolveTheme('dark', 'light')).toBe('dark');
  });

  it('updates the browser chrome colour with the scheme', () => {
    const meta = document.createElement('meta');
    meta.name = 'theme-color';
    document.head.appendChild(meta);
    applyTheme('light');
    expect(meta.content).toBe('#f3f6fb');
    expect(document.documentElement.dataset.theme).toBe('light');
    meta.remove();
  });

  it('never persists the system default, and follows an OS change live', () => {
    const { result } = renderHook(() => useThemePreference());
    expect(result.current.preference).toBe('system');
    expect(localStorage.getItem(THEME_STORAGE_KEY)).toBeNull();
    expect(result.current.theme).toBe('dark');
    systemLight = true;
    act(() => listeners.forEach(l => l()));
    expect(result.current.theme).toBe('light');
    act(() => result.current.cycle());
    expect(localStorage.getItem(THEME_STORAGE_KEY)).toBe('light');
    act(() => result.current.cycle());
    act(() => result.current.cycle());
    expect(result.current.preference).toBe('system');
    expect(localStorage.getItem(THEME_STORAGE_KEY)).toBeNull();
  });
});
