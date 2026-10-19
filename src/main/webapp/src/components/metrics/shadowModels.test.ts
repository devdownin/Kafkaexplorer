// SPDX-License-Identifier: AGPL-3.0-or-later
import { describe, expect, it } from 'vitest';
import type { ForecastEvaluation, ForecastShadowComparison } from '../../api/types';
import { blockVerdict, covariateRows, isShadowComparison, lowestMae, shadowRows } from './shadowModels';

const quality = (mae: number): ForecastEvaluation => ({ mae, mase: null, meanPinballLoss: mae / 2, q10Q90Coverage: .8, meanIntervalWidth: 1 });
export const comparison: ForecastShadowComparison = {
  seriesId: 'a'.repeat(64), state: 'READY', reason: 'Shadow forecast; never served', modelId: 'mistralai/Timestral-28M',
  revision: 'c'.repeat(40), generatedAt: 1, evaluatedPoints: 120, evaluatedThrough: 2, blockPoints: 0, blockPointsRequired: 120,
  shadowQuality: quality(1), primaryQuality: quality(2), baselineMae: { LAST_VALUE: 3 },
  shadowBlockPassed: true, primaryBlockPassed: true, wouldQualify: true, qualificationBlocker: null,
  covariates: {
    covariateMetricIds: ['orders-produced'], state: 'WAITING', reason: 'Covariate orders-produced is WARMING_UP',
    evaluatedPoints: 40, blockPoints: 40, blockPointsRequired: 120, multivariateQuality: quality(.5),
    univariateQuality: quality(1), primaryQuality: quality(2), baselineMae: {}, multivariateBlockPassed: null,
    univariateBlockPassed: null, primaryBlockPassed: null, wouldQualify: false,
    qualificationBlocker: 'The first shared quality block holds 40 of 120 realised points',
  },
};

describe('shadowModels', () => {
  it('lists TimesFM first, since it is the model being served', () => {
    expect(shadowRows(comparison).map(r => r.model)).toEqual(['TimesFM', 'Timestral']);
    expect(covariateRows(comparison.covariates!).map(r => r.model)).toEqual(['TimesFM', 'Timestral', 'Timestral + covariates']);
  });
  it('names a leader only once every model has a judged block, and never on a tie', () => {
    expect(lowestMae(shadowRows(comparison))).toBe('Timestral');
    expect(lowestMae(covariateRows(comparison.covariates!))).toBeNull();
    expect(lowestMae(shadowRows({ ...comparison, primaryQuality: quality(1) }))).toBeNull();
  });
  it('accepts only a comparison-shaped response', () => {
    expect(isShadowComparison(comparison)).toBe(true);
    expect(isShadowComparison({ enabled: true, series: [] })).toBe(false);
    expect(isShadowComparison('')).toBe(false);
  });
  it('words an unjudged block as such rather than as a failure', () => {
    expect([null, true, false].map(blockVerdict)).toEqual(['Not judged yet', 'Passed', 'Failed']);
  });
});
