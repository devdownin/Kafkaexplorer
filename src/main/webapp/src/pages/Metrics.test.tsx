// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors

/*
 * Le câblage de la page Métriques.
 *
 * `metricSuggestions.test.ts` couvre la logique pure et `SuggestionsPanel.test.tsx` le rendu du
 * panneau. Ce qui restait sans test, c'est la page elle-même : ce qu'elle envoie au serveur, et ce
 * que « Review & add » met réellement dans l'éditeur. Le harnais de captures d'écran a trouvé le
 * défaut que ce fichier aurait dû trouver — une métrique de gabarit a un `sql` à `null`, et la
 * carte appelait `.replace()` dessus, ce qui faisait tomber la page entière. Le cas est pinné ici.
 *
 * Monaco est neutralisé : l'éditeur SQL du modal n'entre dans aucune des assertions et jsdom ne
 * sait pas le disposer. `ResizeObserver`, dont les graphes Recharts des cartes ont besoin, est
 * bouché dans `src/test/setup.ts` — jsdom ne le fournit pas, et sans lui la page tombe dans sa
 * frontière d'erreur avant d'avoir rendu quoi que ce soit d'observable.
 */

import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { createMemoryRouter, RouterProvider } from 'react-router-dom';
import axios from 'axios';
import type { MetricConfig, MetricSuggestion, MetricSuggestions } from '../api/types';

vi.mock('axios');
const mockedAxios = vi.mocked(axios, true);

vi.mock('@monaco-editor/react', () => ({
  __esModule: true,
  default: ({ value }: { value?: string }) => <textarea readOnly aria-label="sql editor" value={value ?? ''} />,
  useMonaco: () => null,
}));
vi.mock('../monaco-setup', () => ({}));

vi.mock('../catalogStore', () => ({
  useCatalog: () => ({ topics: ['demo.orders.1.received'], tables: [] }),
}));

const templateMetric: MetricConfig = {
  id: 'm-1',
  name: 'gauge_latency_a_to_b',
  type: 'GAUGE',
  // C'est tout l'objet du premier test : une métrique de gabarit n'a pas de SQL.
  sql: null,
  description: 'Average latency between a and b.',
  warningThreshold: 800, criticalThreshold: 1600,
  lastValue: 412, lastUpdateTime: Date.now() - 5_000, errorMessage: null,
  history: [380, 402, 412], lastSummary: null, createTableSql: null,
  templateType: 'TOPIC_TRANSIT_LATENCY',
  templateParams: { sourceTopic: 'demo.orders.1.received', targetTopic: 'demo.orders.2.validated' },
  executionMode: 'TEMPLATE_BOUNDED_SCAN',
  labelTopic: 'demo.orders.1.received', labelFields: [],
};

const suggestion: MetricSuggestion = {
  id: 'audit:duplicates:demo.orders.1.received',
  source: 'AUDIT',
  title: 'Duplicate keys in demo.orders.1.received',
  rationale: 'The audit found duplicates here once.',
  evidence: ['The cluster audit found 2 duplicate key(s).'],
  thresholdBasis: 'Warning at the 2 already observed.',
  caveats: ['COUNT(DISTINCT …) needs the Flink planner.'],
  alreadyConfigured: false,
  existingMetricName: null,
  metric: {
    id: 'server-side-id',
    name: 'gauge_duplicates_demo_orders_1_received',
    type: 'GAUGE',
    sql: 'SELECT COUNT(*) - COUNT(DISTINCT `id`) AS metric_value\nFROM demo_orders_1_received',
    description: 'Records sharing an id with another record.',
    warningThreshold: 2, criticalThreshold: 4,
    lastValue: null, lastUpdateTime: null, errorMessage: null,
    history: [], lastSummary: null, createTableSql: null,
    templateType: 'RAW_SQL', templateParams: {}, executionMode: 'SQL',
    labelTopic: 'demo.orders.1.received', labelFields: [],
  },
};

const suggestions: MetricSuggestions = {
  suggestions: [suggestion],
  auditAvailable: true, auditId: 'run-1', auditTimestamp: Date.now(),
  auditSource: 'CURRENT_RUN', auditTopics: 12, flowChainsSubmitted: 0, notes: [],
};

