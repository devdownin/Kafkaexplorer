// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors
import React from 'react';
import { describe, expect, it, vi } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import axios from 'axios';
import userEvent from '@testing-library/user-event';
import { FORECAST_COLORS } from './forecastColors';
import { ForecastPanel } from './ForecastPanel';

vi.mock('axios', () => ({ default: { get: vi.fn(), put: vi.fn(), delete: vi.fn(), isAxiosError: () => false } }));
// jsdom measures nothing, so the responsive container would draw a 0 px chart: give it a size.
vi.mock('recharts', async original => {
  const actual = await original<typeof import('recharts')>();
  return {
    ...actual,
    ResponsiveContainer: ({ children }: { children: React.ReactElement<{ width?: number; height?: number }> }) =>
      React.cloneElement(children, { width: 800, height: 260 }),
  };
});

const id = 'a'.repeat(64);
const forecast = { points: [1, 2, 3].map(i => ({ at: 1000 + i * 60000, central: 10, q10: 8, q50: 10, q90: 12 })) };
const status = { enabled: true, state: 'AVAILABLE', series: [{ seriesId: id, metricId: 'm', environment: 'e',
  stepMillis: 60000, minimumContextPoints: 32, withdrawable: false, thresholdConfigured: true,
  activation: { eligible: false, reason: 'not yet', blockPoints: 0, blockPointsRequired: 30, lastBlockPassed: null, failedBlocks: 0, failedBlocksToDegrade: 2 },
  result: { key: 'k', state: 'READY', strategy: 'TIMESFM', visibility: 'SHADOW', reason: 'ok', quality: null, baselineMae: {}, evaluatedPoints: 0,
    context: { status: 'READY', observedPoints: 3, outputUnit: 'ms', points: [0, 1, 2].map(i => ({ endAt: i * 60000, value: 9, imputed: false, samples: 1 })) },
    forecast } }] };

