// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors

import type { StrataPerformance } from './strataPerformance';

/** Optional diagnostics are outside the WebGL canvas and keyboard accessible. */
export default function StrataPerformancePanel({ reading }: { reading: StrataPerformance | null }) {
  return <details className="rounded-xl border border-outline-variant bg-surface-container-low px-3 py-2 text-xs text-on-surface">
    <summary className="cursor-pointer font-semibold focus-visible:outline-2 focus-visible:outline-primary">
      Scene telemetry (local)
    </summary>
    <p className="mt-2 text-on-surface-variant">
      Last observed render statistics. Idle scenes render on demand: an absent FPS is expected,
      not a failure. Resources are Three.js object counts, not measured GPU bytes.
    </p>
    {!reading ? <p className="mt-2" role="status">Awaiting a render sample…</p> :
      <dl className="mt-2 grid grid-cols-2 gap-x-4 gap-y-1 tabular-nums sm:grid-cols-4">
        <div><dt className="text-on-surface-variant">Render mode</dt><dd>{reading.mode}</dd></div>
        <div><dt className="text-on-surface-variant">Active FPS</dt><dd>{reading.fps ?? '—'}</dd></div>
        <div><dt className="text-on-surface-variant">Draw calls (last frame)</dt><dd>{reading.drawCalls}</dd></div>
        <div><dt className="text-on-surface-variant">Geometry objects</dt><dd>{reading.geometries}</dd></div>
        <div><dt className="text-on-surface-variant">Texture objects</dt><dd>{reading.textures}</dd></div>
        <div><dt className="text-on-surface-variant">Scene rebuild</dt><dd>{reading.rebuildMs === null ? '—' : reading.rebuildMs.toFixed(1) + ' ms'}</dd></div>
        <div><dt className="text-on-surface-variant">Pixel ratio</dt><dd>{reading.pixelRatio.toFixed(2)}</dd></div>
        <div><dt className="text-on-surface-variant">Topics</dt><dd>{reading.topics}</dd></div>
      </dl>}
  </details>;
}
