// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors

import { useEffect, useMemo, useState } from 'react';
import axios from 'axios';
import { Link } from 'react-router-dom';
import { Badge, Button, ErrorPanel, PageHeader } from '../components/ui';
import { copyText } from '../clipboard';
import type {
  RegistryCompatibility, RegistrySchemaVersion, RegistrySubjects,
} from '../api/types';
import { prettySchema, schemaDiff, type SchemaDiffRow } from './schemaDiff';

const TIMEOUT = 10000;
const route = (subject: string) => `/api/schema-registry/subjects/${encodeURIComponent(subject)}`;

function failureText(error: unknown): string {
  if (axios.isAxiosError(error)) {
    const message: unknown = error.response?.data?.message;
    if (typeof message === 'string' && message.trim()) return message;
    if (error.code === 'ECONNABORTED') return 'Schema Registry did not respond in time.';
  }
  return 'Could not reach Schema Registry. Check its service and URL, then retry.';
}

type Pair = { baseline: number; candidate: number };
type LoadedPair = { subject: string; pair: Pair; baseline: RegistrySchemaVersion; candidate: RegistrySchemaVersion };
type LoadedCheck = { subject: string; result: RegistryCompatibility };

function SchemaLine({ text, side, kind }: { text: string | null; side: 'before' | 'after'; kind: SchemaDiffRow['kind'] }) {
  const changed = (side === 'before' && kind === 'removed') || (side === 'after' && kind === 'added');
  return (
    <pre className={`min-w-0 px-3 py-0.5 text-[11px] sm:text-[12px] font-mono whitespace-pre-wrap break-words border-b border-outline-variant/20 ${changed
      ? side === 'before' ? 'bg-error/10 text-error' : 'bg-success/10 text-success'
      : 'text-on-surface-variant'}`}>
      {text ?? ' '}
    </pre>
  );
}

