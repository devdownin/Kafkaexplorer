// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors

import { useCallback, useEffect, useRef, useState } from 'react';
import type { Dispatch, PointerEvent as ReactPointerEvent, RefObject, SetStateAction } from 'react';

/** Le pan/zoom d'un graphe : une translation et une échelle. */
export interface Transform {
  x: number;
  y: number;
  scale: number;
}

export interface Viewport {
  width: number;
  height: number;
}

/**
 * Zoom ancré sur un point de la vue : ce point reste sous le curseur.
 *
 * Pure et testée à part, parce que c'est la seule arithmétique du lot — le reste du hook est
 * de la plomberie d'événements que jsdom ne sait pas exercer utilement.
 */
export function zoomTransform(
  current: Transform, factor: number, px: number, py: number,
  minScale: number, maxScale: number,
): Transform {
  const scale = Math.max(minScale, Math.min(maxScale, current.scale * factor));
  const ratio = scale / current.scale;
  return { scale, x: px - ratio * (px - current.x), y: py - ratio * (py - current.y) };
}

export interface GraphViewportOptions {
  /** Cadrage de départ. Chaque page a le sien, et c'est ce que son bouton « reset » vise. */
  initial?: Transform;
  minScale?: number;
  maxScale?: number;
  /**
   * Sélecteur des éléments sur lesquels un appui ne doit **pas** démarrer un déplacement —
   * `'[data-node]'` là où un nœud est cliquable. Absent, tout le fond est saisissable.
   */
  ignoreDragOn?: string;
  /** Effet de bord pendant le glissé : fermer une infobulle que le déplacement périme. */
  onPanMove?: () => void;
}

export interface GraphViewport {
  transform: Transform;
  setTransform: Dispatch<SetStateAction<Transform>>;
  svgRef: RefObject<SVGSVGElement | null>;
  /**
   * Vrai dès que l'opérateur a cadré la vue lui-même. Une page qui recadre automatiquement
   * (à l'arrivée du modèle, à un changement de largeur) doit le consulter : re-centrer sous
   * quelqu'un qui vient de zoomer sur une table est pire que le décadrage qu'on corrige.
   * `markFitted()` le remet à faux — c'est ce que fait un cadrage explicite.
   */
  viewAdjusted: RefObject<boolean>;
  markFitted: () => void;
  /**
   * « Ce cadrage est celui de l'opérateur. » `panBy` et `zoomAround` le disent d'eux-mêmes ; une
   * page qui pose son propre `setTransform` — centrer sur une entité, sur un point du graphe —
   * doit le dire ici, sans quoi le recadrage automatique le défera au prochain redimensionnement.
   */
  markAdjusted: () => void;
  /**
   * Le geste a-t-il glissé, ou était-ce un clic ? Un glissé qui se termine sur un nœud ne doit
   * pas être lu comme une sélection de ce nœud.
   */
  dragged: RefObject<boolean>;
  /** Un déplacement est-il en cours ? Une infobulle ne doit pas s'ouvrir sous un glissé. */
  isPanning: RefObject<boolean>;
  /** Taille de la zone de graphe, sans relire le DOM deux fois. */
  viewport: () => Viewport | null;
  panBy: (dx: number, dy: number) => void;
  zoomAround: (factor: number, px: number, py: number) => void;
  /** Zoom des boutons et du clavier : le centre du canevas reste ancré. */
  zoomFromCenter: (factor: number) => void;
  onPointerDown: (e: ReactPointerEvent<SVGSVGElement>) => void;
  onPointerMove: (e: ReactPointerEvent<SVGSVGElement>) => void;
  onPointerUp: (e: ReactPointerEvent<SVGSVGElement>) => void;
}

