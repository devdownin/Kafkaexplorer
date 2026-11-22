// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors

import { useEffect, useMemo, useState } from 'react';
import axios from 'axios';
import type { ConsumerGroupLag, TopicActivityResponse, TopicConsumers } from '../api/types';
import type { ParsedFlow } from './streamFlowLogic';
import type { StrataLink } from './strataLineage';
import { compareTopicActivity, investigateTopic, temporalAssociation, type TopicComparison } from './strataDiagnostics';
import { detectActivityAnomaly } from './strataTime';
import { comparisonDelta } from './strataWorkspace';

export type DiagnosticMode = 'investigate' | 'compare';

/** Sparse paths: a missing measurement is a gap, not the bottom of the curve. */
function paths(values: Array<number | null>, max: number): string[] {
  const segments: string[] = [];
  let current = '';
  values.forEach((value, index) => {
    if (value === null) {
      if (current) segments.push(current);
      current = '';
      return;
    }
    const x = 18 + (index / Math.max(1, values.length - 1)) * 480;
    const y = 126 - (value / max) * 106;
    current += `${current ? ' L' : 'M'}${x.toFixed(2)} ${y.toFixed(2)}`;
  });
  if (current) segments.push(current);
  return segments;
}

function ComparisonChart({ comparison, selectedBucket }: {
  comparison: TopicComparison; selectedBucket: number;
}) {
  const max = Math.max(1, ...comparison.firstPoints.filter((n): n is number => n !== null),
    ...comparison.secondPoints.filter((n): n is number => n !== null));
  const cursor = 18 + selectedBucket / Math.max(1, comparison.totalBuckets - 1) * 480;
  return (
    <div className="rounded-xl border border-outline-variant bg-surface-container-low p-3">
      <svg viewBox="0 0 520 145" className="w-full" role="img"
        aria-label="Synchronized comparison of the two topics, with missing activity shown as gaps">
        {[20, 73, 126].map(y => <line key={y} x1="16" y1={y} x2="506" y2={y}
          stroke="currentColor" strokeWidth=".5" opacity=".18" />)}
        <rect x={Math.max(16, cursor - 12)} y="8" width="24" height="125" rx="4"
          fill="currentColor" opacity=".055" />
        <line x1={cursor} y1="7" x2={cursor} y2="134" stroke="currentColor"
          strokeWidth="1.3" strokeDasharray="3 4" opacity=".52" />
        {paths(comparison.firstPoints, max).map((path, i) =>
          <path key={`a-${i}`} d={path} fill="none" stroke="#0c9da9" strokeWidth="3"
            strokeLinecap="round" strokeLinejoin="round" />)}
        {paths(comparison.secondPoints, max).map((path, i) =>
          <path key={`b-${i}`} d={path} fill="none" stroke="#9b76db" strokeWidth="3"
            strokeLinecap="round" strokeLinejoin="round" />)}
      </svg>
      <div className="flex flex-wrap items-center justify-between gap-2 text-xs text-on-surface-variant">
        <span>Earlier · {new Date(comparison.startMs).toLocaleTimeString()}</span>
        <span className="font-medium">Same interval on both topics</span>
        <span>{new Date(comparison.endMs).toLocaleTimeString()} · Later</span>
      </div>
    </div>
  );
}

function Readout({ label, value, suffix = '' }: { label: string; value: number | null; suffix?: string }) {
  return <div className="rounded-lg border border-outline-variant p-3 min-w-0">
    <dt className="text-xs text-on-surface-variant">{label}</dt>
    <dd className="mt-1 text-lg font-semibold tabular-nums text-on-surface">{value === null ? 'Unknown' : value.toLocaleString(undefined, { maximumFractionDigits: 1 })}{value === null ? '' : suffix}</dd>
  </div>;
}

function completeLag(group: ConsumerGroupLag | null | undefined): number | null {
  if (!group || group.error || group.partitions.length === 0 || group.partitionsWithoutCommit > 0 ||
    group.partitions.some(partition => partition.lag === null) || !Number.isFinite(group.totalLag)) return null;
  return group.totalLag;
}

