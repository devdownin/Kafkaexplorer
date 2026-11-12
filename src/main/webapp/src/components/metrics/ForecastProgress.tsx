// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors
import type { ForecastProgress as Progress, ForecastRecord } from '../../api/types';
import { about } from './forecastStage';

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
  const unit = result?.context.outputUnit ?? '';
  const step = progress?.stepMillis ?? result?.context.profile?.stepMillis ?? 0;
  const ahead = future.length && step ? about(future.length, step).replace(/^~/, '') : null;
  const tiles = [
    change !== null && <div key="trend"><h3 className="font-semibold">Trend</h3>
      <p>{change > 0 ? 'Rising' : change < 0 ? 'Falling' : 'Stable'} by {Math.abs(change).toPrecision(4)} {unit}{ahead ? ` over ${ahead}` : ''}</p>
      <p className="text-xs">Median at the end of the horizon versus the last measured value</p></div>,
    result?.forecast && <div key="horizon"><h3 className="font-semibold">Horizon</h3>
      <p>{future.length} points · to {new Date(future[future.length - 1]?.at ?? 0).toLocaleString()}</p></div>,
    progress && progress.breachState !== 'NOT_CONFIGURED' && <div key="breach"><h3 className="font-semibold">Predicted threshold breach</h3>
      <p>{breach ? `${breach.breached ? 'Predicted' : 'No conservative breach'} · ${breach.direction} ${breach.threshold} ${unit}`
        : progress.breachState === 'UNAVAILABLE' ? 'Evaluation unavailable' : 'Not evaluated for this result'}</p>
      <p className="text-xs">Your threshold against the cautious edge of the 80 % interval; no alert is sent</p></div>,
    result?.quality && <div key="quality"><h3 className="font-semibold">Realised quality</h3>
      <p>MAE {result.quality.mae.toPrecision(4)} · {result.evaluatedPoints} points</p></div>,
  ].filter(Boolean);
  return <div className="space-y-3" aria-label="Forecast progress and summary">
    {error && <p role="status">{error}</p>}
    {progress && <p className="text-sm">Checked {new Date(progress.checkedAt).toLocaleString()}. Next cycle estimate: {progress.nextScheduledAt ? new Date(progress.nextScheduledAt).toLocaleString() : 'cycle in progress'}.</p>}
    {/* A tile is shown when it has something to say (METRICS-FORECAST-UX-AUDIT.md U3): a series
        collecting history showed four boxes reading "Unmeasured", "No forecast yet", "No threshold
        configured" and "Unmeasured". */}
    {result && tiles.length > 0 && <div className="grid gap-3 sm:grid-cols-2 lg:grid-cols-4">{tiles}</div>}
    {/* The timeline above says where history stands; the full context's detail is one click away. */}
    {progress && <details><summary>History window and sources</summary>
      {progress.state !== 'UNAVAILABLE' && <><p>Full context: {progress.observedPoints} / {progress.requiredPoints} observed points · {progress.state.replace(/_/g, ' ')}</p>
      <progress aria-label="Observed forecast history" max={progress.requiredPoints} value={Math.min(progress.observedPoints ?? 0, progress.requiredPoints)} className="w-full" />
      <p>Missing: {progress.missingPoints} · Imputed: {progress.imputedPoints}</p></>}
      <p>{progress.reason}</p>
      <p>Topics: {progress.topics.join(', ')} · Groups: {progress.groups.join(', ') || 'none'}</p>
      <p>Sampling interval: {progress.stepMillis / 1000}s · Configured horizon: {progress.horizon} points</p>
      {result && <p>History: {new Date(result.context.fromInclusive).toLocaleString()} – {new Date(result.context.toExclusive).toLocaleString()} · Generated: {new Date(result.generatedAt).toLocaleString()}</p>}
    </details>}
  </div>;
}
