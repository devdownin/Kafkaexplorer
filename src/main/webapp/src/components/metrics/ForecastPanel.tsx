// SPDX-License-Identifier: AGPL-3.0-or-later
import { useEffect, useState } from 'react';
import axios from 'axios';
import { Area, Bar, BarChart, ComposedChart, Legend, Line, ReferenceLine, ResponsiveContainer, Tooltip, XAxis, YAxis } from 'recharts';
import { Badge, Button, Field, Select, Table, TableBody, TableHead, TableRow, Td, Th, useConfirm } from '../ui';
import { copyText } from '../../clipboard';
import type { BadgeTone } from '../ui';
import type {
  ForecastActivationRefusal, ForecastProgress as Progress, ForecastSeriesView, ForecastShadowComparison as Comparison,
  ForecastStatus,
} from '../../api/types';
import { ForecastPreparation } from './ForecastPreparation';
import { ForecastProgress } from './ForecastProgress';
import { ForecastShadowComparison } from './ForecastShadowComparison';
import { isShadowComparison, type ShadowView } from './shadowModels';
import { ForecastTimeline } from './ForecastTimeline';
import { about, forecastStage } from './forecastStage';
import { FORECAST_COLORS } from './forecastColors';

/**
 * READY only says a forecast was produced, not that it can be approved: a green badge beside a
 * disabled Approve button read as a contradiction, so it is neutral, and the quality block says
 * where approval stands. The pilot's own value stays in the tooltip.
 */
// Collecting history is the expected first state of every series, not a warning.
const STATE_TONE: Record<string, BadgeTone> = { READY: 'neutral', WARMING_UP: 'neutral', DEGRADED: 'error' };
const STATE_LABEL: Record<string, string> = {
  READY: 'Forecasting', STALE: 'Stale', WARMING_UP: 'Collecting history', DEGRADED: 'Degraded',
};
/**
 * `UNAVAILABLE` is what a series collecting history is written with: there is no forecast, so
 * there is no fallback either — it read "Baseline fallback" on a yellow badge
 * (METRICS-FORECAST-UX-AUDIT.md B3).
 */
const STRATEGY_LABEL: Record<string, string> = { TIMESFM: 'TimesFM', UNAVAILABLE: 'No forecast yet' };
const NEUTRAL_STRATEGIES = new Set(['TIMESFM', 'UNAVAILABLE']);
/** Plain words on the page; the pilot's own term stays in the tooltip and in the API. */
const VISIBILITY_LABEL: Record<string, string> = { ACTIVE: 'Approved', SHADOW: 'Observing' };
const POLL_MS = 30000;

/**
 * One poll on the panel's clock, paused behind a hidden tab and run at once on return. The run
 * reports its own errors; the signal is aborted when the poll is disposed.
 */
function poll(run: (signal: AbortSignal) => Promise<void>): () => void {
  const controller = new AbortController();
  let timer: ReturnType<typeof setTimeout>;
  let skipped = false;
  async function tick() {
    // Behind a hidden tab the requests answer nobody; the listener below reads at once on return.
    if (document.visibilityState !== 'visible') { skipped = true; timer = setTimeout(tick, POLL_MS); return; }
    skipped = false;
    try { await run(controller.signal); } finally {
      if (!controller.signal.aborted) timer = setTimeout(tick, POLL_MS);
    }
  }
  const onVisible = () => {
    if (document.visibilityState === 'visible' && skipped) { clearTimeout(timer); void tick(); }
  };
  document.addEventListener('visibilitychange', onVisible);
  void tick();
  return () => {
    controller.abort(); clearTimeout(timer);
    document.removeEventListener('visibilitychange', onVisible);
  };
}

function isProgress(data: Progress | undefined, seriesId: string): data is Progress {
  return data?.seriesId === seriesId && (data.state === 'UNAVAILABLE' || Number.isFinite(data.observedPoints))
    && data.requiredPoints === 512 && Array.isArray(data.topics) && Array.isArray(data.groups);
}