function stubApi(metrics: MetricConfig[]) {
  mockedAxios.get.mockImplementation((url: string) => {
    if (url === '/api/metrics') return Promise.resolve({ data: metrics });
    if (url === '/api/metrics/metadata') return Promise.resolve({ data: {} });
    if (url === '/api/metrics/templates') return Promise.resolve({ data: [] });
    if (url === '/api/config') return Promise.resolve({ data: { bootstrapServers: 'kafka:9092' } });
    return Promise.resolve({ data: {} });
  });
  mockedAxios.post.mockImplementation((url: string) => {
    if (url === '/api/metrics/suggestions') return Promise.resolve({ data: suggestions });
    return Promise.resolve({ data: {} });
  });
}

async function renderPage() {
  const { default: Metrics } = await import('./Metrics');
  const { ToastProvider } = await import('../components/Toast');
  const { ConfirmProvider } = await import('../components/ui');
  return render(
    <ToastProvider>
      <ConfirmProvider>
        <RouterProvider router={createMemoryRouter([{ path: '/', element: <Metrics /> }], { initialEntries: ['/'] })} />
      </ConfirmProvider>
    </ToastProvider>,
  );
}

beforeEach(() => {
  vi.clearAllMocks();
  localStorage.clear();
});

describe('Metrics page', () => {
  it('renders a template metric, whose SQL is null by construction', async () => {
    stubApi([templateMetric]);
    await renderPage();

    // La page entière tombait ici : `metric.sql.replace(…)` sur un `null` que le serveur
    // renvoie pour toute métrique de gabarit.
    await waitFor(() => expect(screen.getByText('gauge_latency_a_to_b')).toBeInTheDocument());
    // À défaut de requête, le pied de carte dit ce que la métrique compare.
    expect(screen.getByText(/TOPIC_TRANSIT_LATENCY/)).toBeInTheDocument();
    expect(screen.getByText(/demo.orders.1.received → demo.orders.2.validated/)).toBeInTheDocument();
    // Rien à copier quand il n'y a pas de SQL.
    expect(screen.queryByLabelText('Copy the metric SQL')).toBeNull();
  });

  it('asks for suggestions with what this browser holds', async () => {
    stubApi([]);
    await renderPage();

    await waitFor(() => expect(mockedAxios.post).toHaveBeenCalledWith(
      '/api/metrics/suggestions',
      expect.objectContaining({ flowChains: expect.any(Array) }),
    ));
  });

  it('opens the editor pre-filled from a suggestion, and creates nothing', async () => {
    const user = userEvent.setup();
    stubApi([]);
    await renderPage();

    await waitFor(() => expect(screen.getByText('Duplicate keys in demo.orders.1.received')).toBeInTheDocument());
    await user.click(screen.getByRole('button', { name: /Review & add/ }));

    const dialog = await screen.findByRole('dialog');
    // Pré-rempli, et sur le formulaire « nouvelle métrique » : l'id du serveur est retiré, sinon
    // le premier enregistrement écraserait la métrique qui le porte.
    expect(within(dialog).getByText('New SQL Metric')).toBeInTheDocument();
    expect(within(dialog).getByDisplayValue('gauge_duplicates_demo_orders_1_received')).toBeInTheDocument();
    expect(within(dialog).getByDisplayValue('2')).toBeInTheDocument();
    // Rien n'a été créé : ouvrir l'éditeur n'est pas enregistrer.
    expect(mockedAxios.post).not.toHaveBeenCalledWith('/api/metrics', expect.anything());
  });

  /*
   * Le dialogue est la carte, pas le voile plein écran.
   *
   * `role="dialog"` portait sur le conteneur `fixed inset-0` : l'élément annoncé comme
   * dialogue faisait la taille du viewport. Ici il porte aussi un nom accessible, que ce
   * dialogue-là n'avait pas du tout — et il est sur une enveloppe plutôt que sur le
   * <form>, ARIA n'admettant pas `dialog` sur un formulaire.
   */
  it('scopes the editor dialog to the card, names it, and keeps the form inside it', async () => {
    const user = userEvent.setup();
    stubApi([]);
    await renderPage();

    await waitFor(() => expect(screen.getByText('Duplicate keys in demo.orders.1.received')).toBeInTheDocument());
    await user.click(screen.getByRole('button', { name: /Review & add/ }));

    const dialog = await screen.findByRole('dialog', { name: 'New SQL Metric' });
    expect(dialog.className).not.toMatch(/\bfixed\b/);
    expect(dialog.className).not.toMatch(/\binset-0\b/);
    expect(dialog.className).toMatch(/max-w-5xl/);
    expect(dialog.parentElement!.className).toMatch(/fixed inset-0/);
    // Le formulaire garde son propre rôle, à l'intérieur du dialogue.
    expect(dialog.querySelector('form')).not.toBeNull();
  });
});
