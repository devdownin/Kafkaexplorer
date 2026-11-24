// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors
import { useEffect, useRef, useState } from 'react';
import { flushSync } from 'react-dom';
import axios from 'axios';
import { Badge, Button, Checkbox, Field, Input, ListSkeleton, Select } from '../ui';
import { CartesianGrid, ComposedChart, Line, ReferenceLine, ResponsiveContainer, XAxis, YAxis } from 'recharts';
import type { ForecastApplied, ForecastCandidate, ForecastCandidates, ForecastDraft, ForecastDraftRequest, ForecastHistoryPreview, ForecastModelInfo } from '../../api/types';
import { copyText } from '../../clipboard';
import { NameListField } from './NameListField';
import { about } from './forecastStage';
import { FORECAST_COLORS } from './forecastColors';
import { cadenceFor, firstForecastSentence, SEASON_PERIODS, seasonFits } from './forecastAutoCreate';

/** Every option keeps 513 buckets inside the default 30-day history retention the server checks. */
const CADENCES = [{ value: '30000', label: '30 seconds' }, { value: '60000', label: '1 minute' },
  { value: '300000', label: '5 minutes' }, { value: '900000', label: '15 minutes' }];
/** Fields with a sound default, folded away; one that fails validation opens the fold. */
const ADVANCED = new Set(['threshold']);
/** How far ahead is asked in time: the points follow from the interval, and only the ones that fit are offered. */
const AHEADS = [{ ms: 900000, label: '15 minutes' }, { ms: 1800000, label: '30 minutes' }, { ms: 3600000, label: '1 hour' },
  { ms: 7200000, label: '2 hours' }, { ms: 21600000, label: '6 hours' }, { ms: 43200000, label: '12 hours' }];
const aheadFits = (ms: number, cadence: number) => ms % cadence === 0 && ms / cadence >= 1 && ms / cadence <= 60;
/** The same duration when the new interval carries it, else the closest shorter one that fits. */
const aheadFor = (ms: number, cadence: number) => aheadFits(ms, cadence) ? ms
  : AHEADS.filter(o => aheadFits(o.ms, cadence) && o.ms <= ms).pop()?.ms ?? AHEADS.find(o => aheadFits(o.ms, cadence))?.ms ?? ms;
const round = (v: number) => +v.toPrecision(3);
/** The server computes the season from the same rule (`seasonFits`): a whole number of samples, 2 to 512. */
const SEASONALITIES = Object.entries(SEASON_PERIODS).map(([value, period]) =>
  ({ value, period, label: value === 'NONE' ? 'None' : value[0] + value.slice(1).toLowerCase() }));
/** The pilot's terms stay in the API; the page says what the forecast value is. */
const TRANSFORMATION: Record<string, string> = { COUNTER_RATE: 'Rate per second', GAUGE_MEAN: 'Average per interval' };
/**
 * Which model produces the forecast, and which one only watches. The checkpoint is a repository id a
 * reader can look up, the commit is cut to the length Git itself shows, and the adapter version is the
 * identity every stored answer carries — so a forecast on screen can be traced to exactly this.
 * `enabled` is configuration: it says the model is switched on, not that its service answers.
 */
