import { describe, it, expect } from 'vitest';
import {
  tryParse, messageText, diffFields, valuesDiffer, pairSamples, visiblePairs,
  countDiffs, countCompared, coordinates, type TopicMessage,
} from './compare';

const message = (offset: number, value: string | null, partition = 0): TopicMessage => ({
  partition, offset, timestamp: 1_700_000_000_000 + offset, key: `K-${offset}`, value,
  headers: {}, valueBytes: value?.length ?? 0, truncated: false,
});

describe('tryParse', () => {
  it('parses a JSON object', () => {
    expect(tryParse('{"a":1}')).toEqual({ a: 1 });
  });

  it('returns null for anything that is not a JSON object', () => {
    expect(tryParse('<Order/>')).toBeNull();
    expect(tryParse('[1,2]')).toBeNull();   // un tableau ne se compare pas champ par champ
    expect(tryParse('42')).toBeNull();
    expect(tryParse('')).toBeNull();
    expect(tryParse(null)).toBeNull();
    expect(tryParse(undefined)).toBeNull();
  });
});

describe('messageText', () => {
  it('reads the value, and copes with a null payload', () => {
    expect(messageText(message(1, '{"a":1}'))).toBe('{"a":1}');
    expect(messageText(message(1, null))).toBe('');
    expect(messageText(undefined)).toBe('');
  });
});

describe('diffFields', () => {
  it('classifies added, removed, changed and same', () => {
    expect(diffFields({ same: 1, changed: 'a', removed: true }, { same: 1, changed: 'b', added: 9 }))
      .toEqual({ same: 'same', changed: 'changed', removed: 'removed', added: 'added' });
  });

  it('treats a null side as an empty object', () => {
    expect(diffFields(null, { a: 1 })).toEqual({ a: 'added' });
  });
});

describe('valuesDiffer', () => {
  it('compares JSON structurally, so key order does not matter', () => {
    expect(valuesDiffer(message(1, '{"a":1,"b":2}'), message(1, '{"a":1,"b":2}'))).toBe(false);
  });

  it('falls back to raw text when the payload is not JSON', () => {
    expect(valuesDiffer(message(1, '<Order id="1"/>'), message(1, '<Order id="1"/>'))).toBe(false);
    expect(valuesDiffer(message(1, '<Order id="1"/>'), message(1, '<Order id="2"/>'))).toBe(true);
  });

  it('counts a missing counterpart as a difference', () => {
    expect(valuesDiffer(message(1, '{"a":1}'), undefined)).toBe(true);
    expect(valuesDiffer(undefined, undefined)).toBe(true);
  });
});

describe('pairSamples', () => {
  it('pairs by position over the longer side', () => {
    const pairs = pairSamples([message(1, '{"a":1}')], [message(1, '{"a":1}'), message(2, '{"a":2}')]);
    expect(pairs).toHaveLength(2);
    expect(pairs[0].differs).toBe(false);
    expect(pairs[1].a).toBeUndefined();
    expect(pairs[1].differs).toBe(true);
  });

  it('numbers the pairs so both panes can line up', () => {
    expect(pairSamples([message(1, 'x'), message(2, 'y')], []).map((p) => p.index)).toEqual([0, 1]);
  });
});

describe('visiblePairs', () => {
  /*
   * Le défaut d'origine : chaque panneau filtrait sa propre liste puis recevait la liste non
   * filtrée de l'autre, indexée par la position dans la liste filtrée. Un message se retrouvait
   * donc comparé à un message qui n'était pas son vis-à-vis.
   */
  it('keeps both sides on the same pair when filtering', () => {
    const a = [message(1, '{"v":1}'), message(2, '{"v":2}'), message(3, '{"v":3}')];
    const b = [message(1, '{"v":1}'), message(2, '{"v":"CHANGED"}'), message(3, '{"v":3}')];
    const shown = visiblePairs(pairSamples(a, b), true);

    expect(shown).toHaveLength(1);
    expect(shown[0].index).toBe(1);
    expect(messageText(shown[0].a)).toBe('{"v":2}');
    expect(messageText(shown[0].b)).toBe('{"v":"CHANGED"}');
  });

  it('returns everything when the filter is off', () => {
    const pairs = pairSamples([message(1, 'a')], [message(1, 'b')]);
    expect(visiblePairs(pairs, false)).toHaveLength(1);
  });
});

describe('counters', () => {
  const pairs = pairSamples(
    [message(1, '{"v":1}'), message(2, '{"v":2}'), message(3, '{"v":3}')],
    [message(1, '{"v":1}'), message(2, '{"v":9}')],
  );

  it('counts only positions where both sides have a message', () => {
    expect(countCompared(pairs)).toBe(2);
  });

  it('does not count a missing counterpart as a field difference', () => {
    expect(countDiffs(pairs)).toBe(1);
  });
});

describe('coordinates', () => {
  it('names the record a card is showing', () => {
    expect(coordinates(message(42, '{}', 3))).toBe('p3 · offset 42');
    expect(coordinates(undefined)).toBe('');
  });
});
