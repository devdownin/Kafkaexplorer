// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors

/*
 * Le câblage de la page Topic Explorer.
 *
 * `components/topic/topicSearch.ts` couvre la logique pure — critère, corps de requête,
 * couverture, URL. Ce qui n'avait aucun test, c'est ce que la page en fait : quelles requêtes
 * partent, et lesquelles ne partent pas. Or c'est précisément là que vivent les deux règles les
 * plus faciles à casser sans que rien ne le dise, et les deux traversent une frontière de page :
 *
 *   - une URL qui porte un critère complet **exécute** la recherche à l'ouverture — c'est ainsi
 *     qu'un lien partagé se rejoue, et qu'un saut depuis Stream Flow atterrit sur ses messages ;
 *   - une URL qui ne porte qu'un instant **amorce sans lancer** — la sparkline du tableau de bord
 *     ouvre ce topic réglé sur le bucket cliqué sans dire quoi y chercher, et lancer serait
 *     impossible faute de critère. Un test de la page est le seul endroit où cette distinction se
 *     vérifie : `criteriaFromQuery` et `seedFromQuery` sont justes chacun de leur côté, c'est leur
 *     enchaînement qui décide.
 *
 * `samples` est un `TopicMessage[]` et a été un `string[]` — c'est ce changement qui a tué la page
 * Compare pendant des mois — donc les échantillons sont posés ici sous leur forme réelle.
 */

import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import { createMemoryRouter, RouterProvider } from 'react-router-dom';
import axios from 'axios';
import type { TopicDetailResponse, TopicMessage } from '../api/types';
import { previewOf } from '../components/topic/topicSearch';

vi.mock('axios');
const mockedAxios = vi.mocked(axios, true);

// jsdom ne fournit pas `document.queryCommandSupported`, que Monaco appelle au chargement du
// module : la page embarque un éditeur SQL, et l'import suffit à faire tomber le fichier de test.
// Rien de ce qui est vérifié ici n'a besoin d'un vrai éditeur.
vi.mock('@monaco-editor/react', () => ({
  __esModule: true,
  default: ({ value }: { value?: string }) => <textarea readOnly aria-label="sql editor" value={value ?? ''} />,
  useMonaco: () => null,
}));
vi.mock('../monaco-setup', () => ({}));

vi.mock('../catalogStore', () => ({
  useCatalog: () => ({ topics: ['demo.orders.1.received'], tables: [], internalPrefix: '' }),
}));

const TOPIC = 'demo.orders.1.received';
const NOW = 1_760_000_000_000;

function message(overrides: Partial<TopicMessage> = {}): TopicMessage {
  return {
    partition: 0, offset: 12, timestamp: NOW,
    key: 'ORD-1042', value: '{"id":"ORD-1042","status":"NEW"}',
    headers: { 'correlation-id': 'ORD-1042' },
    valueBytes: 32, truncated: false,
    ...overrides,
  };
}

function detail(): TopicDetailResponse {
  return {
    topic: {
      name: TOPIC, partitions: 3,
      minOffsets: { 0: 0, 1: 0, 2: 0 }, maxOffsets: { 0: 40, 1: 40, 2: 40 },
      detectedFormat: 'JSON', estimatedSize: 120,
    },
    format: 'JSON',
    schema: { id: 'STRING', status: 'STRING' },
    ddl: null,
    samples: [message()],
  };
}

function stubApi() {
  mockedAxios.get.mockImplementation((url: string) => {
    if (url.startsWith(`/api/topic/${encodeURIComponent(TOPIC)}?`)) {
      return Promise.resolve({ status: 200, data: detail() });
    }
    return Promise.resolve({ status: 200, data: {} });
  });
  mockedAxios.post.mockResolvedValue({
    status: 200,
    data: {
      hits: [message({ offset: 77 })],
      scanned: 5000, matched: 1, stopReason: 'MAX_HITS', exhausted: false,
      nextCursor: {}, warnings: [],
    },
  });
}

async function renderAt(search: string) {
  const { default: TopicExplorer } = await import('./TopicExplorer');
  const { ToastProvider } = await import('../components/Toast');
  const { ConfirmProvider } = await import('../components/ui');
  return render(
    <ToastProvider>
      <ConfirmProvider>
        <RouterProvider
          router={createMemoryRouter(
            [{ path: '/topic/:name', element: <TopicExplorer /> }],
            { initialEntries: [`/topic/${encodeURIComponent(TOPIC)}${search}`] },
          )}
        />
      </ConfirmProvider>
    </ToastProvider>,
  );
}

const searchCalls = () => mockedAxios.post.mock.calls.filter(c => String(c[0]).includes('/search'));