export default function SchemaRegistryExplorer() {
  const [revision, refresh] = useState(0);
  const [catalog, setCatalog] = useState<RegistrySubjects | null>(null);
  const [registryError, setRegistryError] = useState('');
  const [search, setSearch] = useState('');
  const [subject, setSubject] = useState('');
  const [versions, setVersions] = useState<number[] | null>(null);
  const [versionError, setVersionError] = useState('');
  const [pair, setPair] = useState<Pair>({ baseline: 0, candidate: 0 });
  const [loaded, setLoaded] = useState<LoadedPair | null>(null);
  const [detailError, setDetailError] = useState('');
  const [check, setCheck] = useState<LoadedCheck | null>(null);
  const [checkError, setCheckError] = useState('');
  const [checking, setChecking] = useState(false);
  const [copied, setCopied] = useState(false);

  useEffect(() => {
    const abort = new AbortController();
    axios.get<RegistrySubjects>('/api/schema-registry/subjects', { timeout: TIMEOUT, signal: abort.signal })
      .then(({ data }) => {
        setCatalog(data);
        setRegistryError('');
        setSubject(current => data.subjects.includes(current) ? current : data.subjects[0] ?? '');
      })
      .catch(error => { if (!abort.signal.aborted) { setRegistryError(failureText(error)); setCatalog(null); } });
    return () => abort.abort();
  }, [revision]);

  useEffect(() => {
    if (!subject) return;
    const abort = new AbortController();
    axios.get<number[]>(`${route(subject)}/versions`, { timeout: TIMEOUT, signal: abort.signal })
      .then(({ data }) => {
        const sorted = [...data].sort((a, b) => a - b);
        setVersions(sorted);
        setPair({ baseline: sorted[sorted.length - 2] ?? sorted[sorted.length - 1] ?? 0, candidate: sorted[sorted.length - 1] ?? 0 });
        setVersionError('');
      })
      .catch(error => { if (!abort.signal.aborted) { setVersions([]); setVersionError(failureText(error)); } });
    return () => abort.abort();
  }, [subject, revision]);

  useEffect(() => {
    if (!subject || pair.baseline < 1 || pair.candidate < 1) return;
    const abort = new AbortController();
    const fetchVersion = (version: number) => axios.get<RegistrySchemaVersion>(
      `${route(subject)}/versions/${version}`, { timeout: TIMEOUT, signal: abort.signal },
    ).then(response => response.data);
    Promise.all([fetchVersion(pair.baseline), fetchVersion(pair.candidate)])
      .then(([baseline, candidate]) => {
        setLoaded({ subject, pair: { ...pair }, baseline, candidate });
        setDetailError('');
      })
      .catch(error => { if (!abort.signal.aborted) { setLoaded(null); setDetailError(failureText(error)); } });
    return () => abort.abort();
  }, [subject, pair.baseline, pair.candidate]);

  const matches = useMemo(() => (catalog?.subjects ?? [])
    .filter(name => name.toLowerCase().includes(search.trim().toLowerCase())), [catalog, search]);

  const detail = loaded?.subject === subject && loaded.pair.baseline === pair.baseline
    && loaded.pair.candidate === pair.candidate ? loaded : null;
  const diff = useMemo(() => detail ? schemaDiff(detail.baseline.schema, detail.candidate.schema) : null, [detail]);
  const activeCheck = check?.subject === subject
    && check.result.baselineVersion === pair.baseline
    && check.result.candidateVersion === pair.candidate ? check.result : null;
  const canCheck = Boolean(detail && pair.baseline !== pair.candidate && !checking);
  const topic = subject.endsWith('-value') ? subject.slice(0, -6)
    : subject.endsWith('-key') ? subject.slice(0, -4) : null;

  function chooseSubject(next: string) {
    if (subject === next) return;
    setSubject(next);
    setVersions(null);
    setPair({ baseline: 0, candidate: 0 });
    setLoaded(null);
    setCheck(null);
    setVersionError('');
    setDetailError('');
    setCheckError('');
    setCopied(false);
  }

  function chooseVersion(side: 'baseline' | 'candidate', value: number) {
    setPair(current => ({ ...current, [side]: value }));
    setCheck(null);
    setCheckError('');
    setCopied(false);
  }

  async function checkCompatibility() {
    if (!canCheck) return;
    const selectedSubject = subject;
    setChecking(true);
    setCheck(null);
    setCheckError('');
    try {
      const { data } = await axios.post<RegistryCompatibility>(
        `${route(selectedSubject)}/compatibility`,
        { baselineVersion: pair.baseline, candidateVersion: pair.candidate },
        { timeout: TIMEOUT },
      );
      setCheck({ subject: selectedSubject, result: data });
    } catch (error) {
      setCheckError(failureText(error));
    } finally {
      setChecking(false);
    }
  }

  async function copyCurrentSchema() {
    if (!detail) return;
    setCopied(await copyText(detail.candidate.schema));
  }

  return (
    <div className="p-4 sm:p-6 lg:p-8 space-y-5">
      <PageHeader
        kicker="Explore / Contracts"
        title="Schema Registry Explorer"
        description="Inspect subjects, browse schema history and evaluate compatibility without modifying the registry."
        actions={<>
          <Badge tone="neutral" dot>Read only</Badge>
          <Button variant="outline" icon="refresh" onClick={() => { setCatalog(null); setRegistryError(''); refresh(n => n + 1); }}>
            Refresh
          </Button>
        </>}
      />

      {registryError && (
        <div className="space-y-3">
          <ErrorPanel
            error={{ title: 'Schema Registry unavailable', hint: registryError, raw: '' }}
            onRetry={() => { setRegistryError(''); refresh(n => n + 1); }}
          />
          <div className="rounded-xl border border-outline-variant/60 bg-surface-container-low px-5 py-4 text-[13px] text-on-surface-variant space-y-2">
            <p>The registry is optional. Standard Kafka browsing, metrics and forecasts still work without it.</p>
            <p>To enable it with the bundled demo stack:</p>
            <code className="block overflow-x-auto whitespace-nowrap rounded-md bg-surface-container-high p-3 font-mono text-[11px] text-on-surface">docker compose -f docker-compose.yml -f compose/schema-registry.yml up -d</code>
            <p>For an external registry, configure <code className="font-mono">KAFKA_SCHEMA_REGISTRY_URL</code> on the KafkaExplorer server.</p>
          </div>
        </div>
      )}

      {!registryError && <div className="grid grid-cols-1 xl:grid-cols-[minmax(260px,300px)_minmax(0,1fr)] gap-5 items-start">
        <aside className="rounded-2xl border border-outline-variant/60 bg-surface-container-low overflow-hidden" aria-label="Schema subjects">
          <div className="p-4 border-b border-outline-variant/60 space-y-3">
            <div className="flex items-center justify-between">
              <h2 className="text-[14px] font-semibold text-on-surface">Subjects</h2>
              <Badge tone="neutral">{catalog ? `${catalog.total} total` : 'Loading'}</Badge>
            </div>
            <label className="block">
              <span className="sr-only">Find a subject</span>
              <input
                type="search" placeholder="Filter subjects…" value={search} onChange={event => setSearch(event.target.value)}
                className="w-full h-9 rounded-lg border border-outline-variant/70 bg-surface-container px-3 text-[13px] text-on-surface placeholder:text-outline"
              />
            </label>
            {catalog?.truncated && <p role="status" className="text-[11px] text-warning">Showing the first 1,000 subjects. Refine your catalog externally if needed.</p>}
          </div>
          <div className="max-h-64 xl:max-h-[650px] overflow-y-auto custom-scrollbar">
            {!catalog && <p role="status" className="p-4 text-[12px] text-on-surface-variant">Loading subjects…</p>}
            {catalog && matches.length === 0 && <p className="p-4 text-[12px] text-on-surface-variant">{search ? 'No matching subject.' : 'No schemas registered yet.'}</p>}
            {matches.map(name => (
              <button key={name} type="button" onClick={() => chooseSubject(name)}
                aria-current={subject === name ? 'true' : undefined}
                className={`w-full flex items-start gap-2 text-left px-4 py-3 border-b border-outline-variant/30 transition-colors hover:bg-surface-container-high/70 ${subject === name ? 'bg-primary/10 border-l-[3px] border-l-primary' : ''}`}>
                <span aria-hidden="true" className="material-symbols-outlined text-[18px] text-primary shrink-0">data_object</span>
                <span className="min-w-0 flex-1">
                  <span className="block font-mono text-[12px] text-on-surface break-all">{name}</span>
                  <span className="block mt-0.5 text-[11px] text-on-surface-variant">{name.endsWith('-value') ? 'Value contract' : name.endsWith('-key') ? 'Key contract' : 'Schema subject'}</span>
                </span>
              </button>
            ))}
          </div>
          <div className="px-4 py-3 border-t border-outline-variant/60 text-[11px] text-on-surface-variant">
            {matches.length} shown · Registered subjects are independent of Kafka topic names.
          </div>
        </aside>

        <div className="min-w-0 space-y-5">
          {!subject && catalog && <div className="rounded-2xl border border-outline-variant/60 bg-surface-container-low p-8 text-center text-[13px] text-on-surface-variant">No schema subjects. Register an Avro, Protobuf or JSON schema to see it here.</div>}

          {subject && <section className="rounded-2xl border border-outline-variant/60 bg-surface-container-low p-4 sm:p-5 space-y-4" aria-label="Schema selection">
            <div className="flex flex-wrap items-start justify-between gap-3">
              <div className="min-w-0">
                <p className="text-[11px] uppercase tracking-[0.12em] text-on-surface-variant">Selected subject</p>
                <h2 className="text-[17px] sm:text-[19px] font-semibold text-on-surface font-mono break-all mt-1">{subject}</h2>
              </div>
              {topic && <Link to={`/topic/${encodeURIComponent(topic)}`} className="text-[12px] font-medium text-primary hover:underline">Open topic</Link>}
            </div>
            {versionError && <ErrorPanel error={{ title: 'Could not load versions', hint: versionError, raw: '' }} />}
            {!versions && !versionError && <p role="status" className="text-[12px] text-on-surface-variant">Loading version history…</p>}
            {versions?.length === 0 && !versionError && <p className="text-[12px] text-on-surface-variant">This subject has no active versions.</p>}
            {versions && versions.length > 0 && <div className="grid grid-cols-1 sm:grid-cols-2 gap-3">
              <label className="block text-[12px] font-medium text-on-surface-variant">
                Baseline version
                <select aria-label="Baseline version" value={pair.baseline} onChange={event => chooseVersion('baseline', Number(event.target.value))}
                  className="mt-1 block w-full h-10 rounded-lg border border-outline-variant/70 bg-surface-container px-3 text-[13px] text-on-surface">
                  {versions.map(v => <option key={v} value={v}>v{v}{v === versions[versions.length - 1] ? ' · latest' : ''}</option>)}
                </select>
              </label>
              <label className="block text-[12px] font-medium text-on-surface-variant">
                Candidate version
                <select aria-label="Candidate version" value={pair.candidate} onChange={event => chooseVersion('candidate', Number(event.target.value))}
                  className="mt-1 block w-full h-10 rounded-lg border border-outline-variant/70 bg-surface-container px-3 text-[13px] text-on-surface">
                  {versions.map(v => <option key={v} value={v}>v{v}{v === versions[versions.length - 1] ? ' · latest' : ''}</option>)}
                </select>
              </label>
            </div>}
            {versions && versions.length > 0 && <p className="text-[12px] text-on-surface-variant">
              {versions.length} registered {versions.length === 1 ? 'version' : 'versions'}.
              {' '}Select any two versions to compare their schemas; compatibility is directional and uses the registry's effective policy.
            </p>}
          </section>}

          {detailError && <ErrorPanel error={{ title: 'Could not load schema details', hint: detailError, raw: '' }} />}
          {versions && versions.length > 0 && !detail && !detailError && <div role="status" className="rounded-xl border border-outline-variant/60 bg-surface-container-low p-5 text-[12px] text-on-surface-variant">Loading schema content…</div>}
          {detail && diff && <>
            <section className="rounded-2xl border border-outline-variant/60 bg-surface-container-low overflow-hidden" aria-label="Schema differences">
              <div className="p-4 sm:p-5 border-b border-outline-variant/60 flex flex-wrap justify-between gap-3 items-start">
                <div className="space-y-2">
                  <h3 className="text-[15px] font-semibold text-on-surface">Schema comparison</h3>
                  <div className="flex flex-wrap gap-2">
                    <Badge tone="primary">{detail.candidate.schemaType}</Badge>
                    <Badge>v{pair.baseline} → v{pair.candidate}</Badge>
                    <Badge tone="success">+{diff.added} lines</Badge>
                    <Badge tone={diff.removed ? 'warning' : 'neutral'}>−{diff.removed} lines</Badge>
                  </div>
                </div>
                <Button variant="outline" size="sm" icon={copied ? 'check' : 'content_copy'} onClick={copyCurrentSchema}>
                  {copied ? 'Copied' : 'Copy candidate'}
                </Button>
              </div>
              <div className="grid grid-cols-2 border-b border-outline-variant/60 bg-surface-container">
                <div className="p-3 border-r border-outline-variant/50 min-w-0">
                  <p className="text-[12px] font-semibold text-on-surface">Baseline · v{detail.baseline.version}</p>
                  <p className="text-[11px] text-on-surface-variant">Schema ID {detail.baseline.id} · {detail.baseline.schemaType}</p>
                </div>
                <div className="p-3 min-w-0">
                  <p className="text-[12px] font-semibold text-on-surface">Candidate · v{detail.candidate.version}</p>
                  <p className="text-[11px] text-on-surface-variant">Schema ID {detail.candidate.id} · {detail.candidate.schemaType}</p>
                </div>
              </div>
              <div className="max-h-[520px] overflow-y-auto custom-scrollbar" aria-label="Side-by-side schema diff">
                {diff.rows.map((row, index) => <div key={index} className="grid grid-cols-2">
                  <SchemaLine kind={row.kind} side="before" text={row.before} />
                  <SchemaLine kind={row.kind} side="after" text={row.after} />
                </div>)}
              </div>
              {diff.truncated && <p role="status" className="p-3 border-t border-outline-variant/60 text-[12px] text-warning">Comparison limited to the first 350 lines per version. Full content remains available below.</p>}
              <details className="p-4 border-t border-outline-variant/60">
                <summary className="cursor-pointer text-[12px] font-medium text-primary">View full candidate schema</summary>
                <pre className="mt-3 max-h-96 overflow-auto rounded-lg bg-surface-container-high p-4 font-mono text-[11px] text-on-surface whitespace-pre">{prettySchema(detail.candidate.schema)}</pre>
              </details>
              {detail.candidate.references.length > 0 && <div className="px-4 pb-4">
                <p className="text-[12px] font-semibold text-on-surface mb-2">References ({detail.candidate.references.length})</p>
                <div className="flex flex-wrap gap-2">
                  {detail.candidate.references.map(reference => <Badge key={`${reference.name}:${reference.subject}:${reference.version}`}>
                    {reference.name} · {reference.subject} v{reference.version}
                  </Badge>)}
                </div>
              </div>}
            </section>

            <section className="rounded-2xl border border-outline-variant/60 bg-surface-container-low p-4 sm:p-5 space-y-3" aria-label="Compatibility validation">
              <div className="flex flex-wrap items-center justify-between gap-3">
                <div>
                  <h3 className="text-[15px] font-semibold text-on-surface">Compatibility check</h3>
                  <p className="text-[12px] text-on-surface-variant mt-1">
                    Test candidate v{pair.candidate} against baseline v{pair.baseline}, using the subject's compatibility policy.
                  </p>
                </div>
                <Button icon="verified" onClick={checkCompatibility} loading={checking} disabled={!canCheck}>
                  Check compatibility
                </Button>
              </div>
              {pair.baseline === pair.candidate && <p className="text-[12px] text-on-surface-variant">Select two different versions to enable the compatibility check.</p>}
              {checkError && <ErrorPanel error={{ title: 'Compatibility check failed', hint: checkError, raw: '' }} />}
              {activeCheck && <div role="status" className={`rounded-lg border px-4 py-3 ${activeCheck.compatible ? 'border-success/25 bg-success/5' : 'border-warning/30 bg-warning/5'}`}>
                <div className="flex items-center gap-2 flex-wrap">
                  <Badge tone={activeCheck.compatible ? 'success' : 'warning'} dot>{activeCheck.compatible ? 'Compatible' : 'Incompatible'}</Badge>
                  <span className="text-[12px] text-on-surface-variant">Policy: <strong className="text-on-surface">{activeCheck.level}</strong></span>
                </div>
                {activeCheck.messages.length > 0 && <ul className="mt-2 list-disc pl-5 text-[12px] text-on-surface-variant space-y-1">
                  {activeCheck.messages.map((message, i) => <li key={i} className="break-words">{message}</li>)}
                </ul>}
              </div>}
              <p className="text-[11px] text-on-surface-variant">This check does not register, update or delete schemas. Results depend on the policy currently configured in Schema Registry.</p>
            </section>
          </>}
        </div>
      </div>}
    </div>
  );
}
