// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors

import { useCallback, useEffect, useMemo, useState } from 'react';
import axios from 'axios';
import { Link } from 'react-router-dom';
import type { DashboardResponse, TopicActivityResponse } from '../api/types';
import { BUCKETS, PAGE_SIZE, REFRESH_MS, WINDOW_MS, columnsFor, formatQuantity } from './strata3d/model';
import StrataScene from './strata3d/StrataScene';

type ActivityState = { key: string; response?: TopicActivityResponse; error?: string };

function useDarkSurface(): boolean {
  const [dark, setDark] = useState(() => document.documentElement.dataset.theme === 'dark');
  useEffect(() => {
    const root = document.documentElement;
    const observer = new MutationObserver(() => setDark(root.dataset.theme === 'dark'));
    observer.observe(root, { attributes: true, attributeFilter: ['data-theme'] });
    return () => observer.disconnect();
  }, []);
  return dark;
}

function useReducedMotion(): boolean {
  const [reduced, setReduced] = useState(() =>
    window.matchMedia('(prefers-reduced-motion: reduce)').matches);
  useEffect(() => {
    const media = window.matchMedia('(prefers-reduced-motion: reduce)');
    const changed = () => setReduced(media.matches);
    media.addEventListener('change', changed);
    return () => media.removeEventListener('change', changed);
  }, []);
  return reduced;
}

