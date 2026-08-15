// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors

import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import { createMemoryRouter, RouterProvider } from 'react-router-dom';
import axios from 'axios';
import MetricsHelp from './MetricsHelp';

/*
 * The page asserted measurements nobody had measured — 99.98% availability, 14 ms scrape latency,
 * 0.02% errors, "Ingestion: 1.2M msg/sec" — on the page that teaches how to measure. These tests
 * pin the replacement: the numbers come from /api/metrics, an unreadable list says so instead of
 * showing zeros, and none of the invented figures can come back.
 */

vi.mock('axios');
const mockedAxios = vi.mocked(axios, true);

const metric = (over: Record<string, unknown> = {}) => ({
  lastValue: 12, lastUpdateTime: Date.now() - 5_000, errorMessage: null, ...over,
});

const renderPage = () =>
  render(<RouterProvider router={createMemoryRouter([{ path: '/', element: <MetricsHelp /> }], { initialEntries: ['/'] })} />);

beforeEach(() => vi.clearAllMocks());

describe('MetricsHelp', () => {
  it('shows counts read from the instance', async () => {
    mockedAxios.get.mockResolvedValue({ data: [metric(), metric({ errorMessage: 'boom' }), metric({ lastValue: null, lastUpdateTime: null })] });
    renderPage();

    await waitFor(() => expect(screen.getByText('1 of 3 failing')).toBeInTheDocument());
    expect(mockedAxios.get).toHaveBeenCalledWith('/api/metrics');
  });

  it('says plainly when no metric is configured', async () => {
    mockedAxios.get.mockResolvedValue({ data: [] });
    renderPage();

    await waitFor(() => expect(screen.getByText('No metric configured yet')).toBeInTheDocument());
  });

  it('admits it could not read the list rather than showing zeros', async () => {
    mockedAxios.get.mockRejectedValue(new Error('network'));
    renderPage();

    await waitFor(() => expect(screen.getByText(/could not read the metric list/i)).toBeInTheDocument());
    expect(screen.queryByText('No metric configured yet')).toBeNull();
  });

  it('carries none of the invented figures', async () => {
    mockedAxios.get.mockResolvedValue({ data: [metric()] });
    renderPage();
    await waitFor(() => expect(screen.getByText('1 of 1 reporting')).toBeInTheDocument());

    for (const invented of ['99.98%', '14 ms', '0.02 %', 'Ingestion: 1.2M msg/sec', 'Global Status: Operational', 'Engine v2.1']) {
      expect(screen.queryByText(invented)).toBeNull();
    }
  });

  it('documents the metric types the backend actually supports', async () => {
    mockedAxios.get.mockResolvedValue({ data: [] });
    renderPage();
    await waitFor(() => expect(screen.getByText('No metric configured yet')).toBeInTheDocument());

    for (const type of ['GAUGE', 'COUNTER', 'HISTOGRAM', 'SUMMARY']) {
      expect(screen.getByRole('heading', { name: type })).toBeInTheDocument();
    }
    // TIMER n'existe pas dans MetricService.
    expect(screen.queryByRole('heading', { name: 'TIMER' })).toBeNull();
  });

  it('states that the Prometheus endpoint has no authentication', async () => {
    mockedAxios.get.mockResolvedValue({ data: [] });
    renderPage();

    await waitFor(() => expect(screen.getByText(/no authentication/i)).toBeInTheDocument());
    expect(screen.queryByText(/OAuth2/i)).toBeNull();
  });
});
