// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors
import { useEffect, useRef, useState } from 'react';
import { flushSync } from 'react-dom';
import axios from 'axios';
import { Badge, Button, Checkbox, Field, Input, Select } from '../ui';
import type { ForecastApplied, ForecastCandidate, ForecastCandidates, ForecastDraft, ForecastDraftRequest } from '../../api/types';
import { copyText } from '../../clipboard';
import { NameListField } from './NameListField';
import { about } from './forecastStage';

/** Every option keeps 513 buckets inside the default 30-day history retention the server checks. */
const CADENCES = [{ value: '30000', label: '30 seconds' }, { value: '60000', label: '1 minute' },
  { value: '300000', label: '5 minutes' }, { value: '900000', label: '15 minutes' }];
/** Fields with a sound default, folded away; one that fails validation opens the fold. */
const ADVANCED = new Set(['horizon', 'threshold', 'clusterId', 'collectorId']);
/** The server computes the season from the same rule: a whole number of samples, 2 to 512. */
const SEASONALITIES = [{ value: 'NONE', label: 'None', period: 0 }, { value: 'HOURLY', label: 'Hourly', period: 3600000 },
  { value: 'DAILY', label: 'Daily', period: 86400000 }];
const seasonFits = (period: number, cadence: number) =>
  period === 0 || (period % cadence === 0 && period / cadence >= 2 && period / cadence <= 512);
/** The server's suggestion when the assistant offers it, the minute otherwise. */
const cadenceFor = (c: ForecastCandidate | null) =>
  CADENCES.some(o => Number(o.value) === c?.suggestedStepMillis) ? String(c!.suggestedStepMillis) : '60000';
