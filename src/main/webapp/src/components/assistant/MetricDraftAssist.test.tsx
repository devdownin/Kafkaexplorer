// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors

/*
 * Le câblage du brouillon de métrique : ce qui part (la description et le topic choisi), ce qui
 * s'affiche (le statut du planificateur, toujours), et le seul geste offert — remplir le formulaire,
 * jamais enregistrer.
 */

import { beforeEach, describe, expect, it, vi } from 'vitest';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';

const post = vi.fn();
vi.mock('axios', () => ({ default: { post: (...a: unknown[]) => post(...a) } }));

import type React from 'react';
import { MetricDraftAssist } from './MetricDraftAssist';
import type { AssistantStatus } from '../../api/types';

const STATUS: AssistantStatus = { enabled: true, problem: null, provider: 'OpenRouter', model: 'm', localDeployment: false, baseUrl: null, requestTimeoutSeconds: 60, promptCharBudget: 12000, contextWindowTokens: 0, providerCode: 'OPENROUTER', dataRetentionRefused: true };

const DRAFT = {
  proposal: {
    status: 'OK', sql: 'SELECT COUNT(*) AS metric_value FROM demo_orders', explanation: 'Counts orders.',
    missing: null, validated: true, validationError: null, repairRounds: 0,
    tablesConsidered: ['demo_orders'], tablesOmitted: [], model: 'm', usage: null, durationMs: 900, error: null,
  },
  name: 'orders_total', type: 'GAUGE', description: 'Orders.',
};

beforeEach(() => vi.clearAllMocks());

describe('MetricDraftAssist', () => {
  it('sends the description and the topic, then fills the form on a gesture', async () => {
    post.mockResolvedValue({ data: DRAFT });
    const onApply = vi.fn();
    render(<MetricDraftAssist status={STATUS} topic="demo.orders" onApply={onApply} />);

    expect(screen.getByText(/No retention: Table and column names/)).toBeInTheDocument();
    await userEvent.type(screen.getByLabelText(/Describe the metric/), 'orders count');
    await userEvent.click(screen.getByRole('button', { name: 'Draft' }));

    expect(post.mock.calls[0][1]).toEqual({ description: 'orders count', topic: 'demo.orders' });
    expect(await screen.findByText('Accepted by the planner')).toBeInTheDocument();
    expect(onApply).not.toHaveBeenCalled();
    await userEvent.click(screen.getByRole('button', { name: /Fill the form/ }));
    expect(onApply).toHaveBeenCalledWith(DRAFT);
  });

  it('renders a failed call as a state of the assistant, with its reason', async () => {
    post.mockImplementation(() => Promise.reject(new Error('connection refused')));
    render(<MetricDraftAssist status={STATUS} topic="" onApply={vi.fn()} />);

    await userEvent.type(screen.getByLabelText(/Describe the metric/), 'anything');
    await userEvent.click(screen.getByRole('button', { name: 'Draft' }));

    expect(await screen.findByText('Failed')).toBeInTheDocument();
    expect(screen.getByText(/connection refused/)).toBeInTheDocument();
    expect(post.mock.calls[0][1]).toEqual({ description: 'anything', topic: null });
    expect(screen.queryByRole('button', { name: /Fill the form/ })).not.toBeInTheDocument();
  });

  // METRICS-CREATION-AUDIT.md B1: the panel lives inside the metric editor's <form>.
  it('drafts without submitting the editor it sits in', async () => {
    post.mockResolvedValue({ data: DRAFT });
    const saved = vi.fn((e: React.FormEvent) => e.preventDefault());
    const { container } = render(
      <form onSubmit={saved}>
        <MetricDraftAssist status={STATUS} topic="" onApply={vi.fn()} />
      </form>);

    expect(container.querySelectorAll('form')).toHaveLength(1);
    await userEvent.type(screen.getByLabelText(/Describe the metric/), 'orders count');
    await userEvent.click(screen.getByRole('button', { name: 'Draft' }));
    expect(await screen.findByText('Accepted by the planner')).toBeInTheDocument();

    await userEvent.type(screen.getByLabelText(/Describe the metric/), '{Control>}{Enter}{/Control}');
    expect(post).toHaveBeenCalledTimes(2);
    expect(saved).not.toHaveBeenCalled();
  });
});
