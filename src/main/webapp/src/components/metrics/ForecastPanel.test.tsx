// SPDX-License-Identifier: AGPL-3.0-or-later
import { beforeEach, afterEach, describe, expect, it, vi } from 'vitest';
import { cleanup, render, screen } from '@testing-library/react';
import axios from 'axios';
import userEvent from '@testing-library/user-event';
import { ConfirmProvider } from '../ui';
import { ForecastPanel } from './ForecastPanel';
vi.mock('axios', () => ({ default: { get: vi.fn(), put: vi.fn() } }));
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
    const data={ enabled:true, state:'AVAILABLE', series:[{ seriesId:'a'.repeat(64),metricId:'metric',environment:'production',
      result:{state:'READY',strategy:'TIMESFM',visibility:'SHADOW',reason:'ready',context:{points:[],outputUnit:'messages'},
        forecast:null,quality:null,baselineMae:{},evaluatedPoints:0} }] };
    vi.mocked(axios.get).mockResolvedValue({ data });vi.mocked(axios.put).mockResolvedValue({});
    const user=userEvent.setup();render(<ConfirmProvider><ForecastPanel /></ConfirmProvider>);
    await user.click(await screen.findByRole('button',{name:'Activate after quality checks'}));
    expect(axios.put).not.toHaveBeenCalled();
    await user.click(screen.getByRole('button',{name:'Activate'}));
    expect(axios.put).toHaveBeenCalledWith(`/api/forecasts/${'a'.repeat(64)}/activation`,
      {active:true,confirmed:true},{timeout:10000});
  });
  it('prevents activation of a degraded forecast', async () => {
    vi.mocked(axios.get).mockResolvedValue({data:{enabled:true,state:'AVAILABLE',series:[{seriesId:'a'.repeat(64),
      metricId:'metric',environment:'production',result:{state:'DEGRADED',strategy:'TIMESFM',visibility:'SHADOW',
      reason:'poor coverage',context:{points:[],outputUnit:'messages'},forecast:null,quality:null,baselineMae:{}}}]}});
    render(<ForecastPanel />);
    expect(await screen.findByRole('button',{name:'Activate after quality checks'})).toBeDisabled();
    expect(axios.put).not.toHaveBeenCalled();
  });
  it('reports a malformed API response without crashing the Metrics page', async () => {
    vi.mocked(axios.get).mockResolvedValue({data:{}});
    render(<ForecastPanel />);
    expect(await screen.findByRole('alert')).toHaveTextContent('unavailable');
    expect(screen.getByRole('heading',{name:'Metrics Forecast'})).toBeTruthy();
    expect(axios.put).not.toHaveBeenCalled();
  });
});
