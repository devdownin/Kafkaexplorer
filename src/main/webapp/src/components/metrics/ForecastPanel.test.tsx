// SPDX-License-Identifier: AGPL-3.0-or-later
import { beforeEach, afterEach, describe, expect, it, vi } from 'vitest';
import { cleanup, render, screen } from '@testing-library/react';
import axios from 'axios';
import userEvent from '@testing-library/user-event';
import { ConfirmProvider } from '../ui';
import { ForecastPanel } from './ForecastPanel';
import type { ForecastActivationReadiness } from '../../api/types';
import { comparison } from './shadowModels.test';
vi.mock('axios', () => ({ default: { get: vi.fn(), put: vi.fn(), delete: vi.fn(), isAxiosError: (e: unknown) => Boolean((e as { isAxiosError?: boolean })?.isAxiosError) } }));
const ready: ForecastActivationReadiness = { eligible: true, reason: null, blockPoints: 0, blockPointsRequired: 120, lastBlockPassed: true, failedBlocks: 0, failedBlocksToDegrade: 2 };
const blocked = { ...ready, eligible: false, reason: 'The first quality block holds 40 of 120 realised points', blockPoints: 40, lastBlockPassed: null };
const seriesWith = (activation: ForecastActivationReadiness, state = 'READY') => ({ enabled: true, state: 'AVAILABLE', series: [{ seriesId: 'a'.repeat(64), metricId: 'metric', environment: 'production',
  stepMillis: 60000, minimumContextPoints: 128, withdrawable: false,
  result: { state, strategy: 'TIMESFM', visibility: 'SHADOW', reason: 'ready', context: { status: 'READY', observedPoints: 512, points: [], outputUnit: 'messages' },
    forecast: { points: [] }, quality: null, baselineMae: {}, evaluatedPoints: 0 }, activation }] });
