// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors
import axios from 'axios';
import type { ForecastApplied, ForecastCandidate, ForecastCandidates, ForecastDraftRequest, ForecastHistoryPreview } from '../../api/types';
import { about } from './forecastStage';

/**
 * Creating a forecast from a metric card, with no form.
 *
 * Every forecast starts from an existing metric, so everything it needs is already known: the
 * metric's definition and sources come from the candidate catalogue, the interval from the server's
 * suggestion, the cycle from the recorded history. This used to run inside the assistant, which the
 * page therefore had to open — on success too, to say "Forecast started" in a section the operator
 * then had to close. The form is now only for when this fails.
 */
export const CADENCE_OPTIONS = [30000, 60000, 300000, 900000];
export const SEASON_PERIODS: Record<string, number> = { NONE: 0, HOURLY: 3600000, DAILY: 86400000 };
/** The server's rule: a whole number of samples, 2 to 512. */
export const seasonFits = (period: number, step: number) =>
  period === 0 || (period % step === 0 && period / step >= 2 && period / step <= 512);
/** The server's suggestion when it is one of the offered intervals, the minute otherwise. */
export const cadenceFor = (c: ForecastCandidate | null) =>
  c && CADENCE_OPTIONS.includes(c.suggestedStepMillis) ? c.suggestedStepMillis : 60000;
/** Thirty minutes ahead, in whole points of the interval. */
export const DEFAULT_AHEAD_MS = 1800000;

/**
 * When the first forecast follows, counting what is already recorded. It used to promise "~2 h"
 * whatever was there, while the journal records a metric from the moment it runs.
 */
export function firstForecastSentence(firstPoints: number | null, recorded: number | null, stepMillis: number): string {
  if (firstPoints === null) return 'Its first forecast follows once enough points exist';
  if (recorded !== null && recorded >= firstPoints) return 'Its first forecast is computed at the next cycle, from the history already recorded';
  if (recorded !== null) return `${recorded} of the ${firstPoints} points it needs are already recorded; its first forecast follows in ${about(firstPoints - recorded, stepMillis)}`;
  return `Its first forecast follows after ${firstPoints} points, ${about(firstPoints, stepMillis)} of history`;
}

export interface AutoCreated { seriesId: string; name: string; firstForecast: string }

/** Thrown with the server's own reason when it gave one, so the form can open on it. */
export class AutoCreateRefused extends Error {}

export async function createForecastFromMetric(metricId: string, signal?: AbortSignal): Promise<AutoCreated> {
  const { data: catalogue } = await axios.get<ForecastCandidates>('/api/forecasts/candidates', { signal, timeout: 10000 });
  const candidate = Array.isArray(catalogue?.metrics) ? catalogue.metrics.find(m => m.metricId === metricId) : undefined;
  if (!candidate) throw new AutoCreateRefused('The metric is absent from the candidate catalogue');
  if (catalogue.applyUnavailable !== null) throw new AutoCreateRefused(catalogue.applyUnavailable || 'Forecast creation is currently unavailable');
  if (!candidate.eligible) throw new AutoCreateRefused(candidate.blockers.join('; ') || 'This metric cannot be forecast');
  if (!candidate.topics.length) throw new AutoCreateRefused('This metric has no forecastable source topics');
  const stepMillis = cadenceFor(candidate);
  // The cycle the history shows, as the form would propose it. A preview that cannot be read costs
  // the cycle, never the creation.
  let seasonality = 'NONE';
  let recorded: number | null = null;
  try {
    const { data: history } = await axios.get<ForecastHistoryPreview>(
      `/api/forecasts/candidates/${encodeURIComponent(metricId)}/history`, { params: { stepMillis }, signal, timeout: 10000 });
    if (Number.isFinite(history?.observedPoints)) recorded = history.observedPoints;
    const cycle = history?.suggestedSeasonality ?? '';
    if (SEASON_PERIODS[cycle] && seasonFits(SEASON_PERIODS[cycle], stepMillis)) seasonality = cycle;
  } catch (e) {
    if (signal?.aborted) throw e;
  }
  const request: ForecastDraftRequest = {
    metricId: candidate.metricId, definitionVersion: candidate.definitionVersion, environment: 'local',
    unit: candidate.unit, clusterId: catalogue.clusterId || 'local', collectorId: catalogue.collectorId || 'kafkaexplorer',
    topics: candidate.topics, groups: candidate.groups, stepMillis,
    horizon: Math.max(1, Math.round(DEFAULT_AHEAD_MS / stepMillis)),
    threshold: null, direction: 'ABOVE', confirmed: true, seasonality,
  };
  let result: ForecastApplied;
  try {
    ({ data: result } = await axios.post<ForecastApplied>('/api/forecasts/series', request, { signal, timeout: 10000 }));
  } catch (e) {
    const reason = axios.isAxiosError(e) ? e.response?.data?.reason : undefined;
    throw typeof reason === 'string' ? new AutoCreateRefused(reason) : e;
  }
  if (typeof result?.seriesId !== 'string') throw new Error('Invalid forecast response');
  const firstPoints = Number.isFinite(catalogue.minimumContextPoints)
    ? Math.max(catalogue.minimumContextPoints, SEASON_PERIODS[seasonality] / stepMillis) : null;
  return { seriesId: result.seriesId, name: candidate.name || candidate.metricId,
    firstForecast: firstForecastSentence(firstPoints, recorded, stepMillis) };
}
