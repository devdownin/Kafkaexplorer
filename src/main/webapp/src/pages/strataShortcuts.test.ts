// SPDX-License-Identifier: AGPL-3.0-or-later
import { describe, expect, it } from 'vitest';
import { strataShortcut } from './strataShortcuts';

const press = (key: string, fields: Partial<KeyboardEvent> = {}) => ({
  key, ctrlKey: false, metaKey: false, altKey: false, repeat: false, ...fields,
});

describe('Strata keyboard shortcuts', () => {
  it('reserves Ctrl/⌘+K for the global app command palette', () => {
    expect(strataShortcut(press('k', { ctrlKey: true }), false)).toBeNull();
    expect(strataShortcut(press('k', { metaKey: true }), false)).toBeNull();
  });
  it('supports local help, search, comparison and time playback', () => {
    expect(strataShortcut(press('?'), false)).toBe('help');
    expect(strataShortcut(press('/'), false)).toBe('search');
    expect(strataShortcut(press('f'), false)).toBe('focus');
    expect(strataShortcut(press('o'), false)).toBe('overview');
    expect(strataShortcut(press('c'), false)).toBe('compare');
    expect(strataShortcut(press(' '), false)).toBe('replay');
  });
  it('never hijacks form controls or auto-repeat', () => {
    expect(strataShortcut(press(' '), true)).toBeNull();
    expect(strataShortcut(press('/'), true)).toBeNull();
    expect(strataShortcut(press('f', { repeat: true }), false)).toBeNull();
    expect(strataShortcut(press('Escape'), true)).toBe('escape');
  });
});
