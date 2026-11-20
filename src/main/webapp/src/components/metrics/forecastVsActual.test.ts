// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors
import { describe, expect, it } from 'vitest';
import type { ForecastQualityWindow, MetricForecast } from '../../api/types';
import { compareWithActual, forecastReliability, lastCheck } from './forecastVsActual';

const step = 60000;
const previous = { historyEndAt: 0, points: [1, 2, 3, 4].map(i => ({ at: i * step, central: 100, q10: 90, q50: 100, q90: 110 })) } as MetricForecast;

describe('compareWithActual', () => {
  it('scores each predicted point against the measurement at the same instant', () => {
    const measured = [{ endAt: step, value: 105, imputed: false }, { endAt: 2 * step, value: 120, imputed: false },
      { endAt: 3 * step, value: 96, imputed: false }, { endAt: 4 * step, value: 100, imputed: false }];
    expect(compareWithActual(previous, measured)).toEqual({ compared: 4, inBand: 3, meanError: (5 + 20 + 4 + 0) / 4,
      bias: (5 + 20 - 4 + 0) / 4, madeAt: 0 });
  });

  it('leaves out a filled gap and an instant not measured yet, rather than scoring them', () => {
    const measured = [{ endAt: step, value: 100, imputed: false }, { endAt: 2 * step, value: 500, imputed: true }];
    expect(compareWithActual(previous, measured)).toMatchObject({ compared: 1, inBand: 1, meanError: 0 });
    expect(compareWithActual(previous, [])).toMatchObject({ compared: 0, meanError: null, bias: null });
  });
});

describe('lastCheck', () => {
  const quality = (coverage: number, mae: number) => ({ mae, mase: null, meanPinballLoss: 0, q10Q90Coverage: coverage, meanIntervalWidth: 0 });
  it('takes the latest scored cohort, else the last closed block, else nothing', () => {
    const window = { open: [{ through: 1, points: 10, quality: quality(0.7, 5), baselineMae: {}, meanAbsActual: 1 },
      { through: 2, points: 30, quality: quality(0.9, 3), baselineMae: {}, meanAbsActual: 1 }],
    lastBlock: { through: 0, cohorts: 6, points: 180, quality: quality(0.8, 4) }, failedBlocks: 0 } as unknown as ForecastQualityWindow;
    expect(lastCheck(window)).toEqual({ points: 30, coverage: 0.9, meanError: 3 });
    expect(lastCheck({ ...window, open: [] })).toEqual({ points: 180, coverage: 0.8, meanError: 4 });
    expect(lastCheck({ open: [], lastBlock: null, failedBlocks: 0 })).toBeNull();
    expect(lastCheck(null)).toBeNull();
  });
});

describe('forecastReliability', () => {
  it('says a word from the coverage of the 80 % band, once there are enough points to judge', () => {
    expect(forecastReliability({ points: 30, coverage: 0.87 })).toMatchObject({ level: 'reliable', label: 'Reliable', tone: 'success' });
    expect(forecastReliability({ points: 30, coverage: 0.7 }).level).toBe('reliable');
    expect(forecastReliability({ points: 30, coverage: 0.55 })).toMatchObject({ level: 'fair', tone: 'warning' });
    expect(forecastReliability({ points: 30, coverage: 0.3 })).toMatchObject({ level: 'watch', tone: 'error' });
    expect(forecastReliability({ points: 9, coverage: 1 })).toMatchObject({ level: 'early', tone: 'neutral' });
  });
});
