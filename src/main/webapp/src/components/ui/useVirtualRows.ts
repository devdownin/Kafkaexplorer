// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors

import { useCallback, useEffect, useLayoutEffect, useState, type RefObject } from 'react';

/**
 * Fenêtrage (windowing) sans dépendance pour de longues listes à hauteur de
 * ligne constante. On ne monte dans le DOM que les lignes visibles (plus une
 * marge `overscan`), le reste est remplacé par deux cales (`padTop`/`padBottom`)
 * qui préservent la hauteur totale et donc la barre de défilement.
 *
 * Prévu pour une grille de résultats SQL : des milliers de lignes peuvent
 * arriver (LIMIT élevé) et tout rendre saturait le thread principal. Réservé
 * aux hauteurs de ligne uniformes — les cellules doivent être forcées sur une
 * seule ligne (`whitespace-nowrap`) côté appelant.
 */
export interface VirtualWindow {
  /** Index (inclus) de la première ligne à monter. */
  start: number;
  /** Index (exclu) de la dernière ligne à monter. */
  end: number;
  /** Hauteur de la cale supérieure, en pixels. */
  padTop: number;
  /** Hauteur de la cale inférieure, en pixels. */
  padBottom: number;
}

export function useVirtualRows(
  scrollRef: RefObject<HTMLElement | null>,
  rowCount: number,
  rowHeight: number,
  overscan = 12,
): VirtualWindow {
  const [range, setRange] = useState({ start: 0, end: Math.min(rowCount, overscan * 3) });

  const recompute = useCallback(() => {
    const el = scrollRef.current;
    if (!el || rowHeight <= 0) return;
    const first = Math.max(0, Math.floor(el.scrollTop / rowHeight) - overscan);
    const visible = Math.ceil(el.clientHeight / rowHeight) + overscan * 2;
    const last = Math.min(rowCount, first + visible);
    setRange(prev => (prev.start === first && prev.end === last ? prev : { start: first, end: last }));
  }, [scrollRef, rowCount, rowHeight, overscan]);

  // useLayoutEffect : on cale la fenêtre avant la première peinture pour éviter
  // un flash de liste vide au montage ou au changement de jeu de résultats.
  useLayoutEffect(() => { recompute(); }, [recompute]);

  useEffect(() => {
    const el = scrollRef.current;
    if (!el) return;
    el.addEventListener('scroll', recompute, { passive: true });
    window.addEventListener('resize', recompute);
    return () => {
      el.removeEventListener('scroll', recompute);
      window.removeEventListener('resize', recompute);
    };
  }, [scrollRef, recompute]);

  const start = Math.min(range.start, Math.max(0, rowCount));
  const end = Math.min(Math.max(range.end, start), rowCount);
  return {
    start,
    end,
    padTop: start * rowHeight,
    padBottom: Math.max(0, (rowCount - end) * rowHeight),
  };
}
