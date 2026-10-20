// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors
import { useEffect, useRef, useState } from 'react';
import axios from 'axios';
import { Badge, Button, type BadgeTone } from '../ui';
import type { ForecastPreparationCheck, ForecastReadiness } from '../../api/types';

const LABELS: Record<string, string> = {
  pilot: 'Forecast feature',
  mcp: 'MCP server',
  tools: 'Forecast tools',
  postgres: 'PostgreSQL history',
  timesfm: 'TimesFM inference',
  series: 'Approved series',
  scope: 'MCP scope',
  history: 'History depth',
};
const label = (id: string) => LABELS[id] ?? id;

type Group = 'ready' | 'untested' | 'todo';
const groupOf = (state: string): Group =>
  state === 'READY' ? 'ready' : state === 'NOT_CHECKED' ? 'untested' : 'todo';
const TONE: Record<Group, BadgeTone> = { ready: 'success', untested: 'neutral', todo: 'warning' };
const SUMMARY: Record<Group, string> = { ready: 'ready', untested: 'not tested', todo: 'to configure' };

/** Judged per series once one exists: before that it is a line that can only ever say "later". */
const PER_SERIES = new Set(['history']);

/**
 * History and inference both off: no forecast stack behind this application. Every other check
 * then fails as a consequence — no tools, no series, no scope — and six red lines read as six
 * problems where there is one, with one remedy.
 */
const stackMissing = (checks: ForecastPreparationCheck[]) =>
  ['postgres', 'timesfm'].every(id => checks.some(c => c.id === id && c.state === 'CONFIG_REQUIRED'));

/**
 * The assistant itself lives on the Metrics page, the one place the metric cards also open it: two
 * instances could be open at once, each with its own half-filled form.
 */
export function ForecastPreparation({ onConfigure }: { onConfigure?: () => void }) {
  const [readiness, setReadiness] = useState<ForecastReadiness | null>(null);
  const [error, setError] = useState('');
  const [busy, setBusy] = useState(false);
  const controller = useRef<AbortController | null>(null);
  useEffect(() => {
    const abort = new AbortController(); controller.current = abort;
    axios.get<ForecastReadiness>('/api/forecasts/preparation', { signal: abort.signal, timeout: 10000 })
      .then(({ data }) => {
        if (!abort.signal.aborted) {
          if (!Array.isArray(data?.checks)) { setError('Preparation diagnostic unavailable.'); return; }
          setReadiness(data);
        }
      }).catch(() => { if (!abort.signal.aborted) setError('Preparation diagnostic unavailable.'); });
    return () => abort.abort();
  }, []);
  async function probe() {
    setBusy(true); setError('');
    try {
      const { data } = await axios.post<ForecastReadiness>('/api/forecasts/preparation/probe', {},
        { signal: controller.current?.signal, timeout: 15000 });
      if (!Array.isArray(data?.checks)) throw new Error('Invalid readiness response');
      if (!controller.current?.signal.aborted) setReadiness(data);
    } catch { if (!controller.current?.signal.aborted) setError('Dependency test unavailable. Retry after checking service health.'); }
    finally { if (!controller.current?.signal.aborted) setBusy(false); }
  }
  const checks = readiness?.checks ?? [];
  const missing = readiness !== null && stackMissing(checks);
  const shown = checks.filter(c => !PER_SERIES.has(c.id));
  const byGroup = (g: Group) => shown.filter(c => groupOf(c.state) === g);
  const todo = byGroup('todo');
  const untested = byGroup('untested');

  return <div className="space-y-3" aria-label="Forecast preparation">
    <div className="flex flex-wrap items-baseline justify-between gap-2">
      <h3 className="font-semibold">Prepare your first forecast</h3>
      {readiness && <span className="text-xs text-on-surface-variant">
        Checked {new Date(readiness.checkedAt).toLocaleTimeString()}
      </span>}
    </div>
    {error && <p role="status">{error}</p>}

    {readiness && (missing ? <div role="status" className="space-y-1">
      <p className="font-semibold">The forecast stack is not running.</p>
      <p className="text-sm">Start it with <code>bin/forecast-stack.sh</code>, then reload this page.</p>
    </div> : <>
      <div className="flex flex-wrap gap-2" aria-label="Readiness summary">
        {(['ready', 'untested', 'todo'] as const).map(g => byGroup(g).length > 0 &&
          <Badge key={g} tone={TONE[g]} dot>{byGroup(g).length} {SUMMARY[g]}</Badge>)}
      </div>
      {todo.length > 0 && <ol className="space-y-1 text-sm list-decimal pl-5" aria-label="Next steps">
        {todo.map(c => <li key={c.id}><span className="font-medium">{label(c.id)}</span> — {c.action}</li>)}
      </ol>}
      {untested.length > 0 && <p className="text-sm text-on-surface-variant">
        Not tested yet: {untested.map(c => label(c.id)).join(', ')}.
      </p>}
    </>)}

    <div className="flex flex-wrap gap-2">
      {/* Nothing to probe while neither dependency is configured. */}
      {readiness && !missing && untested.length > 0 &&
        <Button disabled={busy} onClick={() => void probe()}>{busy ? 'Testing dependencies…' : 'Test PostgreSQL and TimesFM'}</Button>}
      {onConfigure && <Button onClick={onConfigure}>Configure a forecast</Button>}
    </div>

    {readiness && <details className="text-sm">
      <summary className="cursor-pointer text-on-surface-variant">All {checks.length} checks</summary>
      <ul className="mt-2 space-y-1.5">{checks.map(c => <li key={c.id}>
        <Badge tone={TONE[groupOf(c.state)]}>{c.state.replace(/_/g, ' ')}</Badge>{' '}
        <span className="font-medium">{label(c.id)}</span> — {c.detail}
        {c.state !== 'READY' && <p className="text-xs text-on-surface-variant">{c.action}</p>}
      </li>)}</ul>
      {missing && <p className="mt-2 text-xs text-on-surface-variant">Running without Docker: configure{' '}
        <code>explorer.forecasting.history.*</code> and <code>explorer.forecasting.inference.*</code>.</p>}
    </details>}
  </div>;
}