/**
 * Le pan/zoom partagé des trois graphes SVG — Lineage, Stream Flow et Data Model.
 *
 * Les trois portaient la même implémentation, recopiée : les mêmes refs `isPanning` / `lastPos`,
 * les mêmes trois gestionnaires de pointeur au caractère près, le même écouteur `wheel` non
 * passif, et le même barème de déplacement au clavier. Une centaine de lignes en trois
 * exemplaires, ce qui est exactement la forme qui laisse une correction atterrir sur une page et
 * pas sur les deux autres — et c'est déjà arrivé ici, le passage aux événements *pointeur* (sans
 * lequel une tablette ne peut pas déplacer le graphe du tout) ayant dû être fait trois fois.
 *
 * Ce que le hook prend en charge est la **mécanique** : l'état, les gestes, la mesure de la vue.
 * Ce qu'il laisse aux pages est la **politique** — ce que `0` recadre, ce qu'`Échap` désélectionne,
 * quel nœud est ramené sous les yeux — parce que ces réponses diffèrent réellement d'un graphe à
 * l'autre et qu'un hook qui les absorberait via trois rappels de plus serait plus difficile à
 * lire que les quarante lignes qu'il remplace. Les pages construisent donc leur gestionnaire
 * clavier sur `panBy` et `zoomFromCenter`, ce qui le ramène à une poignée de lignes chacune.
 *
 * Les événements sont des événements *pointeur* et non souris, et `touch-action: none` est posé
 * par les pages sur le SVG : c'est ce qui rend le déplacement possible au doigt sans que la page
 * défile sous le geste.
 */
export function useGraphViewport(options: GraphViewportOptions = {}): GraphViewport {
  const {
    initial = { x: 0, y: 0, scale: 1 },
    minScale = 0.15,
    maxScale = 4,
    ignoreDragOn,
    onPanMove,
  } = options;

  const [transform, setTransform] = useState<Transform>(initial);
  const svgRef = useRef<SVGSVGElement>(null);
  const isPanning = useRef(false);
  const lastPos = useRef({ x: 0, y: 0 });
  const dragged = useRef(false);
  const viewAdjusted = useRef(false);

  const markFitted = useCallback(() => { viewAdjusted.current = false; }, []);
  const markAdjusted = useCallback(() => { viewAdjusted.current = true; }, []);

  const viewport = useCallback((): Viewport | null => {
    const box = svgRef.current?.getBoundingClientRect();
    return box ? { width: box.width, height: box.height } : null;
  }, []);

  const panBy = useCallback((dx: number, dy: number) => {
    viewAdjusted.current = true;
    setTransform(t => ({ ...t, x: t.x + dx, y: t.y + dy }));
  }, []);

  const zoomAround = useCallback((factor: number, px: number, py: number) => {
    viewAdjusted.current = true;
    setTransform(t => zoomTransform(t, factor, px, py, minScale, maxScale));
  }, [minScale, maxScale]);

  const zoomFromCenter = useCallback((factor: number) => {
    const box = viewport();
    zoomAround(factor, (box?.width ?? 0) / 2, (box?.height ?? 0) / 2);
  }, [viewport, zoomAround]);

  const onPointerDown = useCallback((e: ReactPointerEvent<SVGSVGElement>) => {
    if (ignoreDragOn && (e.target as Element).closest(ignoreDragOn)) return;
    isPanning.current = true;
    dragged.current = false;
    lastPos.current = { x: e.clientX, y: e.clientY };
    e.currentTarget.style.cursor = 'grabbing';
  }, [ignoreDragOn]);

  const onPointerMove = useCallback((e: ReactPointerEvent<SVGSVGElement>) => {
    if (!isPanning.current) return;
    const dx = e.clientX - lastPos.current.x;
    const dy = e.clientY - lastPos.current.y;
    // Quelques pixels de tremblement ne font pas d'un clic un glissé.
    if (Math.abs(dx) + Math.abs(dy) > 2) dragged.current = true;
    lastPos.current = { x: e.clientX, y: e.clientY };
    panBy(dx, dy);
    onPanMove?.();
  }, [panBy, onPanMove]);

  const onPointerUp = useCallback((e: ReactPointerEvent<SVGSVGElement>) => {
    isPanning.current = false;
    e.currentTarget.style.cursor = 'grab';
  }, []);

  // `passive: false` est la raison de l'écouteur manuel : React attache `onWheel` en passif, où
  // `preventDefault()` est ignoré — la page défilerait sous le zoom.
  useEffect(() => {
    const el = svgRef.current;
    if (!el) return;
    const handler = (e: WheelEvent) => {
      e.preventDefault();
      const box = el.getBoundingClientRect();
      zoomAround(e.deltaY > 0 ? 0.9 : 1.1, e.clientX - box.left, e.clientY - box.top);
    };
    el.addEventListener('wheel', handler, { passive: false });
    return () => el.removeEventListener('wheel', handler);
  }, [zoomAround]);

  return {
    transform, setTransform, svgRef, viewAdjusted, markFitted, markAdjusted, dragged, isPanning,
    viewport, panBy, zoomAround, zoomFromCenter,
    onPointerDown, onPointerMove, onPointerUp,
  };
}
