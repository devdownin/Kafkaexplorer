// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors

/*
 * Le câblage de l'écran MCP.
 *
 * Ce qui est pinné ici, ce sont les cinq affirmations que la page fait à l'opérateur et qu'un
 * refactor casserait sans que rien d'autre ne le voie : un outil masqué reste dans la table avec
 * son motif ; une valeur non mesurée se rend « non mesuré » et non zéro ; une couverture partielle
 * porte son pictogramme ; le bandeau « ÉCRITURE ACTIVE » n'apparaît que si un outil mutant est
 * réellement enregistré ; et un serveur désactivé se dit désactivé plutôt que vide.
 */

import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { createMemoryRouter, RouterProvider } from 'react-router-dom';
import axios from 'axios';
import Mcp from './Mcp';

vi.mock('axios');
const mockedAxios = vi.mocked(axios, true);

const WINDOW = {
  requestedWindowMs: 86_400_000,
  oldestCallAt: null,
  callsHeld: 2,
  ringCapacity: 2000,
  droppedFromRing: 0,
  auditPersisted: false,
};

const STATUS = {
  enabled: true,
  transports: ['HTTP (streamable)'],
  endpoint: 'http://localhost:8080/mcp',
  readonly: true,
  writeSurfaceOpen: false,
  mutatingToolsExposed: [] as string[],
  authentication: 'none — anything that can reach the endpoint can call it (OAuth 2.1 is phase 5)',
  topicScope: ['*'],
  groupScope: ['*'],
  tryItEnabled: false,
};

const CATALOG = {
  tools: [
    {
      name: 'kex_list_topics',
      category: 'EXPLORATION',
      description: 'List the Kafka topics.',
      visibility: { state: 'EXPOSED', reason: null },
      defaultBudgetMs: 20_000,
      hardMaxRecords: 50,
      hardMaxRows: 1000,
      hardMaxBytes: 1_048_576,
      calls: 3,
      denied: 0,
      p95Ms: { value: null, measured: false, reason: '3 call(s) in this window; 20 are needed' },
    },
    {
      name: 'kex_produce_message',
      category: 'WRITE',
      description: 'Produce a message.',
      visibility: { state: 'HIDDEN', reason: 'read-only mode (explorer.mcp.readonly=true)' },
      defaultBudgetMs: 20_000,
      hardMaxRecords: 50,
      hardMaxRows: 1000,
      hardMaxBytes: 1_048_576,
      calls: 0,
      denied: 0,
      p95Ms: { value: null, measured: false, reason: 'not called in this window' },
    },
  ],
  observedWindow: WINDOW,
};

const STATS = {
  calls: 2,
  denied: 1,
  errors: 0,
  p50Ms: { value: 12, measured: true, reason: null },
  p95Ms: { value: null, measured: false, reason: '2 call(s) in this window' },
  maxMs: { value: 18, measured: true, reason: null },
  slowestTool: 'kex_trace_key',
  recordsScanned: { value: null, measured: false, reason: 'no call reported a record count' },
  outputBytes: { value: 900, measured: true, reason: null },
  activeIdentities: 1,
  topTools: [{ tool: 'kex_list_topics', calls: 2 }],
  denialsByCode: [{ jsonRpcErrorCode: -32041, guard: 'SCOPE', count: 1 }],
  observedWindow: WINDOW,
};

const CALLS = [
  {
    correlationId: 'a3f9',
    startedAt: '2026-09-07T14:02:31.000Z',
    durationMs: 18_400,
    origin: 'AGENT',
    identity: 'svc-sre@corp',
    clientInfo: 'claude-code/1.4.2',
    tool: 'kex_trace_key',
    redactedParams: {},
    outcome: 'OK',
    jsonRpcErrorCode: null,
    deniedByGuard: null,
    recordsScanned: { value: 8400, measured: true, reason: null },
    stopReason: 'TIME_BUDGET',
    partialCoverage: true,
    truncated: false,
    outputBytes: { value: 900, measured: true, reason: null },
  },
];

