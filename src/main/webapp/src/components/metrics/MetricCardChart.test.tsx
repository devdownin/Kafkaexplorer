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

  const card = (overrides: Partial<MetricConfig>, view: ForecastSeriesView = series) => render(<ToastProvider>
    <MetricCard metric={{ ...metric, ...overrides }} onEdit={() => {}} onDelete={() => {}} onRefresh={() => {}}
      refreshing={false} forecastSeries={view} /></ToastProvider>);

  it('says when the forecast reaches a threshold of the metric, and on which reading', () => {
    // Medians 460 → 431, band ±20, read upwards: the median is past the 450 warning from the first
    // point, while the critical at 470 is reached only by the band's top — an expected warning is
    // the stronger sentence and is the one said.
    card({ warningThreshold: 450, criticalThreshold: 470 } as Partial<MetricConfig>);
    expect(screen.getByLabelText('Forecast threshold')).toHaveTextContent('Warning expected in ~1 min (≥ 450, median)');
  });

  it('reads a threshold crossed downwards when the two thresholds say so', () => {
    card({ warningThreshold: 450, criticalThreshold: 440 } as Partial<MetricConfig>);
    // q50 = 460 - i reaches 440 at i = 20, the 21st point.
    expect(screen.getByLabelText('Forecast threshold')).toHaveTextContent('Critical expected in ~21 min (≤ 440, median)');
  });

  it('names a threshold too far to draw at the edge it lies beyond instead of dropping it', () => {
    card({ warningThreshold: 5000, criticalThreshold: 9000 } as Partial<MetricConfig>);
    const off = screen.getByLabelText('Thresholds off the chart scale');
    expect(off).toHaveTextContent('↑ warn 5,000');
    expect(off).toHaveTextContent('↑ crit 9,000');
    expect(screen.queryByLabelText('Forecast threshold')).toBeNull();
  });

  it('keeps a near threshold on the chart by widening the scale', () => {
    // Values 400–480: a warning at 500 is within one span, so it is drawn rather than named at the edge.
    card({ warningThreshold: 500, criticalThreshold: 600 } as Partial<MetricConfig>);
    expect(screen.getByText('warn')).toBeInTheDocument();
    expect(screen.queryByLabelText('Thresholds off the chart scale')).not.toHaveTextContent('warn');
  });

  it('says how the forecast did against what happened, from the quality window the list carries', () => {
    const scored = structuredClone(series) as unknown as { result: Record<string, unknown> };
    scored.result.qualityWindow = { open: [{ through: 1, points: 30, baselineMae: {}, meanAbsActual: 1,
      quality: { mae: 4.25, mase: null, meanPinballLoss: 0, q10Q90Coverage: 0.87, meanIntervalWidth: 0 } }], lastBlock: null, failedBlocks: 0 };
    render(<ToastProvider><MetricCard metric={metric} onEdit={() => {}} onDelete={() => {}} onRefresh={() => {}}
      refreshing={false} forecastSeries={scored as unknown as ForecastSeriesView}
      forecast={{ stage: 'OBSERVING', label: 'Observing', tone: 'neutral', detail: 'Observing' }} /></ToastProvider>);
    expect(screen.getByLabelText('Forecast against actual')).toHaveTextContent('Last checked: 87% of 30 points in the 80% band · mean error 4.25 ms');
  });

  it('neither draws nor compares the metric thresholds against a counter forecast, a rate per second', () => {
    const rate = structuredClone(series) as unknown as { result: { context: { profile: { transformation: string } } } };
    rate.result.context.profile.transformation = 'COUNTER_RATE';
    card({ type: 'COUNTER', warningThreshold: 450, criticalThreshold: 500 } as Partial<MetricConfig>, rate as unknown as ForecastSeriesView);
    expect(screen.queryByLabelText('Forecast threshold')).toBeNull();
    expect(screen.queryByText('warn')).toBeNull();
    expect(screen.getByText(/a rate per second; the value above is the counter’s own reading/)).toBeInTheDocument();
    expect(screen.getByLabelText('Metric chart legend')).toHaveTextContent('Observed (per second)');
  });
});
