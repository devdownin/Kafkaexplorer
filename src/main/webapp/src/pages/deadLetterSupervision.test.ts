// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors

/*
 * Ce que cet écran affirme, et ce qu'il refuse d'affirmer.
 *
 * Deux règles portent presque tout le fichier, et ce sont celles que ce dépôt paie chaque fois
 * qu'elles sont enfreintes : une source n'est jamais devinée, et un rapport qui n'existe pas ne
 * se dessine pas à zéro.
 */

import { describe, it, expect } from 'vitest';
import type { TopicActivity } from '../api/types';
import {
  activityRequestTopics, assessQueue, describeShare, formatPercent, pairSource, shareSeries,
  shareShape, SHARE_SCALE_FLOOR, sourceCandidates, summarize, supervisionTopics,
} from './deadLetterSupervision';

function activity(topic: string, counts: number[], extra: Partial<TopicActivity> = {}): TopicActivity {
  return {
    topic,
    windowStartMs: 0,
    windowEndMs: counts.length * 3_600_000,
    bucketMs: 3_600_000,
    counts,
    total: counts.reduce((a, b) => a + b, 0),
    coveredFromMs: null,
    partitionsMeasured: 1,
    partitionsTotal: 1,
    available: true,
    note: null,
    ...extra,
  };
}

describe('sourceCandidates', () => {
  it('strips a dead-letter suffix in each of its spellings', () => {
    expect(sourceCandidates('orders.DLQ')).toEqual(['orders']);
    expect(sourceCandidates('orders-dlt')).toEqual(['orders']);
    expect(sourceCandidates('orders_DLQ')).toEqual(['orders']);
  });

  it('reads a retry marker in both directions', () => {
    expect(sourceCandidates('orders.retry.5m')).toEqual(['orders']);
    expect(sourceCandidates('orders-retry-0')).toEqual(['orders']);
    expect(sourceCandidates('retry-orders')).toEqual(['orders']);
  });

  it('offers the immediate link of a chained queue before the head of the chain', () => {
    // orders → orders.retry.5m → orders.retry.5m.DLQ : le taux d'échec de la file de rebut se
    // calcule contre ce qui l'alimente, pas contre le début du pipeline.
    expect(sourceCandidates('orders.retry.5m.DLQ')).toEqual(['orders.retry.5m', 'orders']);
  });

  it('does not manufacture candidates out of trailing segments', () => {
    // `5m` serait un candidat d'un balayage naïf, et finirait par exister sur un cluster assez
    // grand — ce qui produirait un taux d'échec calculé contre un topic sans rapport.
    expect(sourceCandidates('orders.retry.5m')).not.toContain('5m');
  });
});

describe('pairSource', () => {
  it('returns only a topic the cluster really has', () => {
    expect(pairSource('orders.DLQ', new Set(['orders', 'orders.DLQ']))).toBe('orders');
    expect(pairSource('orders.DLQ', new Set(['orders.DLQ']))).toBeNull();
  });
});

describe('supervisionTopics', () => {
  const topics = ['orders', 'orders.DLQ', 'payments', 'payments.retry.5m', 'shipments-dlt', 'audit'];

  it('keeps only queues, dead letters before retries', () => {
    const rows = supervisionTopics(topics);
    expect(rows.map(r => r.topic)).toEqual(['orders.DLQ', 'shipments-dlt', 'payments.retry.5m']);
    expect(rows.map(r => r.kind)).toEqual(['DLQ', 'DLT', 'RETRY']);
  });

  it('names the source it looked for when it found none', () => {
    const row = supervisionTopics(topics).find(r => r.topic === 'shipments-dlt')!;
    expect(row.source).toBeNull();
    expect(row.triedSource).toBe('shipments');
  });
});

describe('activityRequestTopics', () => {
  it('interleaves each queue with its source so a truncation drops whole rows', () => {
    const rows = supervisionTopics(['a', 'a.DLQ', 'b', 'b.DLQ']);
    expect(activityRequestTopics(rows)).toEqual(['a.DLQ', 'a', 'b.DLQ', 'b']);
  });

  it('asks for a shared source once', () => {
    const rows = supervisionTopics(['a', 'a.DLQ', 'a.retry.1']);
    expect(activityRequestTopics(rows)).toEqual(['a.DLQ', 'a', 'a.retry.1']);
  });
});

