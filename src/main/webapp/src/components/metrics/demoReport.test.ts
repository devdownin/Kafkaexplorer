// SPDX-License-Identifier: AGPL-3.0-or-later
import { describe, expect, it } from 'vitest';
import type { ForecastStatus, MetricConfig } from '../../api/types';
import { demoSnapshot, reportHtml } from './demoReport';

describe('demo report', () => {
  it('exports only demo metrics, safe forecast evidence and no secrets', () => {
    const metrics = [
      { id: 'demo-live-unshipped', name: 'Orders <script>alert(1)</script>',
        lastValue: 480, lastUpdateTime: 1700000000000, warningThreshold: 300,
        criticalThreshold: 400, history: [10, 20, 480], errorMessage: null },
      { id: 'production-secret', name: 'Unrelated', lastValue: 9, history: [9] },
    ] as MetricConfig[];
    const forecasts = { series: [
      { seriesId: 'series', metricId: 'demo-live-unshipped', result: {
        state: 'READY', strategy: 'TIMESFM', generatedAt: 1700000000000,
        context: { observedPoints: 512 }, forecast: {
          modelId: 'TimesFM', points: [{ at: 1700000010000, central: 470, q10: 450, q90: 490 }],
        }, quality: null, evaluatedPoints: 8,
      } },
      { seriesId: 'secret-series', metricId: 'production-secret', result: null },
    ] } as ForecastStatus;
    const snapshot = demoSnapshot('overload', metrics, forecasts, new Date('2026-11-23T12:00:00Z'));
    expect(snapshot.summary).toEqual({ metrics: 1, forecastsReady: 1, forecastsTotal: 1 });
    expect(snapshot.forecasts[0].predictedPoints).toEqual([
      { at: 1700000010000, central: 470, q10: 450, q90: 490 },
    ]);
    expect(JSON.stringify(snapshot)).not.toContain('production-secret');
    const html = reportHtml(snapshot);
    expect(html).toContain('Orders &lt;script&gt;alert(1)&lt;/script&gt;');
    expect(html).not.toContain('<script>');
    expect(html).toContain('Overload'.toLowerCase());
  });
});
