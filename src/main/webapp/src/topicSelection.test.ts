// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors

/*
 * Le choix des topics, partagé par Stream Flow, Data Model et Process Mining.
 *
 * Ces cas vivaient dans `pages/dataModel.test.ts` tant que la logique y vivait aussi. Elle est
 * maintenant commune aux trois écrans, et le plafond — la seule chose qui les distinguait — est
 * devenu un paramètre : Data Model en passe un (celui de son endpoint), Process Mining n'en a
 * pas, ce que l'un des cas ci-dessous pose explicitement.
 */

import { describe, it, expect } from 'vitest';
import { addTopicEntries, describeTopicEntry, suggestBroaderPattern } from './topicSelection';

/** Un plafond quelconque, pour les cas qui en exercent un. Data Model passe le sien. */
const CAP = 30;

const catalog = [
  'demo.orders.1.received', 'demo.orders.2.validated', 'demo.payments.authorized',
  'internal.audit.history',
];

describe('addTopicEntries', () => {
  it('adds a single name', () => {
    const entry = addTopicEntries([], 'demo.payments.authorized', catalog);
    expect(entry.selection).toEqual(['demo.payments.authorized']);
    expect(entry.added).toEqual(['demo.payments.authorized']);
  });

  it('takes a pasted list, whatever separates it', () => {
    const entry = addTopicEntries(
      [], 'demo.orders.1.received, demo.orders.2.validated\ndemo.payments.authorized', catalog);
    expect(entry.selection).toHaveLength(3);
  });

  it('expands a pattern over the catalogue, with no extra request', () => {
    const entry = addTopicEntries([], 'demo.orders.*', catalog);
    expect(entry.selection).toEqual(['demo.orders.1.received', 'demo.orders.2.validated']);
  });

  it('reports a pattern that matches nothing instead of sending it as a topic name', () => {
    const entry = addTopicEntries([], 'nope.*', catalog);
    expect(entry.selection).toEqual([]);
    expect(entry.unmatched).toEqual(['nope.*']);
  });

  it('still accepts a plain name the catalogue does not know', () => {
    // A topic can exist before the 30s cache shows it.
    const entry = addTopicEntries([], 'brand.new.topic', catalog);
    expect(entry.selection).toEqual(['brand.new.topic']);
    expect(entry.unmatched).toEqual([]);
  });

  it('honours an explicit internal.* pattern', () => {
    // A screen's bulk "select all" may skip these; naming one is a deliberate request.
    expect(addTopicEntries([], 'internal.*', catalog).selection).toEqual(['internal.audit.history']);
  });

  it('never duplicates what is already selected', () => {
    const entry = addTopicEntries(['demo.orders.1.received'], 'demo.orders.*', catalog);
    expect(entry.selection).toEqual(['demo.orders.1.received', 'demo.orders.2.validated']);
    expect(entry.added).toEqual(['demo.orders.2.validated']);
  });

  it('stops at a cap when the screen has one, and reports what it left out', () => {
    const many = Array.from({ length: 40 }, (_, i) => `t${i}`);
    const entry = addTopicEntries([], '*', many, CAP);
    expect(entry.selection).toHaveLength(CAP);
    expect(entry.overflow).toHaveLength(10);
    expect(entry.cap).toBe(CAP);
  });

  it('has no cap unless the screen passes one — Process Mining bounds a prompt, not a topic count', () => {
    const many = Array.from({ length: 200 }, (_, i) => `t${i}`);
    const entry = addTopicEntries([], '*', many);
    expect(entry.selection).toHaveLength(200);
    expect(entry.overflow).toEqual([]);
    expect(entry.cap).toBeNull();
  });

  it('an empty input changes nothing', () => {
    const before = ['a'];
    const entry = addTopicEntries(before, '   ', catalog);
    expect(entry.selection).toBe(before);
    expect(entry.added).toEqual([]);
  });
});

describe('describeTopicEntry', () => {
  const empty = { selection: [], added: [], unmatched: [], overflow: [], suggestions: {}, cap: null };

  it('counts what went in', () => {
    expect(describeTopicEntry({ ...empty, selection: ['a', 'b'], added: ['a', 'b'] }))
      .toBe('2 topics added');
    expect(describeTopicEntry({ ...empty, selection: ['a'], added: ['a'] }))
      .toBe('1 topic added');
  });

  it('states a partial add rather than letting it pass for a whole one', () => {
    expect(describeTopicEntry({
      ...empty, selection: ['a'], added: ['a'], unmatched: ['nope.*'], overflow: ['b', 'c'], cap: CAP,
    })).toBe(`1 topic added · no topic matches nope.* · 2 left out — the request is capped at ${CAP} topics`);
  });

  it('names the broader form when an anchored pattern found nothing', () => {
    expect(describeTopicEntry({
      ...empty, unmatched: ['orders*'], suggestions: { 'orders*': '*orders*' },
    })).toBe('no topic matches orders* (try *orders*)');
  });

  it('stays quiet about a broader form when there is none to offer', () => {
    expect(describeTopicEntry({ ...empty, unmatched: ['nope.*'] }))
      .toBe('no topic matches nope.*');
  });

  it('says so when everything was already selected — the input must not look inert', () => {
    expect(describeTopicEntry({ ...empty, selection: ['a'] })).toBe('Already selected');
  });

  it('has nothing to say about an empty input', () => {
    expect(describeTopicEntry(empty)).toBeNull();
  });
});

/*
 * Les motifs sont ancrés, ce qui les rend précis : `demo.*` ne ramène pas `internal.demo.x`.
 * La contrepartie est que `orders*` ne trouve rien sur un catalogue en `demo.orders.…`, ce qui se
 * lit comme « ce topic n'existe pas ». L'échappatoire `*orders*` existait déjà ; ce qui manquait
 * était de la connaître. On propose donc, sans jamais élargir tout seul — élargir aurait dilué la
 * précision de tous les autres motifs pour offrir ce que la syntaxe donne déjà.
 */
describe('suggestBroaderPattern', () => {
  const catalog = ['demo.orders.1.received', 'demo.orders.2.validated', 'demo.payments.authorized'];

  it('offers the wrapped form when it is the one that would have matched', () => {
    expect(suggestBroaderPattern('orders*', catalog)).toBe('*orders*');
  });

  it('offers nothing when broadening would find nothing either', () => {
    expect(suggestBroaderPattern('nope*', catalog)).toBeNull();
  });

  it('offers nothing for a pattern that is already wrapped', () => {
    expect(suggestBroaderPattern('*orders*', catalog)).toBeNull();
  });

  it('ignores a plain name — it is not a pattern, and is accepted as typed', () => {
    expect(suggestBroaderPattern('demo.orders.9.new', catalog)).toBeNull();
  });

  it('reaches the note through addTopicEntries, which is where a user meets it', () => {
    const entry = addTopicEntries([], 'orders*', catalog);
    expect(entry.added).toEqual([]);
    expect(describeTopicEntry(entry)).toBe('no topic matches orders* (try *orders*)');
  });

  it('does not broaden on its own: the anchored pattern still decides what is added', () => {
    // Ce qui compte le plus ici — une suggestion ne doit rien sélectionner.
    expect(addTopicEntries([], 'orders*', catalog).selection).toEqual([]);
    expect(addTopicEntries([], 'demo.*', catalog).added).toHaveLength(3);
  });
});
