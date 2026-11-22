// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors

import { useEffect, useMemo, useState } from 'react';
import axios from 'axios';
import type { ConsumerGroupLag, PartitionLag, TopicConsumers } from '../api/types';

export function consumerHealth(group: ConsumerGroupLag): string {
  if (group.error) return 'UNKNOWN';
  if (group.partitions.some(p => p.lag !== null && p.lag < 0)) return 'AHEAD';
  if (group.membersKnown && group.assignedMembers === 0 && group.totalLag > 0) return 'STALLED';
  if (group.partitionsWithoutCommit > 0) return 'PARTIAL';
  return group.totalLag > 0 ? 'BEHIND' : 'CAUGHT_UP';
}

function PartitionBar({ partition, maxLag }: { partition: PartitionLag; maxLag: number }) {
  const lag = partition.lag;
  const width = lag === null ? 0 : Math.max(0, Math.min(100, lag / Math.max(1, maxLag) * 100));
  const color = lag === null ? 'bg-outline-variant' : lag < 0 ? 'bg-error' : lag > 0 ? 'bg-amber-500' : 'bg-emerald-500';
  return (
    <li className="grid grid-cols-[3rem_minmax(0,1fr)_5.5rem] gap-2 items-center py-1.5 text-xs">
      <span className="text-on-surface-variant font-mono">P{partition.partition}</span>
      <div className="h-2 overflow-hidden rounded-full bg-surface-container-high" aria-hidden="true">
        <div className={`h-full rounded-full ${color}`} style={{ width: `${lag === 0 ? 3 : width}%` }} />
      </div>
      <span className="text-on-surface text-right tabular-nums" title={partition.committedOffset === null ? 'Never committed' : `Committed: ${partition.committedOffset}; end: ${partition.endOffset}`}>
        {lag === null ? 'No commit' : lag.toLocaleString()}
      </span>
    </li>
  );
}

export default function StrataConsumerPanel({ topic, onGroupSelect }: {
  topic: string;
  onGroupSelect: (group: ConsumerGroupLag | null) => void;
}) {
  const [data, setData] = useState<TopicConsumers | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [groupId, setGroupId] = useState<string | null>(null);

  useEffect(() => {
    const controller = new AbortController();
    let active = true;
    const fetchData = async () => {
      if (document.visibilityState === 'hidden') return;
      try {
        const response = await axios.get<TopicConsumers>(`/api/topic/${encodeURIComponent(topic)}/consumers`, {
          signal: controller.signal, timeout: 15000,
        });
        if (!active) return;
        setData(response.data);
        setError(null);
        setGroupId(previous => {
          const current = response.data.groups.find(g => g.groupId === previous) ?? response.data.groups[0] ?? null;
          onGroupSelect(current);
          return current?.groupId ?? null;
        });
      } catch (failure) {
        if (!active || axios.isCancel(failure)) return;
        setData(null);
        setError('Consumer group details could not be read.');
        onGroupSelect(null);
      }
    };
    void fetchData();
    const interval = window.setInterval(() => { void fetchData(); }, 30000);
    const onVisible = () => { if (document.visibilityState === 'visible') void fetchData(); };
    document.addEventListener('visibilitychange', onVisible);
    return () => {
      active = false;
      controller.abort();
      window.clearInterval(interval);
      document.removeEventListener('visibilitychange', onVisible);
    };
  }, [topic, onGroupSelect]);

  const current = useMemo(() => data?.groups.find(g => g.groupId === groupId) ?? null, [data, groupId]);
  const sortedPartitions = useMemo(() => [...(current?.partitions ?? [])].sort((a, b) => a.partition - b.partition), [current]);
  const maxLag = Math.max(1, ...sortedPartitions.map(p => Math.max(0, p.lag ?? 0)));
  const health = current ? consumerHealth(current) : null;

  return (
    <section className="mt-5 border-t border-outline-variant pt-4 space-y-3">
      <h3 className="text-sm font-semibold text-on-surface">Consumer groups</h3>
      <p className="text-xs text-on-surface-variant">Lag is scoped to the selected group, not to the topic as a whole.</p>
      {error && <p role="alert" className="text-sm text-error">{error}</p>}
      {!data && !error && <p className="text-sm text-on-surface-variant">Loading consumer positions…</p>}
      {data && !data.available && <p className="text-sm text-on-surface-variant">Group data unavailable. No consumption state can be inferred.</p>}
      {data?.available && <>
        <p className="text-xs text-on-surface-variant">
          Examined {data.groupsExamined} of {data.groupsEligible} eligible groups
          {data.truncated ? ' · scope truncated' : ''}
        </p>
        {data.groups.length === 0 ? <p className="text-xs text-on-surface-variant">No committing groups found in the examined scope.</p> : <>
          <label className="block text-xs font-medium text-on-surface-variant" htmlFor="strata-consumer-group">Inspect group</label>
          <select
            id="strata-consumer-group"
            className="w-full rounded-lg border border-outline-variant bg-surface px-2 py-2 text-sm text-on-surface"
            value={current?.groupId ?? ''}
            onChange={event => {
              const group = data.groups.find(g => g.groupId === event.target.value) ?? null;
              setGroupId(group?.groupId ?? null);
              onGroupSelect(group);
            }}
          >
            {data.groups.map(group => <option key={group.groupId} value={group.groupId}>{group.groupId}</option>)}
          </select>
          {current && <>
            <dl className="grid grid-cols-2 gap-3 text-sm">
              <div><dt className="text-xs text-on-surface-variant">Health</dt><dd className="font-semibold text-on-surface">{health}</dd></div>
              <div><dt className="text-xs text-on-surface-variant">Total committed lag</dt><dd className="font-semibold tabular-nums text-on-surface">{current.totalLag.toLocaleString()}</dd></div>
              <div><dt className="text-xs text-on-surface-variant">Assigned members</dt><dd className="text-on-surface">{current.membersKnown ? current.assignedMembers : 'Unknown'}</dd></div>
              <div><dt className="text-xs text-on-surface-variant">Partitions without commit</dt><dd className="text-on-surface">{current.partitionsWithoutCommit}</dd></div>
            </dl>
            {current.error && <p className="text-xs text-error">{current.error}</p>}
            <div className="space-y-1">
              <p className="text-xs text-on-surface-variant">Partition lag · bars relative to the largest reported lag</p>
              <ul className="max-h-56 overflow-auto">
                {sortedPartitions.slice(0, 100).map(partition =>
                  <PartitionBar key={partition.partition} partition={partition} maxLag={maxLag} />)}
              </ul>
              {sortedPartitions.length > 100 && <p className="text-xs text-on-surface-variant">{sortedPartitions.length - 100} more partitions. Open Topic Explorer for the full list.</p>}
            </div>
          </>}
        </>}
        {data.warnings.length > 0 && <p role="status" className="text-xs text-on-surface-variant">{data.warnings.join(' ')}</p>}
      </>}
    </section>
  );
}
