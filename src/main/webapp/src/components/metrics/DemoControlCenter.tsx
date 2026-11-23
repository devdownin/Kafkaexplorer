// SPDX-License-Identifier: AGPL-3.0-or-later
import { useEffect, useMemo, useState } from 'react';
import axios from 'axios';
import { Badge, Button, Card } from '../ui';
import type { DemoControlStatus, ForecastStatus, MetricConfig } from '../../api/types';
import { demoSnapshot, downloadDemoReport } from './demoReport';

type Scenario = 'normal' | 'overload' | 'incident' | 'recover' | 'seasonal';
const SCENARIOS: { key: Scenario; title: string; detail: string; icon: string }[] = [
  { key: 'normal', title: 'Normal', detail: 'Natural traffic patterns', icon: 'check_circle' },
  { key: 'overload', title: 'Overload', detail: 'Orders and requests back up', icon: 'trending_up' },
  { key: 'incident', title: 'Incident', detail: 'Ticket and event spikes', icon: 'warning' },
  { key: 'recover', title: 'Recovery', detail: 'Drain outstanding work', icon: 'restart_alt' },
  { key: 'seasonal', title: 'Seasonality', detail: 'Fast, repeating request peaks', icon: 'waves' },
];

/**
 * Mounted only for the opt-in demo API (404 is an ordinary production deployment).
 * Reuses the Metrics page's visible-tab clock — no independent polling loop.
 */
