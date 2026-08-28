// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors

/*
 * Le câblage de la page Lineage.
 *
 * Deux décisions du dépôt vivent ici et nulle part ailleurs, et aucune n'est visible d'un module
 * pur — le graphe est du SVG calculé dans la page.
 *
 * La première : **une arête manquante et une dépendance inexistante se ressemblent sur un graphe.**
 * Quand le parseur de Flink n'a pas pu résoudre un statement, `LineageService` retombe sur un
 * balayage lexical et nomme le statement dans `warnings` ; la page doit le rendre, sans quoi le
 * graphe présente une supposition avec l'assurance d'une mesure.
 *
 * La seconde : la requête est **annulable et séquencée**. Basculer « Connected only » deux fois
 * rapidement suffisait à faire atterrir la réponse la plus lente par-dessus la plus récente — le
 * commentaire du code le dit, et rien ne le vérifiait.
 */

import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { createMemoryRouter, RouterProvider } from 'react-router-dom';
import axios from 'axios';

vi.mock('axios');
const mockedAxios = vi.mocked(axios, true);

const GRAPH = {
  nodes: [
    { id: 'topic:demo.orders.1.received', label: 'demo.orders.1.received', type: 'topic', messageCount: 120 },
    { id: 'table:orders_out', label: 'orders_out', type: 'table' },
  ],
  edges: [{ from: 'topic:demo.orders.1.received', to: 'table:orders_out', label: 'INSERT INTO' }],
  warnings: [],
};

function stubGraph(data: unknown) {
  mockedAxios.get.mockImplementation((url: string) =>
    url.startsWith('/api/lineage')
      ? Promise.resolve({ status: 200, data })
      : Promise.resolve({ status: 200, data: {} }));
  // La page appelle `axios.isCancel` sur le chemin d'erreur ; le mock d'axios ne le fournit pas.
  mockedAxios.isCancel.mockReturnValue(false);
}

async function renderPage() {
  const { default: Lineage } = await import('./Lineage');
  const { ToastProvider } = await import('../components/Toast');
  return render(
    <ToastProvider>
      <RouterProvider router={createMemoryRouter([{ path: '/', element: <Lineage /> }], { initialEntries: ['/'] })} />
    </ToastProvider>,
  );
}

const lineageCalls = () => mockedAxios.get.mock.calls.filter(c => String(c[0]).startsWith('/api/lineage'));

beforeEach(() => {
  vi.clearAllMocks();
});

describe('Lineage page', () => {
  it('draws what the server returned', async () => {
    stubGraph(GRAPH);
    await renderPage();

    // Un label de plus de 16 caractères est abrégé dans le SVG (`slice(0, 15) + '…'`), donc
    // l'assertion porte sur ce qui est réellement dessiné, pas sur ce que le serveur a envoyé.
    await waitFor(() => expect(screen.getAllByText('orders_out').length).toBeGreaterThan(0));
    expect(screen.getAllByText(/demo\.orders\.1\.r/).length).toBeGreaterThan(0);
  });

  /*
   * Le cas qui compte : le graphe a été construit en partie à la devinette, et il le dit. Sans
   * cette ligne, une arête absente se lit comme une dépendance qui n'existe pas.
   */
  it('renders the statements Flink could not resolve', async () => {
    stubGraph({
      ...GRAPH,
      warnings: ['Statement for job q-7 could not be parsed — its dependencies were read off the SQL text.'],
    });
    await renderPage();

    await waitFor(() => expect(screen.getByRole('status')).toBeInTheDocument());
    expect(screen.getByText(/could not be parsed/)).toBeInTheDocument();
  });

  it('says a graph is empty rather than showing a blank canvas', async () => {
    stubGraph({ nodes: [], edges: [], warnings: [] });
    await renderPage();

    await waitFor(() => expect(screen.getByText('No lineage data available')).toBeInTheDocument());
  });

  /*
   * Le type est écrit à la main : une réponse sans `nodes` ne doit pas emporter la page. C'est le
   * `?? []` du code, et c'est la classe de panne qui a tué la page Compare.
   */
  it('survives a response that carries none of the arrays the type promises', async () => {
    stubGraph({});
    await renderPage();

    await waitFor(() => expect(screen.getByText('No lineage data available')).toBeInTheDocument());
  });

  it('asks the server again, with the new scope, when Connected only is toggled', async () => {
    stubGraph(GRAPH);
    const user = userEvent.setup();
    await renderPage();

    await waitFor(() => expect(lineageCalls()).toHaveLength(1));
    expect(String(lineageCalls()[0][0])).toContain('connectedOnly=false');

    await user.click(screen.getByLabelText('Connected only'));

    await waitFor(() => expect(lineageCalls()).toHaveLength(2));
    expect(String(lineageCalls()[1][0])).toContain('connectedOnly=true');
  });

  it('reports a failed load instead of leaving the previous graph passing for current', async () => {
    mockedAxios.get.mockRejectedValue(new Error('engine offline'));
    mockedAxios.isCancel.mockReturnValue(false);
    await renderPage();

    await waitFor(() => expect(screen.getByText('No lineage data available')).toBeInTheDocument());
  });
});
