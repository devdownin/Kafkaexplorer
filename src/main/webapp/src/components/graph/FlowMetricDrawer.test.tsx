// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors
import { describe, expect, it, vi } from 'vitest';
import { render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import axios from 'axios';
import { FlowMetricDrawer } from './FlowMetricDrawer';
import type { FlowHit } from '../../pages/streamFlowLogic';

vi.mock('axios', () => ({ default: { get: vi.fn(), post: vi.fn(), isAxiosError: () => false } }));

const hit = (topic: string): FlowHit => ({ topic, occurrences: 1, firstTimestamp: 1, lastTimestamp: 1, firstPartition: 0,
  firstOffset: 0, firstKey: null, preview: null, latencyFromPreviousMs: 10 });

describe('FlowMetricDrawer', () => {
  it('creates the ticked metric with the labels left ticked', async () => {
    vi.mocked(axios.get).mockResolvedValue({ data: { groups: [{ groupId: 'billing' }] } });
    vi.mocked(axios.post).mockImplementation((url: string) => Promise.resolve({ data: url === '/api/metrics/suggestions'
      ? { suggestions: [{ id: 'flow:hop-latency:orders>payments', alreadyConfigured: false, thresholdBasis: null,
          metric: { id: 's', name: 'latency_orders_payments', templateType: 'TOPIC_TRANSIT_LATENCY', templateParams: { sourceSql: 'a' } } }] }
      : url === '/api/metrics' ? { id: 'new' } : {} }));
    render(<MemoryRouter><FlowMetricDrawer topic="payments" hits={['orders', 'payments'].map(hit)} messageKey="42"
      searchPath="orderId" onClose={() => {}} /></MemoryRouter>);

    const create = await screen.findByRole('button', { name: 'Create 1 metric' });
    await userEvent.click(within(screen.getByRole('group', { name: /Labels of Transit latency/ }))
      .getByRole('button', { name: /target_topic=payments/ }));
    await userEvent.click(create);

    await waitFor(() => expect(screen.getByLabelText('Created metrics')).toHaveTextContent('created as latency_orders_payments'));
    const body = vi.mocked(axios.post).mock.calls.find(([url]) => url === '/api/metrics')?.[1] as { id?: string; templateParams: Record<string, unknown> };
    expect(body.id).toBeUndefined();
    expect(body.templateParams.staticLabels).toEqual({ source_topic: 'orders' });
    expect(screen.getByRole('button', { name: 'Create 0 metrics' })).toBeDisabled();
  });

  it('proposes only the clicked hop, and reads no consumer group for it', async () => {
    vi.mocked(axios.get).mockClear();
    vi.mocked(axios.post).mockResolvedValue({ data: { suggestions: [] } });
    render(<MemoryRouter><FlowMetricDrawer topic="payments" hop={{ from: 'orders', to: 'payments' }}
      hits={['orders', 'payments', 'shipped'].map(hit)} messageKey="42" searchPath="" onClose={() => {}} /></MemoryRouter>);
    expect(await screen.findByRole('dialog', { name: 'Create metrics for the hop orders to payments' })).toBeInTheDocument();
    expect(await screen.findByRole('button', { name: 'Create 1 metric' })).toBeEnabled();
    expect(screen.getByText('Records lost orders → payments')).toBeInTheDocument();
    expect(screen.queryByText(/payments → shipped/)).not.toBeInTheDocument();
    expect(axios.get).not.toHaveBeenCalled();
  });
});