export default function StrataDiagnosticPanel({ mode, topic, compareWith, topics, snapshot, links, trace,
  selectedBucket, selectedGroup, onChooseTopic, onCompareWith }: {
  mode: DiagnosticMode;
  topic: string | null;
  compareWith: string | null;
  topics: string[];
  snapshot: TopicActivityResponse | null;
  links: StrataLink[];
  trace: ParsedFlow | null;
  selectedBucket: number;
  selectedGroup: ConsumerGroupLag | null;
  onChooseTopic: (topic: string) => void;
  onCompareWith: (topic: string | null) => void;
}) {
  const [onlyNeighbors, setOnlyNeighbors] = useState(true);
  const investigation = useMemo(() => topic ? investigateTopic(topic, links, trace) : null, [topic, links, trace]);
  const candidates = topics.filter(name => name !== topic);
  const selectedPeer = compareWith && candidates.includes(compareWith) ? compareWith : null;
  const comparison = useMemo(() => topic && selectedPeer ?
    compareTopicActivity(snapshot, topic, selectedPeer, selectedBucket) : null,
  [snapshot, topic, selectedPeer, selectedBucket]);
  const association = useMemo(() => comparison ? temporalAssociation(comparison) : null, [comparison]);
  const relevant = links.filter(link => !onlyNeighbors || link.from === topic || link.to === topic);
  const [peerConsumers, setPeerConsumers] = useState<{ topic: string; groupId: string; data: TopicConsumers } | null>(null);
  const [peerLagError, setPeerLagError] = useState<string | null>(null);
  const groupId = selectedGroup?.groupId ?? null;

  // Compare lag only for the SAME explicitly selected consumer group. Fetch the
  // second topic on demand; absence in a bounded group scan is not zero lag.
  useEffect(() => {
    if (mode !== 'compare' || !selectedPeer || !groupId) return;
    const controller = new AbortController();
    let active = true;
    let busy = false;
    const poll = async () => {
      if (document.visibilityState === 'hidden' || busy) return;
      busy = true;
      try {
        const response = await axios.get<TopicConsumers>(
          `/api/topic/${encodeURIComponent(selectedPeer)}/consumers`,
          { signal: controller.signal, timeout: 15000 });
        if (active) {
          setPeerConsumers({ topic: selectedPeer, groupId, data: response.data });
          setPeerLagError(null);
        }
      } catch (failure) {
        if (active && !axios.isCancel(failure)) {
          setPeerLagError('Peer consumer positions could not be measured.');
          setPeerConsumers(null);
        }
      } finally { busy = false; }
    };
    void poll();
    const interval = window.setInterval(() => { void poll(); }, 30000);
    const visible = () => { if (document.visibilityState === 'visible') void poll(); };
    document.addEventListener('visibilitychange', visible);
    return () => {
      active = false; controller.abort(); window.clearInterval(interval);
      document.removeEventListener('visibilitychange', visible);
    };
  }, [mode, selectedPeer, groupId]);

  const peerData = peerConsumers?.topic === selectedPeer && peerConsumers.groupId === groupId
    ? peerConsumers.data : null;
  const peerGroup = peerData?.groups.find(group => group.groupId === groupId) ?? null;
  const firstLag = completeLag(selectedGroup);
  const secondLag = peerData?.available ? completeLag(peerGroup) : null;
  const rateDelta = comparison ? comparisonDelta(comparison.firstRate, comparison.secondRate) : null;
  const lagDelta = firstLag !== null && secondLag !== null ? firstLag - secondLag : null;

  return <div className="space-y-4" aria-live="polite">
    {mode === 'investigate' && <>
      <div>
        <h3 className="text-sm font-semibold text-on-surface">Investigation · evidence around a topic</h3>
        <p className="mt-1 text-xs text-on-surface-variant">
          Declared processing paths and bounded key-match evidence remain separate.
          Neither a Kafka-key match nor a Flink job definition proves an individual message transfer.
        </p>
      </div>
      {!topic ? <p className="text-sm text-on-surface-variant">Choose a topic to investigate its known neighbors.</p> : <>
        <div className="grid sm:grid-cols-3 gap-2">
          <Readout label="Declared incoming paths" value={investigation!.incoming.length} />
          <Readout label="Declared outgoing paths" value={investigation!.outgoing.length} />
          <Readout label="Observed key matches" value={investigation!.matchingKey?.occurrences ?? null} />
        </div>
        <div className="space-y-2">
          <label className="flex gap-2 items-center text-xs text-on-surface-variant">
            <input type="checkbox" checked={onlyNeighbors} onChange={e => setOnlyNeighbors(e.target.checked)}
              className="accent-primary" />
            Show selected topic’s known neighborhood only
          </label>
          <ul className="max-h-60 space-y-2 overflow-y-auto">
            {relevant.slice(0, 40).map(link =>
              <li key={`${link.from}:${link.to}:${link.via}`}
                className="rounded-lg border border-outline-variant bg-surface-container-low p-3">
                <div className="flex flex-wrap items-center gap-1 text-xs text-on-surface">
                  <button type="button" onClick={() => onChooseTopic(link.from)}
                    className="font-semibold text-primary hover:underline break-all">{link.from}</button>
                  <span aria-hidden="true">→</span>
                  <button type="button" onClick={() => onChooseTopic(link.to)}
                    className="font-semibold text-primary hover:underline break-all">{link.to}</button>
                </div>
                <p className="text-xs text-on-surface-variant mt-1">Declared Flink INSERT path · {link.via}</p>
              </li>)}
            {relevant.length === 0 && <li className="text-xs text-on-surface-variant py-2">
              No explicit Flink INSERT neighbor in the measured view. This does not prove the topic is isolated.
            </li>}
            {relevant.length > 40 && <li className="text-xs text-on-surface-variant">Only the first 40 links are listed.</li>}
          </ul>
        </div>
        <div className="rounded-lg border border-outline-variant p-3 text-xs text-on-surface-variant">
          {investigation?.matchingKey ? <>
            <span className="font-semibold text-on-surface">Bounded Kafka-key matches: </span>
            {investigation.matchingKey.occurrences.toLocaleString()} matches in the selected topic
            {investigation.matchingKey.capped ? ' (lower bound, capped)' : ''}, earliest at{' '}
            {new Date(investigation.matchingKey.firstTimestamp).toLocaleString()}.
            {investigation.tracePartial && ' Trace is partial; unscanned topics and records are unknown.'}
          </> : trace ? 'No key match in the scanned scope for this topic. A bounded scan cannot prove that no record exists.'
            : 'No key trace has been run. Use Trace a message key below to gather bounded record evidence.'}
        </div>
      </>}
    </>}
    {mode === 'compare' && <>
      <div>
        <h3 className="text-sm font-semibold text-on-surface">Two-topic comparison</h3>
        <p className="mt-1 text-xs text-on-surface-variant">
          Both series use one shared server clock and the same 30-minute window. Units are produced
          offsets per bucket; an incomplete partition or retention gap is unknown, never zero.
        </p>
      </div>
      {!topic ? <p className="text-sm text-on-surface-variant">Select the first topic in the list.</p> : <>
        <div className="grid sm:grid-cols-2 gap-2">
          <div className="rounded-lg border border-teal-500/40 bg-surface-container-low p-3 min-w-0">
            <div className="text-xs font-semibold text-teal-600 dark:text-teal-300">Topic A · selected</div>
            <p className="font-semibold break-all text-sm text-on-surface mt-1">{topic}</p>
          </div>
          <label className="rounded-lg border border-violet-400/40 bg-surface-container-low p-3 text-xs font-semibold text-violet-600 dark:text-violet-300">
            Topic B · compare with
            <select aria-label="Topic B to compare" value={selectedPeer ?? ''}
              onChange={e => onCompareWith(e.target.value || null)}
              className="block w-full mt-1 rounded-md border border-outline-variant bg-surface px-2 py-2 text-sm font-normal text-on-surface">
              <option value="">Choose another visible topic</option>
              {candidates.map(name => <option key={name} value={name}>{name}</option>)}
            </select>
          </label>
        </div>
        {!selectedPeer && <p className="text-xs text-on-surface-variant">Select Topic B to compare synchronized offsets and temporal association.</p>}
        {selectedPeer && !comparison && <p className="text-xs text-on-surface-variant">
          Comparison unavailable: one topic has no sample in the current scope, or their time windows differ.
        </p>}
        {comparison && <>
          <div className="grid grid-cols-2 gap-2">
            <Readout label="A · current bucket" value={comparison.firstSlice} />
            <Readout label="B · current bucket" value={comparison.secondSlice} />
            <Readout label="A · offsets/min" value={comparison.firstRate} />
            <Readout label="B · offsets/min" value={comparison.secondRate} />
          </div>
          <div className="rounded-xl border border-outline-variant bg-surface-container-low p-3 text-xs text-on-surface-variant">
            <p className="font-semibold text-on-surface">Production-rate difference · A − B</p>
            <p className="mt-1 text-base font-semibold tabular-nums text-on-surface">
              {rateDelta === null ? 'Unknown (incomplete coverage)' :
                `${rateDelta.absolute >= 0 ? '+' : ''}${rateDelta.absolute.toLocaleString(undefined, { maximumFractionDigits: 1 })} offsets/min`}
            </p>
            <p>{rateDelta?.percent === null || rateDelta === null ? 'Relative difference unavailable' :
              `${rateDelta.percent >= 0 ? '+' : ''}${rateDelta.percent.toFixed(1)}% against B`}</p>
          </div>
          <ComparisonChart comparison={comparison} selectedBucket={selectedBucket} />
          <div className="flex flex-wrap gap-3 text-xs text-on-surface-variant">
            <span><span className="text-teal-600 dark:text-teal-300 font-bold">━</span> A: {topic}</span>
            <span><span className="text-violet-600 dark:text-violet-300 font-bold">━</span> B: {selectedPeer}</span>
            <span>{comparison.pairedBuckets}/{comparison.totalBuckets} complete matched buckets</span>
          </div>
          {association ? <div className="rounded-lg border border-outline-variant bg-surface-container-low p-3 space-y-1">
            <h4 className="font-semibold text-sm text-on-surface">Exploratory temporal association</h4>
            <p className="text-sm text-on-surface">
              Pearson r = {association.coefficient.toFixed(2)} · {association.pairedBuckets} aligned observations
              {association.direction === 'simultaneous' ? ' · synchronous' :
                ` · ${association.direction === 'first-leads-second' ? 'A precedes B' : 'B precedes A'} by ${association.lagBuckets * comparison.bucketMs / 60000} minutes`}.
            </p>
            <p className="text-xs text-on-surface-variant">
              The strongest positive correlation among 0, ±1 and ±2 buckets is descriptive only.
              It is not statistical significance, an operational alert, a traced message or causal evidence.
            </p>
          </div> : <p className="text-xs text-on-surface-variant">
            No defensible temporal association: coverage is incomplete, a series is constant,
            or none of the exploratory shifts reached r ≥ 0.65.
          </p>}
          <div className="rounded-xl border border-outline-variant p-3 space-y-2">
            <h4 className="text-xs font-semibold text-on-surface">Consumer lag · same group on A and B</h4>
            <p className="text-xs text-on-surface-variant">
              {groupId ? `Group: ${groupId}` : 'Choose a group on topic A in the consumer inspector to enable lag comparison.'}
              {' '}Lag is scoped to this group, never the topic as a whole.
            </p>
            {groupId && <>
              <div className="grid grid-cols-2 gap-2">
                <Readout label="A · committed lag" value={firstLag} />
                <Readout label="B · committed lag" value={secondLag} />
              </div>
              <p className="text-xs text-on-surface-variant">
                Difference A − B: {lagDelta === null ? 'Unknown (missing group, commits or coverage)' :
                  lagDelta.toLocaleString() + ' offsets'}
              </p>
              {peerLagError && <p role="status" className="text-xs text-error">{peerLagError}</p>}
              {!peerData && !peerLagError && <p className="text-xs text-on-surface-variant">Loading peer consumer positions…</p>}
              {peerData?.truncated && <p className="text-xs text-on-surface-variant">
                Peer group discovery is bounded; omitted groups remain unknown.
              </p>}
            </>}
          </div>
          <div className="grid gap-2 sm:grid-cols-2">
            {([comparison.first, comparison.second] as const).map((name, index) => {
              const a = snapshot?.topics[name];
              const warning = detectActivityAnomaly(a, selectedBucket);
              return <div key={name} className="rounded-lg border border-outline-variant p-3 text-xs text-on-surface-variant">
                <span className="font-medium text-on-surface">Topic {index === 0 ? 'A' : 'B'}</span>
                <p>Coverage: {a?.available ? `${a.partitionsMeasured}/${a.partitionsTotal} partitions` : 'unknown'}</p>
                <p>Deviation in slice: {warning ? `${warning.kind} candidate (${warning.ratio.toFixed(1)}× baseline)` : 'none established'}</p>
              </div>;
            })}
          </div>
        </>}
      </>}
    </>}
  </div>;
}
