// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors

import type { ConsumerGroupLag, TopicActivity } from '../api/types';
import { consumerHealth } from './StrataConsumerPanel';
import { detectActivityAnomaly, type ActivityAnomaly } from './strataTime';
import type { StrataLink } from './strataLineage';
import { topicEvidence, type TopicEvidence, type StrataLayers } from './strataWorkspace';
import type { StrataQuality } from './strataView';

/** Evidence map: these are leads, NOT a computed cluster health score. */
export interface StrataLead {
  topic: string;
  kind: 'activity-deviation' | 'selected-group-lag';
  level: 'watch' | 'stalled';
  label: string;
  explanation: string;
  source: 'Kafka timestamp offsets' | 'Selected consumer group commits';
}
export interface StrataInspection {
  topic: string;
  evidence: TopicEvidence;
  sampledAtMs: number | null;
  selectedBucket: number;
  interval: { startMs: number; endMs: number } | null;
  anomaly: ActivityAnomaly | null;
  incoming: StrataLink[];
  outgoing: StrataLink[];
  consumer: { groupId: string; health: string; totalLag: number; partitionsWithoutCommit: number;
    membersKnown: boolean; assignedMembers: number } | null;
  leads: StrataLead[];
}

function validInterval(activity: TopicActivity | undefined, index: number) {
  if (!activity || !Number.isInteger(index) || index < 0 ||
    !Number.isFinite(activity.bucketMs) || activity.bucketMs <= 0 ||
    !Number.isFinite(activity.windowStartMs) || !Number.isFinite(activity.windowEndMs) ||
    activity.windowEndMs - activity.windowStartMs !== activity.counts.length * activity.bucketMs ||
    index >= activity.counts.length) return null;
  const startMs = activity.windowStartMs + index * activity.bucketMs;
  return { startMs, endMs: startMs + activity.bucketMs };
}

function consumerLead(topic: string, group: ConsumerGroupLag): StrataLead | null {
  const health = consumerHealth(group);
  if (health === 'STALLED') return {
    topic, kind: 'selected-group-lag', level: 'stalled',
    label: 'Selected group has no assigned members',
    explanation: 'Backlog with zero assigned members for the selected consumer group only; not a cluster-wide outage.',
    source: 'Selected consumer group commits',
  };
  if (health === 'BEHIND') return {
    topic, kind: 'selected-group-lag', level: 'watch', label: 'Selected group has committed lag',
    explanation: 'Observed offset lag in the selected group; a lag count alone does not establish overload.',
    source: 'Selected consumer group commits',
  };
  if (health === 'PARTIAL' || health === 'AHEAD') return {
    topic, kind: 'selected-group-lag', level: 'watch',
    label: 'Selected group position requires verification',
    explanation: 'Commit coverage is incomplete or a committed offset is ahead; no exact lag conclusion is available.',
    source: 'Selected consumer group commits',
  };
  return null;
}

export function strataActivityLead(topic: string, activity: TopicActivity | undefined, bucket: number,
  sampledAtMs: number | null, now = Date.now()): StrataLead | null {
  const status = topicEvidence(activity, bucket, sampledAtMs, now).status;
  if (status === 'unavailable' || status === 'stale') return null;
  const anomaly = detectActivityAnomaly(activity, bucket);
  if (!anomaly) return null;
  return {
    topic, kind: 'activity-deviation', level: 'watch',
    label: anomaly.kind === 'spike' ? 'Offset production spike candidate' : 'Offset production drop candidate',
    explanation: 'Compared with four prior complete buckets (local heuristic, not a broker alert or a congestion diagnosis).',
    source: 'Kafka timestamp offsets',
  };
}

/** Selected-group lag is intentionally NOT applied to other topics. */
export function strataLeads(
  topics: readonly { name: string; activity?: TopicActivity }[],
  bucket: number, sampledAtMs: number | null,
  selected: string | null, selectedGroup: ConsumerGroupLag | null, now = Date.now(),
): StrataLead[] {
  const leads = topics.flatMap(topic => {
    const lead = strataActivityLead(topic.name, topic.activity, bucket, sampledAtMs, now);
    return lead ? [lead] : [];
  });
  if (selected && selectedGroup && topics.some(t => t.name === selected)) {
    const lag = consumerLead(selected, selectedGroup);
    if (lag) leads.push(lag);
  }
  return leads.sort((a, b) => (a.level === 'stalled' ? 0 : 1) - (b.level === 'stalled' ? 0 : 1) ||
    a.topic.localeCompare(b.topic)).slice(0, 24);
}

export function strataInspection(
  topic: string, activity: TopicActivity | undefined, bucket: number, sampledAtMs: number | null,
  links: readonly StrataLink[], selectedGroup: ConsumerGroupLag | null, now = Date.now(),
): StrataInspection {
  const evidence = topicEvidence(activity, bucket, sampledAtMs, now);
  return {
    topic, evidence, sampledAtMs, selectedBucket: bucket,
    interval: validInterval(activity, bucket),
    anomaly: evidence.status !== 'stale' && evidence.status !== 'unavailable'
      ? detectActivityAnomaly(activity, bucket) : null,
    incoming: links.filter(link => link.to === topic).slice(0, 32),
    outgoing: links.filter(link => link.from === topic).slice(0, 32),
    consumer: selectedGroup ? {
      groupId: selectedGroup.groupId, health: consumerHealth(selectedGroup),
      totalLag: selectedGroup.totalLag, partitionsWithoutCommit: selectedGroup.partitionsWithoutCommit,
      membersKnown: selectedGroup.membersKnown, assignedMembers: selectedGroup.assignedMembers,
    } : null,
    leads: strataLeads([{ name: topic, activity }], bucket, sampledAtMs, topic, selectedGroup, now),
  };
}

export interface StrataSnapshotView {
  filter: string;
  quality: StrataQuality;
  layers: StrataLayers;
}

/**
 * Explicit local export of an immutable point-in-time diagnostic. Strict allowlist:
 * NEVER serialize raw trace records, Kafka record keys, headers, messages or tokens.
 * No automatic storage, server endpoint or background collection.
 */
export function strataInvestigationSnapshot(inspection: StrataInspection, view: StrataSnapshotView,
  createdAtMs: number) {
  return {
    schema: 'kafkaexplorer.strata.investigation.v1',
    createdAt: new Date(createdAtMs).toISOString(),
    topic: inspection.topic,
    selectedBucket: inspection.selectedBucket,
    interval: inspection.interval,
    sampledAt: inspection.sampledAtMs === null ? null : new Date(inspection.sampledAtMs).toISOString(),
    evidence: { ...inspection.evidence },
    anomalies: inspection.anomaly ? { ...inspection.anomaly } : null,
    selectedConsumerGroup: inspection.consumer ? { ...inspection.consumer } : null,
    declaredLineage: {
      incoming: inspection.incoming.map(({ from, to, via }) => ({ from, to, via })),
      outgoing: inspection.outgoing.map(({ from, to, via }) => ({ from, to, via })),
    },
    leads: inspection.leads.map(({ topic, kind, level, label, explanation, source }) =>
      ({ topic, kind, level, label, explanation, source })),
    view: { filter: view.filter, quality: view.quality, layers: { ...view.layers } },
    caveats: [
      'Offset production is not retained-record count or payload flow.',
      'Selected-group lag cannot be generalized to other groups or topics.',
      'Temporal coincidences and Flink lineage are not proof of record causality.',
      'Unavailable or stale readings must not be treated as zero.',
    ],
  };
}
