// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors

import { useCallback, useEffect, useMemo, useRef, useState, type CSSProperties } from 'react';
import type { ConsumerGroupLag, DashboardResponse, MetricConfig, PartitionActivityResponse, TopicActivity, TopicActivityResponse } from '../api/types';
import StrataConsumerPanel from './StrataConsumerPanel';
import StrataTracePanel from './StrataTracePanel';
import StrataForecastPanel from './StrataForecastPanel';
import StrataDiagnosticPanel, { type DiagnosticMode } from './StrataDiagnosticPanel';
import { createForecastCanopy, createPartitionRibbons } from './strataImmersive';
import type { StrataProjection } from './strataForecast';
import { consumerHealth } from './StrataConsumerPanel';
import { bucketReading, detectActivityAnomaly } from './strataTime';
import type { ParsedFlow } from './streamFlowLogic';
import { deriveStrataLinks, type StrataLineage, type StrataLink } from './strataLineage';
import { Link } from 'react-router-dom';
import { readStrataView, shareStrataUrl, strataRenderBudget, writeStrataView, type StrataLayout, type StrataQuality } from './strataView';
import {
  RING_BASE_Y, RING_STEP_Y, familyCss, familyLegend, familyShape, flowParticleCount, healthOf, inferNameLinks, labelledTopics,
  laneLayout, linkHealth, metricLinks, spiralSlot, timeHeight, topicColor, topicFamily, type Slot,
} from './strataFlows';
import { copyText } from '../clipboard';
import {
  DEFAULT_STRATA_LAYERS, activityScaleMax, isDoubleTap, partitionFrameMatches, shouldRefit, stableLayout,
  topicEvidence, type StrataLayer, type StrataLayers, type StrataTap,
} from './strataWorkspace';
import {
  StrataHoverCard, StrataLayerMenu, StrataMiniMap, StrataResizeHandle, StrataTimelineDock,
} from './StrataWorkspaceUI';
import StrataShortcutDialog from './StrataShortcutDialog';
import StrataPerformancePanel from './StrataPerformancePanel';
import StrataInvestigationPanel from './StrataInvestigationPanel';
import { strataInspection, strataInvestigationSnapshot, strataLeads, type StrataInspection, type StrataSnapshotView } from './strataInvestigation';
import { summarizeStrataPerformance, type StrataPerformance } from './strataPerformance';
import { strataShortcut } from './strataShortcuts';
import axios from 'axios';
import * as THREE from 'three';
import { OrbitControls } from 'three/addons/controls/OrbitControls.js';

type Dashboard = DashboardResponse;
type Activity = TopicActivity;
type ActivityResponse = TopicActivityResponse;
type Topic = { name: string; size: number | null; activity?: Activity; layoutIndex: number; slot: Slot };
type LinkHealth = ReadonlyMap<string, 'ok' | 'warning' | 'critical'>;
/** The activity window, for placing an instant on the vertical axis. */
type TimeWindow = { startMs: number; bucketMs: number };
const MAX_TOPICS = 48;
const BUCKETS = 12;
const WINDOW_MS = 30 * 60 * 1000;

/**
 * Where a column stands. The slot is computed by the page from the arrangement — the stable
 * sunflower slot (`spiralSlot(layoutIndex)`) or a pipeline lane (`laneLayout`) — so refreshing the
 * metrics never moves a topic, and this function only reads it.
 */
function topicPosition(topic: { slot: Slot }): THREE.Vector3 {
  return new THREE.Vector3(topic.slot.x, 0, topic.slot.z);
}

/** What a scene is laid out from: its names and their slots, so a change of arrangement re-frames it. */
const sceneSignature = (topics: readonly Topic[]) =>
  topics.map(t => `${t.name}:${t.slot.x.toFixed(2)},${t.slot.z.toFixed(2)}`).join('\u0000');

function sceneFrame(topics: Topic[]): { center: THREE.Vector3; extent: number } {
  if (!topics.length) return { center: new THREE.Vector3(0, 2.6, 0), extent: 9 };
  const positions = topics.map(topicPosition);
  const minX = Math.min(...positions.map(p => p.x));
  const maxX = Math.max(...positions.map(p => p.x));
  const minZ = Math.min(...positions.map(p => p.z));
  const maxZ = Math.max(...positions.map(p => p.z));
  return {
    center: new THREE.Vector3((minX + maxX) / 2, 2.6, (minZ + maxZ) / 2),
    extent: Math.max(9, Math.max(maxX - minX, maxZ - minZ) * 0.65 + 5.5),
  };
}

/**
 * Geometries and label textures shared by every rebuild. A rebuild used to allocate a fresh
 * geometry for each of the ~17 meshes of every column and re-rasterise each topic's label on a
 * 2D canvas, then upload all of it to the GPU again — every 30 s for 48 topics, plus at each
 * selection. Rings are shared per quantised size (1/32 of the scale), which no eye separates.
 * Draw calls are unchanged: every mesh still carries its own material, because its opacity is
 * its own.
 */
class StrataAssets {
  private readonly geometries = new Map<string, THREE.BufferGeometry>();
  private readonly labels = new Map<string, THREE.CanvasTexture | null>();

  geometry(key: string, make: () => THREE.BufferGeometry): THREE.BufferGeometry {
    let geometry = this.geometries.get(key);
    if (!geometry) {
      geometry = make();
      geometry.userData.shared = true;
      this.geometries.set(key, geometry);
    }
    return geometry;
  }

  label(name: string, dark: boolean): THREE.CanvasTexture | null {
    const key = (dark ? 'd:' : 'l:') + name;
    if (!this.labels.has(key)) this.labels.set(key, drawLabel(name, dark));
    return this.labels.get(key) ?? null;
  }

  /** Labels of topics no longer in view, or drawn for the other theme, are released. */
  keepLabels(names: readonly string[], dark: boolean) {
    const keep = new Set(names.map(name => (dark ? 'd:' : 'l:') + name));
    for (const [key, texture] of this.labels) {
      if (!keep.has(key)) { texture?.dispose(); this.labels.delete(key); }
    }
  }

  get geometryCount() { return this.geometries.size; }

  dispose() {
    this.geometries.forEach(geometry => geometry.dispose());
    this.labels.forEach(texture => texture?.dispose());
    this.geometries.clear();
    this.labels.clear();
  }
}

function disposeGroup(group: THREE.Group) {
  group.traverse(object => {
    if (object instanceof THREE.Sprite) {
      if (!object.material.map?.userData.shared) object.material.map?.dispose();
      object.material.dispose();
    }
    if (!(object instanceof THREE.Mesh || object instanceof THREE.Line)) return;
    if (!object.geometry.userData.shared) object.geometry.dispose();
    const materials = Array.isArray(object.material) ? object.material : [object.material];
    materials.forEach(material => material.dispose());
  });
}

/** One billboard texture per topic, readable from every orbit angle. Canvas textures never leave the browser. */
function drawLabel(name: string, dark: boolean): THREE.CanvasTexture | null {
  const canvas = document.createElement('canvas');
  canvas.width = 384;
  canvas.height = 72;
  const context = canvas.getContext('2d');
  if (!context) return null;
  context.fillStyle = dark ? 'rgba(14, 30, 48, .94)' : 'rgba(249, 252, 255, .96)';
  context.fillRect(2, 2, 380, 68);
  context.strokeStyle = dark ? 'rgba(155, 197, 235, .5)' : 'rgba(102, 128, 154, .38)';
  context.lineWidth = 2;
  context.strokeRect(2, 2, 380, 68);
  // The stripe carries the column's colour, so a name is read with its column from any angle.
  const tone = topicColor(name, dark);
  context.fillStyle = `hsl(${Math.round(tone.h * 360)} ${Math.round(tone.s * 100)}% ${Math.round(tone.l * 100)}%)`;
  context.fillRect(2, 2, 12, 68);
  context.font = '600 28px system-ui, sans-serif';
  context.fillStyle = dark ? '#F0F6FE' : '#1D334B';
  context.textAlign = 'center';
  context.textBaseline = 'middle';
  const label = name.length > 24 ? name.slice(0, 23) + '…' : name;
  context.fillText(label, 198, 36, 352);
  const texture = new THREE.CanvasTexture(canvas);
  texture.colorSpace = THREE.SRGBColorSpace;
  texture.userData.shared = true;
  return texture;
}

function topicLabel(name: string, dark: boolean, assets: StrataAssets): THREE.Sprite | null {
  const texture = assets.label(name, dark);
  if (!texture) return null;
  const sprite = new THREE.Sprite(new THREE.SpriteMaterial({
    map: texture, transparent: true, depthTest: false, depthWrite: false,
  }));
  sprite.scale.set(3.2, 0.6, 1);
  sprite.position.y = 6.44;
  sprite.userData.label = true;
  return sprite;
}


const RING_STEPS = 32;

function createTopic(topic: Topic, maxRate: number, dark: boolean,
  highlight: 'primary' | 'comparison' | null, ringSegments: number, showActivity: boolean,
  assets: StrataAssets): THREE.Group {
  const group = new THREE.Group();
  group.position.copy(topicPosition(topic));
  group.userData.topic = topic.name;
  const counts = Array.from({ length: BUCKETS }, (_, bucket) => bucketReading(topic.activity, bucket));
  const hasData = showActivity && counts.some(value => value !== null);
  // The colour is the topic's family (`topicColor`): a name-derived, stable hue, the lightness stepping
  // with the stage. It was a hue from the slot, confined to 20 % of the wheel, which made every
  // column the same teal at a glance.
  const tone = topicColor(topic.name, dark);
  const color = new THREE.Color().setHSL(tone.h, tone.s, tone.l);
  // The socle's shape is the family's second channel, for whoever cannot tell the hues apart.
  const sides = familyShape(topicFamily(topic.name));
  const base = new THREE.Mesh(
    assets.geometry(`base:${sides}`, () => new THREE.CylinderGeometry(0.96, 0.96, 0.14, sides)),
    new THREE.MeshBasicMaterial({ color, transparent: true, opacity: 0.6, depthWrite: false }),
  );
  base.position.y = 0.05;
  group.add(base);
  const height = 5.6;
  const shell = new THREE.Mesh(
    assets.geometry('shell', () => new THREE.CylinderGeometry(0.86, 0.86, height, 40, 1, true)),
    new THREE.MeshBasicMaterial({ color, transparent: true, opacity: hasData ? 0.13 : 0.05, side: THREE.DoubleSide, depthWrite: false }),
  );
  shell.position.y = height / 2 + 0.12;
  group.add(shell);
  if (showActivity) for (let i = 0; i < BUCKETS; i++) {
    const value = counts[i];
    const measured = value !== null;
    const normalized = measured
      ? Math.round(Math.log1p(value) / Math.log1p(Math.max(1, maxRate)) * RING_STEPS) / RING_STEPS : 0;
    const geometry = measured
      ? assets.geometry(`ring:${normalized}:${ringSegments}`, () =>
        new THREE.TorusGeometry(0.38 + 0.56 * normalized, 0.025 + normalized * 0.035, 6, ringSegments))
      : assets.geometry(`ring:none:${ringSegments}`, () => new THREE.TorusGeometry(0.43, 0.014, 6, ringSegments));
    const ring = new THREE.Mesh(geometry,
      new THREE.MeshBasicMaterial({ color: measured ? color : 0x8b9aaa, transparent: true, opacity: measured ? 0.4 + normalized * 0.48 : 0.22, depthWrite: false }),
    );
    ring.rotation.x = Math.PI / 2;
    ring.position.y = RING_BASE_Y + i * RING_STEP_Y;
    ring.userData.bucketIndex = i;
    ring.userData.baseOpacity = (ring.material as THREE.MeshBasicMaterial).opacity;
    ring.userData.baseColor = (ring.material as THREE.MeshBasicMaterial).color.clone();
    group.add(ring);
  }
  const crown = new THREE.Mesh(
    assets.geometry('crown', () => new THREE.TorusGeometry(0.89, 0.028, 8, 48)),
    new THREE.MeshBasicMaterial({ color, transparent: true, opacity: 0.8 }),
  );
  crown.rotation.x = Math.PI / 2;
  crown.position.y = height + 0.12;
  group.add(crown);
  // A real activity series is required before rendering any moving activity indicator.
  if (hasData && counts.some(n => n !== null && n > 0)) {
    const glow = new THREE.Mesh(
      assets.geometry('glow', () => new THREE.SphereGeometry(0.10, 10, 8)),
      new THREE.MeshBasicMaterial({ color, transparent: true, opacity: 0.95 }),
    );
    glow.userData.pulse = topic.layoutIndex * 0.41;
    glow.position.set(0, 0.3, 0);
    group.add(glow);
  }
  if (highlight) {
    const accent = highlight === 'primary' ? 0x0c9da9 : 0x9b76db;
    for (const y of [0.12, height + 0.13]) {
      const halo = new THREE.Mesh(
        assets.geometry('halo', () => new THREE.TorusGeometry(1.15, 0.065, 8, 54)),
        new THREE.MeshBasicMaterial({ color: accent, transparent: true, opacity: 0.93, depthWrite: false }),
      );
      halo.rotation.x = Math.PI / 2;
      halo.position.y = y;
      group.add(halo);
    }
  }
  const label = topicLabel(topic.name, dark, assets);
  if (label) group.add(label);
  return group;
}

