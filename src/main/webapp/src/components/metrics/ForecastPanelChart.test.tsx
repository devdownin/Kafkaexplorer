// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors
import React from 'react';
import { describe, expect, it, vi } from 'vitest';
import { render, screen } from '@testing-library/react';
import axios from 'axios';
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
  stepMillis: 60000, minimumContextPoints: 32, withdrawable: false,
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
});