export function ForecastPanel({ onConfigure, onStatus, refresh = 0, focus = '' }: {
  /** A series to show: the one the assistant just started, which was otherwise left behind the first. */
  focus?: string;
  onConfigure?: () => void;
  /** Receives every status the panel polls, so the page can show it on the cards without a second poll. */
  onStatus?: (status: ForecastStatus | null) => void;
  /** Bumped by the page after an approval, to poll at once rather than at the next tick. */
  refresh?: number;
}) {
  const [status, setStatus] = useState<ForecastStatus | null>(null);
  const [progress, setProgress] = useState<Progress | null>(null);
  const [progressError, setProgressError] = useState('');
  const [shadow, setShadow] = useState<{ seriesId: string; view: ShadowView } | null>(null);
  const [selected, setSelected] = useState(focus);
  // Followed during render rather than in an effect: it takes hold once the list carries the series.
  const [seenFocus, setSeenFocus] = useState(focus);
  if (focus !== seenFocus) { setSeenFocus(focus); if (focus) setSelected(focus); }
  const [error, setError] = useState('');
  // A refusal belongs to the button that was clicked and must outlive the next poll: sharing
  // `error` with the poll made it vanish within 30 s, and a failing poll overwrite it.
  const [action, setAction] = useState<{ source: 'approve' | 'withdraw' | 'copy'; text: string } | null>(null);
  /** How much of the 512-point context the chart draws: a forecast is judged against more than the last hour. */
  const [shown, setShown] = useState(60);
  /** P2: `/progress` is read only where its answer is on screen — the threshold on the chart, or the open summary. */
  const [summaryOpen, setSummaryOpen] = useState(false);
  const [busy, setBusy] = useState(false);
  const [copied, setCopied] = useState(false);
  const [revision, setRevision] = useState(0);
  /** P4: the preparation checks are read when their section is opened, not on every visit. */
  const [preparationOpen, setPreparationOpen] = useState(false);
  const [detail, setDetail] = useState<ForecastSeriesView | null>(null);
  const confirm = useConfirm();
  /*
   * Two loops on one clock (METRICS-FORECAST-UX-AUDIT.md P1, P3). The list carries no history
   * points, so the selected series is read on its own — its whole record, its progress and its
   * shadow. Choosing another series used to re-run the single loop, the whole list included, to
   * learn about one series whose summary had just arrived; now only that series is read.
   */
  useEffect(() => poll(async signal => {
    try {
      const { data } = await axios.get<ForecastStatus>('/api/forecasts', { signal, timeout: 10000 });
      if (!data || typeof data.enabled !== 'boolean' || typeof data.state !== 'string' || !Array.isArray(data.series))
        throw new Error('Invalid forecast status response');
      if (signal.aborted) return;
      setStatus(data); setError(''); onStatus?.(data);
    } catch {
      if (!signal.aborted) {
        setError('Forecast persistence is unavailable. Existing results may be stale.');
        // The cards would otherwise keep saying "Ready to approve" about a status nobody can read.
        onStatus?.(null);
      }
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps -- onStatus is the page's state setter, stable
  }), [revision, refresh]);
  const seriesId = status?.series.some(s => s.seriesId === selected) ? selected : status?.series[0]?.seriesId;
  // The list says whether a shadow exists at all: asking each series for one answered a 404 that
  // the browser wrote into the console of every Metrics page (PAGES-SIMPLICITY-AUDIT.md X1).
  const shadowConfigured = status?.shadowEnabled !== false;
  const wantProgress = summaryOpen || Boolean(status?.series.find(s => s.seriesId === seriesId)?.thresholdConfigured);
  useEffect(() => {
    if (!seriesId) return;
    // A series that answered 404 has no shadow configured: asking again every tick answers the same.
    let noShadow = !shadowConfigured;
    const path = `/api/forecasts/${encodeURIComponent(seriesId)}`;
    return poll(async signal => {
      try {
        const { data } = await axios.get<ForecastSeriesView>(path, { signal, timeout: 10000 });
        // Anything else keeps the list's summary on screen rather than a blank panel.
        if (data?.seriesId === seriesId && data.activation && !signal.aborted) setDetail(data);
      } catch { /* the summary from the list stays */ }
      if (wantProgress) try {
        const { data: next } = await axios.get<Progress>(`${path}/progress`, { signal, timeout: 10000 });
        if (!isProgress(next, seriesId)) throw new Error('Invalid progress response');
        if (!signal.aborted) { setProgress(next); setProgressError(''); }
      } catch {
        if (!signal.aborted) setProgressError('History progress unavailable. Previous diagnostics may be stale.');
      }
      let view: ShadowView;
      if (noShadow) {
        view = { kind: 'OFF' };
      } else try {
        const response = await axios.get<Comparison | ''>(`${path}/shadow`,
          { signal, timeout: 10000, validateStatus: code => code === 200 || code === 204 || code === 404 });
        if (response.status === 404) noShadow = true;
        view = response.status === 404 ? { kind: 'OFF' } : response.status === 204 ? { kind: 'EMPTY' }
          : isShadowComparison(response.data) ? { kind: 'READY', comparison: response.data } : { kind: 'ERROR' };
      } catch {
        view = { kind: 'ERROR' };
      }
      if (!signal.aborted) setShadow({ seriesId, view });
    });
  }, [seriesId, revision, refresh, shadowConfigured, wantProgress]);
  const listed = status?.series.find(s => s.seriesId === seriesId);
  // The detail is the same row with its history: preferred once it is about the series shown.
  const series = listed && detail?.seriesId === listed.seriesId ? detail : listed;
  const result = series?.result;
  const seriesProgress = progress?.seriesId === series?.seriesId ? progress : null;
  const chart = [
    // An imputed point is a filled gap, not a measurement: drawn apart, never in "Observed" (B4).
    ...(result?.context.points.slice(-shown).map(p => p.imputed ? { at: p.endAt, imputed: p.value } : { at: p.endAt, actual: p.value }) ?? []),
    ...(result?.forecast?.points.map(p => ({ at: p.at, median: p.q50, floor: p.q10, band: p.q90 - p.q10, q10: p.q10, q90: p.q90 })) ?? []),
  ];
  // Fifteen-minute steps over sixty history and sixty forecast points cover a day and more: a tick
  // that shows only the time then repeats across midnight.
  const anyImputed = chart.some(p => 'imputed' in p && p.imputed != null);
  // B5: the threshold the breach is judged against, on the chart it is judged on.
  const threshold = seriesProgress?.breach?.resultKey === result?.key ? seriesProgress?.breach?.threshold ?? null : null;
  const stage = series ? forecastStage(series) : null;
  const spanMs = chart.length > 1 ? chart[chart.length - 1].at - chart[0].at : 0;
  const quality = result?.quality;
  const comparisons = result && quality
    ? [
        ...(Number.isFinite(quality.mae) ? [{ name: 'Forecast', mae: quality.mae }] : []),
        ...Object.entries(result.baselineMae).filter(([, mae]) => Number.isFinite(mae))
          .map(([name, mae]) => ({ name, mae })),
      ].sort((a, b) => a.mae - b.mae)
    : [];
  // Lot 3: annotations are descriptive only, never an anomaly detector or a new model verdict.
  const forecastPoints = result?.forecast?.points ?? [];
  const firstPrediction = forecastPoints[0]?.q50;
  const lastPrediction = forecastPoints[forecastPoints.length - 1]?.q50;
  const forecastChange = firstPrediction !== undefined && lastPrediction !== undefined
    ? lastPrediction - firstPrediction : null;
  const trend = forecastChange === null ? null
    : Math.abs(forecastChange) < Math.max(1e-9, Math.abs(firstPrediction ?? 0) * 0.01)
      ? 'approximately stable' : forecastChange > 0 ? 'rising' : 'falling';
  const predictedOutside = threshold && forecastPoints.length
    ? forecastPoints.filter(p => threshold.direction === 'ABOVE'
      ? p.q50 >= threshold.threshold : p.q50 <= threshold.threshold).length : 0;
  function exportForecast(format: 'csv' | 'json') {
    if (!result?.forecast || !series) return;
    const rows = [
      ...result.context.points.map(p => ({
        timestamp: new Date(p.endAt).toISOString(), kind: p.imputed ? 'imputed' : 'observed',
        value: p.value, q10: null, q50: null, q90: null,
      })),
      ...result.forecast.points.map(p => ({
        timestamp: new Date(p.at).toISOString(), kind: 'forecast',
        value: null, q10: p.q10, q50: p.q50, q90: p.q90,
      })),
    ];
    const columns = ['timestamp', 'kind', 'value', 'q10', 'q50', 'q90'] as const;
    const content = format === 'json'
      ? JSON.stringify({ seriesId: series.seriesId, metricId: series.metricId,
          unit: result.context.outputUnit, strategy: result.strategy, points: rows }, null, 2)
      : [columns.join(','), ...rows.map(row => columns.map(key => row[key] ?? '').join(','))].join('\\n');
    const blob = new Blob([content], { type: format === 'json' ? 'application/json' : 'text/csv;charset=utf-8' });
    const url = URL.createObjectURL(blob);
    const link = document.createElement('a');
    link.href = url;
    link.download = `forecast-${series.seriesId.replace(/[^a-zA-Z0-9_-]/g, '_')}.${format}`;
    document.body.appendChild(link);
    link.click();
    link.remove();
    setTimeout(() => URL.revokeObjectURL(url), 1000);
  }
  const tick = (v: number) => spanMs > 24 * 3600 * 1000
    ? new Date(v).toLocaleString([], { month: 'short', day: 'numeric', hour: '2-digit', minute: '2-digit' })
    : new Date(v).toLocaleTimeString();
  async function activation() {
    if (!series || busy) return;
    const active = result?.visibility !== 'ACTIVE';
    setAction(null);
    if (active && !await confirm({ title: 'Approve this forecast?', description: 'Its forecasts and predicted threshold breaches are then reported as ACTIVE — operator-approved — to every reader, the MCP forecast tools included. Nothing is sent or triggered, and it can be returned to observing at any time.', confirmLabel: 'Approve', tone: 'primary' })) return;
    setBusy(true);
    try {
      await axios.put(`/api/forecasts/${encodeURIComponent(series.seriesId)}/activation`, { active, confirmed: active }, { timeout: 10000 });
      setRevision(v => v + 1);
    } catch (e) {
      const refusal = axios.isAxiosError<ForecastActivationRefusal>(e) ? e.response?.data?.reason : undefined;
      setAction({ source: 'approve', text: typeof refusal === 'string' ? `Approval refused: ${refusal}` : 'Approval unavailable. Retry after the next refresh.' });
    } finally { setBusy(false); }
  }
  async function withdraw() {
    if (!series || busy) return;
    setAction(null);
    if (!await confirm({ title: 'Stop this forecast?', description: 'Withdraws the approval made from the assistant. Collected history and past results are kept until retention removes them.', confirmLabel: 'Stop forecasting', tone: 'danger' })) return;
    setBusy(true);
    try {
      await axios.delete(`/api/forecasts/series/${encodeURIComponent(series.seriesId)}`, { timeout: 10000 });
      setSelected(''); setRevision(v => v + 1);
    } catch (e) {
      const refusal = axios.isAxiosError<ForecastActivationRefusal>(e) ? e.response?.data?.reason : undefined;
      setAction({ source: 'withdraw', text: typeof refusal === 'string' ? `Stop refused: ${refusal}` : 'Stop unavailable. Retry after the next refresh.' });
    } finally { setBusy(false); }
  }
  async function copyResult() {
    if (!result) return;
    if (await copyText(JSON.stringify(result, null, 2))) setCopied(true);
    else setAction({ source: 'copy', text: 'Copy unavailable in this browser. The result is also served by GET /api/forecasts.' });
  }
  /*
   * The order is the reader's (METRICS-FORECAST-UX-AUDIT.md U1): which series and where it stands,
   * the forecast itself, the one decision it asks for, the steps, then everything else folded. The
   * chart sat 500 px into a 914 px panel, under four tiles, three folds and a destructive button,
   * and the approval was last.
   */
  return <section className="card p-5 space-y-4" aria-label="Metrics Forecast">
    <h2 className="text-lg font-semibold">Metrics Forecast</h2>
    {error && <p role="alert">{error}</p>}
    {!status && !error && <p>Loading forecast status…</p>}
    {status && !status.enabled && <p>Forecast pilot disabled by configuration. Enable explorer.forecasting.pilot.enabled to use it.</p>}
    {(status?.enabled || !status && error) && !status?.series.length && <ForecastPreparation onConfigure={onConfigure} />}
    {status?.enabled && status.modelAvailable === false && <p role="note">No forecasting model is configured, so no forecast can be created. Enable explorer.forecasting.inference (TimesFM) to create one; Timestral only scores beside it.</p>}
    {status?.enabled && status.modelAvailable !== false && status.series.length === 0 && <p>Forecast pilot enabled. Configure approved series, PostgreSQL history and TimesFM inference to calculate forecasts.</p>}
    {status?.enabled && status.series.length > 0 && <>
      <div className="flex flex-wrap items-end gap-3">
        <Field label="Series" className="min-w-64">{p => <Select {...p} value={series?.seriesId ?? ''}
          onChange={e => { setSelected(e.target.value); setCopied(false); setAction(null); }}>
          {status.series.map(s => <option key={s.seriesId} value={s.seriesId}>{s.metricId} · {s.environment}</option>)}
        </Select>}</Field>
        {result && <span className="flex flex-wrap gap-1 pb-1.5" aria-label="Forecast status">
          <Badge tone={STATE_TONE[result.state] ?? 'warning'} title={result.state}>{STATE_LABEL[result.state] ?? result.state}</Badge>
          <Badge tone={NEUTRAL_STRATEGIES.has(result.strategy) ? 'neutral' : 'warning'} title={result.strategy}>
            {STRATEGY_LABEL[result.strategy] ?? 'Baseline fallback'}</Badge>
          <Badge tone={result.visibility === 'ACTIVE' ? 'primary' : 'neutral'} title={result.visibility}>
            {VISIBILITY_LABEL[result.visibility] ?? result.visibility}</Badge>
          <Badge>{result.context.outputUnit}</Badge>
        </span>}
      </div>
      {series?.error && <p role="alert">{series.error}</p>}

      {result?.forecast ? <>
        <div className="flex flex-wrap items-end justify-between gap-3">
          <div className="flex flex-wrap gap-2" aria-label="Forecast export">
            <Button onClick={() => exportForecast('csv')}>Export CSV</Button>
            <Button onClick={() => exportForecast('json')}>Export JSON</Button>
          </div>
          <Field label="History shown" className="w-56">{p => <Select {...p} value={shown} onChange={e => setShown(Number(e.target.value))}>
            {[60, 180, 512].map(n => <option key={n} value={n}>Last {n} points · {about(n, series?.stepMillis ?? 60000)}</option>)}
          </Select>}</Field>
        </div>
        <div style={{ height: 260 }} role="img" aria-label="Measured history and forecast">
          <ResponsiveContainer><ComposedChart data={chart}>
            <XAxis dataKey="at" type="number" domain={['dataMin', 'dataMax']} tickFormatter={tick} />
            {/* The axis reaches the threshold: `ifOverflow="extendDomain"` does not widen it in
                recharts 2, so a threshold above every value was drawn outside the plot, unseen. */}
            <YAxis domain={threshold ? [0, (max: number) => Math.ceil(Math.max(max, threshold.threshold) * 1.05)] : [0, 'auto']} />
            {/* The band is drawn as a floor plus a width, so its own tooltip value is the width — shown
                beside the floor it read as an interval narrower than its lower edge. */}
            <Tooltip labelFormatter={v => new Date(Number(v)).toLocaleString()}
              formatter={(value, name, item) => name === '80 % interval' && item?.payload
                ? [`${Number(item.payload.q10).toPrecision(4)} – ${Number(item.payload.q90).toPrecision(4)}`, name]
                : [value, name]} />
            <Legend />
            {/* Two separate children, never a fragment: recharts 2 does not find a graphical item
                inside `<>…</>`, so the band and its legend entry were never drawn
                (METRICS-FORECAST-UX-AUDIT.md B1). */}
            {result.strategy === 'TIMESFM' && <Area dataKey="floor" stackId="interval" stroke="none" fill="transparent" tooltipType="none" legendType="none" isAnimationActive={false} />}
            {result.strategy === 'TIMESFM' && <Area dataKey="band" stackId="interval" stroke="none" fill={FORECAST_COLORS.band} fillOpacity={0.2} name="80 % interval" isAnimationActive={false} />}
            <Line dataKey="actual" stroke={FORECAST_COLORS.observed} dot={false} name="Observed" isAnimationActive={false} />
            {anyImputed && <Line dataKey="imputed" stroke={FORECAST_COLORS.warning} strokeDasharray="2 3" dot={{ r: 2, fill: FORECAST_COLORS.warning, stroke: 'none' }} legendType="circle" name="Imputed (gap filled)" isAnimationActive={false} />}
            <Line dataKey="median" stroke={FORECAST_COLORS.forecast} strokeDasharray="6 3" dot={false} name={result.strategy === 'TIMESFM' ? 'Median forecast' : 'Baseline forecast'} isAnimationActive={false} />
            {threshold && <ReferenceLine y={threshold.threshold} ifOverflow="extendDomain" stroke={FORECAST_COLORS.warning} strokeDasharray="4 4"
              label={{ value: `Threshold ${threshold.direction === 'ABOVE' ? '≥' : '≤'} ${threshold.threshold}`, position: 'insideTopRight', fill: FORECAST_COLORS.warning, fontSize: 11 }} />}
          </ComposedChart></ResponsiveContainer>
        </div>
        {trend && <div role="note" className="rounded-lg border border-outline-variant p-3 space-y-1">
          <p className="font-medium">Forecast insights</p>
          <p>Predicted median is {trend} across {forecastPoints.length} forecast points.
            {forecastChange !== null && ` Net change: ${forecastChange.toPrecision(4)} ${result.context.outputUnit}.`}</p>
          {threshold && <p>{predictedOutside} of {forecastPoints.length} predicted median points
            {predictedOutside === 1 ? ' meets' : ' meet'} the configured threshold.
            This describes the median only, not the probability of a breach.</p>}
        </div>}
        <p className="text-xs text-on-surface-variant"><span>{result.reason}</span> {result.strategy === 'TIMESFM'
          ? 'The 80 % interval is nominal, not a guarantee.' : 'A baseline forecast has no interval.'} No alert is sent.</p>
      </> : <p>{result ? result.reason : series?.error ? null : 'No persisted forecast yet.'}</p>}

      {/* The decision, beside the sentence that says where the series stands (U5: stopping and
          approving were at the two ends of the panel). */}
      {series && <div className="flex flex-wrap items-center gap-3 rounded-lg border border-outline-variant p-3">
        {stage && <p className="text-sm flex-1 min-w-64">{stage.detail.startsWith(stage.label) ? stage.detail
          : <><span className="font-medium">{stage.label}</span> · {stage.detail}</>}</p>}
        {result?.forecast && (result.visibility === 'ACTIVE'
          ? <Button onClick={() => void activation()} disabled={busy}>Return to observing</Button>
          // The timeline's approval step says what still blocks it; the button points at it.
          : <Button onClick={() => void activation()} disabled={busy || !series.activation?.eligible}
            aria-describedby={series.activation?.eligible ? undefined : 'forecast-step-approval'}>Approve after quality checks</Button>)}
        {series.withdrawable && <Button variant="ghost" onClick={() => void withdraw()} disabled={busy}>Stop forecasting this series</Button>}
      </div>}
      {(action?.source === 'approve' || action?.source === 'withdraw') && <p role="alert">{action.text}</p>}

      {series && <ForecastTimeline series={series} />}

      <details onToggle={e => setSummaryOpen(e.currentTarget.open)}><summary>Summary, history and sources</summary>
        {series && <ForecastProgress progress={seriesProgress} error={progressError} result={result} />}
      </details>
      {result && <details><summary>Quality details and baselines</summary>
        {!result.quality ? <p>Realised quality: unmeasured; backtesting needs evaluated observations.</p> : <>
          <div className="grid grid-cols-3 gap-2 my-3" aria-label="Realised backtesting quality">
            <div><p className="text-xs text-on-surface-variant">Evaluated points</p><strong>{result.evaluatedPoints}</strong></div>
            <div><p className="text-xs text-on-surface-variant">Realised MAE</p><strong>{Number.isFinite(quality?.mae) ? quality!.mae.toPrecision(4) : 'Unmeasured'}</strong></div>
            <div><p className="text-xs text-on-surface-variant">80% coverage</p><strong>{Number.isFinite(quality?.q10Q90Coverage) ? (100 * quality!.q10Q90Coverage).toFixed(1) + '%' : 'Unmeasured'}</strong></div>
          </div>
          {comparisons.length > 1 && <div role="img" aria-label="Realised forecast and baseline mean absolute error comparison" className="my-3">
            <p className="text-xs text-on-surface-variant">Realised MAE · lower is better</p>
            <ResponsiveContainer width="100%" height={Math.max(120, comparisons.length * 42)}>
              <BarChart data={comparisons} layout="vertical" margin={{ left: 10, right: 20 }}>
                <XAxis type="number" domain={[0, 'auto']} />
                <YAxis type="category" dataKey="name" width={120} tick={{ fontSize: 11 }} />
                <Tooltip formatter={value => [Number(value).toPrecision(4), 'MAE']} />
                <Bar dataKey="mae" fill={FORECAST_COLORS.forecast} name="Realised MAE" />
              </BarChart>
            </ResponsiveContainer>
          </div>}
          <p className="text-xs text-on-surface-variant">Historical evaluation, not a guarantee of future accuracy. Compare baselines only when evaluated on equivalent windows.</p>
          <p>MASE {result.quality.mase?.toPrecision(4) ?? 'unmeasured'} · interval coverage {(100 * result.quality.q10Q90Coverage).toFixed(1)}% · interval width {result.quality.meanIntervalWidth.toPrecision(4)} · pinball loss {result.quality.meanPinballLoss.toPrecision(4)}</p>
          <Table><TableHead><TableRow><Th>Baseline</Th><Th>Realised MAE</Th></TableRow></TableHead><TableBody>
            {Object.entries(result.baselineMae).map(([name, mae]) => <TableRow key={name}><Td>{name}</Td><Td>{mae.toPrecision(4)}</Td></TableRow>)}
          </TableBody></Table>
        </>}
        <Button variant="ghost" onClick={() => void copyResult()}>{copied ? 'Raw result copied' : 'Copy raw result (JSON)'}</Button>
        {action?.source === 'copy' && <p role="alert">{action.text}</p>}
      </details>}
      <ForecastShadowComparison view={shadow?.seriesId === series?.seriesId ? shadow?.view ?? null : null} />
      {series && <details><summary>Forecast configuration summary</summary>
        <dl className="grid min-w-0 grid-cols-1 sm:grid-cols-2 gap-2 text-sm my-3 [&>div]:min-w-0">
          <div><dt>Metric</dt><dd className="font-mono break-all">{series.metricId}</dd></div>
          <div><dt>Environment</dt><dd>{series.environment}</dd></div>
          <div><dt>Sampling interval</dt><dd>{about(1, series.stepMillis)}</dd></div>
          <div><dt>Minimum history</dt><dd>{series.minimumContextPoints} points</dd></div>
          <div><dt>Threshold monitoring</dt><dd>{series.thresholdConfigured ? 'Configured' : 'Not configured'}</dd></div>
          <div><dt>Approval</dt><dd>{result?.visibility === 'ACTIVE' ? 'Active' : 'Not approved'}</dd></div>
          {result?.forecast && <div><dt>Model</dt><dd className="min-w-0 break-words [overflow-wrap:anywhere]">{result.forecast.modelId} · {result.forecast.modelRevision}</dd></div>}
          {result?.forecast && <div><dt>Horizon</dt><dd>{about(result.forecast.points.length, series.stepMillis)}</dd></div>}
        </dl>
        <p className="text-xs text-on-surface-variant">Read-only effective configuration. Edit sources through the preparation assistant.</p>
      </details>}
      <details onToggle={e => setPreparationOpen(e.currentTarget.open)}><summary>Preparation and configuration</summary>
        {preparationOpen && <ForecastPreparation onConfigure={onConfigure} />}
      </details>
    </>}
  </section>;
}