describe('ForecastPanel', () => {
  beforeEach(() => vi.clearAllMocks());
  afterEach(cleanup);
  it('reports disabled without offering activation', async () => {
    vi.mocked(axios.get).mockResolvedValue({ data: { enabled: false, state: 'DISABLED', series: [] } });
    render(<ForecastPanel />);
    expect(await screen.findByText(/Forecast pilot disabled/)).toBeTruthy();
    expect(screen.queryByRole('button')).toBeNull();expect(axios.put).not.toHaveBeenCalled();
  });
  it('explains the empty default catalogue without showing a series selector', async () => {
    vi.mocked(axios.get).mockResolvedValue({ data: { enabled: true, state: 'AVAILABLE', series: [] } });
    render(<ForecastPanel />);
    expect(await screen.findByText(/Configure approved series, PostgreSQL history and TimesFM inference/)).toBeTruthy();
    expect(screen.queryByRole('combobox')).toBeNull();
    expect(axios.put).not.toHaveBeenCalled();
  });
  it('reports persistence outage rather than empty measurements', async () => {
    vi.mocked(axios.get).mockRejectedValue(new Error('503'));
    render(<ForecastPanel />);
    expect(await screen.findByRole('alert')).toHaveTextContent('unavailable');
    expect(axios.put).not.toHaveBeenCalled();
  });
  it('aborts the outstanding read on unmount', () => {
    vi.mocked(axios.get).mockReturnValue(new Promise(() => {}));
    const view=render(<ForecastPanel />);
    const config=vi.mocked(axios.get).mock.calls[0][1];view.unmount();
    expect(config?.signal?.aborted).toBe(true);
  });
  it('requires confirmation before sending activation', async () => {
    vi.mocked(axios.get).mockResolvedValue({ data: seriesWith(ready) });vi.mocked(axios.put).mockResolvedValue({});
    const user=userEvent.setup();render(<ConfirmProvider><ForecastPanel /></ConfirmProvider>);
    await user.click(await screen.findByRole('button',{name:'Approve after quality checks'}));
    expect(axios.put).not.toHaveBeenCalled();
    await user.click(screen.getByRole('button',{name:'Approve'}));
    expect(axios.put).toHaveBeenCalledWith(`/api/forecasts/${'a'.repeat(64)}/activation`,
      {active:true,confirmed:true},{timeout:10000});
  });
  it('says why activation is not yet possible before anyone clicks', async () => {
    vi.mocked(axios.get).mockResolvedValue({ data: seriesWith(blocked) });
    render(<ForecastPanel />);
    const button = await screen.findByRole('button',{name:'Approve after quality checks'});
    expect(button).toBeDisabled();
    expect(button).toHaveAccessibleDescription('Not yet: The first quality block holds 40 of 120 realised points');
    expect(screen.getByRole('list', { name: 'Forecast progress' })).toHaveTextContent('40 / 120 realised points · judged in ~80 min');
    expect(axios.put).not.toHaveBeenCalled();
  });
  it('prevents activation of a degraded forecast', async () => {
    vi.mocked(axios.get).mockResolvedValue({ data: seriesWith({ ...blocked, reason: 'Drift was detected', failedBlocks: 2, lastBlockPassed: false }, 'DEGRADED') });
    render(<ForecastPanel />);
    expect(await screen.findByRole('button',{name:'Approve after quality checks'})).toBeDisabled();
    expect(screen.getByText(/Drift on consecutive blocks/)).toBeTruthy();
    expect(axios.put).not.toHaveBeenCalled();
  });
  it('shows the server reason when activation is refused', async () => {
    vi.mocked(axios.get).mockResolvedValue({ data: seriesWith(ready) });
    vi.mocked(axios.put).mockRejectedValue({ isAxiosError: true, response: { status: 409, data: { reason: 'The last quality block failed' } } });
    const user=userEvent.setup();render(<ConfirmProvider><ForecastPanel /></ConfirmProvider>);
    await user.click(await screen.findByRole('button',{name:'Approve after quality checks'}));
    await user.click(screen.getByRole('button',{name:'Approve'}));
    expect(await screen.findByRole('alert')).toHaveTextContent('Approval refused: The last quality block failed');
  });
  it('shows state, strategy and visibility once, as badges', async () => {
    vi.mocked(axios.get).mockResolvedValue({ data: seriesWith(ready) });
    render(<ForecastPanel />);
    const badges = await screen.findByLabelText('Forecast status');
    expect(badges).toHaveTextContent('ForecastingTimesFMObservingmessages');
    // READY only says a forecast exists: the pilot's word is kept in the tooltip, not on a green badge.
    expect(screen.getByTitle('READY')).toHaveTextContent('Forecasting');
    expect(screen.getByTitle('SHADOW')).toHaveTextContent('Observing');
    expect(screen.queryByText('READY · TIMESFM · SHADOW · messages')).toBeNull();
  });
  it('reports a malformed API response without crashing the Metrics page', async () => {
    vi.mocked(axios.get).mockResolvedValue({data:{}});
    render(<ForecastPanel />);
    expect(await screen.findByRole('alert')).toHaveTextContent('unavailable');
    expect(screen.getByRole('heading',{name:'Metrics Forecast'})).toBeTruthy();
    expect(axios.put).not.toHaveBeenCalled();
  });
  it('polls the list, then the selected series on its own: its record, progress and shadow', async () => {
    const progress = { seriesId: 'a'.repeat(64), state: 'WARMING_UP', reason: 'Need more observations', observedPoints: 40, requiredPoints: 512,
      missingPoints: 472, imputedPoints: 0, checkedAt: 1, nextScheduledAt: 100, stepMillis: 60000, horizon: 30,
      topics: ['orders'], groups: [], breach: null, breachState: 'NOT_CONFIGURED' };
    vi.mocked(axios.get).mockImplementation(url => Promise.resolve({ data: String(url).endsWith('/progress') ? progress : seriesWith(ready) }));
    render(<ForecastPanel />);
    expect(await screen.findByText(/40 \/ 512 observed points/)).toBeTruthy();
    // The preparation diagnostic is read once on mount; the list carries no history points
    // (METRICS-FORECAST-UX-AUDIT.md P1), so the series shown is read on its own.
    expect(vi.mocked(axios.get).mock.calls.map(call => call[0]).filter(url => url !== '/api/forecasts/preparation'))
      .toEqual(['/api/forecasts', `/api/forecasts/${'a'.repeat(64)}`,
        `/api/forecasts/${'a'.repeat(64)}/progress`, `/api/forecasts/${'a'.repeat(64)}/shadow`]);
  });
  it('offers the raw result as a copy, not as a page of JSON', async () => {
    vi.mocked(axios.get).mockResolvedValue({ data: seriesWith(ready) });
    render(<ForecastPanel />);
    expect(await screen.findByRole('button', { name: 'Copy raw result (JSON)' })).toBeTruthy();
    expect(document.querySelector('pre')).toBeNull();
  });
  it('hands the configuration assistant to the page instead of opening its own', async () => {
    const onConfigure = vi.fn();
    vi.mocked(axios.get).mockImplementation(url => Promise.resolve({ data: url === '/api/forecasts/preparation'
      ? { checkedAt: 1, probed: false, checks: [] } : { enabled: true, state: 'AVAILABLE', series: [] } }));
    const user = userEvent.setup(); render(<ForecastPanel onConfigure={onConfigure} />);
    await user.click(await screen.findByRole('button', { name: 'Configure a forecast' }));
    expect(onConfigure).toHaveBeenCalledOnce();
    expect(screen.queryByLabelText('Forecast configuration assistant')).toBeNull();
  });
  it('hands every polled status to the page for its cards', async () => {
    const status = seriesWith(blocked);
    vi.mocked(axios.get).mockImplementation(url => Promise.resolve({ data: url === '/api/forecasts' ? status : {} }));
    const onStatus = vi.fn();
    render(<ConfirmProvider><ForecastPanel onStatus={onStatus} /></ConfirmProvider>);
    await screen.findByText('ready');
    expect(onStatus).toHaveBeenCalledWith(status);
  });
  it('stops a series approved from the assistant after confirmation, and only such a series', async () => {
    const status = seriesWith(blocked);
    vi.mocked(axios.get).mockImplementation(url => Promise.resolve({ data: url === '/api/forecasts'
      ? { ...status, series: [{ ...status.series[0], withdrawable: true }] } : {} }));
    vi.mocked(axios.delete).mockResolvedValue({});
    const user = userEvent.setup(); render(<ConfirmProvider><ForecastPanel /></ConfirmProvider>);
    await user.click(await screen.findByRole('button', { name: 'Stop forecasting this series' }));
    await user.click(await screen.findByRole('button', { name: 'Stop forecasting' }));
    expect(axios.delete).toHaveBeenCalledWith(`/api/forecasts/series/${'a'.repeat(64)}`, expect.objectContaining({ timeout: 10000 }));
  });
  it('offers no stop for a series the deployment configuration approved', async () => {
    vi.mocked(axios.get).mockImplementation(url => Promise.resolve({ data: url === '/api/forecasts' ? seriesWith(blocked) : {} }));
    render(<ConfirmProvider><ForecastPanel /></ConfirmProvider>);
    await screen.findByText('ready');
    expect(screen.queryByRole('button', { name: 'Stop forecasting this series' })).toBeNull();
  });
  it('keeps an approval refusal on screen across the next poll, beside the button that raised it', async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true });
    try {
      vi.mocked(axios.get).mockResolvedValue({ data: seriesWith(ready) });
      vi.mocked(axios.put).mockRejectedValue({ isAxiosError: true, response: { status: 409, data: { reason: 'Pilot is refreshing; retry activation' } } });
      const user = userEvent.setup({ advanceTimers: vi.advanceTimersByTime });
      render(<ConfirmProvider><ForecastPanel /></ConfirmProvider>);
      await user.click(await screen.findByRole('button', { name: 'Approve after quality checks' }));
      await user.click(screen.getByRole('button', { name: 'Approve' }));
      expect(await screen.findByRole('alert')).toHaveTextContent('Approval refused: Pilot is refreshing');
      const calls = vi.mocked(axios.get).mock.calls.length;
      await vi.advanceTimersByTimeAsync(31_000);
      expect(vi.mocked(axios.get).mock.calls.length).toBeGreaterThan(calls);
      // A successful poll used to clear it, within thirty seconds and with no action from anyone.
      expect(screen.getByRole('alert')).toHaveTextContent('Approval refused: Pilot is refreshing');
    } finally { vi.useRealTimers(); }
  });
  it('tells the page its status is unreadable, so the cards stop claiming a stage', async () => {
    vi.mocked(axios.get).mockRejectedValue(new Error('503'));
    const onStatus = vi.fn();
    render(<ForecastPanel onStatus={onStatus} />);
    await screen.findByRole('alert');
    expect(onStatus).toHaveBeenCalledWith(null);
  });
  it('asks nothing behind a hidden tab, and stops asking for a shadow that answered 404', async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true });
    const visibility = vi.spyOn(document, 'visibilityState', 'get');
    try {
      vi.mocked(axios.get).mockImplementation(url => Promise.resolve(String(url).endsWith('/shadow')
        ? { status: 404, data: '' } : String(url).endsWith('/progress') ? { data: {} } : { data: seriesWith(ready) }));
      visibility.mockReturnValue('visible');
      render(<ForecastPanel />);
      await screen.findByRole('button', { name: 'Approve after quality checks' });
      const shadowCalls = () => vi.mocked(axios.get).mock.calls.filter(c => String(c[0]).endsWith('/shadow')).length;
      // The selected series is read after the list, in its own loop.
      await vi.waitFor(() => expect(shadowCalls()).toBe(1));
      await vi.advanceTimersByTimeAsync(31_000);
      expect(shadowCalls()).toBe(1);

      visibility.mockReturnValue('hidden');
      const before = vi.mocked(axios.get).mock.calls.length;
      await vi.advanceTimersByTimeAsync(95_000);
      expect(vi.mocked(axios.get).mock.calls.length).toBe(before);

      visibility.mockReturnValue('visible');
      document.dispatchEvent(new Event('visibilitychange'));
      await vi.advanceTimersByTimeAsync(10);
      expect(vi.mocked(axios.get).mock.calls.length).toBeGreaterThan(before);
    } finally { visibility.mockRestore(); vi.useRealTimers(); }
  });
  describe('shadow comparison', () => {
    const answer = (shadow: { status: number; data: unknown }) => vi.mocked(axios.get).mockImplementation(async (url: string) => {
      if (url.endsWith('/shadow')) return shadow;
      if (url.endsWith('/progress')) throw new Error('not under test');
      return { data: seriesWith(ready) };
    });
    it('shows nothing when the deployment runs no shadow', async () => {
      answer({ status: 404, data: '' });
      render(<ForecastPanel />);
      await screen.findByRole('button', { name: 'Approve after quality checks' });
      expect(screen.queryByText('Shadow model comparison')).toBeNull();
    });
    it('says a shadow with nothing scored yet is waiting, not broken', async () => {
      answer({ status: 204, data: '' });
      render(<ForecastPanel />);
      expect(await screen.findByText(/no forecast compatible with this series has been scored yet/)).toBeTruthy();
    });
    it('compares both models on the same cohorts, and the covariate trial beside them', async () => {
      answer({ status: 200, data: comparison });
      render(<ForecastPanel />);
      const table = await screen.findByRole('table', { name: 'Shadow quality' });
      expect(table).toHaveTextContent('TimesFM2.000');
      expect(table).toHaveTextContent('Timestral1.000');
      expect(screen.getByText('Lowest MAE on the shared cohorts: Timestral.')).toBeTruthy();
      expect(screen.getByText("Timestral would pass TimesFM's approval gate on its last block.")).toBeTruthy();
      expect(screen.getByRole('table', { name: 'Shadow quality with covariates' })).toHaveTextContent('Timestral + covariates0.5000');
      expect(screen.getByText(/Timestral with covariates would not qualify yet: The first shared quality block holds 40 of 120/)).toBeTruthy();
      expect(axios.put).not.toHaveBeenCalled();
    });
  });

  // METRICS-FORECAST-UX-AUDIT.md P3: choosing a series reads that series, not the whole list again.
  it('reads only the chosen series when the selection changes', async () => {
    const two = seriesWith(ready);
    const other = { ...two.series[0], seriesId: 'b'.repeat(64), metricId: 'other' };
    vi.mocked(axios.get).mockImplementation(url => Promise.resolve({ data: url === '/api/forecasts' ? { ...two, series: [two.series[0], other] } : {} }));
    const user = userEvent.setup(); render(<ConfirmProvider><ForecastPanel /></ConfirmProvider>);
    const select = await screen.findByLabelText('Series');
    vi.mocked(axios.get).mockClear();
    await user.selectOptions(select, 'b'.repeat(64));
    await vi.waitFor(() => expect(vi.mocked(axios.get).mock.calls.map(c => c[0])).toContain(`/api/forecasts/${'b'.repeat(64)}`));
    expect(vi.mocked(axios.get).mock.calls.map(c => c[0])).not.toContain('/api/forecasts');
  });

  // METRICS-FORECAST-UX-AUDIT.md B2: an unreadable series says so, and the others stay listed.
  it('names a series the server could not read instead of losing the list', async () => {
    const status = seriesWith(ready);
    const broken = { ...status.series[0], seriesId: 'b'.repeat(64), metricId: 'broken', result: null,
      error: 'The latest result of this series could not be read (IllegalStateException)' };
    vi.mocked(axios.get).mockImplementation(url => Promise.resolve({ data: url === '/api/forecasts' ? { ...status, series: [broken, status.series[0]] } : {} }));
    render(<ConfirmProvider><ForecastPanel /></ConfirmProvider>);
    expect(await screen.findByRole('alert')).toHaveTextContent('could not be read');
    expect(screen.getAllByRole('option')).toHaveLength(2);
  });

  // METRICS-FORECAST-UX-AUDIT.md B3: collecting history is not a fallback.
  it('does not call a series without a forecast a baseline fallback', async () => {
    const status = seriesWith(blocked, 'WARMING_UP');
    const collecting = { ...status.series[0], result: { ...status.series[0].result, strategy: 'UNAVAILABLE', forecast: null, reason: 'Collecting history' } };
    vi.mocked(axios.get).mockImplementation(url => Promise.resolve({ data: url === '/api/forecasts' ? { ...status, series: [collecting] } : {} }));
    render(<ConfirmProvider><ForecastPanel /></ConfirmProvider>);
    expect(await screen.findByTitle('UNAVAILABLE')).toHaveTextContent('No forecast yet');
    expect(screen.queryByText('Baseline fallback')).toBeNull();
    // Nothing to approve and no interval to qualify without a forecast.
    expect(screen.queryByRole('button', { name: 'Approve after quality checks' })).toBeNull();
    expect(screen.queryByText(/80 % interval is nominal/)).toBeNull();
  });

  // METRICS-FORECAST-UX-AUDIT.md U1, U5: the decision sits with the sentence saying where the series stands.
  it('puts approval and stop together, under the forecast', async () => {
    const status = seriesWith(blocked);
    vi.mocked(axios.get).mockImplementation(url => Promise.resolve({ data: url === '/api/forecasts'
      ? { ...status, series: [{ ...status.series[0], withdrawable: true }] } : {} }));
    render(<ConfirmProvider><ForecastPanel /></ConfirmProvider>);
    const approve = await screen.findByRole('button', { name: 'Approve after quality checks' });
    expect(approve.parentElement).toBe(screen.getByRole('button', { name: 'Stop forecasting this series' }).parentElement);
    expect(approve.parentElement).toHaveTextContent('Observing');
  });

  // METRICS-FORECAST-UX-AUDIT.md P4: the preparation checks are read when their section opens.
  it('reads the preparation checks only once their section is opened', async () => {
    vi.mocked(axios.get).mockImplementation(url => Promise.resolve({ data: url === '/api/forecasts' ? seriesWith(ready)
      : url === '/api/forecasts/preparation' ? { checkedAt: 1, probed: false, checks: [] } : {} }));
    const user = userEvent.setup(); render(<ConfirmProvider><ForecastPanel /></ConfirmProvider>);
    await screen.findByLabelText('Forecast status');
    expect(vi.mocked(axios.get).mock.calls.map(c => c[0])).not.toContain('/api/forecasts/preparation');
    await user.click(screen.getByText('Preparation and configuration'));
    await vi.waitFor(() => expect(vi.mocked(axios.get).mock.calls.map(c => c[0])).toContain('/api/forecasts/preparation'));
  });

  // METRICS-FORECAST-UX-AUDIT.md U6: the confirmation says what approving changes.
  it('says what approval changes before it is given', async () => {
    vi.mocked(axios.get).mockResolvedValue({ data: seriesWith(ready) });
    const user = userEvent.setup(); render(<ConfirmProvider><ForecastPanel /></ConfirmProvider>);
    await user.click(await screen.findByRole('button', { name: 'Approve after quality checks' }));
    expect(await screen.findByText(/reported as ACTIVE/)).toBeTruthy();
  });
});
