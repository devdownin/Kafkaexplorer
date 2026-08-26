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
import { defaultReadMode, validateScanParams } from './Metrics';

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

/*
 * Une proposition de latence, qui est le gabarit à deux requêtes le plus proposé par le panneau.
 * Ouvrir son éditeur est la seule façon de voir la fenêtre de lecture, qui n'était sur aucun
 * formulaire — voir METRICS-TWO-QUERY-AUDIT.md, D3.
 */
const latencySuggestion: MetricSuggestion = {
  ...suggestion,
  id: 'audit:latency:a>b',
  title: 'Latency between a and b',
  metric: {
    ...suggestion.metric,
    id: 'server-side-latency-id',
    name: 'gauge_latency_a_to_b',
    sql: null,
    templateType: 'TOPIC_TRANSIT_LATENCY',
    templateParams: {
      sourceSql: 'SELECT id AS match_key, event_time AS event_time\nFROM a',
      targetSql: 'SELECT id AS match_key, event_time AS event_time\nFROM b',
    },
    executionMode: 'TEMPLATE_BOUNDED_SCAN',
  },
};

const suggestions: MetricSuggestions = {
  suggestions: [suggestion],
  auditAvailable: true, auditId: 'run-1', auditTimestamp: Date.now(),
  auditSource: 'CURRENT_RUN', auditTopics: 12, flowChainsSubmitted: 0, processMeasured: false,
  notes: [],
};

function stubApi(metrics: MetricConfig[], proposals: MetricSuggestion[] = [suggestion]) {
  mockedAxios.get.mockImplementation((url: string) => {
    if (url === '/api/metrics') return Promise.resolve({ data: metrics });
    if (url === '/api/metrics/metadata') return Promise.resolve({ data: {} });
    if (url === '/api/metrics/templates') return Promise.resolve({ data: [] });
    if (url === '/api/config') return Promise.resolve({ data: { bootstrapServers: 'kafka:9092' } });
    return Promise.resolve({ data: {} });
  });
  mockedAxios.post.mockImplementation((url: string) => {
    if (url === '/api/metrics/suggestions') {
      return Promise.resolve({ data: { ...suggestions, suggestions: proposals } });
    }
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

  /*
   * Les deux gabarits qui lancent deux requêtes — voir METRICS-TWO-QUERY-AUDIT.md.
   *
   * `maxRowsPerSide`, `timeoutMs` et `readMode` décidaient déjà ce que la métrique mesure et
   * n'étaient atteignables que par un POST écrit à la main.
   */
  /*
   * Ce que la mesure a couvert, sur la carte d'une métrique en service — voir D6. Le taux
   * d'appariement était calculé, persisté, et visible seulement dans l'aperçu du modal.
   */
  it('shows on the card what the measurement covered', async () => {
    stubApi([{
      ...templateMetric,
      lastSummary: {
        matchRate: 0.25, matchedCount: 1, unmatchedSourceCount: 3, outOfOrderCount: 2,
        scopeNote: 'Correlated over at most 10000 row(s) per side.',
      },
    }]);
    await renderPage();

    await waitFor(() => expect(screen.getByText('gauge_latency_a_to_b')).toBeInTheDocument());
    // La moyenne ne décrit qu'un quart des événements lus, et la carte le dit à côté d'elle.
    expect(screen.getByText('25% paired')).toBeInTheDocument();
    expect(screen.getByText('2 before source')).toBeInTheDocument();
  });

  it('says nothing about scope when the metric reported none', async () => {
    stubApi([{ ...templateMetric, lastSummary: null }]);
    await renderPage();

    await waitFor(() => expect(screen.getByText('gauge_latency_a_to_b')).toBeInTheDocument());
    expect(screen.queryByText(/paired/)).toBeNull();
  });

  it('puts the scan window of a two-query template on the form', async () => {
    const user = userEvent.setup();
    stubApi([], [latencySuggestion]);
    await renderPage();

    await waitFor(() => expect(screen.getByText('Latency between a and b')).toBeInTheDocument());
    await user.click(screen.getByRole('button', { name: /Review & add/ }));

    const dialog = await screen.findByRole('dialog');
    expect(within(dialog).getByLabelText(/Read from/)).toHaveValue('latest-offset');
    expect(within(dialog).getByLabelText(/Max rows \/ side/)).toHaveValue(10000);
    expect(within(dialog).getByLabelText(/Timeout \/ side/)).toHaveValue(30000);
  });

  it('warns on the form when a latency metric is pointed at the oldest records', async () => {
    const user = userEvent.setup();
    stubApi([], [latencySuggestion]);
    await renderPage();

    await waitFor(() => expect(screen.getByText('Latency between a and b')).toBeInTheDocument());
    await user.click(screen.getByRole('button', { name: /Review & add/ }));

    const dialog = await screen.findByRole('dialog');
    await user.selectOptions(within(dialog).getByLabelText(/Read from/), 'earliest-offset');
    expect(await within(dialog).findByText(/stops moving once the topic outgrows the cap/)).toBeInTheDocument();
  });
});

describe('the scan window of a two-query template', () => {
  it('reads the recent end for a latency and the whole topic for a count', () => {
    expect(defaultReadMode('TOPIC_TRANSIT_LATENCY')).toBe('latest-offset');
    expect(defaultReadMode('TOPIC_COUNT_DELTA')).toBe('earliest-offset');
  });

  it('refuses what the server would refuse, and says nothing about what it accepts', () => {
    const errors = (params: Record<string, unknown>) =>
      validateScanParams('TOPIC_COUNT_DELTA', params).filter(m => m.level === 'error');

    expect(errors({})).toEqual([]);
    expect(errors({ maxRowsPerSide: '50000', timeoutMs: '5000', readMode: 'latest-offset' })).toEqual([]);
    expect(errors({ maxRowsPerSide: '10k' })).toHaveLength(1);
    expect(errors({ maxRowsPerSide: '0' })).toHaveLength(1);
    expect(errors({ maxRowsPerSide: '2000000' })).toHaveLength(1);
    expect(errors({ timeoutMs: '10' })).toHaveLength(1);
    expect(errors({ readMode: 'group-offsets' })).toHaveLength(1);
  });

  it('warns rather than refuses a latency read from the earliest offset', () => {
    const msgs = validateScanParams('TOPIC_TRANSIT_LATENCY', { readMode: 'earliest-offset' });
    expect(msgs.filter(m => m.level === 'error')).toEqual([]);
    expect(msgs.some(m => m.level === 'warning')).toBe(true);
    // Le défaut d'un compte est ce même bout du topic, et n'a rien à signaler.
    expect(validateScanParams('TOPIC_COUNT_DELTA', { readMode: 'earliest-offset' })).toEqual([]);
  });
});
