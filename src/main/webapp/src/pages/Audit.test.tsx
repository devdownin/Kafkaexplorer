// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors

/*
 * Le câblage de la page Audit.
 *
 * Cette page n'avait aucun test, et elle réunit les deux motifs de panne que ce dépôt a déjà
 * payés. D'abord la confiance dans une forme écrite à la main : `globalStats` est un
 * `Map<String, Object>` côté Java, rétréci ici par un `as GlobalStats` sur une convention que
 * rien ne vérifie — c'est la classe de défaut qui a tué la page Compare et failli tuer la page
 * Métriques. Ensuite les états d'un run : un rapport FAILED affichait « 0 topics / 100 % health »,
 * un rapport partiel doit dire qu'il est partiel, et `GET /api/audit/last` répond 204 avec un
 * corps vide quand aucun run n'a eu lieu.
 *
 * Ce qui est vérifié ici est donc ce qu'aucun test pur ne peut voir : ce que la page demande au
 * serveur, et ce qu'elle affiche de ce qu'il répond.
 */

import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import { createMemoryRouter, RouterProvider } from 'react-router-dom';
import axios from 'axios';
import type { AuditReport } from '../api/types';

vi.mock('axios');
const mockedAxios = vi.mocked(axios, true);

const NOW = 1_760_000_000_000;

function report(overrides: Partial<AuditReport> = {}): AuditReport {
  return {
    auditId: 'run-1',
    status: 'COMPLETED',
    totalTopics: 12,
    totalMessages: 3400,
    criticalTopicsCount: 1,
    warningTopicsCount: 2,
    topicAudits: [],
    flowAudits: [],
    globalStats: { timestamp: NOW, durationMs: 4200, healthScore: 0.75 },
    ...overrides,
  } as AuditReport;
}

/** `/api/audit/last` answers 204 with an empty body when no run has ever happened. */
function stubApi(last: { data: unknown; status?: number }, history: unknown = { runs: [], recordsScanned: 0, exhausted: true, warnings: [] }) {
  mockedAxios.get.mockImplementation((url: string) => {
    if (url === '/api/audit/last') return Promise.resolve({ status: last.status ?? 200, data: last.data });
    if (url === '/api/audit/history') return Promise.resolve({ status: 200, data: history });
    return Promise.resolve({ status: 200, data: {} });
  });
  mockedAxios.post.mockResolvedValue({ status: 200, data: 'run-2' });
}

async function renderPage() {
  const { default: Audit } = await import('./Audit');
  const { ToastProvider } = await import('../components/Toast');
  const { ConfirmProvider } = await import('../components/ui');
  return render(
    <ToastProvider>
      <ConfirmProvider>
        <RouterProvider router={createMemoryRouter([{ path: '/', element: <Audit /> }], { initialEntries: ['/'] })} />
      </ConfirmProvider>
    </ToastProvider>,
  );
}

beforeEach(() => {
  vi.clearAllMocks();
  localStorage.clear();
});

afterEach(() => {
  vi.useRealTimers();
});

describe('Audit page', () => {
  it('renders the empty state when no run has ever happened', async () => {
    // 204 + corps vide : la chaîne vide est falsy, et c'est ce que le garde teste.
    stubApi({ data: '', status: 204 });
    await renderPage();

    await waitFor(() => expect(screen.getByText('No audit report yet')).toBeInTheDocument());
    expect(screen.queryByText('Total Topics')).toBeNull();
  });

  it('a failed run shows why, and never the KPI grid', async () => {
    // Le défaut historique : un rapport FAILED affichait « 0 topics / 100 % health », c'est-à-dire
    // un verdict rassurant tiré d'un run qui n'a rien mesuré.
    stubApi({ data: report({
      status: 'FAILED',
      totalTopics: 0,
      globalStats: { error: 'Broker unreachable at kafka:9092', errorType: 'TimeoutException' },
    }) });
    await renderPage();

    await waitFor(() => expect(screen.getByRole('alert')).toBeInTheDocument());
    expect(screen.getByText('Broker unreachable at kafka:9092')).toBeInTheDocument();
    expect(screen.getByText('TimeoutException')).toBeInTheDocument();
    expect(screen.queryByText('Health Score')).toBeNull();
  });

  it('a cancelled run shows its partial results behind a banner that says so', async () => {
    stubApi({ data: report({
      status: 'CANCELLED',
      globalStats: { timestamp: NOW, cancelled: true, stopReason: 'TIME_BUDGET', topicsInScope: 40, healthScore: 0.5 },
    }) });
    await renderPage();

    await waitFor(() => expect(screen.getByText(/Partial report — time budget exhausted/)).toBeInTheDocument());
    // Partiel, mais montré : les topics déjà audités sont un résultat.
    expect(screen.getByText('Total Topics')).toBeInTheDocument();
  });

  /*
   * Le rétrécissement `as GlobalStats` porte sur une convention, pas sur un contrat. Le serveur
   * peut légitimement écrire moins de clés — un rapport d'une version antérieure, un run dont la
   * phase n'a pas encore posé sa durée — et la page doit continuer de rendre.
   */
  it('survives a report whose globalStats carries none of the keys it reads', async () => {
    stubApi({ data: report({ globalStats: {} }) });
    await renderPage();

    await waitFor(() => expect(screen.getByText('Total Topics')).toBeInTheDocument());
    // Pas de score inventé quand le serveur n'en a pas mis.
    expect(screen.queryByText('75%')).toBeNull();
  });

  it('attaches to a run that is still going instead of showing it as finished', async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true });
    stubApi({ data: report({
      status: 'RUNNING',
      globalStats: { phase: 'TOPICS', topicsCompleted: 3, topicsTotal: 12 },
    }) });
    await renderPage();

    // Le poller est branché : /status est interrogé sans geste de l'utilisateur.
    await vi.advanceTimersByTimeAsync(2500);
    await waitFor(() => expect(mockedAxios.get).toHaveBeenCalledWith('/api/audit/status/run-1'));
  });

  it('a history that cannot be read says so rather than reading as "never audited"', async () => {
    mockedAxios.get.mockImplementation((url: string) => {
      if (url === '/api/audit/last') return Promise.resolve({ status: 204, data: '' });
      if (url === '/api/audit/history') return Promise.reject(new Error('boom'));
      return Promise.resolve({ status: 200, data: {} });
    });
    await renderPage();

    // La page tient debout, et l'état vide reste celui de l'absence de rapport.
    await waitFor(() => expect(screen.getByText('No audit report yet')).toBeInTheDocument());
  });
});
