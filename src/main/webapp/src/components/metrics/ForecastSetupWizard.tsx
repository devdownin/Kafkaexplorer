// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors
import { useEffect, useRef, useState } from 'react';
import { flushSync } from 'react-dom';
import axios from 'axios';
import { Button, Checkbox, Field, Input, Select } from '../ui';
import type { ForecastApplied, ForecastCandidate, ForecastCandidates, ForecastDraft, ForecastDraftRequest } from '../../api/types';
import { copyText } from '../../clipboard';
import { NameListField } from './NameListField';

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
function span(millis: number) {
  if (!Number.isFinite(millis) || millis <= 0) return '';
  const minutes = millis / 60000;
  return minutes < 120 ? `${+minutes.toFixed(1)} minutes` : `${+(minutes / 60).toFixed(1)} hours`;
}
export function ForecastSetupWizard({ initialMetricId, onApplied }: { initialMetricId?: string; onApplied?: () => void }) {
  const [candidates, setCandidates] = useState<ForecastCandidates | null>(null);
  const [selected, setSelected] = useState<ForecastCandidate | null>(null);
  const [step, setStep] = useState(1);
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
        if (!abort.current?.signal.aborted) { setApplied(data); onApplied?.(); }
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
  return <div className="border border-outline-variant rounded-md p-4 space-y-3" aria-label="Forecast configuration assistant">
    <h4 className="font-semibold">Step {step} of 2 · {step === 1 ? 'Choose a metric' : canApply ? 'Confirm and start' : 'Confirm and export'}</h4>
    <p className="text-sm">{canApply ? 'Starts the forecast directly, from the history already collected; no file and no restart.'
      : 'Exports the selected series while preserving existing approvals. Review and merge the file before restarting.'}</p>
    {error && <p role="alert">{error}</p>}
    {step === 1 && <>
      {!candidates && !error && <p>Loading candidate metrics…</p>}
      {candidates && <>
        <p>{candidates.total} metric(s){candidates.truncated ? '; showing the first 100' : ''}. Eligibility describes metadata; history and quality are checked separately.</p>
        <Field label="Candidate metric">{p => <Select {...p} value={selected?.metricId ?? ''} onChange={e => choose(e.target.value)}>
          <option value="">Choose a metric</option>{candidates.metrics.map(c => <option key={c.metricId} value={c.metricId}>{c.name || c.metricId} · {c.eligible ? 'configurable' : 'metadata missing'}</option>)}
        </Select>}</Field>
        {selected && <>
          <p>Unit: {selected.unit} · {selected.transformation} · {selected.enrolled ? 'History recorded' : 'History not recorded yet'}</p>
          <ul>{selected.blockers.map(b => <li key={b}>{b}</li>)}</ul>
          <Button disabled={!selected.eligible} onClick={() => setStep(2)}>Continue to sources</Button>
        </>}
      </>}
    </>}
    {step === 2 && !done && <form onSubmit={e => { e.preventDefault(); void submit(canApply ? 'apply' : 'export'); }} className="space-y-3">
      <p>{selected?.name || selected?.metricId} · {unit} · {selected?.transformation}. Forecasts {span(Number(cadence) * Number(horizon))} ahead from one sample every {cadenceLabel}; SHADOW first, no alert sent.</p>
      <Field id="forecast-environment" label="Environment" error={errors.environment}>{p => <Input {...p} value={environment} onChange={e => setEnvironment(e.target.value)} />}</Field>
      <NameListField id="forecast-topics" label="All source topics" names={topics} onChange={setTopics} topics error={errors.topics}
        description="Verify every SQL dependency; suggestions are not a complete provenance attestation." />
      <NameListField id="forecast-groups" label="All consumer groups (optional)" names={groups} onChange={setGroups} />
      <details open={advanced} onToggle={e => setAdvanced(e.currentTarget.open)}>
        <summary>Advanced: sampling, horizon, threshold and history identities</summary>
        <div className="grid gap-3 sm:grid-cols-2 pt-2">
          <Field id="forecast-unit" label="Captured unit" error={errors.unit} description="Change the metric metadata first to change its unit.">{p => <Input {...p} value={unit} readOnly />}</Field>
          <Field id="forecast-cadence" label="Sampling interval" description="Suggested from how often the metric is collected.">{p => <Select {...p} value={cadence} onChange={e => {
            setCadence(e.target.value);
            const period = SEASONALITIES.find(o => o.value === seasonality)?.period ?? 0;
            if (!seasonFits(period, Number(e.target.value))) setSeasonality('NONE');
          }}>
            {CADENCES.map(c => <option key={c.value} value={c.value}>{c.label}</option>)}</Select>}</Field>
          <Field id="forecast-seasonality" label="Repeating cycle"
            description="A daily or hourly pattern gives the forecast a seasonal baseline to beat and a seasonal fallback.">{p => <Select {...p} value={seasonality} onChange={e => setSeasonality(e.target.value)}>
            {SEASONALITIES.filter(o => seasonFits(o.period, Number(cadence))).map(o => <option key={o.value} value={o.value}>{o.label}</option>)}</Select>}</Field>
          <Field id="forecast-horizon" label="Forecast horizon (points)" error={errors.horizon}
            description={span(Number(cadence) * Number(horizon)) && `Forecasts ${span(Number(cadence) * Number(horizon))} ahead`}>{p => <Input {...p} inputMode="numeric" value={horizon} onChange={e => setHorizon(e.target.value)} />}</Field>
          <Field id="forecast-threshold" label="Optional threshold in forecast output units" error={errors.threshold} description={selected?.transformation === 'COUNTER_RATE' ? `${unit} per second` : unit}>{p => <Input {...p} inputMode="decimal" value={threshold} onChange={e => setThreshold(e.target.value)} />}</Field>
          <Field label="Threshold direction">{p => <Select {...p} value={direction} onChange={e => setDirection(e.target.value)}><option value="ABOVE">Above</option><option value="BELOW">Below</option></Select>}</Field>
          <Field id="forecast-clusterId" label="History cluster ID" error={errors.clusterId} description="Prefilled from the running collector.">{p => <Input {...p} value={clusterId} onChange={e => setClusterId(e.target.value)} />}</Field>
          <Field id="forecast-collectorId" label="History collector ID" error={errors.collectorId}>{p => <Input {...p} value={collectorId} onChange={e => setCollectorId(e.target.value)} />}</Field>
        </div>
      </details>
      <label className="flex gap-2 items-start"><Checkbox checked={approved} onChange={setApproved} />I reviewed the semantics and confirm every source topic and consumer group.</label>
      <div className="flex flex-wrap gap-2">
        <Button onClick={() => { setStep(1); setApproved(false); }}>Back</Button>
        <Button type="submit" disabled={!approved || busy}>{canApply ? 'Start forecasting' : 'Validate and export'}</Button>
        {canApply && <Button variant="ghost" disabled={!approved || busy} onClick={() => void submit('export')}>Export YAML instead</Button>}
      </div>
    </form>}
    {applied && <div role="status" className="space-y-1">
      <p className="font-semibold">Forecast started for {selected?.name || selected?.metricId}.</p>
      <p>It reads the history already collected: the first forecast follows once enough points exist, then quality is judged over realised values before approval is offered.</p>
    </div>}
    {draft && <>
      <h5 className="font-semibold">Next steps</h5>
      <ol className="list-decimal pl-5 space-y-1">
        <li>Download or copy the configuration below.</li>
        {draft.instructions.map(i => <li key={i}>{i}</li>)}
        <li>Come back to Metrics Forecast: history progress appears here once the restarted pilot collects.</li>
      </ol>
      <pre className="overflow-auto max-h-80 text-xs">{draft.configuration}</pre>
      <div className="flex flex-wrap gap-2">
        <Button onClick={download}>Download forecasts.yml</Button>
        <Button variant="ghost" onClick={() => void copyConfiguration()}>{copied ? 'Configuration copied' : 'Copy configuration'}</Button>
      </div>
    </>}
  </div>;
}
