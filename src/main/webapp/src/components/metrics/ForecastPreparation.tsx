// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors
import { useEffect, useRef, useState } from 'react';
import axios from 'axios';
import { Button } from '../ui';
import type { ForecastReadiness } from '../../api/types';

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
  return <div className="space-y-3" aria-label="Forecast preparation">
    <h3 className="font-semibold">Prepare your first forecast</h3>
    {error && <p role="status">{error}</p>}
    {readiness && <>
      <p className="text-sm">Checked {new Date(readiness.checkedAt).toLocaleString()}. Agent connectivity is verified from the agent.</p>
      <ul className="space-y-2">{readiness.checks.map(check => <li key={check.id}>
        <strong>{check.id}: {check.state.replace(/_/g, ' ')}</strong> — {check.detail}
        {check.state !== 'READY' && <p className="text-sm text-on-surface-variant">{check.action}</p>}
      </li>)}</ul>
    </>}
    <div className="flex flex-wrap gap-2">
      <Button disabled={busy} onClick={() => void probe()}>{busy ? 'Testing dependencies…' : 'Test PostgreSQL and TimesFM'}</Button>
      {onConfigure && <Button onClick={onConfigure}>Configure a forecast</Button>}
    </div>
  </div>;
}
