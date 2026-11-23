// SPDX-License-Identifier: AGPL-3.0-or-later
import { describe, expect, it } from 'vitest';
import { summarizeStrataPerformance } from './strataPerformance';

const sample = { measuredAtMs: 10, elapsedMs: 3000, renderedFrames: 0, contextLost: false,
  drawCalls: 12, geometries: 84, textures: 5, pixelRatio: 1, rebuildMs: 12.5, topics: 8 };

describe('Strata WebGL telemetry', () => {
  it('never calls idle on-demand rendering a 0 FPS performance issue', () => {
    expect(summarizeStrataPerformance(sample)).toMatchObject({ mode: 'on-demand', fps: null, topics: 8 });
  });
  it('reports measured active FPS without claiming GPU memory in bytes', () => {
    expect(summarizeStrataPerformance({ ...sample, renderedFrames: 90 })).toMatchObject({
      mode: 'active', fps: 30, geometries: 84, textures: 5,
    });
  });
  it('exposes a lost WebGL context as unavailable rather than an active frame', () => {
    expect(summarizeStrataPerformance({ ...sample, renderedFrames: 90, contextLost: true }))
      .toMatchObject({ mode: 'context-lost', fps: null });
  });
});
