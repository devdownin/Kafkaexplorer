// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors

import { beforeEach, describe, expect, it } from 'vitest';
import {
  chainFromHits, chainRoute, clearFlowChains, FLOW_CHAINS_KEY, MAX_FLOW_CHAINS,
  MAX_FLOW_CHAIN_AGE_MS, pushFlowChain, readFlowChains, recordTracedChain, writeFlowChains,
} from './flowChains';
import type { FlowChainEvidence } from '../api/types';
import type { FlowHit } from './streamFlowLogic';

const NOW = 1_700_000_000_000;

function hit(topic: string, firstTimestamp: number, latencyFromPreviousMs: number | null): FlowHit {
  return {
    topic,
    occurrences: 1,
    firstTimestamp,
    lastTimestamp: firstTimestamp,
    firstPartition: 0,
    firstOffset: 12,
    firstKey: 'ORD-42',
    preview: '{"id":"ORD-42"}',
    latencyFromPreviousMs,
  };
}

function chain(topics: string[], tracedAt = NOW): FlowChainEvidence {
  return {
    messageKey: 'ORD-42',
    searchPath: null,
    tracedAt,
    hops: topics.map((topic, index) => ({
      topic,
      firstTimestamp: NOW + index * 1000,
      latencyFromPreviousMs: index === 0 ? null : 1000,
      occurrences: 1,
    })),
  };
}

beforeEach(() => {
  localStorage.clear();
});

describe('chainFromHits', () => {
  it('keeps the order the server chained the hits in', () => {
    const built = chainFromHits(
      [hit('a.topic', NOW, null), hit('b.topic', NOW + 800, 800)],
      { messageKey: 'ORD-42' }, NOW);

    expect(built?.hops.map(h => h.topic)).toEqual(['a.topic', 'b.topic']);
    expect(built?.hops[1].latencyFromPreviousMs).toBe(800);
    expect(built?.tracedAt).toBe(NOW);
  });

  it('refuses a single sighting — one topic is not a path', () => {
    expect(chainFromHits([hit('a.topic', NOW, null)], { messageKey: 'ORD-42' }, NOW)).toBeNull();
  });

  it('drops an empty criterion rather than storing a blank key', () => {
    const built = chainFromHits(
      [hit('a.topic', NOW, null), hit('b.topic', NOW + 1, 1)],
      { messageKey: '   ', searchPath: '' }, NOW);

    expect(built?.messageKey).toBeNull();
    expect(built?.searchPath).toBeNull();
  });
});

describe('readFlowChains', () => {
  it('returns nothing when the store is empty', () => {
    expect(readFlowChains(NOW)).toEqual([]);
  });

  it('erases an envelope of an unknown version rather than guessing its shape', () => {
    localStorage.setItem(FLOW_CHAINS_KEY, JSON.stringify({ v: 99, chains: [chain(['a', 'b'])] }));

    expect(readFlowChains(NOW)).toEqual([]);
    expect(localStorage.getItem(FLOW_CHAINS_KEY)).toBeNull();
  });

  it('erases corrupt JSON instead of throwing on a page load', () => {
    localStorage.setItem(FLOW_CHAINS_KEY, '{not json');

    expect(readFlowChains(NOW)).toEqual([]);
    expect(localStorage.getItem(FLOW_CHAINS_KEY)).toBeNull();
  });

  it('drops a chain older than the expiry — it describes topics that may not exist any more', () => {
    writeFlowChains([chain(['a', 'b'], NOW - MAX_FLOW_CHAIN_AGE_MS - 1), chain(['c', 'd'], NOW)]);

    const kept = readFlowChains(NOW);

    expect(kept).toHaveLength(1);
    expect(chainRoute(kept[0])).toBe('c → d');
  });

  it('drops an entry that no longer describes a path', () => {
    writeFlowChains([chain(['a'])]);

    expect(readFlowChains(NOW)).toEqual([]);
  });
});

describe('pushFlowChain', () => {
  it('puts the newest first and de-duplicates on the route, not on the key', () => {
    pushFlowChain(chain(['a', 'b'], NOW - 5000), NOW);
    const stored = pushFlowChain(chain(['a', 'b'], NOW), NOW);

    expect(stored).toHaveLength(1);
    expect(stored[0].tracedAt).toBe(NOW);   // the fresher latencies win
  });

  it('keeps different routes side by side, bounded', () => {
    for (let i = 0; i < MAX_FLOW_CHAINS + 3; i++) {
      pushFlowChain(chain([`a${i}`, `b${i}`], NOW + i), NOW + i);
    }

    const stored = readFlowChains(NOW + 100);
    expect(stored).toHaveLength(MAX_FLOW_CHAINS);
    expect(chainRoute(stored[0])).toBe(`a${MAX_FLOW_CHAINS + 2} → b${MAX_FLOW_CHAINS + 2}`);
  });
});

describe('recordTracedChain', () => {
  it('stores a real path and reports what it kept', () => {
    const recorded = recordTracedChain(
      [hit('a.topic', NOW, null), hit('b.topic', NOW + 500, 500)],
      { messageKey: 'ORD-42' }, NOW);

    expect(recorded).not.toBeNull();
    expect(readFlowChains(NOW)).toHaveLength(1);
  });

  it('writes nothing when the trace found a single topic', () => {
    expect(recordTracedChain([hit('a.topic', NOW, null)], { messageKey: 'ORD-42' }, NOW)).toBeNull();
    expect(localStorage.getItem(FLOW_CHAINS_KEY)).toBeNull();
  });
});

describe('clearFlowChains', () => {
  it('removes the store', () => {
    pushFlowChain(chain(['a', 'b']), NOW);
    clearFlowChains();

    expect(readFlowChains(NOW)).toEqual([]);
  });
});
