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

export type StepState = 'done' | 'current' | 'pending' | 'failed';

export interface TimelineStep {
  key: 'history' | 'forecast' | 'quality' | 'approval';
  label: string;
  state: StepState;
  detail: string;
}

/**
 * The four steps every series goes through, each with what it has reached and what it waits for.
 *
 * The panel used to say the same things in three places that did not read together: history
 * against 512 in the progress block, the quality block in a sentence of its own, and the approval
 * blocker under the button. A reader had to know the order to know which one mattered now.
 */
export function forecastTimeline(series: ForecastSeriesView): TimelineStep[] {
  const r = series.result;
  const a = series.activation;
  const step = series.stepMillis;
  const need = series.minimumContextPoints;
  const collected = r?.context?.observedPoints ?? 0;
  const forecasting = Boolean(r?.forecast);

  const history: TimelineStep = !r
    ? { key: 'history', label: 'History', state: 'current', detail: 'Waiting for the first forecast cycle' }
    : forecasting || r.context?.status === 'READY'
      ? { key: 'history', label: 'History', state: 'done',
        detail: collected >= 512 ? 'Full 512-point context' : `${collected} points; the context grows to 512` }
      : r.context?.status === 'WARMING_UP'
        ? { key: 'history', label: 'History', state: 'current',
          detail: `${Math.min(collected, need)} / ${need} points · first forecast in ${about(need - collected, step)}` }
        : { key: 'history', label: 'History', state: 'failed', detail: r.reason };

  const forecast: TimelineStep = forecasting && r?.strategy === 'TIMESFM'
    ? { key: 'forecast', label: 'Forecast', state: 'done', detail: `TimesFM · ${r.forecast!.points.length} points ahead` }
    : forecasting
      ? { key: 'forecast', label: 'Forecast', state: 'failed', detail: `Baseline fallback, no interval: ${r!.reason}` }
      : history.state === 'done'
        ? { key: 'forecast', label: 'Forecast', state: 'current', detail: 'Due at the next cycle' }
        : { key: 'forecast', label: 'Forecast', state: 'pending', detail: `Needs ${need} points of history` };

  const block = `${a.blockPoints} / ${a.blockPointsRequired} realised points`;
  const quality: TimelineStep = r?.state === 'DEGRADED'
    ? { key: 'quality', label: 'Quality', state: 'failed', detail: 'Drift on consecutive blocks; revise the series to restart its evaluation' }
    : !forecasting
      ? { key: 'quality', label: 'Quality', state: 'pending', detail: `Judged over ${a.blockPointsRequired} realised points` }
      : a.lastBlockPassed === true && a.failedBlocks === 0
        ? { key: 'quality', label: 'Quality', state: 'done', detail: `Last block passed · next ${block}` }
        : a.lastBlockPassed === false
          ? { key: 'quality', label: 'Quality', state: 'current',
            detail: `Last block failed (${a.failedBlocks} of ${a.failedBlocksToDegrade} before drift) · next ${block}` }
          : { key: 'quality', label: 'Quality', state: 'current',
            detail: `${block} · judged in ${about(a.blockPointsRequired - a.blockPoints, step)}` };

  const approval: TimelineStep = r?.visibility === 'ACTIVE'
    ? { key: 'approval', label: 'Approval', state: 'done', detail: 'Approved' }
    : a.eligible
      ? { key: 'approval', label: 'Approval', state: 'current', detail: 'Ready: approve it below' }
      : { key: 'approval', label: 'Approval', state: 'pending', detail: `Not yet: ${a.reason ?? 'quality checks pending'}` };

  return [history, forecast, quality, approval];
}
