// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors

/**
 * What Strata 3D reads off a topic's *name*, and what it draws between topics. Pure: no Three.js,
 * no request, so every rule here is tested on its own.
 *
 * Two families of claim live in this file and they must never be confused. A colour, a lane or a
 * socle shape is a **presentation** of the name — it measures nothing. An inferred link is a
 * **guess** from a naming convention, drawn on its own layer, off by default, in its own style:
 * the declared Flink lineage is the only link the scene presents as a fact.
 */

import type { MetricConfig } from '../api/types';
import { gradeMetric, type MetricStatus } from './metricAlert';
import type { StrataLink } from './strataLineage';

// ── Families ───────────────────────────────────────────────────────────────────────────────────

function segments(name: string): string[] {
  return name.split(/[._-]+/).filter(Boolean);
}

/**
 * The prefix that names a pipeline: the first two segments of a name that has at least three
 * (`demo.orders.1.received` → `demo.orders`, `demo.sc.01.order.placed.in` → `demo.sc`), the whole
 * name otherwise (`demo.customers`). Separators are normalised to dots, so `orders-v1-in` and
 * `orders.v1.out` are one family.
 */
export function topicFamily(name: string): string {
  const parts = segments(name);
  return parts.length >= 3 ? parts.slice(0, 2).join('.') : parts.join('.') || name;
}

/** The first purely numeric segment after the family — `demo.orders.2.validated` is stage 2. */
export function topicStage(name: string): number | null {
  const parts = segments(name);
  for (const part of parts.slice(parts.length >= 3 ? 2 : parts.length)) {
    if (/^\d{1,4}$/.test(part)) return Number(part);
  }
  return null;
}

/** Where a message ends rather than travels on: dead letters, retries, errors and audit copies. */
export function isSideOutput(name: string): boolean {
  return segments(name).some(part => /^(dlt|dlq|deadletter|retry|error|errors|poison|audit)$/i.test(part));
}

/** FNV-1a, 32 bits: stable across sessions and browsers, which a colour has to be. */
function hash(text: string): number {
  let value = 0x811c9dc5;
  for (let i = 0; i < text.length; i++) {
    value ^= text.charCodeAt(i);
    value = Math.imul(value, 0x01000193) >>> 0;
  }
  return value >>> 0;
}

/**
 * Hues a family may take, in degrees. Every one stays at least 20° from a colour the scene already
 * spends on a meaning — red (stalled group, 0°), amber (deviation, 36°), the lag-free green (167°),
 * the selection cyan (185°) and the comparison violet (266°) — so a topic's identity can never be
 * read as one of those states. Spread one per region of the wheel rather than packed where there is
 * room: three greens 24° apart read as one colour at a glance, which was the defect being fixed.
 */
export const FAMILY_HUES = [60, 100, 140, 208, 232, 290, 314, 338] as const;

/** A family's hue, 0–1: fixed by its name, so filtering or a new topic never repaints it. */
export function familyHue(family: string): number {
  return FAMILY_HUES[hash(family) % FAMILY_HUES.length] / 360;
}

/**
 * A topic's colour as HSL (0–1). The hue is the family's; the lightness steps with the stage, so
 * `1.received` → `6.delivered` reads as one pipeline darkening along its length, and topics with no
 * stage get a small offset from their own name so siblings are not identical.
 */
export function topicColor(name: string, dark: boolean): { h: number; s: number; l: number } {
  const stage = topicStage(name);
  const step = stage !== null ? (stage % 5) - 2 : (hash(name) % 5) - 2;
  const base = dark ? 0.64 : 0.47;
  return { h: familyHue(topicFamily(name)), s: dark ? 0.62 : 0.66, l: base + step * (dark ? 0.04 : 0.045) };
}

/** The socle's number of sides — a second channel for the family, for whoever cannot tell the hues apart. */
export function familyShape(family: string): number {
  return [40, 4, 6, 8][hash(family + '#shape') % 4];
}