/** The floor follows the scene's extent and the theme; a grid sized once at mount was too small for a widened filter. */
function createGround(size: number, dark: boolean): THREE.GridHelper {
  // Quieter than the columns it carries: the grid used to be the most contrasted thing in the scene.
  const ground = new THREE.GridHelper(size, 24, dark ? 0x3a4f61 : 0xa9bccb, dark ? 0x233443 : 0xd8e2ea);
  ground.userData.size = size;
  ground.userData.dark = dark;
  return ground;
}

const isDarkTheme = () => document.documentElement.classList.contains('dark') ||
  document.documentElement.getAttribute('data-theme') === 'dark';

/** Touch screens get one-finger page scrolling; orbiting and zooming take two fingers there. */
export const coarsePointer = () => typeof window.matchMedia === 'function'
  && window.matchMedia('(pointer: coarse)').matches;

/** What the operator is pointing at, as opposed to what was measured. */
type StrataEmphasis = {
  topics: Topic[]; selected: string | null; selectedBucket: number; lensAvailable: boolean; layers: StrataLayers;
  /** The columns whose name stays written (`labelledTopics`); the others show it on hover. */
  labelled: ReadonlySet<string>;
};

/**
 * Applies the time lens, the deviation colour and the selection scale to the current columns.
 * It runs after every rebuild as well as on every change of the emphasis itself: the rebuild
 * replaces the rings with fresh ones at their base opacity, and when the two lived in separate
 * effects a partition poll or a quality change wiped the lens until the next bucket change.
 */
function applyEmphasis(current: { groups: THREE.Group[]; lens: THREE.Mesh }, emphasis: StrataEmphasis) {
  const { topics, selected, selectedBucket, lensAvailable, layers, labelled } = emphasis;
  current.lens.position.y = RING_BASE_Y + selectedBucket * RING_STEP_Y;
  current.lens.visible = lensAvailable && layers.activity;
  const measurements = new Map(topics.map(topic => [topic.name, topic.activity]));
  for (const group of current.groups) {
    group.scale.setScalar(group.userData.topic === selected ? 1.04 : 1);
    const anomaly = layers.anomalies ? detectActivityAnomaly(measurements.get(group.userData.topic as string), selectedBucket) : null;
    for (const object of group.children) {
      if (object.userData.label) object.visible = labelled.has(group.userData.topic as string);
      if (!(object instanceof THREE.Mesh) || object.userData.bucketIndex === undefined) continue;
      const material = object.material as THREE.MeshBasicMaterial;
      const active = object.userData.bucketIndex === selectedBucket;
      material.setValues({ opacity: Math.min(1,
        (object.userData.baseOpacity as number) * (active ? 1.4 : 0.42) * ((group.userData.dimFactor as number | undefined) ?? 1)) });
      material.color.copy(object.userData.baseColor as THREE.Color);
      if (active && anomaly) material.color.setHex(0xe7a044);
    }
  }
}