function mockApi(
  over: { status?: unknown; catalog?: unknown; overrides?: unknown; clients?: unknown } = {},
) {
  mockedAxios.get.mockImplementation((url: string) => {
    if (url.startsWith('/api/mcp/status')) return Promise.resolve({ data: over.status ?? STATUS });
    if (url.startsWith('/api/mcp/catalog/client-config')) {
      return Promise.resolve({
        data: { client: 'claude-code', format: 'shell', snippet: 'claude mcp add …', tokenHint: null },
      });
    }
    if (url.startsWith('/api/mcp/catalog')) return Promise.resolve({ data: over.catalog ?? CATALOG });
    if (url.startsWith('/api/mcp/stats')) return Promise.resolve({ data: STATS });
    if (url.startsWith('/api/mcp/calls')) return Promise.resolve({ data: CALLS });
    if (url.startsWith('/api/mcp/clients')) return Promise.resolve({ data: over.clients ?? [] });
    if (url.startsWith('/api/mcp/overrides')) return Promise.resolve({ data: over.overrides ?? [] });
    return Promise.reject(new Error(`unexpected ${url}`));
  });
}

function renderAt(path = '/mcp') {
  const router = createMemoryRouter([{ path: '/mcp', element: <Mcp /> }], { initialEntries: [path] });
  return render(<RouterProvider router={router} />);
}

beforeEach(() => {
  vi.clearAllMocks();
  mockApi();
});

