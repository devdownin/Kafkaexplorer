// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors

import { useEffect, useState } from 'react';

/**
 * Light is the first-visit default, independently of the operating-system preference.
 * A deliberate dark selection is stored in this browser. Clearing the key restores light.
 */
export type ResolvedTheme = 'light' | 'dark';

export const THEME_STORAGE_KEY = 'kse.theme';
/** Browser chrome (Safari tab bar, Android status bar) — mirrors `--kse-background`. */
export const THEME_COLORS: Record<ResolvedTheme, string> = { light: '#f3f6fb', dark: '#0b0d10' };

export function readPreference(): ResolvedTheme {
  try {
    return localStorage.getItem(THEME_STORAGE_KEY) === 'dark' ? 'dark' : 'light';
  } catch { /* Storage can be disabled. */ }
  return 'light';
}

export function applyTheme(theme: ResolvedTheme): void {
  const root = document.documentElement;
  root.dataset.theme = theme;
  root.classList.toggle('dark', theme === 'dark');
  document.querySelector('meta[name="theme-color"]')?.setAttribute('content', THEME_COLORS[theme]);
}

export function useThemePreference() {
  const [theme, setTheme] = useState<ResolvedTheme>(readPreference);

  useEffect(() => { applyTheme(theme); }, [theme]);
  useEffect(() => {
    try {
      if (theme === 'dark') localStorage.setItem(THEME_STORAGE_KEY, 'dark');
      else localStorage.removeItem(THEME_STORAGE_KEY);
    } catch { /* Optional persistence. */ }
  }, [theme]);

  return {
    theme,
    toggle: () => setTheme(current => current === 'light' ? 'dark' : 'light'),
  };
}
