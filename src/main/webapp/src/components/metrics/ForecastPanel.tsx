// SPDX-License-Identifier: AGPL-3.0-or-later
import { useEffect, useState } from 'react';
import axios from 'axios';
import { Area, ComposedChart, Line, ResponsiveContainer, Tooltip, XAxis, YAxis } from 'recharts';
import { Badge, Button, useConfirm } from '../ui';
import { copyText } from '../../clipboard';
import type { BadgeTone } from '../ui';
import type {
  ForecastActivationRefusal, ForecastProgress as Progress, ForecastStatus,
} from '../../api/types';
import { ForecastPreparation } from './ForecastPreparation';
import { ForecastProgress } from './ForecastProgress';
import { ForecastTimeline } from './ForecastTimeline';

const STATE_TONE: Record<string, BadgeTone> = { READY: 'success', DEGRADED: 'error' };
/** Plain words on the page; the pilot's own term stays in the tooltip and in the API. */
const VISIBILITY_LABEL: Record<string, string> = { ACTIVE: 'Approved', SHADOW: 'Observing' };
const POLL_MS = 30000;

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
  const [selected, setSelected] = useState('');
  const [error, setError] = useState('');
  const [busy, setBusy] = useState(false);
  const [copied, setCopied] = useState(false);
  const [revision, setRevision] = useState(0);
  const confirm = useConfirm();
  // One loop for the catalogue and the selected series' history progress: two independent pollers
  // meant two loading states, two error messages and two clocks for one panel.
  useEffect(() => {
    let disposed = false;
    const controller = new AbortController();
    let timer: ReturnType<typeof setTimeout>;
    async function poll() {
      try {
        const { data } = await axios.get<ForecastStatus>('/api/forecasts', { signal: controller.signal, timeout: 10000 });
        if (!data || typeof data.enabled !== 'boolean' || typeof data.state !== 'string' || !Array.isArray(data.series))
          throw new Error('Invalid forecast status response');
        if (disposed) return;
        setStatus(data); setError(''); onStatus?.(data);
        const seriesId = data.series.some(s => s.seriesId === selected) ? selected : data.series[0]?.seriesId;
        if (!seriesId) { setProgress(null); return; }
        try {
          const { data: next } = await axios.get<Progress>(`/api/forecasts/${encodeURIComponent(seriesId)}/progress`,
            { signal: controller.signal, timeout: 10000 });
          if (!isProgress(next, seriesId)) throw new Error('Invalid progress response');
          if (!disposed) { setProgress(next); setProgressError(''); }
        } catch {
          if (!disposed) setProgressError('History progress unavailable. Previous diagnostics may be stale.');
        }
      } catch {
        if (!disposed) setError('Forecast persistence is unavailable. Existing results may be stale.');
      } finally {
        if (!disposed) timer = setTimeout(poll, POLL_MS);
      }
    }
    void poll();
    return () => { disposed = true; controller.abort(); clearTimeout(timer); };
    // eslint-disable-next-line react-hooks/exhaustive-deps -- onStatus is the page's state setter, stable
  }, [revision, selected, refresh]);
  const series = status?.series.find(s => s.seriesId === selected) ?? status?.series[0];
  const result = series?.result;
  const seriesProgress = progress?.seriesId === series?.seriesId ? progress : null;
  const chart = [
    ...(result?.context.points.slice(-60).map(p => ({ at: p.endAt, actual: p.value })) ?? []),
    ...(result?.forecast?.points.map(p => ({ at: p.at, median: p.q50, floor: p.q10, band: p.q90 - p.q10 })) ?? []),
  ];
  async function activation() {
    if (!series || busy) return;
    const active = result?.visibility !== 'ACTIVE';
    if (active && !await confirm({ title: 'Approve this forecast?', description: 'Records your approval of its realised quality. It sends no alert and can be returned to observing at any time.', confirmLabel: 'Approve', tone: 'primary' })) return;
    setBusy(true);
    try {
      await axios.put(`/api/forecasts/${encodeURIComponent(series.seriesId)}/activation`, { active, confirmed: active }, { timeout: 10000 });
      setRevision(v => v + 1);
    } catch (e) {
      const refusal = axios.isAxiosError<ForecastActivationRefusal>(e) ? e.response?.data?.reason : undefined;
      setError(typeof refusal === 'string' ? `Approval refused: ${refusal}` : 'Approval unavailable. Retry after the next refresh.');
    } finally { setBusy(false); }
  }
  async function withdraw() {
    if (!series || busy) return;
    if (!await confirm({ title: 'Stop this forecast?', description: 'Withdraws the approval made from the assistant. Collected history and past results are kept until retention removes them.', confirmLabel: 'Stop forecasting', tone: 'danger' })) return;
    setBusy(true);
    try {
      await axios.delete(`/api/forecasts/series/${encodeURIComponent(series.seriesId)}`, { timeout: 10000 });
      setSelected(''); setRevision(v => v + 1);
    } catch (e) {
      const refusal = axios.isAxiosError<ForecastActivationRefusal>(e) ? e.response?.data?.reason : undefined;
      setError(typeof refusal === 'string' ? `Stop refused: ${refusal}` : 'Stop unavailable. Retry after the next refresh.');
    } finally { setBusy(false); }
  }
  async function copyResult() {
    if (!result) return;
    if (await copyText(JSON.stringify(result, null, 2))) setCopied(true);
    else setError('Copy unavailable in this browser. The result is also served by GET /api/forecasts.');
  }
  return <section className="card p-5 space-y-4" aria-label="Metrics Forecast">
    <h2 className="text-lg font-semibold">Metrics Forecast</h2>
    {error && <p role="alert">{error}</p>}
    {!status && !error && <p>Loading forecast status…</p>}
    {status && !status.enabled && <p>Forecast pilot disabled by configuration. Enable explorer.forecasting.pilot.enabled to use it.</p>}
    {(status?.enabled || !status && error) && (!status?.series.length ? <ForecastPreparation onConfigure={onConfigure} /> :
      <details><summary>Preparation and configuration</summary><ForecastPreparation onConfigure={onConfigure} /></details>)}
    {status?.enabled && status.series.length === 0 && <p>Forecast pilot enabled. Configure approved series, PostgreSQL history and TimesFM inference to calculate forecasts.</p>}
    {status?.enabled && status.series.length > 0 && <>
      <div className="flex flex-wrap items-center gap-2">
        <label>Series <select value={series?.seriesId ?? ''} onChange={e => { setSelected(e.target.value); setCopied(false); }}>
          {status.series.map(s => <option key={s.seriesId} value={s.seriesId}>{s.metricId} · {s.environment}</option>)}
        </select></label>
        {result && <span className="flex flex-wrap gap-1" aria-label="Forecast status">
          <Badge tone={STATE_TONE[result.state] ?? 'warning'}>{result.state}</Badge>
          <Badge tone={result.strategy === 'TIMESFM' ? 'neutral' : 'warning'}>{result.strategy}</Badge>
          <Badge tone={result.visibility === 'ACTIVE' ? 'primary' : 'neutral'} title={result.visibility}>
            {VISIBILITY_LABEL[result.visibility] ?? result.visibility}</Badge>
          <Badge>{result.context.outputUnit}</Badge>
        </span>}
      </div>
      {series && <ForecastTimeline series={series} />}
      {series && <ForecastProgress progress={seriesProgress} error={progressError} result={result} />}
      {series?.withdrawable && <Button variant="ghost" onClick={() => void withdraw()} disabled={busy}>Stop forecasting this series</Button>}
      {!result ? <p>No persisted forecast yet.</p> : <>
        <p>{result.reason}</p>
        {result.forecast && <div style={{ height: 260 }} aria-label="Measured history and forecast">
          <ResponsiveContainer><ComposedChart data={chart}>
            <XAxis dataKey="at" type="number" domain={['dataMin', 'dataMax']} tickFormatter={v => new Date(v).toLocaleTimeString()} />
            <YAxis /><Tooltip labelFormatter={v => new Date(Number(v)).toLocaleString()} />
            {result.strategy === 'TIMESFM' && <><Area dataKey="floor" stackId="interval" stroke="none" fill="transparent" isAnimationActive={false} />
              <Area dataKey="band" stackId="interval" stroke="none" fill="#64748b" fillOpacity={0.2} name="80 % interval" isAnimationActive={false} /></>}
            <Line dataKey="actual" stroke="#16a34a" dot={false} name="Observed" isAnimationActive={false} />
            <Line dataKey="median" stroke="#6366f1" dot={false} name={result.strategy === 'TIMESFM' ? 'Median forecast' : 'Baseline forecast'} isAnimationActive={false} />
          </ComposedChart></ResponsiveContainer>
        </div>}
        <p>The 80 % interval is nominal, not a guarantee. A baseline forecast has no interval. No alert is sent.</p>
        <details><summary>Quality details and baselines</summary>
        {!result.quality ? <p>Realised quality: unmeasured.</p> : <>
          <p>MASE {result.quality.mase?.toPrecision(4) ?? 'unmeasured'} · interval coverage {(100 * result.quality.q10Q90Coverage).toFixed(1)}% · interval width {result.quality.meanIntervalWidth.toPrecision(4)} · pinball loss {result.quality.meanPinballLoss.toPrecision(4)}</p>
          <table><thead><tr><th>Baseline</th><th>Realised MAE</th></tr></thead><tbody>
            {Object.entries(result.baselineMae).map(([name, mae]) => <tr key={name}><td>{name}</td><td>{mae.toPrecision(4)}</td></tr>)}
          </tbody></table>
        </>}
        <Button variant="ghost" onClick={() => void copyResult()}>{copied ? 'Raw result copied' : 'Copy raw result (JSON)'}</Button>
        </details>
        {result.visibility === 'ACTIVE'
          ? <Button onClick={() => void activation()} disabled={busy}>Return to observing</Button>
          : <div className="space-y-1">
            {/* The timeline's approval step says what still blocks it; the button points at it. */}
            <Button onClick={() => void activation()} disabled={busy || !series?.activation?.eligible}
              aria-describedby={series?.activation?.eligible ? undefined : 'forecast-step-approval'}>Approve after quality checks</Button>
          </div>}
      </>}
    </>}
  </section>;
}
