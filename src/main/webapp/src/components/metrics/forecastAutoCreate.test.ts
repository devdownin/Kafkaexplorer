// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors
import { beforeEach, describe, expect, it, vi } from 'vitest';
import axios from 'axios';
import { AutoCreateRefused, createForecastFromMetric, firstForecastSentence } from './forecastAutoCreate';

vi.mock('axios', () => ({ default: { get: vi.fn(), post: vi.fn(), isAxiosError: vi.fn(() => true) } }));
const metric = { metricId: 'lag', name: 'Lag', definitionVersion: 'version', unit: 'milliseconds', transformation: 'GAUGE_MEAN',
  topics: ['orders'], groups: ['worker'], eligible: true, enrolled: true, blockers: [], suggestedStepMillis: 60000 };
const catalogue = { metrics: [metric], total: 1, truncated: false, clusterId: 'existing', collectorId: 'collector',
  applyUnavailable: null, minimumContextPoints: 128 };
const history = (observedPoints: number, suggestedSeasonality: string | null = null) => ({ status: 'READY', reason: 'ok',
  observedPoints, requiredPoints: 128, unit: 'milliseconds', points: [], suggestedSeasonality, correlation: null });
function serve(preview: unknown, candidates: unknown = catalogue) {
  vi.mocked(axios.get).mockImplementation((url: string) => String(url).endsWith('/history')
    ? preview instanceof Error ? Promise.reject(preview) : Promise.resolve({ data: preview })
    : Promise.resolve({ data: candidates }));
}

describe('createForecastFromMetric', () => {
  beforeEach(() => { vi.clearAllMocks(); vi.mocked(axios.post).mockResolvedValue({ data: { seriesId: 'forecast-lag' } }); });

  it('creates the series from what the metric already says, with no form', async () => {
    serve(history(600));
    const created = await createForecastFromMetric('lag');
    expect(created).toEqual({ seriesId: 'forecast-lag', name: 'Lag',
      firstForecast: 'Its first forecast is computed at the next cycle, from the history already recorded' });
    expect(axios.post).toHaveBeenCalledExactlyOnceWith('/api/forecasts/series', expect.objectContaining({
      metricId: 'lag', definitionVersion: 'version', unit: 'milliseconds', environment: 'local',
      clusterId: 'existing', collectorId: 'collector', topics: ['orders'], groups: ['worker'],
      stepMillis: 60000, horizon: 30, threshold: null, seasonality: 'NONE', confirmed: true,
    }), expect.objectContaining({ timeout: 10000 }));
  });

  it('takes the cycle the history shows', async () => {
    serve(history(600, 'HOURLY'));
    await createForecastFromMetric('lag');
    expect(axios.post).toHaveBeenCalledWith('/api/forecasts/series', expect.objectContaining({ seasonality: 'HOURLY' }), expect.anything());
  });

  it('still creates it, without a cycle, when the history cannot be read', async () => {
    serve(new Error('down'));
    const created = await createForecastFromMetric('lag');
    expect(axios.post).toHaveBeenCalledWith('/api/forecasts/series', expect.objectContaining({ seasonality: 'NONE' }), expect.anything());
    expect(created.firstForecast).toBe('Its first forecast follows after 128 points, ~2 h of history');
  });

  it('refuses with the reason, before posting anything, what the catalogue says cannot be created', async () => {
    serve(history(0), { ...catalogue, metrics: [{ ...metric, eligible: false, blockers: ['Set a known unit'] }] });
    await expect(createForecastFromMetric('lag')).rejects.toEqual(new AutoCreateRefused('Set a known unit'));
    serve(history(0), { ...catalogue, applyUnavailable: 'Runtime approval is off' });
    await expect(createForecastFromMetric('lag')).rejects.toThrow('Runtime approval is off');
    serve(history(0));
    await expect(createForecastFromMetric('missing')).rejects.toThrow('absent from the candidate catalogue');
    expect(axios.post).not.toHaveBeenCalled();
  });

  it('passes the server\'s own refusal on', async () => {
    serve(history(600));
    vi.mocked(axios.post).mockRejectedValue({ response: { data: { reason: 'History not available' } } });
    await expect(createForecastFromMetric('lag')).rejects.toEqual(new AutoCreateRefused('History not available'));
  });
});

describe('firstForecastSentence', () => {
  it('counts what is already recorded', () => {
    expect(firstForecastSentence(128, 100, 60000)).toBe('100 of the 128 points it needs are already recorded; its first forecast follows in ~28 min');
    expect(firstForecastSentence(128, 128, 60000)).toBe('Its first forecast is computed at the next cycle, from the history already recorded');
    expect(firstForecastSentence(null, 5, 60000)).toBe('Its first forecast follows once enough points exist');
  });
});
