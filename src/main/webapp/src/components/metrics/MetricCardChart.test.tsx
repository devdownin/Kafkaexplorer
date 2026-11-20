// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors
import React from 'react';
import { describe, expect, it, vi } from 'vitest';
import { render, screen } from '@testing-library/react';
import { MetricCard } from './MetricCard';
import { ToastProvider } from '../Toast';
import { FORECAST_COLORS } from './forecastColors';
import type { ForecastSeriesView, MetricConfig } from '../../api/types';

// jsdom measures nothing, so the responsive container would draw a 0 px chart: give it a size.
vi.mock('recharts', async original => {
  const actual = await original<typeof import('recharts')>();
  return {
    ...actual,
    ResponsiveContainer: ({ children }: { children: React.ReactElement<{ width?: number; height?: number }> }) =>
      React.cloneElement(children, { width: 400, height: 80 }),
  };
});

const metric = {
  id: 'm-1', name: 'latency', type: 'GAUGE', sql: 'SELECT 1 AS metric_value', description: '',
  warningThreshold: null, criticalThreshold: null, lastValue: 459, lastUpdateTime: Date.now(), errorMessage: null,
  history: [380, 402, 412], lastSummary: null, createTableSql: null,
} as unknown as MetricConfig;
const historyEndAt = Date.UTC(2026, 10, 20, 12);
const series = {
  seriesId: 'c'.repeat(64), metricId: 'm-1', environment: 'local', stepMillis: 60000, minimumContextPoints: 128,
  withdrawable: true, thresholdConfigured: false, activation: null,
  result: { state: 'READY', strategy: 'TIMESFM', visibility: 'SHADOW', reason: 'ok', quality: null,
    context: { status: 'READY', observedPoints: 512, outputUnit: 'ms', profile: { transformation: 'GAUGE_MEAN' },
      points: Array.from({ length: 60 }, (_, i) => ({ endAt: historyEndAt - (59 - i) * 60000, value: 400 + i, imputed: false, samples: 1 })) },
    forecast: { historyEndAt, points: Array.from({ length: 30 }, (_, i) =>
      ({ at: historyEndAt + (i + 1) * 60000, central: 460, q50: 460 - i, q10: 440 - i, q90: 480 - i })) } },
} as unknown as ForecastSeriesView;

describe('MetricCard forecast chart', () => {
  /*
   * The card was an AreaChart, which draws only <Area> children: the median <Line> was never
   * drawn, and the card showed the band alone under a "Forecast" legend.
   */
  it('draws the forecast median as its own dashed stroke, apart from the measurement', () => {
    const { container } = render(<ToastProvider><MetricCard metric={metric} onEdit={() => {}} onDelete={() => {}}
      onRefresh={() => {}} refreshing={false} forecastSeries={series} /></ToastProvider>);
    const median = container.querySelector(`path[stroke="${FORECAST_COLORS.forecast}"][stroke-dasharray="5 3"]`);
    expect(median).not.toBeNull();
    // Healthy is the primary token's stroke; the forecast must not share it.
    expect(FORECAST_COLORS.forecast).not.toBe('#a3adff');
    expect(screen.getByText('now')).toBeInTheDocument();
  });
});
