// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors
import { useEffect, useRef, useState } from 'react';
import { flushSync } from 'react-dom';
import axios from 'axios';
import { Button, Checkbox, Field, Input, Select } from '../ui';
import type { ForecastCandidate, ForecastCandidates, ForecastDraft, ForecastDraftRequest } from '../../api/types';

const split = (text: string) => text.split(',').map(s => s.trim()).filter(Boolean);
/** Every option keeps 513 buckets inside the default 30-day history retention the server checks. */
const CADENCES = [{ value: '30000', label: '30 seconds' }, { value: '60000', label: '1 minute' },
  { value: '300000', label: '5 minutes' }, { value: '900000', label: '15 minutes' }];
const ADVANCED = new Set(['clusterId', 'collectorId']);
function span(millis: number) {
  if (!Number.isFinite(millis) || millis <= 0) return '';
  const minutes = millis / 60000;
  return minutes < 120 ? `${+minutes.toFixed(1)} minutes` : `${+(minutes / 60).toFixed(1)} hours`;
}
export function ForecastSetupWizard({ initialMetricId }: { initialMetricId?: string }) {
  const [candidates, setCandidates] = useState<ForecastCandidates | null>(null);
  const [selected, setSelected] = useState<ForecastCandidate | null>(null);
  const [step, setStep] = useState(1);
  const [environment, setEnvironment] = useState('local');
  const [clusterId, setClusterId] = useState('local');
  const [collectorId, setCollectorId] = useState('kafkaexplorer');
  const [unit, setUnit] = useState('');
  const [topics, setTopics] = useState('');
  const [groups, setGroups] = useState('');
  const [cadence, setCadence] = useState('60000');
  const [horizon, setHorizon] = useState('30');
  const [threshold, setThreshold] = useState('');
  const [direction, setDirection] = useState('ABOVE');
  const [approved, setApproved] = useState(false);
  const [draft, setDraft] = useState<ForecastDraft | null>(null);
  const [error, setError] = useState('');
  const [errors, setErrors] = useState<Record<string, string>>({});
  const [busy, setBusy] = useState(false);
  const [advanced, setAdvanced] = useState(false);
  const abort = useRef<AbortController | null>(null);
  useEffect(() => {
    const controller = new AbortController(); abort.current = controller;
    axios.get<ForecastCandidates>('/api/forecasts/candidates', { signal: controller.signal, timeout: 10000 })
      .then(({ data }) => {
        if (!controller.signal.aborted) {
          if (!Array.isArray(data?.metrics)) { setError('Candidate catalogue unavailable.'); return; }
          setCandidates(data);
          if (initialMetricId) {
            const candidate = data.metrics.find(m => m.metricId === initialMetricId);
            if (candidate) {
              setSelected(candidate); setUnit(candidate.unit);
              setTopics(candidate.topics.join(', ')); setGroups(candidate.groups.join(', '));
            } else setError('Selected metric is absent from the candidate catalogue. Reload or choose a listed metric.');
          }
          if (data.clusterId) setClusterId(data.clusterId);
          if (data.collectorId) setCollectorId(data.collectorId);
        }
      }).catch(() => { if (!controller.signal.aborted) setError('Candidate catalogue unavailable.'); });
    return () => controller.abort();
  }, [initialMetricId]);
  function choose(metricId: string) {
    const c = candidates?.metrics.find(m => m.metricId === metricId) ?? null;
    setSelected(c); setUnit(c?.unit ?? ''); setTopics(c?.topics.join(', ') ?? '');
    setGroups(c?.groups.join(', ') ?? ''); setDraft(null); setApproved(false);
  }
  function review() {
    const next: Record<string, string> = {};
    for (const [key, value] of Object.entries({ environment, clusterId, collectorId, unit }))
      if (!value.trim() || value.length > 128 || [...value].some(char => char.charCodeAt(0) < 32 || char.charCodeAt(0) === 127)) next[key] = 'Use 1–128 printable characters.';
    if (!split(topics).length) next.topics = 'List every source topic.';
    if (!Number.isInteger(Number(horizon)) || Number(horizon) < 1 || Number(horizon) > 60) next.horizon = 'Use 1–60 forecast points.';
    if (threshold.trim() && !Number.isFinite(Number(threshold))) next.threshold = 'Use a finite threshold.';
    setErrors(next);
    const first = Object.keys(next)[0];
    if (first) {
      // A field inside a closed <details> cannot take focus: open it before focusing.
      if (ADVANCED.has(first)) flushSync(() => setAdvanced(true));
      document.getElementById(`forecast-${first}`)?.focus(); return;
    }
    setError(''); setStep(3); setApproved(false);
  }
  async function generate() {
    // The attestation checkbox is the confirmation; a dialog asking the same question again added
    // a click and no information.
    if (!selected || !approved || busy || abort.current?.signal.aborted) return;
    setBusy(true); setError('');
    const request: ForecastDraftRequest = { metricId: selected.metricId, definitionVersion: selected.definitionVersion,
      environment: environment.trim(), unit, clusterId: clusterId.trim(), collectorId: collectorId.trim(),
      topics: split(topics), groups: split(groups), stepMillis: Number(cadence), horizon: Number(horizon),
      threshold: threshold.trim() ? Number(threshold) : null, direction, confirmed: true };
    try {
      const { data } = await axios.post<ForecastDraft>('/api/forecasts/configuration', request,
        { signal: abort.current?.signal, timeout: 10000 });
      if (typeof data?.configuration !== 'string' || !Array.isArray(data.instructions)) throw new Error('Invalid configuration response');
      if (!abort.current?.signal.aborted) setDraft(data);
    } catch (e) {
      if (!abort.current?.signal.aborted) setError(axios.isAxiosError(e) && typeof e.response?.data?.reason === 'string'
        ? e.response.data.reason : 'Configuration validation failed. Reload the catalogue if the metric changed.');
    } finally { if (!abort.current?.signal.aborted) setBusy(false); }
  }
  function download() {
    if (!draft) return;
    const url = URL.createObjectURL(new Blob([draft.configuration], { type: 'application/yaml' }));
    const link = document.createElement('a'); link.href = url; link.download = 'forecasts.yml'; link.click(); URL.revokeObjectURL(url);
  }
  return <div className="border border-outline-variant rounded-md p-4 space-y-3" aria-label="Forecast configuration assistant">
    <h4 className="font-semibold">Step {step} of 3 · {step === 1 ? 'Choose a metric' : step === 2 ? 'Declare semantics and sources' : 'Review and approve'}</h4>
    <p className="text-sm">Exports the selected series while preserving existing runtime approvals. Review and merge the file before restarting.</p>
    {error && <p role="alert">{error}</p>}
    {step === 1 && <>
      {!candidates && !error && <p>Loading candidate metrics…</p>}
      {candidates && <>
        <p>{candidates.total} metric(s){candidates.truncated ? '; showing the first 100' : ''}. Eligibility describes metadata; history and quality are checked separately.</p>
        <Field label="Candidate metric">{p => <Select {...p} value={selected?.metricId ?? ''} onChange={e => choose(e.target.value)}>
          <option value="">Choose a metric</option>{candidates.metrics.map(c => <option key={c.metricId} value={c.metricId}>{c.name || c.metricId} · {c.eligible ? 'configurable' : 'metadata missing'}</option>)}
        </Select>}</Field>
        {selected && <>
          <p>Unit: {selected.unit} · {selected.transformation} · {selected.enrolled ? 'History enrolled' : 'History not enrolled'}</p>
          <ul>{selected.blockers.map(b => <li key={b}>{b}</li>)}</ul>
          <Button disabled={!selected.eligible} onClick={() => setStep(2)}>Continue to sources</Button>
        </>}
      </>}
    </>}
    {step === 2 && <form onSubmit={e => { e.preventDefault(); review(); }} className="space-y-3">
      <div className="grid gap-3 sm:grid-cols-2">
        <Field id="forecast-environment" label="Environment" error={errors.environment}>{p => <Input {...p} value={environment} onChange={e => setEnvironment(e.target.value)} />}</Field>
        <Field id="forecast-unit" label="Captured unit" error={errors.unit} description="Change the metric metadata first to change its unit.">{p => <Input {...p} value={unit} readOnly />}</Field>
        <Field id="forecast-cadence" label="Sampling interval">{p => <Select {...p} value={cadence} onChange={e => setCadence(e.target.value)}>
          {CADENCES.map(c => <option key={c.value} value={c.value}>{c.label}</option>)}</Select>}</Field>
        <Field id="forecast-horizon" label="Forecast horizon (points)" error={errors.horizon}
          description={span(Number(cadence) * Number(horizon)) && `Forecasts ${span(Number(cadence) * Number(horizon))} ahead`}>{p => <Input {...p} inputMode="numeric" value={horizon} onChange={e => setHorizon(e.target.value)} />}</Field>
      </div>
      <details open={advanced} onToggle={e => setAdvanced(e.currentTarget.open)}>
        <summary>Advanced: history identities</summary>
        <p className="text-sm">Prefilled from the running collector; change them only to match another collector.</p>
        <div className="grid gap-3 sm:grid-cols-2">
          <Field id="forecast-clusterId" label="History cluster ID" error={errors.clusterId}>{p => <Input {...p} value={clusterId} onChange={e => setClusterId(e.target.value)} />}</Field>
          <Field id="forecast-collectorId" label="History collector ID" error={errors.collectorId}>{p => <Input {...p} value={collectorId} onChange={e => setCollectorId(e.target.value)} />}</Field>
        </div>
      </details>
      <Field id="forecast-topics" label="All source topics (comma separated)" error={errors.topics} description="Verify every SQL dependency; suggestions are not a complete provenance attestation.">{p => <Input {...p} value={topics} onChange={e => setTopics(e.target.value)} />}</Field>
      <Field label="All consumer groups (comma separated, optional)">{p => <Input {...p} value={groups} onChange={e => setGroups(e.target.value)} />}</Field>
      <Field id="forecast-threshold" label="Optional threshold in forecast output units" error={errors.threshold} description={selected?.transformation === 'COUNTER_RATE' ? `${unit} per second` : unit}>{p => <Input {...p} inputMode="decimal" value={threshold} onChange={e => setThreshold(e.target.value)} />}</Field>
      <Field label="Threshold direction">{p => <Select {...p} value={direction} onChange={e => setDirection(e.target.value)}><option value="ABOVE">Above</option><option value="BELOW">Below</option></Select>}</Field>
      <Button onClick={() => setStep(1)}>Back</Button> <Button type="submit">Review configuration</Button>
    </form>}
    {step === 3 && <>
      <p>{selected?.name || selected?.metricId} · {environment} · {unit} · {selected?.transformation}</p>
      <p>Topics: {topics}. Groups: {groups || 'none'}. History: 512 samples, one every {CADENCES.find(c => c.value === cadence)?.label ?? `${Number(cadence) / 1000} seconds`}; horizon: {horizon} points ({span(Number(cadence) * Number(horizon))}).</p>
      <p>Threshold: {threshold.trim() ? `${direction} ${threshold}` : 'not configured'}. SHADOW initially; no alerts sent.</p>
      <label className="flex gap-2 items-start"><Checkbox checked={approved} onChange={setApproved} disabled={Boolean(draft)} />I reviewed the semantics and confirm every source topic and consumer group.</label>
      {!draft && <><Button onClick={() => { setStep(2); setApproved(false); }}>Back</Button> <Button disabled={!approved || busy} onClick={() => void generate()}>Validate and export</Button></>}
      {draft && <>
        <ul>{draft.instructions.map(i => <li key={i}>{i}</li>)}</ul>
        <pre className="overflow-auto max-h-80 text-xs">{draft.configuration}</pre>
        <Button onClick={download}>Download forecasts.yml</Button>
      </>}
    </>}
  </div>;
}
