// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors

/*
 * Le câblage, pas la logique pure.
 *
 * `pages/metricSuggestions.test.ts` couvre ce qui se décide sans React : rejets, tri, phrases
 * d'état, brouillon d'éditeur. Ce qu'il ne peut pas couvrir, c'est ce que ce panneau promet à
 * l'écran — qu'une proposition affiche l'observation dont elle sort, qu'un panneau vide dise
 * « rien n'a été mesuré » avec de quoi y remédier plutôt que « ce cluster n'appelle aucun KPI »,
 * et que le bouton ouvre l'éditeur au lieu d'enregistrer quoi que ce soit.
 */

import { beforeEach, describe, expect, it, vi } from 'vitest';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import { SuggestionsPanel } from './SuggestionsPanel';
import type { MetricConfig, MetricSuggestion, MetricSuggestions } from '../../api/types';

const NOW = 1_700_000_000_000;

function metric(overrides: Partial<MetricConfig> = {}): MetricConfig {
  return {
    id: 'server-id', name: 'gauge_latency_a_to_b', type: 'GAUGE', sql: '',
    description: 'Average latency', warningThreshold: 800, criticalThreshold: 1600,
    lastValue: null, lastUpdateTime: null, errorMessage: null, history: [], lastSummary: null,
    createTableSql: null, templateType: 'TOPIC_TRANSIT_LATENCY',
    templateParams: { sourceTopic: 'orders.received', targetTopic: 'orders.enriched' },
    executionMode: 'TEMPLATE_BOUNDED_SCAN', labelTopic: 'orders.received', labelFields: [], componentHistory: null,
    ...overrides,
  };
}

function suggestion(overrides: Partial<MetricSuggestion> = {}): MetricSuggestion {
  return {
    id: 'audit:hop-latency:orders.received>orders.enriched',
    source: 'AUDIT',
    title: 'Processing latency orders.received → orders.enriched',
    rationale: 'This hop is on a flow the audit reconstructed.',
    evidence: ['The cluster audit measured an average hop of 400 ms.'],
    thresholdBasis: 'Warning at 2× and critical at 4× the 400 ms that was measured.',
    caveats: ['Correlation matches `id` — check it in the preview.'],
    alreadyConfigured: false,
    existingMetricName: null,
    metric: metric(),
    ...overrides,
  };
}

function response(overrides: Partial<MetricSuggestions> = {}): MetricSuggestions {
  return {
    suggestions: [suggestion()],
    auditAvailable: true, auditId: 'run-1', auditTimestamp: NOW, auditSource: 'CURRENT_RUN',
    auditTopics: 12, flowChainsSubmitted: 1, processMeasured: false, notes: [],
    ...overrides,
  };
}

function renderPanel(props: Partial<React.ComponentProps<typeof SuggestionsPanel>> = {}) {
  const onAdopt = vi.fn();
  const onRefresh = vi.fn();
  render(
    <MemoryRouter>
      <SuggestionsPanel
        response={response()}
        loading={false}
        error={null}
        newerAudit={null}
        onRefresh={onRefresh}
        onAdopt={onAdopt}
        {...props}
      />
    </MemoryRouter>,
  );
  return { onAdopt, onRefresh };
}

beforeEach(() => {
  localStorage.clear();
});

