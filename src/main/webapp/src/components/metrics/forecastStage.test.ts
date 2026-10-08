// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors
import { describe, expect, it } from 'vitest';
import type { ForecastActivationReadiness, ForecastRecord, ForecastSeriesView } from '../../api/types';
import { about, forecastStage } from './forecastStage';

const filling: ForecastActivationReadiness = { eligible: false, reason: 'The first quality block holds 40 of 180 realised points',
  blockPoints: 40, blockPointsRequired: 180, lastBlockPassed: null, failedBlocks: 0, failedBlocksToDegrade: 2 };
function series(result: Partial<ForecastRecord> | null, activation: Partial<ForecastActivationReadiness> = {}): ForecastSeriesView {
  return { seriesId: 'a'.repeat(64), metricId: 'lag', environment: 'local', stepMillis: 60000, minimumContextPoints: 128,
    withdrawable: true, activation: { ...filling, ...activation },
    result: result && { state: 'READY', strategy: 'TIMESFM', visibility: 'SHADOW', reason: 'ready', forecast: { points: [] },
      context: { status: 'READY', observedPoints: 512 }, ...result } as ForecastRecord };
}

describe('forecastStage', () => {
  it('waits for the first cycle before any result exists', () => {
    expect(forecastStage(series(null))).toMatchObject({ stage: 'WAITING', detail: 'Waiting for the first forecast cycle' });
  });
  it('counts history towards the first forecast and says when it is due', () => {
    const s = series({ forecast: null, context: { status: 'WARMING_UP', observedPoints: 68 } as ForecastRecord['context'] });
    expect(forecastStage(s)).toMatchObject({ stage: 'COLLECTING', detail: 'History 68 / 128 points · first forecast in ~60 min' });
  });
  it('names what blocks a series that is not merely warming up', () => {
    const s = series({ forecast: null, reason: 'Gaps exceed the preparation policy',
      context: { status: 'INSUFFICIENT_HISTORY', observedPoints: 400 } as ForecastRecord['context'] });
    expect(forecastStage(s)).toMatchObject({ stage: 'BLOCKED', tone: 'warning', detail: 'Gaps exceed the preparation policy' });
  });
  it('estimates approval from the quality block still filling', () => {
    expect(forecastStage(series({}))).toMatchObject({ stage: 'OBSERVING',
      detail: 'Observing · quality 40 / 180 realised points · approvable in ~2 h if it passes' });
  });
  it('says a failed block restarts the count rather than promising a date', () => {
    expect(forecastStage(series({}, { lastBlockPassed: false, blockPoints: 10 })).detail)
      .toBe('Observing · last quality block failed; the next one holds 10 / 180 points');
  });
  it('reports approvable, approved and degraded in their own words', () => {
    expect(forecastStage(series({}, { eligible: true })).stage).toBe('APPROVABLE');
    expect(forecastStage(series({ visibility: 'ACTIVE' })).stage).toBe('APPROVED');
    expect(forecastStage(series({ state: 'DEGRADED' }))).toMatchObject({ stage: 'DEGRADED', tone: 'error' });
  });
  it('words a duration as an estimate at the scale it falls in', () => {
    expect(about(0, 60000)).toBe('under a minute');
    expect(about(25, 60000)).toBe('~25 min');
    expect(about(180, 60000)).toBe('~3 h');
    expect(about(500, 900000)).toBe('~5 d');
  });
});