describe("l'onglet Catalogue", () => {
  it('affiche un outil masqué avec son motif, jamais absent de la table', async () => {
    // Sans la ligne, un opérateur qui cherche pourquoi son agent ne voit pas l'outil n'a aucune
    // piste et va lire le YAML.
    renderAt();

    expect(await screen.findByText('kex_produce_message')).toBeInTheDocument();
    expect(screen.getByText('Masqué ⓘ')).toBeInTheDocument();
  });

  it("rend « non mesuré » pour un p95 sur trop peu d'appels, jamais 0", async () => {
    renderAt();

    await screen.findByText('kex_list_topics');
    expect(screen.getAllByText('non mesuré').length).toBeGreaterThan(0);
  });

  it("n'offre pas « Essayer » quand le serveur le refuserait, et dit pourquoi", async () => {
    // Masqué plutôt que désactivé : un bouton qui a l'air disponible et répond 403 apprend à se
    // méfier de l'écran, sur la page dont le sujet est de dire ce qui est réellement en vigueur.
    renderAt();

    expect(await screen.findByText('kex_list_topics')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Essayer' })).not.toBeInTheDocument();
    expect(screen.getByText(/allow-try-it/)).toBeInTheDocument();
  });

  it('offre « Essayer » une fois le réglage posé', async () => {
    mockApi({ status: { ...STATUS, tryItEnabled: true } });
    renderAt();

    expect(await screen.findAllByRole('button', { name: 'Essayer' })).not.toHaveLength(0);
  });

  it("affiche LECTURE SEULE tant qu'aucun outil mutant n'est exposé", async () => {
    renderAt();

    expect(await screen.findByText('LECTURE SEULE')).toBeInTheDocument();
    expect(screen.queryByText('ÉCRITURE ACTIVE')).not.toBeInTheDocument();
  });

  it("bascule sur ÉCRITURE ACTIVE dès qu'un outil mutant est réellement enregistré", async () => {
    mockApi({ status: { ...STATUS, writeSurfaceOpen: true, readonly: false, mutatingToolsExposed: ['kex_produce_message'] } });
    renderAt();

    expect(await screen.findByText('ÉCRITURE ACTIVE')).toBeInTheDocument();
  });
});

describe("l'onglet Supervision", () => {
  it("marque d'un avertissement une réponse que l'agent a reçue incomplète", async () => {
    // C'est la colonne pour laquelle l'écran existe : là où naissent les conclusions fausses.
    renderAt('/mcp?tab=supervision');

    expect(await screen.findByText('kex_trace_key')).toBeInTheDocument();
    expect(screen.getByText('⚠')).toBeInTheDocument();
  });

  it('restaure les filtres depuis l\'URL', async () => {
    renderAt('/mcp?tab=supervision&outcome=DENIED');

    await waitFor(() => expect(mockedAxios.get).toHaveBeenCalled());
    const feedCall = mockedAxios.get.mock.calls.find(([url]) => String(url).startsWith('/api/mcp/calls'));
    expect(String(feedCall?.[0])).toContain('outcome=DENIED');
  });

  it('montre les refus à côté des succès, avec le code et la garde', async () => {
    renderAt('/mcp?tab=supervision');

    expect(await screen.findByText(/dont 1 refusés/)).toBeInTheDocument();
    expect(screen.getByText('SCOPE')).toBeInTheDocument();
  });

  it("compte les erreurs à part des refus, sur la même carte", async () => {
    // Trois états, trois nombres : un appel tombé pour une raison qu'aucune garde n'a choisie est
    // le seul des trois qui ne se règle pas dans le YAML, et il disparaissait entre les deux autres.
    renderAt('/mcp?tab=supervision');

    expect(await screen.findByText(/0 en erreur/)).toBeInTheDocument();
  });

  it('nomme les outils les plus appelés', async () => {
    renderAt('/mcp?tab=supervision');

    expect(await screen.findByText('Outils les plus appelés')).toBeInTheDocument();
  });
});

describe('le serveur désactivé', () => {
  it('se dit désactivé plutôt que de rendre un écran vide', async () => {
    // « aucun appel », « console désactivée » et « journal illisible » sont trois états distincts,
    // et l'état vide doit les distinguer.
    mockApi({ status: { ...STATUS, enabled: false, endpoint: null, transports: [] } });
    renderAt();

    expect(await screen.findByText('Le serveur MCP est désactivé')).toBeInTheDocument();
    expect(screen.getByText(/explorer\.mcp\.enabled=false/)).toBeInTheDocument();
  });
});

describe('le bandeau des dérogations', () => {
  it("ne s'affiche pas quand aucune dérogation ne tient", async () => {
    renderAt();

    await screen.findByText('kex_list_topics');
    expect(screen.queryByText(/dérogation est active|dérogations sont actives/)).toBeNull();
  });

  it('nomme chaque dérogation, son auteur et son âge, et ne se referme pas', async () => {
    // Le pire mode de défaillance du commutateur est une dérogation qui survit à son incident ;
    // un bandeau refermable serait fermé le premier jour et la dérogation resterait.
    mockApi({
      overrides: [
        {
          kind: 'TOOL', target: 'kex_sql_query', actor: 'alice', reason: 'une boucle folle',
          since: '2026-09-08T05:00:00Z', ageMs: 3_600_000,
        },
      ],
    });
    renderAt();

    expect(await screen.findByText('Une dérogation est active')).toBeInTheDocument();
    expect(screen.getByText(/kex_sql_query.*alice.*depuis 1 h.*boucle folle/)).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /fermer/i })).toBeNull();
  });

  it("dit qu'elles ne s'effacent pas d'elles-mêmes", async () => {
    mockApi({
      overrides: [
        {
          kind: 'READONLY', target: null, actor: 'bob', reason: 'incident',
          since: '2026-09-08T05:00:00Z', ageMs: 60_000,
        },
      ],
    });
    renderAt();

    expect(await screen.findByText(/ne s'effacent pas d'elles-mêmes/)).toBeInTheDocument();
  });
});

