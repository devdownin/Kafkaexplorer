// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors

import { useEffect, useRef, useState } from 'react';
import * as THREE from 'three';
import { OrbitControls } from 'three/addons/controls/OrbitControls.js';
import type { Column } from './model';
import { BUCKETS, activityPeak, ringRadius } from './model';

interface Props {
  columns: Column[];
  selected: string | null;
  dark: boolean;
  motion: boolean;
  view: 'orbit' | 'top';
  resetIndex: number;
  onSelect: (name: string) => void;
}

interface Spark {
  mesh: THREE.Mesh;
  speed: number;
  phase: number;
}
interface SceneContext {
  renderer: THREE.WebGLRenderer;
  scene: THREE.Scene;
  camera: THREE.OrthographicCamera;
  controls: OrbitControls;
  objects: THREE.Group;
  targets: THREE.Object3D[];
  sparks: Spark[];
  geometries: THREE.BufferGeometry[];
  grid: THREE.GridHelper;
  radius: number;
  resize: () => void;
}

/** Labels are sprites rather than 3D text: they face the camera and remain legible on orbit. */
function labelSprite(name: string, dark: boolean): THREE.Sprite {
  const canvas = document.createElement('canvas');
  canvas.width = 512;
  canvas.height = 96;
  const ctx = canvas.getContext('2d');
  if (ctx) {
    ctx.clearRect(0, 0, 512, 96);
    ctx.fillStyle = dark ? 'rgba(10,20,35,0.88)' : 'rgba(255,255,255,0.94)';
    ctx.beginPath();
    ctx.roundRect(2, 7, 508, 80, 18);
    ctx.fill();
    ctx.strokeStyle = dark ? 'rgba(164,191,235,0.28)' : 'rgba(69,103,145,0.2)';
    ctx.stroke();
    ctx.font = '600 29px Inter, system-ui, sans-serif';
    ctx.fillStyle = dark ? '#EDF4FF' : '#20364F';
    ctx.textAlign = 'center';
    ctx.textBaseline = 'middle';
    const label = name.length > 26 ? name.slice(0, 23) + '…' : name;
    ctx.fillText(label, 256, 46, 465);
  }
  const texture = new THREE.CanvasTexture(canvas);
  texture.colorSpace = THREE.SRGBColorSpace;
  const sprite = new THREE.Sprite(new THREE.SpriteMaterial({
    map: texture, transparent: true, depthTest: false, depthWrite: false,
  }));
  sprite.position.set(0, 4.3, 0);
  sprite.scale.set(2.5, 0.47, 1);
  return sprite;
}

/** Dispose only per-page materials/textures. Shared geometry is released on unmount. */
function clearColumns(context: SceneContext): void {
  context.objects.traverse(node => {
    if (!(node instanceof THREE.Mesh || node instanceof THREE.Sprite)) return;
    const materials = Array.isArray(node.material) ? node.material : [node.material];
    materials.forEach(material => {
      const texture = (material as THREE.SpriteMaterial).map;
      texture?.dispose();
      material.dispose();
    });
  });
  context.objects.clear();
  context.targets = [];
  context.sparks = [];
}

function frameCamera(context: SceneContext, view: 'orbit' | 'top'): void {
  const { camera, controls, radius } = context;
  const target = new THREE.Vector3(0, 1.9, 0);
  controls.target.copy(target);
  camera.position.copy(view === 'top'
    ? new THREE.Vector3(0, radius * 2.0, 0.01)
    : new THREE.Vector3(radius * 0.85, radius * 1.12, radius * 1.48));
  camera.up.set(0, 1, 0);
  camera.lookAt(target);
  controls.update();
}

/**
 * Imperative WebGL island: React owns data and accessible controls, Three.js owns GPU resources.
 * All subscriptions, RAFs, geometries and textures are cleaned when the lazy route unmounts.
 */
