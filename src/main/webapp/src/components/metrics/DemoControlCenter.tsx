// SPDX-License-Identifier: AGPL-3.0-or-later
import { useCallback, useEffect, useState } from 'react';
import axios from 'axios';
import type { ForecastStatus } from '../../api/types';

type Scenario = 'normal' | 'overload' | 'incident' | 'recover' | 'seasonal';
type Phase = 'ACTIVE' | 'APPLYING' | 'STALE';
type DemoState = {
  enabled: boolean;
  /** Last scenario whose requested tick was verified against Kafka log offsets. */
  scenario: Scenario | null;
  requestedScenario: Scenario;
  phase: Phase;
  appliedAt: number | null;
};
type View = 'loading' | 'hidden' | 'ready' | 'error';

const SCENARIOS: {
  id: Scenario; label: string; detail: string; impact: string; icon: string; disruptive: boolean;
}[] = [
  { id: 'normal', label: 'Normal', detail: 'Natural live traffic', impact: 'Restore the baseline traffic curves.', icon: 'check_circle', disruptive: false },
  { id: 'overload', label: 'Overload', detail: 'Backlog increases', impact: 'Orders and requests will exceed their critical thresholds.', icon: 'trending_up', disruptive: true },
  { id: 'incident', label: 'Incident', detail: 'Abrupt disruption', impact: 'Tickets and unarchived events will increase sharply.', icon: 'warning', disruptive: true },
  { id: 'recover', label: 'Recovery', detail: 'Return to nominal', impact: 'Return all five traffic counts to a stable baseline.', icon: 'healing', disruptive: false },
  { id: 'seasonal', label: 'Seasonality', detail: 'Periodic traffic', impact: 'Requests will follow a faster, amplified cycle.', icon: 'waves', disruptive: true },
];

