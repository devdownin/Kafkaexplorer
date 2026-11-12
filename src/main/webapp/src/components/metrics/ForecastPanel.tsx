// SPDX-License-Identifier: AGPL-3.0-or-later
import { useEffect, useState } from 'react';
import axios from 'axios';
import { Area, ComposedChart, Legend, Line, ReferenceLine, ResponsiveContainer, Tooltip, XAxis, YAxis } from 'recharts';
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
import { forecastStage } from './forecastStage';

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

export function ForecastPanel({ onConfigure, onStatus, refresh = 0 }: {
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
  const [selected, setSelected] = useState('');
  const [error, setError] = useState('');
  // A refusal belongs to the button that was clicked and must outlive the next poll: sharing
  // `error` with the poll made it vanish within 30 s, and a failing poll overwrite it.
  const [action, setAction] = useState<{ source: 'approve' | 'withdraw' | 'copy'; text: string } | null>(null);
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
  useEffect(() => {
    if (!seriesId) return;
    // A series that answered 404 has no shadow configured: asking again every tick answers the same.
    let noShadow = false;
    const path = `/api/forecasts/${encodeURIComponent(seriesId)}`;
    return poll(async signal => {
      try {
        const { data } = await axios.get<ForecastSeriesView>(path, { signal, timeout: 10000 });
        // Anything else keeps the list's summary on screen rather than a blank panel.
        if (data?.seriesId === seriesId && data.activation && !signal.aborted) setDetail(data);
      } catch { /* the summary from the list stays */ }
      try {
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
  }, [seriesId, revision, refresh]);
  const listed = status?.series.find(s => s.seriesId === seriesId);
  // The detail is the same row with its history: preferred once it is about the series shown.
  const series = listed && detail?.seriesId === listed.seriesId ? detail : listed;
  const result = series?.result;
  const seriesProgress = progress?.seriesId === series?.seriesId ? progress : null;
  const chart = [
    // An imputed point is a filled gap, not a measurement: drawn apart, never in "Observed" (B4).
    ...(result?.context.points.slice(-60).map(p => p.imputed ? { at: p.endAt, imputed: p.value } : { at: p.endAt, actual: p.value }) ?? []),
    ...(result?.forecast?.points.map(p => ({ at: p.at, median: p.q50, floor: p.q10, band: p.q90 - p.q10, q10: p.q10, q90: p.q90 })) ?? []),
  ];
  // Fifteen-minute steps over sixty history and sixty forecast points cover a day and more: a tick
  // that shows only the time then repeats across midnight.
  const anyImputed = chart.some(p => 'imputed' in p && p.imputed != null);
  // B5: the threshold the breach is judged against, on the chart it is judged on.
  const threshold = seriesProgress?.breach?.resultKey === result?.key ? seriesProgress?.breach?.threshold ?? null : null;
  const stage = series ? forecastStage(series) : null;
  const spanMs = chart.length > 1 ? chart[chart.length - 1].at - chart[0].at : 0;
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
    {status?.enabled && status.series.length === 0 && <p>Forecast pilot enabled. Configure approved series, PostgreSQL history and TimesFM inference to calculate forecasts.</p>}
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
            {result.strategy === 'TIMESFM' && <Area dataKey="band" stackId="interval" stroke="none" fill="#64748b" fillOpacity={0.2} name="80 % interval" isAnimationActive={false} />}
            <Line dataKey="actual" stroke="#16a34a" dot={false} name="Observed" isAnimationActive={false} />
            {anyImputed && <Line dataKey="imputed" stroke="#d97706" strokeDasharray="2 3" dot={{ r: 2, fill: '#d97706', stroke: 'none' }} legendType="circle" name="Imputed (gap filled)" isAnimationActive={false} />}
            <Line dataKey="median" stroke="#6366f1" strokeDasharray="6 3" dot={false} name={result.strategy === 'TIMESFM' ? 'Median forecast' : 'Baseline forecast'} isAnimationActive={false} />
            {threshold && <ReferenceLine y={threshold.threshold} ifOverflow="extendDomain" stroke="#d97706" strokeDasharray="4 4"
              label={{ value: `Threshold ${threshold.direction === 'ABOVE' ? '≥' : '≤'} ${threshold.threshold}`, position: 'insideTopRight', fill: '#d97706', fontSize: 11 }} />}
          </ComposedChart></ResponsiveContainer>
        </div>
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

      <details><summary>Summary, history and sources</summary>
        {series && <ForecastProgress progress={seriesProgress} error={progressError} result={result} />}
      </details>
      {result && <details><summary>Quality details and baselines</summary>
        {!result.quality ? <p>Realised quality: unmeasured.</p> : <>
          <p>MASE {result.quality.mase?.toPrecision(4) ?? 'unmeasured'} · interval coverage {(100 * result.quality.q10Q90Coverage).toFixed(1)}% · interval width {result.quality.meanIntervalWidth.toPrecision(4)} · pinball loss {result.quality.meanPinballLoss.toPrecision(4)}</p>
          <Table><TableHead><TableRow><Th>Baseline</Th><Th>Realised MAE</Th></TableRow></TableHead><TableBody>
            {Object.entries(result.baselineMae).map(([name, mae]) => <TableRow key={name}><Td>{name}</Td><Td>{mae.toPrecision(4)}</Td></TableRow>)}
          </TableBody></Table>
        </>}
        <Button variant="ghost" onClick={() => void copyResult()}>{copied ? 'Raw result copied' : 'Copy raw result (JSON)'}</Button>
        {action?.source === 'copy' && <p role="alert">{action.text}</p>}
      </details>}
      <ForecastShadowComparison view={shadow?.seriesId === series?.seriesId ? shadow?.view ?? null : null} />
      <details onToggle={e => setPreparationOpen(e.currentTarget.open)}><summary>Preparation and configuration</summary>
        {preparationOpen && <ForecastPreparation onConfigure={onConfigure} />}
      </details>
    </>}
  </section>;
}
