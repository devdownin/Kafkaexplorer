// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors

import { useState, useRef, type PointerEvent as ReactPointerEvent, type KeyboardEvent } from 'react';
import type { TopicActivity, TopicActivityResponse } from '../api/types';
import { bucketInterval } from './strataTime';
import {
  activityHistogram, topicEvidence, topicMapPositions,
  type StrataLayers, type StrataLayer,
} from './strataWorkspace';

export type StrataViewTopic = { name: string; layoutIndex: number; activity?: TopicActivity };

const layerDefinitions: { key: StrataLayer; name: string; description: string; swatch: string }[] = [
  { key: 'activity', name: 'Activity', description: 'Measured offset-production rings', swatch: '#0c9da9' },
  { key: 'lineage', name: 'Lineage', description: 'Declared Flink paths, not message traces', swatch: '#7794ab' },
  { key: 'traces', name: 'Key matches', description: 'Chronological matches, not causality', swatch: '#16afbd' },
  { key: 'consumers', name: 'Consumer lag', description: 'Selected group, per partition', swatch: '#e4a757' },
  { key: 'anomalies', name: 'Deviations', description: 'Local activity candidates only', swatch: '#e7a044' },
  { key: 'forecast', name: 'Forecast', description: 'Separate metric axis, q10–q90', swatch: '#9c7de5' },
];

export function StrataLayerMenu({ layers, onChange }: {
  layers: StrataLayers; onChange: (layer: StrataLayer, enabled: boolean) => void;
}) {
  const [open, setOpen] = useState(false);
  const enabled = Object.values(layers).filter(Boolean).length;
  return <div className="relative">
    <button type="button" aria-expanded={open} aria-controls="strata-layer-options"
      onClick={() => setOpen(value => !value)}
      className="rounded-xl border border-outline-variant bg-surface/95 backdrop-blur-lg px-3 py-2 text-xs font-semibold text-on-surface shadow-sm">
      Layers · {enabled}/6
    </button>
    {open && <div id="strata-layer-options"
      className="absolute right-0 top-full mt-2 z-30 w-64 max-w-[calc(100vw-3rem)] rounded-xl border border-outline-variant bg-surface/95 backdrop-blur-xl shadow-xl p-3 space-y-2"
      role="group" aria-label="3D visible layers">
      <div className="flex items-center justify-between gap-2">
        <h3 className="text-xs font-semibold text-on-surface">Visible evidence</h3>
        <button type="button" className="text-xs text-primary underline" onClick={() => setOpen(false)}>Close</button>
      </div>
      {layerDefinitions.map(layer => <label key={layer.key}
        className="flex gap-2 items-start rounded-lg p-1.5 hover:bg-surface-container-high cursor-pointer">
        <input type="checkbox" checked={layers[layer.key]}
          onChange={event => onChange(layer.key, event.target.checked)}
          className="mt-1 accent-primary" />
        <span className="mt-1 h-2.5 w-2.5 rounded-full shrink-0" style={{ background: layer.swatch }} aria-hidden="true" />
        <span className="min-w-0">
          <span className="block text-xs font-semibold text-on-surface">{layer.name}</span>
          <span className="block text-[11px] leading-tight text-on-surface-variant">{layer.description}</span>
        </span>
      </label>)}
      <p className="pt-1 text-[11px] text-on-surface-variant border-t border-outline-variant">
        Hiding a layer never changes the underlying measurements or starts a Kafka scan.
      </p>
    </div>}
  </div>;
}

