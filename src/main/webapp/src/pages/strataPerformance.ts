// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors

/** Client-side WebGL diagnostics only; nothing is transmitted or persisted. */
export interface StrataPerformance {
  measuredAtMs: number;
  mode: 'active' | 'on-demand' | 'context-lost';
  fps: number | null;
  drawCalls: number;
  geometries: number;
  textures: number;
  pixelRatio: number;
  rebuildMs: number | null;
  topics: number;
}

export interface StrataPerformanceWindow {
  measuredAtMs: number;
  elapsedMs: number;
  renderedFrames: number;
  contextLost: boolean;
  drawCalls: number;
  geometries: number;
  textures: number;
  pixelRatio: number;
  rebuildMs: number | null;
  topics: number;
}

/**
 * FPS is meaningful only for sustained rendering. A quiet, demand-rendered scene
 * deliberately reports no FPS, not 0 FPS. Three.js resource counters are
 * estimates of allocated objects, NOT GPU memory in bytes.
 */
export function summarizeStrataPerformance(window: StrataPerformanceWindow): StrataPerformance {
  const active = !window.contextLost && Number.isFinite(window.elapsedMs)
    && window.elapsedMs >= 250 && window.renderedFrames >= 3;
  return {
    measuredAtMs: window.measuredAtMs,
    mode: window.contextLost ? 'context-lost' : active ? 'active' : 'on-demand',
    fps: active ? Math.round(window.renderedFrames * 10000 / window.elapsedMs) / 10 : null,
    drawCalls: Math.max(0, window.drawCalls),
    geometries: Math.max(0, window.geometries),
    textures: Math.max(0, window.textures),
    pixelRatio: window.pixelRatio,
    rebuildMs: window.rebuildMs,
    topics: window.topics,
  };
}
