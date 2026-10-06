// SPDX-License-Identifier: AGPL-3.0-or-later
import { useEffect, useState } from 'react';
import axios from 'axios';
import { Area, ComposedChart, Line, ResponsiveContainer, Tooltip, XAxis, YAxis } from 'recharts';
import { Button, useConfirm } from '../ui';
import type { ForecastStatus } from '../../api/types';
import { ForecastPreparation } from './ForecastPreparation';
import { ForecastProgress } from './ForecastProgress';

export function ForecastPanel() {
  const [status, setStatus] = useState<ForecastStatus | null>(null);
  const [selected, setSelected] = useState('');
  const [error, setError] = useState('');
  const [busy, setBusy] = useState(false);
  const [revision, setRevision] = useState(0);
  const confirm = useConfirm();
  useEffect(() => {
    let disposed = false;
    const controller = new AbortController();
    let timer: ReturnType<typeof setTimeout>;
    async function poll() {
      try {
        const { data } = await axios.get<ForecastStatus>('/api/forecasts', { signal: controller.signal, timeout: 10000 });
        if (!data || typeof data.enabled !== 'boolean' || typeof data.state !== 'string' || !Array.isArray(data.series))
          throw new Error('Invalid forecast status response');
        if (!disposed) { setStatus(data); setError(''); }
      } catch {
        if (!disposed) setError('Forecast persistence is unavailable. Existing results may be stale.');
      } finally {
        if (!disposed) timer = setTimeout(poll, 30000);
      }
    }
    void poll();
    return () => { disposed = true; controller.abort(); clearTimeout(timer); };
  }, [revision]);
  const series = status?.series.find(s => s.seriesId === selected) ?? status?.series[0];
  const result = series?.result;
  const chart = [
    ...(result?.context.points.slice(-60).map(p => ({ at: p.endAt, actual: p.value })) ?? []),
    ...(result?.forecast?.points.map(p => ({ at: p.at, median: p.q50, floor: p.q10, band: p.q90 - p.q10 })) ?? []),
  ];
  async function activation() {
    if (!series || busy) return;
    const active = result?.visibility !== 'ACTIVE';
    if (active && !await confirm({ title: 'Activate this forecast?', description: 'Requires realised quality and all four baseline comparisons. This records operator approval; it sends no alert.', confirmLabel: 'Activate', tone: 'primary' })) return;
    setBusy(true);
    try {
      await axios.put(`/api/forecasts/${encodeURIComponent(series.seriesId)}/activation`, { active, confirmed: active }, { timeout: 10000 });
      setRevision(v => v + 1);
    } catch {
      setError('Activation refused or unavailable. Check realised quality and retry after refresh.');
    } finally { setBusy(false); }
  }
  return <section className="card p-5 space-y-4" aria-label="Metrics Forecast">
    <h2 className="text-lg font-semibold">Metrics Forecast</h2>
    {error && <p role="alert">{error}</p>}
    {!status && !error && <p>Loading forecast status…</p>}
    {status && !status.enabled && <p>Forecast pilot disabled by configuration. Enable explorer.forecasting.pilot.enabled to use it.</p>}
    {(status?.enabled || !status && error) && (!status?.series.length ? <ForecastPreparation /> :
      <details><summary>Preparation and configuration</summary><ForecastPreparation /></details>)}
    {status?.enabled && status.series.length === 0 && <p>Forecast pilot enabled. Configure approved series, PostgreSQL history and TimesFM inference to calculate forecasts.</p>}
    {status?.enabled && status.series.length > 0 && <>
      <label>Series <select value={series?.seriesId ?? ''} onChange={e => setSelected(e.target.value)}>
        {status.series.map(s => <option key={s.seriesId} value={s.seriesId}>{s.metricId} · {s.environment}</option>)}
      </select></label>
      {series && <ForecastProgress key={series.seriesId} seriesId={series.seriesId} result={result} />}
      {!result ? <p>No persisted forecast yet.</p> : <>
        <p>{result.state} · {result.strategy} · {result.visibility} · {result.context.outputUnit}</p>
        <p>{result.reason}</p>
        {result.forecast && <div style={{ height: 260 }} aria-label="Measured history and forecast">
          <ResponsiveContainer><ComposedChart data={chart}>
            <XAxis dataKey="at" type="number" domain={['dataMin', 'dataMax']} tickFormatter={v => new Date(v).toLocaleTimeString()} />
            <YAxis /><Tooltip labelFormatter={v => new Date(Number(v)).toLocaleString()} />
            {result.strategy === 'TIMESFM' && <><Area dataKey="floor" stackId="interval" stroke="none" fill="transparent" isAnimationActive={false} />
              <Area dataKey="band" stackId="interval" stroke="none" fill="#64748b" fillOpacity={0.2} name="Q10–Q90 width" isAnimationActive={false} /></>}
            <Line dataKey="actual" stroke="#16a34a" dot={false} name="Observed" isAnimationActive={false} />
            <Line dataKey="median" stroke="#6366f1" dot={false} name="Q50 / point baseline" isAnimationActive={false} />
          </ComposedChart></ResponsiveContainer>
        </div>}
        <p>Nominal quantiles; no guaranteed confidence. Baseline fallback has no interval. No alerts are sent.</p>
        <details><summary>Quality comparisons and raw result</summary>
        {!result.quality ? <p>Realised quality: unmeasured.</p> : <>
          <p>Evaluated: {result.evaluatedPoints} points · MAE {result.quality.mae.toPrecision(4)} · MASE {result.quality.mase?.toPrecision(4) ?? 'unmeasured'} · coverage {(100 * result.quality.q10Q90Coverage).toFixed(1)}% · width {result.quality.meanIntervalWidth.toPrecision(4)} · pinball {result.quality.meanPinballLoss.toPrecision(4)}</p>
          <table><thead><tr><th>Baseline</th><th>Realised MAE</th></tr></thead><tbody>
            {Object.entries(result.baselineMae).map(([name, mae]) => <tr key={name}><td>{name}</td><td>{mae.toPrecision(4)}</td></tr>)}
          </tbody></table>
        </>}
        <pre className="overflow-auto max-h-80 text-xs">{JSON.stringify(result, null, 2)}</pre>
        </details>
        <Button onClick={() => void activation()} disabled={busy || result.state !== 'READY' && result.visibility !== 'ACTIVE'}>
          {result.visibility === 'ACTIVE' ? 'Return to SHADOW' : 'Activate after quality checks'}
        </Button>
      </>}
    </>}
  </section>;
}
