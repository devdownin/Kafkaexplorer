// SPDX-License-Identifier: AGPL-3.0-or-later
import { beforeEach, afterEach, describe, expect, it, vi } from 'vitest';
import { cleanup, render, screen } from '@testing-library/react';
import axios from 'axios';
import userEvent from '@testing-library/user-event';
import { ConfirmProvider } from '../ui';
import { ForecastPanel } from './ForecastPanel';
import type { ForecastActivationReadiness } from '../../api/types';
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
    expect(badges).toHaveTextContent('READYTIMESFMObservingmessages');
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
  it('polls the selected series progress in the same loop', async () => {
    const progress = { seriesId: 'a'.repeat(64), state: 'WARMING_UP', reason: 'Need more observations', observedPoints: 40, requiredPoints: 512,
      missingPoints: 472, imputedPoints: 0, checkedAt: 1, nextScheduledAt: 100, stepMillis: 60000, horizon: 30,
      topics: ['orders'], groups: [], breach: null, breachState: 'NOT_CONFIGURED' };
    vi.mocked(axios.get).mockImplementation(url => Promise.resolve({ data: String(url).endsWith('/progress') ? progress : seriesWith(ready) }));
    render(<ForecastPanel />);
    expect(await screen.findByText(/40 \/ 512 observed points/)).toBeTruthy();
    // The preparation diagnostic is read once on mount; only these two are polled.
    expect(vi.mocked(axios.get).mock.calls.map(call => call[0]).filter(url => url !== '/api/forecasts/preparation'))
      .toEqual(['/api/forecasts', `/api/forecasts/${'a'.repeat(64)}/progress`]);
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
});
