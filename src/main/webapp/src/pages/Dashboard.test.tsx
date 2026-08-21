// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors

/*
 * Le câblage de la colonne d'activité du tableau de bord.
 *
 * `topicActivity.test.ts` couvre la logique pure ; ce qui reste, et qui n'a de sens qu'ici, c'est
 * ce que la page *demande* et ce qu'elle affiche quand la réponse n'arrive pas. Deux choses en
 * particulier : elle ne mesure que les lignes affichées — sur un cluster de neuf cents topics,
 * demander la courbe de tous coûterait un balayage d'offsets que personne ne regarde — et une
 * lecture qui échoue laisse la raison à l'écran plutôt que des courbes plates, qui affirmeraient
 * « aucun trafic ».
 */

import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { createMemoryRouter, RouterProvider } from 'react-router-dom';
import axios from 'axios';
import Dashboard from './Dashboard';
import { ToastProvider } from '../components/Toast';
import { ConfirmProvider } from '../components/ui';
import type { TopicActivity, TopicActivityResponse } from '../api/types';

vi.mock('axios');
const mockedAxios = vi.mocked(axios, true);

const HOUR = 3_600_000;
const WINDOW_START = 1_700_000_000_000;
const topics = Array.from({ length: 30 }, (_, i) => `demo.topic.${String(i).padStart(2, '0')}`);

function series(topic: string, counts: number[], overrides: Partial<TopicActivity> = {}): TopicActivity {
  return {
    topic,
    windowStartMs: WINDOW_START,
    windowEndMs: WINDOW_START + counts.length * HOUR,
    bucketMs: HOUR,
    counts,
    total: counts.reduce((a, b) => a + b, 0),
    coveredFromMs: null,
    partitionsMeasured: 1,
    partitionsTotal: 1,
    available: true,
    note: null,
    ...overrides,
  };
}

const dashboard = {
  topics,
  topicSizes: Object.fromEntries(topics.map((t, i) => [t, (i + 1) * 100])),
  totalMessages: 46_500,
  tables: [],
  jobs: [],
  health: true,
  clusterName: 'Kafka cluster',
  bootstrapServers: 'kafka:9092',
  topicLastMessages: Object.fromEntries(topics.map(t => [t, WINDOW_START])),
};

function activityFor(names: string[], warnings: string[] = []): TopicActivityResponse {
  return {
    topics: Object.fromEntries(names.map((t, i) => [t, series(t, [0, i + 1, 2 * (i + 1), i + 1])])),
    windowStartMs: WINDOW_START,
    windowEndMs: WINDOW_START + 24 * HOUR,
    bucketMs: HOUR,
    buckets: 24,
    available: true,
    warnings,
  };
}

/** Ce que la page a demandé pour la colonne, ou `null` si elle n'a rien demandé. */
function activityRequests() {
  return mockedAxios.get.mock.calls.filter(call => call[0] === '/api/dashboard/activity');
}

function stubApi(activity: TopicActivityResponse | Error = activityFor(topics.slice(0, 25))) {
  mockedAxios.get.mockImplementation((url: string) => {
    if (url === '/api/dashboard') return Promise.resolve({ data: dashboard });
    if (url === '/api/dashboard/activity') {
      return activity instanceof Error ? Promise.reject(activity) : Promise.resolve({ data: activity });
    }
    return Promise.resolve({ data: {} });
  });
  mockedAxios.isCancel = ((e: unknown) => e instanceof Error && e.name === 'CanceledError') as never;
}

function renderPage() {
  return render(
    <ToastProvider>
      <ConfirmProvider>
        <RouterProvider router={createMemoryRouter([{ path: '/', element: <Dashboard /> }], { initialEntries: ['/'] })} />
      </ConfirmProvider>
    </ToastProvider>,
  );
}

beforeEach(() => {
  vi.clearAllMocks();
  localStorage.clear();
});

