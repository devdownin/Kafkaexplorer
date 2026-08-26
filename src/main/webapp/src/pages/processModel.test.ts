// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors

import { describe, it, expect } from 'vitest';
import {
  describeScope,
  describeTimeSource,
  describeUnavailable,
  formatDuration,
  minorityEnds,
  share,
  skewedEdges,
  slowestEdge,
  windowSpan,
} from './processModel';
import type { ProcessEdge, ProcessModel } from '../api/types';

const edge = (from: string, to: string, extra: Partial<ProcessEdge> = {}): ProcessEdge => ({
  from, to, occurrences: 10, cases: 10, p50Ms: 1_000, p95Ms: 2_000, maxMs: 3_000,
  outOfOrderCount: 0, ...extra,
});

const model = (extra: Partial<ProcessModel> = {}): ProcessModel => ({
  available: true,
  unavailableReason: null,
  cases: 100,
  events: 300,
  eventsWithoutCase: 0,
  windowStartMs: 1_767_225_600_000,
  windowEndMs: 1_767_225_600_000 + 7_200_000,
  eventTimeSource: 'MAPPED_FIELD',
  activities: [],
  edges: [],
  variants: [],
  starts: [],
  ends: [],
  repeats: [],
  spotlightCases: [],
  variantsOmitted: 0,
  edgesOmitted: 0,
  notes: [],
  ...extra,
});

describe('formatDuration', () => {
  /* Un décompte de millisecondes brut est un nombre que personne ne lit. */
  it('renders a duration a reader can weigh, at every magnitude', () => {
    expect(formatDuration(812)).toBe('812 ms');
    expect(formatDuration(3_200)).toBe('3.2 s');
    expect(formatDuration(246_000)).toBe('4.1 min');
    expect(formatDuration(7_200_000)).toBe('2.0 h');
  });

  it('keeps a sign rather than dropping it, and refuses a non-number', () => {
    expect(formatDuration(-500)).toBe('-500 ms');
    expect(formatDuration(Number.NaN)).toBe('—');
  });
});

describe('share', () => {
  it('is a percentage, and an em dash when there is nothing to divide by', () => {
    expect(share(95, 100)).toBe('95.0%');
    expect(share(1, 0)).toBe('—');
  });
});

describe('describeTimeSource', () => {
  /*
   * À énoncer, jamais à supposer : une latence mesurée sur l'horloge du broker est une autre
   * mesure que la même latence sur l'horodatage métier.
   */
  it('names the clock, and says what the fallback one measures', () => {
    expect(describeTimeSource('MAPPED_FIELD')).toContain('business timestamps');
    expect(describeTimeSource('MIXED')).toContain('partial fallback');
    expect(describeTimeSource('RECORD_TIMESTAMP')).toContain('produce time, not event time');
  });
});

describe('describeScope', () => {
  it('states the cases, the events, the span and the clock', () => {
    const line = describeScope(model());
    expect(line).toContain('100 cases');
    expect(line).toContain('300 events');
    expect(line).toContain('2.0 h');
    expect(line).toContain('business timestamps');
  });

  it('singularises a lone case rather than writing "1 cases"', () => {
    expect(describeScope(model({ cases: 1, events: 1 }))).toContain('1 case · 1 event');
  });

  /* Pas de processus n'est pas un processus vide — et la ligne d'en-tête ne doit pas l'imiter. */
  it('says there is no log rather than reporting zero of everything', () => {
    expect(describeScope(model({ available: false, cases: 0, events: 0 })))
      .toBe('No event log could be built');
  });
});

describe('windowSpan', () => {
  it('never renders a negative span', () => {
    expect(windowSpan(model({ windowStartMs: 100, windowEndMs: 0 }))).toBe('0 ms');
  });
});

describe('slowestEdge', () => {
  /*
   * Même règle que la chaîne de Stream Flow : « la plus lente » ne dit rien avec une seule
   * transition, et l'annoncer donnerait à une transition unique l'air d'un constat.
   */
  it('says nothing when there is only one transition', () => {
    expect(slowestEdge(model({ edges: [edge('a', 'b')] }))).toBeNull();
  });

  it('picks the worst p95, not the worst maximum', () => {
    const worst = slowestEdge(model({
      edges: [
        edge('a', 'b', { p95Ms: 2_000, maxMs: 90_000 }),
        edge('b', 'c', { p95Ms: 8_000, maxMs: 9_000 }),
      ],
    }));
    expect(worst?.from).toBe('b');
  });

  it('says nothing at all when no log was built', () => {
    expect(slowestEdge(model({ available: false, edges: [edge('a', 'b'), edge('b', 'c')] })))
      .toBeNull();
  });
});

describe('skewedEdges', () => {
  /* Deux horloges qui se contredisent est un constat sur le parc, pas un défaut à lisser. */
  it('reports only the transitions the broker saw in the opposite order', () => {
    const skewed = skewedEdges(model({
      edges: [edge('a', 'b'), edge('b', 'c', { outOfOrderCount: 3 })],
    }));
    expect(skewed).toHaveLength(1);
    expect(skewed[0].to).toBe('c');
  });
});

describe('minorityEnds', () => {
  /*
   * Rien n'est appelé orphelin : quelle activité doit terminer un processus est un fait métier que
   * cette application n'a pas. Ce qui est rendu est la distribution.
   */
  it('returns the ends other than the most frequent, and nothing when there is only one', () => {
    expect(minorityEnds(model({ ends: [{ activity: 'enriched', cases: 91 }] }))).toEqual([]);
    expect(minorityEnds(model({
      ends: [{ activity: 'enriched', cases: 91 }, { activity: 'validated', cases: 9 }],
    }))).toEqual([{ activity: 'validated', cases: 9 }]);
  });
});

describe('describeUnavailable', () => {
  it('passes the server reason through rather than inventing one', () => {
    expect(describeUnavailable(model({ available: false, unavailableReason: 'No correlation id is mapped.' })))
      .toBe('No correlation id is mapped.');
  });

  it('says a reason is missing instead of rendering null', () => {
    expect(describeUnavailable(model({ available: false }))).toContain('no reason was given');
  });
});
