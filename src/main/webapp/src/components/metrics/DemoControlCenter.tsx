// SPDX-License-Identifier: AGPL-3.0-or-later
import { useCallback, useEffect, useState } from 'react';
import axios from 'axios';
import type { ForecastStatus } from '../../api/types';

type Scenario = 'normal' | 'overload' | 'incident' | 'recover' | 'seasonal';
type DemoState = { enabled: boolean; scenario: Scenario };
const SCENARIOS: { id: Scenario; label: string; detail: string; icon: string }[] = [
  { id: 'normal', label: 'Normal', detail: 'Natural live traffic', icon: 'check_circle' },
  { id: 'overload', label: 'Overload', detail: 'Backlog increases', icon: 'trending_up' },
  { id: 'incident', label: 'Incident', detail: 'Abrupt disruption', icon: 'warning' },
  { id: 'recover', label: 'Recovery', detail: 'Return to nominal', icon: 'healing' },
  { id: 'seasonal', label: 'Seasonality', detail: 'Periodic traffic', icon: 'waves' },
];

/** Only mounts in a demo-enabled deployment. A normal KafkaExplorer gets a 404 and no UI. */
export function DemoControlCenter({ metricCount, forecasts }: {
  metricCount: number; forecasts: ForecastStatus | null;
}) {
  const [state, setState] = useState<DemoState | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  const [expanded, setExpanded] = useState(false);
  const refresh = useCallback(async (signal?: AbortSignal) => {
    try {
      const response = await axios.get<DemoState>('/api/demo-control', { signal });
      if (!signal?.aborted) { setState(response.data); setError(''); }
    } catch (err) {
      if (signal?.aborted) return;
      if (axios.isAxiosError(err) && err.response?.status === 404) { setState(null); return; }
      setError('Demo status is unavailable. Retry to refresh.');
    }
  }, []);
  useEffect(() => {
    const controller = new AbortController();
    void refresh(controller.signal);
    return () => controller.abort();
  }, [refresh]);

  if (!state?.enabled) return null;
  const ready = forecasts?.series.filter(s => s.result?.state === 'READY').length ?? 0;
  const seriesCount = forecasts?.series.length ?? 0;
  function exportReport() {
    // Deliberately export only the on-screen summary, not tokens, config or raw Kafka data.
    const report = {
      kind: 'kafkaexplorer-forecast-demo',
      exportedAt: new Date().toISOString(),
      scenario: state?.scenario,
      metrics: { active: metricCount, expected: 5 },
      forecasts: {
        total: seriesCount, ready,
        series: (forecasts?.series ?? []).map(series => ({
          metricId: series.metricId,
          state: series.result?.state ?? 'UNAVAILABLE',
          strategy: series.result?.strategy ?? null,
          generatedAt: series.result?.generatedAt ?? null,
          quality: series.result?.quality ?? null,
        })),
      },
    };
    const url = URL.createObjectURL(new Blob([JSON.stringify(report, null, 2)], { type: 'application/json' }));
    const link = document.createElement('a');
    link.href = url; link.download = 'kafkaexplorer-forecast-demo-report.json';
    document.body.appendChild(link); link.click(); link.remove();
    window.setTimeout(() => URL.revokeObjectURL(url), 0);
  }
  async function choose(scenario: Scenario) {
    if (busy || scenario === state?.scenario) return;
    setBusy(true); setError('');
    try {
      const response = await axios.put<DemoState>('/api/demo-control', { scenario }, { timeout: 10000 });
      setState(response.data);
    } catch {
      setError('Scenario change failed. The generator has not been changed.');
    } finally { setBusy(false); }
  }

  return <section aria-label="Forecast demo control center"
    className="rounded-2xl border border-outline-variant/60 bg-surface-container-low/80 shadow-sm overflow-hidden">
    <div className="flex flex-wrap items-center justify-between gap-3 p-4 md:px-5">
      <div className="flex items-center gap-3">
        <span className="material-symbols-outlined text-primary" aria-hidden="true">tune</span>
        <div><h2 className="text-base font-semibold text-on-surface">Forecast Demo Control Center</h2>
          <p className="text-xs text-on-surface-variant">Local demo · Live Kafka traffic · Changes apply on the next generator tick</p></div>
      </div>
      <div className="flex items-center gap-2">
      <button type="button" onClick={exportReport}
        className="rounded-lg border border-outline-variant px-3 py-2 text-sm text-on-surface hover:bg-primary/10">
        <span className="material-symbols-outlined text-base align-middle mr-1" aria-hidden="true">download</span>
        Export report
      </button>
      <button type="button" onClick={() => setExpanded(v => !v)} aria-expanded={expanded}
        className="rounded-lg border border-outline-variant px-3 py-2 text-sm text-on-surface hover:bg-primary/10">
        {expanded ? 'Hide controls' : 'Manage scenarios'}
        <span className="material-symbols-outlined text-base align-middle ml-1" aria-hidden="true">{expanded ? 'expand_less' : 'expand_more'}</span>
      </button></div>
    </div>
    <div className="grid grid-cols-2 md:grid-cols-4 gap-2 px-4 pb-4 md:px-5 text-sm">
      <div className="rounded-xl bg-surface-container/70 p-3"><p className="text-xs text-on-surface-variant">Metrics</p><strong>{metricCount} / 5</strong></div>
      <div className="rounded-xl bg-surface-container/70 p-3"><p className="text-xs text-on-surface-variant">Forecast series</p><strong>{seriesCount}</strong></div>
      <div className="rounded-xl bg-surface-container/70 p-3"><p className="text-xs text-on-surface-variant">Ready forecasts</p><strong>{ready}</strong></div>
      <div className="rounded-xl bg-surface-container/70 p-3"><p className="text-xs text-on-surface-variant">Traffic scenario</p><strong className="capitalize">{state.scenario}</strong></div>
    </div>
    {expanded && <div className="border-t border-outline-variant/60 px-4 py-4 md:px-5">
      <p className="text-xs text-on-surface-variant mb-3">Select a scenario. This changes demo Kafka traffic only; existing metric history and forecasts are preserved.</p>
      <div className="grid grid-cols-2 lg:grid-cols-5 gap-2">
        {SCENARIOS.map(s => <button type="button" key={s.id} disabled={busy}
          aria-pressed={state.scenario === s.id} onClick={() => void choose(s.id)}
          className={`min-h-24 text-left rounded-xl border p-3 transition-colors disabled:opacity-60 focus-visible:outline-2 focus-visible:outline-primary ${state.scenario === s.id ? 'border-primary bg-primary/10' : 'border-outline-variant hover:bg-primary/5'}`}>
          <span className="material-symbols-outlined text-xl text-primary" aria-hidden="true">{s.icon}</span>
          <span className="block text-sm font-medium text-on-surface">{s.label}</span>
          <span className="block text-xs text-on-surface-variant mt-1">{s.detail}</span>
        </button>)}
      </div>
      <div className="flex items-center justify-between mt-3">
        <span className="text-xs text-on-surface-variant" role="status">{busy ? 'Applying scenario…' : 'Current scenario: ' + state.scenario}</span>
        <button type="button" onClick={() => void refresh()} className="text-xs text-primary underline underline-offset-2">Refresh status</button>
      </div>
    </div>}
    {error && <p role="alert" className="px-5 pb-4 text-sm text-error">{error}</p>}
  </section>;
}
