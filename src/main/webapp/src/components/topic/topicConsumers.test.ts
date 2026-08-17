// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors

import { describe, expect, it } from 'vitest';
import {
  ConsumerGroupLag,
  PartitionLag,
  PartitionTimeLag,
  TopicConsumers,
  TopicTimeLag,
  describeDelay,
  describeScope,
  describeSummary,
  filterGroups,
  formatCount,
  formatDelay,
  healthOf,
  isComplete,
  progressOf,
  sortDelayPartitions,
  sortGroups,
  totalLag,
} from './topicConsumers';

const part = (over: Partial<PartitionLag> = {}): PartitionLag => ({
  partition: 0,
  committedOffset: 90,
  endOffset: 100,
  lag: 10,
  memberId: 'm1',
  clientId: 'c1',
  host: 'h1',
  ...over,
});

const group = (over: Partial<ConsumerGroupLag> = {}): ConsumerGroupLag => ({
  groupId: 'orders',
  type: 'CLASSIC',
  state: 'STABLE',
  members: 1,
  assignedMembers: 1,
  membersKnown: true,
  totalLag: 10,
  partitionsWithoutCommit: 0,
  partitions: [part()],
  error: null,
  ...over,
});

const consumers = (over: Partial<TopicConsumers> = {}): TopicConsumers => ({
  topic: 'demo.orders',
  groups: [group()],
  groupsExamined: 3,
  groupsEligible: 3,
  groupsInCluster: 3,
  truncated: false,
  available: true,
  warnings: [],
  ...over,
});

describe('healthOf', () => {
  it('calls a group at the end of every partition caught up', () => {
    expect(healthOf(group({ totalLag: 0 }))).toBe('CAUGHT_UP');
  });

  it('calls a reading group behind, not broken', () => {
    expect(healthOf(group({ totalLag: 4200 }))).toBe('BEHIND');
  });

  it('separates a group with no assigned member from one merely behind', () => {
    // Zéro membre et du retard : rien ne le résorbera, ce n'est pas le même problème.
    expect(healthOf(group({ assignedMembers: 0, totalLag: 4200 }))).toBe('STALLED');
  });

  it('does not call a group stalled on a membership nobody could read', () => {
    // Zéro membre par absence de réponse, pas par absence de membre : le serveur applique la même
    // règle, et l'audit en faisait sinon un constat critique sur un groupe Streams très sain.
    expect(healthOf(group({ membersKnown: false, assignedMembers: 0, totalLag: 4200 }))).toBe('BEHIND');
  });

  it('does not call a zero-lag group with no member stalled', () => {
    expect(healthOf(group({ assignedMembers: 0, totalLag: 0 }))).toBe('CAUGHT_UP');
  });

  it('flags a group that reads only part of the partitions', () => {
    expect(healthOf(group({ partitionsWithoutCommit: 2 }))).toBe('PARTIAL');
  });

  it('flags a committed offset past the end of the log', () => {
    expect(healthOf(group({ partitions: [part({ lag: -12 })] }))).toBe('AHEAD');
  });

  it('reports an unreadable group as unknown rather than caught up', () => {
    expect(healthOf(group({ totalLag: 0, error: 'coordinator unavailable' }))).toBe('UNKNOWN');
  });
});