/** Opt-in controller: 404 means absent by design, not an error banner on normal installations. */
export function DemoControlCenter({ metricCount, metricsLoading, forecasts, forecastsQueried }: {
  metricCount: number;
  metricsLoading: boolean;
  forecasts: ForecastStatus | null;
  forecastsQueried: boolean;
}) {
  const [view, setView] = useState<View>('loading');
  const [state, setState] = useState<DemoState | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  const [expanded, setExpanded] = useState(false);
  const [candidate, setCandidate] = useState<Scenario | null>(null);

  const refresh = useCallback(async (signal?: AbortSignal) => {
    try {
      const response = await axios.get<DemoState>('/api/demo-control', { signal, timeout: 10000 });
      if (signal?.aborted) return;
      if (!response.data?.enabled || !response.data.requestedScenario || !response.data.phase)
        throw new Error('Invalid demo control response');
      setState(response.data);
      setView('ready');
      setError('');
    } catch (err) {
      if (signal?.aborted) return;
      if (axios.isAxiosError(err) && err.response?.status === 404) {
        setState(null);
        setView('hidden');
      } else {
        setView('error');
        setError('Demo control status is unavailable. Check the Explorer service and retry.');
      }
    }
  }, []);
  useEffect(() => {
    const controller = new AbortController();
    void Promise.resolve().then(() => refresh(controller.signal));
    return () => controller.abort();
  }, [refresh]);
  useEffect(() => {
    if (view === 'hidden' || view === 'loading') return;
    const interval = window.setInterval(() => {
      if (document.visibilityState === 'visible') void refresh();
    }, view === 'error' || state?.phase !== 'ACTIVE' ? 4000 : 12000);
    return () => window.clearInterval(interval);
  }, [view, state?.phase, refresh]);

  if (view === 'loading' || view === 'hidden') return null;
  if (view === 'error' || !state) return <section aria-label="Forecast demo control center"
    className="rounded-2xl border border-outline-variant/60 bg-surface-container-low p-4">
    <div className="flex flex-wrap items-center justify-between gap-3">
      <div>
        <h2 className="font-semibold text-on-surface">Forecast Demo Control Center</h2>
        <p role="alert" className="text-sm text-error mt-1">{error}</p>
      </div>
      <button type="button" onClick={() => void refresh()}
        className="min-h-10 rounded-lg border border-outline-variant px-4 py-2 text-sm text-primary">Retry</button>
    </div>
  </section>;

  const ready = forecasts?.series.filter(s => s.result?.state === 'READY').length ?? 0;
  const seriesCount = forecasts?.series.length ?? 0;
  const forecastUnavailable = forecastsQueried && (!forecasts || !forecasts.enabled);
  const forecastNumber = !forecastsQueried ? 'Loading…' : forecastUnavailable ? 'Unavailable' : String(seriesCount);
  const readyNumber = !forecastsQueried ? 'Loading…' : forecastUnavailable ? 'Unavailable' : String(ready);
  const selectedScenario = SCENARIOS.find(s => s.id === candidate);
  const active = state.phase === 'ACTIVE';
  const appliedTime = state.appliedAt ? new Date(state.appliedAt).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit', second: '2-digit' }) : null;
  const phaseText = active ? 'Active · verified against Kafka offsets'
    : state.phase === 'APPLYING' ? 'Applying · waiting for Kafka offsets'
    : 'Generator unavailable · acknowledgement expired';

  function exportReport() {
    // Do not report a scenario as active before it is verified by the Kafka generator.
    const report = {
      kind: 'kafkaexplorer-forecast-demo',
      exportedAt: new Date().toISOString(),
      scenario: {
        requested: state?.requestedScenario, confirmed: state?.scenario,
        phase: state?.phase, appliedAt: state?.appliedAt,
      },
      metrics: { active: metricsLoading ? null : metricCount, expected: 5 },
      forecasts: {
        status: !forecastsQueried ? 'LOADING' : forecastUnavailable ? 'UNAVAILABLE' : 'AVAILABLE',
        total: forecastUnavailable || !forecastsQueried ? null : seriesCount,
        ready: forecastUnavailable || !forecastsQueried ? null : ready,
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
    if (busy || state?.requestedScenario === scenario) return;
    setBusy(true); setError(''); setCandidate(null);
    try {
      const response = await axios.put<DemoState>('/api/demo-control', { scenario }, { timeout: 10000 });
      if (!response.data?.requestedScenario || !response.data.phase)
        throw new Error('Invalid demo control response');
      setState(response.data);
    } catch {
      setError('Scenario change failed. No application was confirmed; retry the action.');
    } finally { setBusy(false); }
  }

  function selectScenario(scenario: Scenario) {
    const option = SCENARIOS.find(s => s.id === scenario);
    if (!option || busy || scenario === state?.requestedScenario) return;
    if (option.disruptive) setCandidate(scenario);
    else void choose(scenario); // Normal and Recovery stay one-click escape routes.
  }

  return <section aria-label="Forecast demo control center"
    className="rounded-2xl border border-outline-variant/60 bg-surface-container-low/80 shadow-sm overflow-hidden">
    <div className="flex flex-wrap items-center justify-between gap-3 p-4 md:px-5">
      <div className="flex items-center gap-3 min-w-0">
        <span className="material-symbols-outlined text-primary" aria-hidden="true">tune</span>
        <div><h2 className="text-base font-semibold text-on-surface">Forecast Demo Control Center</h2>
          <p className="text-xs text-on-surface-variant">Local demo · Kafka-verified scenario control</p></div>
      </div>
      <div className="flex items-center flex-wrap gap-2">
        <button type="button" onClick={exportReport}
          className="min-h-10 rounded-lg border border-outline-variant px-3 py-2 text-sm text-on-surface hover:bg-primary/10">
          <span className="material-symbols-outlined text-base align-middle mr-1" aria-hidden="true">download</span>
          Export report
        </button>
        <button type="button" onClick={() => setExpanded(v => !v)} aria-expanded={expanded}
          className="min-h-10 rounded-lg border border-outline-variant px-3 py-2 text-sm text-on-surface hover:bg-primary/10">
          {expanded ? 'Hide controls' : 'Manage scenarios'}
          <span className="material-symbols-outlined text-base align-middle ml-1" aria-hidden="true">{expanded ? 'expand_less' : 'expand_more'}</span>
        </button>
      </div>
    </div>
    <div className="grid grid-cols-2 md:grid-cols-4 gap-2 px-4 pb-4 md:px-5 text-sm">
      <div className="rounded-xl bg-surface-container/70 p-3"><p className="text-xs text-on-surface-variant">Demo metrics</p>
        <strong className="tabular-nums">{metricsLoading ? 'Loading…' : `${metricCount} / 5`}</strong></div>
      <div className="rounded-xl bg-surface-container/70 p-3"><p className="text-xs text-on-surface-variant">Forecast series</p>
        <strong className="tabular-nums">{forecastNumber}</strong></div>
      <div className="rounded-xl bg-surface-container/70 p-3"><p className="text-xs text-on-surface-variant">Ready forecasts</p>
        <strong className="tabular-nums">{readyNumber}</strong></div>
      <div className="rounded-xl bg-surface-container/70 p-3"><p className="text-xs text-on-surface-variant">Confirmed scenario</p>
        <strong className="capitalize">{state.scenario ?? 'Not verified'}</strong></div>
    </div>
    <div className="flex flex-wrap gap-2 items-center justify-between border-t border-outline-variant/60 px-4 py-3 md:px-5">
      <div role="status" aria-live="polite" className="flex flex-wrap gap-2 items-center text-xs text-on-surface-variant">
        <span aria-hidden="true" className={`h-2 w-2 rounded-full ${active ? 'bg-success' : state.phase === 'APPLYING' ? 'bg-warning' : 'bg-error'}`} />
        <span>{phaseText}</span>
        {!active && <span>Requested: <strong className="text-on-surface">{state.requestedScenario}</strong></span>}
        {appliedTime && <span>Last verified: {appliedTime}</span>}
      </div>
      {state.requestedScenario !== 'normal' && <button type="button" disabled={busy}
        onClick={() => void choose('normal')}
        className="min-h-10 rounded-lg border border-outline-variant px-3 py-2 text-xs font-medium text-primary hover:bg-primary/10 disabled:opacity-50">
        Return to normal
      </button>}
    </div>
    {expanded && <div className="border-t border-outline-variant/60 px-4 py-4 md:px-5">
      <p className="text-xs text-on-surface-variant mb-3">Select a scenario. Changes reach Kafka on the next traffic tick; the confirmation waits for broker offsets.</p>
      <div className="grid grid-cols-2 lg:grid-cols-5 gap-2">
        {SCENARIOS.map(s => <button type="button" key={s.id} disabled={busy || s.id === state.requestedScenario}
          aria-pressed={active && state.scenario === s.id} onClick={() => selectScenario(s.id)}
          className={`min-h-24 text-left rounded-xl border p-3 transition-colors disabled:opacity-60 focus-visible:outline-2 focus-visible:outline-primary ${state.requestedScenario === s.id ? 'border-primary bg-primary/10' : 'border-outline-variant hover:bg-primary/5'}`}>
          <span className="material-symbols-outlined text-xl text-primary" aria-hidden="true">{s.icon}</span>
          <span className="block text-sm font-medium text-on-surface">{s.label}</span>
          <span className="block text-xs text-on-surface-variant mt-1">{s.detail}</span>
        </button>)}
      </div>
      {selectedScenario && <div role="group" aria-label="Confirm traffic scenario"
        className="mt-3 rounded-xl border border-warning/40 bg-warning/5 p-3">
        <p className="text-sm font-medium text-on-surface">Apply {selectedScenario.label}?</p>
        <p className="text-xs text-on-surface-variant mt-1">{selectedScenario.impact} The change affects demo Kafka data only and does not rewrite historical metrics.</p>
        <div className="flex flex-wrap gap-2 mt-3">
          <button type="button" onClick={() => void choose(selectedScenario.id)} disabled={busy}
            className="min-h-10 rounded-lg bg-primary px-4 py-2 text-sm font-medium text-on-primary">Apply scenario</button>
          <button type="button" onClick={() => setCandidate(null)} disabled={busy}
            className="min-h-10 rounded-lg border border-outline-variant px-4 py-2 text-sm text-on-surface">Cancel</button>
        </div>
      </div>}
      <div className="flex items-center justify-between gap-2 mt-3">
        <span className="text-xs text-on-surface-variant" role="status">{busy ? 'Sending scenario request…' : 'Select a scenario to update live traffic'}</span>
        <button type="button" onClick={() => void refresh()} className="min-h-10 px-2 text-xs text-primary underline underline-offset-2">Refresh status</button>
      </div>
    </div>}
    {error && <p role="alert" className="px-5 pb-4 text-sm text-error">{error}</p>}
  </section>;
}