describe('Dashboard activity column', () => {
  it('measures the rows on screen, not the whole cluster', async () => {
    stubApi();
    renderPage();

    await waitFor(() => expect(activityRequests()).toHaveLength(1));
    const params = activityRequests()[0][1]?.params as { topics: string; windowMs: number; buckets: number };
    // Vingt-cinq lignes affichées sur trente topics : la lecture s'arrête à la page.
    expect(params.topics.split(',')).toHaveLength(25);
    expect(params.topics.split(',')).toEqual(topics.slice(0, 25));
    expect(params.windowMs).toBe(24 * HOUR);
    expect(params.buckets).toBe(24);
  });

  it('renders each curve with what it says, since an image alone says nothing', async () => {
    stubApi();
    renderPage();

    const curve = await screen.findByRole('img', { name: /produced in demo\.topic\.01/ });
    expect(curve.getAttribute('aria-label')).toMatch(/Peak/);
    // Et la ligne de portée dit la résolution plutôt que de laisser la courbe la suggérer.
    expect(screen.getByText(/One point per 1 h over the last 24 h/)).toBeInTheDocument();
  });

  it('states a topic it could not measure instead of drawing it flat', async () => {
    const response = activityFor(topics.slice(0, 25));
    response.topics[topics[0]] = {
      ...series(topics[0], []),
      available: false,
      total: 0,
      note: 'Offsets could not be read for any of the 3 partition(s): Connection refused',
    };
    stubApi(response);
    renderPage();

    const unmeasured = await screen.findByRole('img', { name: /could not be measured: Offsets could not be read/ });
    expect(unmeasured).toHaveTextContent('not measured');
    expect(screen.getByText(/1 of the 25 topics on this page could not be measured/)).toBeInTheDocument();
  });

  it('keeps the table when the read fails, and says why', async () => {
    stubApi(new Error('Network Error'));
    renderPage();

    await waitFor(() => expect(screen.getByText(/Activity could not be measured/)).toBeInTheDocument());
    // Le tableau n'est pas remplacé par une bannière : seule la colonne manque.
    expect(screen.getByText(topics[0])).toBeInTheDocument();
    expect(screen.queryByRole('img', { name: /produced in/ })).toBeNull();
  });

  it('reports a read the broker refused outright, once', async () => {
    stubApi({
      topics: {}, windowStartMs: 0, windowEndMs: 0, bucketMs: 3_600_000, buckets: 24,
      available: false, warnings: ['Offsets could not be read: Connection refused'],
    });
    renderPage();

    await waitFor(() => expect(screen.getByText(
      'Activity could not be measured: Offsets could not be read: Connection refused',
    )).toBeInTheDocument());
    // Et pas une seconde fois en dessous, sous forme d'avertissement.
    expect(screen.queryByText('Offsets could not be read: Connection refused')).toBeNull();
  });

  it('renders the server own warnings, a truncated read being invisible otherwise', async () => {
    stubApi(activityFor(topics.slice(0, 25), ['2 topic(s) were left out of this read.']));
    renderPage();

    expect(await screen.findByText('2 topic(s) were left out of this read.')).toBeInTheDocument();
  });

  it('asks for nothing when the column is switched off', async () => {
    const user = userEvent.setup();
    stubApi();
    renderPage();

    await waitFor(() => expect(activityRequests()).toHaveLength(1));
    await user.selectOptions(screen.getByLabelText('Activity'), 'off');

    await waitFor(() => expect(screen.queryByRole('columnheader', { name: /Activity/ })).toBeNull());
    expect(activityRequests()).toHaveLength(1);
    // Le choix survit à la page : la colonne coûte des allers-retours au broker.
    expect(localStorage.getItem('kse:dashboard-activity')).toBe('off');
  });

  it('re-reads on a window change rather than relabelling the previous series', async () => {
    const user = userEvent.setup();
    stubApi();
    renderPage();

    await waitFor(() => expect(activityRequests()).toHaveLength(1));
    await user.selectOptions(screen.getByLabelText('Activity'), '1h');

    await waitFor(() => expect(activityRequests()).toHaveLength(2));
    const params = activityRequests()[1][1]?.params as { windowMs: number; buckets: number };
    expect(params.windowMs).toBe(HOUR);
    expect(params.buckets).toBe(12);
  });
});
