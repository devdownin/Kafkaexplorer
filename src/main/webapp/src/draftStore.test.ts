import { beforeEach, describe, expect, it, vi } from 'vitest';
import { MAX_DRAFT_BYTES, clearDraft, draftKey, readDraft, writeDraft } from './draftStore';

describe('draftStore', () => {
  beforeEach(() => {
    localStorage.clear();
    vi.restoreAllMocks();
  });

  it('round-trips a value', () => {
    writeDraft('t', { a: 1, b: ['x'] });
    expect(readDraft('t', null)).toEqual({ a: 1, b: ['x'] });
  });

  it('namespaces its keys', () => {
    writeDraft('t', 1);
    expect(localStorage.getItem(draftKey('t'))).toBe('1');
    expect(draftKey('t').startsWith('kse:draft:')).toBe(true);
  });

  it('returns the fallback when nothing was written', () => {
    expect(readDraft('missing', 'default')).toBe('default');
  });

  it('returns the fallback rather than throwing on corrupt JSON', () => {
    localStorage.setItem(draftKey('t'), '{not json');
    expect(readDraft('t', 'default')).toBe('default');
  });

  it('clears a draft', () => {
    writeDraft('t', 1);
    clearDraft('t');
    expect(readDraft('t', 'gone')).toBe('gone');
  });

  it('drops an oversized draft instead of keeping a stale one', () => {
    writeDraft('t', 'small');
    writeDraft('t', 'x'.repeat(MAX_DRAFT_BYTES + 1));
    // Ni le géant ni l'ancien : relire une version périmée serait pire que ne rien relire.
    expect(readDraft('t', 'gone')).toBe('gone');
  });

  it('writes nothing for a value JSON cannot represent', () => {
    writeDraft('t', undefined);
    expect(localStorage.getItem(draftKey('t'))).toBeNull();
  });

  it('never throws when storage refuses the write', () => {
    vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => {
      throw new Error('QuotaExceededError');
    });
    expect(() => writeDraft('t', { a: 1 })).not.toThrow();
  });

  it('never throws when storage refuses the read', () => {
    vi.spyOn(Storage.prototype, 'getItem').mockImplementation(() => {
      throw new Error('SecurityError');
    });
    expect(readDraft('t', 'fallback')).toBe('fallback');
  });
});
