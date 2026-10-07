// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors
import type { ForecastProgress as Progress, ForecastRecord } from '../../api/types';

/** Presentational: the panel's single poll supplies the progress and its error. */
export function ForecastProgress({ progress, error, result }: {
  progress: Progress | null; error: string; result: ForecastRecord | null | undefined;
}) {
  const history = result?.context.points.filter(p => p.value !== null && !p.imputed) ?? [];
  const future = result?.forecast?.points ?? [];
  const measured = history[history.length - 1]?.value;
  const predicted = future[future.length - 1]?.q50;
  const change = measured != null && predicted != null ? predicted - measured : null;
  const breach = progress?.breach?.resultKey === result?.key ? progress?.breach?.threshold : null;
  return <div className="space-y-3" aria-label="Forecast progress and summary">
    {error && <p role="status">{error}</p>}
    {progress && <>
      {progress.state !== 'UNAVAILABLE' && <><p>History: {progress.observedPoints} / {progress.requiredPoints} observed points · {progress.state.replace(/_/g, ' ')}</p>
      <progress aria-label="Observed forecast history" max={progress.requiredPoints} value={Math.min(progress.observedPoints ?? 0, progress.requiredPoints)} className="w-full" />
      <p>Missing: {progress.missingPoints} · Imputed: {progress.imputedPoints}</p></>}
      <p>{progress.reason}</p>
      <p className="text-sm">Checked {new Date(progress.checkedAt).toLocaleString()}. Next cycle estimate: {progress.nextScheduledAt ? new Date(progress.nextScheduledAt).toLocaleString() : 'cycle in progress'}.</p>
    </>}
    {result && <div className="grid gap-3 sm:grid-cols-2 lg:grid-cols-4">
      <div><h3 className="font-semibold">Trend</h3><p>{change === null ? 'Unmeasured' : `${change > 0 ? 'Rising' : change < 0 ? 'Falling' : 'Stable'} · ${change.toPrecision(4)} ${result.context.outputUnit}`}</p><p className="text-xs">Last Q50 versus last measured value</p></div>
      <div><h3 className="font-semibold">Horizon</h3><p>{result.forecast ? `${result.forecast.points.length} points · to ${new Date(future[future.length - 1]?.at ?? 0).toLocaleString()}` : 'No forecast yet'}</p></div>
      <div><h3 className="font-semibold">Predicted threshold breach</h3><p>{breach ? `${breach.breached ? 'Predicted' : 'No conservative breach'} · ${breach.direction} ${breach.threshold} ${result.context.outputUnit}`
        : progress?.breachState === 'NOT_CONFIGURED' ? 'No threshold configured' : progress?.breachState === 'UNAVAILABLE' ? 'Evaluation unavailable' : 'Not evaluated for this result'}</p><p className="text-xs">Your threshold against the cautious edge of the 80 % interval; no alert is sent</p></div>
      <div><h3 className="font-semibold">Realised quality</h3><p>{result.quality ? `MAE ${result.quality.mae.toPrecision(4)} · ${result.evaluatedPoints} points` : 'Unmeasured'}</p></div>
    </div>}
    {progress && <details><summary>Sources and history window</summary>
      <p>Topics: {progress.topics.join(', ')} · Groups: {progress.groups.join(', ') || 'none'}</p>
      <p>Sampling interval: {progress.stepMillis / 1000}s · Configured horizon: {progress.horizon} points</p>
      {result && <p>History: {new Date(result.context.fromInclusive).toLocaleString()} – {new Date(result.context.toExclusive).toLocaleString()} · Generated: {new Date(result.generatedAt).toLocaleString()}</p>}
    </details>}
  </div>;
}
