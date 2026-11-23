// SPDX-License-Identifier: AGPL-3.0-or-later
import { describe, expect, it } from 'vitest';
import type { ConsumerGroupLag, TopicActivity } from '../api/types';
import { DEFAULT_STRATA_LAYERS } from './strataWorkspace';
import { strataActivityLead, strataInspection, strataInvestigationSnapshot, strataLeads } from './strataInvestigation';

const bucketMs = 150_000;
const activity = (name: string): TopicActivity => ({
  topic: name, windowStartMs: 0, windowEndMs: 12 * bucketMs, bucketMs,
  counts: [20, 20, 20, 20, 20, 20, 20, 20, 20, 20, 20, 200],
  total: 420, coveredFromMs: null, partitionsMeasured: 2, partitionsTotal: 2,
  available: true, note: 'secret trace content must not be exported',
});
const stalledGroup: ConsumerGroupLag = {
  groupId: 'selected-only', type: 'consumer', state: 'EMPTY', members: 0,
  assignedMembers: 0, membersKnown: true, totalLag: 123,
  partitionsWithoutCommit: 0, partitions: [], error: null,
};

describe('Strata guided investigation evidence', () => {
  it('only marks a fully measured activity deviation, never missing or stale offsets', () => {
    expect(strataActivityLead('orders', activity('orders'), 11, 1000, 1000)?.kind).toBe('activity-deviation');
    expect(strataActivityLead('orders', { ...activity('orders'), partitionsMeasured: 1 }, 11, 1000, 1000)).toBeNull();
    expect(strataActivityLead('orders', { ...activity('orders'), coveredFromMs: 11 * bucketMs }, 4, 1000, 1000)).toBeNull();
    expect(strataActivityLead('orders', activity('orders'), 11, 1000, 100_000)).toBeNull();
  });

  it('never projects the selected group lag onto other topics', () => {
    const leads = strataLeads([
      { name: 'orders', activity: activity('orders') }, { name: 'payments', activity: activity('payments') },
    ], 11, 1000, 'orders', stalledGroup, 1000);
    expect(leads.filter(l => l.kind === 'selected-group-lag')).toHaveLength(1);
    expect(leads[0]).toMatchObject({ topic: 'orders', level: 'stalled' });
    expect(leads.find(l => l.topic === 'payments' && l.kind === 'selected-group-lag')).toBeUndefined();
  });

  it('creates an explicit allowlisted snapshot without raw traces, keys or source notes', () => {
    const report = strataInspection('orders', activity('orders'), 11, 1000,
      [{ from: 'source', to: 'orders', via: 'declared-flink-job' }],
      stalledGroup, 1000);
    const snapshot = strataInvestigationSnapshot(report,
      { filter: 'orders', quality: 'economy', layers: DEFAULT_STRATA_LAYERS }, 2000);
    expect(snapshot.schema).toBe('kafkaexplorer.strata.investigation.v1');
    expect(snapshot.evidence.selected).toBe(200);
    expect(snapshot.declaredLineage.incoming[0]).toMatchObject({ via: 'declared-flink-job' });
    expect(snapshot.selectedConsumerGroup?.groupId).toBe('selected-only');
    const serialized = JSON.stringify(snapshot);
    expect(serialized).not.toContain('secret trace content');
    expect(serialized).not.toContain('hits');
    expect(serialized).not.toContain('rawKey');
    expect(serialized).toContain('Kafka timestamp offsets');
  });
});
