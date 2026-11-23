// SPDX-License-Identifier: AGPL-3.0-or-later
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { cleanup, render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import axios from 'axios';
import { DemoControlCenter } from './DemoControlCenter';
import type { ForecastStatus } from '../../api/types';

vi.mock('axios', () => ({
  default: {
    get: vi.fn(),
    put: vi.fn(),
    isAxiosError: (error: unknown) => Boolean((error as { isAxiosError?: boolean })?.isAxiosError),
  },
}));

const applying = {
  enabled: true, scenario: null, requestedScenario: 'normal', phase: 'APPLYING', appliedAt: null,
};
const confirmed = {
  enabled: true, scenario: 'normal', requestedScenario: 'normal', phase: 'ACTIVE', appliedAt: Date.now(),
};
const emptyForecast = { enabled: true, state: 'AVAILABLE', series: [] } as ForecastStatus;

describe('Forecast Demo Control Center', () => {
  beforeEach(() => vi.clearAllMocks());
  afterEach(cleanup);

  it('shows explicit loading and unverified states instead of false zero or active', async () => {
    vi.mocked(axios.get).mockResolvedValue({ data: applying });
    render(<DemoControlCenter metricCount={0} metricsLoading forecasts={null} forecastsQueried={false} />);
    expect(await screen.findByText('Not verified')).toBeTruthy();
    expect(screen.getAllByText('Loading…')).toHaveLength(3);
    expect(screen.getByText(/Applying · waiting for Kafka offsets/)).toBeTruthy();
    expect(screen.queryByText(/Active · verified/)).toBeNull();
  });

  it('requires confirmation for disruptive traffic and never reports active before the receipt', async () => {
    vi.mocked(axios.get).mockResolvedValue({ data: confirmed });
    vi.mocked(axios.put).mockResolvedValue({ data: {
      enabled: true, scenario: 'normal', requestedScenario: 'incident', phase: 'APPLYING', appliedAt: Date.now(),
    } });
    const user = userEvent.setup();
    render(<DemoControlCenter metricCount={5} metricsLoading={false}
      forecasts={emptyForecast} forecastsQueried />);
    await screen.findByText('Normal');
    await user.click(screen.getByRole('button', { name: /Manage scenarios/ }));
    await user.click(screen.getByRole('button', { name: /Incident/ }));
    const dialog = screen.getByRole('group', { name: 'Confirm traffic scenario' });
    expect(within(dialog).getByText(/Tickets and unarchived events/)).toBeTruthy();
    expect(axios.put).not.toHaveBeenCalled();
    await user.click(within(dialog).getByRole('button', { name: 'Apply scenario' }));
    await waitFor(() => expect(axios.put).toHaveBeenCalledWith('/api/demo-control',
      { scenario: 'incident' }, { timeout: 10000 }));
    expect(screen.getByText(/Applying · waiting for Kafka offsets/)).toBeTruthy();
    expect(screen.getByText(/Requested:/)).toBeTruthy();
    expect(screen.getByText('normal')).toBeTruthy();
    expect(screen.queryByText(/Active · verified against Kafka offsets/)).toBeNull();
  });

  it('does not hide a failed initial request and can recover using Retry', async () => {
    vi.mocked(axios.get).mockRejectedValueOnce(new Error('network')).mockResolvedValue({ data: confirmed });
    const user = userEvent.setup();
    render(<DemoControlCenter metricCount={0} metricsLoading={false}
      forecasts={null} forecastsQueried />);
    expect(await screen.findByRole('alert')).toHaveTextContent('unavailable');
    await user.click(screen.getByRole('button', { name: 'Retry' }));
    expect(await screen.findByText(/Active · verified against Kafka offsets/)).toBeTruthy();
    expect(screen.getAllByText('Unavailable').length).toBeGreaterThan(0);
    expect(screen.getByText('0 / 5')).toBeTruthy();
  });

  it('stays absent on the deliberate 404 when demo control is not enabled', async () => {
    vi.mocked(axios.get).mockRejectedValue({
      isAxiosError: true, response: { status: 404 },
    });
    const { container } = render(<DemoControlCenter metricCount={0} metricsLoading={false}
      forecasts={null} forecastsQueried />);
    await waitFor(() => expect(axios.get).toHaveBeenCalled());
    await waitFor(() => expect(container.querySelector('section')).toBeNull());
  });
});