describe('SuggestionsPanel', () => {
  it('shows the measurement a proposal rests on, and where its thresholds come from', () => {
    renderPanel();

    expect(screen.getByText(/Processing latency orders.received/)).toBeInTheDocument();
    expect(screen.getByText(/measured an average hop of 400 ms/)).toBeInTheDocument();
    expect(screen.getByText(/Warning at 2× and critical at 4×/)).toBeInTheDocument();
    // Les topics mesurés sont nommés : une carte sans eux ne se relie à rien du cluster.
    expect(screen.getByText('orders.enriched')).toBeInTheDocument();
  });

  it('keeps the assumptions one click away rather than hidden', async () => {
    const user = userEvent.setup();
    renderPanel();

    expect(screen.queryByText(/Correlation matches/)).not.toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: /What this assumes/ }));

    expect(screen.getByText(/Correlation matches/)).toBeInTheDocument();
  });

  it('opens the editor instead of saving — nothing is created from a card', async () => {
    const user = userEvent.setup();
    const { onAdopt } = renderPanel();

    await user.click(screen.getByRole('button', { name: /Review & add/ }));

    expect(onAdopt).toHaveBeenCalledTimes(1);
    expect(onAdopt.mock.calls[0][0].id).toBe('audit:hop-latency:orders.received>orders.enriched');
  });

  it('says nothing was measured, with what would unlock proposals', () => {
    renderPanel({
      response: response({
        suggestions: [], auditAvailable: false, auditTimestamp: null, auditSource: null,
        auditTopics: 0, flowChainsSubmitted: 0,
      }),
    });

    expect(screen.getByText('Nothing measured yet')).toBeInTheDocument();
    expect(screen.getByRole('link', { name: /Run a cluster audit/ })).toHaveAttribute('href', '/audit');
    expect(screen.getByRole('link', { name: /Trace a message key/ })).toHaveAttribute('href', '/stream-flow');
  });

  it('does not conclude the cluster needs no KPI when a measurement simply suggested none', () => {
    renderPanel({ response: response({ suggestions: [] }) });

    expect(screen.queryByText('Nothing measured yet')).not.toBeInTheDocument();
    expect(screen.getByText(/No KPI could be derived/)).toBeInTheDocument();
  });

  it('marks what an existing metric already covers rather than dropping the card', () => {
    renderPanel({
      response: response({
        suggestions: [suggestion({ alreadyConfigured: true, existingMetricName: 'orders_latency' })],
      }),
    });

    expect(screen.getByText('orders_latency')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /Open a variant/ })).toBeInTheDocument();
    /* Le nom de la métrique existante est ce que l'on va chercher ensuite, et la ligne le
       tronque : la sonde le signalait coupé (`p 334>320`) sans rien qui le porte. */
    expect(screen.getByText('orders_latency')).toHaveAttribute('title', 'orders_latency');
  });

  it('dismisses on this browser only, and offers the card back', async () => {
    const user = userEvent.setup();
    renderPanel();

    await user.click(screen.getByRole('button', { name: /Dismiss/ }));
    expect(screen.queryByText(/Processing latency orders.received/)).not.toBeInTheDocument();

    await user.click(screen.getByRole('button', { name: /1 dismissed proposal/ }));
    await user.click(screen.getByRole('button', { name: 'Restore' }));

    expect(screen.getByText(/Processing latency orders.received/)).toBeInTheDocument();
  });

  it('renders what was found but not turned into a metric', () => {
    renderPanel({
      response: response({
        notes: ['The audit reported consumer-lag findings on demo.payments. Lag is not proposed as a SQL metric.'],
      }),
    });

    expect(screen.getByText(/Lag is not proposed as a SQL metric/)).toBeInTheDocument();
  });

  it('warns when the proposals rest on an old audit', () => {
    vi.useFakeTimers();
    vi.setSystemTime(NOW + 12 * 24 * 60 * 60 * 1000);
    try {
      renderPanel();
      expect(screen.getByText(/12 days old/)).toBeInTheDocument();
    } finally {
      vi.useRealTimers();
    }
  });

  it('surfaces a failed derivation instead of an empty panel that would read as "no KPI"', () => {
    renderPanel({
      response: null,
      error: { title: 'Failed to derive suggested KPIs.', hint: 'The broker is unreachable.', raw: 'boom' },
    });

    expect(screen.getByText('Failed to derive suggested KPIs.')).toBeInTheDocument();
  });
});

describe('un audit plus récent', () => {
  it('renders the note and offers to re-derive on the spot', async () => {
    const user = userEvent.setup();
    const { onRefresh } = renderPanel({ newerAudit: 'A more recent audit ran on 14/08/2026.' });

    expect(screen.getByText('A more recent audit ran on 14/08/2026.')).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: /Re-derive now/ }));

    expect(onRefresh).toHaveBeenCalled();
  });

  it('shows nothing when the displayed run is the newest one', () => {
    renderPanel({ newerAudit: null });

    expect(screen.queryByRole('button', { name: /Re-derive now/ })).toBeNull();
  });
});