describe('les leviers du commutateur', () => {
  const CLIENT = { identity: 'svc-sre@corp', clientInfo: 'claude-code/1.4.2', calls: 12 };

  it('demande une raison avant de couper un outil, et rien avant de le rétablir', async () => {
    // La raison est ce qui, dans le bandeau, fait lever la dérogation ; lever n'a rien à expliquer.
    const user = userEvent.setup();
    renderAt();
    await screen.findByText('kex_list_topics');

    await user.click(screen.getByRole('button', { name: 'Couper' }));

    expect(screen.getByText('Couper kex_list_topics')).toBeInTheDocument();
    // Rien n'est envoyé tant que la raison est vide.
    expect(screen.getByRole('button', { name: 'Couper' })).toBeDisabled();
    expect(mockedAxios.post).not.toHaveBeenCalled();
  });

  it("coupe l'outil avec l'auteur et la raison saisis", async () => {
    const user = userEvent.setup();
    mockedAxios.post.mockResolvedValue({ data: { applied: true, message: 'ok', override: null } });
    renderAt();
    await screen.findByText('kex_list_topics');

    await user.click(screen.getByRole('button', { name: 'Couper' }));
    await user.type(screen.getByLabelText('Pourquoi ?'), 'boucle folle');
    await user.type(screen.getByLabelText('Qui ? (déclaré, non vérifié)'), 'alice');
    await user.click(screen.getByRole('button', { name: 'Couper' }));

    expect(mockedAxios.post).toHaveBeenCalledWith(
      '/api/mcp/toggle/tool/kex_list_topics',
      { enable: true, actor: 'alice', reason: 'boucle folle' },
    );
  });

  it("offre la quarantaine sur chaque identité — le levier le plus utile en incident", async () => {
    // « Cet agent boucle » se règle en coupant l'identité, pas l'outil : il en appellerait un autre.
    mockApi({ clients: [CLIENT] });
    renderAt('/mcp?tab=supervision');

    // L'identité apparaît aussi dans le flux d'appels ; c'est le levier qui est nouveau.
    expect(await screen.findByRole('button', { name: 'Quarantaine' })).toBeInTheDocument();
    expect(screen.getAllByText('svc-sre@corp').length).toBeGreaterThan(0);
  });

  it('propose de relâcher une identité déjà en quarantaine plutôt que de la remettre', async () => {
    mockApi({
      clients: [CLIENT],
      overrides: [{
        kind: 'QUARANTINE', target: 'svc-sre@corp', actor: 'alice', reason: 'exfiltration',
        since: '2026-09-08T05:00:00Z', ageMs: 60_000,
      }],
    });
    renderAt('/mcp?tab=supervision');

    expect(await screen.findByRole('button', { name: 'Relâcher' })).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Quarantaine' })).toBeNull();
  });

  it('propose le verrou lecture seule à côté de l’état qu’il change', async () => {
    renderAt();

    expect(await screen.findByRole('button', { name: 'Verrouiller' })).toBeInTheDocument();
  });

  it('propose de lever le verrou quand il est posé', async () => {
    mockApi({
      overrides: [{
        kind: 'READONLY', target: null, actor: 'alice', reason: 'incident',
        since: '2026-09-08T05:00:00Z', ageMs: 60_000,
      }],
    });
    renderAt();

    expect(await screen.findByRole('button', { name: 'Lever le verrou' })).toBeInTheDocument();
  });

  it('frappe un jeton pour un outil qui exige une approbation, et ne le montre qu’une fois', async () => {
    // Sans lui l'outil est inappelable : le badge le disait et rien ne le débloquait.
    const user = userEvent.setup();
    mockApi({
      catalog: {
        ...CATALOG,
        tools: [{
          ...CATALOG.tools[0],
          visibility: { state: 'EXPOSED_WITH_APPROVAL', reason: 'déclaré dans la liste' },
        }],
      },
    });
    mockedAxios.post.mockResolvedValue({
      data: { token: 'tok-abc', tool: 'kex_list_topics', expiresInMinutes: 15, message: null },
    });
    renderAt();
    await screen.findByText('kex_list_topics');

    await user.click(screen.getByRole('button', { name: 'Approuver' }));

    expect(await screen.findByText('tok-abc')).toBeInTheDocument();
    expect(screen.getByText(/Montré une seule fois/)).toBeInTheDocument();
  });
});

describe('le rejeu depuis le topic d’audit', () => {
  it('dit ce que le balayage a couvert, pas seulement ce qu’il a trouvé', async () => {
    // Une fenêtre vide que le balayage n'a pas atteinte et une fenêtre vide qu'il a atteinte sont
    // deux conclusions opposées.
    const user = userEvent.setup();
    renderAt('/mcp?tab=supervision');
    await screen.findByRole('button', { name: /Rejouer depuis le topic/ });

    mockedAxios.get.mockResolvedValueOnce({
      data: {
        calls: [], recordsScanned: 0, scanReachedWindowStart: false, topicExists: true,
        warnings: ["Le balayage n'a pas atteint le début de la fenêtre."],
      },
    });
    await user.click(screen.getByRole('button', { name: /Rejouer depuis le topic/ }));
    await user.click(screen.getByRole('button', { name: 'Rejouer' }));

    expect(await screen.findByText(/ne prouve rien/)).toBeInTheDocument();
  });
});
