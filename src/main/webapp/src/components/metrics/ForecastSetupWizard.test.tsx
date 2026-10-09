// SPDX-License-Identifier: AGPL-3.0-or-later
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { cleanup, render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import axios from 'axios';
import { ConfirmProvider } from '../ui';
import { ForecastSetupWizard } from './ForecastSetupWizard';
vi.mock('axios', () => ({ default: { get: vi.fn(), post: vi.fn(), isAxiosError: vi.fn(() => true) } }));
const EXPORT_ONLY = 'Runtime approval is off (explorer.forecasting.pilot.runtime-approval); export the configuration instead';
const metric = { metricId: 'lag', name: 'Lag', definitionVersion: 'version', unit: 'milliseconds', transformation: 'GAUGE_MEAN',
  topics: ['orders'], groups: ['worker'], eligible: true, enrolled: false, blockers: [], suggestedStepMillis: 60000 };
describe('ForecastSetupWizard', () => {
  beforeEach(() => { vi.clearAllMocks(); vi.mocked(axios.get).mockResolvedValue({ data: { metrics: [metric], total: 1, truncated: false, clusterId: 'existing', collectorId: 'collector', applyUnavailable: EXPORT_ONLY } }); });
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
    // The option names the blocker itself, not a generic "metadata missing" whatever the cause.
    expect(screen.getByRole('option', { name: 'Lag · Set a known unit' })).toBeTruthy();
    expect(screen.getByRole('button', { name: 'Continue to sources' })).toBeDisabled(); expect(axios.post).not.toHaveBeenCalled();
  });
  it('validates all fields before review and uses existing collector identities', async () => {
    const user = await sources();
    expect(screen.getByLabelText('History cluster ID')).toHaveValue('existing');
    await user.clear(screen.getByLabelText('Environment'));
    await user.click(screen.getByRole('button', { name: 'Remove orders' }));
    await user.clear(screen.getByLabelText('Forecast horizon (points)')); await user.type(screen.getByLabelText('Forecast horizon (points)'), '61');
    await user.click(screen.getByRole('checkbox'));
    await user.click(screen.getByRole('button', { name: 'Validate and export' }));
    expect(screen.getByLabelText('Environment')).toHaveFocus();
    expect(screen.getByLabelText('Environment')).toHaveAttribute('aria-invalid', 'true');
    expect(screen.getByText('Add every source topic.')).toBeTruthy();
    expect(screen.getByLabelText('Forecast horizon (points)')).toHaveAttribute('aria-invalid', 'true');
    expect(axios.post).not.toHaveBeenCalled();
  });
  it('requires the source attestation, and only it, before validating a configuration', async () => {
    vi.mocked(axios.post).mockResolvedValue({ data: { configuration: 'explorer: {}', seriesId: 'id', instructions: ['Restart with the file'] } });
    const user = await sources();
    expect(screen.getByRole('button', { name: 'Validate and export' })).toBeDisabled();
    await user.click(screen.getByRole('checkbox')); await user.click(screen.getByRole('button', { name: 'Validate and export' }));
    expect(await screen.findByText('Restart with the file')).toBeTruthy();
    expect(screen.queryByRole('dialog')).toBeNull();
    expect(axios.post).toHaveBeenCalledWith('/api/forecasts/configuration', expect.objectContaining({ confirmed: true,
      metricId: 'lag', definitionVersion: 'version', unit: 'milliseconds', clusterId: 'existing', collectorId: 'collector',
      topics: ['orders'], groups: ['worker'], horizon: 30, threshold: null, seasonality: 'NONE' }), expect.objectContaining({ timeout: 10000 }));
    expect(screen.getByRole('button', { name: 'Download forecasts.yml' })).toBeTruthy();
  });
  it('displays server validation failures and never presents a downloadable file', async () => {
    vi.mocked(axios.post).mockRejectedValue({ response: { data: { reason: 'Metric changed; reload candidates and review again' } } });
    const user = await sources();
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
    const details = screen.getByText('Advanced: sampling, horizon, threshold and history identities').closest('details');
    expect(details).not.toHaveAttribute('open');
    await user.clear(screen.getByLabelText('History collector ID'));
    await user.click(screen.getByRole('checkbox'));
    await user.click(screen.getByRole('button', { name: 'Validate and export' }));
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
    await user.click(screen.getByRole('checkbox')); await user.click(screen.getByRole('button', { name: 'Validate and export' }));
    await screen.findByRole('button', { name: 'Copy configuration' });
    expect(axios.post).toHaveBeenCalledWith('/api/forecasts/configuration',
      expect.objectContaining({ topics: ['orders', 'payments'], groups: ['billing'] }), expect.anything());
  });
  it('numbers the steps after export and offers a copy beside the download', async () => {
    vi.mocked(axios.post).mockResolvedValue({ data: { configuration: 'explorer: {}', seriesId: 'id', instructions: ['Merge the file', 'Restart'] } });
    const user = await sources();
    await user.click(screen.getByRole('checkbox')); await user.click(screen.getByRole('button', { name: 'Validate and export' }));
    const steps = (await screen.findByText('Next steps')).nextElementSibling;
    expect(steps?.tagName).toBe('OL');
    expect([...steps!.querySelectorAll('li')].map(li => li.textContent)).toEqual([
      'Download or copy the configuration below.', 'Merge the file', 'Restart',
      'Come back to Metrics Forecast: history progress appears here once the restarted pilot collects.']);
    expect(screen.getByRole('button', { name: 'Copy configuration' })).toBeTruthy();
    expect(screen.getByRole('button', { name: 'Download forecasts.yml' })).toBeTruthy();
  });
  it('offers only the cycles the sampling interval can carry, and sends the one chosen', async () => {
    vi.mocked(axios.post).mockResolvedValue({ data: { configuration: 'explorer: {}', seriesId: 'id', instructions: [] } });
    const user = await sources();
    const cycle = screen.getByLabelText('Repeating cycle');
    expect([...cycle.querySelectorAll('option')].map(o => o.textContent)).toEqual(['None', 'Hourly']);
    await user.selectOptions(screen.getByLabelText('Sampling interval'), '300000');
    expect([...cycle.querySelectorAll('option')].map(o => o.textContent)).toEqual(['None', 'Hourly', 'Daily']);
    await user.selectOptions(cycle, 'DAILY');
    await user.selectOptions(screen.getByLabelText('Sampling interval'), '60000');
    expect(cycle).toHaveValue('NONE');
    await user.selectOptions(cycle, 'HOURLY');
    await user.click(screen.getByRole('checkbox')); await user.click(screen.getByRole('button', { name: 'Validate and export' }));
    await screen.findByRole('button', { name: 'Copy configuration' });
    expect(axios.post).toHaveBeenCalledWith('/api/forecasts/configuration', expect.objectContaining({ seasonality: 'HOURLY', stepMillis: 60000 }), expect.anything());
  });
  it('starts the forecast directly when the deployment allows it, without a file', async () => {
    vi.mocked(axios.get).mockResolvedValue({ data: { metrics: [{ ...metric, suggestedStepMillis: 300000 }], total: 1, truncated: false,
      clusterId: 'existing', collectorId: 'collector', applyUnavailable: null } });
    vi.mocked(axios.post).mockResolvedValue({ data: { seriesId: 'id' } });
    const onApplied = vi.fn();
    const user = userEvent.setup(); render(<ConfirmProvider><ForecastSetupWizard onApplied={onApplied} /></ConfirmProvider>);
    await user.selectOptions(await screen.findByLabelText('Candidate metric'), 'lag');
    await user.click(screen.getByRole('button', { name: 'Continue to sources' }));
    expect(screen.getByText('Step 2 of 2 · Confirm and start')).toBeTruthy();
    expect(screen.getByLabelText('Sampling interval')).toHaveValue('300000');
    expect(screen.getByRole('button', { name: 'Start forecasting' })).toBeDisabled();
    await user.click(screen.getByRole('checkbox')); await user.click(screen.getByRole('button', { name: 'Start forecasting' }));
    expect(await screen.findByText('Forecast started for Lag.')).toBeTruthy();
    expect(axios.post).toHaveBeenCalledWith('/api/forecasts/series', expect.objectContaining({ metricId: 'lag', stepMillis: 300000, confirmed: true }),
      expect.objectContaining({ timeout: 10000 }));
    expect(onApplied).toHaveBeenCalledOnce();
    expect(screen.queryByRole('button', { name: 'Download forecasts.yml' })).toBeNull();
  });
  it('still offers the export beside a direct start', async () => {
    vi.mocked(axios.get).mockResolvedValue({ data: { metrics: [metric], total: 1, truncated: false, clusterId: 'existing', collectorId: 'collector', applyUnavailable: null } });
    vi.mocked(axios.post).mockResolvedValue({ data: { configuration: 'explorer: {}', seriesId: 'id', instructions: [] } });
    const user = await sources();
    await user.click(screen.getByRole('checkbox')); await user.click(screen.getByRole('button', { name: 'Export YAML instead' }));
    expect(await screen.findByRole('button', { name: 'Download forecasts.yml' })).toBeTruthy();
    expect(axios.post).toHaveBeenCalledWith('/api/forecasts/configuration', expect.anything(), expect.anything());
  });
  it('keeps everything with a sound default folded away', async () => {
    await sources();
    const details = screen.getByText('Advanced: sampling, horizon, threshold and history identities').closest('details')!;
    for (const label of ['Sampling interval', 'Repeating cycle', 'Forecast horizon (points)', 'Optional threshold in forecast output units', 'History cluster ID'])
      expect(details.contains(screen.getByLabelText(label))).toBe(true);
    expect(details.contains(screen.getByLabelText('Environment'))).toBe(false);
  });
});