// METRICS-FORECAST-UX-AUDIT.md B1: the two <Area> of the interval sat in a fragment, which recharts 2
// does not look into — neither the band nor its legend entry was ever drawn.
describe('ForecastPanel chart', () => {
  it('draws the 80 % interval of a TimesFM forecast', async () => {
    vi.mocked(axios.get).mockImplementation(url => Promise.resolve({ data: url === '/api/forecasts' ? status : {} }));
    render(<ForecastPanel />);
    expect(await screen.findByText('80 % interval')).toBeInTheDocument();
    expect(screen.getByText('Median forecast')).toBeInTheDocument();
  });

  // METRICS-FORECAST-UX-AUDIT.md B4: a filled gap is drawn apart from what was measured.
  it('draws imputed points apart, under their own name', async () => {
    const withGap = structuredClone(status);
    withGap.series[0].result.context.points[1].imputed = true;
    vi.mocked(axios.get).mockImplementation(url => Promise.resolve({ data: url === '/api/forecasts' ? withGap : {} }));
    render(<ForecastPanel />);
    expect(await screen.findByText('Imputed (gap filled)')).toBeInTheDocument();
  });

  // METRICS-FORECAST-UX-AUDIT.md B5: the threshold a breach is judged against is on the chart.
  it('draws the configured threshold', async () => {
    const progress = { seriesId: id, state: 'READY', reason: 'ok', observedPoints: 3, requiredPoints: 512, missingPoints: 0, imputedPoints: 0,
      checkedAt: 1, nextScheduledAt: 2, stepMillis: 60000, horizon: 3, topics: ['t'], groups: [], breachState: 'EVALUATED',
      breach: { resultKey: 'k', threshold: { threshold: 11, direction: 'ABOVE', breached: true } } };
    vi.mocked(axios.get).mockImplementation(url => Promise.resolve({ data: url === '/api/forecasts' ? status
      : String(url).endsWith('/progress') ? progress : {} }));
    render(<ForecastPanel />);
    expect(await screen.findByText('Threshold ≥ 11')).toBeInTheDocument();
  });

  // One clock for the page: given one, the panel reads once per tick and runs no timer of its own.
  it('reads once per tick of the page clock it is given', async () => {
    vi.mocked(axios.get).mockClear();
    vi.mocked(axios.get).mockImplementation(url => Promise.resolve({ data: url === '/api/forecasts' ? status : {} }));
    const listReads = () => vi.mocked(axios.get).mock.calls.filter(([url]) => url === '/api/forecasts').length;
    const { rerender } = render(<ForecastPanel clock={0} />);
    await screen.findByText('80 % interval');
    expect(listReads()).toBe(1);
    rerender(<ForecastPanel clock={0} />);
    expect(listReads()).toBe(1);
    rerender(<ForecastPanel clock={1} />);
    await waitFor(() => expect(listReads()).toBe(2));
  });

  // The metric's own thresholds, drawn as the card draws them: a near one on the chart, a far one named.
  it('draws the metric\u2019s own warning and critical thresholds, naming a far one at the edge', async () => {
    vi.mocked(axios.get).mockImplementation(url => Promise.resolve({ data: url === '/api/forecasts' ? status : {} }));
    render(<ForecastPanel metricThresholds={{ m: { warning: 11, critical: 5000 } }} />);
    expect(await screen.findByText('warning 11')).toBeInTheDocument();
    expect(screen.getByLabelText('Thresholds off the chart scale')).toHaveTextContent('↑ critical 5,000');
  });

  it('draws none of them against a rate forecast', async () => {
    const rate = structuredClone(status) as typeof status & { series: { result: { context: Record<string, unknown> } }[] };
    rate.series[0].result.context.profile = { transformation: 'COUNTER_RATE' };
    vi.mocked(axios.get).mockImplementation(url => Promise.resolve({ data: url === '/api/forecasts' ? rate : {} }));
    render(<ForecastPanel metricThresholds={{ m: { warning: 11, critical: 5000 } }} />);
    expect(await screen.findByText('80 % interval')).toBeInTheDocument();
    expect(screen.queryByText('warning 11')).toBeNull();
    expect(screen.queryByLabelText('Thresholds off the chart scale')).toBeNull();
  });

  // A threshold above every value was drawn outside the plot: `ifOverflow="extendDomain"` does not
  // widen the axis in recharts 2. Found by the documentation capture, not by the test above.
  it('widens the value axis to a threshold above every value', async () => {
    const progress = { seriesId: id, state: 'READY', reason: 'ok', observedPoints: 3, requiredPoints: 512, missingPoints: 0, imputedPoints: 0,
      checkedAt: 1, nextScheduledAt: 2, stepMillis: 60000, horizon: 3, topics: ['t'], groups: [], breachState: 'EVALUATED',
      breach: { resultKey: 'k', threshold: { threshold: 50, direction: 'ABOVE', breached: false } } };
    vi.mocked(axios.get).mockImplementation(url => Promise.resolve({ data: url === '/api/forecasts' ? status
      : String(url).endsWith('/progress') ? progress : {} }));
    const { container } = render(<ForecastPanel />);
    await screen.findByText('Threshold ≥ 50');
    const ticks = [...container.querySelectorAll('.recharts-yAxis .recharts-cartesian-axis-tick-value')]
      .map(t => Number(t.textContent));
    expect(Math.max(...ticks)).toBeGreaterThanOrEqual(50);
  });

  // One past forecast against what happened, point by point, only once it is asked for.
  it('sets the latest fully measured forecast beside what happened, on request', async () => {
    // Measured 9 at 0, 1 and 2 minutes; the previous forecast said 10 (8–12) at 1 and 2, and 20 at 0.
    const previous = { historyEndAt: -60000, points: [0, 1, 2].map(i => ({ at: i * 60000, central: 10, q10: i === 0 ? 18 : 8, q50: i === 0 ? 20 : 10, q90: i === 0 ? 22 : 12 })) };
    const asked: string[] = [];
    vi.mocked(axios.get).mockImplementation(url => {
      asked.push(String(url));
      return Promise.resolve(String(url).endsWith('/previous') ? { status: 200, data: previous } : { data: url === '/api/forecasts' ? status : {} });
    });
    const { container } = render(<ForecastPanel />);
    await screen.findByText('Median forecast');
    expect(asked.some(u => u.endsWith('/previous'))).toBe(false);
    await userEvent.setup().click(screen.getByRole('checkbox', { name: /Previous forecast vs actual/ }));
    const summary = await screen.findByText(/measured/, { selector: '[aria-label="Previous forecast against what happened"]' });
    expect(summary).toHaveTextContent('2 of 3 measured points inside its 80 % band');
    expect(summary).toHaveTextContent('mean error 4.33 ms');
    // Measured below what it predicted on average, by as much as its mean error: said, as a direction.
    expect(summary).toHaveTextContent('it ran high by 4.33 on average');
    expect(screen.getByText('Previous forecast')).toBeInTheDocument();
    expect(container.querySelector(`path[stroke="${FORECAST_COLORS.previous}"]`)).not.toBeNull();
  });

  it('says so when no forecast has been fully measured yet', async () => {
    vi.mocked(axios.get).mockImplementation(url => Promise.resolve(String(url).endsWith('/previous')
      ? { status: 204, data: '' } : { data: url === '/api/forecasts' ? status : {} }));
    render(<ForecastPanel />);
    await screen.findByText('Median forecast');
    await userEvent.setup().click(screen.getByRole('checkbox', { name: /Previous forecast vs actual/ }));
    expect(await screen.findByText(/No forecast has been fully measured yet/)).toBeInTheDocument();
  });

  // The axis started at zero: a latency between 1 900 and 2 300 was a flat line and its band a sliver.
  it('fits the value axis to the series rather than starting at zero', async () => {
    const high = structuredClone(status);
    high.series[0].result.context.points.forEach((p, i) => { p.value = 1900 + 100 * i; });
    high.series[0].result.forecast.points.forEach((p, i) => Object.assign(p, { q10: 2050 + i, q50: 2100 + i, q90: 2300 + i }));
    vi.mocked(axios.get).mockImplementation(url => Promise.resolve({ data: url === '/api/forecasts' ? high : {} }));
    const { container } = render(<ForecastPanel />);
    await screen.findByText('Median forecast');
    const ticks = [...container.querySelectorAll('.recharts-yAxis .recharts-cartesian-axis-tick-value')]
      .map(t => Number(t.textContent!.replace(/[^\d.-]/g, '')));
    expect(Math.min(...ticks)).toBeGreaterThan(1000);
    expect(Math.max(...ticks)).toBeGreaterThanOrEqual(2300);
  });

  // Where the measurements stop is marked, and the median leaves from the last measured point.
  it('marks the start of the forecast and joins it to the last measurement', async () => {
    vi.mocked(axios.get).mockImplementation(url => Promise.resolve({ data: url === '/api/forecasts' ? status : {} }));
    const { container } = render(<ForecastPanel />);
    expect(await screen.findByText('now')).toBeInTheDocument();
    const median = container.querySelector('.recharts-line path[stroke-dasharray="6 3"]');
    // Three predicted points plus the measured one it starts from: four vertices, three segments.
    expect(median?.getAttribute('d')?.match(/[ML]/g)?.length ?? (median?.getAttribute('d')?.match(/C/g)?.length ?? 0) + 1).toBe(4);
  });
});
