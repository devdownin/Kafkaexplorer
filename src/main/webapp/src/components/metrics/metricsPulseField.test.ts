// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors

import { describe, it, expect } from 'vitest';
import {
  DEFAULT_FIELD_OPTIONS,
  LINK_DISTANCE,
  advanceField,
  createField,
  linkAlpha,
  nodeCountFor,
  resizeField,
  type FieldNode,
} from './metricsPulseField';

/**
 * Un tirage déterministe : la suite est connue, donc les positions le sont aussi. C'est tout
 * l'intérêt d'avoir passé `random` en paramètre — sans lui, ces cas ne pourraient qu'affirmer
 * qu'un tableau n'est pas vide.
 */
function sequence(values: number[]): () => number {
  let i = 0;
  return () => values[i++ % values.length];
}

describe('nodeCountFor', () => {
  it('derives the count from the area', () => {
    // 1440×900 = 1 296 000 px² / 26 000 ≈ 49.8 → 50, entre les deux bornes.
    expect(nodeCountFor(1440, 900)).toBe(50);
  });

  /*
   * Le plafond est la raison d'être de cette fonction. Le coût des liens croît en n², donc un
   * compte proportionnel à la surface fait payer un écran 4K bien plus que ×6,5.
   */
  it('caps what a very large screen would otherwise ask for', () => {
    const uncapped = Math.round((3840 * 2160) / DEFAULT_FIELD_OPTIONS.pixelsPerNode);
    expect(uncapped).toBeGreaterThan(DEFAULT_FIELD_OPTIONS.maxNodes);
    expect(nodeCountFor(3840, 2160)).toBe(DEFAULT_FIELD_OPTIONS.maxNodes);
  });

  it('keeps a floor so a narrow pane is not empty', () => {
    expect(nodeCountFor(320, 200)).toBe(DEFAULT_FIELD_OPTIONS.minNodes);
  });

  /*
   * Zéro et non `minNodes` : un hôte que rien n'a encore disposé n'est pas un petit hôte. Peupler
   * un champ de 0×0 puis le redimensionner à la première mesure est le clignotement que
   * `resizeField` existe pour éviter.
   */
  it('reports nothing for a surface that has not been laid out', () => {
    expect(nodeCountFor(0, 0)).toBe(0);
    expect(nodeCountFor(1440, 0)).toBe(0);
  });
});

describe('createField', () => {
  it('places every node inside the surface, at the configured speed', () => {
    const nodes = createField(800, 600, sequence([0.1, 0.9, 0.25, 0.5, 0.75, 0.3]));
    expect(nodes).toHaveLength(nodeCountFor(800, 600));
    for (const node of nodes) {
      expect(node.x).toBeGreaterThanOrEqual(0);
      expect(node.x).toBeLessThanOrEqual(800);
      expect(node.y).toBeGreaterThanOrEqual(0);
      expect(node.y).toBeLessThanOrEqual(600);
      expect(node.radius).toBeGreaterThanOrEqual(DEFAULT_FIELD_OPTIONS.minRadius);
      expect(node.radius).toBeLessThanOrEqual(DEFAULT_FIELD_OPTIONS.maxRadius);
    }
  });

  /*
   * La direction est tirée sur le cercle, pas composante par composante — sans quoi le champ
   * dériverait √2 fois plus vite en diagonale qu'à l'horizontale, ce qui se voit à l'œil.
   */
  it('gives every node the same speed whatever its direction', () => {
    const nodes = createField(800, 600, sequence([0.05, 0.37, 0.62, 0.88, 0.13, 0.5]));
    for (const node of nodes) {
      const speed = Math.hypot(node.vx, node.vy);
      expect(speed).toBeCloseTo(DEFAULT_FIELD_OPTIONS.speed, 6);
    }
  });
});

describe('advanceField', () => {
  const node = (over: Partial<FieldNode> = {}): FieldNode =>
    ({ x: 100, y: 100, vx: 10, vy: 0, radius: 1, ...over });

  it('moves a node by its velocity over the elapsed time', () => {
    const nodes = [node()];
    advanceField(nodes, 800, 600, 0.5);
    expect(nodes[0].x).toBeCloseTo(105, 6);
    expect(nodes[0].y).toBeCloseTo(100, 6);
  });

  /*
   * Les bords enroulent au lieu de rebondir : un rebond accumule les points sur les bords et
   * rend le cadre du fond lisible, alors que ce calque est censé n'en pas avoir. L'enroulement
   * se fait sur une marge, sinon un point disparaîtrait d'un bord pour réapparaître net à
   * l'autre au milieu d'un lien.
   */
  it('wraps a node past the edge, with the margin that hides the jump', () => {
    const margin = LINK_DISTANCE / 2;
    const nodes = [node({ x: 800 + margin, vx: 10 })];
    advanceField(nodes, 800, 600, 1);
    expect(nodes[0].x).toBe(-margin);
  });

  it('wraps vertically the same way', () => {
    const margin = LINK_DISTANCE / 2;
    const nodes = [node({ y: -margin, vx: 0, vy: -10 })];
    advanceField(nodes, 800, 600, 1);
    expect(nodes[0].y).toBe(600 + margin);
  });

  it('does nothing on a surface that has not been laid out', () => {
    const nodes = [node()];
    advanceField(nodes, 0, 0, 1);
    expect(nodes[0].x).toBe(100);
  });
});

