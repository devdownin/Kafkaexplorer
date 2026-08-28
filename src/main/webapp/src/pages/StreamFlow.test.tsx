// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors

/*
 * Le câblage de la page Stream Flow.
 *
 * `pages/streamFlow.ts` couvre déjà la logique pure — validation, portée des chemins, aller-retour
 * d'URL, historique, export, lecture de la réponse, mise en page, ajustement. Ce qui n'avait aucun
 * test, c'est ce que la page en fait, et trois de ces décisions sont écrites en toutes lettres dans
 * le code sans que rien ne les vérifie :
 *
 *   - **un lien partagé se rejoue à l'ouverture** — « c'est ce qu'on partage », dit le commentaire,
 *     et c'est ce qui fait qu'une trace collée dans un ticket vaut quelque chose ;
 *   - **sans corps lisible, la page retombe sur l'appel non streamé**, explicitement pour un
 *     navigateur ancien ou un environnement de test : la trace est la même, sans les résultats
 *     intermédiaires. Ce chemin-là n'est jamais pris en production, donc il ne peut être vérifié
 *     que par un test ;
 *   - **un critère refusé est rendu avec la raison du serveur.** `StreamFlowController` répond 400
 *     avec un corps `{"message": …}` précisément parce que `server.error.include-message` vaut
 *     `never` — une regex invalide doit dire laquelle, pas « échec ».
 */

import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import { createMemoryRouter, RouterProvider } from 'react-router-dom';
import axios from 'axios';

vi.mock('axios');
const mockedAxios = vi.mocked(axios, true);

vi.mock('../catalogStore', () => ({
  useCatalog: () => ({ topics: ['demo.orders.1.received', 'demo.orders.2.validated'], tables: [], internalPrefix: '' }),
}));

const NOW = 1_760_000_000_000;

const FLOW = {
  nodes: [
    { topic: 'demo.orders.1.received', occurrences: 1, firstTimestamp: NOW },
    { topic: 'demo.orders.2.validated', occurrences: 1, firstTimestamp: NOW + 1200 },
  ],
  edges: [{ from: 'demo.orders.1.received', to: 'demo.orders.2.validated', latencyMs: 1200 }],
  hits: [
    { topic: 'demo.orders.1.received', occurrences: 1, firstTimestamp: NOW, lastTimestamp: NOW,
      partition: 0, offset: 12, key: 'ORD-1042', preview: '{"id":"ORD-1042"}', latencyFromPreviousMs: null, occurrencesCapped: false },
    { topic: 'demo.orders.2.validated', occurrences: 1, firstTimestamp: NOW + 1200, lastTimestamp: NOW + 1200,
      partition: 1, offset: 7, key: 'ORD-1042', preview: '{"id":"ORD-1042"}', latencyFromPreviousMs: 1200, occurrencesCapped: false },
  ],
  stats: {
    topicsInScope: 2, topicsScanned: 2, topicsSkipped: 0, topicsFailed: 0,
    skippedTopics: [], failedTopics: [], messagesScanned: 400, matches: 2,
    durationMs: 900, truncated: false, stopReason: 'COMPLETE', maxMessagesPerTopic: 100, timeWindowMs: null,
  },
  warnings: [],
};

/** Pas de corps lisible : c'est le cas que la page traite explicitement, et jsdom le produit. */
function stubFetchWithoutBody(ok = true, status = 200, body: unknown = null) {
  vi.stubGlobal('fetch', vi.fn().mockResolvedValue({
    ok, status, body: null,
    json: () => Promise.resolve(body),
  }));
}

async function renderAt(search: string) {
  const { default: StreamFlow } = await import('./StreamFlow');
  const { ToastProvider } = await import('../components/Toast');
  const { ConfirmProvider } = await import('../components/ui');
  return render(
    <ToastProvider>
      <ConfirmProvider>
        <RouterProvider
          router={createMemoryRouter([{ path: '/stream-flow', element: <StreamFlow /> }],
            { initialEntries: [`/stream-flow${search}`] })}
        />
      </ConfirmProvider>
    </ToastProvider>,
  );
}

const traceCalls = () => mockedAxios.post.mock.calls.filter(c => String(c[0]) === '/api/stream-flow');

beforeEach(() => {
  vi.clearAllMocks();
  localStorage.clear();
  mockedAxios.isCancel.mockReturnValue(false);
});

afterEach(() => {
  vi.unstubAllGlobals();
});

describe('Stream Flow page', () => {
  it('runs nothing on an empty form', async () => {
    stubFetchWithoutBody();
    mockedAxios.post.mockResolvedValue({ status: 200, data: FLOW });
    await renderAt('');

    await waitFor(() => expect(screen.getByText(/Stream Flow/i)).toBeInTheDocument());
    expect(traceCalls()).toHaveLength(0);
    expect(vi.mocked(fetch)).not.toHaveBeenCalled();
  });

  /*
   * Le lien partagé. Il porte le critère, et l'ouvrir doit produire la trace — sans quoi le
   * destinataire reçoit un formulaire pré-rempli et doit appuyer lui-même, ce qui n'est pas ce
   * que « partager une trace » veut dire.
   */
  it('replays the trace a shared link carries, and falls back when the body is not readable', async () => {
    stubFetchWithoutBody();
    mockedAxios.post.mockResolvedValue({ status: 200, data: FLOW });
    await renderAt('?key=ORD-1042&exact=1');

    // Le flux est tenté d'abord…
    await waitFor(() => expect(vi.mocked(fetch)).toHaveBeenCalledWith('/api/stream-flow/stream', expect.anything()));
    // …puis, faute de corps lisible, la même trace passe par l'appel non streamé.
    await waitFor(() => expect(traceCalls()).toHaveLength(1));
    expect(traceCalls()[0][1]).toMatchObject({ messageKey: 'ORD-1042' });

    // Et le résultat est rendu : deux topics, la latence du saut.
    await waitFor(() => expect(screen.getAllByText(/demo\.orders\.2\.validated/).length).toBeGreaterThan(0));
  });

  /*
   * `server.error.include-message` vaut `never`, donc le contrôleur met la raison dans le corps.
   * Une regex invalide doit dire laquelle : c'est la seule chose que l'opérateur peut corriger.
   */
  it('shows the server’s reason for a refused criterion', async () => {
    // Un critère que la page accepte : sinon elle le refuserait d'elle-même et le chemin du
    // serveur ne serait jamais emprunté. Ce qui est vérifié ici est le report de SA raison à lui.
    stubFetchWithoutBody(false, 400, { message: 'Invalid regular expression: Unclosed character class' });
    await renderAt('?key=ORD-1042');

    // `getAllBy…` : la raison peut apparaître deux fois — le panneau d'erreur et le toast.
    await waitFor(() =>
      expect(screen.getAllByText(/Unclosed character class/).length).toBeGreaterThan(0));
  });

  /*
   * « Rien trouvé » doit répéter ce qui a été lu : une trace est un scan borné, et un « non
   * trouvé » sans portée se lit comme « ce message n'existe pas ».
   */
  it('a trace that found nothing states what it scanned', async () => {
    stubFetchWithoutBody();
    mockedAxios.post.mockResolvedValue({
      status: 200,
      data: { ...FLOW, nodes: [], edges: [], hits: [], stats: { ...FLOW.stats, matches: 0 } },
    });
    await renderAt('?key=ORD-9999');

    await waitFor(() => expect(traceCalls()).toHaveLength(1));
    // La couverture reste à l'écran : topics lus, messages lus.
    await waitFor(() => expect(screen.getAllByText(/2 topics|400/).length).toBeGreaterThan(0));
  });
});
