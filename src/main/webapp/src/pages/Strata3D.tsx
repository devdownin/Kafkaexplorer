// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors

import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import type { ConsumerGroupLag, DashboardResponse, PartitionActivityResponse, TopicActivity, TopicActivityResponse } from '../api/types';
import StrataConsumerPanel from './StrataConsumerPanel';
import StrataTracePanel from './StrataTracePanel';
import StrataForecastPanel from './StrataForecastPanel';
import StrataDiagnosticPanel, { type DiagnosticMode } from './StrataDiagnosticPanel';
import { createForecastCanopy, createPartitionRibbons } from './strataImmersive';
import type { StrataProjection } from './strataForecast';
import { consumerHealth } from './StrataConsumerPanel';
import { bucketInterval, bucketReading, detectActivityAnomaly } from './strataTime';
import type { ParsedFlow } from './streamFlowLogic';
import { deriveStrataLinks, type StrataLineage, type StrataLink } from './strataLineage';
import { Link } from 'react-router-dom';
import { readStrataView, strataRenderBudget, writeStrataView, type StrataQuality } from './strataView';
import { copyText } from '../clipboard';
import axios from 'axios';
import * as THREE from 'three';
import { OrbitControls } from 'three/addons/controls/OrbitControls.js';

type Dashboard = DashboardResponse;
type Activity = TopicActivity;
type ActivityResponse = TopicActivityResponse;
type Topic = { name: string; size: number; activity?: Activity; layoutIndex: number };
const MAX_TOPICS = 48;
const BUCKETS = 12;
const WINDOW_MS = 30 * 60 * 1000;

function position(index: number): THREE.Vector3 {
  // Deterministic sunflower placement; refreshing metrics never moves a topic.
  const radius = 3.4 * Math.sqrt(index);
  const angle = index * 2.399963229728653;
  return new THREE.Vector3(Math.cos(angle) * radius, 0, Math.sin(angle) * radius);
}

function sceneFrame(topics: Topic[]): { center: THREE.Vector3; extent: number } {
  if (!topics.length) return { center: new THREE.Vector3(0, 2.6, 0), extent: 9 };
  const positions = topics.map(topic => position(topic.layoutIndex));
  const minX = Math.min(...positions.map(p => p.x));
  const maxX = Math.max(...positions.map(p => p.x));
  const minZ = Math.min(...positions.map(p => p.z));
  const maxZ = Math.max(...positions.map(p => p.z));
  return {
    center: new THREE.Vector3((minX + maxX) / 2, 2.6, (minZ + maxZ) / 2),
    extent: Math.max(9, Math.max(maxX - minX, maxZ - minZ) * 0.65 + 5.5),
  };
}

function disposeGroup(group: THREE.Group) {
  group.traverse(object => {
    if (object instanceof THREE.Sprite) {
      object.material.map?.dispose();
      object.material.dispose();
    }
    if (!(object instanceof THREE.Mesh || object instanceof THREE.Line)) return;
    object.geometry.dispose();
    const materials = Array.isArray(object.material) ? object.material : [object.material];
    materials.forEach(material => material.dispose());
  });
}

/** One billboard per topic, readable from every orbit angle. Canvas textures never leave the browser. */
function topicLabel(name: string, dark: boolean): THREE.Sprite | null {
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
  context.font = '600 28px system-ui, sans-serif';
  context.fillStyle = dark ? '#F0F6FE' : '#1D334B';
  context.textAlign = 'center';
  context.textBaseline = 'middle';
  const label = name.length > 24 ? name.slice(0, 23) + '…' : name;
  context.fillText(label, 192, 36, 362);
  const texture = new THREE.CanvasTexture(canvas);
  texture.colorSpace = THREE.SRGBColorSpace;
  const sprite = new THREE.Sprite(new THREE.SpriteMaterial({
    map: texture, transparent: true, depthTest: false, depthWrite: false,
  }));
  sprite.scale.set(3.2, 0.6, 1);
  sprite.position.y = 6.44;
  return sprite;
}

