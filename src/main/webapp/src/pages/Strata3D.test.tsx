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
import type { MetricConfig, TopicActivity } from '../api/types';

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

interface Stub { topics: string[]; activityFails: boolean; metrics: MetricConfig[] }
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
    if (url === '/api/metrics') return Promise.resolve({ data: stub.metrics });
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
  stub = { topics: ['orders', 'payments'], activityFails: false, metrics: [] };
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

  describe('families and flows', () => {
    const pipeline = ['orders', 'payments', 'demo.orders.1.received', 'demo.orders.2.validated',
      'demo.orders.3.enriched', 'demo.sc.01.order.placed.in'];
    const hue = (topic: string) => {
      const base = columns().get(topic)!.children[0] as THREE.Mesh;
      return (base.material as THREE.MeshBasicMaterial).color.getHSL({ h: 0, s: 0, l: 0 }).h;
    };
    const links = () => {
      const found: THREE.Line[] = [];
      drawn.scene?.traverse(object => { if (object instanceof THREE.Line && object.userData.link) found.push(object); });
      return found;
    };
    const particles = () => {
      const found: THREE.Mesh[] = [];
      drawn.scene?.traverse(object => { if (object instanceof THREE.Mesh && object.userData.flow) found.push(object); });
      return found;
    };
    const showInferred = async (user: ReturnType<typeof userEvent.setup>) => {
      await user.click(screen.getByRole('button', { name: /Layers ·/ }));
      await user.click(screen.getByRole('checkbox', { name: /Inferred links/ }));
    };

    it('gives a family one hue and another family another', async () => {
      stub.topics = pipeline;
      await renderPage();
      await waitFor(() => expect(columns().has('demo.sc.01.order.placed.in')).toBe(true));
      expect(hue('demo.orders.1.received')).toBeCloseTo(hue('demo.orders.3.enriched'), 5);
      expect(Math.abs(hue('demo.orders.1.received') - hue('demo.sc.01.order.placed.in'))).toBeGreaterThan(0.02);
    });

    it('lays a pipeline out along its stages on request, and keeps the choice in the URL', async () => {
      const user = userEvent.setup();
      stub.topics = pipeline;
      await renderPage();
      const spiral = columns().get('demo.orders.2.validated')!.position.clone();
      await user.click(screen.getByRole('button', { name: 'Pipeline lanes' }));
      await waitFor(() => expect(columns().get('demo.orders.2.validated')!.position.equals(spiral)).toBe(false));
      const [received, validated, enriched] = ['demo.orders.1.received', 'demo.orders.2.validated', 'demo.orders.3.enriched']
        .map(name => columns().get(name)!.position);
      expect(received.x).toBeLessThan(validated.x);
      expect(validated.x).toBeLessThan(enriched.x);
      expect(received.z).toBe(enriched.z);
      expect(window.location.search).toContain('sv.layout=lanes');
      // The spiral keeps its stable slots: switching back puts every column where it was.
      await user.click(screen.getByRole('button', { name: 'Spiral' }));
      await waitFor(() => expect(columns().get('demo.orders.2.validated')!.position.equals(spiral)).toBe(true));
    });

    it('draws links guessed from names only on their own layer, with what the target produced', async () => {
      const user = userEvent.setup();
      stub.topics = pipeline;
      await renderPage();
      expect(links().some(line => line.userData.link.inferred)).toBe(false);
      await showInferred(user);
      await waitFor(() => expect(links().length).toBeGreaterThan(0));
      const pairs = links().map(line => `${line.userData.link.from} -> ${line.userData.link.to}`);
      expect(pairs).toContain('demo.orders.1.received -> demo.orders.2.validated');
      expect(links().every(line => line.userData.link.inferred)).toBe(true);
      // Every target produced 30 records per bucket: particles travel each link.
      expect(particles().length).toBeGreaterThanOrEqual(links().length);
    });

    it('turns a link red when the count comparison over its two topics is critical', async () => {
      const user = userEvent.setup();
      stub.topics = pipeline;
      stub.metrics = [{
        id: 'gap', name: 'lost between received and validated', type: 'GAUGE', sql: null, description: '',
        warningThreshold: 5, criticalThreshold: 20, lastValue: 40, lastUpdateTime: 1, errorMessage: null,
        history: [], lastSummary: null, createTableSql: null, templateType: 'TOPIC_COUNT_DELTA',
        templateParams: { leftTopic: 'demo.orders.1.received', rightTopic: 'demo.orders.2.validated' },
        executionMode: null, labelTopic: null, labelFields: null, componentHistory: null,
      }];
      await renderPage();
      await showInferred(user);
      await waitFor(() => expect(links().length).toBeGreaterThan(0));
      const breached = links().find(line => line.userData.link.to === 'demo.orders.2.validated')!;
      expect(breached.userData.link.health).toBe('critical');
      expect((breached.material as THREE.LineDashedMaterial).color.getHex()).toBe(0xdd5b59);
      const calm = links().find(line => line.userData.link.to === 'demo.orders.3.enriched')!;
      expect(calm.userData.link.health).toBeNull();
    });

    it('lists the families in view and filters on one', async () => {
      const user = userEvent.setup();
      stub.topics = pipeline;
      await renderPage();
      const chip = await screen.findByRole('button', { name: /^Filter on family demo\.orders, 3 topics/ });
      await user.click(chip);
      expect(screen.getByRole('textbox', { name: 'Filter topics' })).toHaveValue('demo.orders');
      expect(chip).toHaveAttribute('aria-pressed', 'true');
    });
  });
});

