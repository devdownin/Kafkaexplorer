// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors

/*
 * Ce que vingt enregistrements permettent d'affirmer, et ce qu'ils ne permettent pas.
 *
 * Le cas qui porte ce fichier est le dernier : un champ présent sur deux messages sur vingt ne
 * doit pas produire « 100 % de timeouts ». C'est un décompte juste sur un dénominateur faux, et
 * c'est la façon la plus courante de mentir avec des chiffres exacts.
 */

import { describe, it, expect } from 'vitest';
import type { TopicMessage } from '../api/types';
import {
  describeSample, fieldValue, groupByField, MISSING_LABEL, reasonFields,
} from './deadLetterReasons';

function record(
  value: unknown, headers: Record<string, string | null> = {}, offset = 0,
): TopicMessage {
  return {
    partition: 0,
    offset,
    timestamp: 1_700_000_000_000 + offset,
    key: `K-${offset}`,
    value: value === null ? null : typeof value === 'string' ? value : JSON.stringify(value),
    headers,
    valueBytes: 0,
    truncated: false,
  };
}

describe('reasonFields', () => {
  it('ranks a field that promises a cause above one that does not', () => {
    const messages = [
      record({ failure_reason: 'timeout', amount: 12 }, { 'event-type': 'order.dead.lettered' }),
      record({ failure_reason: 'timeout', amount: 30 }, { 'event-type': 'order.dead.lettered' }),
    ];
    expect(reasonFields(messages)[0]).toMatchObject({ name: 'failure_reason', origin: 'payload' });
  });

  it('reads headers and payload alike', () => {
    const messages = [
      record({ state: 'DEAD' }, { 'original-topic': 'orders.validated' }),
      record({ state: 'DEAD' }, { 'original-topic': 'orders.enriched' }),
    ];
    const ids = reasonFields(messages).map(f => f.id);
    expect(ids).toContain('header:original-topic');
    expect(ids).toContain('field:state');
  });

  it('drops a field that is distinct on every record — it groups nothing', () => {
    // Un identifiant produirait autant de groupes que de messages ; ce n'est pas un regroupement.
    const messages = [0, 1, 2, 3].map(i => record({ id: `ORD-${i}`, reason: 'timeout' }, {}, i));
    const names = reasonFields(messages).map(f => f.name);
    expect(names).toContain('reason');
    expect(names).not.toContain('id');
  });

  it('survives a record that is not JSON at all', () => {
    // C'est précisément ce qu'une file de rebut contient : un message illisible ne doit pas
    // emporter la lecture des autres.
    const messages = [
      record('<<not json>>', { exception: 'ParseError' }),
      record('', { exception: 'ParseError' }),
      record({ reason: 'x' }, { exception: 'Timeout' }),
    ];
    expect(reasonFields(messages).map(f => f.name)).toContain('exception');
  });

  it('prefers a field that splits the sample over one that is constant', () => {
    // Mesuré sur la capture : `event-type`, constant, gagnait et affichait « 100 % » d'une seule
    // valeur — exact, et sans information.
    const messages = [
      record({ status: 'DEAD' }, { 'event-type': 'order.dead.lettered' }),
      record({ status: 'RETRY' }, { 'event-type': 'order.dead.lettered' }),
    ];
    expect(reasonFields(messages)[0].name).toBe('status');
  });
});

describe('groupByField', () => {
  const field = { id: 'field:reason', name: 'reason', origin: 'payload' as const, present: 3, distinct: 2 };

  it('counts the values, most frequent first', () => {
    const messages = [
      record({ reason: 'timeout' }), record({ reason: 'timeout' }), record({ reason: 'schema' }),
    ];
    expect(groupByField(messages, field)).toEqual([
      { value: 'timeout', count: 2, percent: (2 / 3) * 100, missing: false },
      { value: 'schema', count: 1, percent: (1 / 3) * 100, missing: false },
    ]);
  });

  it('counts records without the field against the whole sample, as their own row', () => {
    const messages = [
      record({ reason: 'timeout' }), record({ reason: 'timeout' }),
      ...Array.from({ length: 18 }, (_, i) => record({ state: 'DEAD' }, {}, i)),
    ];
    const groups = groupByField(messages, field);
    // Deux porteurs sur vingt : 10 %, jamais 100 %.
    expect(groups[0]).toMatchObject({ value: MISSING_LABEL, count: 18, missing: true });
    expect(groups[1]).toMatchObject({ value: 'timeout', count: 2, percent: 10 });
  });

  it('keeps an absence behind a value it ties with — an absence is not a cause', () => {
    const messages = [record({ reason: 'timeout' }), record({ state: 'DEAD' })];
    expect(groupByField(messages, field).map(g => g.missing)).toEqual([false, true]);
  });
});

describe('fieldValue', () => {
  it('renders a nested value rather than [object Object]', () => {
    const field = { id: 'field:cause', name: 'cause', origin: 'payload' as const, present: 1, distinct: 1 };
    expect(fieldValue(record({ cause: { code: 500 } }), field)).toBe('{"code":500}');
  });

  it('treats a null header as absent, never as a value', () => {
    const field = { id: 'header:exception', name: 'exception', origin: 'header' as const, present: 0, distinct: 0 };
    expect(fieldValue(record({}, { exception: null }), field)).toBeUndefined();
  });
});

describe('describeSample', () => {
  it('says it is a sample and not the window the curves cover', () => {
    const field = { id: 'field:reason', name: 'reason', origin: 'payload' as const, present: 2, distinct: 2 };
    const said = describeSample([record({}), record({})], field);
    expect(said).toContain('2 most recent records');
    expect(said).toContain('not a distribution over the window');
  });

  it('separates an unreadable queue from one whose records group into nothing', () => {
    expect(describeSample([], null)).toContain('No record could be read');
    expect(describeSample([record({})], null)).toContain('no field that groups them');
  });
});
