// SPDX-License-Identifier: AGPL-3.0-or-later
import { describe, expect, it } from 'vitest';
import type { ForecastSeriesView } from '../api/types';
import { spatialProjection } from './strataForecast';

const series = {
  seriesId: 'forecast-1', metricId: 'm1', environment: 'demo', topics: ['orders'],
  groups: [], stepMillis: 1000,
  result: {
    state: 'READY', strategy: 'TIMESFM', visibility: 'SHADOW', generatedAt: 100,
    context: { outputUnit: 'records/s', points: [
      { endAt: 9000, value: 12, imputed: false, samples: 1 },
      { endAt: 10000, value: null, imputed: true, samples: 0 },
      { endAt: 11000, value: 15, imputed: false, samples: 1 },
    ] },
    forecast: {
      seriesId: 'forecast-1', outputUnit: 'records/s', historyEndAt: 11000, modelId: 'TimesFM',
      points: [
        { at: 12000, q10: 10, q50: 16, q90: 22 },
        { at: 13000, q10: 11, q50: 18, q90: 24 },
      ],
    },
  },
} as ForecastSeriesView;
describe('Kafka Strata forecast evidence', () => {
  it('projects measured and forecast intervals on distinct time axes', () => {
    const v = spatialProjection(series, 'orders', 11500);
    expect(v?.observed).toEqual([{ at: 9000, value: 12 }, { at: 11000, value: 15 }]);
    expect(v?.future).toHaveLength(2);
    expect(v?.unit).toBe('records/s');
  });
  it('refuses guessed topic mappings and ambiguous multi-topic series', () => {
    expect(spatialProjection(series, 'payments', 11500)).toBeNull();
    expect(spatialProjection({ ...series, topics: ['orders', 'payments'] }, 'orders', 11500)).toBeNull();
  });
  it('refuses degraded, stale, mismatched or expired forecasts', () => {
    expect(spatialProjection({ ...series, result: { ...series.result!, state: 'DEGRADED' } }, 'orders', 11500)).toBeNull();
    expect(spatialProjection(series, 'orders', 14000)).toBeNull();
    expect(spatialProjection({ ...series, result: { ...series.result!, forecast: {
      ...series.result!.forecast!, outputUnit: 'bytes',
    } } }, 'orders', 11500)).toBeNull();
  });
});