export function StrataMiniMap({ items, totalTopics, selected, peer, onSelect, onFocus }: {
  items: readonly { name: string; layoutIndex: number }[];
  totalTopics: number;
  selected: string | null; peer: string | null;
  onSelect: (name: string) => void; onFocus: (name: string) => void;
}) {
  const positions = topicMapPositions(items, totalTopics);
  return <section className="rounded-xl border border-outline-variant bg-surface-container-low p-3" aria-label="Topic mini-map">
    <div className="flex items-center justify-between">
      <h3 className="text-xs font-semibold text-on-surface">Topic map</h3>
      <span className="text-[11px] text-on-surface-variant">Top-down · X/Z</span>
    </div>
    <div className="relative mx-auto mt-2 w-full max-w-[240px] aspect-[240/164] rounded-lg bg-surface overflow-hidden">
      <svg viewBox="0 0 240 164" className="absolute inset-0 h-full w-full" aria-hidden="true">
        <path d="M0 82H240M120 0V164" stroke="currentColor" opacity=".10" strokeDasharray="3 6" />
        {positions.map(p => <circle key={p.name} cx={p.x} cy={p.y}
          r={p.name === selected || p.name === peer ? 5 : 2.7}
          fill={p.name === selected ? '#0c9da9' : p.name === peer ? '#9b76db' : '#8aa5b8'}
          opacity={p.name === selected || p.name === peer ? 1 : .7} />)}
      </svg>
      <span className="sr-only">Position of visible topics in the full cluster.</span>
      {positions.map(p => <button type="button" key={p.name} title={p.name}
        aria-label={`Select ${p.name} on the topic map`}
        className="absolute rounded-full focus-visible:outline focus-visible:outline-2 focus-visible:outline-primary"
        style={{ left: `${p.x / 240 * 100}%`, top: `${p.y / 164 * 100}%`,
          width: 15, height: 15, transform: 'translate(-50%, -50%)' }}
        onClick={() => onSelect(p.name)} onDoubleClick={() => onFocus(p.name)} />)}
    </div>
    <p className="mt-2 text-[11px] text-on-surface-variant">
      Select a point to inspect; double-click to focus. Cyan: selected. Violet: comparison.
    </p>
  </section>;
}

export function StrataTimelineDock({ snapshot, topics, selectedBucket, onBucket, replaying, onReplay,
  replaySpeed, onSpeed, onLatest }: {
  snapshot: TopicActivityResponse | null;
  topics: readonly { activity?: TopicActivity }[];
  selectedBucket: number; onBucket: (bucket: number) => void;
  replaying: boolean; onReplay: () => void; replaySpeed: number;
  onSpeed: (speed: number) => void; onLatest: () => void;
}) {
  const buckets = snapshot?.buckets ?? 12;
  const histogram = activityHistogram(topics, buckets);
  const peak = Math.max(1, ...histogram.map(bucket => bucket.value));
  const interval = snapshot ? bucketInterval({
    startMs: snapshot.windowStartMs, endMs: snapshot.windowEndMs,
    bucketMs: snapshot.bucketMs, count: snapshot.buckets,
  }, selectedBucket) : null;
  return <section aria-label="Temporal lens" className="rounded-2xl border border-outline-variant bg-surface/95 backdrop-blur-xl shadow-lg p-3 space-y-2">
    <div className="flex items-center flex-wrap justify-between gap-2">
      <div className="flex items-center gap-2">
        <button type="button" onClick={onReplay} disabled={!snapshot?.available}
          aria-pressed={replaying}
          className="rounded-lg bg-primary-container px-3 py-1.5 text-xs font-semibold text-on-primary-container disabled:opacity-50">
          {replaying ? '❚❚ Pause' : '▶ Replay'}
        </button>
        <label className="text-xs text-on-surface-variant">Speed
          <select aria-label="Replay speed" value={replaySpeed} onChange={e => onSpeed(Number(e.target.value))}
            className="ml-2 rounded-lg border border-outline-variant bg-surface px-2 py-1.5 text-on-surface">
            <option value={0.5}>0.5×</option><option value={1}>1×</option>
            <option value={2}>2×</option><option value={4}>4×</option>
          </select>
        </label>
      </div>
      <div className="flex items-center gap-2">
        <span className="text-xs tabular-nums text-on-surface font-medium">
          {interval ? `${new Date(interval.startMs).toLocaleTimeString()} – ${new Date(interval.endMs).toLocaleTimeString()}` : 'No measured interval'}
        </span>
        <button type="button" onClick={onLatest} disabled={!snapshot?.available || (!replaying && selectedBucket === buckets - 1)}
          className="rounded-lg border border-outline-variant px-2.5 py-1.5 text-xs text-on-surface disabled:opacity-45">
          Latest
        </button>
      </div>
    </div>
    <div className="flex h-9 items-end gap-1" role="group" aria-label="Measured topic production by time bucket">
      {histogram.map(bucket => <button key={bucket.index} type="button"
        onClick={() => onBucket(bucket.index)} disabled={!snapshot?.available}
        aria-label={`Select interval ${bucket.index + 1} of ${buckets}; ${bucket.measured} of ${bucket.total} topics measured`}
        aria-pressed={bucket.index === selectedBucket}
        title={`Bucket ${bucket.index + 1}: ${bucket.measured}/${bucket.total} measured topics`}
        className={`group relative flex-1 rounded-t-sm focus-visible:outline focus-visible:outline-2 focus-visible:outline-primary ${bucket.index === selectedBucket ? 'bg-primary/15' : 'bg-surface-container-high/65'}`}
        style={{ height: '100%' }}>
        <span aria-hidden="true" className={`absolute bottom-0 left-0 right-0 rounded-t-sm ${bucket.measured === 0 ? 'bg-outline-variant' :
          bucket.measured < bucket.total ? 'bg-amber-500/65' : 'bg-primary/80'}`}
          style={{ height: bucket.measured === 0 ? '3px' : `${Math.max(9, bucket.value / peak * 100)}%` }} />
        {bucket.index === selectedBucket && <span aria-hidden="true"
          className="absolute inset-x-0 bottom-0 top-0 border-x-2 border-primary pointer-events-none" />}
      </button>)}
    </div>
    <input id="strata-time-lens" type="range" min={0} max={Math.max(0, buckets - 1)}
      step={1} value={selectedBucket} onChange={e => onBucket(Number(e.target.value))}
      disabled={!snapshot?.available} className="w-full accent-primary"
      aria-label="Selected time interval" aria-valuetext={interval ?
        `${new Date(interval.startMs).toLocaleString()} to ${new Date(interval.endMs).toLocaleTimeString()}` : 'Unavailable'} />
    <div className="flex justify-between gap-2 text-[11px] text-on-surface-variant">
      <span>Oldest</span><span>{selectedBucket + 1}/{buckets} · amber: incomplete coverage</span><span>Most recent</span>
    </div>
  </section>;
}

