// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors

/*
 * Le câblage de la page Cluster.
 *
 * Elle est petite et n'a presque pas de logique pure, ce qui est exactement pourquoi elle n'avait
 * pas de test : il n'y avait rien à extraire dans un module. Ce qu'elle décide est pourtant dans
 * son câblage, et deux de ces décisions valent d'être pinnées.
 *
 * La première est une règle du dépôt : un groupe que l'application a créé pour ses propres lectures
 * est **marqué, pas caché**. Cette page décrit le cluster ; un groupe que l'explorer y a laissé en
 * fait partie, et ce que l'opérateur ne doit pas avoir à faire, c'est deviner lesquelles des lignes
 * sont les siennes.
 *
 * La seconde est un choix de robustesse écrit dans le code : `GET /api/cluster` est tenté avec un
 * `.catch(() => null)` pour qu'un cluster en mode Zookeeper — sans quorum KRaft ni listing de
 * groupes — n'emporte pas la vue des configurations, qui est le contenu principal de la page.
 */

import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import { createMemoryRouter, RouterProvider } from 'react-router-dom';
import axios from 'axios';

vi.mock('axios');
const mockedAxios = vi.mocked(axios, true);

const CONFIGS = {
  'offsets.topic.replication.factor': '1',
  'offsets.topic.num.partitions': '50',
  'num.network.threads': '3',
};

const DETAILS = {
  groups: [
    { groupId: 'orders-api', type: 'CONSUMER', state: 'STABLE' },
    { groupId: 'kafka-explorer-metadata-9f2c', type: 'CLASSIC', state: 'EMPTY', explorer: true },
  ],
};

function stubApi(details: unknown | null) {
  mockedAxios.get.mockImplementation((url: string) => {
    if (url === '/api/cluster/configs') return Promise.resolve({ status: 200, data: CONFIGS });
    if (url === '/api/cluster') {
      return details === null
        ? Promise.reject(new Error('no quorum on this cluster'))
        : Promise.resolve({ status: 200, data: details });
    }
    return Promise.resolve({ status: 200, data: {} });
  });
}

async function renderPage() {
  const { default: Cluster } = await import('./Cluster');
  const { ToastProvider } = await import('../components/Toast');
  return render(
    <ToastProvider>
      <RouterProvider router={createMemoryRouter([{ path: '/', element: <Cluster /> }], { initialEntries: ['/'] })} />
    </ToastProvider>,
  );
}

beforeEach(() => {
  vi.clearAllMocks();
});

describe('Cluster page', () => {
  it('marks the application’s own groups instead of hiding them, and counts them', async () => {
    stubApi(DETAILS);
    await renderPage();

    await waitFor(() => expect(screen.getByText('orders-api')).toBeInTheDocument());
    // La ligne est là — cette page décrit le cluster, pas seulement ce qui n'est pas à nous.
    expect(screen.getByText('kafka-explorer-metadata-9f2c')).toBeInTheDocument();
    expect(screen.getByText('this app')).toBeInTheDocument();
    expect(screen.getByText(/1 of them this application/)).toBeInTheDocument();
  });

  it('says nothing about ownership when no group is ours', async () => {
    stubApi({ groups: [{ groupId: 'orders-api', type: 'CONSUMER', state: 'STABLE' }] });
    await renderPage();

    await waitFor(() => expect(screen.getByText('orders-api')).toBeInTheDocument());
    expect(screen.queryByText('this app')).toBeNull();
    expect(screen.queryByText(/of them this application/)).toBeNull();
  });

  /*
   * Le `.catch(() => null)` sur `/api/cluster` est un choix, pas un oubli : un cluster en mode
   * Zookeeper n'a ni quorum KRaft ni listing de groupes, et la vue des configurations — le contenu
   * principal de la page — ne doit pas disparaître avec eux.
   */
  it('keeps the configuration view when the cluster details cannot be read', async () => {
    stubApi(null);
    await renderPage();

    await waitFor(() => expect(screen.getByText('offsets.topic.replication.factor')).toBeInTheDocument());
    // Ni quorum ni groupes : les sections qui en dépendent s'effacent, la page reste.
    expect(screen.queryByText('Client Groups')).toBeNull();
    expect(screen.queryByRole('alert')).toBeNull();
  });

  it('an empty group list is a statement about the cluster, not an absent section', async () => {
    stubApi({ groups: [] });
    await renderPage();

    await waitFor(() => expect(screen.getByText('Client Groups')).toBeInTheDocument());
    expect(screen.getByText(/No registered client groups/)).toBeInTheDocument();
  });

  it('reports a failure to read the configurations rather than rendering an empty page', async () => {
    mockedAxios.get.mockImplementation((url: string) =>
      url === '/api/cluster/configs'
        ? Promise.reject(new Error('broker unreachable'))
        : Promise.resolve({ status: 200, data: {} }));
    await renderPage();

    await waitFor(() => expect(screen.getByText('Failed to load cluster configuration')).toBeInTheDocument());
  });
});