function createTopic(topic: Topic, index: number, maxRate: number, dark: boolean,
  highlight: 'primary' | 'comparison' | null, ringSegments: number): THREE.Group {
  const group = new THREE.Group();
  group.position.copy(position(topic.layoutIndex));
  group.userData.topic = topic.name;
  const counts = Array.from({ length: BUCKETS }, (_, bucket) => bucketReading(topic.activity, bucket));
  const hasData = counts.some(value => value !== null);
  const hue = (index * 0.137508) % 1;
  const color = new THREE.Color().setHSL(hue * 0.25 + 0.48, dark ? 0.7 : 0.64, dark ? 0.68 : 0.45);
  const base = new THREE.Mesh(
    new THREE.CylinderGeometry(0.96, 0.96, 0.10, 40),
    new THREE.MeshBasicMaterial({ color, transparent: true, opacity: 0.22, depthWrite: false }),
  );
  base.position.y = 0.05;
  group.add(base);
  const height = 5.6;
  const shell = new THREE.Mesh(
    new THREE.CylinderGeometry(0.86, 0.86, height, 40, 1, true),
    new THREE.MeshBasicMaterial({ color, transparent: true, opacity: hasData ? 0.055 : 0.025, side: THREE.DoubleSide, depthWrite: false }),
  );
  shell.position.y = height / 2 + 0.12;
  group.add(shell);
  for (let i = 0; i < BUCKETS; i++) {
    const value = counts[i];
    const measured = value !== null;
    const normalized = measured ? Math.log1p(value) / Math.log1p(Math.max(1, maxRate)) : 0;
    const radius = measured ? 0.38 + 0.56 * normalized : 0.43;
    const ring = new THREE.Mesh(
      new THREE.TorusGeometry(radius, measured ? 0.025 + normalized * 0.035 : 0.014, 6, ringSegments),
      new THREE.MeshBasicMaterial({ color: measured ? color : 0x8b9aaa, transparent: true, opacity: measured ? 0.4 + normalized * 0.48 : 0.22, depthWrite: false }),
    );
    ring.rotation.x = Math.PI / 2;
    ring.position.y = 0.35 + i * 0.43;
    ring.userData.bucketIndex = i;
    ring.userData.baseOpacity = (ring.material as THREE.MeshBasicMaterial).opacity;
    ring.userData.baseColor = (ring.material as THREE.MeshBasicMaterial).color.clone();
    group.add(ring);
  }
  const crown = new THREE.Mesh(
    new THREE.TorusGeometry(0.89, 0.028, 8, 48),
    new THREE.MeshBasicMaterial({ color, transparent: true, opacity: 0.8 }),
  );
  crown.rotation.x = Math.PI / 2;
  crown.position.y = height + 0.12;
  group.add(crown);
  // A real activity series is required before rendering any moving activity indicator.
  if (hasData && counts.some(n => n !== null && n > 0)) {
    const glow = new THREE.Mesh(
      new THREE.SphereGeometry(0.10, 10, 8),
      new THREE.MeshBasicMaterial({ color, transparent: true, opacity: 0.95 }),
    );
    glow.userData.pulse = index * 0.41;
    glow.position.set(0, 0.3, 0);
    group.add(glow);
  }
  if (highlight) {
    const accent = highlight === 'primary' ? 0x0c9da9 : 0x9b76db;
    for (const y of [0.12, height + 0.13]) {
      const halo = new THREE.Mesh(
        new THREE.TorusGeometry(1.15, 0.065, 8, 54),
        new THREE.MeshBasicMaterial({ color: accent, transparent: true, opacity: 0.93, depthWrite: false }),
      );
      halo.rotation.x = Math.PI / 2;
      halo.position.y = y;
      group.add(halo);
    }
  }
  const label = topicLabel(topic.name, dark);
  if (label) group.add(label);
  return group;
}