export default function Strata3D() {
  const [dashboard, setDashboard] = useState<DashboardResponse | null>(null);
  const [dashboardError, setDashboardError] = useState<string | null>(null);
  const [activityState, setActivityState] = useState<ActivityState | null>(null);
  const [filter, setFilter] = useState('');
  const [page, setPage] = useState(0);
  const [selected, setSelected] = useState<string | null>(null);
  const [view, setView] = useState<'orbit' | 'top'>('orbit');
  const [resetIndex, setResetIndex] = useState(0);
  const [playing, setPlaying] = useState(true);
  const dark = useDarkSurface();
  const reduced = useReducedMotion();

  useEffect(() => {
    let mounted = true;
    const refresh = async () => {
      try {
        const result = await axios.get<DashboardResponse>('/api/dashboard', { timeout: 20_000 });
        if (!mounted) return;
        setDashboard(result.data);
        setDashboardError(result.data.health ? null : 'Kafka is unreachable; the current topic inventory could not be verified.');
      } catch {
        if (mounted) setDashboardError('Could not load the cluster topic inventory.');
      }
    };
    void refresh();
    const timer = window.setInterval(refresh, 60_000);
    return () => { mounted = false; window.clearInterval(timer); };
  }, []);

  const allNames = useMemo(() => [...(dashboard?.topics ?? [])].sort((a, b) => a.localeCompare(b)),
    [dashboard?.topics]);
  const filteredNames = useMemo(() => allNames.filter(name => name.toLowerCase().includes(filter.trim().toLowerCase())),
    [allNames, filter]);
  const totalPages = Math.max(1, Math.ceil(filteredNames.length / PAGE_SIZE));
  const currentPage = Math.min(page, totalPages - 1);
  const visibleNames = useMemo(
    () => filteredNames.slice(currentPage * PAGE_SIZE, (currentPage + 1) * PAGE_SIZE),
    [filteredNames, currentPage],
  );
  const visibleKey = visibleNames.join('\u0000');

  useEffect(() => {
    if (visibleNames.length === 0 || !dashboard?.health) return;
    let active = true;
    const refresh = async () => {
      try {
        const result = await axios.get<TopicActivityResponse>('/api/dashboard/activity', {
          params: { topics: visibleNames.join(','), windowMs: WINDOW_MS, buckets: BUCKETS },
          timeout: 25_000,
        });
        if (active) setActivityState({ key: visibleKey, response: result.data });
      } catch {
        if (active) setActivityState({
          key: visibleKey, error: 'Activity measurements are unavailable; the topic inventory remains visible.',
        });
      }
    };
    void refresh();
    const timer = window.setInterval(refresh, REFRESH_MS);
    return () => { active = false; window.clearInterval(timer); };
  }, [visibleKey, visibleNames, dashboard?.health]);

  const activity = activityState?.key === visibleKey ? activityState.response : undefined;
  const columns = useMemo(() =>
    columnsFor(visibleNames, activity?.available ? activity.topics : undefined, dashboard?.topicSizes ?? {}),
    [visibleNames, activity, dashboard?.topicSizes]);
  const chosen = selected && visibleNames.includes(selected) ? selected : (visibleNames[0] ?? null);
  const chosenColumn = columns.find(column => column.name === chosen);
  const measured = columns.filter(column => column.coverage === 'complete').length;
  const partial = columns.filter(column => column.coverage === 'partial').length;
  const unknown = columns.length - measured - partial;

  const reset = useCallback((preset: 'orbit' | 'top') => {
    setView(preset);
    setResetIndex(index => index + 1);
  }, []);

  return (
    <div className="p-4 sm:p-6 lg:p-8 space-y-5 max-w-[1800px] mx-auto">
      <header className="flex flex-col sm:flex-row gap-4 sm:items-end sm:justify-between">
        <div>
          <p className="text-xs font-semibold tracking-[0.14em] uppercase text-primary mb-1">Spatial observability</p>
          <h1 className="text-2xl font-semibold text-on-surface">Kafka Strata 3D</h1>
          <p className="text-sm text-on-surface-variant mt-1 max-w-3xl">
            Each column is a topic. Its stacked rings show offset activity over the last hour, oldest below and newest above.
            Zoom, orbit or select a topic to inspect its measurements.
          </p>
        </div>
        <Link to="/lineage" className="self-start text-sm text-primary hover:underline whitespace-nowrap">
          View lineage graph <span aria-hidden="true">↗</span>
        </Link>
      </header>

      {dashboardError && (
        <div role="alert" className="rounded-xl border border-warning/40 bg-warning/5 px-4 py-3 text-sm text-on-surface">
          {dashboardError}
        </div>
      )}

      <section className="grid grid-cols-2 sm:grid-cols-4 gap-3" aria-label="Scene overview">
        {[
          ['Topics in view', `${visibleNames.length} / ${filteredNames.length}`],
          ['Fully measured', String(measured)],
          ['Partially measured', String(partial)],
          ['Not measured', String(unknown)],
        ].map(([label, value]) => (
          <div key={label} className="rounded-xl border border-outline-variant/50 bg-surface-container-low p-3">
            <p className="text-xs text-on-surface-variant">{label}</p>
            <p className="text-xl tabular-nums font-semibold text-on-surface mt-1">{value}</p>
          </div>
        ))}
      </section>

      <div className="grid grid-cols-1 xl:grid-cols-[minmax(0,1fr)_330px] gap-4">
        <section className="min-w-0 rounded-2xl border border-outline-variant/60 bg-surface-container-low overflow-hidden">
          <div className="flex flex-wrap gap-2 items-center justify-between px-3 sm:px-4 py-3 border-b border-outline-variant/50">
            <span className="text-xs font-medium text-on-surface-variant">
              1 h window · 12 × 5 min · offset activity
            </span>
            <div className="flex items-center flex-wrap gap-2">
              <button type="button" onClick={() => reset('orbit')}
                className="rounded-lg border border-outline-variant/60 px-3 py-1.5 text-xs text-on-surface hover:bg-surface-container-high"
                aria-label="Reset 3D camera to isometric view">Reset view</button>
              <button type="button" onClick={() => reset('top')}
                className={`rounded-lg border px-3 py-1.5 text-xs ${view === 'top' ? 'border-primary text-primary bg-primary/10' : 'border-outline-variant/60 text-on-surface hover:bg-surface-container-high'}`}
                aria-label="Show top-down topic topology">Top view</button>
              <button type="button" onClick={() => setPlaying(p => !p)} disabled={reduced}
                className="rounded-lg border border-outline-variant/60 px-3 py-1.5 text-xs text-on-surface hover:bg-surface-container-high disabled:opacity-40"
                aria-pressed={playing && !reduced}
                title={reduced ? 'Motion disabled by your system preference' : 'Representative particles indicate aggregated activity, not individual messages'}>
                {playing && !reduced ? 'Pause motion' : 'Play motion'}
              </button>
            </div>
          </div>

          <div className="h-[min(68vh,720px)] min-h-[360px] relative" style={{
            background: dark
              ? 'radial-gradient(ellipse at 50% 25%, #243950, #101c2e 75%)'
              : 'radial-gradient(ellipse at 50% 20%, #ffffff, #eaf2f9 75%)',
          }}>
            {visibleNames.length > 0 ? (
              <StrataScene columns={columns} selected={chosen} dark={dark}
                motion={playing && !reduced} view={view} resetIndex={resetIndex} onSelect={setSelected} />
            ) : (
              <div className="h-full flex items-center justify-center text-sm text-on-surface-variant p-8 text-center">
                {dashboard === null && !dashboardError ? 'Loading the cluster…'
                  : !dashboard?.health ? 'No verified topic inventory is available.'
                  : filter ? 'No topics match this filter.' : 'This cluster has no topics.'}
              </div>
            )}
          </div>
          <div className="flex flex-wrap gap-2 justify-between px-4 py-3 text-xs text-on-surface-variant border-t border-outline-variant/50">
            <span>Drag to orbit · Scroll/pinch to zoom · Click a column to select · Right-drag to pan</span>
            <span>Radius: √(bucket offsets / page peak) · Equal scale across visible topics</span>
          </div>
        </section>

        <aside className="flex flex-col gap-4 min-w-0" aria-label="Topic details and accessible scene navigation">
          <div className="rounded-2xl border border-outline-variant/60 bg-surface-container-low p-4 space-y-3">
            <h2 className="text-sm font-semibold text-on-surface">Explore topics</h2>
            <input type="search" value={filter} onChange={event => { setFilter(event.target.value); setPage(0); }}
              placeholder="Filter topic names…" aria-label="Filter topics in 3D scene"
              className="w-full rounded-lg border border-outline-variant/70 bg-surface px-3 py-2 text-sm text-on-surface placeholder:text-on-surface-variant focus:outline-2 focus:outline-primary"/>
            <div className="flex items-center justify-between text-xs text-on-surface-variant">
              <span>Page {currentPage + 1} of {totalPages}</span>
              <div className="flex gap-2">
                <button type="button" disabled={currentPage === 0} onClick={() => setPage(p => Math.max(0, p - 1))}
                  className="border border-outline-variant/60 rounded-lg px-2.5 py-1 disabled:opacity-35"
                  aria-label="Previous topics">Previous</button>
                <button type="button" disabled={currentPage >= totalPages - 1}
                  onClick={() => setPage(p => Math.min(totalPages - 1, p + 1))}
                  className="border border-outline-variant/60 rounded-lg px-2.5 py-1 disabled:opacity-35"
                  aria-label="Next topics">Next</button>
              </div>
            </div>
            <div className="max-h-44 overflow-y-auto space-y-1 custom-scrollbar" role="group" aria-label="Select topic">
              {columns.map(column => (
                <button key={column.name} type="button" onClick={() => setSelected(column.name)}
                  aria-pressed={chosen === column.name} title={column.name}
                  className={`flex items-center text-left w-full gap-2 rounded-lg px-2.5 py-2 text-xs ${chosen === column.name ? 'bg-primary/12 text-primary font-semibold' : 'hover:bg-surface-container-high text-on-surface'}`}>
                  <span aria-hidden="true" className={`w-2 h-2 rounded-full shrink-0 ${column.coverage === 'complete' ? 'bg-primary' : column.coverage === 'partial' ? 'bg-warning' : 'bg-outline'}`}/>
                  <span className="truncate">{column.name}</span>
                </button>
              ))}
            </div>
          </div>

          <div className="rounded-2xl border border-outline-variant/60 bg-surface-container-low p-4 space-y-3" aria-live="polite">
            <h2 className="text-sm font-semibold text-on-surface">Selected topic</h2>
            {chosenColumn ? (
              <>
                <p className="font-medium text-on-surface break-all">{chosenColumn.name}</p>
                <p className={`text-xs ${chosenColumn.coverage === 'complete' ? 'text-primary' : chosenColumn.coverage === 'partial' ? 'text-warning' : 'text-on-surface-variant'}`}>
                  {chosenColumn.coverage === 'complete' ? 'Complete activity coverage' :
                    chosenColumn.coverage === 'partial' ? 'Partial coverage — values are lower bounds' :
                    'Activity not measured — not a zero'}
                </p>
                <dl className="grid grid-cols-2 gap-x-4 gap-y-3 text-xs">
                  <div>
                    <dt className="text-on-surface-variant">Last-hour offsets</dt>
                    <dd className="font-semibold text-on-surface tabular-nums mt-1">
                      {chosenColumn.coverage === 'unavailable' ? 'Unknown' :
                        `${formatQuantity(chosenColumn.activity?.total)}${chosenColumn.coverage === 'partial' ? '+' : ''}`}
                    </dd>
                  </div>
                  <div>
                    <dt className="text-on-surface-variant">Latest 5 min</dt>
                    <dd className="font-semibold text-on-surface tabular-nums mt-1">
                      {chosenColumn.coverage === 'unavailable' ? 'Unknown' :
                        formatQuantity(chosenColumn.activity?.counts[BUCKETS - 1])}
                    </dd>
                  </div>
                  <div>
                    <dt className="text-on-surface-variant">Estimated retained records</dt>
                    <dd className="font-semibold text-on-surface tabular-nums mt-1">{formatQuantity(chosenColumn.retainedMessages)}</dd>
                  </div>
                  <div>
                    <dt className="text-on-surface-variant">Partitions measured</dt>
                    <dd className="font-semibold text-on-surface tabular-nums mt-1">
                      {chosenColumn.activity?.available ?
                        `${chosenColumn.activity.partitionsMeasured} / ${chosenColumn.activity.partitionsTotal}` : 'Unknown'}
                    </dd>
                  </div>
                </dl>
                {chosenColumn.activity?.note && (
                  <p className="text-xs text-on-surface-variant">{chosenColumn.activity.note}</p>
                )}
                {activity?.windowEndMs && <p className="text-xs text-on-surface-variant">
                  Window ended {new Date(activity.windowEndMs).toLocaleString()}
                </p>}
                <Link to={`/topic/${encodeURIComponent(chosenColumn.name)}`}
                  className="inline-flex text-sm font-medium text-primary hover:underline">
                  Open Topic Explorer <span className="ml-1" aria-hidden="true">↗</span>
                </Link>
              </>
            ) : (
              <p className="text-xs text-on-surface-variant">Select a topic to inspect its measurements.</p>
            )}
          </div>

          <div className="rounded-xl border border-outline-variant/60 p-3 text-xs text-on-surface-variant space-y-2">
            <p><strong className="text-on-surface font-semibold">Reading the scene.</strong> Complete measurements use cyan;
              partial measurements use amber; unknown measurements are neutral grey.</p>
            <p>Offsets produced during each interval are derived from Kafka offset boundaries, not sampled messages.
              Compaction, retention and measurement budgets can limit coverage.</p>
            <p>Moving points illustrate aggregate production activity; they are never individual records.
              Topic-to-topic connections and consumer lag are reserved for P2.</p>
          </div>
        </aside>
      </div>

      {activityState?.key === visibleKey && activityState.error && (
        <p role="status" className="text-sm text-warning">{activityState.error}</p>
      )}
      {activity && !activity.available && (
        <p role="status" className="text-sm text-warning">Activity service could not measure this selection.</p>
      )}
      {activity?.warnings?.length ? (
        <div role="status" className="text-xs text-on-surface-variant">
          Measurement scope: {activity.warnings.join(' · ')}
        </div>
      ) : null}
      <p className="text-xs text-on-surface-variant">
        Showing at most {PAGE_SIZE} topics at once to bound broker reads and GPU work.
        The complete topic inventory remains accessible through filtering and paging.
      </p>
    </div>
  );
}