describe('linkAlpha', () => {
  it('is zero at and beyond the link distance', () => {
    expect(linkAlpha(LINK_DISTANCE, 0)).toBe(0);
    expect(linkAlpha(LINK_DISTANCE + 1, 0)).toBe(0);
  });

  it('is strongest when the two points coincide', () => {
    expect(linkAlpha(0, 0)).toBe(1);
  });

  /*
   * Le profil est quadratique et non linéaire : à mi-distance il reste 75 % en linéaire, ce qui
   * rend un maillage plein, contre 25 % ici — le champ respire.
   */
  it('falls off quadratically, so a half-distance link is already faint', () => {
    expect(linkAlpha(LINK_DISTANCE / 2, 0)).toBeCloseTo(0.75, 6);
    expect(linkAlpha(0, LINK_DISTANCE / 2)).toBeCloseTo(0.75, 6);
  });

  it('measures the two axes together', () => {
    const diagonal = LINK_DISTANCE / Math.SQRT2;
    expect(linkAlpha(diagonal, diagonal)).toBeCloseTo(0, 6);
  });
});

describe('resizeField', () => {
  /*
   * Le cas qui justifie ce module. Le réflexe — rappeler `createField` sur chaque notification de
   * redimensionnement — refait tirer chaque position, donc le fond saute à chaque image pendant
   * qu'on tire un bord de fenêtre. Invisible en développement, systématique chez qui range deux
   * fenêtres côte à côte.
   */
  it('carries the existing nodes over, scaled to the new surface', () => {
    const nodes: FieldNode[] = [{ x: 400, y: 300, vx: 5, vy: -5, radius: 1.2 }];
    const resized = resizeField(nodes, { width: 800, height: 600 }, { width: 400, height: 600 });
    expect(resized[0].x).toBeCloseTo(200, 6);
    expect(resized[0].y).toBeCloseTo(300, 6);
    expect(resized[0].vx).toBe(5);
    expect(resized[0].radius).toBe(1.2);
  });

  it('tops the field up when the surface grew', () => {
    const small = createField(400, 300, sequence([0.2, 0.4, 0.6, 0.8]));
    const grown = resizeField(small, { width: 400, height: 300 }, { width: 1920, height: 1080 });
    expect(grown.length).toBe(nodeCountFor(1920, 1080));
    expect(grown.length).toBeGreaterThan(small.length);
  });

  it('drops the surplus when the surface shrank', () => {
    const large = createField(1920, 1080, sequence([0.2, 0.4, 0.6, 0.8]));
    const shrunk = resizeField(large, { width: 1920, height: 1080 }, { width: 400, height: 300 });
    expect(shrunk.length).toBe(nodeCountFor(400, 300));
  });

  /*
   * Une surface précédente nulle est la *première* mesure, pas un redimensionnement : il n'y a
   * pas d'échelle à appliquer. Sans ce garde-fou, la division par zéro multiplierait chaque
   * position par l'infini et le champ disparaîtrait au premier montage d'un hôte pas encore
   * disposé — exactement le cas de jsdom, et celui d'un onglet ouvert en arrière-plan.
   */
  it('does not scale by infinity when there was no previous surface', () => {
    const nodes: FieldNode[] = [{ x: 10, y: 20, vx: 1, vy: 1, radius: 1 }];
    const resized = resizeField(nodes, { width: 0, height: 0 }, { width: 800, height: 600 });
    expect(resized[0].x).toBe(10);
    expect(resized[0].y).toBe(20);
    for (const node of resized) {
      expect(Number.isFinite(node.x)).toBe(true);
      expect(Number.isFinite(node.y)).toBe(true);
    }
  });

  it('empties the field when the surface goes away', () => {
    const nodes = createField(800, 600, sequence([0.3, 0.6]));
    expect(resizeField(nodes, { width: 800, height: 600 }, { width: 0, height: 0 })).toEqual([]);
  });
});