function StrataCanvas({ topics, selected, compareWith, diagnosticMode, onSelect, onFocus,
  focused, partitionActivity, forecast,
  motion, quality, links, selectedGroup, themeRevision, selectedBucket, trace, lensAvailable }: {
  topics: Topic[];
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
  selectedGroup: ConsumerGroupLag | null;
  themeRevision: number;
  selectedBucket: number;
  trace: ParsedFlow | null;
  lensAvailable: boolean;
}) {
  const host = useRef<HTMLDivElement>(null);
  const onSelectRef = useRef(onSelect);
  const onFocusRef = useRef(onFocus);
  const motionRef = useRef(motion);
  const budget = useMemo(() => strataRenderBudget(quality, topics.length, window.devicePixelRatio || 1), [quality, topics.length]);
  const budgetRef = useRef(budget);
  const [webglFailed, setWebglFailed] = useState(false);
  useEffect(() => { onSelectRef.current = onSelect; }, [onSelect]);
  useEffect(() => { onFocusRef.current = onFocus; }, [onFocus]);
  const state = useRef<{
    scene: THREE.Scene;
    camera: THREE.OrthographicCamera;
    renderer: THREE.WebGLRenderer;
    controls: OrbitControls;
    content: THREE.Group;
    groups: THREE.Group[];
    lens: THREE.Mesh;
    signature: string;
    invalidate: () => void;
    fit: (items: Topic[]) => void;
    focus: (topic: string | null) => void;
    positions: Map<string, THREE.Vector3>;
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
    lens.position.y = 0.35 + 11 * 0.43;
    scene.add(lens);
    const ground = new THREE.GridHelper(Math.max(40, extent * 2.8), 24, 0x7794ab, 0xb1c1cf);
    ground.position.set(initialFrame.center.x, -0.12, initialFrame.center.z);
    scene.add(ground);
    const current = {
      scene, camera, renderer, controls, content: contentGroup,
      groups: [] as THREE.Group[], lens, signature: topics.map(t => t.name + ':' + t.layoutIndex).join('\\u0000'),
      positions: new Map<string, THREE.Vector3>(),
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
    const onPointerUp = (event: PointerEvent) => {
      if (!down || Math.hypot(down.x - event.clientX, down.y - event.clientY) > 5) return;
      const topic = pickedTopic(event);
      if (topic) onSelectRef.current(topic);
    };
    const onDoubleClick = (event: MouseEvent) => {
      const topic = pickedTopic(event);
      if (topic) { onSelectRef.current(topic); onFocusRef.current(topic); }
    };
    renderer.domElement.addEventListener('dblclick', onDoubleClick);
    renderer.domElement.addEventListener('pointerdown', onPointerDown);
    renderer.domElement.addEventListener('pointerup', onPointerUp);
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
    const requestRender = () => {
      if (!contextLost && !frame && document.visibilityState === 'visible') frame = requestAnimationFrame(render);
    };
    const render = (time: number) => {
      frame = 0;
      const moving = motionRef.current && current.groups.some(group =>
        group.children.some(object => object.userData.pulse !== undefined));
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
      renderer.render(scene, camera);
      if (transition || moving || cameraChanged) requestRender();
    };
    current.invalidate = requestRender;
    const onOrbitChange = () => requestRender();
    controls.addEventListener('change', onOrbitChange);
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
    };
    const onContextRestored = () => { contextLost = false; setWebglFailed(false); requestRender(); };
    renderer.domElement.addEventListener('webglcontextlost', onContextLost);
    renderer.domElement.addEventListener('webglcontextrestored', onContextRestored);
    const observer = new ResizeObserver(resize);
    observer.observe(element);
    resize();
    requestRender();
    return () => {
      cancelAnimationFrame(frame);
      observer.disconnect();
      document.removeEventListener('visibilitychange', onVisibilityChange);
      controls.removeEventListener('change', onOrbitChange);
      renderer.domElement.removeEventListener('webglcontextlost', onContextLost);
      renderer.domElement.removeEventListener('webglcontextrestored', onContextRestored);
      renderer.domElement.removeEventListener('dblclick', onDoubleClick);
      renderer.domElement.removeEventListener('pointerdown', onPointerDown);
      renderer.domElement.removeEventListener('pointerup', onPointerUp);
      controls.dispose();
      disposeGroup(contentGroup);
      lens.geometry.dispose();
      (lens.material as THREE.Material).dispose();
      ground.geometry.dispose();
      (ground.material as THREE.Material).dispose();
      renderer.dispose();
      renderer.domElement.remove();
      state.current = null;
    };
  // Camera lifetime is independent of metric polling: never reset orbit on refresh.
  // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  useEffect(() => {
    const current = state.current;
    if (!current) return;
    const signature = topics.map(t => t.name + ':' + t.layoutIndex).join('\\u0000');
    if (signature !== current.signature) {
      current.signature = signature;
      current.fit(topics);
    }
    // Replace measured geometry without tearing down WebGL, orbit or pointer handlers.
    disposeGroup(current.content);
    current.content.clear();
    const dark = document.documentElement.classList.contains('dark') ||
      document.documentElement.getAttribute('data-theme') === 'dark';
    (current.lens.material as THREE.MeshBasicMaterial).color.setHex(dark ? 0x83b9d5 : 0x5888af);
    const values = topics.flatMap(t => Array.from({ length: BUCKETS }, (_, bucket) => bucketReading(t.activity, bucket))
      .filter((n): n is number => n !== null));
    const maxRate = Math.max(1, ...values);
    current.positions = new Map(topics.map(topic => [topic.name, position(topic.layoutIndex)]));
    current.groups = topics.map((topic, index) => {
      const highlight = topic.name === selected ? 'primary' :
        diagnosticMode === 'compare' && topic.name === compareWith ? 'comparison' : null;
      const column = createTopic(topic, index, maxRate, dark, highlight, budget.ringSegments);
      const isNeighbor = diagnosticMode === 'investigate' && links.some(link =>
        (link.from === selected && link.to === topic.name) ||
        (link.to === selected && link.from === topic.name));
      const dimmed = Boolean(focused && topic.name !== focused && topic.name !== compareWith && !isNeighbor);
      column.userData.dimFactor = dimmed ? 0.22 : 1;
      if (dimmed) {
        // The focus context stays in sight but must not compete with the inspected topic.
        column.traverse(object => {
          if (!(object instanceof THREE.Mesh || object instanceof THREE.Sprite)) return;
          const materials = Array.isArray(object.material) ? object.material : [object.material];
          for (const material of materials) {
            if (material instanceof THREE.MeshBasicMaterial || material instanceof THREE.SpriteMaterial) {
              material.setValues({ opacity: material.opacity * 0.22, transparent: true });
            }
          }
        });
      }
      current.content.add(column);
      return column;
    });
    const lookup = current.positions;
    for (const link of links) {
      const start = lookup.get(link.from);
      const end = lookup.get(link.to);
      if (!start || !end) continue;
      const span = start.distanceTo(end);
      const curve = new THREE.CubicBezierCurve3(
        new THREE.Vector3(start.x, 5.6, start.z),
        new THREE.Vector3(start.x, 6.5 + span * 0.12, start.z),
        new THREE.Vector3(end.x, 6.5 + span * 0.12, end.z),
        new THREE.Vector3(end.x, 5.6, end.z),
      );
      const geometry = new THREE.BufferGeometry().setFromPoints(curve.getPoints(40));
      const neighborhood = link.from === selected || link.to === selected;
      const line = new THREE.Line(geometry, new THREE.LineDashedMaterial({
        color: neighborhood ? (dark ? 0x48c6cb : 0x118b9c) : (dark ? 0x9db6db : 0x6d7fa2),
        transparent: true, opacity: diagnosticMode === 'investigate' && selected
          ? (neighborhood ? 0.92 : 0.12) : 0.58,
        dashSize: 0.38, gapSize: 0.24,
      }));
      line.computeLineDistances();
      current.content.add(line);
    }

    // Chronological key matches are distinct from dashed Flink lineage. The links
    // express event order, not a guaranteed causal transfer.
    if (trace) {
      const visibleHits = trace.hits.filter(hit => lookup.has(hit.topic)).slice(0, 48);
      for (let index = 0; index < visibleHits.length; index++) {
        const start = lookup.get(visibleHits[index].topic)!;
        const point = new THREE.Mesh(
          new THREE.SphereGeometry(0.17, 14, 10),
          new THREE.MeshBasicMaterial({ color: 0x1bafbd }),
        );
        point.position.set(start.x, 6.18, start.z);
        current.content.add(point);
        if (index === 0) continue;
        const end = lookup.get(visibleHits[index - 1].topic)!;
        const radius = start.distanceTo(end);
        const curve = new THREE.CubicBezierCurve3(
          new THREE.Vector3(end.x, 6.18, end.z),
          new THREE.Vector3(end.x, 7.3 + radius * 0.09, end.z),
          new THREE.Vector3(start.x, 7.3 + radius * 0.09, start.z),
          new THREE.Vector3(start.x, 6.18, start.z),
        );
        const beam = new THREE.Mesh(
          new THREE.TubeGeometry(curve, 24, 0.04, 6, false),
          new THREE.MeshBasicMaterial({ color: 0x16afbd, transparent: true, opacity: 0.88 }),
        );
        current.content.add(beam);
      }
    }

    const selectedIndex = topics.findIndex(t => t.name === selected);
    if (selectedIndex >= 0 && selectedGroup) {
      const point = position(topics[selectedIndex].layoutIndex);
      const partitions = selectedGroup.partitions.slice(0, 64);
      const healthColor = selectedGroup.error ? 0x8592a1 : selectedGroup.totalLag > 0 && selectedGroup.membersKnown && selectedGroup.assignedMembers === 0
        ? 0xdd5b59 : 0xe4a757;
      for (let index = 0; index < partitions.length; index++) {
        const partition = partitions[index];
        const angle = index * Math.PI * 2 / Math.max(1, partitions.length);
        const color = partition.lag === null ? 0x8d9ba8
          : partition.lag < 0 ? 0xd95b64 : partition.lag === 0 ? 0x4aaf99 : healthColor;
        const marker = new THREE.Mesh(
          new THREE.SphereGeometry(0.12, 10, 8),
          new THREE.MeshBasicMaterial({ color }),
        );
        marker.position.set(point.x + Math.cos(angle) * 1.45, 0.42, point.z + Math.sin(angle) * 1.45);
        current.content.add(marker);
      }
    }
    if (focused && partitionActivity?.topic === focused && partitionActivity.partitionsMeasured > 0) {
      const center = lookup.get(focused);
      if (center) current.content.add(createPartitionRibbons(partitionActivity, center, dark));
    }
    if (forecast && focused === forecast.topic) {
      const center = lookup.get(forecast.topic);
      if (center) current.content.add(createForecastCanopy(forecast, center, dark));
    }
    current.invalidate();
  }, [topics, links, selectedGroup, selected, compareWith, diagnosticMode, focused, partitionActivity, forecast, themeRevision, trace, budget.ringSegments]);

  const scopeKey = topics.map(t => t.name + ':' + t.layoutIndex).join('\\u0000');
  useEffect(() => { state.current?.focus(focused); }, [focused, scopeKey]);

  useEffect(() => {
    const groups = state.current?.groups;
    if (!groups) return;
    for (const group of groups) group.scale.setScalar(group.userData.topic === selected ? 1.04 : 1);
    state.current?.invalidate();
  }, [selected, topics]);

  useEffect(() => {
    const current = state.current;
    if (!current) return;
    current.lens.position.y = 0.35 + selectedBucket * 0.43;
    current.lens.visible = lensAvailable;
    const measurements = new Map(topics.map(topic => [topic.name, topic.activity]));
    for (const group of current.groups) {
      const anomaly = detectActivityAnomaly(measurements.get(group.userData.topic as string), selectedBucket);
      for (const object of group.children) {
        if (!(object instanceof THREE.Mesh) || object.userData.bucketIndex === undefined) continue;
        const material = object.material as THREE.MeshBasicMaterial;
        const active = object.userData.bucketIndex === selectedBucket;
        material.setValues({ opacity: Math.min(1,
          (object.userData.baseOpacity as number) * (active ? 1.4 : 0.42) * (group.userData.dimFactor as number ?? 1)) });
        material.color.copy(object.userData.baseColor as THREE.Color);
        if (active && anomaly) material.color.setHex(0xe7a044);
      }
    }
    current.invalidate();
  }, [selectedBucket, topics, links, trace, selectedGroup, selected, compareWith, diagnosticMode, focused, themeRevision, lensAvailable]);

  return (
    <div ref={host} role="img"
      aria-label="Interactive Kafka topic activity. Drag to orbit, scroll to zoom, double-click to focus. Use the topic list for keyboard access."
      className="w-full h-full min-h-[380px] cursor-grab active:cursor-grabbing">
      {webglFailed && <p role="status" className="p-6 text-sm text-on-surface">3D rendering unavailable or context lost. Use the accessible topic list below.</p>}
    </div>
  );
}

