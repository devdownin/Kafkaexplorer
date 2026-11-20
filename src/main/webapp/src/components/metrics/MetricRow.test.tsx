// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors
import { describe, expect, it } from 'vitest';
import { render } from '@testing-library/react';
import { MetricRow } from './MetricRow';
import type { ForecastSeriesView, MetricConfig } from '../../api/types';

const metric = {
  id: 'm-1', name: 'latency', type: 'GAUGE', sql: 'SELECT 1 AS metric_value', description: '',
  warningThreshold: null, criticalThreshold: null, lastValue: 412, lastUpdateTime: Date.now(), errorMessage: null,
  history: [380, 402, 412], lastSummary: null, createTableSql: null,
} as unknown as MetricConfig;

const series = (transformation: string) => ({
  seriesId: 's', metricId: 'm-1', stepMillis: 60000,
  result: { strategy: 'TIMESFM', context: { profile: { transformation } },
    forecast: { historyEndAt: 0, points: [1, 2, 3].map(i => ({ at: i * 60000, central: 420, q10: 410, q50: 420 + i, q90: 430 })) } },
}) as unknown as ForecastSeriesView;

const row = (forecastSeries?: ForecastSeriesView) => render(<ul><MetricRow metric={metric} refreshing={false}
  onEdit={() => {}} onRefresh={() => {}} forecastSeries={forecastSeries} /></ul>);

describe('MetricRow sparkline', () => {
  it('continues the history with the predicted median, dashed', () => {
    const { container } = row(series('GAUGE_MEAN'));
    const forecast = container.querySelector('[data-testid="sparkline-forecast"]');
    expect(forecast).not.toBeNull();
    expect(forecast!.getAttribute('stroke-dasharray')).toBe('3 2');
    // From the last measured point, then at most half as many predicted steps as measured ones (3 → 2).
    expect(forecast!.getAttribute('points')!.split(' ')).toHaveLength(3);
  });

  it('draws no forecast for a counter, whose forecast is a rate, nor without a forecast', () => {
    expect(row(series('COUNTER_RATE')).container.querySelector('[data-testid="sparkline-forecast"]')).toBeNull();
    expect(row().container.querySelector('[data-testid="sparkline-forecast"]')).toBeNull();
  });
});