beforeEach(() => {
  vi.clearAllMocks();
  localStorage.clear();
  stubApi();
});

describe('Topic Explorer page', () => {
  it('loads the topic named by the route', async () => {
    await renderAt('');

    await waitFor(() => expect(screen.getByText(TOPIC)).toBeInTheDocument());
    expect(mockedAxios.get).toHaveBeenCalledWith(
      expect.stringContaining(`/api/topic/${encodeURIComponent(TOPIC)}?readMode=`),
    );
    // Aucun critère dans l'URL : rien ne doit partir en recherche.
    expect(searchCalls()).toHaveLength(0);
  });

  it('runs the search a shared link carries', async () => {
    await renderAt('?mode=CONTAINS&q=ORD-1042&dir=NEWEST');

    await waitFor(() => expect(searchCalls()).toHaveLength(1));
    const [, body] = searchCalls()[0];
    expect(body).toMatchObject({ mode: 'CONTAINS', query: 'ORD-1042' });
  });

  /*
   * Le lien de la sparkline. Il ne porte pas de critère — la recherche n'a pas de critère
   * « tout », par décision — donc la page pose l'instant et attend. Lancer ici enverrait une
   * requête que le serveur ne peut pas satisfaire ; ne rien poser perdrait le seul renseignement
   * que le lien transportait.
   */
  it('primes the form from an instant-only link without running anything', async () => {
    await renderAt(`?start=TIMESTAMP&at=${NOW}`);

    await waitFor(() => expect(screen.getByText(TOPIC)).toBeInTheDocument());
    expect(searchCalls()).toHaveLength(0);
  });

  it('a link carrying only a record reads that record and runs no search', async () => {
    await renderAt('?record=0:12');

    await waitFor(() => expect(mockedAxios.get).toHaveBeenCalledWith(
      `/api/topic/${encodeURIComponent(TOPIC)}/record`,
      { params: { partition: 0, offset: 12 } },
    ));
    expect(searchCalls()).toHaveLength(0);
  });

  /*
   * `samples` est typé à la main comme tout le reste de `api/types.ts`. Une réponse qui n'en porte
   * pas — une version antérieure, une erreur renvoyée en 200 — ne doit pas emporter la page : le
   * même défaut, sur le même champ, avait tué la page Compare.
   */
  it('survives a topic response with no samples at all', async () => {
    mockedAxios.get.mockImplementation((url: string) => {
      if (url.startsWith(`/api/topic/${encodeURIComponent(TOPIC)}?`)) {
        return Promise.resolve({ status: 200, data: { ...detail(), samples: undefined } });
      }
      return Promise.resolve({ status: 200, data: {} });
    });

    await renderAt('');

    await waitFor(() => expect(screen.getByText(TOPIC)).toBeInTheDocument());
  });

  /*
   * La cellule d'aperçu de la table porte son texte en `title`.
   *
   * Elle est en `max-w-0 w-full`, donc elle prend ce qui reste de la table et rogne : mesuré par
   * `layout-probe --detail` à 704 px pour 1 711 px de contenu, une quarantaine de caractères sur
   * les 240 que garde `previewOf`. Le reste s'obtient en cliquant la ligne, et c'est bien ce que
   * la page propose — mais pas en survolant, et un survol est ce qu'on fait quand on balaie
   * cinquante lignes. jsdom ne dispose rien, donc ce cas ne mesure pas le rognage : il épingle
   * l'attribut, qui est ce qui disparaît en silence quand on réécrit une ligne de classes.
   */
  it('carries the clipped preview in a title, so hovering shows it without selecting the row', async () => {
    const long = JSON.stringify({ id: 'ORD-1042', payload: 'x'.repeat(400) });
    mockedAxios.get.mockImplementation((url: string) => {
      if (url.startsWith(`/api/topic/${encodeURIComponent(TOPIC)}?`)) {
        return Promise.resolve({
          status: 200,
          data: { ...detail(), samples: [message({ value: long })] },
        });
      }
      return Promise.resolve({ status: 200, data: {} });
    });

    await renderAt('');
    await waitFor(() => expect(screen.getByText(TOPIC)).toBeInTheDocument());

    const table = await screen.findByRole('table');
    const cell = [...table.querySelectorAll('td')].find(td => td.classList.contains('max-w-0'));
    expect(cell).toBeTruthy();
    expect(cell).toHaveAttribute('title', previewOf(long));
    // Et l'aperçu est bien tronqué : un `title` égal au texte entier dirait que rien n'est rogné.
    expect(previewOf(long).length).toBeLessThan(long.length);
  });
});