export default function Strata3D() {
  const [initialView] = useState(() => readStrataView(new URLSearchParams(window.location.search)));
  const [dashboard, setDashboard] = useState<Dashboard | null>(null);
  const [activitySnapshot, setActivitySnapshot] = useState<{
    scope: string; data: ActivityResponse; receivedAt: number;
  } | null>(null);
  const [lineage, setLineage] = useState<StrataLineage | null>(null);
  const [lineageError, setLineageError] = useState<string | null>(null);
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
  const [motion, setMotion] = useState(() => !window.matchMedia('(prefers-reduced-motion: reduce)').matches);
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

  // Share only the operator's view choices; never put Kafka keys, trace records
  // or forecast payloads in URLs. Replacements avoid polluting browser history.
  useEffect(() => {
    const existing = new URLSearchParams(window.location.search);
    const next = writeStrataView(existing, {
      mode: diagnosticMode, topic: selected, peer: compareWith, focus: focused,
      filter: query, bucket: selectedBucket, quality,
    });
    if (existing.toString() !== next.toString()) {
      const suffix = next.toString();
      window.history.replaceState(window.history.state, '',
        window.location.pathname + (suffix ? '?' + suffix : '') + window.location.hash);
    }
  }, [diagnosticMode, selected, compareWith, focused, query, selectedBucket, quality]);

  const copyViewLink = async () => {
    const url = new URL(window.location.href);
    // Sharing deliberately drops unrelated query parameters (which may be sensitive).
    url.search = writeStrataView(new URLSearchParams(), {
      mode: diagnosticMode, topic: selected, peer: compareWith, focus: focused,
      filter: query, bucket: selectedBucket, quality,
    }).toString();
    const link = url.toString();
    const copied = await copyText(link);
    setShareLink(copied ? null : link);
    setShareNotice(copied
      ? 'View link copied. It contains no traced message keys.'
      : 'Copy this link manually. It contains no traced message keys.');
  };

  const onGroupSelect = useCallback((group: ConsumerGroupLag | null) => setSelectedGroup(group), []);
  const onProjection = useCallback((projection: StrataProjection | null) => setForecastOverlay(projection), []);
  const chooseTopic = useCallback((name: string) => {
    if (selected !== name) setSelectedGroup(null);
    if (compareWith === name) setCompareWith(null);
    setSelected(name);
  }, [selected, compareWith]);
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
  const layoutIndices = useMemo(() => new Map(names.map((name, index) => [name, index])), [names]);
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
        const { data } = await axios.get<ActivityResponse>('/api/dashboard/activity', {
          params: { topics: scope, windowMs: WINDOW_MS, buckets: BUCKETS },
          timeout: 20000, signal: controller.signal,
        });
        if (active) setActivitySnapshot({ scope, data, receivedAt: Date.now() });
      } catch (failure) {
        if (active && !axios.isCancel(failure)) {
          setActivitySnapshot(previous => previous?.scope === scope ? null : previous);
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
  const activeTrace = traced?.scope === scope ? traced.flow : null;
  const onTrace = useCallback((flow: ParsedFlow | null) => {
    setTraced(flow ? { scope, flow } : null);
  }, [scope]);
  const topics = useMemo(() => scoped.map(name => ({
    name, size: dashboard?.topicSizes[name] ?? 0, activity: activity?.topics[name],
    layoutIndex: layoutIndices.get(name) ?? 0,
  })), [scoped, dashboard, activity, layoutIndices]);
  const links = useMemo(() => lineage ? deriveStrataLinks(lineage, scoped) : [], [lineage, scoped]);
  const selectedTopic = topics.find(t => t.name === selected);
  const activeCompareWith = diagnosticMode === 'compare' && compareWith !== selected &&
    topics.some(t => t.name === compareWith) ? compareWith : null;
  const measured = topics.filter(t => t.activity?.available).length;
  const incomplete = topics.filter(t => t.activity?.available && (
    t.activity.partitionsMeasured < t.activity.partitionsTotal || t.activity.coveredFromMs !== null
  )).length;
  const interval = activity ? bucketInterval({
    startMs: activity.windowStartMs, endMs: activity.windowEndMs,
    bucketMs: activity.bucketMs, count: activity.buckets,
  }, selectedBucket) : null;
  const anomalies = topics.flatMap(topic => {
    const candidate = detectActivityAnomaly(topic.activity, selectedBucket);
    return candidate ? [{ name: topic.name, ...candidate }] : [];
  });
  const stalledGroup = selectedGroup && consumerHealth(selectedGroup) === 'STALLED'
    ? selectedGroup : null;

  return (
    <div className="space-y-4 pb-8">
      <div className="flex flex-wrap items-center justify-between gap-3">
        <div>
          <p className="text-xs font-medium tracking-widest uppercase text-primary">Spatial observability</p>
          <h1 className="text-2xl font-semibold text-on-surface">Kafka Strata 3D</h1>
          <p className="text-sm text-on-surface-variant">
            Topic activity over time · declared Flink lineage · consumer positions. Height is time, not retained size.
          </p>
        </div>
        <div className="flex items-center gap-2">
          <label className="flex items-center gap-2 text-xs text-on-surface-variant">
            Render quality
            <select aria-label="3D render quality" value={quality}
              onChange={event => setQuality(event.target.value as StrataQuality)}
              className="rounded-lg border border-outline-variant bg-surface px-2 py-2 text-sm text-on-surface">
              <option value="auto">Auto</option>
              <option value="detail">Detail</option>
              <option value="economy">Economy</option>
            </select>
          </label>
          <button className="px-3 py-2 rounded-lg border border-outline-variant text-sm text-on-surface"
            onClick={() => setMotion(v => !v)} aria-pressed={motion}>{motion ? 'Pause motion' : 'Enable motion'}</button>
          <button className="px-3 py-2 rounded-lg border border-outline-variant text-sm text-on-surface"
            onClick={() => setRefresh(v => v + 1)}>Refresh</button>
        </div>
      </div>
      <section className="flex flex-wrap items-center gap-2 rounded-xl border border-outline-variant bg-surface px-3 py-2"
        aria-label="Spatial view presets">
        <span className="text-xs font-semibold text-on-surface-variant mr-1">View presets</span>
        <button type="button" aria-pressed={diagnosticMode === 'investigate' && !activeFocus}
          onClick={() => { setFocused(null); setCompareWith(null); setDiagnosticMode('investigate'); }}
          className="rounded-lg border border-outline-variant px-3 py-1.5 text-xs text-on-surface">Overview</button>
        <button type="button" aria-pressed={Boolean(activeFocus) && diagnosticMode === 'investigate'}
          disabled={scoped.length === 0}
          onClick={() => {
            const target = selected && scoped.includes(selected) ? selected : scoped[0];
            if (target) { focusTopic(target); setDiagnosticMode('investigate'); }
          }}
          className="rounded-lg border border-outline-variant px-3 py-1.5 text-xs text-on-surface disabled:opacity-45">Focus selected</button>
        <button type="button" aria-pressed={diagnosticMode === 'compare'}
          onClick={() => { setFocused(null); setDiagnosticMode('compare'); }}
          className="rounded-lg border border-outline-variant px-3 py-1.5 text-xs text-on-surface">Compare</button>
        <button type="button" onClick={() => { void copyViewLink(); }}
          className="rounded-lg bg-primary-container text-on-primary-container px-3 py-1.5 text-xs font-medium">Copy view link</button>
        {shareNotice && <span role="status" className="text-xs text-on-surface-variant">{shareNotice}</span>}
        {shareLink && <input aria-label="Shareable view URL" readOnly value={shareLink}
          onFocus={event => event.currentTarget.select()}
          className="w-full rounded-lg border border-outline-variant bg-surface px-2 py-1 text-xs text-on-surface" />}
      </section>
      {error && <p role="alert" className="rounded-lg p-3 bg-error-container text-on-error-container">{error}</p>}
      <div className="flex flex-wrap items-center gap-3 text-sm text-on-surface-variant">
        <label className="flex items-center gap-2">Filter topics
          <input aria-label="Filter topics" className="rounded-lg border border-outline-variant bg-surface px-3 py-2 text-on-surface"
            value={query} onChange={e => setQuery(e.target.value)} maxLength={160} placeholder="Topic name" />
        </label>
        <span>{topics.length} / {names.length} topics shown</span>
        <span>{measured} / {topics.length} activity series measured</span>
        {incomplete > 0 && <span>{incomplete} partial series</span>}
        <span>{links.length} declared lineage links</span>
        {anomalies.length > 0 && <span className="text-amber-700 dark:text-amber-300">{anomalies.length} activity deviations in slice</span>}
        {activitySnapshot?.scope === scope && <span>Activity sampled {new Date(activitySnapshot.receivedAt).toLocaleTimeString()}</span>}
      </div>
      {names.length > MAX_TOPICS && <p className="text-xs text-on-surface-variant">
        Rendering the first {MAX_TOPICS} matching topics. Filter to reach others. Measurement is bounded by server limits.
      </p>}
      <section className="rounded-xl border border-outline-variant bg-surface p-4 space-y-3">
        <div className="flex flex-wrap items-center justify-between gap-3">
          <div>
            <h2 className="text-sm font-semibold text-on-surface">Temporal lens</h2>
            <p className="text-xs text-on-surface-variant">
              Slice all topics at one shared time interval. The horizontal plane follows your selection.
            </p>
          </div>
          <div className="flex items-center flex-wrap gap-2">
            <button type="button" className="rounded-lg border border-outline-variant px-3 py-1.5 text-xs text-on-surface"
              disabled={!activity?.available} aria-pressed={replaying}
              onClick={() => {
                if (replaying) setReplaying(false);
                else { setSelectedBucket(0); setReplaying(true); }
              }}>{replaying ? 'Pause replay' : 'Replay history'}</button>
            <label htmlFor="strata-replay-speed" className="text-xs text-on-surface-variant">Replay speed</label>
            <select id="strata-replay-speed" className="rounded-lg border border-outline-variant bg-surface px-2 py-1.5 text-xs text-on-surface"
              value={replaySpeed} onChange={event => setReplaySpeed(Number(event.target.value))}>
              <option value={0.5}>0.5×</option><option value={1}>1×</option>
              <option value={2}>2×</option><option value={4}>4×</option>
            </select>
            <button type="button" className="rounded-lg border border-outline-variant px-3 py-1.5 text-xs text-on-surface"
              onClick={() => { setReplaying(false); setSelectedBucket(BUCKETS - 1); }}
              disabled={selectedBucket === BUCKETS - 1 && !replaying}>Most recent</button>
          </div>
        </div>
        <label className="block text-xs font-medium text-on-surface-variant" htmlFor="strata-time-lens">
          Selected interval: {interval ? `${new Date(interval.startMs).toLocaleString()} – ${new Date(interval.endMs).toLocaleTimeString()}` :
            'Activity not measured yet'}
        </label>
        <input type="range" id="strata-time-lens" min={0} max={BUCKETS - 1} step={1}
          value={selectedBucket} onChange={event => { setReplaying(false); setSelectedBucket(Number(event.target.value)); }}
          disabled={!activity?.available}
          aria-valuetext={interval ? `${new Date(interval.startMs).toLocaleString()} to ${new Date(interval.endMs).toLocaleTimeString()}` : 'Unavailable'}
          className="w-full accent-primary" />
        <div className="flex justify-between text-xs text-on-surface-variant">
          <span>Oldest</span><span>Bucket {selectedBucket + 1}/{BUCKETS}</span><span>Most recent</span>
        </div>
        <p className="text-xs text-on-surface-variant">
          Measurements are offset-based, not samples of individual messages.
          Missing and partially covered buckets are explicitly excluded from anomaly detection.
        </p>
      </section>
      <div className="relative overflow-hidden rounded-2xl border border-outline-variant bg-surface-container-low"
        style={{ height: 'min(68vh, 680px)', minHeight: 410 }}>
        {topics.length > 0 ?
          <StrataCanvas topics={topics} selected={selected} compareWith={activeCompareWith}
            diagnosticMode={diagnosticMode} onSelect={chooseTopic} onFocus={focusTopic}
            focused={activeFocus} partitionActivity={partitionActivity} forecast={visibleForecast} motion={motion} quality={quality}
            links={links} selectedGroup={selectedGroup} themeRevision={themeRevision}
            selectedBucket={selectedBucket} lensAvailable={Boolean(activity?.available)} trace={activeTrace} /> :
          <div className="p-8 text-on-surface-variant">{dashboard ? 'No matching topics.' : 'Loading cluster…'}</div>}
        <div className="absolute bottom-3 left-3 pointer-events-none rounded-lg bg-surface/95 px-3 py-2 text-xs text-on-surface-variant shadow-sm">
          Drag to orbit · Scroll to zoom · Double-click a topic to focus<br />
          12 × 2.5-minute rings · newest at top · logarithmic scale<br />
          Dashes: declared Flink INSERT paths (not observed messages)<br />
          Coloured satellites: selected group's partition lag<br />
          Teal arcs: chronological key matches · amber rings: activity deviations<br />
          Focus: measured partition ribbons · violet canopy: metric forecast<br />
          Cyan halo: topic A · purple halo: topic B (comparison, not a link)
        </div>
      </div>
      <section className="rounded-2xl border border-outline-variant bg-surface-container-low p-3 sm:p-5 space-y-4"
        aria-labelledby="strata-workspace-heading">
        <header className="flex flex-wrap items-center justify-between gap-3">
          <div>
            <h2 id="strata-workspace-heading" className="text-lg font-semibold text-on-surface">Diagnostic workspace</h2>
            <p className="text-xs text-on-surface-variant">
              One selected topic, shared evidence, synced time cursor and a second topic when comparing.
              These analyses are read-only.
            </p>
          </div>
          <div className="inline-flex rounded-xl border border-outline-variant bg-surface p-1 gap-1"
            role="group" aria-label="Diagnostic mode">
            <button type="button" aria-pressed={diagnosticMode === 'investigate'}
              className={`rounded-lg px-3 py-2 text-sm font-medium ${diagnosticMode === 'investigate'
                ? 'bg-primary-container text-on-primary-container' : 'text-on-surface-variant hover:bg-surface-container-high'}`}
              onClick={() => setDiagnosticMode('investigate')}>Investigate</button>
            <button type="button" aria-pressed={diagnosticMode === 'compare'}
              className={`rounded-lg px-3 py-2 text-sm font-medium ${diagnosticMode === 'compare'
                ? 'bg-primary-container text-on-primary-container' : 'text-on-surface-variant hover:bg-surface-container-high'}`}
              onClick={() => setDiagnosticMode('compare')}>Compare</button>
          </div>
        </header>
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
                  {partition.available ? partition.counts.reduce((sum, n) => sum + n, 0).toLocaleString() + ' offsets / 30 min' : 'Unavailable'}
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
      <div className="grid gap-4 lg:grid-cols-[minmax(0,1fr)_minmax(280px,360px)]">
        <section className="rounded-xl border border-outline-variant bg-surface p-4 space-y-4">
          <div>
            <h2 className="font-semibold text-on-surface mb-3">Topics in view</h2>
            <div className="max-h-64 overflow-auto grid sm:grid-cols-2 gap-1">
              {topics.map(topic =>
                <button key={topic.name} className={`text-left rounded-lg px-3 py-2 text-sm truncate ${selected === topic.name ?
                  'bg-primary-container text-on-primary-container' : 'hover:bg-surface-container-high text-on-surface'}`}
                  onClick={() => chooseTopic(topic.name)}
                  onDoubleClick={() => focusTopic(topic.name)} title="Double-click to focus">{topic.name}</button>)}
            </div>
          </div>
          <div className="border-t border-outline-variant pt-4">
            <StrataDiagnosticPanel mode={diagnosticMode} topic={selectedTopic?.name ?? null}
              compareWith={activeCompareWith} topics={scoped} snapshot={activity} links={links}
              trace={activeTrace} selectedBucket={selectedBucket}
              onChooseTopic={chooseTopic} onCompareWith={setCompareWith} />
          </div>
        </section>
        <aside className="rounded-xl border border-outline-variant bg-surface p-4" aria-live="polite">
          <h2 className="font-semibold text-on-surface mb-3">Topic inspector</h2>
          {selectedTopic ? <>
            <p className="break-all font-medium text-on-surface">{selectedTopic.name}</p>
            <button type="button" onClick={() => activeFocus === selectedTopic.name ? setFocused(null) : focusTopic(selectedTopic.name)}
              className="mt-2 rounded-lg border border-outline-variant px-3 py-1.5 text-xs text-on-surface">
              {activeFocus === selectedTopic.name ? 'Return to overview' : 'Focus partitions and forecast'}
            </button>
            <dl className="text-sm text-on-surface-variant mt-3 space-y-2">
              <div className="flex justify-between gap-2"><dt>Estimated retained offsets</dt>
                <dd className="tabular-nums text-on-surface">{selectedTopic.size.toLocaleString()}</dd></div>
              <div className="flex justify-between gap-2"><dt>Selected time slice</dt>
                <dd className="tabular-nums text-on-surface">{bucketReading(selectedTopic.activity, selectedBucket)?.toLocaleString() ?? 'Unknown'}</dd></div>
              <div className="flex justify-between gap-2"><dt>30-minute produced offsets</dt>
                <dd className="tabular-nums text-on-surface">{selectedTopic.activity?.available ?
                  selectedTopic.activity.total.toLocaleString() : 'Unavailable'}</dd></div>
              <div className="flex justify-between gap-2"><dt>Latest bucket (offsets/min)</dt>
                <dd className="tabular-nums text-on-surface">{selectedTopic.activity?.available && selectedTopic.activity.counts.length ?
                  Math.round(selectedTopic.activity.counts[selectedTopic.activity.counts.length - 1] * 60000 / selectedTopic.activity.bucketMs).toLocaleString() :
                  'Unavailable'}</dd></div>
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
        </aside>
      </div>
      </section>
    </div>
  );
}
