// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors
import React, { useEffect, useMemo, useState } from 'react';
import { Link } from 'react-router-dom';
import axios from 'axios';
import type { FlowHit } from '../../pages/streamFlowLogic';
import type { MetricConfig, MetricSuggestions, TopicConsumers } from '../../api/types';
import { chainFromHits } from '../../pages/flowChains';
import { consumerLagDraft, flowMetricOptions, metricName, neighboursOf, withStaticLabels,
  type FlowMetricOption } from '../../pages/flowMetrics';
import { extractApiErrorMessage } from '../../pages/queryError';
import { Badge, Button, Checkbox, Select } from '../ui';

/**
 * Create metrics from a topic of the trace, in one screen: the hops into and out of it, its
 * consumers and its production — ticked, labelled, created. `flowMetrics.ts` decides what is
 * proposed; this renders it and posts what is ticked. Creating is the gesture (the operator asked
 * for it from a graph they just read); every metric is then a normal one, edited on Metrics.
 */
export const FlowMetricDrawer: React.FC<{
  topic: string;
  hits: FlowHit[];
  messageKey: string;
  searchPath: string;
  onClose: () => void;
}> = ({ topic, hits, messageKey, searchPath, onClose }) => {
  const neighbours = useMemo(() => neighboursOf(hits, topic), [hits, topic]);
  const [suggestions, setSuggestions] = useState<MetricSuggestions['suggestions'] | null>(null);
  const [suggestionsError, setSuggestionsError] = useState<string | null>(null);
  const [groups, setGroups] = useState<string[] | null>(null);
  const [groupsError, setGroupsError] = useState<string | null>(null);
  const [group, setGroup] = useState<string | null>(null);
  const [ticked, setTicked] = useState<Record<string, boolean>>({});
  const [unticked, setUnticked] = useState<Record<string, boolean>>({});
  const [busy, setBusy] = useState(false);
  const [results, setResults] = useState<{ id: string; title: string; ok: boolean; text: string }[]>([]);

  // A single sighting is not a path: no hop, so nothing to ask the server about.
  const chain = useMemo(() => chainFromHits(hits, { messageKey, searchPath }), [hits, messageKey, searchPath]);
  useEffect(() => {
    let live = true;
    if (!chain) return;
    axios.post<MetricSuggestions>('/api/metrics/suggestions', { flowChains: [chain] })
      .then(res => { if (live) setSuggestions(res.data?.suggestions ?? []); })
      .catch(e => { if (live) { setSuggestions([]); setSuggestionsError(extractApiErrorMessage(e)); } });
    return () => { live = false; };
  }, [chain]);

  useEffect(() => {
    let live = true;
    axios.get<TopicConsumers>(`/api/topic/${encodeURIComponent(topic)}/consumers`)
      .then(res => { if (live) setGroups((res.data?.groups ?? []).map(g => g.groupId)); })
      .catch(e => { if (live) { setGroups([]); setGroupsError(extractApiErrorMessage(e)); } });
    return () => { live = false; };
  }, [topic]);

  const chosenGroup = group ?? groups?.[0] ?? null;
  const options = useMemo(() => flowMetricOptions(neighbours, chain ? suggestions : [], suggestionsError, groups, groupsError)
    .map(o => o.kind === 'consumer-lag' && chosenGroup
      ? { ...o, labels: { topic, consumer_group: chosenGroup }, draft: consumerLagDraft(topic, chosenGroup) } : o),
  [neighbours, chain, suggestions, suggestionsError, groups, groupsError, chosenGroup, topic]);

  const isOn = (o: FlowMetricOption) => !o.unavailable && (ticked[o.id] ?? o.defaultOn);
  const labelsOf = (o: FlowMetricOption) => Object.fromEntries(
    Object.entries(o.labels).filter(([k]) => !unticked[`${o.id}|${k}`]));
  const chosen = options.filter(isOn);

  async function create() {
    setBusy(true);
    const done: typeof results = [];
    for (const o of chosen) {
      try {
        const res = await axios.post<MetricConfig | ''>('/api/metrics', withStaticLabels(o.draft, labelsOf(o)));
        const id = res.data ? res.data.id : null;
        if (id) void axios.post(`/api/metrics/${id}/refresh`).catch(() => undefined);
        done.push({ id: o.id, title: o.title, ok: true, text: `created as ${o.draft.name ?? metricName(o.title)}` });
      } catch (e) {
        done.push({ id: o.id, title: o.title, ok: false, text: extractApiErrorMessage(e) });
      }
    }
    setResults(done);
    // What was created is unticked, so a second click does not create it twice.
    setTicked(prev => ({ ...prev, ...Object.fromEntries(done.filter(d => d.ok).map(d => [d.id, false])) }));
    setBusy(false);
  }

  return (
    <aside role="dialog" aria-label={`Create metrics for ${topic}`}
      className="absolute right-3 top-3 bottom-3 w-[min(26rem,calc(100%-1.5rem))] z-20 flex flex-col
        rounded-xl border border-outline-variant bg-surface-container-low shadow-xl">
      <header className="flex items-start justify-between gap-2 px-4 pt-3 pb-2 border-b border-outline-variant/60">
        <div className="min-w-0">
          <h2 className="text-sm font-bold">Create metrics</h2>
          <p className="text-xs text-outline font-mono truncate" title={topic}>
            {neighbours.previous ? `${neighbours.previous} → ` : ''}<span className="text-on-surface">{topic}</span>{neighbours.next ? ` → ${neighbours.next}` : ''}
          </p>
        </div>
        <button type="button" onClick={onClose} aria-label="Close" className="w-7 h-7 rounded text-outline hover:text-primary">
          <span className="material-symbols-outlined text-[18px]" aria-hidden="true">close</span>
        </button>
      </header>

      <ul className="flex-1 overflow-auto custom-scrollbar px-4 py-2 space-y-3">
        {options.map(o => (
          <li key={o.id} className={`rounded-lg border p-3 text-sm ${isOn(o) ? 'border-primary/50 bg-primary/5' : 'border-outline-variant/60'}`}>
            <label className="flex items-start gap-2">
              <Checkbox checked={isOn(o)} onChange={v => setTicked(p => ({ ...p, [o.id]: v }))}
                disabled={!!o.unavailable} aria-label={o.title} />
              <span className="min-w-0">
                <span className="font-medium block">{o.title}</span>
                <span className="text-xs text-outline block">{o.unavailable ?? o.why}</span>
                {o.existing && <Badge tone="warning">Already exists: {o.existing}</Badge>}
              </span>
            </label>
            {o.kind === 'consumer-lag' && groups && groups.length > 1 && (
              <Select aria-label="Consumer group" className="mt-2" value={chosenGroup ?? ''} onChange={e => setGroup(e.target.value)}>
                {groups.map(g => <option key={g} value={g}>{g}</option>)}
              </Select>
            )}
            {!o.unavailable && (
              <div className="mt-2 flex flex-wrap gap-1.5" role="group" aria-label={`Labels of ${o.title}`}>
                {Object.entries(o.labels).map(([k, v]) => {
                  const key = `${o.id}|${k}`;
                  const on = !unticked[key];
                  return (
                    <button key={k} type="button" aria-pressed={on}
                      onClick={() => setUnticked(p => ({ ...p, [key]: on }))}
                      className={`px-2 py-0.5 rounded-full text-[11px] font-mono border ${on
                        ? 'border-primary/60 text-on-surface bg-primary/10' : 'border-outline-variant text-outline line-through'}`}>
                      {k}=<span className="opacity-80">{v}</span>
                    </button>
                  );
                })}
              </div>
            )}
            {o.draft.warningThreshold != null && !o.unavailable && (
              <p className="mt-1 text-[11px] text-outline">
                Thresholds: warning {o.draft.warningThreshold}, critical {o.draft.criticalThreshold ?? '—'} — edit them on Metrics.
              </p>
            )}
          </li>
        ))}
      </ul>

      <footer className="px-4 py-3 border-t border-outline-variant/60 space-y-2">
        {results.length > 0 && (
          <ul aria-label="Created metrics" className="text-xs space-y-0.5">
            {results.map(r => (
              <li key={r.id} className={r.ok ? 'text-[#8fd6a3]' : 'text-[#f58c8c]'} role={r.ok ? undefined : 'alert'}>
                {r.ok ? '✓' : '✗'} {r.title}: {r.text}
              </li>
            ))}
          </ul>
        )}
        <div className="flex items-center justify-between gap-2">
          <Link to="/metrics" className="text-xs text-primary hover:underline">Open Metrics</Link>
          <Button variant="primary" size="sm" icon="add_chart" onClick={create} disabled={busy || chosen.length === 0}>
            {busy ? 'Creating…' : `Create ${chosen.length} metric${chosen.length === 1 ? '' : 's'}`}
          </Button>
        </div>
      </footer>
    </aside>
  );
};
