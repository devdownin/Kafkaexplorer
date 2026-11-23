// SPDX-License-Identifier: AGPL-3.0-or-later
import { useEffect, useRef, useState } from 'react';
import axios from 'axios';
import { Area, Bar, BarChart, CartesianGrid, ComposedChart, Legend, Line, ReferenceArea, ReferenceLine, ResponsiveContainer, Tooltip, XAxis, YAxis } from 'recharts';
import { Badge, Button, Checkbox, Field, Input, Select, Table, TableBody, TableHead, TableRow, Td, Th, useConfirm } from '../ui';
import { copyText } from '../../clipboard';
import type { BadgeTone } from '../ui';
import type {
  ForecastActivationRefusal, ForecastProgress as Progress, ForecastSeriesView, ForecastShadowComparison as Comparison,
  ForecastStatus, MetricForecast,
} from '../../api/types';
import { compareWithActual } from './forecastVsActual';
import { ForecastPreparation } from './ForecastPreparation';
import { ForecastProgress } from './ForecastProgress';
import { ForecastShadowComparison } from './ForecastShadowComparison';
import { isShadowComparison, type ShadowView } from './shadowModels';
import { ForecastTimeline } from './ForecastTimeline';
import { about, forecastStage } from './forecastStage';
import { FORECAST_COLORS, THRESHOLD_COLORS } from './forecastColors';
import { placeThresholds } from './thresholdPlacement';
import { predictedPoints, whatIfReach } from './forecastBreach';
import { forecastCsv } from './forecastCsv';

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
function poll(run: (signal: AbortSignal) => Promise<void>, ownClock = true): () => void {
  const controller = new AbortController();
  // Driven by the page's clock: one read per tick it hands down, no timer and no listener here —
  // the page's own polling already pauses behind a hidden tab and reads again on return.
  if (!ownClock) {
    void run(controller.signal);
    return () => controller.abort();
  }
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

export function ForecastPanel({ onStatus, refresh = 0, clock, focus = '', metricNames = {}, metricThresholds = {} }: {
  /**
   * The series to show: the one a card's Details asked for, or the one just started. The card is
   * where a series is chosen — every forecast belongs to a metric card — so the panel has no
   * selector of its own: it repeated the list of cards, in metric ids instead of names.
   */
  focus?: string;
  /** Metric names by id, so the panel says which metric it shows the way the card does. */
  metricNames?: Record<string, string>;
  /** Each metric's own warning and critical thresholds, drawn on its chart as the card draws them. */
  metricThresholds?: Record<string, { warning: number | null; critical: number | null }>;
  /** Receives every status the panel polls, so the page can show it on the cards without a second poll. */
  onStatus?: (status: ForecastStatus | null) => void;
  /** Bumped by the page after an approval, to poll at once rather than at the next tick. */
  refresh?: number;
  /**
   * The page's clock. Given, the panel reads once per tick instead of running its own timer: the
   * metric list and the forecasts were two loops (15 s and 30 s) that never met, and now share one.
   */
  clock?: number;
}) {
  const [tableOpen, setTableOpen] = useState(false);
  const [status, setStatus] = useState<ForecastStatus | null>(null);
  const [progress, setProgress] = useState<Progress | null>(null);
  const [progressError, setProgressError] = useState('');
  const [shadow, setShadow] = useState<{ seriesId: string; view: ShadowView } | null>(null);
  const [selected, setSelected] = useState(focus);
  const [seenFocus, setSeenFocus] = useState(focus);
  const [error, setError] = useState('');
  // A refusal belongs to the button that was clicked and must outlive the next poll: sharing
  // `error` with the poll made it vanish within 30 s, and a failing poll overwrite it.
  const [action, setAction] = useState<{ source: 'approve' | 'withdraw' | 'copy'; text: string } | null>(null);
  /** How much of the 512-point context the chart draws: a forecast is judged against more than the last hour. */
  const [shown, setShown] = useState(60);
  /*
   * The latest fully measured forecast, drawn over what happened: read only once asked for, and
   * once per latest result — a past forecast does not change until a newer one has matured.
   */
  const [compare, setCompare] = useState(false);
  const [previousRead, setPreviousRead] = useState<{ key: string; forecast: MetricForecast | null; failed: boolean } | null>(null);
  /** P2: `/progress` is read only where its answer is on screen — the threshold on the chart, or the open summary. */
  const [summaryOpen, setSummaryOpen] = useState(false);
  const [busy, setBusy] = useState(false);
  const [copied, setCopied] = useState(false);
  // Followed during render rather than in an effect: it takes hold once the list carries the series.
  const [revision, setRevision] = useState(0);
  /*
   * Whether the panel is on screen. It sits at the bottom of the page and read the shown series'
   * detail, progress and shadow on every tick for nobody; off screen, those reads wait and the
   * first one happens as it scrolls into view. The list itself is always read — the cards need it.
   * Without IntersectionObserver (an old browser, the test DOM) the panel counts as on screen.
   */
  const sectionRef = useRef<HTMLElement | null>(null);
  const [onScreen, setOnScreen] = useState(true);
  useEffect(() => {
    const element = sectionRef.current;
    if (!element || typeof IntersectionObserver === 'undefined') return;
    const observer = new IntersectionObserver(entries => setOnScreen(entries.some(e => e.isIntersecting)),
      { rootMargin: '200px' });
    observer.observe(element);
    return () => observer.disconnect();
  }, []);
  // A threshold the operator is only trying out: drawn, and read like the metric's own.
  const [whatIf, setWhatIf] = useState('');
  const [whatIfBelow, setWhatIfBelow] = useState<boolean | null>(null);
  // Reset per-series experimentation when the card focuses another forecast.
  if (focus !== seenFocus) { setSeenFocus(focus); if (focus) {
    setSelected(focus); setCopied(false); setAction(null);
    setWhatIf(''); setWhatIfBelow(null); setCompare(false);
  } }
  /** P4: the preparation checks are read when their section is opened, not on every visit. */
  const [preparationOpen, setPreparationOpen] = useState(false);
  const [detail, setDetail] = useState<ForecastSeriesView | null>(null);
  const [detailError, setDetailError] = useState<{ seriesId: string; resultKey: string | null } | null>(null);
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
  }, clock === undefined), [revision, refresh, clock]);
  const seriesId = status?.series.some(s => s.seriesId === selected) ? selected : status?.series[0]?.seriesId;
  // The list says whether a shadow exists at all: asking each series for one answered a 404 that
  // the browser wrote into the console of every Metrics page (PAGES-SIMPLICITY-AUDIT.md X1).
  const shadowConfigured = status?.shadowEnabled !== false;
  const wantProgress = summaryOpen || Boolean(status?.series.find(s => s.seriesId === seriesId)?.thresholdConfigured);
  const latestKey = status?.series.find(s => s.seriesId === seriesId)?.result?.key;
  useEffect(() => {
    if (!seriesId || !onScreen) return;
    // A series that answered 404 has no shadow configured: asking again every tick answers the same.
    let noShadow = !shadowConfigured;
    const path = `/api/forecasts/${encodeURIComponent(seriesId)}`;
    return poll(async signal => {
      try {
        const { data } = await axios.get<ForecastSeriesView>(path, { signal, timeout: 10000 });
        // Anything else keeps the list's summary on screen rather than a blank panel.
        if (data?.seriesId === seriesId && data.activation && !signal.aborted) {
          setDetail(data);
          setDetailError(null);
        }
      } catch {
        if (!signal.aborted) setDetailError({ seriesId, resultKey: latestKey ?? null });
      }
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
    }, clock === undefined);
  }, [seriesId, revision, refresh, clock, shadowConfigured, wantProgress, onScreen, latestKey]);
  const previousKey = `${seriesId}|${latestKey}`;
  useEffect(() => {
    if (!compare || !seriesId || !latestKey) return;
    const controller = new AbortController();
    axios.get<MetricForecast | ''>(`/api/forecasts/${encodeURIComponent(seriesId)}/previous`,
      { signal: controller.signal, timeout: 10000, validateStatus: code => code === 200 || code === 204 })
      .then(response => {
        if (controller.signal.aborted) return;
        const forecast = response.status === 200 && response.data && Array.isArray(response.data.points) ? response.data : null;
        setPreviousRead({ key: `${seriesId}|${latestKey}`, forecast, failed: false });
      })
      .catch(() => { if (!controller.signal.aborted) setPreviousRead({ key: `${seriesId}|${latestKey}`, forecast: null, failed: true }); });
    return () => controller.abort();
  }, [compare, seriesId, latestKey]);
  const listed = status?.series.find(s => s.seriesId === seriesId);
  // A same-series detail can still be a different result version. Never present an old
  // curve under a newer status: wait for a matching detail, and show a read failure.
  const currentDetail = listed && detail?.seriesId === listed.seriesId
    && detail.result?.key === listed.result?.key ? detail : null;
  const series = currentDetail ?? listed;
  // The summary is current by definition: it can show predicted points while the
  // matching full history is loading, but an earlier detail must never take precedence.
  const result = series?.result;
  const displayedStatus = listed?.result ?? result;
  const currentDetailError = detailError !== null && detailError.seriesId === seriesId
    && detailError.resultKey === (latestKey ?? null);
  // Only the windows the context can fill (METRICS-FORECAST-AUDIT-2.md U1): "Last 512 points" over a
  // series still collecting its first 130 drew 130 and said 512.
  const windows = [60, 180, 512].filter(n => n === 60 || n <= (result?.context.points.length ?? 0));
  const seriesProgress = progress?.seriesId === series?.seriesId ? progress : null;
  const previousShown = compare && previousRead?.key === previousKey ? previousRead : null;
  const previous = previousShown?.forecast ?? null;
  const previousAt = new Map((previous?.points ?? []).map(p => [p.at, p]));
  const versus = previous && result ? compareWithActual(previous, result.context.points) : null;
  const history: { at: number; actual?: number | null; imputed?: number | null; median?: number | null;
    prevMedian?: number; prevFloor?: number; prevBand?: number; prevQ10?: number; prevQ90?: number }[] =
    // An imputed point is a filled gap, not a measurement: drawn apart, never in "Observed" (B4).
    result?.context.points.slice(-(windows.includes(shown) ? shown : 60)).map(p => {
      const row = p.imputed ? { at: p.endAt, imputed: p.value } : { at: p.endAt, actual: p.value };
      const past = previousAt.get(p.endAt);
      return past ? { ...row, prevMedian: past.q50, prevFloor: past.q10, prevBand: past.q90 - past.q10, prevQ10: past.q10, prevQ90: past.q90 } : row;
    }) ?? [];
  /*
   * The median starts on the last measured point, so the two strokes meet: they were drawn with a
   * gap of one step between them, which read as a missing reading at the very moment that matters.
   */
  const lastMeasured = [...history].reverse().find(p => typeof p.actual === 'number');
  const chart = [
    ...history.map(p => p === lastMeasured && result?.forecast?.points.length ? { ...p, median: p.actual } : p),
    ...(result?.forecast?.points.map(p => ({ at: p.at, median: p.q50, floor: p.q10, band: p.q90 - p.q10, q10: p.q10, q90: p.q90 })) ?? []),
  ];
  const forecastStart = lastMeasured?.at ?? result?.forecast?.historyEndAt;
  // Fifteen-minute steps over sixty history and sixty forecast points cover a day and more: a tick
  // that shows only the time then repeats across midnight.
  const anyImputed = chart.some(p => 'imputed' in p && p.imputed != null);
  // B5: the threshold the breach is judged against, on the chart it is judged on.
  const threshold = seriesProgress?.breach?.resultKey === result?.key ? seriesProgress?.breach?.threshold ?? null : null;
  const stage = series ? forecastStage(series) : null;
  const spanMs = chart.length > 1 ? chart[chart.length - 1].at - chart[0].at : 0;
  /*
   * The value axis fits the data and the threshold, padded by a tenth of their range. It started at
   * zero, so a latency moving between 1 900 and 2 300 under a threshold at 3 800 was a flat line in
   * the middle of the plot, and the 80 % band a sliver nobody could read.
   */
  const plotted = chart.flatMap(p => [('actual' in p ? p.actual : null), ('imputed' in p ? p.imputed : null),
    ('median' in p ? p.median : null), ('q10' in p ? p.q10 : null), ('q90' in p ? p.q90 : null),
    ('prevQ10' in p ? p.prevQ10 : null), ('prevQ90' in p ? p.prevQ90 : null)])
    .filter((v): v is number => typeof v === 'number' && Number.isFinite(v));
  if (threshold) plotted.push(threshold.threshold);
  // The metric's own thresholds, by the rule the card uses (thresholdPlacement.ts). They are on the
  // counter's reading, so a rate forecast is never compared with them and they are not drawn.
  const own = series ? metricThresholds[series.metricId] : undefined;
  const ownRate = result?.context?.profile?.transformation === 'COUNTER_RATE';
  const placed = placeThresholds(plotted.length ? Math.min(...plotted) : 0, plotted.length ? Math.max(...plotted) : 1,
    own && !ownRate ? [{ label: 'warning', value: own.warning, color: THRESHOLD_COLORS.warn },
      { label: 'critical', value: own.critical, color: THRESHOLD_COLORS.crit }] : []);
  for (const l of placed.lines) plotted.push(l.value);
  const whatIfValue = whatIf.trim() === '' ? Number.NaN : Number(whatIf);
  // The direction the metric's own thresholds imply (critical below warning reads downwards), until chosen.
  const below = whatIfBelow ?? Boolean(own && own.warning !== null && own.critical !== null && own.critical < own.warning);
  const tried = Number.isFinite(whatIfValue) && result
    ? whatIfReach(predictedPoints(result.forecast), whatIfValue, below, result.strategy === 'TIMESFM') : null;
  if (Number.isFinite(whatIfValue)) plotted.push(whatIfValue);
  const low = plotted.length ? Math.min(...plotted) : 0;
  const high = plotted.length ? Math.max(...plotted) : 1;
  const pad = (high - low) * 0.1 || Math.abs(high) * 0.1 || 1;
  // Bounds on a round step, so the ticks read 1,600 · 2,400 rather than 1,628.6 · 2,828.6; a
  // non-negative series is not padded below zero.
  const step = 10 ** Math.floor(Math.log10((high - low + 2 * pad) / 4));
  const yDomain: [number, number] = [Math.max(low >= 0 ? 0 : -Infinity, Math.floor((low - pad) / step) * step),
    Math.ceil((high + pad) / step) * step];
  const number = (v: unknown) => typeof v === 'number' ? v.toLocaleString(undefined, { maximumFractionDigits: 2 }) : String(v);
  const observedCount = chart.filter(p => 'actual' in p && typeof p.actual === 'number').length;
  const predictedRows = chart.filter(p => !('actual' in p) && !('imputed' in p) && typeof p.median === 'number');
  const chartSummary = `Measured history and forecast: ${observedCount} observed points`
    + (predictedRows.length ? `, ${predictedRows.length} predicted, from ${number(predictedRows[0].median)} to ${number(predictedRows[predictedRows.length - 1].median)}` : '')
    + '. The same points are in the table below.';
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
    const content = format === 'json'
      ? JSON.stringify({ seriesId: series.seriesId, metricId: series.metricId,
          unit: result.context.outputUnit, strategy: result.strategy, points: rows }, null, 2)
      : forecastCsv(rows);
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
  return <section ref={sectionRef} className="kse-surface kse-forecast-panel p-4 md:p-5 space-y-4" aria-label="Metrics Forecast">
    <h2 className="text-lg font-semibold text-on-surface tracking-tight">Metrics Forecast</h2>
    {error && <p role="alert">{error}</p>}
    {/* The panel in its own shape — a status line, then the chart's height — rather than a sentence. */}
    {!status && !error && <div role="status" aria-label="Loading forecast status" className="space-y-3">
      <div aria-hidden="true" className="skeleton-shimmer h-4 w-2/5" />
      <div aria-hidden="true" className="skeleton-shimmer h-[260px] w-full rounded-lg" />
    </div>}
    {status && !status.enabled && <p>Forecast pilot disabled by configuration. Enable explorer.forecasting.pilot.enabled to use it.</p>}
    {(status?.enabled || !status && error) && !status?.series.length && <ForecastPreparation />}
    {status?.enabled && status.modelAvailable === false && <p role="note">No forecasting model is configured, so no forecast can be created. Enable explorer.forecasting.inference (TimesFM) to create one; Timestral only scores beside it.</p>}
    {status?.enabled && status.modelAvailable !== false && status.series.length === 0 && <p>Forecast pilot enabled. Configure approved series, PostgreSQL history and TimesFM inference to calculate forecasts.</p>}
    {status?.enabled && status.series.length > 0 && <>
      <div className="flex flex-wrap items-end gap-3">
        {status.series.length > 1 && <Field label="Forecast series" className="w-full sm:w-80">
          {p => <Select {...p} value={seriesId ?? ''} onChange={e => {
            setSelected(e.target.value);
            setWhatIf(''); setWhatIfBelow(null); setCompare(false); setAction(null);
          }}>
            {status.series.map(item => <option key={item.seriesId} value={item.seriesId}>
              {metricNames[item.metricId] ?? item.metricId} · {item.environment} · {item.seriesId.slice(-8)}
            </option>)}
          </Select>}
        </Field>}
        {series && <div className="min-w-64 pb-1">
          <h3 className="font-semibold font-mono break-all" aria-label="Forecast series">{metricNames[series.metricId] ?? series.metricId}</h3>
          <p className="text-xs text-on-surface-variant">{series.environment}{status.series.length > 1
            ? ` · ${status.series.length} forecasts — Details on a metric card shows its own` : ''}</p>
        </div>}
        {displayedStatus && <span className="flex flex-wrap gap-1 pb-1.5" aria-label="Forecast status">
          <Badge tone={STATE_TONE[displayedStatus.state] ?? 'warning'} title={displayedStatus.state}>{STATE_LABEL[displayedStatus.state] ?? displayedStatus.state}</Badge>
          <Badge tone={NEUTRAL_STRATEGIES.has(displayedStatus.strategy) ? 'neutral' : 'warning'} title={displayedStatus.strategy}>
            {STRATEGY_LABEL[displayedStatus.strategy] ?? 'Baseline fallback'}</Badge>
          <Badge tone={displayedStatus.visibility === 'ACTIVE' ? 'primary' : 'neutral'} title={displayedStatus.visibility}>
            {VISIBILITY_LABEL[displayedStatus.visibility] ?? displayedStatus.visibility}</Badge>
          <Badge>{displayedStatus.context.outputUnit}</Badge>
        </span>}
      </div>
      {series?.error && <p role="alert">{series.error}</p>}

      {listed?.result?.forecast && !currentDetail && <p role={currentDetailError ? 'alert' : 'status'}
        className="text-sm text-on-surface-variant">
        {currentDetailError
          ? 'Full history for the latest forecast is unavailable. Only its current summary is shown; older detailed data is hidden. Retry on the next refresh.'
          : 'Loading the full history for this forecast; its current summary is shown below.'}
      </p>}
      {result?.forecast ? <>
        <div className="flex flex-wrap items-end justify-between gap-3">
          <div className="flex flex-wrap gap-2" aria-label="Forecast export">
            <Button variant="outline" size="sm" icon="download" onClick={() => exportForecast('csv')}>Export CSV</Button>
            <Button variant="outline" size="sm" icon="download" onClick={() => exportForecast('json')}>Export JSON</Button>
          </div>
          <label className="flex items-center gap-2 text-sm pb-2" title="The latest forecast whose whole horizon has been measured, drawn over what happened">
            <Checkbox checked={compare} onChange={setCompare} />Previous forecast vs actual</label>
          <Field label="History shown" className="w-56">{p => <Select {...p} value={windows.includes(shown) ? shown : 60} onChange={e => setShown(Number(e.target.value))}>
            {windows.map(n => <option key={n} value={n}>Last {n} points · {about(n, series?.stepMillis ?? 60000)}</option>)}
          </Select>}</Field>
        </div>
        <div className="kse-chart-viewport" style={{ height: 278 }} role="img" aria-label={chartSummary}>
          <ResponsiveContainer><ComposedChart data={chart}>
            <CartesianGrid stroke={FORECAST_COLORS.band} strokeOpacity={0.12} vertical={false} />
            <XAxis dataKey="at" type="number" domain={['dataMin', 'dataMax']} tickFormatter={tick}
              tick={{ fill: FORECAST_COLORS.band, fontSize: 11 }} stroke={FORECAST_COLORS.band} strokeOpacity={0.4} minTickGap={24} />
            {/* The axis reaches the threshold: `ifOverflow="extendDomain"` does not widen it in
                recharts 2, so a threshold above every value was drawn outside the plot, unseen. */}
            <YAxis domain={yDomain} allowDataOverflow tickFormatter={v => number(v)} width={56}
              tick={{ fill: FORECAST_COLORS.band, fontSize: 11 }} stroke={FORECAST_COLORS.band} strokeOpacity={0.4} />
            {/* What lies ahead is tinted, and "now" marks where the measurements stop. */}
            {forecastStart !== undefined && <ReferenceArea x1={forecastStart} x2={chart[chart.length - 1].at}
              fill={FORECAST_COLORS.forecast} fillOpacity={0.05} strokeOpacity={0} ifOverflow="hidden" />}
            {forecastStart !== undefined && <ReferenceLine x={forecastStart} stroke={FORECAST_COLORS.band} strokeDasharray="3 3"
              label={{ value: 'Forecast begins', position: 'insideTopLeft', fill: 'var(--viz-axis)', fontSize: 11 }} />}
            {/* The band is drawn as a floor plus a width, so its own tooltip value is the width — shown
                beside the floor it read as an interval narrower than its lower edge. The tooltip takes
                the page's surface: recharts' default is white, which put a near-white date on white. */}
            <Tooltip labelFormatter={v => new Date(Number(v)).toLocaleString()}
              contentStyle={{ background: 'var(--viz-tooltip-bg)', border: '1px solid var(--viz-tooltip-border)', color: 'var(--viz-tooltip-text)', borderRadius: 8, fontSize: 12 }}
              labelStyle={{ color: 'var(--viz-axis)', marginBottom: 4 }} itemStyle={{ padding: 0 }}
              cursor={{ stroke: 'var(--viz-axis)', strokeOpacity: 0.5, strokeDasharray: '3 3' }}
              formatter={(value, name, item) => name === '80 % interval' && item?.payload
                ? [`${number(item.payload.q10)} – ${number(item.payload.q90)}`, name]
                : name === 'Previous 80 % interval' && item?.payload
                  ? [`${number(item.payload.prevQ10)} – ${number(item.payload.prevQ90)}`, name]
                  : [number(value), name]} />
            <Legend wrapperStyle={{ fontSize: 12, paddingTop: 8, color: 'var(--viz-axis)' }} />
            {/* Two separate children, never a fragment: recharts 2 does not find a graphical item
                inside `<>…</>`, so the band and its legend entry were never drawn
                (METRICS-FORECAST-UX-AUDIT.md B1). */}
            {result.strategy === 'TIMESFM' && <Area dataKey="floor" stackId="interval" stroke="none" fill="transparent" tooltipType="none" legendType="none" isAnimationActive={false} />}
            {result.strategy === 'TIMESFM' && <Area dataKey="band" stackId="interval" stroke="none" fill={FORECAST_COLORS.forecast} fillOpacity={0.14} name="80 % interval" isAnimationActive={false} />}
            {previous && <Area dataKey="prevFloor" stackId="previous" stroke="none" fill="transparent" tooltipType="none" legendType="none" isAnimationActive={false} />}
            {previous && <Area dataKey="prevBand" stackId="previous" stroke="none" fill={FORECAST_COLORS.previous} fillOpacity={0.22} name="Previous 80 % interval" isAnimationActive={false} />}
            {previous && <Line dataKey="prevMedian" stroke={FORECAST_COLORS.previous} strokeWidth={2} strokeDasharray="2 3" dot={false} name="Previous forecast" isAnimationActive={false} />}
            <Line dataKey="actual" stroke={FORECAST_COLORS.observed} strokeWidth={2} dot={false} name="Observed" isAnimationActive={false} />
            {anyImputed && <Line dataKey="imputed" stroke={FORECAST_COLORS.warning} strokeDasharray="2 3" dot={{ r: 2, fill: FORECAST_COLORS.warning, stroke: 'none' }} legendType="circle" name="Imputed (gap filled)" isAnimationActive={false} />}
            <Line dataKey="median" stroke={FORECAST_COLORS.forecast} strokeWidth={2} strokeDasharray="6 3" dot={false} name={result.strategy === 'TIMESFM' ? 'Median forecast' : 'Baseline forecast'} isAnimationActive={false} />
            {placed.lines.map(l => <ReferenceLine key={l.label} y={l.value} stroke={l.color} strokeDasharray="4 4"
              label={{ value: `${l.label} ${number(l.value)}`, position: 'insideBottomRight', fill: l.color, fontSize: 11 }} />)}
            {Number.isFinite(whatIfValue) && <ReferenceLine y={whatIfValue} stroke={FORECAST_COLORS.forecast} strokeDasharray="2 2"
              label={{ value: `what if ${number(whatIfValue)}`, position: 'insideTopLeft', fill: FORECAST_COLORS.forecast, fontSize: 11 }} />}
            {threshold && <ReferenceLine y={threshold.threshold} ifOverflow="extendDomain" stroke={FORECAST_COLORS.warning} strokeDasharray="4 4"
              label={{ value: `Threshold ${threshold.direction === 'ABOVE' ? '≥' : '≤'} ${threshold.threshold}`, position: 'insideTopRight', fill: FORECAST_COLORS.warning, fontSize: 11 }} />}
          </ComposedChart></ResponsiveContainer>
        </div>
        {/* The same points as the chart, for a screen reader and for a keyboard: the numbers are
            otherwise reachable only by hovering. Rendered only when opened — sixty-odd rows per panel. */}
        <details className="text-sm" onToggle={e => setTableOpen((e.currentTarget as HTMLDetailsElement).open)}>
          <summary className="cursor-pointer text-outline">Chart data as a table</summary>
          {tableOpen && <Table rowCount={chart.length} maxBodyHeight="16rem">
            <caption className="sr-only">Measured values, predicted median and 80 % band, one row per point</caption>
            <TableHead><TableRow><Th>Time</Th><Th>Kind</Th><Th>Value</Th><Th>80 % band</Th></TableRow></TableHead>
            <TableBody>{chart.map(p => {
              const measured = 'actual' in p && typeof p.actual === 'number' ? p.actual : null;
              const imputed = 'imputed' in p && typeof p.imputed === 'number' ? p.imputed : null;
              const predicted = !('actual' in p) && !('imputed' in p) && typeof p.median === 'number';
              const value = measured ?? imputed ?? (predicted ? p.median : null);
              return <TableRow key={p.at}>
                <Td>{tick(p.at)}</Td>
                <Td>{imputed !== null ? 'Imputed' : predicted ? 'Predicted' : 'Observed'}</Td>
                <Td>{value === null || value === undefined ? '—' : number(value)}</Td>
                <Td>{predicted && 'q10' in p && typeof p.q10 === 'number' && 'q90' in p && typeof p.q90 === 'number' ? `${number(p.q10)} – ${number(p.q90)}` : '—'}</Td>
              </TableRow>;
            })}</TableBody>
          </Table>}
        </details>
        {placed.offScale.length > 0 && <p className="text-xs font-mono" aria-label="Thresholds off the chart scale">
          {placed.offScale.map(t => <span key={t.label} className="mr-3" style={{ color: t.color }}>{t.up ? '↑' : '↓'} {t.label} {number(t.value)} <span className="text-outline">off scale</span></span>)}</p>}
        {/* Try a threshold before setting it: when this forecast would reach it, read as the card reads
            the metric's own. Nothing is saved — to keep it, edit the metric. */}
        <div className="flex flex-wrap items-end gap-2 text-sm" aria-label="Try a threshold">
          <Field label="Try a threshold" className="w-40">{p => <Input {...p} type="number" inputMode="decimal" value={whatIf}
            onChange={e => setWhatIf(e.target.value)} placeholder={result.context.outputUnit || 'value'} />}</Field>
          <Select className="w-auto" aria-label="Direction of the tried threshold" value={below ? 'below' : 'above'}
            onChange={e => setWhatIfBelow(e.target.value === 'below')}>
            <option value="above">reached upwards (≥)</option>
            <option value="below">reached downwards (≤)</option>
          </Select>
          {Number.isFinite(whatIfValue) && <p role="status" className="pb-2">
            {tried
              ? `Reached ${tried.likelihood === 'expected' ? 'on the median' : 'on the edge of the 80 % band'} in ${about(tried.steps, series!.stepMillis)}.`
              : 'Not reached within the forecast horizon.'}
            <span className="text-on-surface-variant"> Nothing is saved: to keep it, edit the metric.</span></p>}
        </div>
        {compare && <p role="status" aria-label="Previous forecast against what happened" className="text-sm">
          {!previousShown ? 'Reading the previous forecast…'
            : previousShown.failed ? 'The previous forecast could not be read. Retry after the next refresh.'
            : !previous || !versus ? 'No forecast has been fully measured yet: the first comparison is available one horizon after the first forecast.'
            : versus.compared === 0 ? 'The previous forecast has no measured point to compare with yet.'
            : <>Forecast made at {new Date(versus.madeAt).toLocaleTimeString()}: <span className="font-medium">{versus.inBand} of {versus.compared}</span> measured
              points inside its 80 % band · mean error <span className="font-medium">{number(versus.meanError)} {result.context.outputUnit}</span>
              {versus.bias !== null && Math.abs(versus.bias) >= (versus.meanError ?? 0) * 0.5
                ? ` · it ran ${versus.bias > 0 ? 'low' : 'high'} by ${number(Math.abs(versus.bias))} on average` : ''}.</>}
        </p>}
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

      {/* Two folds, where there were five: what the forecast is worth, and how it is set up. */}
      {result && <details><summary>Quality</summary>
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
        <ForecastShadowComparison view={shadow?.seriesId === series?.seriesId ? shadow?.view ?? null : null} />
      </details>}
      {series && <details onToggle={e => { const open = e.currentTarget.open; setSummaryOpen(open); setPreparationOpen(open); }}>
        <summary>Configuration</summary>
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
        <p className="text-xs text-on-surface-variant">Read-only effective configuration: to change it, stop this forecast and start it again from its metric card.</p>
        <ForecastProgress progress={seriesProgress} error={progressError} result={result} />
        {/* The dependency checks, for when a forecast stops: read only once this fold is open. */}
        {preparationOpen && <ForecastPreparation />}
      </details>}
    </>}
  </section>;
}
