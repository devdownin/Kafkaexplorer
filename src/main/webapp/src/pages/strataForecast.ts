// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors

import type { ForecastSeriesView } from '../api/types';

export interface StrataProjection {
  topic: string;
  seriesId: string;
  metricId: string;
  unit: string;
  modelId: string;
  visibility: string;
  generatedAt: number;
  historyEndAt: number;
  observed: Array<{ at: number; value: number }>;
  future: Array<{ at: number; q10: number; q50: number; q90: number }>;
}

/**
 * Only an explicitly scoped, ready, actual TimesFM forecast can extend a Kafka topic.
 * The forecast lives on its own metric axis: its values are NEVER interpreted as
 * produced offsets unless the configured metric actually has that output unit.
 */
export function spatialProjection(
  series: ForecastSeriesView, topic: string, now = Date.now(),
): StrataProjection | null {
  if (series.topics.length !== 1 || series.topics[0] !== topic) return null;
  const result = series.result;
  if (!result || result.state !== 'READY' || result.strategy !== 'TIMESFM') return null;
  const forecast = result.forecast;
  if (!forecast || forecast.seriesId !== series.seriesId || forecast.outputUnit !== result.context.outputUnit) return null;
  if (!Number.isFinite(forecast.historyEndAt) || !Number.isFinite(series.stepMillis) || series.stepMillis <= 0) return null;
  const observed = result.context.points
    .filter(p => !p.imputed && p.value !== null && Number.isFinite(p.value)
      && Number.isFinite(p.endAt) && p.endAt <= forecast.historyEndAt)
    .sort((a, b) => a.endAt - b.endAt)
    .slice(-16)
    .map(p => ({ at: p.endAt, value: p.value as number }));
  if (observed.length < 2) return null;
  if (Math.abs(observed[observed.length - 1].at - forecast.historyEndAt) > series.stepMillis * 2) return null;
  const future = forecast.points
    .filter(p => [p.at, p.q10, p.q50, p.q90].every(Number.isFinite)
      && p.at > forecast.historyEndAt && p.q10 <= p.q50 && p.q50 <= p.q90)
    .sort((a, b) => a.at - b.at)
    .slice(0, 24)
    .map(p => ({ at: p.at, q10: p.q10, q50: p.q50, q90: p.q90 }));
  if (future.length === 0 || !future.some(p => p.at > now)) return null;
  return {
    topic, seriesId: series.seriesId, metricId: series.metricId,
    unit: forecast.outputUnit, modelId: forecast.modelId,
    visibility: result.visibility, generatedAt: result.generatedAt,
    historyEndAt: forecast.historyEndAt, observed, future,
  };
}