/** CSS colour for a family swatch, at the light-theme stage-0 lightness. */
export function familyCss(family: string, dark = false): string {
  return `hsl(${Math.round(familyHue(family) * 360)} ${dark ? 62 : 66}% ${dark ? 64 : 47}%)`;
}

export interface FamilyCount { family: string; count: number }

/** The families present, largest first, for the navigator's legend. */
export function familyLegend(names: readonly string[]): FamilyCount[] {
  const counts = new Map<string, number>();
  for (const name of names) counts.set(topicFamily(name), (counts.get(topicFamily(name)) ?? 0) + 1);
  return [...counts].map(([family, count]) => ({ family, count }))
    .sort((a, b) => b.count - a.count || a.family.localeCompare(b.family));
}

// ── Links inferred from names ──────────────────────────────────────────────────────────────────

export const INFERRED_VIA = 'inferred from topic names';

/**
 * Pipelines a naming convention suggests: within a family, each numbered stage feeds the next one
 * present (`1.received` → `2.validated`), and inside a stage an `.in` topic feeds its `.out`. A
 * side output (dead letter, retry, audit) can receive but never feeds the next stage. A pair the
 * declared lineage already holds is left to it, and so is anything past `limit`.
 *
 * This is a guess and every link says so (`via`): nothing in Kafka records who produced a record.
 */
export function inferNameLinks(names: readonly string[], declared: readonly StrataLink[] = [],
  limit = 96): StrataLink[] {
  const known = new Set(declared.map(link => link.from + '\u0000' + link.to));
  const links: StrataLink[] = [];
  const add = (from: string, to: string) => {
    const key = from + '\u0000' + to;
    if (from === to || known.has(key) || links.length >= limit) return;
    known.add(key);
    links.push({ from, to, via: INFERRED_VIA });
  };
  const families = new Map<string, Map<number, string[]>>();
  for (const name of [...names].sort((a, b) => a.localeCompare(b))) {
    const stage = topicStage(name);
    if (stage === null) continue;
    const family = topicFamily(name);
    const stages = families.get(family) ?? new Map<number, string[]>();
    stages.set(stage, [...(stages.get(stage) ?? []), name]);
    families.set(family, stages);
  }
  const lastSegment = (name: string) => { const parts = segments(name); return parts[parts.length - 1]?.toLowerCase(); };
  for (const stages of families.values()) {
    const order = [...stages.keys()].sort((a, b) => a - b);
    order.forEach((stage, index) => {
      const members = stages.get(stage)!;
      const inputs = members.filter(name => lastSegment(name) === 'in');
      const outputs = members.filter(name => lastSegment(name) === 'out');
      for (const input of inputs) for (const output of outputs) add(input, output);
      const next = order[index + 1];
      if (next === undefined) return;
      const nextMembers = stages.get(next)!;
      const nextInputs = nextMembers.filter(name => lastSegment(name) === 'in');
      const sources = (outputs.length ? outputs : members).filter(name => !isSideOutput(name));
      for (const source of sources) for (const target of nextInputs.length ? nextInputs : nextMembers) add(source, target);
    });
  }
  return links;
}

// ── Pipeline lanes ─────────────────────────────────────────────────────────────────────────────

export type StrataLayoutMode = 'spiral' | 'lanes';
export interface Slot { x: number; z: number }

/** The sunflower slot of the spiral layout — the placement `stableLayout`'s index has always meant. */
export function spiralSlot(index: number): Slot {
  const radius = 3.4 * Math.sqrt(index);
  const angle = index * 2.399963229728653;
  return { x: Math.cos(angle) * radius, z: Math.sin(angle) * radius };
}

const LANE_STEP_X = 3.3;
const LANE_STEP_Z = 2.6;
const LANE_GAP = 1.4;

/**
 * One lane per family, its stages left to right, the topics of one stage side by side across the
 * lane; topics without a stage follow the last stage. Families of a single topic share the last
 * lane, or a cluster of one-topic families would be as many lanes as topics.
 */
