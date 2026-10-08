// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors
import type { ForecastSeriesView } from '../../api/types';
import type { BadgeTone } from '../ui';

export type ForecastStage = 'WAITING' | 'COLLECTING' | 'OBSERVING' | 'APPROVABLE' | 'APPROVED' | 'DEGRADED' | 'BLOCKED';

export interface ForecastStageSummary {
  stage: ForecastStage;
  label: string;
  tone: BadgeTone;
  detail: string;
}

/** `~25 min`, `~3 h`, `~2 d`: an estimate from the sampling interval, worded as one. */
export function about(points: number, stepMillis: number): string {
  const minutes = Math.max(0, points) * stepMillis / 60000;
  if (minutes < 1) return 'under a minute';
  if (minutes < 90) return `~${Math.round(minutes)} min`;
  if (minutes < 48 * 60) return `~${Math.round(minutes / 60)} h`;
  return `~${Math.round(minutes / 1440)} d`;
}

/**
 * Where one series stands, in the order a forecast moves through: history, first forecast,
 * quality, approval. The card shows this line instead of sending the reader to the panel to
 * assemble it from three separate readouts.
 */
export function forecastStage(series: ForecastSeriesView): ForecastStageSummary {
  const r = series.result;
  const step = series.stepMillis;
  if (!r) return { stage: 'WAITING', label: 'Waiting', tone: 'neutral', detail: 'Waiting for the first forecast cycle' };
  if (r.state === 'DEGRADED')
    return { stage: 'DEGRADED', label: 'Degraded', tone: 'error', detail: 'Drift detected; revise the series to restart its evaluation' };
  if (r.visibility === 'ACTIVE') return { stage: 'APPROVED', label: 'Approved', tone: 'primary', detail: 'Approved after quality checks' };
  if (!r.forecast) {
    if (r.context.status !== 'WARMING_UP') return { stage: 'BLOCKED', label: 'Blocked', tone: 'warning', detail: r.reason };
    const have = Math.min(r.context.observedPoints, series.minimumContextPoints);
    return {
      stage: 'COLLECTING', label: 'Collecting', tone: 'neutral',
      detail: `History ${have} / ${series.minimumContextPoints} points · first forecast in ${about(series.minimumContextPoints - have, step)}`,
    };
  }
  const a = series.activation;
  if (a.eligible) return { stage: 'APPROVABLE', label: 'Ready to approve', tone: 'success', detail: 'Quality checks passed; approve it from Metrics Forecast' };
  const filling = `quality ${a.blockPoints} / ${a.blockPointsRequired} realised points`;
  const detail = a.lastBlockPassed === false
    ? `Observing · last quality block failed; the next one holds ${a.blockPoints} / ${a.blockPointsRequired} points`
    : a.blockPoints < a.blockPointsRequired
      ? `Observing · ${filling} · approvable in ${about(a.blockPointsRequired - a.blockPoints, step)} if it passes`
      : `Observing · ${a.reason ?? filling}`;
  return { stage: 'OBSERVING', label: 'Observing', tone: 'neutral', detail };
}