export function StrataHoverCard({ name, activity, bucket, receivedAt, x, y }: {
  name: string; activity: TopicActivity | undefined;
  bucket: number; receivedAt: number | null; x: number; y: number;
}) {
  const evidence = topicEvidence(activity, bucket, receivedAt);
  return <div role="status" className="pointer-events-none absolute z-20 w-56 max-w-[70vw] rounded-xl border border-outline-variant bg-surface/95 backdrop-blur-xl shadow-xl p-3 text-xs text-on-surface"
    style={{ left: x, top: y }}>
    <p className="font-semibold break-all">{name}</p>
    <p className="mt-1 capitalize text-on-surface-variant">Coverage: {evidence.status} · {evidence.measuredBuckets}/{evidence.totalBuckets} intervals</p>
    <p className="mt-1">Selected slice: <strong>{evidence.selected === null ? 'Unknown' : `${evidence.selected.toLocaleString()} offsets`}</strong></p>
    <p>Latest: <strong>{evidence.latestRate === null ? 'Unknown' : `${Math.round(evidence.latestRate).toLocaleString()} offsets/min`}</strong></p>
    <p className="text-on-surface-variant">Partitions: {evidence.partitionsMeasured}/{evidence.partitionsTotal}</p>
    <p className="text-on-surface-variant">{receivedAt ? `Sampled ${new Date(receivedAt).toLocaleTimeString()}` : 'No sample available'}</p>
  </div>;
}

export function StrataResizeHandle({ side, value, onChange }: {
  side: 'left' | 'right'; value: number; onChange: (value: number) => void;
}) {
  const drag = useRef<{ x: number; width: number } | null>(null);
  const delta = side === 'left' ? 1 : -1;
  const clamp = (width: number) => Math.round(Math.min(430, Math.max(206, width)));
  const pointerMove = (event: ReactPointerEvent<HTMLDivElement>) => {
    if (drag.current) onChange(clamp(drag.current.width + (event.clientX - drag.current.x) * delta));
  };
  const keys = (event: KeyboardEvent<HTMLDivElement>) => {
    if (event.key === 'ArrowLeft' || event.key === 'ArrowRight') {
      event.preventDefault();
      onChange(clamp(value + (event.key === 'ArrowRight' ? 1 : -1) * delta * 12));
    }
  };
  return <div role="separator" tabIndex={0} aria-orientation="vertical"
    aria-label={side === 'left' ? 'Resize navigator' : 'Resize inspector'}
    aria-valuemin={206} aria-valuemax={430} aria-valuenow={value}
    onKeyDown={keys}
    onPointerDown={event => {
      drag.current = { x: event.clientX, width: value };
      event.currentTarget.setPointerCapture(event.pointerId);
    }}
    onPointerMove={pointerMove}
    onPointerUp={event => { drag.current = null; if (event.currentTarget.hasPointerCapture(event.pointerId)) event.currentTarget.releasePointerCapture(event.pointerId); }}
    onPointerCancel={() => { drag.current = null; }}
    className={`hidden lg:block absolute top-0 bottom-0 w-2 cursor-col-resize hover:bg-primary/15 focus-visible:bg-primary/20 focus-visible:outline-primary z-10 ${side === 'left' ? 'right-0' : 'left-0'}`}
  />;
}
