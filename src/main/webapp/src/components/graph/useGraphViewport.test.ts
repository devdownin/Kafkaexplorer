// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors

import { describe, expect, it } from 'vitest';
import { zoomTransform } from './useGraphViewport';

/**
 * Only the arithmetic is unit-tested here, and deliberately so: the rest of the hook is pointer
 * and wheel plumbing over an SVG's real geometry, which jsdom has none of — a test of it would
 * assert that mocks were called, not that a graph pans.
 *
 * What the arithmetic has to get right is the anchor: a zoom that does not keep the pointed-at
 * point under the pointer feels like the graph jumping away from the cursor, which is the whole
 * reason this is not a plain scale multiplication.
 */
describe('zoomTransform', () => {
  const identity = { x: 0, y: 0, scale: 1 };

  it('keeps the anchored point exactly under the pointer', () => {
    const before = { x: 30, y: -12, scale: 1.4 };
    const [px, py] = [220, 140];
    // The graph coordinate currently drawn at (px, py).
    const graphX = (px - before.x) / before.scale;
    const graphY = (py - before.y) / before.scale;

    for (const factor of [1.1, 0.9, 2, 0.5]) {
      const after = zoomTransform(before, factor, px, py, 0.1, 4);
      expect(after.x + graphX * after.scale).toBeCloseTo(px, 6);
      expect(after.y + graphY * after.scale).toBeCloseTo(py, 6);
    }
  });

  it('clamps to the bounds it is given, and each page gives its own', () => {
    // Data Model goes lower and stops earlier than the other two: a hundred entities need it.
    expect(zoomTransform(identity, 100, 0, 0, 0.1, 3).scale).toBe(3);
    expect(zoomTransform(identity, 0.001, 0, 0, 0.1, 3).scale).toBe(0.1);
    expect(zoomTransform(identity, 100, 0, 0, 0.15, 4).scale).toBe(4);
    expect(zoomTransform(identity, 0.001, 0, 0, 0.15, 4).scale).toBe(0.15);
  });

  it('does not drift the translation once the scale is pinned at a bound', () => {
    // At the bound the ratio is 1, so a further zoom must be a no-op rather than a slow slide.
    const atMax = zoomTransform(identity, 100, 200, 100, 0.15, 4);
    expect(zoomTransform(atMax, 2, 200, 100, 0.15, 4)).toEqual(atMax);
  });

  it('is a no-op at factor 1', () => {
    const t = { x: 17, y: 23, scale: 0.75 };
    expect(zoomTransform(t, 1, 90, 60, 0.15, 4)).toEqual(t);
  });
});
