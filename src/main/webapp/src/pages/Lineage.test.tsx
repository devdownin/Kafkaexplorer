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
 * La seconde : la requête est **séquencée**. Basculer « Connected only » deux fois rapidement
 * suffisait à faire atterrir la réponse la plus lente par-dessus la plus récente — le commentaire du
 * code le dit, et rien ne le vérifiait. C'est `requestSeq` qui le garantit, et c'est ce qui est
 * couvert ici. L'`AbortController` posé à côté ne l'est pas, délibérément : le mock d'axios ignore
 * `signal`, donc un test l'affirmerait sans que rien ne l'exerce — exactement le genre de garantie
 * annoncée et non tenue que ce fichier existe pour supprimer.
 */

import { describe, it, expect, vi, beforeEach } from 'vitest';
import { act, render, screen, waitFor } from '@testing-library/react';
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

    // Pas de `getByRole('status')` : le conteneur de toasts en porte un en permanence
    // (`Toast.tsx`), donc le rôle désigne deux éléments dès qu'un avertissement est rendu.
    expect(await screen.findByText(/could not be parsed/)).toBeInTheDocument();
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

  /*
   * Sur erreur la page ne vide pas `data` : elle le signale. Les deux moitiés comptent, et
   * l'assertion « état vide » ne pouvait en distinguer aucune — un premier chargement réussi n'ayant
   * jamais eu lieu, elle passait aussi bien contre une page qui garde silencieusement un graphe
   * périmé.
   */
  it('reports a failed load instead of leaving the previous graph passing for current', async () => {
    let call = 0;
    mockedAxios.get.mockImplementation((url: string) => {
      if (!String(url).startsWith('/api/lineage')) return Promise.resolve({ status: 200, data: {} });
      call += 1;
      return call === 1
        ? Promise.resolve({ status: 200, data: GRAPH })
        : Promise.reject(new Error('engine offline'));
    });
    mockedAxios.isCancel.mockReturnValue(false);

    const user = userEvent.setup();
    await renderPage();
    await waitFor(() => expect(screen.getAllByText('orders_out').length).toBeGreaterThan(0));

    await user.click(screen.getByLabelText('Connected only'));

    expect(await screen.findByText('Failed to load lineage graph')).toBeInTheDocument();
    // Et le graphe précédent est toujours là — ce qui n'est acceptable que parce que l'échec, lui,
    // est dit : sans le toast au-dessus, l'écran affirmerait que ces arêtes sont à jour.
    expect(screen.getAllByText('orders_out').length).toBeGreaterThan(0);
    expect(screen.queryByText('No lineage data available')).not.toBeInTheDocument();
  });

  /*
   * `requestSeq` : deux bascules rapides, la réponse de la *première* arrivant en dernier. Elle doit
   * être jetée, sinon la portée affichée n'est pas celle de l'interrupteur.
   */
  it('drops a slow earlier response instead of letting it overwrite the newest scope', async () => {
    const named = (label: string) => ({
      nodes: [{ id: `table:${label}`, label, type: 'table' }],
      edges: [],
      warnings: [],
    });
    type Res = { status: number; data: unknown };
    let resolveAll!: (v: Res) => void;
    const all = new Promise<Res>(res => { resolveAll = res; });
    let resolveConnected!: (v: Res) => void;
    const connected = new Promise<Res>(res => { resolveConnected = res; });

    mockedAxios.get.mockImplementation((url: string) => {
      const u = String(url);
      if (!u.startsWith('/api/lineage')) return Promise.resolve({ status: 200, data: {} });
      return u.includes('connectedOnly=true') ? connected : all;
    });
    mockedAxios.isCancel.mockReturnValue(false);

    const user = userEvent.setup();
    await renderPage();
    await waitFor(() => expect(lineageCalls()).toHaveLength(1));

    await user.click(screen.getByLabelText('Connected only'));
    await waitFor(() => expect(lineageCalls()).toHaveLength(2));

    resolveConnected({ status: 200, data: named('scope_connected') });
    await waitFor(() => expect(screen.getAllByText('scope_connected').length).toBeGreaterThan(0));

    // La réponse périmée arrive maintenant. Elle porte un graphe parfaitement valide : ce qui la
    // disqualifie est son rang, pas son contenu.
    //
    // `act` plutôt qu'un `setTimeout(0)` : l'assertion qui suit est *négative*, donc un flush trop
    // court la ferait passer contre une page sur le point d'afficher `scope_all`. `act` vide les
    // microtâches et laisse React commiter, ce qu'un tick de macrotâche ne garantit pas.
    await act(async () => {
      resolveAll({ status: 200, data: named('scope_all') });
      await Promise.resolve();
      await Promise.resolve();
    });

    expect(screen.queryByText('scope_all')).not.toBeInTheDocument();
    expect(screen.getAllByText('scope_connected').length).toBeGreaterThan(0);
  });
});
