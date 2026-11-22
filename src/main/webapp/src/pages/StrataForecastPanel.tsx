// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors

import { useEffect, useMemo, useState } from 'react';
import { Link } from 'react-router-dom';
import axios from 'axios';
import type { ForecastSeriesView, ForecastStatus } from '../api/types';
import { spatialProjection, type StrataProjection } from './strataForecast';

function ForecastMiniChart({ forecast }: { forecast: StrataProjection }) {
  const all = [
    ...forecast.observed.map(p => ({ at: p.at, value: p.value })),
    ...forecast.future.flatMap(p => [{ at: p.at, value: p.q10 }, { at: p.at, value: p.q90 }]),
  ];
  const lo = Math.min(...all.map(p => p.value));
  const hi = Math.max(...all.map(p => p.value));
  const span = Math.max(1e-9, hi - lo);
  const start = all[0].at;
  const duration = Math.max(1, all[all.length - 1].at - start);
  const x = (at: number) => 8 + (at - start) * 384 / duration;
  const y = (value: number) => 108 - (value - lo) / span * 94;
  const observed = forecast.observed.map(p => `${x(p.at)},${y(p.value)}`).join(' ');
  const future = forecast.future.map(p => `${x(p.at)},${y(p.q50)}`).join(' ');
  const top = forecast.future.map(p => `${x(p.at)},${y(p.q90)}`);
  const bottom = forecast.future.slice().reverse().map(p => `${x(p.at)},${y(p.q10)}`);
  const boundary = x(forecast.historyEndAt);
  return (
    <svg role="img" aria-label={`Observed and predicted ${forecast.unit} with q10–q90 uncertainty bounds`}
      viewBox="0 0 400 120" className="w-full rounded-lg bg-surface-container-low">
      <rect x={boundary} y="0" width={Math.max(0, 400 - boundary)} height="120" fill="currentColor" opacity=".035" />
      <line x1={boundary} y1="4" x2={boundary} y2="116" stroke="currentColor" strokeDasharray="3 5" opacity=".35" />
      <polygon points={[...top, ...bottom].join(' ')} fill="#9c7de5" opacity=".21" />
      <polyline points={observed} fill="none" stroke="#0f9eab" strokeWidth="2.8" strokeLinecap="round" />
      <polyline points={future} fill="none" stroke="#9c7de5" strokeWidth="2.8" strokeLinecap="round" />
    </svg>
  );
}

