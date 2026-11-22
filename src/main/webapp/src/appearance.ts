// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors

/**
 * Two browser-side preferences beside the colour scheme (`theme.ts`): the accent colour and the
 * density. Both are attributes on `<html>` — `data-accent`, `data-density` — read by CSS alone
 * (`accent-tokens.css`, `index.css`), so changing one repaints without a render. Neither goes to
 * the server: they describe how *this* browser shows the application, not the deployment.
 * `index.html` applies them before first paint, so a stored choice never flashes the default.
 */
export type Accent = 'indigo' | 'blue' | 'teal' | 'pink' | 'orange' | 'graphite';
export type Density = 'comfortable' | 'compact';

export const ACCENT_STORAGE_KEY = 'kse.accent';
export const DENSITY_STORAGE_KEY = 'kse.density';

/** `swatch` is the light-scheme primary, for the picker only; every contrast is checked in `accent-tokens.css`. */
export const ACCENTS: ReadonlyArray<{ id: Accent; label: string; swatch: string }> = [
  { id: 'indigo', label: 'Indigo', swatch: '#4453b8' },
  { id: 'blue', label: 'Blue', swatch: '#0066cc' },
  { id: 'teal', label: 'Teal', swatch: '#08768b' },
  { id: 'pink', label: 'Pink', swatch: '#b0225f' },
  { id: 'orange', label: 'Orange', swatch: '#a83e00' },
  { id: 'graphite', label: 'Graphite', swatch: '#3c4858' },
];
export const DENSITIES: ReadonlyArray<{ value: Density; label: string }> = [
  { value: 'comfortable', label: 'Comfortable' },
  { value: 'compact', label: 'Compact' },
];

const isAccent = (v: unknown): v is Accent => ACCENTS.some(a => a.id === v);
const isDensity = (v: unknown): v is Density => v === 'comfortable' || v === 'compact';

function read(key: string): string | null {
  try { return localStorage.getItem(key); } catch { return null; }
}
function write(key: string, value: string | null): void {
  try {
    if (value === null) localStorage.removeItem(key); else localStorage.setItem(key, value);
  } catch { /* Optional persistence. */ }
}

export function readAccent(): Accent {
  const v = read(ACCENT_STORAGE_KEY);
  return isAccent(v) ? v : 'indigo';
}
export function readDensity(): Density {
  const v = read(DENSITY_STORAGE_KEY);
  return isDensity(v) ? v : 'comfortable';
}

/** The default is stored as *absence*, so clearing site data and choosing the default are the same state. */
export function applyAccent(accent: Accent): void {
  const root = document.documentElement;
  if (accent === 'indigo') delete root.dataset.accent; else root.dataset.accent = accent;
  write(ACCENT_STORAGE_KEY, accent === 'indigo' ? null : accent);
}
export function applyDensity(density: Density): void {
  const root = document.documentElement;
  if (density === 'comfortable') delete root.dataset.density; else root.dataset.density = density;
  write(DENSITY_STORAGE_KEY, density === 'comfortable' ? null : density);
}
