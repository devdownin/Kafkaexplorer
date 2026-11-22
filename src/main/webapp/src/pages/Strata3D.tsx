// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors

import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { Link } from 'react-router-dom';
import axios from 'axios';
import * as THREE from 'three';
import { OrbitControls } from 'three/addons/controls/OrbitControls.js';

type Dashboard = { topics: string[]; topicSizes: Record<string, number>; health: boolean };
type Activity = { counts: number[]; available: boolean; partitionsMeasured: number; partitionsTotal: number; note?: string | null };
type ActivityResponse = { topics: Record<string, Activity>; available: boolean; warnings: string[]; windowStartMs: number; windowEndMs: number; bucketMs: number };
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

function StrataCanvas({ topics, selected, onSelect, motion }: {
  topics: Topic[]; selected: string | null; onSelect: (topic: string) => void; motion: boolean;
}) {
  const host = useRef<HTMLDivElement>(null);
  const selectRef = useRef(onSelect);
  selectRef.current = onSelect;
  const selectedRef = useRef(selected);
  selectedRef.current = selected;
  useEffect(() => {
    const element = host.current;
    if (!element) return;
    const scene = new THREE.Scene();
    const camera = new THREE.OrthographicCamera(-14, 14, 9, -9, 0.1, 500);
    const extent = 3.4 * Math.sqrt(Math.max(0, topics.length - 1)) + 4;
    const halfHeight = Math.max(9, extent * 0.78);
    const controlsTarget = new THREE.Vector3(0, 2.6, 0);
    camera.position.set(extent * 1.2, extent * 1.05, extent * 1.2);
    camera.left = -halfHeight;
    camera.right = halfHeight;
    camera.top = halfHeight;
    camera.bottom = -halfHeight;
    camera.updateProjectionMatrix();
    camera.lookAt(controlsTarget);
    let renderer: THREE.WebGLRenderer;
    try {
      renderer = new THREE.WebGLRenderer({ antialias: true, alpha: true, powerPreference: 'low-power' });
    } catch {
      element.dataset.webglUnavailable = 'true';
      return;
    }
    renderer.setPixelRatio(Math.min(window.devicePixelRatio || 1, 2));
    renderer.outputColorSpace = THREE.SRGBColorSpace;
    element.appendChild(renderer.domElement);
    const controls = new OrbitControls(camera, renderer.domElement);
    controls.target.copy(controlsTarget);
    controls.enableDamping = motion;
    controls.dampingFactor = 0.09;
    controls.minPolarAngle = 0.14;
    controls.maxPolarAngle = Math.PI / 2.12;
    controls.minZoom = 0.5;
    controls.maxZoom = 5;
    controls.enablePan = true;
    controls.update();
    const dark = document.documentElement.classList.contains('dark') ||
      document.documentElement.getAttribute('data-theme') === 'dark';
    const ground = new THREE.GridHelper(Math.max(40, extent * 2.8), 24, dark ? 0x54718c : 0x96b0c1, dark ? 0x324a60 : 0xc7d4df);
    ground.position.y = -0.1;
    scene.add(ground);
    const values = topics.flatMap(t => t.activity?.available ? t.activity.counts : []);
    const maxRate = Math.max(1, ...values);
    const groups = topics.map((topic, index) => {
      const group = createTopic(topic, index, maxRate, dark);
      scene.add(group);
      return group;
    });
    const raycaster = new THREE.Raycaster();
    const pointer = new THREE.Vector2();
    let start: { x: number; y: number } | null = null;
    const onDown = (event: PointerEvent) => { start = { x: event.clientX, y: event.clientY }; };
    const onUp = (event: PointerEvent) => {
      if (!start || Math.hypot(event.clientX - start.x, event.clientY - start.y) > 5) return;
      const rect = renderer.domElement.getBoundingClientRect();
      pointer.set((event.clientX - rect.left) / rect.width * 2 - 1, -(event.clientY - rect.top) / rect.height * 2 + 1);
      raycaster.setFromCamera(pointer, camera);
      const intersections = raycaster.intersectObjects(groups, true);
      const hit = intersections[0]?.object;
      if (hit) {
        let parent: THREE.Object3D | null = hit;
        while (parent && !parent.userData.topic) parent = parent.parent;
        if (parent?.userData.topic) selectRef.current(parent.userData.topic as string);
      }
    };
    renderer.domElement.addEventListener('pointerdown', onDown);
    renderer.domElement.addEventListener('pointerup', onUp);
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
      if (time - last < (motion ? 33 : 100)) return;
      last = time;
      controls.update();
      for (const group of groups) {
        const active = group.userData.topic === selectedRef.current;
        for (const object of group.children) {
          if (!(object instanceof THREE.Mesh)) continue;
          if (object.userData.pulse !== undefined) {
            object.position.y = motion ? 0.3 + ((time * 0.00035 + object.userData.pulse) % 1) * 5.2 : 5.45;
          }
          if (object.geometry instanceof THREE.TorusGeometry) {
            const material = object.material as THREE.MeshBasicMaterial;
            material.opacity = active ? Math.min(1, material.opacity + (1 - material.opacity) * 0.12) : material.opacity;
          }
        }
      }
      renderer.render(scene, camera);
    };
    frame = requestAnimationFrame(render);
    return () => {
      cancelAnimationFrame(frame);
      observer.disconnect();
      renderer.domElement.removeEventListener('pointerdown', onDown);
      renderer.domElement.removeEventListener('pointerup', onUp);
      controls.dispose();
      groups.forEach(disposeGroup);
      ground.geometry.dispose();
      (ground.material as THREE.Material).dispose();
      renderer.dispose();
      renderer.domElement.remove();
    };
  }, [topics, motion]);
  return <div ref={host} role="img" aria-label="Interactive 3D map of Kafka topics. Drag to orbit, scroll to zoom, click a topic to inspect." className="w-full h-full min-h-[380px] cursor-grab active:cursor-grabbing" />;
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