/** The pilot's terms stay in the API; the page says what the forecast value is. */
const TRANSFORMATION: Record<string, string> = { COUNTER_RATE: 'Rate per second', GAUGE_MEAN: 'Average per interval' };
function span(millis: number) {
  if (!Number.isFinite(millis) || millis <= 0) return '';
  const minutes = millis / 60000;
  return minutes < 120 ? `${+minutes.toFixed(1)} minutes` : `${+(minutes / 60).toFixed(1)} hours`;
}
export function ForecastSetupWizard({ initialMetricId, onApplied, forecastMetricIds = [] }: {
  initialMetricId?: string;
  /** Receives the series that was started, so the page can show it rather than whichever is first. */
  onApplied?: (seriesId: string) => void;
  /**
   * Metrics that already have a series (METRICS-FORECAST-UX-AUDIT.md B6): offered as configurable,
   * they were refused only after the attestation and the submit, with a pointer to a deployment
   * file that a series started from here does not have.
   */
  forecastMetricIds?: string[];
}) {
  const [candidates, setCandidates] = useState<ForecastCandidates | null>(null);
  const [selected, setSelected] = useState<ForecastCandidate | null>(null);
  const [environment, setEnvironment] = useState('local');
  const [clusterId, setClusterId] = useState('local');
  const [collectorId, setCollectorId] = useState('kafkaexplorer');
  const [unit, setUnit] = useState('');
  const [topics, setTopics] = useState<string[]>([]);
  const [groups, setGroups] = useState<string[]>([]);
  const [copied, setCopied] = useState(false);
  const [cadence, setCadence] = useState('60000');
  const [horizon, setHorizon] = useState('30');
  const [seasonality, setSeasonality] = useState('NONE');
  const [threshold, setThreshold] = useState('');
  const [direction, setDirection] = useState('ABOVE');
  const [approved, setApproved] = useState(false);
  const [draft, setDraft] = useState<ForecastDraft | null>(null);
  const [applied, setApplied] = useState<ForecastApplied | null>(null);
  const [error, setError] = useState('');
  const [errors, setErrors] = useState<Record<string, string>>({});
  const [busy, setBusy] = useState(false);
  const [advanced, setAdvanced] = useState(false);
  const abort = useRef<AbortController | null>(null);
  const canApply = candidates?.applyUnavailable === null;
  function take(c: ForecastCandidate | null) {
    setSelected(c); setUnit(c?.unit ?? ''); setTopics(c?.topics ?? []); setGroups(c?.groups ?? []);
    const next = cadenceFor(c); setCadence(next);
    setSeasonality(cycle => seasonFits(SEASONALITIES.find(o => o.value === cycle)?.period ?? 0, Number(next)) ? cycle : 'NONE');
  }
  useEffect(() => {
    const controller = new AbortController(); abort.current = controller;
    axios.get<ForecastCandidates>('/api/forecasts/candidates', { signal: controller.signal, timeout: 10000 })
      .then(({ data }) => {
        if (!controller.signal.aborted) {
          if (!Array.isArray(data?.metrics)) { setError('Candidate catalogue unavailable.'); return; }
          setCandidates(data);
          if (initialMetricId) {
            const candidate = data.metrics.find(m => m.metricId === initialMetricId);
            // Opened from a card, the metric is chosen already and the form is under it at once
            // (METRICS-FORECAST-UX-AUDIT.md U4). A metric with a blocker shows the blocker instead.
            if (candidate) take(candidate);
            else setError('Selected metric is absent from the candidate catalogue. Reload or choose a listed metric.');
          }
          if (data.clusterId) setClusterId(data.clusterId);
          if (data.collectorId) setCollectorId(data.collectorId);
        }
      }).catch(() => { if (!controller.signal.aborted) setError('Candidate catalogue unavailable.'); });
    return () => controller.abort();
  }, [initialMetricId]);
  function choose(metricId: string) {
    take(candidates?.metrics.find(m => m.metricId === metricId) ?? null);
    setDraft(null); setApplied(null); setApproved(false); setCopied(false);
  }
  function valid() {
    const next: Record<string, string> = {};
    for (const [key, value] of Object.entries({ environment, clusterId, collectorId, unit }))
      if (!value.trim() || value.length > 128 || [...value].some(char => char.charCodeAt(0) < 32 || char.charCodeAt(0) === 127)) next[key] = 'Use 1–128 printable characters.';
    if (!topics.length) next.topics = 'Add every source topic.';
    // The server refuses a declaration that drops a topic or group the template reads, with a
    // page-level 400 that names no field: caught here, on the field, with the names to put back.
    const missingTopics = (selected?.topics ?? []).filter(t => !topics.includes(t));
    if (topics.length && missingTopics.length) next.topics = `Keep every topic the metric reads: ${missingTopics.join(', ')}.`;
    const missingGroups = (selected?.groups ?? []).filter(g => !groups.includes(g));
    if (missingGroups.length) next.groups = `Keep every consumer group the metric reads: ${missingGroups.join(', ')}.`;
    if (!Number.isInteger(Number(horizon)) || Number(horizon) < 1 || Number(horizon) > 60) next.horizon = 'Use 1–60 forecast points.';
    if (threshold.trim() && !Number.isFinite(Number(threshold))) next.threshold = 'Use a finite threshold.';
    setErrors(next);
    const first = Object.keys(next)[0];
    if (!first) return true;
    // A field inside a closed <details> cannot take focus: open it before focusing.
    if (ADVANCED.has(first)) flushSync(() => setAdvanced(true));
    document.getElementById(`forecast-${first}`)?.focus();
    return false;
  }
  async function submit(mode: 'apply' | 'export') {
    // The attestation checkbox is the confirmation; a dialog asking the same question again added
    // a click and no information.
    if (!selected || !approved || busy || abort.current?.signal.aborted || !valid()) return;
    setBusy(true); setError('');
    const request: ForecastDraftRequest = { metricId: selected.metricId, definitionVersion: selected.definitionVersion,
      environment: environment.trim(), unit, clusterId: clusterId.trim(), collectorId: collectorId.trim(),
      topics, groups, stepMillis: Number(cadence), horizon: Number(horizon),
      threshold: threshold.trim() ? Number(threshold) : null, direction, confirmed: true, seasonality };
    const options = { signal: abort.current?.signal, timeout: 10000 };
    try {
      if (mode === 'apply') {
        const { data } = await axios.post<ForecastApplied>('/api/forecasts/series', request, options);
        if (typeof data?.seriesId !== 'string') throw new Error('Invalid approval response');
        if (!abort.current?.signal.aborted) { setApplied(data); onApplied?.(data.seriesId); }
      } else {
        const { data } = await axios.post<ForecastDraft>('/api/forecasts/configuration', request, options);
        if (typeof data?.configuration !== 'string' || !Array.isArray(data.instructions)) throw new Error('Invalid configuration response');
        if (!abort.current?.signal.aborted) setDraft(data);
      }
    } catch (e) {
      if (!abort.current?.signal.aborted) setError(axios.isAxiosError(e) && typeof e.response?.data?.reason === 'string'
        ? e.response.data.reason : 'Configuration validation failed. Reload the catalogue if the metric changed.');
    } finally { if (!abort.current?.signal.aborted) setBusy(false); }
  }
  async function copyConfiguration() {
    if (!draft) return;
    if (await copyText(draft.configuration)) setCopied(true);
    else setError('Copy unavailable in this browser; download the file instead.');
  }
  function download() {
    if (!draft) return;
    const url = URL.createObjectURL(new Blob([draft.configuration], { type: 'application/yaml' }));
    const link = document.createElement('a'); link.href = url; link.download = 'forecasts.yml'; link.click(); URL.revokeObjectURL(url);
  }
  const cadenceLabel = CADENCES.find(c => c.value === cadence)?.label ?? `${Number(cadence) / 1000} seconds`;
  const done = Boolean(draft || applied);
  // The flow ended: the form and its Back button are gone, and the only way to configure another
  // metric was to close the assistant and reopen it.
  function startOver() {
    take(null); setDraft(null); setApplied(null); setApproved(false); setCopied(false);
    setError(''); setErrors({}); setAdvanced(false);
  }
  const seasonPoints = (SEASONALITIES.find(o => o.value === seasonality)?.period ?? 0) / Number(cadence);
  // A first forecast waits for the floor, or for one whole cycle when that is longer — the server's rule.
  const firstPoints = Number.isFinite(candidates?.minimumContextPoints) ? Math.max(candidates!.minimumContextPoints, seasonPoints) : null;
  const firstSpan = firstPoints === null ? '' : `${about(firstPoints, Number(cadence))} of history`;
  const ahead = span(Number(cadence) * Number(horizon));
  /*
   * One screen (it was two steps): the metric, then what has no sound default — the environment,
   * the sources, the attestation. Choosing a metric is what reveals the rest, so "Continue to
   * sources" and "Back" asked for a click that decided nothing, and Back was drawn as the primary
   * action beside a disabled Start.
   */
  return <div className="card p-5 space-y-4" aria-label="Forecast configuration assistant">
    {done && <h4 className="font-semibold">{applied ? 'Forecast started' : 'Configuration ready'}</h4>}
    {error && <p role="alert" className="text-sm text-error">{error}</p>}
    {!candidates && !error && <p className="text-sm text-on-surface-variant">Loading candidate metrics…</p>}
    {candidates && !done && <>
      <div className="grid gap-3 sm:grid-cols-[minmax(0,2fr)_minmax(0,1fr)]">
        <Field label="Metric" description={candidates.truncated ? `Showing the first 100 of ${candidates.total} metrics.` : undefined}>
          {p => <Select {...p} value={selected?.metricId ?? ''} onChange={e => choose(e.target.value)}>
            <option value="">Choose a metric</option>{candidates.metrics.map(c => {
              const forecast = forecastMetricIds.includes(c.metricId);
              // Only what stands in the way is said: "configurable" on every other line was noise.
              const note = forecast ? 'already forecast' : c.eligible ? '' : c.blockers[0] ?? 'not configurable';
              return <option key={c.metricId} value={c.metricId} disabled={forecast}>{c.name || c.metricId}{note && ` · ${note}`}</option>;
            })}
          </Select>}</Field>
        {selected?.eligible && <Field id="forecast-environment" label="Environment" error={errors.environment}>
          {p => <Input {...p} form="forecast-setup" value={environment} onChange={e => setEnvironment(e.target.value)} />}</Field>}
      </div>
      {selected && !selected.eligible && <div role="group" aria-label="What blocks this metric"
        className="rounded-lg border border-warning/40 bg-warning/5 p-3 text-sm space-y-1">
        <p className="font-medium">This metric cannot be forecast yet.</p>
        <ul className="list-disc pl-5">{selected.blockers.map(b => <li key={b}>{b}</li>)}</ul>
      </div>}
    </>}
    {candidates && selected?.eligible && !done && <form id="forecast-setup" onSubmit={e => { e.preventDefault(); void submit(canApply ? 'apply' : 'export'); }} className="space-y-4">
      {/* What the defaults are, without opening the fold that changes them. */}
      <dl className="grid grid-cols-2 lg:grid-cols-4 gap-px overflow-hidden rounded-lg border border-outline-variant bg-outline-variant text-sm" aria-label="Forecast summary">
        {[['Forecast value', TRANSFORMATION[selected.transformation] ?? selected.transformation, selected.transformation === 'COUNTER_RATE' ? `${unit} per second` : unit],
          ['One point every', cadenceLabel, seasonality === 'NONE' ? 'No repeating cycle' : `${SEASONALITIES.find(o => o.value === seasonality)?.label} cycle`],
          ['Looks ahead', ahead || '—', `${horizon} points`],
          ['First forecast after', firstPoints === null ? '—' : `${firstPoints} points`,
            firstSpan && `${firstSpan}${selected.enrolled ? '; what is already recorded counts' : ', recorded from now'}`],
        ].map(([label, value, hint]) => <div key={label} className="bg-surface-container px-3 py-2">
          <dt className="text-[11px] uppercase tracking-wide text-on-surface-variant">{label}</dt>
          <dd className="font-medium tabular-nums">{value}</dd>
          {hint && <dd className="text-xs text-on-surface-variant">{hint}</dd>}
        </div>)}
      </dl>
      <fieldset className="space-y-2">
        <legend className="text-sm font-medium">Sources this metric reads</legend>
        <p className="text-xs text-on-surface-variant">Prefilled from the metric's template. Check them against its SQL: a source left out is a dependency the forecast will not know about.</p>
        <div className="grid gap-3 md:grid-cols-2 items-start">
          <NameListField id="forecast-topics" label="All source topics" names={topics} onChange={setTopics} topics error={errors.topics} />
          <NameListField id="forecast-groups" label="All consumer groups (optional)" names={groups} onChange={setGroups} error={errors.groups} />
        </div>
      </fieldset>
      <details open={advanced} onToggle={e => setAdvanced(e.currentTarget.open)} className="text-sm">
        <summary className="cursor-pointer text-on-surface-variant">Advanced: sampling, horizon, threshold and history identities</summary>
        <div className="grid gap-3 sm:grid-cols-2 lg:grid-cols-4 pt-3">
          <Field id="forecast-cadence" label="Sampling interval" description="Suggested from how often the metric is collected.">{p => <Select {...p} value={cadence} onChange={e => {
            setCadence(e.target.value);
            const period = SEASONALITIES.find(o => o.value === seasonality)?.period ?? 0;
            if (!seasonFits(period, Number(e.target.value))) setSeasonality('NONE');
          }}>
            {CADENCES.map(c => <option key={c.value} value={c.value}>{c.label}</option>)}</Select>}</Field>
          <Field id="forecast-horizon" label="Forecast horizon (points)" error={errors.horizon}
            description={ahead && `Forecasts ${ahead} ahead`}>{p => <Input {...p} inputMode="numeric" value={horizon} onChange={e => setHorizon(e.target.value)} />}</Field>
          <Field id="forecast-seasonality" label="Repeating cycle"
            description="Gives the forecast a seasonal baseline to beat, and a seasonal fallback.">{p => <Select {...p} value={seasonality} onChange={e => setSeasonality(e.target.value)}>
            {SEASONALITIES.filter(o => seasonFits(o.period, Number(cadence))).map(o => <option key={o.value} value={o.value}>{o.label}</option>)}</Select>}</Field>
          <Field id="forecast-unit" label="Captured unit" error={errors.unit} description="Set on the metric itself.">{p => <Input {...p} value={unit} readOnly />}</Field>
          <Field id="forecast-threshold" label="Threshold (optional)" error={errors.threshold}
            description={`In ${selected.transformation === 'COUNTER_RATE' ? `${unit} per second` : unit}; drawn on the chart.`}>{p => <Input {...p} inputMode="decimal" value={threshold} onChange={e => setThreshold(e.target.value)} />}</Field>
          <Field label="Threshold direction">{p => <Select {...p} value={direction} onChange={e => setDirection(e.target.value)}><option value="ABOVE">Above</option><option value="BELOW">Below</option></Select>}</Field>
          <Field id="forecast-clusterId" label="History cluster ID" error={errors.clusterId} description="Prefilled from the running collector.">{p => <Input {...p} value={clusterId} onChange={e => setClusterId(e.target.value)} />}</Field>
          <Field id="forecast-collectorId" label="History collector ID" error={errors.collectorId}>{p => <Input {...p} value={collectorId} onChange={e => setCollectorId(e.target.value)} />}</Field>
        </div>
      </details>
      <label className="flex gap-2 items-center text-sm"><Checkbox checked={approved} onChange={setApproved} />I confirm these are all the topics and consumer groups this metric reads.</label>
      <div className="flex flex-wrap items-center gap-2">
        <Button type="submit" disabled={!approved || busy} loading={busy}>{canApply ? 'Start forecasting' : 'Validate and export'}</Button>
        {canApply && <Button variant="ghost" disabled={!approved || busy} onClick={() => void submit('export')}>Export YAML instead</Button>}
        <Badge title="SHADOW">Starts in observation</Badge>
      </div>
      <p className="text-xs text-on-surface-variant">{canApply
        ? 'Starts directly, from the history already collected: no file, no restart. Nothing is alerted or sent, and you approve it once its quality checks pass.'
        : <>Exports the selected series while preserving existing approvals. Review and merge the file before restarting. {candidates.applyUnavailable}</>}</p>
    </form>}
    {applied && <div role="status" className="space-y-2 text-sm">
      <p className="font-semibold">Forecast started for {selected?.name || selected?.metricId}.</p>
      <p>It is now the series shown in Metrics Forecast below. {firstSpan ? `Its first forecast follows after ${firstPoints} points, ${firstSpan}` : 'Its first forecast follows once enough points exist'}; then quality is judged over realised values before approval is offered.</p>
      <Button variant="secondary" onClick={startOver}>Configure another metric</Button>
    </div>}
    {draft && <>
      <h5 className="font-semibold">Next steps</h5>
      <ol className="list-decimal pl-5 space-y-1 text-sm">
        <li>Download or copy the configuration below.</li>
        {draft.instructions.map(i => <li key={i}>{i}</li>)}
        <li>Come back to Metrics Forecast: history progress appears here once the restarted pilot collects.</li>
      </ol>
      <pre className="overflow-auto max-h-80 text-xs rounded-lg bg-surface-container p-3">{draft.configuration}</pre>
      <div className="flex flex-wrap gap-2">
        <Button onClick={download}>Download forecasts.yml</Button>
        <Button variant="secondary" onClick={() => void copyConfiguration()}>{copied ? 'Configuration copied' : 'Copy configuration'}</Button>
        <Button variant="ghost" onClick={startOver}>Configure another metric</Button>
      </div>
    </>}
  </div>;
}
