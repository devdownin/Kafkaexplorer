import { describe, it, expect } from 'vitest';
import { criteriaFromQuery, splitOnMatches } from './TopicSearchPanel';
import { buildTopicSearchQuery, parseTraceParams } from '../../pages/streamFlow';

describe('splitOnMatches', () => {
  it('returns the whole text when there is nothing to highlight', () => {
    expect(splitOnMatches('order shipped', '', false)).toEqual(['order shipped']);
  });

  it('puts matches at odd indexes so they can be marked', () => {
    expect(splitOnMatches('a-SKU-b', 'SKU', true)).toEqual(['a-', 'SKU', '-b']);
  });

  it('is case insensitive by default and keeps the original casing', () => {
    expect(splitOnMatches('Order SHIPPED now', 'shipped', false))
      .toEqual(['Order ', 'SHIPPED', ' now']);
  });

  it('respects case when asked to', () => {
    expect(splitOnMatches('Order SHIPPED', 'shipped', true)).toEqual(['Order SHIPPED']);
  });

  it('splits every occurrence', () => {
    expect(splitOnMatches('ab ab ab', 'ab', true)).toEqual(['', 'ab', ' ', 'ab', ' ', 'ab', '']);
  });

  it('reassembles into the original text', () => {
    const text = '{"status":"NEW","child":{"status":"NEW"}}';
    expect(splitOnMatches(text, 'status', false).join('')).toBe(text);
  });
});

describe('criteriaFromQuery', () => {
  it('ignores an ordinary topic URL', () => {
    expect(criteriaFromQuery('')).toBeNull();
    expect(criteriaFromQuery('?readMode=latest-offset')).toBeNull();
    expect(criteriaFromQuery('?mode=NONSENSE&q=x')).toBeNull();
  });

  /** Le bouton « Search » du panneau refuserait le même critère : ne pas le lancer non plus. */
  it('ignores a search with nothing to look for', () => {
    expect(criteriaFromQuery('?mode=CONTAINS&q=  ')).toBeNull();
    expect(criteriaFromQuery('?mode=FIELD&field=order.id')).toBeNull();
    expect(criteriaFromQuery('?mode=FIELD&value=ORD-42')).toBeNull();
    expect(criteriaFromQuery('?mode=KEY&value=ORD-42')).not.toBeNull();
  });

  it('reads a field search with its operator and options', () => {
    const criteria = criteriaFromQuery('?mode=FIELD&field=order.id&op=REGEX&value=ORD-.*&case=1')!;
    expect(criteria.mode).toBe('FIELD');
    expect(criteria.field).toBe('order.id');
    expect(criteria.operator).toBe('REGEX');
    expect(criteria.value).toBe('ORD-.*');
    expect(criteria.caseSensitive).toBe(true);
  });

  it('falls back to the default operator rather than sending an unknown one', () => {
    expect(criteriaFromQuery('?mode=FIELD&field=id&value=1&op=BETWEEN')!.operator).toBe('EQ');
  });

  /** Le sélecteur de portée n'a que quatre valeurs : une fenêtre libre est arrondie au-dessus. */
  it('snaps the time window onto an offered scope', () => {
    expect(criteriaFromQuery('?mode=CONTAINS&q=x&since=5')!.sinceMinutes).toBe(15);
    expect(criteriaFromQuery('?mode=CONTAINS&q=x&since=90')!.sinceMinutes).toBe(1440);
    expect(criteriaFromQuery('?mode=CONTAINS&q=x&since=99999')!.sinceMinutes).toBe(0);
    expect(criteriaFromQuery('?mode=CONTAINS&q=x')!.sinceMinutes).toBe(0);
  });

  /** Le lien construit par la page Stream Flow doit se relire ici, sinon il ne sert à rien. */
  it('reads back what a stream-flow hop links to', () => {
    const trace = parseTraceParams('?key=ORD-42&path=header:correlation-id&case=1&window=30');
    const criteria = criteriaFromQuery(buildTopicSearchQuery(trace))!;

    expect(criteria.mode).toBe('HEADER');
    expect(criteria.field).toBe('correlation-id');
    expect(criteria.value).toBe('ORD-42');
    expect(criteria.operator).toBe('CONTAINS');
    expect(criteria.caseSensitive).toBe(true);
    expect(criteria.sinceMinutes).toBe(60);

    const exact = criteriaFromQuery(buildTopicSearchQuery({ ...trace, searchPath: '', exactKey: true }))!;
    expect(exact.mode).toBe('KEY');
    expect(exact.keyPartitioning).toBe(true);
    expect(exact.value).toBe('ORD-42');
  });
});
