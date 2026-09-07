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

function mockApi(over: { status?: unknown; catalog?: unknown } = {}) {
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
    if (url.startsWith('/api/mcp/clients')) return Promise.resolve({ data: [] });
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