export function laneLayout(names: readonly string[]): Map<string, Slot> {
  const byFamily = new Map<string, string[]>();
  for (const name of names) byFamily.set(topicFamily(name), [...(byFamily.get(topicFamily(name)) ?? []), name]);
  const lanes = [...byFamily].filter(([, members]) => members.length > 1)
    .sort((a, b) => b[1].length - a[1].length || a[0].localeCompare(b[0]))
    .map(([, members]) => members);
  const singles = [...byFamily].filter(([, members]) => members.length === 1)
    .map(([, members]) => members[0]).sort((a, b) => a.localeCompare(b));
  const slots = new Map<string, Slot>();
  let z = 0;
  for (const members of lanes) {
    const columns = new Map<string, string[]>();
    const sorted = [...members].sort((a, b) => {
      const sa = topicStage(a) ?? Number.MAX_SAFE_INTEGER;
      const sb = topicStage(b) ?? Number.MAX_SAFE_INTEGER;
      return sa - sb || Number(isSideOutput(a)) - Number(isSideOutput(b)) || a.localeCompare(b);
    });
    for (const name of sorted) {
      const stage = topicStage(name);
      const key = stage === null ? 'n:' + name : 's:' + stage;
      columns.set(key, [...(columns.get(key) ?? []), name]);
    }
    let depth = 1;
    [...columns.values()].forEach((column, x) => {
      depth = Math.max(depth, column.length);
      column.forEach((name, row) => slots.set(name, { x: x * LANE_STEP_X, z: z + row * LANE_STEP_Z }));
    });
    z += depth * LANE_STEP_Z + LANE_GAP;
  }
  const perRow = 8;
  singles.forEach((name, index) => slots.set(name,
    { x: (index % perRow) * LANE_STEP_X, z: z + Math.floor(index / perRow) * LANE_STEP_Z }));
  return slots;
}

// ── Time on the vertical axis ──────────────────────────────────────────────────────────────────

export const RING_BASE_Y = 0.35;
export const RING_STEP_Y = 0.43;

/**
 * The height of an instant in the activity window, on the same scale as the rings: bucket `i`
 * sits at `RING_BASE_Y + i × RING_STEP_Y`, and an instant halfway through it sits on its ring.
 * Outside the window the height is clamped to the nearest edge and `inWindow` says so — a hit an
 * hour old is not drawn as if it had happened at the start of the window.
 */
export function timeHeight(timestampMs: number, windowStartMs: number, bucketMs: number,
  buckets: number): { y: number; inWindow: boolean } {
  if (!Number.isFinite(timestampMs) || !(bucketMs > 0) || buckets < 1) return { y: RING_BASE_Y, inWindow: false };
  const position = (timestampMs - windowStartMs) / bucketMs - 0.5;
  const clamped = Math.min(buckets - 1, Math.max(0, position));
  const inWindow = timestampMs >= windowStartMs && timestampMs <= windowStartMs + buckets * bucketMs;
  return { y: RING_BASE_Y + clamped * RING_STEP_Y, inWindow };
}

// ── Particles and link health ──────────────────────────────────────────────────────────────────

/**
 * How many particles travel a link: none for an unmeasured or silent target, then one to five on
 * the same logarithmic scale as the rings, so a link carries visibly what its target produced.
 */
export function flowParticleCount(rate: number | null, maxRate: number): number {
  if (rate === null || !(rate > 0)) return 0;
  return 1 + Math.round(4 * Math.log1p(rate) / Math.log1p(Math.max(1, maxRate)));
}

const pairKey = (left: string, right: string) => left + '\u0000' + right;
const SEVERITY: Record<MetricStatus, number> = { critical: 3, warning: 2, ok: 1, pending: 0, error: 0 };

/**
 * The verdict of the count-comparison metrics (`TOPIC_COUNT_DELTA`) over each pair of topics, by
 * `gradeMetric` — the Metrics page's own rule, not a second one. Only a graded metric counts: a
 * pending or failing metric says nothing about the link and is left out rather than read as fine.
 */
