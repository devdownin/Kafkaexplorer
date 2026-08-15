// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors

import { beforeEach, describe, expect, it, vi } from 'vitest';
import { act, renderHook } from '@testing-library/react';
import {
  MAX_DRAFT_AGE_MS, MAX_DRAFT_BYTES,
  clearDraft, draftAge, draftKey, readDraft, useDraftConflict, usePersistentState, writeDraft,
} from './draftStore';

describe('draftStore', () => {
  beforeEach(() => {
    localStorage.clear();
    vi.restoreAllMocks();
    vi.useRealTimers();
  });

  it('round-trips a value', () => {
    writeDraft('t', { a: 1, b: ['x'] });
    expect(readDraft('t', null)).toEqual({ a: 1, b: ['x'] });
  });

  it('namespaces its keys', () => {
    writeDraft('t', 1);
    expect(draftKey('t')).toBe('kse:draft:t');
    expect(localStorage.getItem(draftKey('t'))).not.toBeNull();
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

  describe('expiry', () => {
    it('ignores and erases a draft past its age', () => {
      writeDraft('t', 'old');
      vi.setSystemTime(Date.now() + MAX_DRAFT_AGE_MS + 1000);
      expect(readDraft('t', 'fresh')).toBe('fresh');
      // Effacé au passage : le relire chaque fois pour le rejeter ne sert personne.
      expect(localStorage.getItem(draftKey('t'))).toBeNull();
    });

    it('keeps a draft still within its age', () => {
      writeDraft('t', 'recent');
      vi.setSystemTime(Date.now() + MAX_DRAFT_AGE_MS - 1000);
      expect(readDraft('t', 'fresh')).toBe('recent');
    });

    it('reports the age of a usable draft, and nothing otherwise', () => {
      expect(draftAge('t')).toBeNull();
      writeDraft('t', 'x');
      expect(draftAge('t')).toBeGreaterThanOrEqual(0);
      vi.setSystemTime(Date.now() + MAX_DRAFT_AGE_MS + 1000);
      expect(draftAge('t')).toBeNull();
    });

    it('rejects and erases a draft written in an unknown shape', () => {
      // Ce qu'aurait écrit une version antérieure du module : lisible, mais pas interprétable.
      localStorage.setItem(draftKey('t'), JSON.stringify({ some: 'raw value' }));
      expect(readDraft('t', 'fallback')).toBe('fallback');
      expect(localStorage.getItem(draftKey('t'))).toBeNull();
    });
  });

  describe('usePersistentState', () => {
    it('writes nothing while the form is untouched', () => {
      renderHook(() => usePersistentState('form', { a: '' }));
      // Une simple visite ne doit pas semer une clé par champ chez quelqu'un qui n'a rien saisi.
      expect(localStorage.getItem(draftKey('form'))).toBeNull();
    });

    it('writes as soon as the value moves', () => {
      const { result } = renderHook(() => usePersistentState('form', { a: '' }));
      act(() => result.current[1]({ a: 'typed' }));
      expect(readDraft('form', null)).toEqual({ a: 'typed' });
    });

    it('restores an existing draft over the initial value', () => {
      writeDraft('form', { a: 'kept' });
      const { result } = renderHook(() => usePersistentState('form', { a: '' }));
      expect(result.current[0]).toEqual({ a: 'kept' });
    });

    it('keeps maintaining a draft that already existed, even back at the initial value', () => {
      writeDraft('form', { a: 'kept' });
      const { result } = renderHook(() => usePersistentState('form', { a: '' }));
      act(() => result.current[1]({ a: '' }));
      // Effacer ici déciderait à la place de l'appelant, dont c'est le geste explicite.
      expect(readDraft('form', null)).toEqual({ a: '' });
    });
  });

  describe('useDraftConflict', () => {
    const fireStorage = (key: string) => act(() => {
      window.dispatchEvent(new StorageEvent('storage', { key }));
    });

    it('stays quiet until another tab writes one of the watched keys', () => {
      const { result } = renderHook(() => useDraftConflict(['a', 'b']));
      expect(result.current).toBe(false);
      fireStorage(draftKey('other'));
      expect(result.current).toBe(false);
    });

    it('reports a write from another tab', () => {
      const { result } = renderHook(() => useDraftConflict(['a', 'b']));
      fireStorage(draftKey('b'));
      expect(result.current).toBe(true);
    });

    it('ignores a whole-storage clear', () => {
      const { result } = renderHook(() => useDraftConflict(['a']));
      act(() => { window.dispatchEvent(new StorageEvent('storage', { key: null })); });
      expect(result.current).toBe(false);
    });
  });
});