describe('shareSeries', () => {
  it('divides bucket by bucket and over the window', () => {
    const series = shareSeries(activity('a.DLQ', [1, 2]), activity('a', [100, 100]));
    expect(series.points).toEqual([1, 2]);
    expect(series.overall).toBeCloseTo(1.5);
    expect(series.available).toBe(true);
  });

  it('leaves a hole where the source produced nothing, never a zero', () => {
    const series = shareSeries(activity('a.DLQ', [0, 3]), activity('a', [0, 100]));
    // Le premier bucket n'a pas de taux d'échec : zéro s'y lirait « tout va bien ».
    expect(series.points).toEqual([null, 3]);
  });

  it('counts what the source cannot explain instead of hiding it', () => {
    const series = shareSeries(activity('a.DLQ', [5, 0]), activity('a', [0, 100]));
    expect(series.points[0]).toBeNull();
    expect(series.unexplained).toBe(1);
    expect(series.note).toContain('one hop');
  });

  it('flags a queue taking more than its source produced', () => {
    const series = shareSeries(activity('a.DLQ', [150]), activity('a', [100]));
    expect(series.overflow).toBe(true);
    expect(series.points).toEqual([150]);
    expect(series.note).toContain('redeliveries');
  });

  it('refuses two series that are not bucketed alike', () => {
    const series = shareSeries(activity('a.DLQ', [1, 2]), activity('a', [100]));
    expect(series.available).toBe(false);
    expect(series.note).toContain('not bucketed alike');
  });

  it('says which side is missing', () => {
    expect(shareSeries(activity('a.DLQ', [1]), null).note).toContain('No source topic');
    const unreadable = activity('a', [], { available: false, note: 'the broker timed out.' });
    expect(shareSeries(activity('a.DLQ', [1]), unreadable).note).toContain('the broker timed out.');
  });

  it('says the share is a ceiling where the source is only a floor', () => {
    const truncated = activity('a', [10, 10], { coveredFromMs: 1 });
    expect(shareSeries(activity('a.DLQ', [1, 1]), truncated).note).toContain('ceiling');
  });
});

describe('shareShape', () => {
  it('breaks the line at the holes and greys them', () => {
    const shape = shareShape([10, null, 10], 100, 20, 0);
    expect(shape.segments).toHaveLength(0);
    expect(shape.dots).toHaveLength(2);
    expect(shape.gaps).toEqual([{ x: 50, width: 50 }]);
  });

  it('joins consecutive measured points into one stroke', () => {
    const shape = shareShape([10, 20, null], 100, 20, 0);
    expect(shape.segments).toHaveLength(1);
    expect(shape.dots).toHaveLength(0);
  });

  it('keeps a small rate small — the scale has a floor', () => {
    const shape = shareShape([0.3], 100, 20, 0);
    expect(shape.top).toBe(SHARE_SCALE_FLOOR);
    // Le point reste près de la ligne de base plutôt que de toucher le haut de la boîte.
    expect(shape.points[0]!.y).toBeGreaterThan(10);
  });

  it('scales to the peak once it clears the floor', () => {
    expect(shareShape([4, 8], 100, 20, 0).top).toBe(8);
  });
});

describe('assessQueue', () => {
  it('reads silence as the good news on this screen', () => {
    const verdict = assessQueue(activity('a.DLQ', [0, 0, 0]), 'DLQ');
    expect(verdict.state).toBe('quiet');
    expect(verdict.tone).toBe('success');
  });

  it('separates a queue filling now from one that merely has content', () => {
    const steady = assessQueue(activity('a.DLQ', [5, 5, 5, 5, 5, 5]), 'DLQ');
    expect(steady.state).toBe('receiving');
    const surging = assessQueue(activity('a.DLQ', [1, 1, 1, 1, 1, 40]), 'DLQ');
    expect(surging.state).toBe('surging');
    expect(surging.tone).toBe('error');
  });

  it('never turns an unread topic into a quiet one', () => {
    const verdict = assessQueue(activity('a.DLQ', [], { available: false, note: 'no leader.' }), 'DLQ');
    expect(verdict.state).toBe('unknown');
    expect(verdict.detail).toContain('no leader.');
  });
});

describe('summarize', () => {
  it('counts only what was actually measured', () => {
    const rows = supervisionTopics(['a', 'a.DLQ', 'b.DLQ', 'c.retry.1']);
    const summary = summarize(rows, {
      'a.DLQ': activity('a.DLQ', [1, 1]),
      'b.DLQ': activity('b.DLQ', [], { available: false, note: 'unreadable' }),
    });
    expect(summary.topics).toBe(3);
    expect(summary.deadLetter).toBe(2);
    expect(summary.retry).toBe(1);
    expect(summary.measured).toBe(1);
    expect(summary.receiving).toBe(1);
    expect(summary.messages).toBe(2);
    expect(summary.unpaired).toBe(2);
  });
});

describe('formatPercent / describeShare', () => {
  it('keeps a sub-percent rate readable and rounds a large one', () => {
    expect(formatPercent(0.4237)).toBe('0.42%');
    expect(formatPercent(4.27)).toBe('4.3%');
    expect(formatPercent(42.7)).toBe('43%');
    expect(formatPercent(0)).toBe('0%');
  });

  it('states the absence of a source rather than an absence of failures', () => {
    const empty = shareSeries(activity('a.DLQ', [1]), null);
    expect(describeShare(empty, 'a.DLQ', null)).toContain('no source topic is paired');
  });
});