function ModelDetails({ models }: { models: ForecastModelInfo[] }) {
  if (models.length === 0) return null;
  return <section className="space-y-2" aria-label="Forecast model">
    <h5 className="text-sm font-medium">Forecast model</h5>
    <ul className="space-y-2">{models.map(m => <li key={m.role} className="rounded-lg border border-outline-variant bg-surface-container px-3 py-2 text-sm">
      <div className="flex flex-wrap items-center gap-2">
        <span className="font-medium">{m.name}</span>
        <Badge tone={m.enabled ? 'success' : 'neutral'}>{m.enabled ? 'On' : 'Off'}</Badge>
        <span className="text-xs text-on-surface-variant">{m.role === 'PRIMARY'
          ? 'Produces the forecast you see'
          : 'Scored beside it on the same history, never shown as a forecast'}</span>
      </div>
      <dl className="mt-1 grid gap-x-4 gap-y-0.5 text-xs sm:grid-cols-[auto_1fr]">
        <dt className="text-on-surface-variant">Checkpoint</dt><dd className="font-mono break-all">{m.modelId || '—'}</dd>
        <dt className="text-on-surface-variant">Revision</dt>
        <dd className="font-mono">{m.revision ? m.revision.slice(0, 7) : 'Set on the inference service'}</dd>
        <dt className="text-on-surface-variant">Adapter</dt><dd className="font-mono">{m.adapterVersion || '—'}</dd>
      </dl>
      {!m.enabled && <p className="mt-1 text-xs text-on-surface-variant">{m.role === 'PRIMARY'
        ? 'Inference is not enabled: no forecast can be produced until it is.'
        : 'Not enabled: nothing is compared with the forecast.'}</p>}
    </li>)}</ul>
  </section>;
}
function span(millis: number) {
  if (!Number.isFinite(millis) || millis <= 0) return '';
  const minutes = millis / 60000;
  return minutes < 120 ? `${+minutes.toFixed(1)} minutes` : `${+(minutes / 60).toFixed(1)} hours`;
}
/**
 * The forecast form for one metric — opened only when the card's one-click creation could not
 * finish (`forecastAutoCreate.ts`). Every forecast starts from an existing metric, so there is no
 * metric to choose here, and nothing the metric already says is asked again: its unit, the history
 * identities and the environment keep the values the one-click path sends.
 */
