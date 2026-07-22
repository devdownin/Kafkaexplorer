import { describe, it, expect } from 'vitest';
import { renderHook, act } from '@testing-library/react';
import { createRef } from 'react';
import { useVirtualRows } from './useVirtualRows';

/** jsdom ne calcule pas la mise en page : on simule un conteneur défilable. */
function scrollEl(scrollTop: number, clientHeight: number) {
  const el = document.createElement('div');
  Object.defineProperty(el, 'scrollTop', { value: scrollTop, writable: true, configurable: true });
  Object.defineProperty(el, 'clientHeight', { value: clientHeight, writable: true, configurable: true });
  return el;
}

describe('useVirtualRows', () => {
  it('mounts only the visible window plus overscan, with matching spacers', () => {
    const ref = createRef<HTMLDivElement>();
    // 10 000 lignes de 40px, fenêtre de 400px (10 visibles), défilé à 4000px (ligne 100).
    (ref as { current: HTMLElement }).current = scrollEl(4000, 400);
    const { result } = renderHook(() => useVirtualRows(ref, 10_000, 40, 5));

    // first = floor(4000/40) - 5 = 95 ; visible = ceil(400/40) + 5*2 = 20 ; last = 115.
    expect(result.current.start).toBe(95);
    expect(result.current.end).toBe(115);
    expect(result.current.padTop).toBe(95 * 40);
    expect(result.current.padBottom).toBe((10_000 - 115) * 40);
    // Les cales encadrent exactement les lignes montées : hauteur totale préservée.
    const mounted = result.current.end - result.current.start;
    expect(result.current.padTop + mounted * 40 + result.current.padBottom).toBe(10_000 * 40);
  });

  it('clamps the window to the row count near the end and never over-pads', () => {
    const ref = createRef<HTMLDivElement>();
    (ref as { current: HTMLElement }).current = scrollEl(0, 400);
    const { result } = renderHook(() => useVirtualRows(ref, 8, 40, 5));
    expect(result.current.start).toBe(0);
    expect(result.current.end).toBe(8);
    expect(result.current.padBottom).toBe(0);
  });

  it('recomputes the window on scroll', () => {
    const ref = createRef<HTMLDivElement>();
    const el = scrollEl(0, 400);
    (ref as { current: HTMLElement }).current = el;
    const { result } = renderHook(() => useVirtualRows(ref, 10_000, 40, 5));
    expect(result.current.start).toBe(0);

    act(() => {
      (el as unknown as { scrollTop: number }).scrollTop = 8000;
      el.dispatchEvent(new Event('scroll'));
    });
    expect(result.current.start).toBe(Math.floor(8000 / 40) - 5); // 195
  });
});
