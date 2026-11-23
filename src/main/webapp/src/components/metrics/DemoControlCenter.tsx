// SPDX-License-Identifier: AGPL-3.0-or-later
import { useCallback, useEffect, useState } from 'react';
import axios from 'axios';
import type { ForecastStatus } from '../../api/types';
import { type Scenario, type DemoScenarioEvent, SCENARIOS, scenarioFor, previewPath } from './demoScenarios';

type Phase = 'ACTIVE' | 'APPLYING' | 'STALE';
type DemoState = {
  enabled: boolean;
  scenario: Scenario | null;
  requestedScenario: Scenario;
  phase: Phase;
  appliedAt: number | null;
  startedAt: number | null;
};
type View = 'loading' | 'hidden' | 'ready' | 'error';

function ScenarioPreview({ scenario }: { scenario: Scenario }) {
  const option = scenarioFor(scenario);
  return <svg className="mt-2 h-11 w-full" viewBox="0 0 152 44" preserveAspectRatio="none"
    role="img" aria-label={`Illustrative ${option.label.toLowerCase()} traffic shape, not live data`}>
    <path d="M0 40 H152" stroke="currentColor" strokeOpacity={0.12} fill="none" />
    <path d={previewPath(option.preview)} fill="none" stroke="currentColor" strokeWidth="2.5"
      strokeLinecap="round" strokeLinejoin="round" vectorEffect="non-scaling-stroke" />
  </svg>;
}

