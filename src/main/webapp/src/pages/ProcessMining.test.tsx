// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors

/*
 * Le câblage de la page Process Mining.
 *
 * `processMiningDraft.ts`, `processMiningCoverage.ts` et `schemaMapping.ts` couvrent la logique
 * pure ; `SchemaValidationPanel` et `MermaidRenderer` ont leurs propres tests. Ce qui n'en avait
 * aucun, c'est le pipeline lui-même — et il porte une distinction que ce dépôt a payée cher.
 *
 * `FieldProfileResult.error` sépare **« le profilage n'a pas eu lieu »** de **« il a tourné et n'a
 * rien trouvé »**. Les deux envoient l'opérateur à des endroits opposés : l'un vers l'endpoint, le
 * modèle ou la clé, l'autre vers le cluster. Le serveur ne les distinguait pas, et trois pannes de
 * modèle ont été lues comme des lectures Kafka en échec — c'est écrit dans le commentaire du code,
 * et rien ne le vérifiait. Une réponse qui profile zéro topic *sans* erreur est la seconde, et son
 * message doit parler du cluster.
 *
 * Le troisième cas est l'autre moitié de la même règle : une réponse utilisable fait avancer le
 * pipeline. Sans lui, les deux premiers seraient satisfaits par une page qui échoue toujours.
 */

import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { createMemoryRouter, RouterProvider } from 'react-router-dom';
import axios from 'axios';

vi.mock('axios');
const mockedAxios = vi.mocked(axios, true);

// Mermaid ne parse rien ici : ce qui est testé est le pipeline, pas le rendu d'un diagramme.
vi.mock('../components/processmining/MermaidRenderer', () => ({
  __esModule: true,
  default: () => <div data-testid="flowchart" />,
}));

const TOPICS = ['demo.orders.1.received', 'demo.orders.2.validated'];

const PROFILE = {
  topics: [
    {
      topic: 'demo.orders.1.received',
      fields: [{ path: '$.id', role: 'CORRELATION_ID', confidence: 0.9, samples: ['ORD-1042'] }],
    },
  ],
  warnings: [],
  error: null,
};

function stubApi(profile: unknown) {
  mockedAxios.get.mockImplementation((url: string) => {
    if (url === '/api/dashboard') return Promise.resolve({ status: 200, data: { topics: TOPICS, tables: [] } });
    if (url === '/api/config') return Promise.resolve({ status: 200, data: { llmProvider: 'OPENROUTER', llmBaseUrl: 'https://openrouter.ai/api/v1' } });
    if (url === '/api/process-mining/audit-templates') return Promise.resolve({ status: 200, data: [] });
    return Promise.resolve({ status: 200, data: {} });
  });
  mockedAxios.post.mockImplementation((url: string) =>
    url === '/api/process-mining/profiling/start'
      ? Promise.resolve({ status: 200, data: profile })
      : Promise.resolve({ status: 200, data: {} }));
}

async function renderPage() {
  const { default: ProcessMining } = await import('./ProcessMining');
  const { ToastProvider } = await import('../components/Toast');
  const { ConfirmProvider } = await import('../components/ui');
  return render(
    <ToastProvider>
      <ConfirmProvider>
        <RouterProvider router={createMemoryRouter([{ path: '/', element: <ProcessMining /> }], { initialEntries: ['/'] })} />
      </ConfirmProvider>
    </ToastProvider>,
  );
}

/** Choisit un topic puis lance le profilage — le geste de l'étape 1. */
async function startProfiling(user: ReturnType<typeof userEvent.setup>) {
  await waitFor(() => expect(screen.getByText(TOPICS[0])).toBeInTheDocument());
  await user.click(screen.getByText(TOPICS[0]));
  await user.click(screen.getByRole('button', { name: /Start Profiling/ }));
}

beforeEach(() => {
  vi.clearAllMocks();
  localStorage.clear();
});

describe('Process Mining pipeline', () => {
  it('sends the chosen topics to the profiling endpoint', async () => {
    stubApi(PROFILE);
    const user = userEvent.setup();
    await renderPage();
    await startProfiling(user);

    await waitFor(() => expect(mockedAxios.post).toHaveBeenCalledWith(
      '/api/process-mining/profiling/start',
      expect.objectContaining({ topics: [TOPICS[0]] }),
      expect.anything(),
    ));
  });

  /*
   * Le cas qui a coûté du temps : `error` renseigné veut dire que l'appel n'a pas eu lieu. Rien
   * d'autre dans l'enregistrement n'est alors un constat, et la page doit ramener à la sélection
   * avec CE message — pas avec une phrase sur le cluster.
   */
  it('a profiling run that did not happen says so, and returns to the selection', async () => {
    stubApi({ topics: [], warnings: [], error: 'No API key is configured for the LLM provider.' });
    const user = userEvent.setup();
    await renderPage();
    await startProfiling(user);

    await waitFor(() => expect(screen.getByText('No API key is configured for the LLM provider.')).toBeInTheDocument());
    // Retour à l'étape 1 : la liste des topics est de nouveau là.
    expect(screen.getByRole('button', { name: /Start Profiling/ })).toBeInTheDocument();
  });

  /*
   * L'autre zéro. Le profilage a bien tourné et n'a rien trouvé : le message envoie vers le
   * cluster, pas vers la configuration du modèle.
   */
  it('a run that profiled nothing points at the cluster, not at the model', async () => {
    stubApi({ topics: [], warnings: [], error: null });
    const user = userEvent.setup();
    await renderPage();
    await startProfiling(user);

    await waitFor(() => expect(screen.getByText(/Check that the selected topics hold messages/)).toBeInTheDocument());
  });

  it('a usable profile advances the pipeline to the validation step', async () => {
    stubApi(PROFILE);
    const user = userEvent.setup();
    await renderPage();
    await startProfiling(user);

    // L'étape 2 est celle où l'opérateur corrige ce que le modèle a proposé.
    await waitFor(() => expect(screen.getByText('Validate Schema Mapping')).toBeInTheDocument());
    expect(screen.queryByRole('button', { name: /Start Profiling/ })).toBeNull();
  });
});
