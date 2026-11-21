// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors

import { useEffect, useState } from 'react';

/**
 * The appearance preference has three states, as on macOS and iOS: an explicit light or dark,
 * or `system`, which follows `prefers-color-scheme` *live*. `system` is never written to
 * storage as a resolved value — persisting the OS default on first mount is what used to turn
 * it into a frozen choice that no later OS change could reach.
 */
export type ThemePreference = 'system' | 'light' | 'dark';
export type ResolvedTheme = 'light' | 'dark';

export const THEME_STORAGE_KEY = 'kse.theme';
const LIGHT_QUERY = '(prefers-color-scheme: light)';
/** Browser chrome (Safari tab bar, Android status bar) — mirrors `--kse-background`. */
export const THEME_COLORS: Record<ResolvedTheme, string> = { light: '#f3f6fb', dark: '#0b0d10' };

export const nextPreference = (p: ThemePreference): ThemePreference =>
  p === 'system' ? 'light' : p === 'light' ? 'dark' : 'system';

export function readPreference(): ThemePreference {
  try {
    const saved = localStorage.getItem(THEME_STORAGE_KEY);
    if (saved === 'light' || saved === 'dark') return saved;
  } catch { /* Storage can be disabled. */ }
  return 'system';
}

const systemTheme = (): ResolvedTheme =>
  typeof window !== 'undefined' && window.matchMedia?.(LIGHT_QUERY).matches ? 'light' : 'dark';

export function resolveTheme(p: ThemePreference, system: ResolvedTheme): ResolvedTheme {
  return p === 'system' ? system : p;
}

export function applyTheme(theme: ResolvedTheme): void {
  const root = document.documentElement;
  root.dataset.theme = theme;
  root.classList.toggle('dark', theme === 'dark');
  document.querySelector('meta[name="theme-color"]')?.setAttribute('content', THEME_COLORS[theme]);
}

export function useThemePreference() {
  const [preference, setPreference] = useState<ThemePreference>(readPreference);
  const [system, setSystem] = useState<ResolvedTheme>(systemTheme);

  useEffect(() => {
    const query = window.matchMedia?.(LIGHT_QUERY);
    if (!query) return;
    const onChange = () => setSystem(query.matches ? 'light' : 'dark');
    query.addEventListener?.('change', onChange);
    return () => query.removeEventListener?.('change', onChange);
  }, []);

  const theme = resolveTheme(preference, system);
  useEffect(() => { applyTheme(theme); }, [theme]);
  useEffect(() => {
    try {
      if (preference === 'system') localStorage.removeItem(THEME_STORAGE_KEY);
      else localStorage.setItem(THEME_STORAGE_KEY, preference);
    } catch { /* Optional persistence. */ }
  }, [preference]);

  return { preference, theme, cycle: () => setPreference(nextPreference) };
}