/** A metric forecast is deliberately a separate axis from Kafka offset production. */
export default function StrataForecastPanel({ topic, onProjection }: {
  topic: string;
  onProjection: (projection: StrataProjection | null) => void;
}) {
  const [status, setStatus] = useState<ForecastStatus | null>(null);
  const [listError, setListError] = useState<string | null>(null);
  const [chosenId, setChosenId] = useState('');
  const [detail, setDetail] = useState<ForecastSeriesView | null>(null);
  const [detailError, setDetailError] = useState<string | null>(null);

  useEffect(() => {
    const controller = new AbortController();
    let active = true;
    let busy = false;
    const poll = async () => {
      if (document.visibilityState === 'hidden' || busy) return;
      busy = true;
      try {
        const { data } = await axios.get<ForecastStatus>('/api/forecasts', { signal: controller.signal, timeout: 10000 });
        if (active) { setStatus(data); setListError(null); }
      } catch (error) {
        if (active && !axios.isCancel(error)) { setListError('Forecast status unavailable.'); setStatus(null); }
      } finally { busy = false; }
    };
    void poll();
    const timer = window.setInterval(() => { void poll(); }, 60000);
    const visible = () => { if (document.visibilityState === 'visible') void poll(); };
    document.addEventListener('visibilitychange', visible);
    return () => {
      active = false; controller.abort(); window.clearInterval(timer);
      document.removeEventListener('visibilitychange', visible);
    };
  }, []);

  // No metric-name matching. Only the operator-approved topic scope is evidence.
  const candidates = useMemo(() => (status?.series ?? []).filter(
    series => series.topics?.length === 1 && series.topics[0] === topic,
  ), [status, topic]);
  const selected = candidates.find(series => series.seriesId === chosenId) ?? candidates[0] ?? null;

  useEffect(() => {
    if (!selected) return;
    const controller = new AbortController();
    let active = true;
    const load = async () => {
      if (document.visibilityState === 'hidden') return;
      try {
        const { data } = await axios.get<ForecastSeriesView>(
          `/api/forecasts/${encodeURIComponent(selected.seriesId)}`,
          { signal: controller.signal, timeout: 10000 },
        );
        if (active) { setDetail(data); setDetailError(null); }
      } catch (error) {
        if (active && !axios.isCancel(error)) { setDetailError('Forecast detail unavailable.'); setDetail(null); }
      }
    };
    void load();
    const timer = window.setInterval(() => { void load(); }, 60000);
    const visible = () => { if (document.visibilityState === 'visible') void load(); };
    document.addEventListener('visibilitychange', visible);
    return () => {
      active = false; controller.abort(); window.clearInterval(timer);
      document.removeEventListener('visibilitychange', visible);
    };
  }, [selected?.seriesId]);

  const resolved = detail?.seriesId === selected?.seriesId ? detail : selected;
  const projection = useMemo(() => resolved ? spatialProjection(resolved, topic) : null, [resolved, topic]);
  useEffect(() => { onProjection(projection); }, [onProjection, projection]);

  return (
    <section className="rounded-xl border border-outline-variant bg-surface p-4 space-y-3">
      <div>
        <h2 className="font-semibold text-on-surface">Forecast spatial</h2>
        <p className="text-xs text-on-surface-variant mt-1">
          Projection from existing, explicitly approved topic-scoped metric series.
          It is drawn on its own scale, never as Kafka-produced offsets.
        </p>
      </div>
      {listError && <p role="alert" className="text-xs text-error">{listError}</p>}
      {!status && !listError && <p className="text-xs text-on-surface-variant">Loading forecast series…</p>}
      {status && !status.enabled && <p className="text-xs text-on-surface-variant">Forecast service is disabled.</p>}
      {status?.enabled && candidates.length === 0 && <p className="text-xs text-on-surface-variant">
        No forecast has an explicit one-topic scope matching <strong>{topic}</strong>.
        Multi-topic forecasts are deliberately not assigned to one column.
      </p>}
      {selected && <>
        <label htmlFor="strata-forecast-series" className="block text-xs font-medium text-on-surface-variant">
          Metric forecast for this topic
        </label>
        <select id="strata-forecast-series" value={selected.seriesId}
          onChange={event => { setChosenId(event.target.value); setDetail(null); }}
          className="w-full rounded-lg border border-outline-variant bg-surface p-2 text-sm text-on-surface">
          {candidates.map(series => <option key={series.seriesId} value={series.seriesId}>
            {series.metricId} · {series.environment}
          </option>)}
        </select>
        {detailError && <p role="alert" className="text-xs text-error">{detailError}</p>}
        {!projection ? <p className="text-xs text-on-surface-variant">
          Spatial projection unavailable: the series may lack measured history, contain an expired or
          degraded forecast, or have incompatible units. See the Metrics page for details.
        </p> : <>
          <div className="flex flex-wrap items-center gap-3 text-xs text-on-surface-variant">
            <span>Observed <span className="text-teal-600 dark:text-teal-400">━</span></span>
            <span>Predicted <span className="text-violet-500">━</span></span>
            <span>q10–q90 band <span className="text-violet-500">▰</span></span>
          </div>
          <ForecastMiniChart forecast={projection} />
          <dl className="grid grid-cols-2 gap-2 text-xs text-on-surface-variant">
            <div><dt>Unit</dt><dd className="font-medium text-on-surface">{projection.unit}</dd></div>
            <div><dt>Model</dt><dd className="font-medium text-on-surface">{projection.modelId}</dd></div>
            <div><dt>Series visibility</dt><dd className="font-medium text-on-surface">{projection.visibility}</dd></div>
            <div><dt>History ends</dt><dd className="text-on-surface">{new Date(projection.historyEndAt).toLocaleString()}</dd></div>
            <div className="col-span-2"><dt>Forecast horizon</dt><dd className="text-on-surface">
              {new Date(projection.future[0].at).toLocaleString()} – {new Date(projection.future[projection.future.length - 1].at).toLocaleString()}
            </dd></div>
          </dl>
          {projection.visibility !== 'ACTIVE' && <p className="text-xs text-amber-700 dark:text-amber-300">
            Shadow forecast: not approved for operational decisions.
          </p>}
          <p className="text-xs text-on-surface-variant">
            The translucent violet canopy is a model-estimated q10–q90 interval, not a guaranteed coverage bound.
            Observed history excludes imputed points.
          </p>
        </>}
        <Link to="/metrics" className="inline-block text-xs text-primary underline underline-offset-2">
          Inspect forecast quality in Metrics
        </Link>
      </>}
    </section>
  );
}