export function DemoControlCenter({ metrics, forecast, clock }: {
  metrics: MetricConfig[]; forecast: ForecastStatus | null; clock: number;
}) {
  const [status, setStatus] = useState<DemoControlStatus | null>(null);
  const [available, setAvailable] = useState(true);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  const [notice, setNotice] = useState('');

  useEffect(() => {
    const controller = new AbortController();
    axios.get<DemoControlStatus>('/api/demo-control', { signal: controller.signal, timeout: 7000 })
      .then(({ data }) => {
        if (controller.signal.aborted) return;
        if (!data || !SCENARIOS.some(item => item.key === data.scenario)) { setAvailable(false); return; }
        setAvailable(true); setStatus(data); setError('');
      })
      .catch(e => {
        if (controller.signal.aborted) return;
        if (axios.isAxiosError(e) && e.response?.status === 404) { setAvailable(false); return; }
        setError('Demo control connection failed. The scenario remains unchanged.');
      });
    return () => controller.abort();
  }, [clock]);

  const demoMetrics = useMemo(() => metrics.filter(m => m.id.startsWith('demo-live-')), [metrics]);
  const demoSeries = useMemo(() => (forecast?.series ?? []).filter(s => s.metricId.startsWith('demo-live-')), [forecast]);
  const ready = demoSeries.filter(s => s.result?.state === 'READY' && s.result?.strategy === 'TIMESFM').length;
  const historyAvailable = demoSeries.some(s => (s.result?.context?.observedPoints ?? 0) >= 500);
  const trafficRecent = demoMetrics.some(m => m.lastUpdateTime != null
    && Date.now() - m.lastUpdateTime < 120_000 && !m.errorMessage);

  if (!available || !status) return null;

  async function choose(scenario: Scenario) {
    if (busy || !status || status.scenario === scenario) return;
    setBusy(true); setError(''); setNotice('');
    try {
      const { data } = await axios.post<DemoControlStatus>('/api/demo-control/scenario', { scenario }, {
        headers: { 'X-Kex-Demo-Control': 'change-scenario' }, timeout: 10000,
      });
      setStatus(data);
      setNotice(`${SCENARIOS.find(s => s.key === scenario)?.title} selected. Kafka traffic changes on the next generator tick (up to 15 s).`);
    } catch {
      setError('Could not apply the scenario. Check demo-live and Explorer logs; the previous mode was kept.');
    } finally {
      setBusy(false);
    }
  }

  function exportReport(format: 'json' | 'html') {
    downloadDemoReport(demoSnapshot(status!.scenario, metrics, forecast), format);
    setNotice(`${format.toUpperCase()} snapshot exported from the latest Metrics and Forecast readings.`);
  }

  const evidence = [
    { label: 'Explorer API', value: 'Connected', ok: true, icon: 'dns' },
    { label: 'Kafka traffic', value: trafficRecent ? 'Recent metrics' : 'Waiting for refresh', ok: trafficRecent, icon: 'hub' },
    { label: 'History store', value: historyAvailable ? 'Forecast context loaded' : 'Awaiting history', ok: historyAvailable, icon: 'database' },
    { label: 'TimesFM', value: ready ? `${ready} ready` : 'Awaiting forecast', ok: ready > 0, icon: 'query_stats' },
  ];

  return (
    <section aria-label="Forecast Demo Control Center" className="scroll-mt-20">
      <Card className="relative overflow-hidden p-4 md:p-5">
        <div aria-hidden="true" className="pointer-events-none absolute -top-24 right-0 size-56 rounded-full bg-primary/5 blur-3xl" />
        <div className="relative">
          <div className="flex flex-wrap gap-3 items-start justify-between">
            <div className="flex items-start gap-3 min-w-0">
              <span aria-hidden="true" className="material-symbols-outlined text-primary text-[23px] mt-0.5">tune</span>
              <div>
                <div className="flex flex-wrap items-center gap-2">
                  <h2 className="font-semibold text-base tracking-tight text-on-surface">Forecast Demo Control Center</h2>
                  <Badge tone="success">Local demo</Badge>
                </div>
                <p className="text-[12px] mt-0.5 text-on-surface-variant">Control the live Kafka scenarios and capture an evidence-based forecast snapshot.</p>
              </div>
            </div>
            <div className="flex flex-wrap gap-2 items-center">
              <Button size="sm" variant="outline" icon="download" onClick={() => exportReport('json')} aria-label="Export demo report as JSON">JSON</Button>
              <Button size="sm" variant="outline" icon="description" onClick={() => exportReport('html')} aria-label="Export demo report as HTML">HTML</Button>
            </div>
          </div>

          <div className="grid grid-cols-2 md:grid-cols-4 gap-2 mt-4" aria-label="Live demo indicators">
            {evidence.map(item => (
              <div key={item.label} className="rounded-xl border border-outline-variant/70 bg-surface-container-low/60 px-3 py-2.5 min-w-0">
                <div className="flex gap-1.5 items-center text-[11px] text-on-surface-variant">
                  <span className="material-symbols-outlined text-[15px]" aria-hidden="true">{item.icon}</span>
                  {item.label}
                </div>
                <div className="flex items-center gap-1.5 mt-1">
                  <span aria-hidden="true" className={`size-1.5 shrink-0 rounded-full ${item.ok ? 'bg-success' : 'bg-warning'}`} />
                  <span className="text-xs font-medium text-on-surface truncate" title={item.value}>{item.value}</span>
                </div>
              </div>
            ))}
          </div>
          <div className="flex flex-wrap justify-between items-center gap-2 mt-4 mb-2">
            <h3 className="text-[12px] font-semibold tracking-wide text-on-surface">Traffic scenarios</h3>
            <span className="text-[11px] text-on-surface-variant">
              {demoMetrics.length}/5 metrics · {ready}/{demoSeries.length} ready TimesFM forecasts
              {status.changedAt && ` · mode updated ${new Date(status.changedAt).toLocaleTimeString()}`}
            </span>
          </div>
          <div className="grid grid-cols-2 md:grid-cols-3 xl:grid-cols-5 gap-2" role="group" aria-label="Choose a demo traffic scenario">
            {SCENARIOS.map(item => {
              const selected = item.key === status.scenario;
              return (
                <button key={item.key} type="button" disabled={busy} aria-pressed={selected}
                  onClick={() => void choose(item.key)}
                  className={`kse-control min-h-[88px] rounded-xl p-3 text-left border transition-colors disabled:opacity-60 focus-visible:outline-2 focus-visible:outline-primary ${selected
                    ? 'border-primary/60 bg-primary/10 text-on-surface shadow-sm'
                    : 'border-outline-variant/70 bg-surface-container-low/50 text-on-surface hover:bg-surface-container-high'}`}>
                  <span className="flex items-center justify-between gap-2">
                    <span aria-hidden="true" className={`material-symbols-outlined text-[19px] ${selected ? 'text-primary' : 'text-on-surface-variant'}`}>{item.icon}</span>
                    {selected && <span className="text-[10px] font-semibold text-primary">ACTIVE</span>}
                  </span>
                  <span className="block font-semibold text-[12px] mt-1">{item.title}</span>
                  <span className="block text-[11px] leading-snug mt-0.5 text-on-surface-variant">{item.detail}</span>
                </button>
              );
            })}
          </div>
          <div className="flex flex-wrap items-center justify-between gap-2 mt-3 text-[11px] text-on-surface-variant">
            <span>Indicators are derived from observed metrics and forecast results, not Docker container health.</span>
            <span aria-live="polite">{busy ? 'Applying scenario…' : notice}</span>
          </div>
          {error && <p role="alert" className="text-error text-[12px] mt-2">{error}</p>}
        </div>
      </Card>
    </section>
  );
}
