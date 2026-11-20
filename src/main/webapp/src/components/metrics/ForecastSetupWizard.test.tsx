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
  beforeEach(() => { vi.clearAllMocks(); vi.mocked(axios.get).mockResolvedValue({ data: { metrics: [metric], total: 1, truncated: false, clusterId: 'existing', collectorId: 'collector', applyUnavailable: EXPORT_ONLY, minimumContextPoints: 128 } }); });
  afterEach(cleanup);
  async function sources() {
    const user = userEvent.setup(); render(<ConfirmProvider><ForecastSetupWizard /></ConfirmProvider>);
    // Choosing the metric is what reveals the form: there is no second step to continue to.
    await user.selectOptions(await screen.findByLabelText('Metric'), 'lag'); return user;
  }
  it('creates a forecast directly when opened from an eligible metric card', async () => {
    vi.mocked(axios.get).mockResolvedValue({ data: { metrics: [metric], total: 1, truncated: false,
      clusterId: 'existing', collectorId: 'collector', applyUnavailable: null, minimumContextPoints: 128 } });
    vi.mocked(axios.post).mockResolvedValue({ data: { seriesId: 'forecast-lag' } });
    const onApplied = vi.fn();
    render(<ConfirmProvider><ForecastSetupWizard initialMetricId="lag" onApplied={onApplied} /></ConfirmProvider>);
    expect(await screen.findByText('Forecast started for Lag.')).toBeTruthy();
    expect(axios.post).toHaveBeenCalledTimes(1);
    expect(axios.post).toHaveBeenCalledWith('/api/forecasts/series', expect.objectContaining({
      metricId: 'lag', definitionVersion: 'version', unit: 'milliseconds',
      clusterId: 'existing', collectorId: 'collector', topics: ['orders'], groups: ['worker'],
      stepMillis: 60000, horizon: 30, seasonality: 'NONE', confirmed: true,
    }), expect.objectContaining({ timeout: 10000 }));
    expect(onApplied).toHaveBeenCalledWith('forecast-lag');
  });
  it('creates it with the cycle the history shows, as the form would propose it', async () => {
    const candidates = { metrics: [metric], total: 1, truncated: false,
      clusterId: 'existing', collectorId: 'collector', applyUnavailable: null, minimumContextPoints: 128 };
    vi.mocked(axios.get).mockImplementation((url: string) => Promise.resolve({ data: url.endsWith('/history')
      ? { status: 'READY', reason: 'ok', observedPoints: 600, requiredPoints: 128, unit: 'milliseconds', points: [],
        suggestedSeasonality: 'HOURLY', correlation: 0.9 }
      : candidates }));
    vi.mocked(axios.post).mockResolvedValue({ data: { seriesId: 'forecast-lag' } });
    render(<ConfirmProvider><ForecastSetupWizard initialMetricId="lag" /></ConfirmProvider>);
    expect(await screen.findByText('Forecast started for Lag.')).toBeTruthy();
    expect(axios.post).toHaveBeenCalledWith('/api/forecasts/series',
      expect.objectContaining({ seasonality: 'HOURLY' }), expect.anything());
  });
  // It promised "after 128 points, ~2 h" whatever was already recorded.
  it('counts the history already recorded when it says when the first forecast follows', async () => {
    const candidates = { metrics: [metric], total: 1, truncated: false,
      clusterId: 'existing', collectorId: 'collector', applyUnavailable: null, minimumContextPoints: 128 };
    for (const [observed, sentence] of [[600, 'Its first forecast is computed at the next cycle, from the history already recorded'],
      [100, '100 of the 128 points it needs are already recorded; its first forecast follows in ~28 min']] as const) {
      vi.mocked(axios.get).mockImplementation((url: string) => Promise.resolve({ data: url.endsWith('/history')
        ? { status: 'READY', reason: 'ok', observedPoints: observed, requiredPoints: 128, unit: 'milliseconds', points: [],
          suggestedSeasonality: null, correlation: null }
        : candidates }));
      vi.mocked(axios.post).mockResolvedValue({ data: { seriesId: 'forecast-lag' } });
      render(<ConfirmProvider><ForecastSetupWizard initialMetricId="lag" /></ConfirmProvider>);
      expect(await screen.findByText(new RegExp(sentence.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')))).toBeTruthy();
      cleanup();
    }
  });
  it('still creates it without a cycle when the history cannot be read', async () => {
    const candidates = { metrics: [metric], total: 1, truncated: false,
      clusterId: 'existing', collectorId: 'collector', applyUnavailable: null, minimumContextPoints: 128 };
    vi.mocked(axios.get).mockImplementation((url: string) => url.endsWith('/history')
      ? Promise.reject(new Error('down')) : Promise.resolve({ data: candidates }));
    vi.mocked(axios.post).mockResolvedValue({ data: { seriesId: 'forecast-lag' } });
    render(<ConfirmProvider><ForecastSetupWizard initialMetricId="lag" /></ConfirmProvider>);
    expect(await screen.findByText('Forecast started for Lag.')).toBeTruthy();
    expect(axios.post).toHaveBeenCalledWith('/api/forecasts/series',
      expect.objectContaining({ seasonality: 'NONE' }), expect.anything());
  });
  it('keeps the editable assistant if automatic creation is refused', async () => {
    vi.mocked(axios.get).mockResolvedValue({ data: { metrics: [metric], total: 1,
      clusterId: 'existing', collectorId: 'collector', applyUnavailable: null, minimumContextPoints: 128 } });
    vi.mocked(axios.post).mockRejectedValue({ response: { data: { reason: 'History not available' } } });
    render(<ConfirmProvider><ForecastSetupWizard initialMetricId="lag" /></ConfirmProvider>);
    expect(await screen.findByRole('alert')).toHaveTextContent('History not available');
    expect(screen.getByRole('button', { name: 'Start forecasting' })).toBeTruthy();
  });
  it('does not automatically create a series already present', async () => {
    vi.mocked(axios.get).mockResolvedValue({ data: { metrics: [metric], total: 1,
      clusterId: 'existing', collectorId: 'collector', applyUnavailable: null } });
    render(<ConfirmProvider><ForecastSetupWizard initialMetricId="lag" forecastMetricIds={['lag']} /></ConfirmProvider>);
    expect(await screen.findByRole('option', { name: /already forecast/ })).toBeDisabled();
    expect(axios.post).not.toHaveBeenCalled();
  });
  it('shows metadata blockers in place of the form', async () => {
    vi.mocked(axios.get).mockResolvedValue({ data: { metrics: [{ ...metric, eligible: false, blockers: ['Set a known unit'] }], total: 1 } });
    const user = userEvent.setup(); render(<ConfirmProvider><ForecastSetupWizard /></ConfirmProvider>);
    await user.selectOptions(await screen.findByLabelText('Metric'), 'lag');
    expect(screen.getByText('Set a known unit')).toBeTruthy();
    // The option names the blocker itself, not a generic "metadata missing" whatever the cause.
    expect(screen.getByRole('option', { name: 'Lag · Set a known unit' })).toBeTruthy();
    expect(screen.getByRole('group', { name: 'What blocks this metric' })).toHaveTextContent('cannot be forecast yet');
    expect(screen.queryByRole('checkbox')).toBeNull(); expect(screen.queryByLabelText('Environment')).toBeNull();
  });
  it('names a configurable metric alone, and says only what stands in the way of another', async () => {
    render(<ConfirmProvider><ForecastSetupWizard /></ConfirmProvider>);
    expect(await screen.findByRole('option', { name: 'Lag' })).toBeTruthy();
  });
  it('validates all fields before review and uses existing collector identities', async () => {
    const user = await sources();
    expect(screen.getByLabelText('History cluster ID')).toHaveValue('existing');
    await user.clear(screen.getByLabelText('Environment'));
    await user.click(screen.getByRole('button', { name: 'Remove orders' }));
    await user.click(screen.getByRole('checkbox'));
    await user.click(screen.getByRole('button', { name: 'Validate and export' }));
    expect(screen.getByText('Add every source topic.')).toBeTruthy();
    expect(screen.getByLabelText('Environment')).toHaveAttribute('aria-invalid', 'true');
    // Environment is folded away; a bad one opens the fold and takes focus all the same.
    expect(screen.getByLabelText('Environment').closest('details')).toHaveAttribute('open');
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
    render(<ConfirmProvider><ForecastSetupWizard initialMetricId="lag" /></ConfirmProvider>);
    // Opened for one eligible metric, its sources are there at once (METRICS-FORECAST-UX-AUDIT.md U4).
    expect(await screen.findByRole('list', { name: 'All source topics: selected' })).toHaveTextContent('orders');
    expect(screen.getByRole('list', { name: 'All consumer groups (optional): selected' })).toHaveTextContent('worker');
    expect(screen.getByRole('checkbox')).not.toBeChecked();
    expect(screen.getByRole('button', { name: 'Validate and export' })).toBeDisabled();
    expect(axios.post).not.toHaveBeenCalled();
  });
  it('keeps eligibility blockers for the requested metric', async () => {
    vi.mocked(axios.get).mockResolvedValue({ data: { metrics: [{ ...metric, eligible: false, blockers: ['Set a known unit'] }], total: 1 } });
    render(<ConfirmProvider><ForecastSetupWizard initialMetricId="lag" /></ConfirmProvider>);
    expect(await screen.findByText('Set a known unit')).toBeTruthy();
    expect(screen.queryByRole('button', { name: 'Validate and export' })).toBeNull();
    expect(axios.post).not.toHaveBeenCalled();
  });
  it('does not select another metric when the requested one is absent', async () => {
    render(<ConfirmProvider><ForecastSetupWizard initialMetricId="missing" /></ConfirmProvider>);
    expect(await screen.findByRole('alert')).toHaveTextContent('Selected metric is absent');
    expect(screen.getByLabelText('Metric')).toHaveValue('');
    expect(axios.post).not.toHaveBeenCalled();
  });
  it('aborts catalogue reads on close', () => {
    vi.mocked(axios.get).mockReturnValue(new Promise(() => {})); const view = render(<ForecastSetupWizard />);
    const options = vi.mocked(axios.get).mock.calls[0][1]; view.unmount(); expect(options?.signal?.aborted).toBe(true);
  });
  it('asks how far ahead in time, and offers only the durations the interval can carry', async () => {
    const user = await sources();
    const ahead = screen.getByLabelText('Looks ahead');
    // A minute carries up to 60 points: an hour at most.
    expect([...ahead.querySelectorAll('option')].map(o => o.textContent)).toEqual(['15 minutes', '30 minutes', '1 hour']);
    await user.selectOptions(ahead, String(3600000));
    expect(screen.getByLabelText('Forecast summary')).toHaveTextContent('60 minutes');
    // Five minutes carry five hours; the hour chosen stays.
    await user.selectOptions(screen.getByLabelText('Sampling interval'), '300000');
    expect(ahead).toHaveValue('3600000');
    expect(screen.getByLabelText('Forecast summary')).toHaveTextContent('12 points');
    expect([...ahead.querySelectorAll('option')].map(o => o.textContent)).toContain('2 hours');
    // Back to thirty seconds: an hour is 120 points, so the longest that fits takes its place.
    await user.selectOptions(screen.getByLabelText('Sampling interval'), '30000');
    expect(ahead).toHaveValue('1800000');
  });
  it('keeps the history identities folded away, and opens them when one is invalid', async () => {
    const user = await sources();
    const details = screen.getByText('Advanced: interval, horizon, threshold, environment and history identities').closest('details');
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
    await user.click(screen.getByRole('button', { name: 'Remove billing' }));
    expect(screen.getByRole('list', { name: 'All source topics: selected' }).querySelectorAll('li')).toHaveLength(2);
    await user.click(screen.getByRole('checkbox')); await user.click(screen.getByRole('button', { name: 'Validate and export' }));
    await screen.findByRole('button', { name: 'Copy configuration' });
    expect(axios.post).toHaveBeenCalledWith('/api/forecasts/configuration',
      expect.objectContaining({ topics: ['orders', 'payments'], groups: ['worker'] }), expect.anything());
  });
  it('refuses, on the field, a declaration that drops a topic or group the metric reads', async () => {
    // The server answers this with a page-level 400 that names no field.
    const user = await sources();
    await user.click(screen.getByRole('button', { name: 'Remove orders' }));
    await user.type(screen.getByLabelText('All source topics'), 'payments{Enter}');
    await user.click(screen.getByRole('button', { name: 'Remove worker' }));
    await user.click(screen.getByRole('checkbox')); await user.click(screen.getByRole('button', { name: 'Validate and export' }));
    expect(screen.getByText('Keep every topic the metric reads: orders.')).toBeTruthy();
    expect(screen.getByText('Keep every consumer group the metric reads: worker.')).toBeTruthy();
    expect(axios.post).not.toHaveBeenCalled();
  });
  it('says why starting directly is unavailable, and names no mode before the catalogue answers', async () => {
    let answer: (value: unknown) => void = () => {};
    vi.mocked(axios.get).mockReturnValue(new Promise(resolve => { answer = resolve; }));
    render(<ConfirmProvider><ForecastSetupWizard /></ConfirmProvider>);
    expect(screen.queryByText(/Exports the selected series/)).toBeNull();
    expect(screen.queryByText(/Starts the forecast directly/)).toBeNull();
    answer({ data: { metrics: [metric], total: 1, truncated: false, applyUnavailable: EXPORT_ONLY } });
    // Said beside the button it explains, once a metric gives the form a button.
    await userEvent.setup().selectOptions(await screen.findByLabelText('Metric'), 'lag');
    expect(screen.getByText(/Exports the selected series/)).toHaveTextContent(EXPORT_ONLY);
  });
  it('lets the operator configure another metric once one is exported', async () => {
    vi.mocked(axios.post).mockResolvedValue({ data: { configuration: 'explorer: {}', seriesId: 'id', instructions: [] } });
    const user = await sources();
    await user.click(screen.getByRole('checkbox')); await user.click(screen.getByRole('button', { name: 'Validate and export' }));
    expect(await screen.findByRole('heading', { name: 'Configuration ready' })).toBeTruthy();
    await user.click(screen.getByRole('button', { name: 'Configure another metric' }));
    expect(screen.queryByRole('heading', { name: 'Configuration ready' })).toBeNull();
    expect(screen.getByLabelText('Metric')).toHaveValue('');
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
      clusterId: 'existing', collectorId: 'collector', applyUnavailable: null, minimumContextPoints: 128 } });
    vi.mocked(axios.post).mockResolvedValue({ data: { seriesId: 'id' } });
    const onApplied = vi.fn();
    const user = userEvent.setup(); render(<ConfirmProvider><ForecastSetupWizard onApplied={onApplied} /></ConfirmProvider>);
    await user.selectOptions(await screen.findByLabelText('Metric'), 'lag');
    expect(screen.getByLabelText('Sampling interval')).toHaveValue('300000');
    expect(screen.getByRole('button', { name: 'Start forecasting' })).toBeDisabled();
    await user.click(screen.getByRole('checkbox')); await user.click(screen.getByRole('button', { name: 'Start forecasting' }));
    expect(await screen.findByText('Forecast started for Lag.')).toBeTruthy();
    expect(axios.post).toHaveBeenCalledWith('/api/forecasts/series', expect.objectContaining({ metricId: 'lag', stepMillis: 300000, confirmed: true }),
      expect.objectContaining({ timeout: 10000 }));
    // The series it started, so the page can show that one; and when its first forecast is due.
    expect(onApplied).toHaveBeenCalledExactlyOnceWith('id');
    expect(screen.getByRole('status')).toHaveTextContent('first forecast follows after 128 points, ~11 h of history');
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
    const details = screen.getByText('Advanced: interval, horizon, threshold, environment and history identities').closest('details')!;
    for (const label of ['Sampling interval', 'Repeating cycle', 'Looks ahead', 'Threshold (optional)', 'History cluster ID', 'Environment'])
      expect(details.contains(screen.getByLabelText(label))).toBe(true);
    // What stays outside it is the metric and the sources: the two things with no sound default.
    expect(details.contains(screen.getByLabelText('Metric'))).toBe(false);
    expect(details.contains(screen.getByLabelText('All source topics'))).toBe(false);
  });

  it('summarises what the defaults give, and follows the fold that changes them', async () => {
    const user = await sources();
    const summary = screen.getByLabelText('Forecast summary');
    for (const text of ['Average per interval', 'milliseconds', '1 minute', '30 minutes', '128 points', '~2 h of history, recorded from now'])
      expect(summary).toHaveTextContent(text);
    // The pilot's terms stay out of the page.
    expect(summary).not.toHaveTextContent('GAUGE_MEAN');
    await user.selectOptions(screen.getByLabelText('Sampling interval'), '300000');
    await user.selectOptions(screen.getByLabelText('Repeating cycle'), 'DAILY');
    // One whole cycle when it is longer than the floor: 288 five-minute points in a day.
    for (const text of ['5 minutes', 'Daily cycle', '30 minutes', '6 points', '288 points', '~24 h of history'])
      expect(summary).toHaveTextContent(text);
  });
  it('omits the first-forecast estimate from a server that does not state the floor', async () => {
    vi.mocked(axios.get).mockResolvedValue({ data: { metrics: [{ ...metric, enrolled: true }], total: 1, truncated: false, applyUnavailable: null } });
    await sources();
    expect(screen.getByLabelText('Forecast summary')).not.toHaveTextContent('points,');
    expect(screen.getByLabelText('Forecast summary')).toHaveTextContent('First forecast after—');
  });
  it('makes starting the one primary action, with no step to go back to', async () => {
    await sources();
    expect(screen.getByRole('button', { name: 'Validate and export' })).toHaveAttribute('type', 'submit');
    expect(screen.queryByRole('button', { name: 'Back' })).toBeNull();
    expect(screen.queryByText(/Step \d of/)).toBeNull();
  });

  // The history the metric already has, read at the chosen interval.
  const HISTORY = { status: 'READY', reason: 'Ready', observedPoints: 300, requiredPoints: 128, unit: 'milliseconds',
    suggestedSeasonality: 'DAILY', correlation: 0.91,
    points: Array.from({ length: 40 }, (_, i) => ({ endAt: 1_000_000 + i * 60000, value: 100 + i, imputed: false, samples: 1 })) };
  function withHistory(history: unknown) {
    vi.mocked(axios.get).mockImplementation(url => Promise.resolve({ data: String(url).endsWith('/history') ? history
      : { metrics: [{ ...metric, suggestedStepMillis: 300000 }], total: 1, truncated: false, clusterId: 'existing', collectorId: 'collector',
        applyUnavailable: null, minimumContextPoints: 128 } }));
  }
  it('previews the recorded history at the interval, and says how much of the first forecast it covers', async () => {
    withHistory(HISTORY); await sources();
    const figure = await screen.findByRole('figure', { name: 'Recorded history' });
    expect(figure).toHaveTextContent('300 of the 128 points a first forecast needs');
    expect(vi.mocked(axios.get)).toHaveBeenCalledWith('/api/forecasts/candidates/lag/history',
      expect.objectContaining({ params: { stepMillis: 300000 } }));
  });
  it('says why there is nothing to draw instead of an empty chart', async () => {
    withHistory({ ...HISTORY, status: 'WARMING_UP', reason: 'Collecting history: 3 of the 128 points', points: [], suggestedSeasonality: null, correlation: null });
    await sources();
    expect(await screen.findByRole('figure', { name: 'Recorded history' })).toHaveTextContent('Collecting history: 3 of the 128 points');
  });
  it('proposes the cycle the history shows, unless the operator has chosen one', async () => {
    withHistory(HISTORY); await sources();
    await vi.waitFor(() => expect(screen.getByLabelText('Repeating cycle')).toHaveValue('DAILY'));
    expect(screen.getByLabelText('Forecast summary')).toHaveTextContent('Daily cycle, found in the history');
    cleanup(); vi.clearAllMocks();
    withHistory(HISTORY);
    const user = userEvent.setup(); render(<ConfirmProvider><ForecastSetupWizard /></ConfirmProvider>);
    await user.selectOptions(await screen.findByLabelText('Metric'), 'lag');
    await user.selectOptions(screen.getByLabelText('Repeating cycle'), 'NONE');
    await user.selectOptions(screen.getByLabelText('Sampling interval'), '900000');
    await vi.waitFor(() => expect(vi.mocked(axios.get)).toHaveBeenCalledWith(expect.stringContaining('/history'), expect.objectContaining({ params: { stepMillis: 900000 } })));
    expect(screen.getByLabelText('Repeating cycle')).toHaveValue('NONE');
  });
  it('offers a threshold from the latest recorded value, in the unit of the series', async () => {
    withHistory({ ...HISTORY, suggestedSeasonality: null }); const user = await sources();
    const use = await screen.findByRole('button', { name: 'Use 209 (1.5× latest)' });
    await user.click(use);
    expect(screen.getByLabelText('Threshold (optional)')).toHaveValue('209');
    // Once a threshold is written the offer goes, so it cannot overwrite a choice.
    expect(screen.queryByRole('button', { name: /1.5× latest/ })).toBeNull();
  });

  // METRICS-FORECAST-AUDIT-2.md B4: a BELOW threshold is suggested below the latest value.
  it('suggests a threshold on the side of its direction', async () => {
    withHistory({ ...HISTORY, suggestedSeasonality: null }); const user = await sources();
    expect(await screen.findByRole('button', { name: 'Use 209 (1.5× latest)' })).toBeTruthy();
    await user.selectOptions(screen.getByLabelText('Threshold direction'), 'BELOW');
    // Latest 139: 1.5× would be a threshold already crossed.
    expect(screen.getByRole('button', { name: 'Use 69.5 (0.5× latest)' })).toBeTruthy();
    expect(screen.queryByRole('button', { name: /1.5× latest/ })).toBeNull();
  });
  // METRICS-FORECAST-AUDIT-2.md B5: no history is drawn under an interval it was not read at.
  it('drops the previous interval\'s history while the new one is read', async () => {
    let answer: (value: unknown) => void = () => {};
    let reads = 0;
    vi.mocked(axios.get).mockImplementation(url => {
      if (!String(url).endsWith('/history')) return Promise.resolve({ data: { metrics: [{ ...metric, suggestedStepMillis: 300000 }],
        total: 1, truncated: false, clusterId: 'existing', collectorId: 'collector', applyUnavailable: null, minimumContextPoints: 128 } });
      reads++;
      return reads === 1 ? Promise.resolve({ data: HISTORY }) : new Promise(resolve => { answer = resolve; });
    });
    const user = await sources();
    expect(await screen.findByRole('figure', { name: 'Recorded history' })).toHaveTextContent('at 5 minutes');
    await user.selectOptions(screen.getByLabelText('Sampling interval'), '900000');
    expect(screen.queryByRole('figure', { name: 'Recorded history' })).toBeNull();
    answer({ data: HISTORY });
    expect(await screen.findByRole('figure', { name: 'Recorded history' })).toHaveTextContent('at 15 minutes');
  });

  // METRICS-FORECAST-UX-AUDIT.md B6: a metric that already has a series is named, and cannot be chosen.
  it('marks a metric that is already forecast instead of refusing it after the submit', async () => {
    render(<ConfirmProvider><ForecastSetupWizard forecastMetricIds={['lag']} /></ConfirmProvider>);
    const option = await screen.findByRole('option', { name: /Lag · already forecast/ });
    expect(option).toBeDisabled();
  });

  // METRICS-FORECAST-UX-AUDIT.md B7: the label sits above the chips it titles.
  it('puts the source chips under their label', async () => {
    await sources();
    const label = screen.getByText('All source topics');
    const chips = screen.getByRole('list', { name: 'All source topics: selected' });
    expect(label.compareDocumentPosition(chips) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy();
  });
});