export function linkHealth(metrics: readonly MetricConfig[]): Map<string, 'ok' | 'warning' | 'critical'> {
  const health = new Map<string, 'ok' | 'warning' | 'critical'>();
  for (const metric of metrics) {
    if (metric.templateType !== 'TOPIC_COUNT_DELTA') continue;
    const left = metric.templateParams?.leftTopic;
    const right = metric.templateParams?.rightTopic;
    if (typeof left !== 'string' || typeof right !== 'string' || !left || !right) continue;
    const status = gradeMetric(metric);
    if (status !== 'ok' && status !== 'warning' && status !== 'critical') continue;
    for (const key of [pairKey(left, right), pairKey(right, left)]) {
      const previous = health.get(key);
      if (!previous || SEVERITY[status] > SEVERITY[previous]) health.set(key, status);
    }
  }
  return health;
}

/** A link's verdict, whichever way round the metric names its two topics. */
export function healthOf(health: ReadonlyMap<string, 'ok' | 'warning' | 'critical'>, link: StrataLink) {
  return health.get(pairKey(link.from, link.to)) ?? null;
}

// ── Links an operator's count comparison declares ──────────────────────────────────────────────

export const COMPARED_VIA = 'compared by a count metric';

/**
 * The pairs a `TOPIC_COUNT_DELTA` metric compares, as links from its left topic to its right one.
 * An operator who wrote that metric said the two topics are one flow — upstream and downstream —
 * which is a declaration, unlike a name convention, and the only link whose health is measured.
 * Without it the verdict had nothing to colour: the demo's five count comparisons were over topics
 * no lineage and no naming rule linked, so the red and orange links never appeared.
 *
 * Only the topics in view, and only a pair no other link already draws, whichever way round.
 */
export function metricLinks(metrics: readonly MetricConfig[], names: readonly string[],
  taken: readonly StrataLink[] = []): StrataLink[] {
  const inView = new Set(names);
  const known = new Set(taken.flatMap(link => [pairKey(link.from, link.to), pairKey(link.to, link.from)]));
  const links: StrataLink[] = [];
  for (const metric of metrics) {
    if (metric.templateType !== 'TOPIC_COUNT_DELTA') continue;
    const from = metric.templateParams?.leftTopic;
    const to = metric.templateParams?.rightTopic;
    if (typeof from !== 'string' || typeof to !== 'string' || from === to) continue;
    if (!inView.has(from) || !inView.has(to) || known.has(pairKey(from, to))) continue;
    known.add(pairKey(from, to));
    known.add(pairKey(to, from));
    links.push({ from, to, via: COMPARED_VIA });
  }
  return links;
}

// ── Which names stay written ───────────────────────────────────────────────────────────────────

/** Up to this many topics in view, every column keeps its name. */
export const LABEL_ALL_UP_TO = 12;
/** Past it, the busiest this many keep theirs beside the ones the operator is pointing at. */
export const BUSIEST_LABELLED = 8;

/**
 * The columns whose name stays written. Forty-eight overlapping labels hide the scene they name,
 * so past `LABEL_ALL_UP_TO` only these keep one: whatever the operator is pointing at (`pinned` —
 * the selection, the comparison, the focus, the hovered column, a trace's topics) and the busiest
 * by their latest measured bucket. An unmeasured topic is never "busy": it is ranked last, not zero.
 * Every other name is still one hover, one list row or the mini-map away.
 */
export function labelledTopics(topics: readonly { name: string; latest: number | null }[],
  pinned: Iterable<string | null | undefined>, busiest = BUSIEST_LABELLED): Set<string> {
  if (topics.length <= LABEL_ALL_UP_TO) return new Set(topics.map(topic => topic.name));
  const labelled = new Set<string>();
  for (const name of pinned) if (name) labelled.add(name);
  topics.filter(topic => topic.latest !== null && topic.latest > 0)
    .sort((a, b) => (b.latest as number) - (a.latest as number) || a.name.localeCompare(b.name))
    .slice(0, busiest)
    .forEach(topic => labelled.add(topic.name));
  return labelled;
}