export function ForecastSetupWizard({ metricId: forMetric, initialError = '', sourcesInferred = true, onApplied }: {
  metricId: string;
  /** Why the one-click creation stopped: the form opens on it. */
  initialError?: string;
  /**
   * Whether the metric's sources were inferred from free SQL rather than declared by its template.
   * Only then is the operator asked to confirm them: a template's sources are known by construction,
   * and the one-click path already sends them confirmed.
   */
  sourcesInferred?: boolean;
  /** Receives the series that was started, so the page can show it rather than whichever is first. */
  onApplied?: (seriesId: string) => void;
}) {
  const [candidates, setCandidates] = useState<ForecastCandidates | null>(null);
  const [selected, setSelected] = useState<ForecastCandidate | null>(null);
  const environment = 'local';
  const [clusterId, setClusterId] = useState('local');
  const [collectorId, setCollectorId] = useState('kafkaexplorer');
  const [unit, setUnit] = useState('');
  const [topics, setTopics] = useState<string[]>([]);
  const [groups, setGroups] = useState<string[]>([]);
  const [copied, setCopied] = useState(false);
  const [cadence, setCadence] = useState('60000');
  const [ahead, setAhead] = useState(1800000);
  /*
   * Keyed by the metric and interval it was read for (METRICS-FORECAST-AUDIT-2.md B5): changing the
   * interval kept the previous interval's points on screen, captioned with the new one, until the
   * new read answered.
   */
  const [previewRead, setPreview] = useState<{ key: string; data: ForecastHistoryPreview } | null>(null);
  // A cycle the operator chose is never replaced by the one the history suggests.
  const cycleTouched = useRef(false);
  const [seasonality, setSeasonality] = useState('NONE');
  const [threshold, setThreshold] = useState('');
  const [direction, setDirection] = useState('ABOVE');
  const [approved, setApproved] = useState(false);
  const [draft, setDraft] = useState<ForecastDraft | null>(null);
  const [applied, setApplied] = useState<ForecastApplied | null>(null);
  // History already recorded when the series started: the first forecast needs only the rest.
  const [recordedAtStart, setRecordedAtStart] = useState<number | null>(null);
  const [error, setError] = useState(initialError);
  const [errors, setErrors] = useState<Record<string, string>>({});
  const [busy, setBusy] = useState(false);
  const [advanced, setAdvanced] = useState(false);
  const abort = useRef<AbortController | null>(null);
  const canApply = candidates?.applyUnavailable === null;
  const horizon = Math.max(1, Math.round(ahead / Number(cadence)));
  function take(c: ForecastCandidate | null) {
    setSelected(c); setUnit(c?.unit ?? ''); setTopics(c?.topics ?? []); setGroups(c?.groups ?? []);
    const next = String(cadenceFor(c)); setCadence(next); setAhead(a => aheadFor(a, Number(next)));
    cycleTouched.current = false; setPreview(null);
    setSeasonality(cycle => seasonFits(SEASONALITIES.find(o => o.value === cycle)?.period ?? 0, Number(next)) ? cycle : 'NONE');
  }
  useEffect(() => {
    const controller = new AbortController(); abort.current = controller;
    axios.get<ForecastCandidates>('/api/forecasts/candidates', { signal: controller.signal, timeout: 10000 })
      .then(async ({ data }) => {
        if (controller.signal.aborted) return;
        if (!Array.isArray(data?.metrics)) { setError('Candidate catalogue unavailable.'); return; }
        setCandidates(data);
        if (data.clusterId) setClusterId(data.clusterId);
        if (data.collectorId) setCollectorId(data.collectorId);
        const candidate = data.metrics.find(m => m.metricId === forMetric);
        if (!candidate) setError('This metric is absent from the candidate catalogue. Reload the page.');
        else take(candidate);
      }).catch(() => { if (!controller.signal.aborted) setError('Candidate catalogue unavailable.'); });
    return () => controller.abort();
    // One form per metric: the page remounts it (a key) for another.
  }, [forMetric]);
  /*
   * What the metric's recorded history looks like at this interval — read when a metric or an
   * interval is chosen, never on a keystroke. It answers three questions the form used to leave to
   * guesswork: is there enough history, what cycle does it show, and what is a threshold worth here.
   */
  const metricId = selected?.eligible ? selected.metricId : '';
  const previewKey = `${metricId}|${cadence}`;
  const preview = previewRead?.key === previewKey ? previewRead.data : null;
  useEffect(() => {
    if (!metricId) return;
    const controller = new AbortController();
    axios.get<ForecastHistoryPreview>(`/api/forecasts/candidates/${encodeURIComponent(metricId)}/history`,
      { params: { stepMillis: Number(cadence) }, signal: controller.signal, timeout: 10000 })
      .then(({ data }) => {
        if (controller.signal.aborted || !Array.isArray(data?.points) || typeof data.status !== 'string') return;
        setPreview({ key: `${metricId}|${cadence}`, data });
        const cycle = data.suggestedSeasonality;
        if (!cycleTouched.current && (cycle === 'DAILY' || cycle === 'HOURLY')) setSeasonality(cycle);
      }).catch(() => { if (!controller.signal.aborted) setPreview(null); });
    return () => controller.abort();
  }, [metricId, cadence]);
  function valid() {
    const next: Record<string, string> = {};
    if (!topics.length) next.topics = 'Add every source topic.';
    // The server refuses a declaration that drops a topic or group the template reads, with a
    // page-level 400 that names no field: caught here, on the field, with the names to put back.
    const missingTopics = (selected?.topics ?? []).filter(t => !topics.includes(t));
    if (topics.length && missingTopics.length) next.topics = `Keep every topic the metric reads: ${missingTopics.join(', ')}.`;
    const missingGroups = (selected?.groups ?? []).filter(g => !groups.includes(g));
    if (missingGroups.length) next.groups = `Keep every consumer group the metric reads: ${missingGroups.join(', ')}.`;
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
    if (!selected || !confirmed || busy || abort.current?.signal.aborted || !valid()) return;
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
        if (!abort.current?.signal.aborted) { setRecordedAtStart(preview?.observedPoints ?? null); setApplied(data); onApplied?.(data.seriesId); }
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
  const confirmed = approved || !sourcesInferred;
  const seasonPoints = (SEASONALITIES.find(o => o.value === seasonality)?.period ?? 0) / Number(cadence);
  // A first forecast waits for the floor, or for one whole cycle when that is longer — the server's rule.
  const firstPoints = Number.isFinite(candidates?.minimumContextPoints) ? Math.max(candidates!.minimumContextPoints, seasonPoints) : null;
  const firstSpan = firstPoints === null ? '' : `${about(firstPoints, Number(cadence))} of history`;
  const aheadText = span(ahead);
  const lastValue = preview?.points[preview.points.length - 1]?.value ?? null;
  // Above the latest value for ABOVE, below it for BELOW (METRICS-FORECAST-AUDIT-2.md B4): 1.5× the
  // latest value as a BELOW threshold is already crossed, a breach on the very first cycle.
  const suggestionFactor = direction === 'BELOW' ? 0.5 : 1.5;
  const suggestedThreshold = lastValue !== null && lastValue > 0 ? round(lastValue * suggestionFactor) : null;
  const found = preview?.suggestedSeasonality === seasonality && seasonality !== 'NONE';
  /*
   * One screen (it was two steps): the metric, then what has no sound default — the environment,
   * the sources, the attestation. Choosing a metric is what reveals the rest, so "Continue to
   * sources" and "Back" asked for a click that decided nothing, and Back was drawn as the primary
   * action beside a disabled Start.
   */
  return <div className="card p-5 space-y-4" aria-label="Forecast configuration assistant">
    {done && <h4 className="font-semibold">{applied ? 'Forecast started' : 'Configuration ready'}</h4>}
    {error && <p role="alert" className="text-sm text-error">{error}</p>}
    {!candidates && !error && <ListSkeleton label="Loading the metric" rows={3} />}
    {candidates && !done && <>
      {selected && <p className="text-sm">Metric <span className="font-mono">{selected.name || selected.metricId}</span></p>}
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
          ['One point every', cadenceLabel, seasonality === 'NONE' ? 'No repeating cycle' : `${SEASONALITIES.find(o => o.value === seasonality)?.label} cycle${found ? ', found in the history' : ''}`],
          ['Looks ahead', aheadText || '—', `${horizon} points`],
          ['First forecast after', firstPoints === null ? '—' : `${firstPoints} points`,
            firstSpan && `${firstSpan}${selected.enrolled ? '; what is already recorded counts' : ', recorded from now'}`],
        ].map(([label, value, hint]) => <div key={label} className="bg-surface-container px-3 py-2">
          <dt className="text-[11px] uppercase tracking-wide text-on-surface-variant">{label}</dt>
          <dd className="font-medium tabular-nums">{value}</dd>
          {hint && <dd className="text-xs text-on-surface-variant">{hint}</dd>}
        </div>)}
      </dl>
      <ModelDetails models={candidates.models ?? []} />
      {preview && <figure className="space-y-1" aria-label="Recorded history">
        <figcaption className="text-xs text-on-surface-variant">{preview.points.length > 1
          ? `Recorded history at ${cadenceLabel}: ${preview.observedPoints} of the ${preview.requiredPoints} points a first forecast needs.`
          : preview.reason}</figcaption>
        {preview.points.length > 1 && <div style={{ height: 110 }} role="img" aria-label="Recorded history chart">
          <ResponsiveContainer><ComposedChart data={preview.points}>
            <CartesianGrid stroke={FORECAST_COLORS.band} strokeOpacity={0.15} vertical={false} />
            <XAxis dataKey="endAt" type="number" domain={['dataMin', 'dataMax']} hide />
            <YAxis width={44} domain={[0, (max: number) => Math.max(max, Number(threshold) || 0)]} tick={{ fontSize: 10, fill: FORECAST_COLORS.band }} />
            <Line dataKey="value" stroke={FORECAST_COLORS.observed} dot={false} strokeWidth={1.5} isAnimationActive={false} />
            {threshold.trim() && Number.isFinite(Number(threshold)) && <ReferenceLine y={Number(threshold)} stroke={FORECAST_COLORS.warning} strokeDasharray="4 4" />}
          </ComposedChart></ResponsiveContainer>
        </div>}
      </figure>}
      <fieldset className="space-y-2">
        <legend className="text-sm font-medium">Sources this metric reads</legend>
        <p className="text-xs text-on-surface-variant">{sourcesInferred
          ? 'Inferred from the metric\'s SQL. Check them against it: a source left out is a dependency the forecast will not know about.'
          : 'Declared by the metric\'s template.'}</p>
        <div className="grid gap-3 md:grid-cols-2 items-start">
          <NameListField id="forecast-topics" label="All source topics" names={topics} onChange={setTopics} topics error={errors.topics} />
          <NameListField id="forecast-groups" label="All consumer groups (optional)" names={groups} onChange={setGroups} error={errors.groups} />
        </div>
      </fieldset>
      <details open={advanced} onToggle={e => setAdvanced(e.currentTarget.open)} className="text-sm">
        <summary className="cursor-pointer py-1 text-on-surface-variant">Advanced: interval, horizon, cycle and threshold</summary>
        <div className="grid gap-3 sm:grid-cols-2 lg:grid-cols-4 pt-3">
          <Field id="forecast-cadence" label="Sampling interval" description="Suggested from how often the metric is collected.">{p => <Select {...p} value={cadence} onChange={e => {
            setCadence(e.target.value);
            const period = SEASONALITIES.find(o => o.value === seasonality)?.period ?? 0;
            if (!seasonFits(period, Number(e.target.value))) setSeasonality('NONE');
            setAhead(a => aheadFor(a, Number(e.target.value)));
          }}>
            {CADENCES.map(c => <option key={c.value} value={c.value}>{c.label}</option>)}</Select>}</Field>
          <Field id="forecast-ahead" label="Looks ahead" description={`${horizon} points at this interval`}>{p => <Select {...p} value={ahead} onChange={e => setAhead(Number(e.target.value))}>
            {AHEADS.filter(o => aheadFits(o.ms, Number(cadence))).map(o => <option key={o.ms} value={o.ms}>{o.label}</option>)}</Select>}</Field>
          <Field id="forecast-seasonality" label="Repeating cycle"
            description={found ? `Found in the history (correlation ${round(preview?.correlation ?? 0)}).` : 'Gives the forecast a seasonal baseline to beat, and a seasonal fallback.'}>{p => <Select {...p} value={seasonality} onChange={e => { cycleTouched.current = true; setSeasonality(e.target.value); }}>
            {SEASONALITIES.filter(o => seasonFits(o.period, Number(cadence))).map(o => <option key={o.value} value={o.value}>{o.label}</option>)}</Select>}</Field>
          <Field id="forecast-threshold" label="Threshold (optional)" error={errors.threshold}
            description={<>In {selected.transformation === 'COUNTER_RATE' ? `${unit} per second` : unit}
              {lastValue !== null && <>; latest recorded {round(lastValue)}. {suggestedThreshold !== null && !threshold.trim()
                && <Button variant="ghost" size="sm" className="h-6 px-1.5 underline" onClick={() => setThreshold(String(suggestedThreshold))}>Use {suggestedThreshold} ({suggestionFactor}× latest)</Button>}</>}</>}>{p => <Input {...p} inputMode="decimal" value={threshold} onChange={e => setThreshold(e.target.value)} />}</Field>
          <Field label="Threshold direction">{p => <Select {...p} value={direction} onChange={e => setDirection(e.target.value)}><option value="ABOVE">Above</option><option value="BELOW">Below</option></Select>}</Field>
        </div>
      </details>
      {sourcesInferred && <label className="flex gap-2 items-center text-sm"><Checkbox checked={approved} onChange={setApproved} />I confirm these are all the topics and consumer groups this metric reads.</label>}
      <div className="flex flex-wrap items-center gap-2">
        <Button type="submit" disabled={!confirmed || busy} loading={busy}>{canApply ? 'Start forecasting' : 'Validate and export'}</Button>
        {canApply && <Button variant="ghost" disabled={!confirmed || busy} onClick={() => void submit('export')}>Export YAML instead</Button>}
        <Badge title="SHADOW">Starts in observation</Badge>
      </div>
      <p className="text-xs text-on-surface-variant">{canApply
        ? 'Starts directly, from the history already collected: no file, no restart. Nothing is alerted or sent, and you approve it once its quality checks pass.'
        : <>Exports the selected series while preserving existing approvals. Review and merge the file before restarting. {candidates.applyUnavailable}</>}</p>
    </form>}
    {applied && <div role="status" className="space-y-2 text-sm">
      <p className="font-semibold">Forecast started for {selected?.name || selected?.metricId}.</p>
      {/* It said "after 128 points, ~2 h" whatever was already recorded, while the journal keeps
          every metric's history from the moment it runs: a metric with a day behind it has its first
          forecast at the next cycle, not in two hours. */}
      <p>It is now the series shown in Metrics Forecast below. {firstForecastSentence(firstPoints, recordedAtStart, Number(cadence))}; then quality is judged over realised values before approval is offered.</p>
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
      </div>
    </>}
  </div>;
}