describe('describeScope', () => {
  it('distinguishes "nobody consumes it" from "we only looked at a few"', () => {
    const none = describeScope(consumers({ groups: [] }));
    expect(none).toMatch(/No group holds a committed offset/);
    expect(none).toMatch(/all 3 eligible groups read/);

    const capped = describeScope(consumers({
      groups: [], groupsExamined: 200, groupsEligible: 2200, groupsInCluster: 3000, truncated: true,
    }));
    expect(capped).toMatch(/200 of 2200 eligible groups read/);
    // Le total du cluster reste dit, mais comme un contexte — pas comme le dénominateur, puisque
    // 800 des 3000 avaient été écartés avant même le plafond.
    expect(capped).toMatch(/of the cluster's 3000/);
  });

  it('does not present a filtered count as the whole cluster', () => {
    // 12 groupes listés, 3 éligibles : « tous les 3 groupes du cluster lus » était faux.
    const scope = describeScope(consumers({ groupsExamined: 3, groupsEligible: 3, groupsInCluster: 12 }));
    expect(scope).toMatch(/all 3 eligible groups read of the cluster's 12/);
  });

  it('never turns a failed read into a statement about the cluster', () => {
    // Le cas qui a motivé `available` : mêmes compteurs à zéro qu'un cluster vide, sens opposé.
    const scope = describeScope(consumers({
      groups: [], groupsExamined: 0, groupsEligible: 0, groupsInCluster: 0, available: false,
      warnings: ['Could not list the cluster\'s groups: broker unreachable'],
    }));
    expect(scope).toMatch(/could not be read/);
    expect(scope).not.toMatch(/no client group at all/);
  });

  it('says when the cluster simply has no group', () => {
    expect(describeScope(consumers({ groups: [], groupsExamined: 0, groupsInCluster: 0 })))
      .toMatch(/no client group at all/);
  });

  it('says when every group was excluded rather than unreadable', () => {
    expect(describeScope(consumers({ groups: [], groupsExamined: 0, groupsEligible: 0, groupsInCluster: 12 })))
      .toMatch(/None of the cluster's 12 groups could be measured/);
  });

  it('agrees in number with one group', () => {
    expect(describeScope(consumers())).toMatch(/1 group consumes this topic/);
  });

  it('returns nothing without a response', () => {
    expect(describeScope(null)).toBe('');
  });
});

describe('describeSummary', () => {
  it('says nothing when there is no group', () => {
    expect(describeSummary([])).toBeNull();
  });

  it('sums the lag and names what deserves naming', () => {
    const summary = describeSummary([
      group({ groupId: 'a', totalLag: 1500 }),
      group({ groupId: 'b', totalLag: 500, assignedMembers: 0 }),
      group({ groupId: 'c', totalLag: 0, partitionsWithoutCommit: 1 }),
    ]);
    expect(summary).toMatch(/2K messages behind in total/);
    expect(summary).toMatch(/1 group stalled/);
    expect(summary).toMatch(/1 reading only part of the partitions/);
  });
});

describe('sortGroups / filterGroups', () => {
  const three = [
    group({ groupId: 'b-mid', totalLag: 50, state: 'EMPTY' }),
    group({ groupId: 'a-big', totalLag: 900, state: 'STABLE' }),
    group({ groupId: 'c-none', totalLag: 0, state: 'STABLE' }),
  ];

  it('sorts by lag, worst first', () => {
    expect(sortGroups(three, 'lag', true).map(g => g.groupId)).toEqual(['a-big', 'b-mid', 'c-none']);
    expect(sortGroups(three, 'lag', false).map(g => g.groupId)).toEqual(['c-none', 'b-mid', 'a-big']);
  });

  it('sorts by name and by state', () => {
    expect(sortGroups(three, 'groupId', false).map(g => g.groupId)).toEqual(['a-big', 'b-mid', 'c-none']);
    expect(sortGroups(three, 'state', false)[0].state).toBe('EMPTY');
  });

  it('does not mutate the list it is given', () => {
    const before = three.map(g => g.groupId);
    sortGroups(three, 'lag', true);
    expect(three.map(g => g.groupId)).toEqual(before);
  });

  it('filters on the group id, case-insensitively', () => {
    expect(filterGroups(three, 'BIG').map(g => g.groupId)).toEqual(['a-big']);
    expect(filterGroups(three, '  ')).toHaveLength(3);
  });
});

describe('totalLag / formatCount / progressOf', () => {
  it('sums the known lags', () => {
    expect(totalLag([group({ totalLag: 10 }), group({ totalLag: 32 })])).toBe(42);
  });

  it('compacts large counts and trims a trailing zero', () => {
    expect(formatCount(999)).toBe('999');
    expect(formatCount(1500)).toBe('1.5K');
    expect(formatCount(2000)).toBe('2K');
    expect(formatCount(1_250_000)).toBe('1.3M');
    expect(formatCount(-4200)).toBe('-4.2K');
  });

  it('gives no progress bar without a committed offset', () => {
    expect(progressOf(part({ committedOffset: null, lag: null }))).toBeNull();
  });

  it('gives no progress bar on an empty partition', () => {
    // Une barre pleine dirait « tout consommé » là où il n'y a rien à consommer.
    expect(progressOf(part({ committedOffset: 0, endOffset: 0, lag: 0 }))).toBeNull();
  });

  it('clamps a committed offset past the end', () => {
    expect(progressOf(part({ committedOffset: 500, endOffset: 100, lag: -400 }))).toBe(1);
  });

  it('reports the consumed share', () => {
    expect(progressOf(part({ committedOffset: 25, endOffset: 100, lag: 75 }))).toBe(0.25);
  });
});

/* ──────────────────────────────────────────────────────────────────────────
 * Le retard en temps
 * ────────────────────────────────────────────────────────────────────────── */

function partitionDelay(over: Partial<PartitionTimeLag> = {}): PartitionTimeLag {
  return {
    partition: 0, committedOffset: 100, endOffset: 150, recordLag: 50,
    lagMs: 60_000, oldestWaitingTimestamp: 1_700_000_000_000, note: null, ...over,
  };
}

function timeLag(over: Partial<TopicTimeLag> = {}): TopicTimeLag {
  return {
    topic: 'demo.orders', groupId: 'orders-api', partitions: [partitionDelay()],
    maxLagMs: 60_000, avgLagMs: 60_000,
    partitionsMeasured: 1, partitionsCaughtUp: 0, partitionsWithoutCommit: 0, partitionsUnknown: 0,
    available: true, error: null, warnings: [], ...over,
  };
}

describe('formatDelay', () => {
  it('drops to the order of magnitude that reads', () => {
    expect(formatDelay(450)).toBe('450 ms');
    expect(formatDelay(45_000)).toBe('45 s');
    expect(formatDelay(600_000)).toBe('10 min');
    expect(formatDelay(3 * 3600_000)).toBe('3 h');
    expect(formatDelay(4 * 24 * 3600_000)).toBe('4 d');
  });
});

describe('describeDelay', () => {
  it('states the age and the partitions it is the worst of', () => {
    expect(describeDelay(timeLag())).toBe('The oldest waiting message is 60 s old (worst of 1 partition measured).');
  });

  it('says "at least" when partitions are missing — a partial maximum is a floor', () => {
    const text = describeDelay(timeLag({ partitionsUnknown: 2, partitionsMeasured: 1 }));

    expect(text).toContain('at least');
    expect(text).toContain('2 unreadable');
    expect(text).toContain('floor');
  });

  it('counts partitions with no commit apart from unreadable ones', () => {
    const text = describeDelay(timeLag({ partitionsWithoutCommit: 3 }));

    expect(text).toContain('3 never committed');
  });

  it('gives the server reason rather than a number when nothing could be measured', () => {
    const text = describeDelay(timeLag({
      available: false, maxLagMs: null, error: "Group 'x' has no committed offset on this topic.",
    }));

    expect(text).toBe("Group 'x' has no committed offset on this topic.");
  });
});

describe('sortDelayPartitions', () => {
  it('puts what could not be measured first, then the worst ages', () => {
    const rows = [
      partitionDelay({ partition: 0, lagMs: 1000 }),
      partitionDelay({ partition: 1, lagMs: null, note: 'compacted' }),
      partitionDelay({ partition: 2, lagMs: 90_000 }),
    ];

    // Une mesure absente demande une décision ; un âge se lit du pire au moindre.
    expect(sortDelayPartitions(rows).map(p => p.partition)).toEqual([1, 2, 0]);
  });

  it('does not mutate its input', () => {
    const rows = [partitionDelay({ partition: 5, lagMs: 10 }), partitionDelay({ partition: 1, lagMs: 99 })];
    sortDelayPartitions(rows);

    expect(rows.map(p => p.partition)).toEqual([5, 1]);
  });
});

describe('isComplete', () => {
  it('is false as soon as a partition was not measured', () => {
    expect(isComplete(timeLag())).toBe(true);
    expect(isComplete(timeLag({ partitionsUnknown: 1 }))).toBe(false);
    expect(isComplete(timeLag({ partitionsWithoutCommit: 1 }))).toBe(false);
  });
});
