// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors

import { beforeEach, describe, expect, it } from 'vitest';
import {
  ACCENTS, ACCENT_STORAGE_KEY, DENSITY_STORAGE_KEY,
  applyAccent, applyDensity, readAccent, readDensity,
} from './appearance';

beforeEach(() => {
  localStorage.clear();
  delete document.documentElement.dataset.accent;
  delete document.documentElement.dataset.density;
});

describe('accent', () => {
  it('reads an absent or unknown value as the default', () => {
    expect(readAccent()).toBe('indigo');
    localStorage.setItem(ACCENT_STORAGE_KEY, 'chartreuse');
    expect(readAccent()).toBe('indigo');
  });

  it('sets the attribute and remembers every accent but the default', () => {
    for (const { id } of ACCENTS.filter(a => a.id !== 'indigo')) {
      applyAccent(id);
      expect(document.documentElement.dataset.accent).toBe(id);
      expect(readAccent()).toBe(id);
    }
  });

  it('stores the default as absence', () => {
    applyAccent('teal');
    applyAccent('indigo');
    expect(document.documentElement.dataset.accent).toBeUndefined();
    expect(localStorage.getItem(ACCENT_STORAGE_KEY)).toBeNull();
  });
});

describe('density', () => {
  it('defaults to comfortable and stores only compact', () => {
    expect(readDensity()).toBe('comfortable');
    applyDensity('compact');
    expect(document.documentElement.dataset.density).toBe('compact');
    expect(localStorage.getItem(DENSITY_STORAGE_KEY)).toBe('compact');
    expect(readDensity()).toBe('compact');
    applyDensity('comfortable');
    expect(document.documentElement.dataset.density).toBeUndefined();
    expect(localStorage.getItem(DENSITY_STORAGE_KEY)).toBeNull();
  });
});
