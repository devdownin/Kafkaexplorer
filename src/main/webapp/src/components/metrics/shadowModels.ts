// SPDX-License-Identifier: AGPL-3.0-or-later
import type { ForecastCovariateComparison, ForecastEvaluation, ForecastShadowComparison } from '../../api/types';

/**
 * What the panel knows about the shadow for the selected series. `OFF` is a 404 — no shadow on
 * this deployment — and renders nothing; `EMPTY` is a 204, a shadow with no compatible row yet,
 * which is a different sentence from "unavailable".
 */
export type ShadowView =
  | { kind: 'OFF' }
  | { kind: 'EMPTY' }
  | { kind: 'ERROR' }
  | { kind: 'READY'; comparison: ForecastShadowComparison };

export interface ModelRow {
  model: string;
  quality: ForecastEvaluation | null;
  blockPassed: boolean | null;
}

export function isShadowComparison(data: unknown): data is ForecastShadowComparison {
  const c = data as ForecastShadowComparison | null;
  return !!c && typeof c.seriesId === 'string' && typeof c.state === 'string' && typeof c.wouldQualify === 'boolean'
    && Number.isFinite(c.evaluatedPoints) && Number.isFinite(c.blockPointsRequired)
    && typeof c.baselineMae === 'object' && c.baselineMae !== null;
}

/** TimesFM and the shadow on the cohorts both were scored on, TimesFM first: it is what is served. */
export function shadowRows(c: ForecastShadowComparison): ModelRow[] {
  return [
    { model: 'TimesFM', quality: c.primaryQuality, blockPassed: c.primaryBlockPassed },
    { model: 'Timestral', quality: c.shadowQuality, blockPassed: c.shadowBlockPassed },
  ];
}

export function covariateRows(c: ForecastCovariateComparison): ModelRow[] {
  return [
    { model: 'TimesFM', quality: c.primaryQuality, blockPassed: c.primaryBlockPassed },
    { model: 'Timestral', quality: c.univariateQuality, blockPassed: c.univariateBlockPassed },
    { model: 'Timestral + covariates', quality: c.multivariateQuality, blockPassed: c.multivariateBlockPassed },
  ];
}

/**
 * The lowest MAE, named only once a block has been judged for every model: before that the
 * figures pool a handful of cohorts, and naming a leader on them is the verdict the block exists
 * to withhold.
 */
export function lowestMae(rows: ModelRow[]): string | null {
  if (rows.some(r => r.quality === null || r.blockPassed === null)) return null;
  const best = Math.min(...rows.map(r => r.quality!.mae));
  const leaders = rows.filter(r => r.quality!.mae === best);
  return leaders.length === 1 ? leaders[0].model : null;
}

export function blockVerdict(passed: boolean | null): string {
  return passed === null ? 'Not judged yet' : passed ? 'Passed' : 'Failed';
}