export default function StrataScene({ columns, selected, dark, motion, view, resetIndex, onSelect }: Props) {
  const hostRef = useRef<HTMLDivElement>(null);
  const ctxRef = useRef<SceneContext | null>(null);
  const callbackRef = useRef(onSelect);
  const motionRef = useRef(motion);
  const selectedRef = useRef(selected);
  const [unsupported, setUnsupported] = useState(false);

  useEffect(() => { callbackRef.current = onSelect; }, [onSelect]);
  useEffect(() => { motionRef.current = motion; }, [motion]);
  useEffect(() => { selectedRef.current = selected; }, [selected]);

  useEffect(() => {
    const host = hostRef.current;
    if (!host) return;
    let renderer: THREE.WebGLRenderer;
    try {
      renderer = new THREE.WebGLRenderer({ antialias: true, alpha: false, powerPreference: 'low-power' });
    } catch {
      setUnsupported(true);
      return;
    }

    renderer.setPixelRatio(Math.min(window.devicePixelRatio || 1, 1.75));
    renderer.outputColorSpace = THREE.SRGBColorSpace;
    renderer.toneMapping = THREE.ACESFilmicToneMapping;
    renderer.toneMappingExposure = 1.15;
    renderer.domElement.style.display = 'block';
    renderer.domElement.style.touchAction = 'none';
    renderer.domElement.setAttribute('aria-hidden', 'true');
    host.appendChild(renderer.domElement);

    const scene = new THREE.Scene();
    const camera = new THREE.OrthographicCamera(-12, 12, 10, -10, 0.1, 800);
    const controls = new OrbitControls(camera, renderer.domElement);
    controls.enableDamping = true;
    controls.dampingFactor = 0.065;
    controls.minZoom = 0.65;
    controls.maxZoom = 5;
    controls.minPolarAngle = 0;
    controls.maxPolarAngle = Math.PI * 0.49;
    controls.enablePan = true;
    controls.screenSpacePanning = true;

    const ambient = new THREE.AmbientLight(0xffffff, 2.0);
    scene.add(ambient);
    const key = new THREE.DirectionalLight(0xffffff, 2.3);
    key.position.set(-12, 24, 9);
    scene.add(key);

    const grid = new THREE.GridHelper(150, 50, 0x8ca7c7, 0xa3b6c9);
    const gridMaterial = grid.material as THREE.Material;
    gridMaterial.transparent = true;
    gridMaterial.opacity = 0.15;
    grid.position.y = -0.12;
    scene.add(grid);

    const objects = new THREE.Group();
    scene.add(objects);
    const geometries: THREE.BufferGeometry[] = [
      new THREE.CylinderGeometry(0.48, 0.48, 3.3, 32, 1, true),
      new THREE.TorusGeometry(1, 0.032, 7, 48),
      new THREE.TorusGeometry(0.98, 0.029, 8, 64),
      new THREE.CircleGeometry(0.98, 48),
      new THREE.SphereGeometry(0.075, 8, 8),
    ];

    const context: SceneContext = {
      renderer, scene, camera, controls, objects, targets: [], sparks: [],
      geometries, grid, radius: 12, resize: () => {},
    };
    ctxRef.current = context;

    const resize = () => {
      const width = host.clientWidth;
      const height = host.clientHeight;
      if (!width || !height) return;
      const halfH = context.radius * 0.88;
      const aspect = width / height;
      camera.left = -halfH * aspect;
      camera.right = halfH * aspect;
      camera.top = halfH;
      camera.bottom = -halfH;
      camera.updateProjectionMatrix();
      renderer.setSize(width, height, false);
    };
    context.resize = resize;
    const observer = new ResizeObserver(resize);
    observer.observe(host);
    resize();

    const raycaster = new THREE.Raycaster();
    const pointer = new THREE.Vector2();
    let press: { x: number; y: number } | null = null;
    const pointerDown = (event: PointerEvent) => {
      if (event.button !== 0) return;
      press = { x: event.clientX, y: event.clientY };
    };
    const pointerUp = (event: PointerEvent) => {
      if (!press || Math.hypot(event.clientX - press.x, event.clientY - press.y) > 6) {
        press = null;
        return;
      }
      press = null;
      const rect = renderer.domElement.getBoundingClientRect();
      if (!rect.width || !rect.height) return;
      pointer.set((event.clientX - rect.left) / rect.width * 2 - 1,
        1 - (event.clientY - rect.top) / rect.height * 2);
      raycaster.setFromCamera(pointer, camera);
      const intersect = raycaster.intersectObjects(context.targets, false)[0];
      const name = intersect?.object.userData.topic as string | undefined;
      if (name) callbackRef.current(name);
    };
    renderer.domElement.addEventListener('pointerdown', pointerDown);
    renderer.domElement.addEventListener('pointerup', pointerUp);

    let request = 0;
    let lastFrame = 0;
    const reduced = window.matchMedia('(prefers-reduced-motion: reduce)');
    const updateMotion = () => { controls.enableDamping = !reduced.matches; };
    reduced.addEventListener('change', updateMotion);
    updateMotion();
    const animate = (now: number) => {
      request = requestAnimationFrame(animate);
      if (document.hidden || now - lastFrame < 33) return;
      const delta = Math.min(0.075, (now - lastFrame) / 1000);
      lastFrame = now;
      if (motionRef.current && !reduced.matches) {
        for (const spark of context.sparks) {
          spark.phase = (spark.phase + delta * spark.speed) % 1;
          spark.mesh.position.y = 0.2 + spark.phase * 3.3;
        }
      }
      controls.update();
      renderer.render(scene, camera);
    };
    request = requestAnimationFrame(animate);

    return () => {
      cancelAnimationFrame(request);
      reduced.removeEventListener('change', updateMotion);
      renderer.domElement.removeEventListener('pointerdown', pointerDown);
      renderer.domElement.removeEventListener('pointerup', pointerUp);
      observer.disconnect();
      controls.dispose();
      clearColumns(context);
      geometries.forEach(geometry => geometry.dispose());
      grid.geometry.dispose();
      gridMaterial.dispose();
      renderer.dispose();
      renderer.domElement.remove();
      ctxRef.current = null;
    };
  }, []);

  useEffect(() => {
    const context = ctxRef.current;
    if (!context) return;
    clearColumns(context);
    const { objects, geometries } = context;
    const peak = activityPeak(columns);
    const palette = {
      complete: dark ? 0x5cd3e8 : 0x168cae,
      partial: dark ? 0xf6be74 : 0xb46a13,
      unavailable: dark ? 0x73859b : 0x8495a9,
    };
    context.renderer.setClearColor(dark ? 0x101c2e : 0xeaf2f9, 1);
    context.radius = Math.max(8.7, Math.sqrt(Math.max(1, columns.length)) * 3.0);
    columns.forEach((column, index) => {
      const group = new THREE.Group();
      group.position.set(column.position.x, 0, column.position.z);
      group.userData.topic = column.name;
      objects.add(group);
      const color = palette[column.coverage];
      const body = new THREE.Mesh(geometries[0], new THREE.MeshPhysicalMaterial({
        color, transparent: true, opacity: 0.19, metalness: 0.05, roughness: 0.22,
        side: THREE.DoubleSide, depthWrite: false,
      }));
      body.position.y = 1.75;
      body.userData.topic = column.name;
      group.add(body);
      context.targets.push(body);

      const foot = new THREE.Mesh(geometries[3], new THREE.MeshBasicMaterial({
        color, transparent: true, opacity: 0.13, depthWrite: false, side: THREE.DoubleSide,
      }));
      foot.rotation.x = -Math.PI / 2;
      foot.position.y = 0.025;
      foot.userData.topic = column.name;
      group.add(foot);
      context.targets.push(foot);

      const counts = column.activity?.counts ?? [];
      for (let bucket = 0; bucket < BUCKETS; bucket++) {
        const measured = column.coverage !== 'unavailable';
        const value = measured ? counts[bucket] : 0;
        // The base ring is only a guide for measured zero activity, not a simulated event.
        const radius = measured ? ringRadius(value, peak) : 0.47;
        const ring = new THREE.Mesh(geometries[1], new THREE.MeshBasicMaterial({
          color, transparent: true,
          opacity: !measured ? 0.17 : value > 0 ? 0.42 + 0.4 * Math.sqrt(value / peak) : 0.11,
          depthWrite: false, side: THREE.DoubleSide,
        }));
        ring.rotation.x = Math.PI / 2;
        ring.scale.set(radius, radius, 1);
        ring.position.y = 0.36 + bucket * 0.26;
        ring.userData.topic = column.name;
        group.add(ring);
        context.targets.push(ring);
      }

      const highlight = new THREE.Mesh(geometries[2], new THREE.MeshBasicMaterial({
        color: dark ? 0xf7fafc : 0x223c5c, transparent: true, opacity: 0.85,
      }));
      highlight.rotation.x = Math.PI / 2;
      highlight.position.y = 0.08;
      highlight.userData.selection = true;
      highlight.visible = column.name === selectedRef.current;
      group.add(highlight);

      group.add(labelSprite(column.name, dark));

      // One representative spark per busy column; it is NOT a sampled Kafka record.
      const lastCount = column.coverage !== 'unavailable' ? (counts[counts.length - 1] ?? 0) : 0;
      if (lastCount > 0) {
        const spark = new THREE.Mesh(geometries[4], new THREE.MeshBasicMaterial({
          color, transparent: true, opacity: 0.92, depthWrite: false,
        }));
        const phase = (index * 0.397) % 1;
        spark.position.set(0.1, 0.2 + phase * 3.3, 0.46);
        group.add(spark);
        context.sparks.push({ mesh: spark, phase, speed: 0.17 + 0.6 * Math.sqrt(lastCount / peak) });
      }
    });
    context.resize();
  }, [columns, dark]);

  useEffect(() => {
    const context = ctxRef.current;
    if (!context) return;
    context.objects.children.forEach(group => {
      group.children.forEach(node => {
        if (node.userData.selection) node.visible = group.userData.topic === selected;
      });
    });
  }, [selected, columns]);

  useEffect(() => {
    const context = ctxRef.current;
    if (context) frameCamera(context, view);
  }, [view, resetIndex, columns.length]);

  return (
    <div ref={hostRef} className="h-full w-full relative overflow-hidden" role="img"
      aria-label="Interactive three-dimensional Kafka topic activity landscape. Select a topic with the accessible list alongside the scene.">
      {unsupported && (
        <div className="absolute inset-0 flex items-center justify-center p-8 text-center bg-surface-container-low">
          <p className="text-sm text-on-surface-variant">
            3D rendering is unavailable on this device. Use the topic list to explore the same measurements.
          </p>
        </div>
      )}
    </div>
  );
}
