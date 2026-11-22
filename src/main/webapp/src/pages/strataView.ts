// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors

/** URL-only spatial view state. No message keys, traces or record payloads are shared. */
export type StrataQuality = 'auto' | 'detail' | 'economy';
export type StrataViewMode = 'investigate' | 'compare';
export interface StrataView {
  mode: StrataViewMode;
  topic: string | null;
  peer: string | null;
  focus: string | null;
  filter: string;
  bucket: number;
  quality: StrataQuality;
}

const KEYS = ['sv.mode', 'sv.topic', 'sv.peer', 'sv.focus', 'sv.filter', 'sv.bucket', 'sv.quality'] as const;
const MAX_TOPIC = 249;
const MAX_FILTER = 160;
const DEFAULT_BUCKET = 11;

function safeString(raw: string | null, limit: number): string | null {
  if (!raw || raw.length > limit ||
      Array.from(raw).some(char => char.charCodeAt(0) < 32 || char.charCodeAt(0) === 127)) return null;
  return raw;
}

/** Treat untrusted links as hints, never as authorization to access Kafka. */
export function readStrataView(params: URLSearchParams): StrataView {
  const mode = params.get('sv.mode') === 'compare' ? 'compare' : 'investigate';
  const rawBucket = params.get('sv.bucket');
  const bucket = rawBucket !== null && /^(?:[0-9]|10|11)$/.test(rawBucket)
    ? Number(rawBucket) : DEFAULT_BUCKET;
  const rawQuality = params.get('sv.quality');
  const quality: StrataQuality = rawQuality === 'detail' || rawQuality === 'economy'
    ? rawQuality : 'auto';
  const topic = safeString(params.get('sv.topic'), MAX_TOPIC);
  const peer = safeString(params.get('sv.peer'), MAX_TOPIC);
  const focus = safeString(params.get('sv.focus'), MAX_TOPIC);
  return {
    mode,
    topic,
    peer: peer === topic ? null : peer,
    focus: focus === topic ? focus : null,
    filter: safeString(params.get('sv.filter'), MAX_FILTER) ?? '',
    bucket,
    quality,
  };
}

/** Preserve unrelated search parameters while replacing only our namespaced keys. */
export function writeStrataView(params: URLSearchParams, view: StrataView): URLSearchParams {
  const next = new URLSearchParams(params);
  KEYS.forEach(key => next.delete(key));
  if (view.mode === 'compare') next.set('sv.mode', 'compare');
  if (view.topic) next.set('sv.topic', view.topic);
  if (view.peer && view.mode === 'compare' && view.peer !== view.topic) next.set('sv.peer', view.peer);
  if (view.focus && view.focus === view.topic) next.set('sv.focus', view.focus);
  if (view.filter) next.set('sv.filter', view.filter.slice(0, MAX_FILTER));
  if (view.bucket !== DEFAULT_BUCKET) next.set('sv.bucket', String(Math.max(0, Math.min(11, Math.trunc(view.bucket)))));
  if (view.quality !== 'auto') next.set('sv.quality', view.quality);
  return next;
}

export interface StrataRenderBudget {
  pixelRatio: number;
  frameIntervalMs: number;
  ringSegments: number;
}

/** The automatic budget scales fidelity down on dense scenes and high-DPR displays. */
export function strataRenderBudget(quality: StrataQuality, topicCount: number, deviceRatio: number): StrataRenderBudget {
  const ratio = Number.isFinite(deviceRatio) && deviceRatio > 0 ? deviceRatio : 1;
  if (quality === 'economy') return { pixelRatio: Math.min(ratio, 1), frameIntervalMs: 67, ringSegments: 24 };
  if (quality === 'detail') return { pixelRatio: Math.min(ratio, 2), frameIntervalMs: 33, ringSegments: 48 };
  if (topicCount >= 32) return { pixelRatio: Math.min(ratio, 1), frameIntervalMs: 67, ringSegments: 24 };
  if (topicCount >= 16) return { pixelRatio: Math.min(ratio, 1.4), frameIntervalMs: 50, ringSegments: 32 };
  return { pixelRatio: Math.min(ratio, 1.75), frameIntervalMs: 33, ringSegments: 48 };
}
