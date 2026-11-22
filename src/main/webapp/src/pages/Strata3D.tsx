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
    if (!(object instanceof THREE.Mesh || object instanceof THREE.Line)) return;
    object.geometry.dispose();
    const materials = Array.isArray(object.material) ? object.material : [object.material];
    materials.forEach(material => material.dispose());
  });
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
  return group;
}

function StrataCanvas({ topics, selected, onSelect, motion, links, selectedGroup }: {
  topics: Topic[];
  selected: string | null;
  onSelect: (topic: string) => void;
  motion: boolean;
  links: StrataLink[];
  selectedGroup: ConsumerGroupLag | null;
}) {
  const host = useRef<HTMLDivElement>(null);
  const onSelectRef = useRef(onSelect);
  onSelectRef.current = onSelect;
  const motionRef = useRef(motion);
  motionRef.current = motion;
  const [webglFailed, setWebglFailed] = useState(false);
  const state = useRef<{
    scene: THREE.Scene;
    camera: THREE.OrthographicCamera;
    renderer: THREE.WebGLRenderer;
    controls: OrbitControls;
    content: THREE.Group;
    groups: THREE.Group[];
  } | null>(null);
  useEffect(() => {
    const element = host.current;
    if (!element) return;
    const scene = new THREE.Scene();
    const camera = new THREE.OrthographicCamera(-12, 12, 12, -12, 0.1, 500);
    const extent = 3.4 * Math.sqrt(Math.max(0, topics.length - 1)) + 4;
    const halfHeight = Math.max(9, extent * 0.8);
    const controlsTarget = new THREE.Vector3(0, 2.6, 0);
    camera.position.set(extent * 1.2, extent * 1.05, extent * 1.2);
    camera.lookAt(controlsTarget);
    let renderer: THREE.WebGLRenderer;
    try {
      renderer = new THREE.WebGLRenderer({ antialias: true, alpha: true, powerPreference: 'low-power' });
    } catch {
      setWebglFailed(true);
      return;
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
    const current = { scene, camera, renderer, controls, content: contentGroup, groups: [] as THREE.Group[] };
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
  }, [topics, links, selectedGroup, selected]);

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
  const [activity, setActivity] = useState<ActivityResponse | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [selected, setSelected] = useState<string | null>(null);
  const [query, setQuery] = useState('');
  const [motion, setMotion] = useState(() => !window.matchMedia('(prefers-reduced-motion: reduce)').matches);
  const [refresh, setRefresh] = useState(0);
  useEffect(() => {
    let cancelled = false;
    axios.get<Dashboard>('/api/dashboard', { timeout: 15000 })
      .then(({ data }) => { if (!cancelled) { setDashboard(data); setError(null); } })
      .catch(() => { if (!cancelled) setError('Cluster metadata could not be loaded.'); });
    return () => { cancelled = true; };
  }, [refresh]);
  const names = useMemo(() => (dashboard?.topics ?? []).slice().sort((a, b) => a.localeCompare(b)), [dashboard]);
  const scoped = useMemo(() => names.filter(n => n.toLowerCase().includes(query.toLowerCase())).slice(0, MAX_TOPICS), [names, query]);
  useEffect(() => {
    if (!scoped.length) { setActivity(null); return; }
    let cancelled = false;
    setActivity(null);
    axios.get<ActivityResponse>('/api/dashboard/activity', {
      params: { topics: scoped.join(','), windowMs: WINDOW_MS, buckets: BUCKETS },
      timeout: 20000,
    }).then(({ data }) => { if (!cancelled) setActivity(data); })
      .catch(() => { if (!cancelled) setActivity(null); });
    return () => { cancelled = true; };
  }, [scoped, refresh]);
  const topics = useMemo(() => scoped.map(name => ({
    name, size: dashboard?.topicSizes[name] ?? 0, activity: activity?.topics[name],
  })), [scoped, dashboard, activity]);
  const selectedTopic = topics.find(t => t.name === selected);
  const measured = topics.filter(t => t.activity?.available).length;
  const incomplete = topics.filter(t => t.activity?.available && (t.activity.partitionsMeasured < t.activity.partitionsTotal)).length;
  const noWebgl = typeof document !== 'undefined' && !document.createElement('canvas').getContext('webgl2');
  return (
    <div className="space-y-4 pb-8">
      <div className="flex flex-wrap items-center justify-between gap-3">
        <div>
          <p className="text-xs font-medium tracking-widest uppercase text-primary">Spatial observability</p>
          <h1 className="text-2xl font-semibold text-on-surface">Kafka Strata 3D</h1>
          <p className="text-sm text-on-surface-variant">A temporal landscape of topic activity. Height represents time, not stored messages.</p>
        </div>
        <div className="flex items-center gap-2">
          <button className="px-3 py-2 rounded-lg border border-outline-variant text-sm text-on-surface" onClick={() => setMotion(v => !v)} aria-pressed={motion}>{motion ? 'Pause motion' : 'Enable motion'}</button>
          <button className="px-3 py-2 rounded-lg border border-outline-variant text-sm text-on-surface" onClick={() => setRefresh(v => v + 1)}>Refresh</button>
        </div>
      </div>
      {error && <p role="alert" className="rounded-lg p-3 bg-error-container text-on-error-container">{error}</p>}
      <div className="flex flex-wrap items-center gap-3 text-sm text-on-surface-variant">
        <label className="flex items-center gap-2">Filter topics
          <input aria-label="Filter topics" className="rounded-lg border border-outline-variant bg-surface px-3 py-2 text-on-surface" value={query} onChange={e => setQuery(e.target.value)} placeholder="Topic name" />
        </label>
        <span>{topics.length} / {names.length} topics shown</span>
        <span>{measured} activity series available</span>
        {incomplete > 0 && <span>{incomplete} partial series</span>}
      </div>
      {names.length > MAX_TOPICS && <p className="text-xs text-on-surface-variant">Showing at most {MAX_TOPICS} topics for rendering and measurement cost. Filter to explore others.</p>}
      <div className="relative overflow-hidden rounded-2xl border border-outline-variant bg-surface-container-low" style={{ height: 'min(68vh, 680px)', minHeight: 410 }}>
        {noWebgl ? <div className="p-8 text-on-surface">WebGL 2 is unavailable. Use the topic list below.</div>
          : topics.length > 0 ? <StrataCanvas topics={topics} selected={selected} onSelect={setSelected} motion={motion} />
          : <div className="p-8 text-on-surface-variant">{dashboard ? 'No matching topics.' : 'Loading cluster…'}</div>}
        <div className="absolute bottom-3 left-3 pointer-events-none rounded-lg bg-surface/90 px-3 py-2 text-xs text-on-surface-variant shadow-sm">
          Drag to orbit · Scroll to zoom · Click to inspect<br />
          Rings: 12 five-minute buckets · Top: most recent<br />
          Ring width: log-scaled offset activity · No payloads sampled
        </div>
      </div>
      {activity?.warnings?.length ? <div className="text-xs text-on-surface-variant" role="status">{activity.warnings.join(' ')}</div> : null}
      {activity && !activity.available && <p className="text-sm text-on-surface-variant">Activity is unavailable; neutral rings indicate unknown data, not zero traffic.</p>}
      <div className="grid gap-4 lg:grid-cols-[1fr_300px]">
        <section className="rounded-xl border border-outline-variant bg-surface p-4">
          <h2 className="font-semibold text-on-surface mb-3">Topics in view</h2>
          <div className="max-h-64 overflow-auto grid sm:grid-cols-2 gap-1">
            {topics.map(t => <button key={t.name} className={`text-left rounded-lg px-3 py-2 text-sm truncate ${selected === t.name ? 'bg-primary-container text-on-primary-container' : 'hover:bg-surface-container-high text-on-surface'}`} onClick={() => setSelected(t.name)}>{t.name}</button>)}
          </div>
        </section>
        <aside className="rounded-xl border border-outline-variant bg-surface p-4" aria-live="polite">
          <h2 className="font-semibold text-on-surface mb-3">Topic inspector</h2>
          {selectedTopic ? <>
            <p className="break-all font-medium text-on-surface">{selectedTopic.name}</p>
            <dl className="text-sm text-on-surface-variant mt-3 space-y-2">
              <div className="flex justify-between gap-2"><dt>Retained offset span (approx.)</dt><dd>{selectedTopic.size.toLocaleString()}</dd></div>
              <div className="flex justify-between gap-2"><dt>30-minute offset activity</dt><dd>{selectedTopic.activity?.available ? selectedTopic.activity.counts.reduce((a,b) => a+b, 0).toLocaleString() : 'Unavailable'}</dd></div>
              <div className="flex justify-between gap-2"><dt>Partitions measured</dt><dd>{selectedTopic.activity?.available ? `${selectedTopic.activity.partitionsMeasured}/${selectedTopic.activity.partitionsTotal}` : 'Unknown'}</dd></div>
            </dl>
            {selectedTopic.activity?.note && <p className="mt-2 text-xs text-on-surface-variant">{selectedTopic.activity.note}</p>}
            <Link className="mt-4 inline-block text-sm font-medium text-primary hover:underline" to={`/topic/${encodeURIComponent(selectedTopic.name)}`}>Open Topic Explorer →</Link>
          </> : <p className="text-sm text-on-surface-variant">Select a column or a topic in the list.</p>}
        </aside>
      </div>
    </div>
  );
}