/** Opt-in controller: 404 means absent by design, not an error banner on normal installations. */
export function DemoControlCenter({ metricCount, metricsLoading, forecasts, forecastsQueried, onConfirmed, onInspectMetrics }: {
  metricCount: number;
  metricsLoading: boolean;
  forecasts: ForecastStatus | null;
  forecastsQueried: boolean;
  onConfirmed?: (event: DemoScenarioEvent) => void;
  onInspectMetrics?: (metricIds: string[]) => void;
}) {
  const [view, setView] = useState<View>('loading');
  const [state, setState] = useState<DemoState | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  const [expanded, setExpanded] = useState(false);
  const [candidate, setCandidate] = useState<Scenario | null>(null);
  const [guided, setGuided] = useState(false);

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

  // A heartbeat updates appliedAt every tick; startedAt remains constant across ticks.
  // Emit once per genuinely confirmed scenario, never on merely requested state.
  useEffect(() => {
    if (view !== 'ready' || state?.phase !== 'ACTIVE' || !state.startedAt || !state.scenario) return;
    const event: DemoScenarioEvent = { scenario: state.scenario, at: state.startedAt,
      metricIds: scenarioFor(state.scenario).metricIds };
    void Promise.resolve().then(() => onConfirmed?.(event));
  }, [view, state?.phase, state?.scenario, state?.startedAt, onConfirmed]);

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
  const activeOption = SCENARIOS.find(s => s.id === state.scenario);
  const inspectIds = activeOption?.metricIds ?? scenarioFor(state.requestedScenario).metricIds;
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

  function startGuide() {
    setGuided(true);
    setExpanded(true);
    if (state?.requestedScenario !== 'normal') void choose('normal');
  }
  function selectScenario(scenario: Scenario) {
    const option = SCENARIOS.find(s => s.id === scenario);
    if (!option || busy || scenario === state?.requestedScenario) return;
    if (option.disruptive) setCandidate(scenario);
    else void choose(scenario); // Normal and Recovery stay one-click escape routes.
  }

  return <section aria-label="Forecast demo control center"
    className="kse-surface relative overflow-hidden rounded-2xl border border-outline-variant/60 bg-surface-container-low/80 shadow-sm">
    <div className="flex flex-wrap items-center justify-between gap-3 p-4 sm:p-5">
      <div className="flex items-center gap-3 min-w-0">
        <span className="material-symbols-outlined text-primary" aria-hidden="true">tune</span>
        <div><h2 className="text-base font-semibold text-on-surface">Forecast Demo Control Center</h2>
          <p className="text-xs text-on-surface-variant">Local demo · Kafka-verified scenario control</p></div>
      </div>
      <div className="flex flex-wrap items-center gap-2">
        <button type="button" onClick={exportReport}
          className="min-h-11 rounded-xl border border-outline-variant px-3 py-2 text-sm text-on-surface hover:bg-primary/10 focus-visible:outline-2 focus-visible:outline-primary motion-safe:transition-colors">
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
    <div className="grid grid-cols-1 min-[375px]:grid-cols-2 lg:grid-cols-4 gap-2 px-4 pb-4 sm:px-5 text-sm">
      <div className="rounded-xl bg-surface-container/70 p-3"><p className="text-xs text-on-surface-variant">Demo metrics</p>
        <strong className="tabular-nums">{metricsLoading ? 'Loading…' : `${metricCount} / 5`}</strong></div>
      <div className="rounded-xl bg-surface-container/70 p-3"><p className="text-xs text-on-surface-variant">Forecast series</p>
        <strong className="tabular-nums">{forecastNumber}</strong></div>
      <div className="rounded-xl bg-surface-container/70 p-3"><p className="text-xs text-on-surface-variant">Ready forecasts</p>
        <strong className="tabular-nums">{readyNumber}</strong></div>
      <div className="rounded-xl bg-surface-container/70 p-3"><p className="text-xs text-on-surface-variant">Confirmed scenario</p>
        <strong className="capitalize">{state.scenario ?? 'Not verified'}</strong></div>
    </div>
    <div className="flex flex-wrap gap-2 items-center justify-between border-t border-outline-variant/60 px-4 py-3 sm:px-5">
      <div role="status" aria-live="polite" className="flex flex-wrap gap-2 items-center text-xs text-on-surface-variant">
        <span aria-hidden="true" className={`h-2 w-2 rounded-full ${active ? 'bg-success' : state.phase === 'APPLYING' ? 'bg-warning' : 'bg-error'}`} />
        <span>{phaseText}</span>
        {!active && <span>Requested: <strong className="text-on-surface">{state.requestedScenario}</strong></span>}
        {appliedTime && <span>Last verified: {appliedTime}</span>}
      </div>
      <div className="flex flex-wrap gap-2">
        <button type="button" onClick={() => onInspectMetrics?.(inspectIds)}
          className="min-h-11 rounded-xl px-3 py-2 text-xs font-semibold text-primary hover:bg-primary/10 focus-visible:outline-2 focus-visible:outline-primary">
          View impacted metrics
        </button>
      {state.requestedScenario !== 'normal' && <button type="button" disabled={busy}
        onClick={() => void choose('normal')}
        className="min-h-10 rounded-lg border border-outline-variant px-3 py-2 text-xs font-medium text-primary hover:bg-primary/10 disabled:opacity-50">
        Return to normal
      </button>}</div>
    </div>
    <div className="flex flex-wrap justify-between gap-2 border-t border-outline-variant/60 px-4 py-3 sm:px-5">
      <div className="min-w-0">
        <p className="text-sm font-semibold text-on-surface">Guided demonstration</p>
        <p className="text-xs text-on-surface-variant">Baseline → incident → recovery. Every step uses real Kafka traffic.</p>
      </div>
      <button type="button" onClick={() => guided ? setGuided(false) : startGuide()}
        aria-expanded={guided} className="min-h-11 rounded-xl border border-outline-variant px-3 py-2 text-sm text-primary hover:bg-primary/10 focus-visible:outline-2 focus-visible:outline-primary">
        {guided ? 'Exit guide' : 'Start guide'}
      </button>
    </div>
    {guided && <div className="border-t border-outline-variant/60 px-4 py-4 sm:px-5">
      <ol className="grid gap-3 sm:grid-cols-3" aria-label="Three demo steps">
        <li className="min-w-0 rounded-xl bg-surface-container/70 p-3">
          <p className="text-xs font-semibold text-primary">01 · Baseline</p>
          <p className="mt-1 text-xs text-on-surface-variant">Observe nominal traffic and the current prediction.</p>
          <button type="button" onClick={() => { void choose('normal'); onInspectMetrics?.(scenarioFor('normal').metricIds); }}
            className="mt-2 min-h-11 rounded-lg border border-outline-variant px-3 text-xs text-primary focus-visible:outline-2 focus-visible:outline-primary">
            Show baseline</button>
        </li>
        <li className="min-w-0 rounded-xl bg-surface-container/70 p-3">
          <p className="text-xs font-semibold text-warning">02 · Incident</p>
          <p className="mt-1 text-xs text-on-surface-variant">Apply an incident after reviewing its impact.</p>
          <button type="button" onClick={() => { setCandidate('incident'); setExpanded(true); }}
            disabled={state.phase !== 'ACTIVE' || state.scenario !== 'normal' || busy}
            className="mt-2 min-h-11 rounded-lg border border-outline-variant px-3 text-xs text-primary disabled:opacity-40 focus-visible:outline-2 focus-visible:outline-primary">
            Trigger incident</button>
        </li>
        <li className="min-w-0 rounded-xl bg-surface-container/70 p-3">
          <p className="text-xs font-semibold text-primary">03 · Recovery</p>
          <p className="mt-1 text-xs text-on-surface-variant">Watch measured traffic return to a stable baseline.</p>
          <button type="button" onClick={() => void choose('recover')}
            disabled={state.phase !== 'ACTIVE' || state.scenario !== 'incident' || busy}
            className="mt-2 min-h-11 rounded-lg border border-outline-variant px-3 text-xs text-primary disabled:opacity-40 focus-visible:outline-2 focus-visible:outline-primary">
            Recover traffic</button>
        </li>
      </ol>
      <p className="mt-3 text-xs text-on-surface-variant" role="status">
        Current step: {state.phase !== 'ACTIVE' ? 'Waiting for Kafka confirmation'
          : state.scenario === 'incident' ? 'Incident active — compare observed vs predicted'
            : state.scenario === 'recover' ? 'Recovery active — inspect the metrics' : 'Baseline'}
      </p>
    </div>}
    {expanded && <div className="border-t border-outline-variant/60 px-4 py-4 sm:px-5">
      <p className="text-xs text-on-surface-variant mb-3">Select a scenario. Changes reach Kafka on the next traffic tick; the confirmation waits for broker offsets.</p>
      <div className="grid grid-cols-1 min-[440px]:grid-cols-2 md:grid-cols-3 xl:grid-cols-5 gap-2">
        {SCENARIOS.map(s => <button type="button" key={s.id} disabled={busy || s.id === state.requestedScenario}
          aria-pressed={active && state.scenario === s.id} onClick={() => selectScenario(s.id)}
          className={`min-h-32 text-left rounded-xl border bg-surface-container-low/60 p-3 motion-safe:transition-[background-color,border-color,box-shadow] disabled:opacity-60 focus-visible:outline-2 focus-visible:outline-primary ${state.requestedScenario === s.id ? 'border-primary bg-primary/10' : 'border-outline-variant hover:bg-primary/5'}`}>
          <span className="material-symbols-outlined text-xl text-primary" aria-hidden="true">{s.icon}</span>
          <span className="block text-sm font-medium text-on-surface">{s.label}</span>
          <span className="block text-xs text-on-surface-variant mt-1">{s.detail}</span>
          <ScenarioPreview scenario={s.id} />
          <span className="block mt-1 text-[10px] text-on-surface-variant">Illustrative pattern</span>
        </button>)}
      </div>
      {onInspectMetrics && <p className="mt-3 text-xs text-on-surface-variant">
        The chart previews are illustrative. Use <strong>View impacted metrics</strong> for live observed data.
      </p>}
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
