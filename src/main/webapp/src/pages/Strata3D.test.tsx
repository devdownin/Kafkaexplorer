// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors

/*
 * Le câblage de la page Strata 3D.
 *
 * Les modules purs (`strataTime`, `strataWorkspace`, …) étaient testés ; la page ne l'était pas, et
 * les défauts qu'elle portait vivaient tous entre deux effets React ou entre la page et la scène —
 * là où aucun module pur ne regarde :
 *
 * - l'emphase du curseur temporel était appliquée par un effet, la reconstruction de la scène par un
 *   autre, avec des dépendances différentes : un changement de qualité (ou une lecture des
 *   partitions) remettait chaque anneau à son opacité de base ;
 * - un topic créé ailleurs décalait le rang alphabétique de tous les suivants, et la caméra de
 *   l'opérateur revenait à la vue d'ensemble au rafraîchissement suivant ;
 * - un échec de lecture de l'activité effaçait l'échantillon en silence ;
 * - l'inspecteur était une région `aria-live`, relue à chaque interrogation.
 *
 * WebGL n'existe pas sous jsdom : `WebGLRenderer` est remplacé par un double qui retient la scène
 * et la caméra qu'on lui demande de dessiner. Tout le reste de Three.js est réel.
 */

import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { act, render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import axios from 'axios';
import type * as ThreeModule from 'three';
import type { TopicActivity } from '../api/types';

const drawn = vi.hoisted(() => ({
  scene: null as ThreeModule.Scene | null,
  camera: null as ThreeModule.OrthographicCamera | null,
}));

vi.mock('three', async importOriginal => {
  const actual = await importOriginal<typeof ThreeModule>();
  class FakeRenderer {
    domElement = document.createElement('canvas');
    info = { render: { calls: 0 }, memory: { geometries: 0, textures: 0 } };
    outputColorSpace = '';
    setPixelRatio() {}
    getPixelRatio() { return 1; }
    setSize() {}
    render(scene: ThreeModule.Scene, camera: ThreeModule.OrthographicCamera) {
      drawn.scene = scene;
      drawn.camera = camera;
    }
    dispose() {}
  }
  return { ...actual, WebGLRenderer: FakeRenderer };
});

vi.mock('axios');
const mockedAxios = vi.mocked(axios, true);

import * as THREE from 'three';
import Strata3D from './Strata3D';

const WINDOW_START = 1_700_000_000_000;
const BUCKET_MS = 150_000;

function series(topic: string, counts: number[]): TopicActivity {
  return {
    topic, windowStartMs: WINDOW_START, windowEndMs: WINDOW_START + 12 * BUCKET_MS, bucketMs: BUCKET_MS,
    counts, total: counts.reduce((sum, n) => sum + n, 0), coveredFromMs: null,
    partitionsMeasured: 1, partitionsTotal: 1, available: true, note: null,
  };
}

interface Stub { topics: string[]; activityFails: boolean }
let stub: Stub;

function install() {
  mockedAxios.isCancel.mockReturnValue(false);
  mockedAxios.post.mockImplementation((url: string, body?: unknown) => {
    if (url !== '/api/dashboard/activity') return Promise.reject(new Error('not stubbed: ' + url));
    if (stub.activityFails) return Promise.reject(new Error('broker timeout'));
    const asked = (body as { topics: string[] }).topics;
    return Promise.resolve({ data: {
      topics: Object.fromEntries(asked.map(t => [t, series(t, Array(12).fill(30))])),
      windowStartMs: WINDOW_START, windowEndMs: WINDOW_START + 12 * BUCKET_MS,
      bucketMs: BUCKET_MS, buckets: 12, available: true, warnings: [],
    } });
  });
  mockedAxios.get.mockImplementation((url: string) => {
    if (url === '/api/dashboard') {
      return Promise.resolve({ data: {
        topics: stub.topics, topicSizes: Object.fromEntries(stub.topics.map(t => [t, 100])),
        totalMessages: 0, tables: [], health: true, clusterName: 'test', bootstrapServers: 'x',
        topicLastMessages: {}, internalTopicPrefix: '',
      } });
    }
    if (url === '/api/lineage') return Promise.resolve({ data: { nodes: [], edges: [], warnings: [] } });
    return Promise.reject(new Error('not stubbed: ' + url));
  });
}

function columns(): Map<string, THREE.Group> {
  const found = new Map<string, THREE.Group>();
  drawn.scene?.traverse(object => {
    if (object instanceof THREE.Group && typeof object.userData.topic === 'string') {
      found.set(object.userData.topic as string, object);
    }
  });
  return found;
}

function ring(topic: string, bucket: number): THREE.Mesh {
  const column = columns().get(topic);
  const mesh = column?.children.find(child => child.userData.bucketIndex === bucket);
  if (!(mesh instanceof THREE.Mesh)) throw new Error(`no ring ${bucket} for ${topic}`);
  return mesh;
}

const opacity = (mesh: THREE.Mesh) => (mesh.material as THREE.MeshBasicMaterial).opacity;

async function renderPage() {
  render(<MemoryRouter initialEntries={['/strata']}><Strata3D /></MemoryRouter>);
  await screen.findByRole('button', { name: /^orders/ });
  // The scene is drawn once the activity sample has turned the rings into measured ones.
  await waitFor(() => expect(screen.getByText(/^Sampled /)).toBeInTheDocument());
  await waitFor(() => expect(opacity(ring('orders', 0))).toBeGreaterThan(0));
}

beforeEach(() => {
  vi.clearAllMocks();
  drawn.scene = null;
  drawn.camera = null;
  stub = { topics: ['orders', 'payments'], activityFails: false };
  install();
  window.history.replaceState(null, '', '/strata');
  Object.defineProperty(document, 'visibilityState', { configurable: true, get: () => 'visible' });
  // jsdom has no 2D canvas; topic labels are skipped rather than logged as "not implemented".
  vi.spyOn(HTMLCanvasElement.prototype, 'getContext').mockReturnValue(null);
});

afterEach(() => {
  vi.restoreAllMocks();
  document.documentElement.classList.remove('dark');
});

function ground(): THREE.GridHelper {
  const grid = drawn.scene?.children.find(child => child instanceof THREE.GridHelper);
  if (!(grid instanceof THREE.GridHelper)) throw new Error('no ground');
  return grid;
}

const columnColor = (topic: string) => {
  const crown = columns().get(topic)!.children.find(child =>
    child instanceof THREE.Mesh && child.geometry instanceof THREE.TorusGeometry
    && child.userData.bucketIndex === undefined) as THREE.Mesh;
  return (crown.material as THREE.MeshBasicMaterial).color.getHex();
};

describe('Strata3D page', () => {
  it('keeps the time-lens emphasis when the scene is rebuilt', async () => {
    const user = userEvent.setup();
    await renderPage();
    // The latest bucket (11) is selected: the others are dimmed to 42 % of their base opacity.
    const base = ring('orders', 0).userData.baseOpacity as number;
    expect(opacity(ring('orders', 0))).toBeCloseTo(base * 0.42, 5);

    // A quality change rebuilds every ring. Before the fix they came back at their base opacity.
    const before = ring('orders', 0);
    await user.selectOptions(screen.getByLabelText('3D render quality'), 'economy');
    await waitFor(() => expect(ring('orders', 0)).not.toBe(before));
    expect(opacity(ring('orders', 0))).toBeCloseTo(base * 0.42, 5);
    expect(opacity(ring('orders', 11))).toBeCloseTo(Math.min(1, base * 1.4), 5);
  });

  it('does not move the camera or the existing columns when a topic is created elsewhere', async () => {
    const user = userEvent.setup();
    await renderPage();
    const ordersAt = columns().get('orders')!.position.clone();

    // The operator zooms: a wheel on the canvas is their own gesture.
    const canvas = document.querySelector('canvas')!;
    act(() => { canvas.dispatchEvent(new WheelEvent('wheel', { deltaY: -200, bubbles: true, cancelable: true })); });
    await waitFor(() => expect(drawn.camera!.zoom).not.toBe(1));
    const zoom = drawn.camera!.zoom;
    const position = drawn.camera!.position.clone();

    // A topic that sorts first appears: it used to shift every rank and re-frame the camera.
    stub.topics = ['aaa-new', 'orders', 'payments'];
    await user.click(screen.getByRole('button', { name: 'Refresh' }));
    await screen.findByRole('button', { name: /^aaa-new/ });
    await waitFor(() => expect(columns().has('aaa-new')).toBe(true));

    expect(columns().get('orders')!.position.equals(ordersAt)).toBe(true);
    expect(drawn.camera!.zoom).toBe(zoom);
    expect(drawn.camera!.position.equals(position)).toBe(true);
  });

  it('keeps the last activity sample and says the refresh failed', async () => {
    const user = userEvent.setup();
    await renderPage();
    stub.activityFails = true;
    await user.click(screen.getByRole('button', { name: 'Refresh' }));

    expect(await screen.findByRole('alert')).toHaveTextContent(/activity could not be refreshed/i);
    expect(screen.getByText(/^Last sample /)).toBeInTheDocument();
    // The readings stay: the list still shows the selected bucket's measured count.
    expect(within(screen.getByRole('button', { name: /^orders/ })).getByText('30')).toBeInTheDocument();
    expect(opacity(ring('orders', 11))).toBeGreaterThan(0.25);
  });

  it('does not make the polled inspector or status bar a live region', async () => {
    const user = userEvent.setup();
    await renderPage();
    await user.click(screen.getByRole('button', { name: /^orders/ }));

    const inspector = screen.getByRole('region', { name: 'Topic inspector' });
    expect(inspector.tagName).toBe('SECTION');
    expect(inspector.closest('[aria-live]')).toBeNull();
    expect(inspector.parentElement?.closest('aside aside')).toBeNull();
    expect(screen.getByText(/^Sampled /).closest('[role="status"]')).toBeNull();
  });

  it('asks for the activity in a request body, never in the URL', async () => {
    await renderPage();
    expect(mockedAxios.post).toHaveBeenCalledWith('/api/dashboard/activity',
      expect.objectContaining({ topics: ['orders', 'payments'], buckets: 12 }), expect.anything());
    expect(mockedAxios.get.mock.calls.some(([url]) => url === '/api/dashboard/activity')).toBe(false);
  });

  it('reuses ring geometries across a rebuild instead of allocating them again', async () => {
    const user = userEvent.setup();
    await renderPage();
    const before = ring('orders', 3);
    await user.click(screen.getByRole('button', { name: /^payments/ }));
    await waitFor(() => expect(ring('orders', 3)).not.toBe(before));
    expect(ring('orders', 3).geometry).toBe(before.geometry);
    // Same measured value, same quantised size: one geometry for every equal ring.
    expect(ring('payments', 3).geometry).toBe(before.geometry);
  });

  it('keeps a topic colour when the filter changes what is in view', async () => {
    const user = userEvent.setup();
    await renderPage();
    const color = columnColor('payments');
    await user.type(screen.getByLabelText('Filter topics'), 'pay');
    await waitFor(() => expect(columns().has('orders')).toBe(false));
    expect(columnColor('payments')).toBe(color);
  });

  it('states the ring scale it draws with', async () => {
    await renderPage();
    const legend = screen.getByTestId('strata-ring-legend');
    expect(legend).toHaveTextContent('2.5-minute bucket');
    expect(within(legend).getByText('30')).toBeInTheDocument();
  });

  it('recolours the floor with the theme and grows it with the frame', async () => {
    const user = userEvent.setup();
    stub.topics = Array.from({ length: 40 }, (_, i) => `t${String(i).padStart(2, '0')}`).concat(['orders']);
    window.history.replaceState(null, '', '/strata?sv.filter=orders');
    await renderPage();
    const small = ground().userData.size as number;
    expect(ground().userData.dark).toBe(false);

    await user.clear(screen.getByLabelText('Filter topics'));
    await waitFor(() => expect(ground().userData.size as number).toBeGreaterThan(small));

    act(() => { document.documentElement.classList.add('dark'); });
    await waitFor(() => expect(ground().userData.dark).toBe(true));
  });

  it('leaves the comparison on Escape once nothing is focused', async () => {
    const user = userEvent.setup();
    await renderPage();
    const workspace = screen.getByRole('region', { name: 'Spatial view presets' });
    await user.click(within(workspace).getByRole('button', { name: 'Compare' }));
    expect(within(workspace).getByRole('button', { name: 'Compare' })).toHaveAttribute('aria-pressed', 'true');
    await user.click(document.body);
    await user.keyboard('{Escape}');
    expect(within(workspace).getByRole('button', { name: 'Overview' })).toHaveAttribute('aria-pressed', 'true');
  });

  it('lets one finger scroll the page on a touch screen', async () => {
    const original = window.matchMedia;
    window.matchMedia = (query: string) => ({ ...original(query), matches: query === '(pointer: coarse)' });
    try {
      await renderPage();
      expect(document.querySelector('canvas')!.style.touchAction).toBe('pan-y');
      expect(screen.getByText(/Two fingers orbit and zoom · Double-tap to focus/)).toBeInTheDocument();
    } finally {
      window.matchMedia = original;
    }
  });
});
