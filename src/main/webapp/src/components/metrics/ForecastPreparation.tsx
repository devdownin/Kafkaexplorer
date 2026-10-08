// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors
import { useEffect, useRef, useState } from 'react';
import axios from 'axios';
import { Button } from '../ui';
import type { ForecastPreparationCheck, ForecastReadiness } from '../../api/types';

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
  const missing = readiness !== null && stackMissing(readiness.checks);
  const list = readiness && <ul className="space-y-2">{readiness.checks.map(check => <li key={check.id}>
    <strong>{check.id}: {check.state.replace(/_/g, ' ')}</strong> — {check.detail}
    {check.state !== 'READY' && <p className="text-sm text-on-surface-variant">{check.action}</p>}
  </li>)}</ul>;
  return <div className="space-y-3" aria-label="Forecast preparation">
    <h3 className="font-semibold">Prepare your first forecast</h3>
    {error && <p role="status">{error}</p>}
    {readiness && (missing ? <>
      <div role="status" className="space-y-1">
        <p className="font-semibold">The forecast stack is not running.</p>
        <p>Durable history (PostgreSQL) and TimesFM inference are both off, so nothing is recorded or forecast yet. From a
          checkout of this repository, start them with <code>bin/forecast-stack.sh</code>, then reload this page.</p>
        <p className="text-sm text-on-surface-variant">Running without Docker: configure <code>explorer.forecasting.history.*</code> and{' '}
          <code>explorer.forecasting.inference.*</code>, as the forecasts guide describes.</p>
      </div>
      <details><summary>All {readiness.checks.length} checks</summary>{list}</details>
    </> : <>
      <p className="text-sm">Checked {new Date(readiness.checkedAt).toLocaleString()}. Agent connectivity is verified from the agent.</p>
      {list}
    </>)}
    <div className="flex flex-wrap gap-2">
      {/* Nothing to probe while neither dependency is configured. */}
      {!missing && <Button disabled={busy} onClick={() => void probe()}>{busy ? 'Testing dependencies…' : 'Test PostgreSQL and TimesFM'}</Button>}
      {onConfigure && <Button onClick={onConfigure}>Configure a forecast</Button>}
    </div>
  </div>;
}
