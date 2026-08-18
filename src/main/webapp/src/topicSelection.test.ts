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
import { addTopicEntries, describeTopicEntry } from './topicSelection';

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
  const empty = { selection: [], added: [], unmatched: [], overflow: [], cap: null };

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

  it('says so when everything was already selected — the input must not look inert', () => {
    expect(describeTopicEntry({ ...empty, selection: ['a'] })).toBe('Already selected');
  });

  it('has nothing to say about an empty input', () => {
    expect(describeTopicEntry(empty)).toBeNull();
  });
});
