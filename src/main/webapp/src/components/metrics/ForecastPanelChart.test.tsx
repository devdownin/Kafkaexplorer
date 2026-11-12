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
});
