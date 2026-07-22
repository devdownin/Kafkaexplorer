import { describe, it, expect } from 'vitest';
import { NAV_ITEMS, groupNavItems, resolvePageName } from './navigation';

describe('resolvePageName', () => {
  it('resolves exact routes to their nav name', () => {
    expect(resolvePageName('/')).toBe('Dashboard');
    expect(resolvePageName('/query')).toBe('SQL Editor');
    expect(resolvePageName('/config')).toBe('Settings');
  });

  it('resolves dynamic routes', () => {
    expect(resolvePageName('/topic/orders.created')).toBe('Topic Explorer');
    expect(resolvePageName('/metrics/help')).toBe('Metrics Guide');
  });

  it('returns empty string for unknown routes', () => {
    expect(resolvePageName('/does-not-exist')).toBe('');
  });
});

describe('groupNavItems', () => {
  it('keeps the ungrouped Dashboard in its own leading section', () => {
    const sections = groupNavItems(NAV_ITEMS);
    expect(sections[0].group).toBeUndefined();
    expect(sections[0].items.map((i) => i.name)).toEqual(['Dashboard']);
  });

  it('groups consecutive items sharing a group label', () => {
    const sections = groupNavItems(NAV_ITEMS);
    const explore = sections.find((s) => s.group === 'Explore');
    expect(explore?.items.map((i) => i.name)).toEqual(['SQL Editor', 'Compare', 'Stream Flow']);
    // Every item ends up in exactly one section.
    const total = sections.reduce((n, s) => n + s.items.length, 0);
    expect(total).toBe(NAV_ITEMS.length);
  });
});