function StrataCanvas({ topics, selected, compareWith, diagnosticMode, onSelect, onFocus,
  focused, partitionActivity, forecast,
  motion, quality, links, comparedLinks, inferredLinks, labelled, health, timeWindow, selectedGroup, themeRevision, selectedBucket, trace, lensAvailable, layers, frameKey, onHover, onPerformance }: {
  topics: Topic[];
  /** Changes when the operator changes what is in view (the filter): the camera re-frames then. */
  frameKey: string;
  selected: string | null;
  compareWith: string | null;
  diagnosticMode: DiagnosticMode;
  onSelect: (topic: string) => void;
  onFocus: (topic: string) => void;
  focused: string | null;
  partitionActivity: PartitionActivityResponse | null;
  forecast: StrataProjection | null;
  motion: boolean;
  quality: StrataQuality;
  links: StrataLink[];
  /** The pairs a count-comparison metric compares (`metricLinks`): an operator's declaration. */
  comparedLinks: StrataLink[];
  /** Guessed from topic names (`inferNameLinks`): drawn only on their own layer, never as lineage. */
  inferredLinks: StrataLink[];
  labelled: ReadonlySet<string>;
  /** The count-comparison metrics' verdict on a pair of topics, which colours the link between them. */
  health: LinkHealth;
  timeWindow: TimeWindow | null;
  selectedGroup: ConsumerGroupLag | null;
  themeRevision: number;
  selectedBucket: number;
  trace: ParsedFlow | null;
  lensAvailable: boolean;
  layers: typeof DEFAULT_STRATA_LAYERS;
  onHover: (hover: { name: string; x: number; y: number } | null) => void;
  onPerformance: (reading: StrataPerformance) => void;
}) {
  const host = useRef<HTMLDivElement>(null);
  const onSelectRef = useRef(onSelect);
  const onFocusRef = useRef(onFocus);
  const onHoverRef = useRef(onHover);
  const onPerformanceRef = useRef(onPerformance);
  const motionRef = useRef(motion);
  const budget = useMemo(() => strataRenderBudget(quality, topics.length, window.devicePixelRatio || 1), [quality, topics.length]);
  const budgetRef = useRef(budget);
  const [webglFailed, setWebglFailed] = useState(false);
  useEffect(() => { onSelectRef.current = onSelect; }, [onSelect]);
  useEffect(() => { onFocusRef.current = onFocus; }, [onFocus]);
  useEffect(() => { onHoverRef.current = onHover; }, [onHover]);
  useEffect(() => { onPerformanceRef.current = onPerformance; }, [onPerformance]);
  const state = useRef<{
    scene: THREE.Scene;
    camera: THREE.OrthographicCamera;
    renderer: THREE.WebGLRenderer;
    controls: OrbitControls;
    content: THREE.Group;
    groups: THREE.Group[];
    lens: THREE.Mesh;
    assets: StrataAssets;
    /** `null` keeps that half as it is: a re-frame changes the size, a theme change the colours. */
    setGround: (size: number | null, dark: boolean | null) => void;
    signature: string;
    visible: string[];
    frameKey: string;
    userMoved: boolean;
    rebuildMs: number | null;
    invalidate: () => void;
    fit: (items: Topic[]) => void;
    focus: (topic: string | null) => void;
    positions: Map<string, THREE.Vector3>;
    /** Travel along the links; the render loop moves them while motion is on. */
    particles: THREE.Mesh[];
    overview: { position: THREE.Vector3; target: THREE.Vector3; zoom: number };
  } | null>(null);
  useEffect(() => {
    motionRef.current = motion;
    if (state.current) {
      state.current.controls.enableDamping = motion;
      state.current.invalidate();
    }
  }, [motion]);
  useEffect(() => {
    budgetRef.current = budget;
    if (state.current) {
      state.current.renderer.setPixelRatio(budget.pixelRatio);
      state.current.invalidate();
    }
  }, [budget]);
  useEffect(() => {
    const element = host.current;
    if (!element) return;
    const scene = new THREE.Scene();
    const camera = new THREE.OrthographicCamera(-12, 12, 12, -12, 0.1, 500);
    const initialFrame = sceneFrame(topics);
    const extent = initialFrame.extent;
    let halfHeight = extent;
    const controlsTarget = initialFrame.center.clone();
    camera.position.set(controlsTarget.x + extent * 1.2, extent * 1.05,
      controlsTarget.z + extent * 1.2);
    camera.lookAt(controlsTarget);
    let renderer: THREE.WebGLRenderer;
    try {
      renderer = new THREE.WebGLRenderer({ antialias: true, alpha: true, powerPreference: 'low-power' });
    } catch {
      // Defer the fallback update: React disallows synchronous state writes from effects.
      const fallback = window.setTimeout(() => setWebglFailed(true), 0);
      return () => window.clearTimeout(fallback);
    }
    renderer.setPixelRatio(budgetRef.current.pixelRatio);
    renderer.outputColorSpace = THREE.SRGBColorSpace;
    element.appendChild(renderer.domElement);
    const controls = new OrbitControls(camera, renderer.domElement);
    controls.target.copy(controlsTarget);
    controls.enableDamping = motionRef.current;
    controls.dampingFactor = 0.08;
    controls.minPolarAngle = 0.15;
    controls.maxPolarAngle = Math.PI / 2.12;
    controls.minZoom = 0.5;
    controls.maxZoom = 5;
    controls.enablePan = true;
    if (coarsePointer()) {
      // OrbitControls claims every touch (`touch-action: none`), and the canvas is at least 510 px
      // tall: on a phone, the page could hardly be scrolled at all. One finger now scrolls the
      // page; two fingers orbit and zoom.
      controls.touches = { ONE: null, TWO: THREE.TOUCH.DOLLY_ROTATE };
      renderer.domElement.style.touchAction = 'pan-y';
    }
    controls.update();
    const contentGroup = new THREE.Group();
    scene.add(contentGroup);
    // The horizontal time cursor is scene-owned and never reconstructed by polling.
    const lens = new THREE.Mesh(
      new THREE.PlaneGeometry(Math.max(28, extent * 2.65), Math.max(28, extent * 2.65)),
      new THREE.MeshBasicMaterial({ color: 0x83b9d5, transparent: true, opacity: 0.085,
        side: THREE.DoubleSide, depthWrite: false }),
    );
    lens.rotation.x = -Math.PI / 2;
    lens.position.copy(initialFrame.center);
    lens.position.y = RING_BASE_Y + 11 * RING_STEP_Y;
    scene.add(lens);
    let ground = createGround(Math.max(40, extent * 2.8), isDarkTheme());
    ground.position.set(initialFrame.center.x, -0.12, initialFrame.center.z);
    scene.add(ground);
    const assets = new StrataAssets();
    const current = {
      scene, camera, renderer, controls, content: contentGroup, assets,
      setGround: (size: number | null, dark: boolean | null) => {
        const nextSize = size ?? ground.userData.size as number;
        const nextDark = dark ?? ground.userData.dark as boolean;
        if (ground.userData.size === nextSize && ground.userData.dark === nextDark) return;
        const next = createGround(nextSize, nextDark);
        next.position.copy(ground.position);
        scene.remove(ground);
        ground.geometry.dispose();
        (ground.material as THREE.Material).dispose();
        scene.add(next);
        ground = next;
      },
      groups: [] as THREE.Group[], lens, signature: sceneSignature(topics), particles: [] as THREE.Mesh[],
      visible: topics.map(t => t.name), frameKey, userMoved: false,
      positions: new Map<string, THREE.Vector3>(),
      rebuildMs: null as number | null,
      overview: { position: camera.position.clone(), target: controlsTarget.clone(), zoom: 1 },
      invalidate: () => {},
      fit: (_items: Topic[]) => {},
      focus: (_topic: string | null) => {},
    };
    state.current = current;

    const pointer = new THREE.Vector2();
    const raycaster = new THREE.Raycaster();
    let down: { x: number; y: number } | null = null;
    let transition: {
      startedAt: number; fromPosition: THREE.Vector3; toPosition: THREE.Vector3;
      fromTarget: THREE.Vector3; toTarget: THREE.Vector3; fromZoom: number; toZoom: number;
    } | null = null;
    const onPointerDown = (event: PointerEvent) => { down = { x: event.clientX, y: event.clientY }; };
    const pickedTopic = (event: PointerEvent | MouseEvent): string | null => {
      const bounds = renderer.domElement.getBoundingClientRect();
      if (!bounds.width || !bounds.height) return null;
      pointer.set((event.clientX - bounds.left) / bounds.width * 2 - 1,
        1 - (event.clientY - bounds.top) / bounds.height * 2);
      raycaster.setFromCamera(pointer, camera);
      const picked = raycaster.intersectObjects(current.groups, true)[0]?.object;
      let item: THREE.Object3D | null = picked ?? null;
      while (item && !item.userData.topic) item = item.parent;
      return typeof item?.userData.topic === 'string' ? item.userData.topic as string : null;
    };
    let lastTap: StrataTap | null = null;
    const onPointerUp = (event: PointerEvent) => {
      const start = down;
      down = null;
      if (!start || Math.hypot(start.x - event.clientX, start.y - event.clientY) > 5) return;
      const topic = pickedTopic(event);
      if (!topic) { lastTap = null; return; }
      onSelectRef.current(topic);
      if (event.pointerType !== 'touch') return;
      // No reliable dblclick on a touch screen: the second tap on the same column focuses it.
      if (isDoubleTap(lastTap, topic, event.timeStamp)) {
        lastTap = null;
        onHoverRef.current(null);
        onFocusRef.current(topic);
      } else {
        lastTap = { topic, atMs: event.timeStamp };
      }
    };
    let lastHoverAt = 0;
    const onPointerMove = (event: PointerEvent) => {
      if (event.buttons || event.timeStamp - lastHoverAt < 50) {
        if (event.buttons) onHoverRef.current(null);
        return;
      }
      lastHoverAt = event.timeStamp;
      const topic = pickedTopic(event);
      const rect = renderer.domElement.getBoundingClientRect();
      onHoverRef.current(topic ? {
        name: topic,
        x: Math.min(Math.max(8, rect.width - 236), Math.max(8, event.clientX - rect.left + 12)),
        y: Math.min(Math.max(8, rect.height - 192), Math.max(8, event.clientY - rect.top + 10)),
      } : null);
    };
    const onPointerLeave = () => { down = null; onHoverRef.current(null); };
    const onDoubleClick = (event: MouseEvent) => {
      const topic = pickedTopic(event);
      if (topic) { onHoverRef.current(null); onSelectRef.current(topic); onFocusRef.current(topic); }
    };
    renderer.domElement.addEventListener('dblclick', onDoubleClick);
    renderer.domElement.addEventListener('pointerdown', onPointerDown);
    renderer.domElement.addEventListener('pointerup', onPointerUp);
    renderer.domElement.addEventListener('pointermove', onPointerMove);
    renderer.domElement.addEventListener('pointerleave', onPointerLeave);
    const resize = () => {
      const width = Math.max(1, element.clientWidth);
      const height = Math.max(1, element.clientHeight);
      camera.left = -halfHeight * width / height;
      camera.right = halfHeight * width / height;
      camera.top = halfHeight;
      camera.bottom = -halfHeight;
      camera.updateProjectionMatrix();
      renderer.setSize(width, height, false);
      renderer.render(scene, camera);
    };
    current.fit = (items: Topic[]) => {
      transition = null;
      current.userMoved = false;
      const frame = sceneFrame(items);
      halfHeight = frame.extent;
      camera.position.set(frame.center.x + frame.extent * 1.2, frame.extent * 1.05,
        frame.center.z + frame.extent * 1.2);
      controls.target.copy(frame.center);
      camera.zoom = 1;
      camera.updateProjectionMatrix();
      current.overview = {
        position: camera.position.clone(), target: controls.target.clone(), zoom: 1,
      };
      ground.position.set(frame.center.x, -0.12, frame.center.z);
      current.setGround(Math.max(40, frame.extent * 2.8), null);
      current.lens.position.x = frame.center.x;
      current.lens.position.z = frame.center.z;
      const planeSize = Math.max(28, extent * 2.65);
      current.lens.scale.setScalar(Math.max(1, (frame.extent * 2.65) / planeSize));
      controls.update();
      resize();
    };
    current.focus = (topic: string | null) => {
      const point = topic ? current.positions.get(topic) : null;
      const target = point ? new THREE.Vector3(point.x, 2.8, point.z) : current.overview.target.clone();
      const offset = camera.position.clone().sub(controls.target);
      const direction = offset.lengthSq() > 0.1 ? offset.normalize() : new THREE.Vector3(1, 1, 1).normalize();
      const goal = point ? target.clone().addScaledVector(direction, Math.max(15, halfHeight * 0.8))
        : current.overview.position.clone();
      const targetZoom = point ? Math.min(5, Math.max(1.65, halfHeight / 6)) : current.overview.zoom;
      transition = {
        startedAt: performance.now(), fromPosition: camera.position.clone(), toPosition: goal,
        fromTarget: controls.target.clone(), toTarget: target,
        fromZoom: camera.zoom, toZoom: targetZoom,
      };
      current.invalidate();
    };
    let frame = 0;
    let last = 0;
    let contextLost = false;
    let reportSince = performance.now();
    let renderedFrames = 0;
    const reportPerformance = () => {
      if (document.visibilityState !== 'visible') { reportSince = performance.now(); renderedFrames = 0; return; }
      const now = performance.now();
      onPerformanceRef.current(summarizeStrataPerformance({
        elapsedMs: now - reportSince, renderedFrames, contextLost,
        drawCalls: renderer.info.render.calls, geometries: renderer.info.memory.geometries,
        textures: renderer.info.memory.textures, pixelRatio: renderer.getPixelRatio(),
        rebuildMs: current.rebuildMs, topics: current.groups.length,
        measuredAtMs: Date.now(),
      }));
      reportSince = now;
      renderedFrames = 0;
    };
    const reportTimer = window.setInterval(reportPerformance, 3000);
    const requestRender = () => {
      if (!contextLost && !frame && document.visibilityState === 'visible') frame = requestAnimationFrame(render);
    };
    const render = (time: number) => {
      frame = 0;
      const moving = motionRef.current && (current.particles.length > 0 || current.groups.some(group =>
        group.children.some(object => object.userData.pulse !== undefined)));
      // Static frames do not consume a permanent RAF loop. Dense scenes throttle
      // only continuous animation; camera movement and focus still render promptly.
      if (moving && time - last < budgetRef.current.frameIntervalMs) {
        requestRender();
        return;
      }
      last = time;
      controls.enableDamping = motionRef.current;
      if (transition) {
        const elapsed = Math.max(0, time - transition.startedAt);
        const t = motionRef.current ? Math.min(1, elapsed / 640) : 1;
        const eased = 1 - (1 - t) ** 3;
        camera.position.copy(transition.fromPosition).lerp(transition.toPosition, eased);
        controls.target.copy(transition.fromTarget).lerp(transition.toTarget, eased);
        camera.zoom = THREE.MathUtils.lerp(transition.fromZoom, transition.toZoom, eased);
        camera.updateProjectionMatrix();
        if (t >= 1) transition = null;
      }
      const cameraChanged = controls.update();
      for (const group of current.groups) {
        for (const object of group.children) {
          if (!(object instanceof THREE.Mesh) || object.userData.pulse === undefined) continue;
          object.position.y = moving
            ? 0.3 + ((time * 0.00035 + (object.userData.pulse as number)) % 1) * 5.2
            : 5.45;
        }
      }
      for (const particle of current.particles) {
        const flow = particle.userData.flow as { curve: THREE.Curve<THREE.Vector3>; phase: number };
        // Still, the particles keep their even spacing: how many there are still says how much flowed.
        flow.curve.getPointAt(moving ? (time * 0.00016 + flow.phase) % 1 : flow.phase, particle.position);
      }
      renderer.render(scene, camera);
      renderedFrames++;
      if (transition || moving || cameraChanged) requestRender();
    };
    current.invalidate = requestRender;
    const onOrbitChange = () => requestRender();
    controls.addEventListener('change', onOrbitChange);
    // 'start' fires on the operator's own drag, pinch or wheel, never on a programmatic move.
    const onOrbitStart = () => { current.userMoved = true; };
    controls.addEventListener('start', onOrbitStart);
    const onVisibilityChange = () => {
      if (document.visibilityState === 'visible') requestRender();
      else { cancelAnimationFrame(frame); frame = 0; }
    };
    document.addEventListener('visibilitychange', onVisibilityChange);
    const onContextLost = (event: Event) => {
      event.preventDefault();
      contextLost = true;
      cancelAnimationFrame(frame);
      frame = 0;
      setWebglFailed(true);
      reportPerformance();
    };
    const onContextRestored = () => { contextLost = false; setWebglFailed(false); requestRender(); };
    renderer.domElement.addEventListener('webglcontextlost', onContextLost);
    renderer.domElement.addEventListener('webglcontextrestored', onContextRestored);
    const observer = new ResizeObserver(resize);
    observer.observe(element);
    resize();
    requestRender();
    return () => {
      window.clearInterval(reportTimer);
      cancelAnimationFrame(frame);
      observer.disconnect();
      document.removeEventListener('visibilitychange', onVisibilityChange);
      controls.removeEventListener('change', onOrbitChange);
      controls.removeEventListener('start', onOrbitStart);
      renderer.domElement.removeEventListener('webglcontextlost', onContextLost);
      renderer.domElement.removeEventListener('webglcontextrestored', onContextRestored);
      renderer.domElement.removeEventListener('dblclick', onDoubleClick);
      renderer.domElement.removeEventListener('pointerdown', onPointerDown);
      renderer.domElement.removeEventListener('pointerup', onPointerUp);
      renderer.domElement.removeEventListener('pointermove', onPointerMove);
      renderer.domElement.removeEventListener('pointerleave', onPointerLeave);
      controls.dispose();
      disposeGroup(contentGroup);
      lens.geometry.dispose();
      (lens.material as THREE.Material).dispose();
      ground.geometry.dispose();
      (ground.material as THREE.Material).dispose();
      assets.dispose();
      renderer.dispose();
      renderer.domElement.remove();
      state.current = null;
    };
  // Camera lifetime is independent of metric polling: never reset orbit on refresh.
  // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  const emphasis = useRef<StrataEmphasis>({ topics, selected, selectedBucket, lensAvailable, layers, labelled });
  useEffect(() => {
    emphasis.current = { topics, selected, selectedBucket, lensAvailable, layers, labelled };
    const current = state.current;
    if (!current) return;
    applyEmphasis(current, emphasis.current);
    current.invalidate();
  }, [topics, selected, selectedBucket, lensAvailable, layers, labelled]);

  useEffect(() => {
    const current = state.current;
    if (!current) return;
    const signature = sceneSignature(topics);
    if (signature !== current.signature) {
      const visible = topics.map(t => t.name);
      // A topic created elsewhere must not throw away the operator's orbit.
      if (shouldRefit(current.visible, visible, frameKey !== current.frameKey, current.userMoved)) current.fit(topics);
      current.signature = signature;
      current.visible = visible;
    }
    current.frameKey = frameKey;
    // Replace measured geometry without tearing down WebGL, orbit or pointer handlers.
    const rebuildStarted = performance.now();
    disposeGroup(current.content);
    current.content.clear();
    const dark = isDarkTheme();
    current.setGround(null, dark);
    current.assets.keepLabels(topics.map(topic => topic.name), dark);
    (current.lens.material as THREE.MeshBasicMaterial).color.setHex(dark ? 0x83b9d5 : 0x5888af);
    const maxRate = layers.activity ? activityScaleMax(topics, BUCKETS) : 1;
    current.positions = new Map(topics.map(topic => [topic.name, topicPosition(topic)]));
    current.groups = topics.map(topic => {
      const highlight = topic.name === selected ? 'primary' :
        diagnosticMode === 'compare' && topic.name === compareWith ? 'comparison' : null;
      const column = createTopic(topic, maxRate, dark, highlight, budget.ringSegments, layers.activity, current.assets);
      const contextTopic = focused ?? (diagnosticMode === 'investigate' && layers.lineage &&
        links.some(link => link.from === selected || link.to === selected) ? selected : null);
      const isNeighbor = layers.lineage && links.some(link =>
        (link.from === contextTopic && link.to === topic.name) ||
        (link.to === contextTopic && link.from === topic.name));
      const dimmed = Boolean(contextTopic && topic.name !== contextTopic && topic.name !== compareWith && !isNeighbor);
      const dimFactor = focused ? 0.22 : 0.50;
      column.userData.dimFactor = dimmed ? dimFactor : 1;
      if (dimmed) {
        // The focus context stays in sight but must not compete with the inspected topic.
        column.traverse(object => {
          if (!(object instanceof THREE.Mesh || object instanceof THREE.Sprite)) return;
          const materials = Array.isArray(object.material) ? object.material : [object.material];
          for (const material of materials) {
            if (material instanceof THREE.MeshBasicMaterial || material instanceof THREE.SpriteMaterial) {
              material.setValues({ opacity: material.opacity * dimFactor, transparent: true });
            }
          }
        });
      }
      current.content.add(column);
      return column;
    });
    const lookup = current.positions;
    current.particles = [];
    const byName = new Map(topics.map(topic => [topic.name, topic]));
    // Declared lineage first, then the pairs a count comparison declares, then the links guessed from
    // names, and a pair is drawn once, by the first that holds it. They are never drawn alike: the
    // declared ones keep their long dashes, a compared pair is a near-solid line in its verdict's
    // colour, the guessed ones a fine grey dotted line on a layer that is off by default.
    const drawnLinks: { link: StrataLink; kind: 'declared' | 'compared' | 'inferred' }[] = [
      ...(layers.lineage ? links.map(link => ({ link, kind: 'declared' as const })) : []),
      ...(layers.compared ? comparedLinks.map(link => ({ link, kind: 'compared' as const })) : []),
      ...(layers.inferred ? inferredLinks.map(link => ({ link, kind: 'inferred' as const })) : []),
    ];
    const drawnPairs = new Set<string>();
    for (const { link, kind } of drawnLinks) {
      const inferred = kind === 'inferred';
      const compared = kind === 'compared';
      const start = lookup.get(link.from);
      const end = lookup.get(link.to);
      if (!start || !end) continue;
      const pair = [link.from, link.to].sort().join('\u0000');
      if (drawnPairs.has(pair)) continue;
      drawnPairs.add(pair);
      const span = start.distanceTo(end);
      const curve = new THREE.CubicBezierCurve3(
        new THREE.Vector3(start.x, 5.6, start.z),
        new THREE.Vector3(start.x, 6.5 + span * 0.12, start.z),
        new THREE.Vector3(end.x, 6.5 + span * 0.12, end.z),
        new THREE.Vector3(end.x, 5.6, end.z),
      );
      const geometry = new THREE.BufferGeometry().setFromPoints(curve.getPoints(40));
      const neighborhood = link.from === selected || link.to === selected;
      const receding = diagnosticMode === 'investigate' && Boolean(selected) && !neighborhood;
      // A count-comparison metric over the pair in breach turns the link and what travels on it to its
      // severity. Nothing else does: a link without such a metric is drawn in its ordinary colour.
      const verdict = healthOf(health, link);
      const alarm = verdict === 'critical' ? 0xdd5b59 : verdict === 'warning' ? 0xe4a757 : null;
      // A compared pair within its thresholds says so in the lag-free green; one whose metric has no
      // verdict yet (pending, failing) stays neutral rather than passing for healthy.
      const lineColor = alarm ?? (compared ? (verdict === 'ok' ? 0x4aaf99 : (dark ? 0x9db6db : 0x6d7fa2))
        : inferred ? (dark ? 0xa3b1be : 0x5d6b78)
        : neighborhood ? (dark ? 0x48c6cb : 0x118b9c) : (dark ? 0x9db6db : 0x6d7fa2));
      const line = new THREE.Line(geometry, new THREE.LineDashedMaterial({
        color: lineColor, transparent: true,
        opacity: receding ? 0.12 : diagnosticMode === 'investigate' && selected ? 0.92 : inferred ? 0.72 : 0.7,
        dashSize: inferred ? 0.1 : compared ? 1.2 : 0.38, gapSize: inferred ? 0.16 : compared ? 0.08 : 0.24,
      }));
      line.computeLineDistances();
      line.userData.link = { from: link.from, to: link.to, inferred, kind, health: verdict };
      current.content.add(line);
      // What travels: as many particles as the target produced in the latest bucket, on the rings'
      // own logarithmic scale. No measurement, no particle — an empty link is not drawn as a quiet one.
      const target = byName.get(link.to);
      const count = layers.activity
        ? flowParticleCount(target ? bucketReading(target.activity, BUCKETS - 1) : null, maxRate) : 0;
      const tone = topicColor(link.from, dark);
      const particleColor = alarm ?? new THREE.Color().setHSL(tone.h, tone.s, Math.min(0.85, tone.l + 0.12));
      for (let k = 0; k < count; k++) {
        const particle = new THREE.Mesh(
          current.assets.geometry('flow-particle', () => new THREE.SphereGeometry(0.16, 10, 8)),
          new THREE.MeshBasicMaterial({ color: particleColor, transparent: true, depthWrite: false,
            opacity: receding ? 0.15 : inferred ? 0.7 : 0.95 }),
        );
        particle.userData.flow = { curve, phase: k / count };
        particle.userData.link = line.userData.link;
        curve.getPointAt(k / count, particle.position);
        current.content.add(particle);
        current.particles.push(particle);
      }
    }

    // Chronological key matches are distinct from dashed Flink lineage. The links express event
    // order, not a guaranteed causal transfer. Height is time on this axis, so each hit sits at the
    // height of its own instant and the slope between two hits is the latency between them; a hit
    // older or newer than the window is clamped to its edge and drawn faint, never as if in it.
    if (layers.traces && trace) {
      const visibleHits = trace.hits.filter(hit => lookup.has(hit.topic)).slice(0, 48);
      const placed = visibleHits.map(hit => {
        const at = lookup.get(hit.topic)!;
        const height = timeWindow
          ? timeHeight(hit.firstTimestamp, timeWindow.startMs, timeWindow.bucketMs, BUCKETS)
          : { y: 6.18, inWindow: true };
        return { point: new THREE.Vector3(at.x, height.y, at.z), inWindow: height.inWindow };
      });
      placed.forEach(({ point, inWindow }, index) => {
        const marker = new THREE.Mesh(
          current.assets.geometry('trace-point', () => new THREE.SphereGeometry(0.17, 14, 10)),
          new THREE.MeshBasicMaterial({ color: 0x1bafbd, transparent: !inWindow, opacity: inWindow ? 1 : 0.35 }),
        );
        marker.position.copy(point);
        marker.userData.traceHit = index;
        current.content.add(marker);
        if (index === 0) return;
        const previous = placed[index - 1];
        const beam = new THREE.Mesh(
          new THREE.TubeGeometry(new THREE.LineCurve3(previous.point, point), 1, 0.045, 6, false),
          new THREE.MeshBasicMaterial({ color: 0x16afbd, transparent: true,
            opacity: inWindow && previous.inWindow ? 0.9 : 0.35 }),
        );
        current.content.add(beam);
      });
    }

    const selectedIndex = topics.findIndex(t => t.name === selected);
    if (layers.consumers && selectedIndex >= 0 && selectedGroup) {
      const point = topicPosition(topics[selectedIndex]);
      const partitions = selectedGroup.partitions.slice(0, 64);
      const healthColor = selectedGroup.error ? 0x8592a1 : selectedGroup.totalLag > 0 && selectedGroup.membersKnown && selectedGroup.assignedMembers === 0
        ? 0xdd5b59 : 0xe4a757;
      for (let index = 0; index < partitions.length; index++) {
        const partition = partitions[index];
        const angle = index * Math.PI * 2 / Math.max(1, partitions.length);
        const color = partition.lag === null ? 0x8d9ba8
          : partition.lag < 0 ? 0xd95b64 : partition.lag === 0 ? 0x4aaf99 : healthColor;
        const marker = new THREE.Mesh(
          current.assets.geometry('lag-marker', () => new THREE.SphereGeometry(0.12, 10, 8)),
          new THREE.MeshBasicMaterial({ color }),
        );
        marker.position.set(point.x + Math.cos(angle) * 1.45, 0.42, point.z + Math.sin(angle) * 1.45);
        current.content.add(marker);
      }
    }
    if (layers.activity && focused && partitionActivity?.topic === focused && partitionActivity.partitionsMeasured > 0) {
      const center = lookup.get(focused);
      if (center) current.content.add(createPartitionRibbons(partitionActivity, center, dark));
    }
    if (layers.forecast && forecast && focused === forecast.topic) {
      const center = lookup.get(forecast.topic);
      if (center) current.content.add(createForecastCanopy(forecast, center, dark));
    }
    applyEmphasis(current, emphasis.current);
    current.rebuildMs = performance.now() - rebuildStarted;
    current.invalidate();
  }, [topics, links, comparedLinks, inferredLinks, health, timeWindow, selectedGroup, selected, compareWith, diagnosticMode, focused, partitionActivity, forecast, themeRevision, trace, budget.ringSegments, layers, frameKey]);

  const scopeKey = sceneSignature(topics);
  // A change of scope re-aims a focused camera; with nothing focused it must leave the camera
  // alone, or every topic created elsewhere sent the operator back to the overview.
  const lastFocus = useRef<string | null>(null);
  useEffect(() => {
    if (focused || lastFocus.current) state.current?.focus(focused);
    lastFocus.current = focused;
  }, [focused, scopeKey]);

  return (
    <div ref={host} role="img"
      aria-label="Interactive Kafka topic activity. Drag to orbit, scroll to zoom, double-click to focus. Use the topic list for keyboard access."
      className="w-full h-full min-h-[380px] cursor-grab active:cursor-grabbing">
      {webglFailed && <p role="status" className="p-6 text-sm text-on-surface">3D rendering unavailable or context lost. Use the accessible topic list below.</p>}
    </div>
  );
}

/** Called only from an explicit click, never during a React render. */
function downloadInvestigation(inspection: StrataInspection, view: StrataSnapshotView) {
  const capturedAt = Date.now();
  const contents = strataInvestigationSnapshot(inspection, view, capturedAt);
  const blob = new Blob([JSON.stringify(contents, null, 2)], { type: 'application/json' });
  const objectUrl = URL.createObjectURL(blob);
  const link = document.createElement('a');
  link.href = objectUrl;
  link.download = 'strata-investigation-' + capturedAt + '.json';
  document.body.appendChild(link);
  link.click();
  link.remove();
  window.setTimeout(() => URL.revokeObjectURL(objectUrl), 10000);
}

export default function Strata3D() {
  const [initialView] = useState(() => readStrataView(new URLSearchParams(window.location.search)));
  const [dashboard, setDashboard] = useState<Dashboard | null>(null);
  const [activitySnapshot, setActivitySnapshot] = useState<{
    scope: string; data: ActivityResponse; receivedAt: number;
  } | null>(null);
  const [lineage, setLineage] = useState<StrataLineage | null>(null);
  const [lineageError, setLineageError] = useState<string | null>(null);
  const [activityError, setActivityError] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [selected, setSelected] = useState<string | null>(initialView.topic);
  const [diagnosticMode, setDiagnosticMode] = useState<DiagnosticMode>(initialView.mode);
  const [compareWith, setCompareWith] = useState<string | null>(initialView.peer);
  const [selectedGroup, setSelectedGroup] = useState<ConsumerGroupLag | null>(null);
  const [query, setQuery] = useState(initialView.filter);
  const [filteredQuery, setFilteredQuery] = useState(initialView.filter);
  const [quality, setQuality] = useState<StrataQuality>(initialView.quality);
  const [shareNotice, setShareNotice] = useState<string | null>(null);
  const [shareLink, setShareLink] = useState<string | null>(null);
  const [performanceStats, setPerformanceStats] = useState<StrataPerformance | null>(null);
  const [motion, setMotion] = useState(() => !window.matchMedia('(prefers-reduced-motion: reduce)').matches);
  const [touch] = useState(coarsePointer);
  useEffect(() => {
    const preference = window.matchMedia('(prefers-reduced-motion: reduce)');
    const onChange = () => setMotion(!preference.matches);
    preference.addEventListener('change', onChange);
    return () => preference.removeEventListener('change', onChange);
  }, []);
  const [refresh, setRefresh] = useState(0);
  const [themeRevision, setThemeRevision] = useState(0);
  const [selectedBucket, setSelectedBucket] = useState(initialView.bucket);
  const [replaying, setReplaying] = useState(false);
  const [replaySpeed, setReplaySpeed] = useState(1);
  const [focused, setFocused] = useState<string | null>(initialView.focus);
  const [partitionSnapshot, setPartitionSnapshot] = useState<{ topic: string; data: PartitionActivityResponse } | null>(null);
  const [partitionError, setPartitionError] = useState<string | null>(null);
  const [forecastOverlay, setForecastOverlay] = useState<StrataProjection | null>(null);
  const [traced, setTraced] = useState<{ scope: string; flow: ParsedFlow } | null>(null);
  const [layers, setLayers] = useState(DEFAULT_STRATA_LAYERS);
  // `layout` below is the stable-slot cache; this is the operator's choice of arrangement.
  const [arrangement, setArrangement] = useState<StrataLayout>(initialView.layout);
  const [metrics, setMetrics] = useState<MetricConfig[]>([]);
  const [shortcutsOpen, setShortcutsOpen] = useState(false);
  const topicFilterRef = useRef<HTMLInputElement>(null);
  const closeShortcuts = useCallback(() => setShortcutsOpen(false), []);
  const [hovered, setHovered] = useState<{ name: string; x: number; y: number } | null>(null);
  const updateHovered = useCallback((next: { name: string; x: number; y: number } | null) => {
    setHovered(previous => {
      if (!next && !previous) return previous;
      if (next && previous && previous.name === next.name &&
        Math.abs(previous.x - next.x) < 12 && Math.abs(previous.y - next.y) < 12) return previous;
      return next;
    });
  }, []);
  const [navigatorOpen, setNavigatorOpen] = useState(true);
  const [inspectorOpen, setInspectorOpen] = useState(true);
  const [navigatorWidth, setNavigatorWidth] = useState(246);
  const [inspectorWidth, setInspectorWidth] = useState(350);

  // Share only the operator's view choices; never put Kafka keys, trace records
  // or forecast payloads in URLs. Replacements avoid polluting browser history.
  useEffect(() => {
    const existing = new URLSearchParams(window.location.search);
    const next = writeStrataView(existing, {
      mode: diagnosticMode, topic: selected, peer: compareWith, focus: focused,
      filter: query, bucket: selectedBucket, quality, layout: arrangement,
    });
    if (existing.toString() !== next.toString()) {
      const suffix = next.toString();
      window.history.replaceState(window.history.state, '',
        window.location.pathname + (suffix ? '?' + suffix : '') + window.location.hash);
    }
  }, [diagnosticMode, selected, compareWith, focused, query, selectedBucket, quality, arrangement]);

  const copyViewLink = async () => {
    const link = shareStrataUrl(window.location.href, {
      mode: diagnosticMode, topic: selected, peer: compareWith, focus: focused,
      filter: query, bucket: selectedBucket, quality, layout: arrangement,
    });
    const copied = await copyText(link);
    setShareLink(copied ? null : link);
    setShareNotice(copied
      ? 'View link copied. It contains no traced message keys.'
      : 'Copy this link manually. It contains no traced message keys.');
  };

  const onGroupSelect = useCallback((group: ConsumerGroupLag | null) => setSelectedGroup(group), []);
  const onProjection = useCallback((projection: StrataProjection | null) => setForecastOverlay(projection), []);
  const chooseTopic = useCallback((name: string) => {
    if (selected !== name) {
      setSelectedGroup(null);
      // A newly selected topic must not leave the camera focused on a different topic.
      if (focused) setFocused(null);
    }
    if (compareWith === name) setCompareWith(null);
    setSelected(name);
  }, [selected, compareWith, focused]);
  const focusTopic = useCallback((name: string) => {
    chooseTopic(name);
    setFocused(name);
  }, [chooseTopic]);

  useEffect(() => {
    if (!replaying) return;
    const timer = window.setInterval(() => {
      if (document.visibilityState === 'visible') setSelectedBucket(bucket => (bucket + 1) % BUCKETS);
    }, 1100 / replaySpeed);
    return () => window.clearInterval(timer);
  }, [replaying, replaySpeed]);

  useEffect(() => {
    const timeout = window.setTimeout(() => setFilteredQuery(query), 280);
    return () => window.clearTimeout(timeout);
  }, [query]);

  useEffect(() => {
    const observer = new MutationObserver(() => setThemeRevision(v => v + 1));
    observer.observe(document.documentElement, { attributes: true, attributeFilter: ['class', 'data-theme'] });
    return () => observer.disconnect();
  }, []);

  useEffect(() => {
    const controller = new AbortController();
    let active = true;
    let busy = false;
    const fetchDashboard = async () => {
      if (document.visibilityState === 'hidden' || busy) return;
      busy = true;
      try {
        const { data } = await axios.get<Dashboard>('/api/dashboard', { timeout: 15000, signal: controller.signal });
        if (active) { setDashboard(data); setError(null); }
      } catch (failure) {
        if (active && !axios.isCancel(failure)) setError('Cluster metadata could not be refreshed. Displaying last known values if available.');
      } finally {
        busy = false;
      }
    };
    void fetchDashboard();
    const interval = window.setInterval(() => { void fetchDashboard(); }, 60000);
    const onVisible = () => { if (document.visibilityState === 'visible') void fetchDashboard(); };
    document.addEventListener('visibilitychange', onVisible);
    return () => {
      active = false;
      controller.abort();
      window.clearInterval(interval);
      document.removeEventListener('visibilitychange', onVisible);
    };
  }, [refresh]);

  const names = useMemo(() => (dashboard?.topics ?? []).slice().sort((a, b) => a.localeCompare(b)), [dashboard]);
  const scoped = useMemo(() => names.filter(name => name.toLocaleLowerCase().includes(filteredQuery.toLocaleLowerCase())).slice(0, MAX_TOPICS), [names, filteredQuery]);
  const [layout, setLayout] = useState<{ names: string[]; slots: Map<string, number> }>(
    () => ({ names: [], slots: new Map() }));
  const layoutIndices = layout.names === names ? layout.slots : stableLayout(layout.slots, names);
  if (layout.names !== names) setLayout({ names, slots: layoutIndices });
  const layoutExtent = Math.max(names.length, ...Array.from(layoutIndices.values(), slot => slot + 1));
  const activeFocus = focused && scoped.includes(focused) ? focused : null;
  const scope = scoped.join(',');

  useEffect(() => {
    if (!scope) return;
    const controller = new AbortController();
    let active = true;
    let busy = false;
    const fetchActivity = async () => {
      if (document.visibilityState === 'hidden' || busy) return;
      busy = true;
      try {
        // POST, not GET: 48 names of up to 249 characters overflow a default request line.
        const { data } = await axios.post<ActivityResponse>('/api/dashboard/activity',
          { topics: scope.split(','), windowMs: WINDOW_MS, buckets: BUCKETS },
          { timeout: 20000, signal: controller.signal });
        if (active) { setActivitySnapshot({ scope, data, receivedAt: Date.now() }); setActivityError(null); }
      } catch (failure) {
        // Keep the last sample, as the cluster metadata does, and say so: blanking it turned
        // every ring neutral while the page claimed activity had simply not been measured yet.
        // `topicEvidence` marks the sample stale once it is older than 90 seconds.
        if (active && !axios.isCancel(failure)) {
          setActivityError('Topic activity could not be refreshed. Readings shown come from the last successful sample.');
        }
      } finally {
        busy = false;
      }
    };
    void fetchActivity();
    const interval = window.setInterval(() => { void fetchActivity(); }, 30000);
    const onVisible = () => { if (document.visibilityState === 'visible') void fetchActivity(); };
    document.addEventListener('visibilitychange', onVisible);
    return () => {
      active = false;
      controller.abort();
      window.clearInterval(interval);
      document.removeEventListener('visibilitychange', onVisible);
    };
  }, [scope, refresh]);

  useEffect(() => {
    const controller = new AbortController();
    let active = true;
    let busy = false;
    const fetchLineage = async () => {
      if (document.visibilityState === 'hidden' || busy) return;
      busy = true;
      try {
        const { data } = await axios.get<StrataLineage>('/api/lineage', {
          params: { connectedOnly: true }, timeout: 20000, signal: controller.signal,
        });
        if (active) { setLineage(data); setLineageError(null); }
      } catch (failure) {
        if (active && !axios.isCancel(failure)) {
          setLineageError('Lineage could not be refreshed; links are unavailable.');
          setLineage(null);
        }
      } finally {
        busy = false;
      }
    };
    void fetchLineage();
    const interval = window.setInterval(() => { void fetchLineage(); }, 120000);
    const onVisible = () => { if (document.visibilityState === 'visible') void fetchLineage(); };
    document.addEventListener('visibilitychange', onVisible);
    return () => {
      active = false;
      controller.abort();
      window.clearInterval(interval);
      document.removeEventListener('visibilitychange', onVisible);
    };
  }, [refresh]);

  // The count-comparison metrics colour the links they compare. Read-only, bounded, paused while the
  // tab is hidden like every other read here — and silent when it fails: a link without a verdict is
  // drawn in its ordinary colour, which is the truth about it, so a failure has nothing to correct.
  useEffect(() => {
    const controller = new AbortController();
    let active = true;
    let busy = false;
    const fetchMetrics = async () => {
      if (document.visibilityState === 'hidden' || busy) return;
      busy = true;
      try {
        const { data } = await axios.get<MetricConfig[]>('/api/metrics', { timeout: 20000, signal: controller.signal });
        if (active) setMetrics(Array.isArray(data) ? data : []);
      } catch (failure) {
        if (active && !axios.isCancel(failure)) setMetrics([]);
      } finally {
        busy = false;
      }
    };
    void fetchMetrics();
    const interval = window.setInterval(() => { void fetchMetrics(); }, 60000);
    const onVisible = () => { if (document.visibilityState === 'visible') void fetchMetrics(); };
    document.addEventListener('visibilitychange', onVisible);
    return () => {
      active = false;
      controller.abort();
      window.clearInterval(interval);
      document.removeEventListener('visibilitychange', onVisible);
    };
  }, [refresh]);

  useEffect(() => {
    if (!activeFocus) return;
    const controller = new AbortController();
    let active = true;
    let busy = false;
    const poll = async () => {
      if (document.visibilityState === 'hidden' || busy) return;
      busy = true;
      try {
        const { data } = await axios.get<PartitionActivityResponse>('/api/dashboard/partition-activity', {
          params: { topic: activeFocus, windowMs: WINDOW_MS, buckets: BUCKETS },
          signal: controller.signal, timeout: 20000,
        });
        if (active) { setPartitionSnapshot({ topic: activeFocus, data }); setPartitionError(null); }
      } catch (reason) {
        if (active && !axios.isCancel(reason)) {
          setPartitionSnapshot(null);
          setPartitionError('Partition-level activity is unavailable.');
        }
      } finally { busy = false; }
    };
    void poll();
    const timer = window.setInterval(() => { void poll(); }, 30000);
    const onVisible = () => { if (document.visibilityState === 'visible') void poll(); };
    document.addEventListener('visibilitychange', onVisible);
    return () => {
      active = false; controller.abort(); window.clearInterval(timer);
      document.removeEventListener('visibilitychange', onVisible);
    };
  }, [activeFocus, refresh]);

  const partitionActivity = partitionSnapshot?.topic === activeFocus ? partitionSnapshot.data : null;
  const visibleForecast = forecastOverlay?.topic === activeFocus ? forecastOverlay : null;
  const activity = activitySnapshot?.scope === scope ? activitySnapshot.data : null;
  const alignedPartitionActivity = partitionFrameMatches(partitionActivity, activity) ? partitionActivity : null;
  const activeTrace = traced?.scope === scope ? traced.flow : null;
  const onTrace = useCallback((flow: ParsedFlow | null) => {
    setTraced(flow ? { scope, flow } : null);
  }, [scope]);
  // Lanes are laid out from the topics in view: a pipeline is read along its stages, which the
  // spiral scatters. The spiral keeps its stable slots, so switching back restores every column.
  const lanes = useMemo(() => arrangement === 'lanes' ? laneLayout(scoped) : null, [arrangement, scoped]);
  const topics = useMemo(() => scoped.map(name => {
    const layoutIndex = layoutIndices.get(name) ?? 0;
    return {
      name, size: dashboard?.topicSizes[name] ?? null, activity: activity?.topics[name],
      layoutIndex, slot: lanes?.get(name) ?? spiralSlot(layoutIndex),
    };
  }), [scoped, dashboard, activity, layoutIndices, lanes]);
  const links = useMemo(() => lineage ? deriveStrataLinks(lineage, scoped) : [], [lineage, scoped]);
  const inferredLinks = useMemo(() => inferNameLinks(scoped, links), [scoped, links]);
  const comparedLinks = useMemo(() => metricLinks(metrics, scoped, links), [metrics, scoped, links]);
  const health = useMemo(() => linkHealth(metrics), [metrics]);
  // The cluster's families, not the filtered view's: choosing one must not make the others vanish.
  const families = useMemo(() => familyLegend(names), [names]);
  const windowStartMs = activity?.windowStartMs;
  const bucketMs = activity?.bucketMs;
  const timeWindow = useMemo(() => windowStartMs !== undefined && bucketMs !== undefined && bucketMs > 0
    ? { startMs: windowStartMs, bucketMs } : null, [windowStartMs, bucketMs]);
  const selectedTopic = topics.find(t => t.name === selected);
  const receivedAt = activitySnapshot?.scope === scope ? activitySnapshot.receivedAt : null;
  const selectedEvidence = topicEvidence(selectedTopic?.activity, selectedBucket, receivedAt);
  // Cheap, bounded derived views; avoid memoizing mutable Kafka snapshot objects.
  // React Compiler cannot safely preserve memoization around these inputs.
  const inspection = selectedTopic ? strataInspection(selectedTopic.name,
    selectedTopic.activity, selectedBucket, receivedAt, links, selectedGroup) : null;
  const leads = strataLeads(topics, selectedBucket, receivedAt,
    selectedTopic?.name ?? null, selectedGroup);
  const saveInvestigation = () => {
    if (inspection) downloadInvestigation(inspection, { filter: filteredQuery, quality, layers });
  };
  const hoveredTopic = hovered && topics.find(t => t.name === hovered.name);
  const setLayer = useCallback((layer: StrataLayer, enabled: boolean) => {
    setLayers(current => ({ ...current, [layer]: enabled }));
  }, []);
  const selectBucket = useCallback((bucket: number) => { setReplaying(false); setSelectedBucket(bucket); }, []);
  const activeCompareWith = diagnosticMode === 'compare' && compareWith !== selected &&
    topics.some(t => t.name === compareWith) ? compareWith : null;
  // Past a dozen columns only the busiest and the ones being pointed at keep their name written.
  // The inputs are folded into one key so the set — and the repaint it costs — changes only when
  // the answer can: a hover over the same column, or a poll that leaves the ranking, does not.
  const hoveredName = hovered?.name ?? null;
  const pinnedNames = [selected, activeCompareWith, activeFocus, hoveredName,
    ...(layers.traces ? activeTrace?.hits.map(hit => hit.topic) ?? [] : [])];
  const labelReadings = topics.map(topic => ({ name: topic.name, latest: bucketReading(topic.activity, BUCKETS - 1) }));
  const labelKey = layers.labels ? 'all:' + scope
    : [...labelledTopics(labelReadings, pinnedNames)].sort().join('\u0000');
  const labelled = useMemo(() => new Set(labelKey.startsWith('all:') ? scoped
    : labelKey ? labelKey.split('\u0000') : []), [labelKey, scoped]);
  const measured = topics.filter(t => t.activity?.available).length;
  const ringScaleMax = activityScaleMax(topics, BUCKETS);
  const bucketMinutes = activity?.bucketMs ? activity.bucketMs / 60000 : WINDOW_MS / BUCKETS / 60000;
  const bucketLabel = `${Number(bucketMinutes.toFixed(1)).toLocaleString()}-minute bucket`;
  const incomplete = topics.filter(t => topicEvidence(t.activity, selectedBucket,
    activitySnapshot?.scope === scope ? activitySnapshot.receivedAt : null).status === 'partial').length;
  const anomalies = topics.flatMap(topic => {
    const candidate = detectActivityAnomaly(topic.activity, selectedBucket);
    return candidate ? [{ name: topic.name, ...candidate }] : [];
  });
  const stalledGroup = selectedGroup && consumerHealth(selectedGroup) === 'STALLED'
    ? selectedGroup : null;

  // Ctrl/⌘+K belongs to Layout's global command palette. Local Strata
  // shortcuts act only when an interactive control is not handling the key.
  useEffect(() => {
    const onKey = (event: KeyboardEvent) => {
      if (shortcutsOpen) return;
      const target = event.target;
      const insideControl = target instanceof Element && Boolean(
        target.closest('input, textarea, select, button, a, [contenteditable="true"], [role="dialog"]'));
      const action = strataShortcut(event, insideControl);
      if (!action) return;
      if (action === 'replay' && !activity?.available) return;
      if (action === 'focus' && (!selected || !scoped.includes(selected))) return;
      event.preventDefault();
      switch (action) {
        case 'help': setShortcutsOpen(true); break;
        case 'search':
          setNavigatorOpen(true);
          window.requestAnimationFrame(() => topicFilterRef.current?.focus());
          break;
        case 'overview':
          setFocused(null); setCompareWith(null); setDiagnosticMode('investigate');
          break;
        case 'focus':
          if (selected) focusTopic(selected);
          break;
        case 'compare':
          setFocused(null); setDiagnosticMode('compare');
          break;
        case 'replay':
          if (replaying) setReplaying(false);
          else { setSelectedBucket(0); setReplaying(true); }
          break;
        case 'escape':
          // One step back at a time: leave the focus first, then the comparison.
          setHovered(null);
          if (focused) setFocused(null);
          else if (diagnosticMode === 'compare') { setCompareWith(null); setDiagnosticMode('investigate'); }
          break;
      }
    };
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, [shortcutsOpen, activity?.available, selected, scoped, focusTopic, replaying, focused, diagnosticMode]);

  return (
    <div data-testid="strata-workspace" className="space-y-4 pb-8">
      <header className="flex flex-wrap items-start justify-between gap-3">
        <div>
          <p className="text-xs font-medium tracking-widest uppercase text-primary">Spatial observability</p>
          <h1 className="text-2xl font-semibold text-on-surface">Kafka Strata 3D</h1>
          <p className="text-sm text-on-surface-variant">
            Measured offset production · declared Flink paths · consumer positions. Height represents time.
          </p>
        </div>
        <div className="flex flex-wrap items-center gap-2">
          <label className="flex items-center gap-2 text-xs text-on-surface-variant">
            Render quality
            <select aria-label="3D render quality" value={quality}
              onChange={event => setQuality(event.target.value as StrataQuality)}
              className="rounded-lg border border-outline-variant bg-surface px-2 py-2 text-sm text-on-surface">
              <option value="auto">Auto</option><option value="detail">Detail</option><option value="economy">Economy</option>
            </select>
          </label>
          <button type="button" className="rounded-lg border border-outline-variant px-3 py-2 text-xs text-on-surface"
            aria-pressed={motion} onClick={() => setMotion(value => !value)}>
            {motion ? 'Pause motion' : 'Enable motion'}
          </button>
          <button type="button" className="rounded-lg border border-outline-variant px-3 py-2 text-xs text-on-surface"
            onClick={() => setRefresh(value => value + 1)}>Refresh</button>
        </div>
      </header>
      <div className="flex flex-wrap items-center justify-between gap-2 rounded-xl border border-outline-variant bg-surface px-3 py-2">
        <section aria-label="Spatial view presets" className="flex flex-wrap items-center gap-2">
          <span className="mr-1 text-xs font-semibold text-on-surface-variant">Workspace</span>
          <button type="button" aria-pressed={diagnosticMode === 'investigate' && !activeFocus}
            onClick={() => { setFocused(null); setCompareWith(null); setDiagnosticMode('investigate'); }}
            className="rounded-lg border border-outline-variant px-3 py-1.5 text-xs text-on-surface">Overview</button>
          <button type="button" aria-pressed={Boolean(activeFocus) && diagnosticMode === 'investigate'}
            disabled={!scoped.length}
            onClick={() => {
              const target = selected && scoped.includes(selected) ? selected : scoped[0];
              if (target) { focusTopic(target); setDiagnosticMode('investigate'); }
            }}
            className="rounded-lg border border-outline-variant px-3 py-1.5 text-xs text-on-surface disabled:opacity-45">Focus selected</button>
          <button type="button" aria-pressed={diagnosticMode === 'compare'}
            onClick={() => { setFocused(null); setDiagnosticMode('compare'); }}
            className="rounded-lg border border-outline-variant px-3 py-1.5 text-xs text-on-surface">Compare</button>
          <span className="ml-2 mr-1 text-xs font-semibold text-on-surface-variant">Arrange</span>
          <div role="group" aria-label="Arrangement of the topics" className="inline-flex rounded-lg border border-outline-variant">
            <button type="button" aria-pressed={arrangement === 'spiral'} onClick={() => setArrangement('spiral')}
              title="Every topic keeps the slot it was first given"
              className="rounded-l-lg px-3 py-1.5 text-xs text-on-surface aria-pressed:bg-primary/10 aria-pressed:text-primary">Spiral</button>
            <button type="button" aria-pressed={arrangement === 'lanes'} onClick={() => setArrangement('lanes')}
              title="One lane per family of topics, its numbered stages left to right"
              className="rounded-r-lg border-l border-outline-variant px-3 py-1.5 text-xs text-on-surface aria-pressed:bg-primary/10 aria-pressed:text-primary">Pipeline lanes</button>
          </div>
        </section>
        <div className="flex flex-wrap items-center gap-2">
          <button type="button" aria-expanded={navigatorOpen}
            className="rounded-lg border border-outline-variant px-2.5 py-1.5 text-xs text-on-surface"
            onClick={() => setNavigatorOpen(value => !value)}>{navigatorOpen ? 'Hide topics' : 'Show topics'}</button>
          <button type="button" aria-expanded={inspectorOpen}
            className="rounded-lg border border-outline-variant px-2.5 py-1.5 text-xs text-on-surface"
            onClick={() => setInspectorOpen(value => !value)}>{inspectorOpen ? 'Hide inspector' : 'Show inspector'}</button>
          <button type="button" onClick={() => { void copyViewLink(); }}
            className="rounded-lg bg-primary-container px-3 py-1.5 text-xs font-medium text-on-primary-container">
            Copy view link
          </button>
          <button type="button" onClick={() => setShortcutsOpen(true)}
            className="rounded-lg border border-outline-variant px-3 py-1.5 text-xs text-on-surface"
            aria-label="Keyboard shortcuts and accessibility help">
            Shortcuts <kbd aria-hidden="true">?</kbd>
          </button>
        </div>
      </div>
      {(shareNotice || shareLink) && <div className="text-xs text-on-surface-variant" role="status">
        {shareNotice}
        {shareLink && <input aria-label="Shareable view URL" readOnly value={shareLink}
          onFocus={event => event.currentTarget.select()}
          className="mt-2 w-full rounded-lg border border-outline-variant bg-surface px-2 py-1 text-xs text-on-surface" />}
      </div>}
      {error && <p role="alert" className="rounded-lg bg-error-container p-3 text-on-error-container">{error}</p>}
      {activityError && <p role="alert" className="rounded-lg bg-error-container p-3 text-on-error-container">{activityError}</p>}
      {/* Not a live region: it changes on every 30-second poll and would be read out each time. */}
      <div className="flex flex-wrap gap-x-4 gap-y-1 text-xs text-on-surface-variant">
        <span>{topics.length}/{names.length} visible topics</span>
        <span>{measured} with readings · {incomplete} partial</span>
        <span>{links.length} declared lineage paths</span>
        {anomalies.length > 0 && <span className="text-amber-700 dark:text-amber-300">{anomalies.length} deviations in slice</span>}
        <span>{activitySnapshot?.scope === scope
          ? (activityError ? 'Last sample ' : 'Sampled ') + new Date(activitySnapshot.receivedAt).toLocaleTimeString()
          : activityError ? 'Activity unavailable' : 'Activity not measured yet'}</span>
      </div>
      <StrataPerformancePanel reading={performanceStats} />
      {names.length > MAX_TOPICS && <p className="text-xs text-on-surface-variant">
        The view is limited to {MAX_TOPICS} topics. Filter by name to reach the others.
      </p>}
      <div className="grid grid-cols-1 gap-3 2xl:grid-cols-[var(--strata-nav)_minmax(0,1fr)_var(--strata-inspector)]"
        style={{ '--strata-nav': navigatorOpen ? navigatorWidth + 'px' : '0px',
          '--strata-inspector': inspectorOpen ? inspectorWidth + 'px' : '0px' } as CSSProperties}>
        {navigatorOpen && <aside className="relative order-2 2xl:order-1 min-w-0 rounded-2xl border border-outline-variant bg-surface-container-low p-3 space-y-4 2xl:max-h-[min(75vh,760px)] 2xl:overflow-y-auto">
          <StrataResizeHandle side="left" value={navigatorWidth} onChange={setNavigatorWidth} />
          <div className="flex items-center justify-between">
            <h2 className="text-sm font-semibold text-on-surface">Topic navigator</h2>
            <span className="text-[11px] text-on-surface-variant">{topics.length} visible</span>
          </div>
          <label className="block space-y-1 text-xs text-on-surface-variant">
            <span>Filter topics</span>
            <input ref={topicFilterRef} aria-label="Filter topics" value={query} onChange={event => setQuery(event.target.value)}
              maxLength={160} placeholder="Topic name"
              className="w-full rounded-lg border border-outline-variant bg-surface px-3 py-2 text-sm text-on-surface" />
          </label>
          {families.length > 1 && families.some(entry => entry.count > 1) && <section aria-label="Topic families">
            <h3 className="mb-1.5 text-xs font-semibold text-on-surface">Families</h3>
            <p className="mb-2 text-[11px] text-on-surface-variant">
              Colour and socle shape follow the name prefix. Choose one to filter.
            </p>
            <div className="flex flex-wrap gap-1.5">
              {families.slice(0, 12).map(({ family, count }) => {
                // The filter matches names, so a family is filtered by the prefix its topics are written with.
                const prefix = names.find(name => topicFamily(name) === family)?.slice(0, family.length) ?? family;
                const active = query === prefix;
                return <button type="button" key={family} aria-pressed={active}
                  aria-label={`Filter on family ${family}, ${count} topic${count === 1 ? '' : 's'}`}
                  onClick={() => setQuery(active ? '' : prefix)}
                  className="inline-flex items-center gap-1.5 rounded-full border border-outline-variant px-2 py-0.5 text-[11px] text-on-surface aria-pressed:border-primary aria-pressed:bg-primary/10">
                  <span aria-hidden="true" className="inline-block h-2.5 w-2.5 rounded-full" style={{ background: familyCss(family) }} />
                  <span className="font-mono">{family}</span>
                  <span className="tabular-nums text-on-surface-variant">{count}</span>
                </button>;
              })}
            </div>
          </section>}
          <StrataMiniMap items={topics} totalTopics={layoutExtent} selected={selected} peer={activeCompareWith}
            leads={leads} onSelect={chooseTopic} onFocus={focusTopic} lanes={arrangement === 'lanes'} />
          <div>
            <h3 className="mb-2 text-xs font-semibold text-on-surface">Topics in view</h3>
            <p className="mb-2 text-[11px] text-on-surface-variant">
              <span className="text-emerald-600 dark:text-emerald-400">●</span> Complete ·
              <span className="text-amber-600 dark:text-amber-400"> ●</span> Partial ·
              <span className="text-sky-600 dark:text-sky-400"> ●</span> Stale ·
              <span className="text-on-surface-variant"> ●</span> Unavailable
            </p>
            <div className="max-h-72 space-y-1 overflow-y-auto" role="group" aria-label="Topics in view">
              {topics.map(topic => {
                const evidence = topicEvidence(topic.activity, selectedBucket,
                  activitySnapshot?.scope === scope ? activitySnapshot.receivedAt : null);
                return <button key={topic.name} type="button" title="Select; double-click to focus"
                  className={`flex w-full items-center gap-2 rounded-lg px-2.5 py-2 text-left text-xs ${selected === topic.name ?
                    'bg-primary-container text-on-primary-container' : 'text-on-surface hover:bg-surface-container-high'}`}
                  onClick={() => chooseTopic(topic.name)} onDoubleClick={() => focusTopic(topic.name)}
                  onKeyDown={event => { if (event.key === 'Enter' && event.shiftKey) {
                    event.preventDefault(); focusTopic(topic.name);
                  } }}>
                  <span aria-hidden="true" className={`h-2 w-2 shrink-0 rounded-full ${evidence.status === 'measured' ? 'bg-emerald-500' :
                    evidence.status === 'partial' ? 'bg-amber-500' :
                    evidence.status === 'stale' ? 'bg-sky-500' : 'bg-outline-variant'}`} />
                  <span className="min-w-0 flex-1 truncate">{topic.name}</span>
                  <span className="shrink-0 tabular-nums opacity-65">{evidence.selected?.toLocaleString() ?? '—'}</span>
                </button>;
              })}
              {topics.length === 0 && <p className="text-xs text-on-surface-variant">No matching topics.</p>}
            </div>
          </div>
        </aside>}
        <section aria-label="3D topic visualization" className="order-1 2xl:order-2 min-w-0">
          <div className="relative overflow-hidden rounded-2xl border border-outline-variant bg-surface-container-low"
            style={{ height: 'min(75vh, 760px)', minHeight: 510 }}>
            {topics.length > 0 ?
              <StrataCanvas topics={topics} selected={selected} compareWith={activeCompareWith}
                diagnosticMode={diagnosticMode} onSelect={chooseTopic} onFocus={focusTopic}
                focused={activeFocus} partitionActivity={alignedPartitionActivity} forecast={visibleForecast} motion={motion}
                quality={quality} layers={layers} onHover={updateHovered} onPerformance={setPerformanceStats}
                links={links} comparedLinks={comparedLinks} inferredLinks={inferredLinks} labelled={labelled} health={health} timeWindow={timeWindow}
                selectedGroup={selectedGroup} themeRevision={themeRevision}
                selectedBucket={selectedBucket} lensAvailable={Boolean(activity?.available)} trace={activeTrace}
                frameKey={filteredQuery + '|' + arrangement} /> :
              <p className="p-8 text-sm text-on-surface-variant">{dashboard ? 'No matching topics.' : 'Loading cluster…'}</p>}
            {hovered && hoveredTopic && <StrataHoverCard name={hoveredTopic.name} activity={hoveredTopic.activity}
              bucket={selectedBucket} receivedAt={activitySnapshot?.scope === scope ? activitySnapshot.receivedAt : null}
              x={hovered.x} y={hovered.y} />}
            <div className="absolute right-3 top-3 z-20"><StrataLayerMenu layers={layers} onChange={setLayer} /></div>
            <div className="pointer-events-none absolute left-3 top-3 max-w-[60%] space-y-1">
              <p className="rounded-lg bg-surface/85 px-2.5 py-1.5 text-[11px] text-on-surface-variant backdrop-blur-md">
                {touch ? 'One finger scrolls the page · Two fingers orbit and zoom · Double-tap to focus'
                  : 'Drag to orbit · Scroll to zoom · Double-click to focus'}
              </p>
              {layers.activity && <p data-testid="strata-ring-legend"
                className="rounded-lg bg-surface/85 px-2.5 py-1.5 text-[11px] text-on-surface-variant backdrop-blur-md">
                Ring width: offsets produced per {bucketLabel}, log scale. Widest ring ={' '}
                <strong className="text-on-surface tabular-nums">{ringScaleMax.toLocaleString()}</strong> offsets
                (largest among the visible topics; filtering changes it). Thin grey ring: not measured.
              </p>}
            </div>
            <div className="absolute bottom-3 left-3 right-3 z-10">
              <StrataTimelineDock snapshot={activity} topics={topics} selectedBucket={selectedBucket}
                onBucket={selectBucket} replaying={replaying}
                onReplay={() => {
                  if (replaying) setReplaying(false);
                  else { setSelectedBucket(0); setReplaying(true); }
                }}
                replaySpeed={replaySpeed} onSpeed={setReplaySpeed}
                onLatest={() => { setReplaying(false); setSelectedBucket(BUCKETS - 1); }} />
            </div>
          </div>
        </section>
        {inspectorOpen && <aside className="relative order-3 min-w-0 rounded-2xl border border-outline-variant bg-surface-container-low p-3 space-y-3 2xl:max-h-[min(75vh,760px)] 2xl:overflow-y-auto"
          aria-label="Topic inspector and comparison">
          <StrataResizeHandle side="right" value={inspectorWidth} onChange={setInspectorWidth} />
          <div className="flex items-center justify-between gap-2">
            <h2 className="text-sm font-semibold text-on-surface">Diagnostic inspector</h2>
            <div role="group" aria-label="Diagnostic mode" className="flex rounded-lg border border-outline-variant bg-surface p-0.5">
              <button type="button" aria-pressed={diagnosticMode === 'investigate'}
                className={`rounded-md px-2 py-1 text-xs ${diagnosticMode === 'investigate' ? 'bg-primary-container text-on-primary-container' : 'text-on-surface-variant'}`}
                onClick={() => setDiagnosticMode('investigate')}>Investigate</button>
              <button type="button" aria-pressed={diagnosticMode === 'compare'}
                className={`rounded-md px-2 py-1 text-xs ${diagnosticMode === 'compare' ? 'bg-primary-container text-on-primary-container' : 'text-on-surface-variant'}`}
                onClick={() => setDiagnosticMode('compare')}>Compare</button>
            </div>
          </div>
          <StrataDiagnosticPanel mode={diagnosticMode} topic={selectedTopic?.name ?? null}
            compareWith={activeCompareWith} topics={scoped} snapshot={activity} links={links}
            trace={activeTrace} selectedBucket={selectedBucket} selectedGroup={selectedGroup}
            onChooseTopic={chooseTopic} onCompareWith={setCompareWith} />
          {diagnosticMode === 'investigate' && <StrataInvestigationPanel inspection={inspection} leads={leads}
            onChooseTopic={chooseTopic} onFocus={() => { if (selectedTopic) focusTopic(selectedTopic.name); }}
            onEvidence={() => document.getElementById('strata-evidence-details')?.scrollIntoView({
              behavior: motion ? 'smooth' : 'auto', block: 'start',
            })}
            onSnapshot={saveInvestigation} />}
        {/* A section, not a nested <aside>; and not a live region, since it holds the consumer
            panel and polled figures that a screen reader would otherwise announce every 30 s. */}
        <section className="rounded-xl border border-outline-variant bg-surface p-4" aria-labelledby="strata-topic-inspector">
          <h2 id="strata-topic-inspector" className="font-semibold text-on-surface mb-3">Topic inspector</h2>
          {selectedTopic ? <>
            <p className="break-all font-medium text-on-surface">{selectedTopic.name}</p>
            <button type="button" onClick={() => activeFocus === selectedTopic.name ? setFocused(null) : focusTopic(selectedTopic.name)}
              className="mt-2 rounded-lg border border-outline-variant px-3 py-1.5 text-xs text-on-surface">
              {activeFocus === selectedTopic.name ? 'Return to overview' : 'Focus partitions and forecast'}
            </button>
            <p className="mt-3 text-xs text-on-surface-variant">
              Evidence: <strong className="text-on-surface capitalize">{selectedEvidence.status}</strong> ·
              {selectedEvidence.measuredBuckets}/{selectedEvidence.totalBuckets} complete intervals
            </p>
            <dl className="text-sm text-on-surface-variant mt-3 space-y-2">
              <div className="flex justify-between gap-2"><dt>Estimated retained offsets</dt>
                <dd className="tabular-nums text-on-surface">{selectedTopic.size?.toLocaleString() ?? 'Unknown'}</dd></div>
              <div className="flex justify-between gap-2"><dt>Selected time slice</dt>
                <dd className="tabular-nums text-on-surface">{bucketReading(selectedTopic.activity, selectedBucket)?.toLocaleString() ?? 'Unknown'}</dd></div>
              <div className="flex justify-between gap-2"><dt>30-minute produced offsets</dt>
                <dd className="tabular-nums text-on-surface">{selectedEvidence.total?.toLocaleString() ?? 'Unknown (partial or missing)'}</dd></div>
              <div className="flex justify-between gap-2"><dt>Latest bucket (offsets/min)</dt>
                <dd className="tabular-nums text-on-surface">{selectedEvidence.latestRate === null ? 'Unknown' : Math.round(selectedEvidence.latestRate).toLocaleString()}</dd></div>
              <div className="flex justify-between gap-2"><dt>Partitions measured</dt>
                <dd className="text-on-surface">{selectedTopic.activity?.available ?
                  `${selectedTopic.activity.partitionsMeasured}/${selectedTopic.activity.partitionsTotal}` : 'Unknown'}</dd></div>
            </dl>
            {selectedTopic.activity?.note && <p className="mt-2 text-xs text-on-surface-variant">{selectedTopic.activity.note}</p>}
            {selectedTopic.activity?.coveredFromMs !== null && selectedTopic.activity?.available &&
              <p className="mt-2 text-xs text-on-surface-variant">Historical activity before retention coverage is a lower bound.</p>}
            <StrataConsumerPanel key={selectedTopic.name} topic={selectedTopic.name} onGroupSelect={onGroupSelect} />
            <Link className="mt-4 inline-block text-sm font-medium text-primary hover:underline"
              to={`/topic/${encodeURIComponent(selectedTopic.name)}`}>Open Topic Explorer →</Link>
          </> : <p className="text-sm text-on-surface-variant">Select a column or topic to see consumer and partition lag.</p>}
        </section>
        </aside>}
      </div>
      <section id="strata-evidence-details" className="space-y-4 rounded-2xl border border-outline-variant bg-surface-container-low p-3 sm:p-5"
        aria-label="Detailed evidence and traces">
        <div>
          <h2 className="text-base font-semibold text-on-surface">Evidence details</h2>
          <p className="text-xs text-on-surface-variant">
            Partition measurements, forecast and operator-initiated key tracing. These operations are read-only.
          </p>
        </div>
      {activeFocus && <section className="rounded-xl border border-outline-variant bg-surface p-4 space-y-2">
        <div className="flex flex-wrap items-start justify-between gap-3">
          <div>
            <h2 className="font-semibold text-on-surface">Focused exploration · {activeFocus}</h2>
            <p className="text-xs text-on-surface-variant">
              Radial ribbons show measured offsets produced per partition and per 2.5-minute bucket.
              Width follows each partition's own measured rate; missing or retention-truncated buckets have no ribbon.
            </p>
          </div>
          <button type="button" onClick={() => setFocused(null)}
            className="rounded-lg border border-outline-variant px-3 py-2 text-xs text-on-surface">
            Return to overview
          </button>
        </div>
        {partitionError && <p role="alert" className="text-xs text-error">{partitionError}</p>}
        {!partitionActivity && !partitionError && <p className="text-xs text-on-surface-variant">Measuring partition activity…</p>}
        {partitionActivity && !alignedPartitionActivity && <p role="status" className="text-xs text-amber-700 dark:text-amber-300">
          Partition sample and cluster time lens use different windows; 3D ribbons are hidden until both align.
        </p>}
        {partitionActivity && <>
          <p className="text-xs text-on-surface-variant" role="status">
            {partitionActivity.partitionsMeasured}/{partitionActivity.partitionsTotal} partitions measured
            {partitionActivity.truncated ? ' · bounded scope, not all partitions shown' : ''}
          </p>
          {partitionActivity.warnings.length > 0 && <p className="text-xs text-on-surface-variant">{partitionActivity.warnings.join(' ')}</p>}
          <ul className="grid gap-1 sm:grid-cols-3 lg:grid-cols-6 max-h-36 overflow-auto">
            {partitionActivity.partitions.map(partition =>
              <li key={partition.partition} className="rounded-lg bg-surface-container-low px-2 py-1.5 text-xs text-on-surface">
                <span className="font-semibold">P{partition.partition}</span>
                <span className="block text-on-surface-variant">
                  {partition.available && partition.coveredFromMs === null &&
                    partition.counts.every(n => Number.isFinite(n) && n >= 0)
                    ? partition.counts.reduce((sum, n) => sum + n, 0).toLocaleString() + ' offsets / 30 min'
                    : 'Partial or unavailable'}
                  {partition.coveredFromMs !== null ? ' · partial retention' : ''}
                </span>
              </li>)}
          </ul>
        </>}
      </section>}

      {activeFocus && <StrataForecastPanel key={activeFocus} topic={activeFocus} onProjection={onProjection} />}
      <StrataTracePanel key={scope} topics={scoped} onTrace={onTrace} onSelect={chooseTopic} />
      {anomalies.length > 0 && <section className="rounded-xl border border-amber-400/40 bg-surface p-4 space-y-2">
        <h2 className="text-sm font-semibold text-on-surface">Unusual activity in this time slice</h2>
        <p className="text-xs text-on-surface-variant">
          Candidates only: relative to each topic's four preceding complete buckets, not a broker alert.
        </p>
        <ul className="grid gap-1 sm:grid-cols-2">
          {anomalies.map(item => <li key={item.name}>
            <button onClick={() => chooseTopic(item.name)}
              className="w-full text-left px-2 py-1.5 rounded-lg hover:bg-surface-container-high text-sm text-on-surface">
              <span className="font-medium">{item.name}</span>
              <span className="text-xs text-on-surface-variant block">
                {item.kind === 'spike' ? 'Spike' : 'Drop'} · {item.value.toLocaleString()} offsets / bucket
                · baseline {Math.round(item.baseline).toLocaleString()}
              </span>
            </button>
          </li>)}
        </ul>
      </section>}
      {stalledGroup && <p role="status" className="rounded-xl border border-error/40 bg-surface p-3 text-sm text-on-surface">
        Group <strong>{stalledGroup.groupId}</strong> is stalled on the selected topic:
        backlog exists but no members are assigned. This is the selected consumer group, not a cluster-wide conclusion.
      </p>}
      {activity?.warnings?.length ? <p className="text-xs text-on-surface-variant" role="status">{activity.warnings.join(' ')}</p> : null}
      {activity && !activity.available && <p className="text-sm text-on-surface-variant">Activity unavailable; neutral rings indicate unknown readings, not zero traffic.</p>}
      {lineageError && <p role="status" className="text-xs text-on-surface-variant">{lineageError}</p>}
      {lineage?.warnings?.length ? <p role="status" className="text-xs text-on-surface-variant">
        Lineage has inferred or incomplete dependencies: {lineage.warnings.join(' ')}
      </p> : null}

      </section>
      {shortcutsOpen && <StrataShortcutDialog onClose={closeShortcuts} />}
    </div>
  );
}
