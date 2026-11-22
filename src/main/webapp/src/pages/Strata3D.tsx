// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors

import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import type { ConsumerGroupLag, DashboardResponse, TopicActivity, TopicActivityResponse } from '../api/types';
import StrataConsumerPanel from './StrataConsumerPanel';
import { deriveStrataLinks, type StrataLineage, type StrataLink } from './strataLineage';
import { Link } from 'react-router-dom';
import axios from 'axios';
import * as THREE from 'three';
import { OrbitControls } from 'three/addons/controls/OrbitControls.js';

type Dashboard = DashboardResponse;
type Activity = TopicActivity;
type ActivityResponse = TopicActivityResponse;
type Topic = { name: string; size: number; activity?: Activity };
const MAX_TOPICS = 48;
const BUCKETS = 12;
const WINDOW_MS = 30 * 60 * 1000;

function position(index: number): THREE.Vector3 {
  // Deterministic sunflower placement; refreshing metrics never moves a topic.
  const radius = 3.4 * Math.sqrt(index);
  const angle = index * 2.399963229728653;
  return new THREE.Vector3(Math.cos(angle) * radius, 0, Math.sin(angle) * radius);
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

function createTopic(topic: Topic, index: number, maxRate: number, dark: boolean): THREE.Group {
  const group = new THREE.Group();
  group.position.copy(position(index));
  group.userData.topic = topic.name;
  const counts = topic.activity?.available ? topic.activity.counts.slice(-BUCKETS) : [];
  const hasData = counts.length > 0;
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
    const measured = Number.isFinite(value) && value >= 0;
    const normalized = measured ? Math.log1p(value) / Math.log1p(Math.max(1, maxRate)) : 0;
    const radius = measured ? 0.38 + 0.56 * normalized : 0.43;
    const ring = new THREE.Mesh(
      new THREE.TorusGeometry(radius, measured ? 0.025 + normalized * 0.035 : 0.014, 6, 48),
      new THREE.MeshBasicMaterial({ color: measured ? color : 0x8b9aaa, transparent: true, opacity: measured ? 0.4 + normalized * 0.48 : 0.22, depthWrite: false }),
    );
    ring.rotation.x = Math.PI / 2;
    ring.position.y = 0.35 + i * 0.43;
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
  if (hasData && counts.some(n => n > 0)) {
    const glow = new THREE.Mesh(
      new THREE.SphereGeometry(0.10, 10, 8),
      new THREE.MeshBasicMaterial({ color, transparent: true, opacity: 0.95 }),
    );
    glow.userData.pulse = index * 0.41;
    glow.position.set(0, 0.3, 0);
    group.add(glow);
  }
  const label = topicLabel(topic.name, dark);
  if (label) group.add(label);
  return group;
}

function StrataCanvas({ topics, selected, onSelect, motion, links, selectedGroup, themeRevision }: {
  topics: Topic[];
  selected: string | null;
  onSelect: (topic: string) => void;
  motion: boolean;
  links: StrataLink[];
  selectedGroup: ConsumerGroupLag | null;
  themeRevision: number;
}) {
  const host = useRef<HTMLDivElement>(null);
  const onSelectRef = useRef(onSelect);
  const motionRef = useRef(motion);
  const [webglFailed, setWebglFailed] = useState(false);
  useEffect(() => { onSelectRef.current = onSelect; }, [onSelect]);
  useEffect(() => { motionRef.current = motion; }, [motion]);
  const state = useRef<{
    scene: THREE.Scene;
    camera: THREE.OrthographicCamera;
    renderer: THREE.WebGLRenderer;
    controls: OrbitControls;
    content: THREE.Group;
    groups: THREE.Group[];
    signature: string;
    fit: (count: number) => void;
  } | null>(null);
  useEffect(() => {
    const element = host.current;
    if (!element) return;
    const scene = new THREE.Scene();
    const camera = new THREE.OrthographicCamera(-12, 12, 12, -12, 0.1, 500);
    const extent = 3.4 * Math.sqrt(Math.max(0, topics.length - 1)) + 4;
    let halfHeight = Math.max(9, extent * 0.8);
    const controlsTarget = new THREE.Vector3(0, 2.6, 0);
    camera.position.set(extent * 1.2, extent * 1.05, extent * 1.2);
    camera.lookAt(controlsTarget);
    let renderer: THREE.WebGLRenderer;
    try {
      renderer = new THREE.WebGLRenderer({ antialias: true, alpha: true, powerPreference: 'low-power' });
    } catch {
      // Defer the fallback update: React disallows synchronous state writes from effects.
      const fallback = window.setTimeout(() => setWebglFailed(true), 0);
      return () => window.clearTimeout(fallback);
    }
    renderer.setPixelRatio(Math.min(window.devicePixelRatio || 1, 2));
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
    const ground = new THREE.GridHelper(Math.max(40, extent * 2.8), 24, 0x7794ab, 0xb1c1cf);
    ground.position.y = -0.12;
    scene.add(ground);
    const current = {
      scene, camera, renderer, controls, content: contentGroup,
      groups: [] as THREE.Group[], signature: topics.map(t => t.name).join('\\u0000'),
      fit: (_count: number) => {},
    };
    state.current = current;

    const pointer = new THREE.Vector2();
    const raycaster = new THREE.Raycaster();
    let down: { x: number; y: number } | null = null;
    const onPointerDown = (event: PointerEvent) => { down = { x: event.clientX, y: event.clientY }; };
    const onPointerUp = (event: PointerEvent) => {
      if (!down || Math.hypot(down.x - event.clientX, down.y - event.clientY) > 5) return;
      const bounds = renderer.domElement.getBoundingClientRect();
      if (!bounds.width || !bounds.height) return;
      pointer.set((event.clientX - bounds.left) / bounds.width * 2 - 1, 1 - (event.clientY - bounds.top) / bounds.height * 2);
      raycaster.setFromCamera(pointer, camera);
      const picked = raycaster.intersectObjects(current.groups, true)[0]?.object;
      let item: THREE.Object3D | null = picked ?? null;
      while (item && !item.userData.topic) item = item.parent;
      if (item?.userData.topic) onSelectRef.current(item.userData.topic as string);
    };
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
    current.fit = (count: number) => {
      const width = 3.4 * Math.sqrt(Math.max(0, count - 1)) + 4;
      halfHeight = Math.max(9, width * 0.8);
      camera.position.set(width * 1.2, width * 1.05, width * 1.2);
      controls.target.set(0, 2.6, 0);
      camera.zoom = 1;
      controls.update();
      resize();
    };
    const observer = new ResizeObserver(resize);
    observer.observe(element);
    resize();
    let frame = 0;
    let last = 0;
    const render = (time: number) => {
      frame = requestAnimationFrame(render);
      if (time - last < (motionRef.current ? 33 : 100)) return;
      last = time;
      controls.enableDamping = motionRef.current;
      controls.update();
      for (const group of current.groups) {
        for (const object of group.children) {
          if (!(object instanceof THREE.Mesh) || object.userData.pulse === undefined) continue;
          object.position.y = motionRef.current
            ? 0.3 + ((time * 0.00035 + (object.userData.pulse as number)) % 1) * 5.2
            : 5.45;
        }
      }
      renderer.render(scene, camera);
    };
    frame = requestAnimationFrame(render);
    return () => {
      cancelAnimationFrame(frame);
      observer.disconnect();
      renderer.domElement.removeEventListener('pointerdown', onPointerDown);
      renderer.domElement.removeEventListener('pointerup', onPointerUp);
      controls.dispose();
      disposeGroup(contentGroup);
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
    const signature = topics.map(t => t.name).join('\\u0000');
    if (signature !== current.signature) {
      current.signature = signature;
      current.fit(topics.length);
    }
    // Replace measured geometry without tearing down WebGL, orbit or pointer handlers.
    disposeGroup(current.content);
    current.content.clear();
    const dark = document.documentElement.classList.contains('dark') ||
      document.documentElement.getAttribute('data-theme') === 'dark';
    const values = topics.flatMap(t => t.activity?.available ? t.activity.counts : []);
    const maxRate = Math.max(1, ...values);
    current.groups = topics.map((topic, index) => {
      const column = createTopic(topic, index, maxRate, dark);
      current.content.add(column);
      return column;
    });
    const lookup = new Map(topics.map((t, index) => [t.name, position(index)]));
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
      const line = new THREE.Line(geometry, new THREE.LineDashedMaterial({
        color: dark ? 0x9db6db : 0x6d7fa2, transparent: true, opacity: 0.58, dashSize: 0.38, gapSize: 0.24,
      }));
      line.computeLineDistances();
      current.content.add(line);
    }

    const selectedIndex = topics.findIndex(t => t.name === selected);
    if (selectedIndex >= 0 && selectedGroup) {
      const point = position(selectedIndex);
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
    current.renderer.render(current.scene, current.camera);
  }, [topics, links, selectedGroup, selected, themeRevision]);

  useEffect(() => {
    const groups = state.current?.groups;
    if (!groups) return;
    for (const group of groups) group.scale.setScalar(group.userData.topic === selected ? 1.04 : 1);
  }, [selected, topics]);

  return (
    <div ref={host} role="img"
      aria-label="Interactive 3D topic activity map. Drag to orbit, scroll to zoom. The topic list below provides keyboard selection."
      className="w-full h-full min-h-[380px] cursor-grab active:cursor-grabbing">
      {webglFailed && <p role="status" className="p-6 text-sm text-on-surface">3D rendering unavailable. Use the accessible topic list below.</p>}
    </div>
  );
}

export default function Strata3D() {
  const [dashboard, setDashboard] = useState<Dashboard | null>(null);
  const [activitySnapshot, setActivitySnapshot] = useState<{
    scope: string; data: ActivityResponse; receivedAt: number;
  } | null>(null);
  const [lineage, setLineage] = useState<StrataLineage | null>(null);
  const [lineageError, setLineageError] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [selected, setSelected] = useState<string | null>(null);
  const [selectedGroup, setSelectedGroup] = useState<ConsumerGroupLag | null>(null);
  const [query, setQuery] = useState('');
  const [filteredQuery, setFilteredQuery] = useState('');
  const [motion, setMotion] = useState(() => !window.matchMedia('(prefers-reduced-motion: reduce)').matches);
  const [refresh, setRefresh] = useState(0);
  const [themeRevision, setThemeRevision] = useState(0);

  const onGroupSelect = useCallback((group: ConsumerGroupLag | null) => setSelectedGroup(group), []);
  const chooseTopic = useCallback((name: string) => {
    if (selected !== name) setSelectedGroup(null);
    setSelected(name);
  }, [selected]);

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

  const activity = activitySnapshot?.scope === scope ? activitySnapshot.data : null;
  const topics = useMemo(() => scoped.map(name => ({
    name, size: dashboard?.topicSizes[name] ?? 0, activity: activity?.topics[name],
  })), [scoped, dashboard, activity]);
  const links = useMemo(() => lineage ? deriveStrataLinks(lineage, scoped) : [], [lineage, scoped]);
  const selectedTopic = topics.find(t => t.name === selected);
  const measured = topics.filter(t => t.activity?.available).length;
  const incomplete = topics.filter(t => t.activity?.available && (
    t.activity.partitionsMeasured < t.activity.partitionsTotal || t.activity.coveredFromMs !== null
  )).length;

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
          <button className="px-3 py-2 rounded-lg border border-outline-variant text-sm text-on-surface"
            onClick={() => setMotion(v => !v)} aria-pressed={motion}>{motion ? 'Pause motion' : 'Enable motion'}</button>
          <button className="px-3 py-2 rounded-lg border border-outline-variant text-sm text-on-surface"
            onClick={() => setRefresh(v => v + 1)}>Refresh</button>
        </div>
      </div>
      {error && <p role="alert" className="rounded-lg p-3 bg-error-container text-on-error-container">{error}</p>}
      <div className="flex flex-wrap items-center gap-3 text-sm text-on-surface-variant">
        <label className="flex items-center gap-2">Filter topics
          <input aria-label="Filter topics" className="rounded-lg border border-outline-variant bg-surface px-3 py-2 text-on-surface"
            value={query} onChange={e => setQuery(e.target.value)} placeholder="Topic name" />
        </label>
        <span>{topics.length} / {names.length} topics shown</span>
        <span>{measured} / {topics.length} activity series measured</span>
        {incomplete > 0 && <span>{incomplete} partial series</span>}
        <span>{links.length} declared lineage links</span>
        {activitySnapshot?.scope === scope && <span>Activity sampled {new Date(activitySnapshot.receivedAt).toLocaleTimeString()}</span>}
      </div>
      {names.length > MAX_TOPICS && <p className="text-xs text-on-surface-variant">
        Rendering the first {MAX_TOPICS} matching topics. Filter to reach others. Measurement is bounded by server limits.
      </p>}
      <div className="relative overflow-hidden rounded-2xl border border-outline-variant bg-surface-container-low"
        style={{ height: 'min(68vh, 680px)', minHeight: 410 }}>
        {topics.length > 0 ?
          <StrataCanvas topics={topics} selected={selected} onSelect={chooseTopic} motion={motion}
            links={links} selectedGroup={selectedGroup} themeRevision={themeRevision} /> :
          <div className="p-8 text-on-surface-variant">{dashboard ? 'No matching topics.' : 'Loading cluster…'}</div>}
        <div className="absolute bottom-3 left-3 pointer-events-none rounded-lg bg-surface/95 px-3 py-2 text-xs text-on-surface-variant shadow-sm">
          Drag to orbit · Scroll to zoom · Click a topic<br />
          12 × 2.5-minute rings · newest at top · logarithmic scale<br />
          Dashes: declared Flink INSERT paths (not observed messages)<br />
          Coloured satellites: selected group's partition lag
        </div>
      </div>
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
                  onClick={() => chooseTopic(topic.name)}>{topic.name}</button>)}
            </div>
          </div>
          <div className="border-t border-outline-variant pt-3">
            <h3 className="text-sm font-semibold text-on-surface mb-2">Declared Flink INSERT links</h3>
            <p className="text-xs text-on-surface-variant mb-2">
              Paths derive from source-topic tables and running INSERT jobs. No link means no verifiable path in the retrieved lineage, not necessarily no data flow.
            </p>
            {links.length === 0 ?
              <p className="text-sm text-on-surface-variant">No explicit topic-to-topic INSERT path in this view.</p> :
              <ul className="max-h-44 overflow-auto space-y-1">
                {links.slice(0, 40).map(link =>
                  <li key={`${link.from}|${link.to}|${link.via}`}>
                    <button className="w-full text-left text-xs text-on-surface hover:bg-surface-container-high px-2 py-1.5 rounded-lg"
                      onClick={() => chooseTopic(link.from)} title={`Evidence: ${link.via}`}>
                      <span className="font-medium">{link.from}</span> → {link.to}
                      <span className="text-on-surface-variant"> · {link.via}</span>
                    </button>
                  </li>)}
                {links.length > 40 && <li className="text-xs text-on-surface-variant">More links omitted from this list.</li>}
              </ul>}
          </div>
        </section>
        <aside className="rounded-xl border border-outline-variant bg-surface p-4" aria-live="polite">
          <h2 className="font-semibold text-on-surface mb-3">Topic inspector</h2>
          {selectedTopic ? <>
            <p className="break-all font-medium text-on-surface">{selectedTopic.name}</p>
            <dl className="text-sm text-on-surface-variant mt-3 space-y-2">
              <div className="flex justify-between gap-2"><dt>Estimated retained offsets</dt>
                <dd className="tabular-nums text-on-surface">{selectedTopic.size.toLocaleString()}</dd></div>
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
    </div>
  );
}
