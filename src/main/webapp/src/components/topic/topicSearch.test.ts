import { describe, it, expect } from 'vitest';
import { splitOnMatches } from './TopicSearchPanel';

describe('splitOnMatches', () => {
  it('returns the whole text when there is nothing to highlight', () => {
    expect(splitOnMatches('order shipped', '', false)).toEqual(['order shipped']);
  });

  it('puts matches at odd indexes so they can be marked', () => {
    expect(splitOnMatches('a-SKU-b', 'SKU', true)).toEqual(['a-', 'SKU', '-b']);
  });

  it('is case insensitive by default and keeps the original casing', () => {
    expect(splitOnMatches('Order SHIPPED now', 'shipped', false))
      .toEqual(['Order ', 'SHIPPED', ' now']);
  });

  it('respects case when asked to', () => {
    expect(splitOnMatches('Order SHIPPED', 'shipped', true)).toEqual(['Order SHIPPED']);
  });

  it('splits every occurrence', () => {
    expect(splitOnMatches('ab ab ab', 'ab', true)).toEqual(['', 'ab', ' ', 'ab', ' ', 'ab', '']);
  });

  it('reassembles into the original text', () => {
    const text = '{"status":"NEW","child":{"status":"NEW"}}';
    expect(splitOnMatches(text, 'status', false).join('')).toBe(text);
  });
});
