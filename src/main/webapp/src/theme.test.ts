// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors

import { beforeEach, describe, expect, it } from 'vitest';
import { act, renderHook } from '@testing-library/react';
import { applyTheme, readPreference, THEME_STORAGE_KEY, useThemePreference } from './theme';

beforeEach(() => { localStorage.clear(); });

describe('theme preference', () => {
  it('defaults to light without saved preference, including invalid legacy values', () => {
    expect(readPreference()).toBe('light');
    localStorage.setItem(THEME_STORAGE_KEY, 'system');
    expect(readPreference()).toBe('light');
    localStorage.setItem(THEME_STORAGE_KEY, 'sepia');
    expect(readPreference()).toBe('light');
    localStorage.setItem(THEME_STORAGE_KEY, 'dark');
    expect(readPreference()).toBe('dark');
  });

  it('updates the browser chrome and document scheme together', () => {
    const meta = document.createElement('meta');
    meta.name = 'theme-color';
    document.head.appendChild(meta);

    applyTheme('light');
    expect(meta.content).toBe('#f3f6fb');
    expect(document.documentElement.dataset.theme).toBe('light');
    expect(document.documentElement.classList.contains('dark')).toBe(false);

    applyTheme('dark');
    expect(meta.content).toBe('#0b0d10');
    expect(document.documentElement.dataset.theme).toBe('dark');
    expect(document.documentElement.classList.contains('dark')).toBe(true);
    meta.remove();
  });

  it('switches directly between light and dark, persisting only dark across mounts', () => {
    const first = renderHook(() => useThemePreference());
    expect(first.result.current.theme).toBe('light');
    expect(localStorage.getItem(THEME_STORAGE_KEY)).toBeNull();
    act(() => first.result.current.toggle());
    expect(first.result.current.theme).toBe('dark');
    expect(localStorage.getItem(THEME_STORAGE_KEY)).toBe('dark');
    first.unmount();

    const second = renderHook(() => useThemePreference());
    expect(second.result.current.theme).toBe('dark');
    act(() => second.result.current.toggle());
    expect(second.result.current.theme).toBe('light');
    expect(localStorage.getItem(THEME_STORAGE_KEY)).toBeNull();
    second.unmount();
  });
});
