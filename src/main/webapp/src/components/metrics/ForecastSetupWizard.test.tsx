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
    await user.click(screen.getByRole('button', { name: 'Remove orders' }));
    await user.clear(screen.getByLabelText('Forecast horizon (points)')); await user.type(screen.getByLabelText('Forecast horizon (points)'), '61');
    await user.click(screen.getByRole('button', { name: 'Review configuration' }));
    expect(screen.getByLabelText('Environment')).toHaveFocus();
    expect(screen.getByLabelText('Environment')).toHaveAttribute('aria-invalid', 'true');
    expect(screen.getByText('Add every source topic.')).toBeTruthy();
    expect(screen.getByLabelText('Forecast horizon (points)')).toHaveAttribute('aria-invalid', 'true');
    expect(axios.post).not.toHaveBeenCalled();
  });
  it('requires the source attestation, and only it, before validating a configuration', async () => {
    vi.mocked(axios.post).mockResolvedValue({ data: { configuration: 'explorer: {}', seriesId: 'id', instructions: ['Restart with the file'] } });
    const user = await sources(); await user.click(screen.getByRole('button', { name: 'Review configuration' }));
    expect(screen.getByRole('button', { name: 'Validate and export' })).toBeDisabled();
    await user.click(screen.getByRole('checkbox')); await user.click(screen.getByRole('button', { name: 'Validate and export' }));
    expect(await screen.findByText('Restart with the file')).toBeTruthy();
    expect(screen.queryByRole('dialog')).toBeNull();
    expect(axios.post).toHaveBeenCalledWith('/api/forecasts/configuration', expect.objectContaining({ confirmed: true,
      metricId: 'lag', definitionVersion: 'version', unit: 'milliseconds', clusterId: 'existing', collectorId: 'collector',
      topics: ['orders'], groups: ['worker'], horizon: 30, threshold: null }), expect.objectContaining({ timeout: 10000 }));
    expect(screen.getByRole('button', { name: 'Download forecasts.yml' })).toBeTruthy();
  });
  it('displays server validation failures and never presents a downloadable file', async () => {
    vi.mocked(axios.post).mockRejectedValue({ response: { data: { reason: 'Metric changed; reload candidates and review again' } } });
    const user = await sources(); await user.click(screen.getByRole('button', { name: 'Review configuration' }));
    await user.click(screen.getByRole('checkbox')); await user.click(screen.getByRole('button', { name: 'Validate and export' }));
    expect(await screen.findByRole('alert')).toHaveTextContent('Metric changed');
    expect(screen.queryByRole('button', { name: 'Download forecasts.yml' })).toBeNull();
  });
  it('prefills the requested metric and known sources without approving an export', async () => {
    const user = userEvent.setup(); render(<ConfirmProvider><ForecastSetupWizard initialMetricId="lag" /></ConfirmProvider>);
    expect(await screen.findByRole('button', { name: 'Continue to sources' })).toBeEnabled();
    expect(screen.getByLabelText('Candidate metric')).toHaveValue('lag');
    await user.click(screen.getByRole('button', { name: 'Continue to sources' }));
    expect(screen.getByRole('list', { name: 'All source topics: selected' })).toHaveTextContent('orders');
    expect(screen.getByRole('list', { name: 'All consumer groups (optional): selected' })).toHaveTextContent('worker');
    await user.click(screen.getByRole('button', { name: 'Review configuration' }));
    expect(screen.getByRole('checkbox')).not.toBeChecked();
    expect(screen.getByRole('button', { name: 'Validate and export' })).toBeDisabled();
    expect(axios.post).not.toHaveBeenCalled();
  });
  it('keeps eligibility blockers for the requested metric', async () => {
    vi.mocked(axios.get).mockResolvedValue({ data: { metrics: [{ ...metric, eligible: false, blockers: ['Set a known unit'] }], total: 1 } });
    render(<ConfirmProvider><ForecastSetupWizard initialMetricId="lag" /></ConfirmProvider>);
    expect(await screen.findByText('Set a known unit')).toBeTruthy();
    expect(screen.getByRole('button', { name: 'Continue to sources' })).toBeDisabled();
    expect(axios.post).not.toHaveBeenCalled();
  });
  it('does not select another metric when the requested one is absent', async () => {
    render(<ConfirmProvider><ForecastSetupWizard initialMetricId="missing" /></ConfirmProvider>);
    expect(await screen.findByRole('alert')).toHaveTextContent('Selected metric is absent');
    expect(screen.getByLabelText('Candidate metric')).toHaveValue('');
    expect(axios.post).not.toHaveBeenCalled();
  });
  it('aborts catalogue reads on close', () => {
    vi.mocked(axios.get).mockReturnValue(new Promise(() => {})); const view = render(<ForecastSetupWizard />);
    const options = vi.mocked(axios.get).mock.calls[0][1]; view.unmount(); expect(options?.signal?.aborted).toBe(true);
  });
  it('offers sampling intervals by name and states the horizon as a duration', async () => {
    const user = await sources();
    await user.selectOptions(screen.getByLabelText('Sampling interval'), '300000');
    expect(screen.getByText('Forecasts 2.5 hours ahead')).toBeTruthy();
    await user.clear(screen.getByLabelText('Forecast horizon (points)')); await user.type(screen.getByLabelText('Forecast horizon (points)'), '6');
    expect(screen.getByText('Forecasts 30 minutes ahead')).toBeTruthy();
  });
  it('keeps the history identities folded away, and opens them when one is invalid', async () => {
    const user = await sources();
    const details = screen.getByText('Advanced: history identities').closest('details');
    expect(details).not.toHaveAttribute('open');
    await user.clear(screen.getByLabelText('History collector ID'));
    await user.click(screen.getByRole('button', { name: 'Review configuration' }));
    expect(details).toHaveAttribute('open');
    expect(screen.getByLabelText('History collector ID')).toHaveFocus();
  });
  it('edits sources as chips: add with Enter, ignore duplicates, remove one', async () => {
    vi.mocked(axios.post).mockResolvedValue({ data: { configuration: 'explorer: {}', seriesId: 'id', instructions: [] } });
    const user = await sources();
    await user.type(screen.getByLabelText('All source topics'), 'payments{Enter}');
    await user.type(screen.getByLabelText('All source topics'), 'orders{Enter}');
    await user.type(screen.getByLabelText('All consumer groups (optional)'), 'billing{Enter}');
    await user.click(screen.getByRole('button', { name: 'Remove worker' }));
    expect(screen.getByRole('list', { name: 'All source topics: selected' }).querySelectorAll('li')).toHaveLength(2);
    await user.click(screen.getByRole('button', { name: 'Review configuration' }));
    await user.click(screen.getByRole('checkbox')); await user.click(screen.getByRole('button', { name: 'Validate and export' }));
    await screen.findByRole('button', { name: 'Copy configuration' });
    expect(axios.post).toHaveBeenCalledWith('/api/forecasts/configuration',
      expect.objectContaining({ topics: ['orders', 'payments'], groups: ['billing'] }), expect.anything());
  });
  it('numbers the steps after export and offers a copy beside the download', async () => {
    vi.mocked(axios.post).mockResolvedValue({ data: { configuration: 'explorer: {}', seriesId: 'id', instructions: ['Merge the file', 'Restart'] } });
    const user = await sources(); await user.click(screen.getByRole('button', { name: 'Review configuration' }));
    await user.click(screen.getByRole('checkbox')); await user.click(screen.getByRole('button', { name: 'Validate and export' }));
    const steps = (await screen.findByText('Next steps')).nextElementSibling;
    expect(steps?.tagName).toBe('OL');
    expect([...steps!.querySelectorAll('li')].map(li => li.textContent)).toEqual([
      'Download or copy the configuration below.', 'Merge the file', 'Restart',
      'Come back to Metrics Forecast: history progress appears here once the restarted pilot collects.']);
    expect(screen.getByRole('button', { name: 'Copy configuration' })).toBeTruthy();
    expect(screen.getByRole('button', { name: 'Download forecasts.yml' })).toBeTruthy();
  });
});
