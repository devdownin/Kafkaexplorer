// SPDX-License-Identifier: AGPL-3.0-or-later
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { cleanup, render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import axios from 'axios';
import { ConfirmProvider } from '../ui';
import { ForecastSetupWizard } from './ForecastSetupWizard';
vi.mock('axios', () => ({ default: { get: vi.fn(), post: vi.fn(), isAxiosError: vi.fn(() => true) } }));
const metric = { metricId: 'lag', name: 'Lag', definitionVersion: 'version', unit: 'milliseconds', transformation: 'GAUGE_MEAN',
  topics: ['orders'], groups: ['worker'], eligible: true, enrolled: false, blockers: [] };
describe('ForecastSetupWizard', () => {
  beforeEach(() => { vi.clearAllMocks(); vi.mocked(axios.get).mockResolvedValue({ data: { metrics: [metric], total: 1, truncated: false, clusterId: 'existing', collectorId: 'collector' } }); });
  afterEach(cleanup);
  async function sources() {
    const user = userEvent.setup(); render(<ConfirmProvider><ForecastSetupWizard /></ConfirmProvider>);
    await user.selectOptions(await screen.findByLabelText('Candidate metric'), 'lag');
    await user.click(screen.getByRole('button', { name: 'Continue to sources' })); return user;
  }
  it('shows metadata blockers and refuses to continue', async () => {
    vi.mocked(axios.get).mockResolvedValue({ data: { metrics: [{ ...metric, eligible: false, blockers: ['Set a known unit'] }], total: 1 } });
    const user = userEvent.setup(); render(<ConfirmProvider><ForecastSetupWizard /></ConfirmProvider>);
    await user.selectOptions(await screen.findByLabelText('Candidate metric'), 'lag');
    expect(screen.getByText('Set a known unit')).toBeTruthy();
    expect(screen.getByRole('button', { name: 'Continue to sources' })).toBeDisabled(); expect(axios.post).not.toHaveBeenCalled();
  });
  it('validates all fields before review and uses existing collector identities', async () => {
    const user = await sources();
    expect(screen.getByLabelText('History cluster ID')).toHaveValue('existing');
    await user.clear(screen.getByLabelText('Environment'));
    await user.clear(screen.getByLabelText('All source topics (comma separated)'));
    await user.clear(screen.getByLabelText('Forecast horizon (points)')); await user.type(screen.getByLabelText('Forecast horizon (points)'), '61');
    await user.click(screen.getByRole('button', { name: 'Review configuration' }));
    expect(screen.getByLabelText('Environment')).toHaveFocus();
    expect(screen.getByLabelText('Environment')).toHaveAttribute('aria-invalid', 'true');
    expect(screen.getByLabelText('Forecast horizon (points)')).toHaveAttribute('aria-invalid', 'true');
    expect(axios.post).not.toHaveBeenCalled();
  });
  it('requires source attestation and confirmation before validating a configuration', async () => {
    vi.mocked(axios.post).mockResolvedValue({ data: { configuration: 'explorer: {}', seriesId: 'id', instructions: ['Restart with the file'] } });
    const user = await sources(); await user.click(screen.getByRole('button', { name: 'Review configuration' }));
    expect(screen.getByRole('button', { name: 'Validate and export' })).toBeDisabled();
    await user.click(screen.getByRole('checkbox')); await user.click(screen.getByRole('button', { name: 'Validate and export' }));
    expect(axios.post).not.toHaveBeenCalled();
    await user.click(screen.getByRole('button', { name: 'Export configuration' }));
    expect(await screen.findByText('Restart with the file')).toBeTruthy();
    expect(axios.post).toHaveBeenCalledWith('/api/forecasts/configuration', expect.objectContaining({ confirmed: true,
      metricId: 'lag', definitionVersion: 'version', unit: 'milliseconds', clusterId: 'existing', collectorId: 'collector',
      topics: ['orders'], groups: ['worker'], horizon: 30, threshold: null }), expect.objectContaining({ timeout: 10000 }));
    expect(screen.getByRole('button', { name: 'Download forecasts.yml' })).toBeTruthy();
  });
  it('displays server validation failures and never presents a downloadable file', async () => {
    vi.mocked(axios.post).mockRejectedValue({ response: { data: { reason: 'Metric changed; reload candidates and review again' } } });
    const user = await sources(); await user.click(screen.getByRole('button', { name: 'Review configuration' }));
    await user.click(screen.getByRole('checkbox')); await user.click(screen.getByRole('button', { name: 'Validate and export' }));
    await user.click(screen.getByRole('button', { name: 'Export configuration' }));
    expect(await screen.findByRole('alert')).toHaveTextContent('Metric changed');
    expect(screen.queryByRole('button', { name: 'Download forecasts.yml' })).toBeNull();
  });
  it('aborts catalogue reads on close', () => {
    vi.mocked(axios.get).mockReturnValue(new Promise(() => {})); const view = render(<ForecastSetupWizard />);
    const options = vi.mocked(axios.get).mock.calls[0][1]; view.unmount(); expect(options?.signal?.aborted).toBe(true);
  });
});
