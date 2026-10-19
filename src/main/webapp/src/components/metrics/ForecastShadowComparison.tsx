// SPDX-License-Identifier: AGPL-3.0-or-later
import { Badge } from '../ui';
import type { ForecastCovariateComparison, ForecastShadowComparison as Comparison } from '../../api/types';
import { blockVerdict, covariateRows, lowestMae, shadowRows, type ModelRow, type ShadowView } from './shadowModels';

function QualityTable({ label, rows }: { label: string; rows: ModelRow[] }) {
  return <table aria-label={label}><thead><tr>
    <th>Model</th><th>MAE</th><th>Interval coverage</th><th>Pinball loss</th><th>Last block</th>
  </tr></thead><tbody>
    {rows.map(r => <tr key={r.model}>
      <td>{r.model}</td>
      <td>{r.quality ? r.quality.mae.toPrecision(4) : 'unmeasured'}</td>
      <td>{r.quality ? `${(100 * r.quality.q10Q90Coverage).toFixed(1)}%` : 'unmeasured'}</td>
      <td>{r.quality ? r.quality.meanPinballLoss.toPrecision(4) : 'unmeasured'}</td>
      <td>{blockVerdict(r.blockPassed)}</td>
    </tr>)}
  </tbody></table>;
}

function Verdict({ rows, wouldQualify, blocker, subject }: {
  rows: ModelRow[]; wouldQualify: boolean; blocker: string | null; subject: string;
}) {
  const leader = lowestMae(rows);
  return <>
    {leader && <p>Lowest MAE on the shared cohorts: {leader}.</p>}
    <p>{wouldQualify
      ? `${subject} would pass TimesFM's approval gate on its last block.`
      : `${subject} would not qualify yet: ${blocker ?? 'no judged block'}.`}</p>
  </>;
}

function Covariates({ c }: { c: ForecastCovariateComparison }) {
  const rows = covariateRows(c);
  return <div className="space-y-2">
    <h4 className="font-medium">With covariates: {c.covariateMetricIds.join(', ')}</h4>
    {c.state && <p><Badge tone={c.state === 'READY' ? 'success' : 'warning'}>{c.state}</Badge> {c.reason}</p>}
    <p>{c.evaluatedPoints} points scored for all three · block {c.blockPoints} / {c.blockPointsRequired}</p>
    <QualityTable label="Shadow quality with covariates" rows={rows} />
    <Verdict rows={rows} wouldQualify={c.wouldQualify} blocker={c.qualificationBlocker} subject="Timestral with covariates" />
  </div>;
}

function Ready({ c }: { c: Comparison }) {
  const rows = shadowRows(c);
  return <div className="space-y-3">
    <p><Badge tone={c.state === 'READY' ? 'success' : 'warning'}>{c.state}</Badge> {c.reason}</p>
    <p>{c.modelId}{c.revision ? ` @ ${c.revision.slice(0, 12)}` : ''} · {c.evaluatedPoints} points scored for both · block {c.blockPoints} / {c.blockPointsRequired}</p>
    <QualityTable label="Shadow quality" rows={rows} />
    {Object.keys(c.baselineMae).length > 0 && <p>Baselines on the same cohorts: {Object.entries(c.baselineMae)
      .map(([name, mae]) => `${name} ${mae.toPrecision(4)}`).join(' · ')}</p>}
    <Verdict rows={rows} wouldQualify={c.wouldQualify} blocker={c.qualificationBlocker} subject="Timestral" />
    {c.covariates && <Covariates c={c.covariates} />}
  </div>;
}

/** Comparison only: the shadow is never served, never approved, and sends nothing. */
export function ForecastShadowComparison({ view }: { view: ShadowView | null }) {
  if (!view || view.kind === 'OFF') return null;
  return <details><summary>Shadow model comparison</summary>
    {view.kind === 'EMPTY' && <p>The shadow is enabled; no forecast compatible with this series has been scored yet.</p>}
    {view.kind === 'ERROR' && <p>Shadow comparison unavailable. Previous figures may be stale.</p>}
    {view.kind === 'READY' && <Ready c={view.comparison} />}
    <p>The shadow is never served or approved, and sends nothing. Both models are scored on the same realised values.</p>
  </details>;
}
