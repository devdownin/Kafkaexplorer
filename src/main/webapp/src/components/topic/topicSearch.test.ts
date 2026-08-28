// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors

import { describe, it, expect, beforeEach } from 'vitest';
import {
  PINNED_KEY,
  SEARCH_HISTORY_KEY,
  VIEW_KEY,
  OPERATORS,
  SEARCH_MODES,
  analyzeHits,
  announceResult,
  describeMode,
  describeOperator,
  describeStopReason,
  buildRecordLink,
  recordFromQuery,
  recordParam,
  withRecord,
  canFollow,
  describeFollow,
  isPinned,
  isTypingTarget,
  nextSelectedRank,
  readPinned,
  togglePinned,
  valuesAtPath,
  directionApplies,
  raiseHitCapAction,
  switchStart,
  toDateTimeLocal,
  describeAdvanced,
  describeCriterion,
  describeHitInsight,
  filterHits,
  highlightedPath,
  mergeWarnings,
  rankHits,
  sortHits,
  describePartitionScope,
  describeScanShare,
  effectiveScanBudget,
  firstErrorField,
  hitsToRows,
  keyPartitioningApplies,
  operatorsFor,
  previewOf,
  pushSearchHistory,
  readSearchHistory,
  readViewMode,
  searchToJson,
  suggestWidenings,
  switchMode,
  validateCriteria,
  writeViewMode,
  type TopicMessage,
  buildSearchBody,
  buildSearchQuery,
  coverageOf,
  criteriaFromQuery,
  seedFromQuery,
  startTimestamp,
  describeCoverage,
  emptyCriteria,
  highlightFor,
  highlightedHeader,
  nextScanAction,
  revealsHeaders,
  splitForHighlight,
  splitOnMatches,
  splitOnRegex,
  type SearchCoverage,
  type TopicSearchCriteria,
  type TopicSearchResponse,
  readCriteriaDraft,
  saveCriteriaDraft,
  isEmptyCriteria,
  normalizeTopicDetail,
} from './topicSearch';
import type { TopicDetailResponse } from '../../api/types';
import { buildTopicSearchQuery, parseTraceParams } from '../../pages/streamFlow';

const criteria = (over: Partial<TopicSearchCriteria> = {}): TopicSearchCriteria =>
  ({ ...emptyCriteria, ...over });

const response = (over: Partial<TopicSearchResponse> = {}): TopicSearchResponse => ({
  hits: [],
  scanned: 20_000,
  matched: 3,
  elapsedMs: 4_000,
  exhausted: false,
  stopReason: 'MAX_SCAN',
  nextCursor: { '0': 20_000 },
  warnings: [],
  ...over,
});

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

describe('splitOnRegex', () => {
  it('marks what the pattern matched', () => {
    expect(splitOnRegex('ORD-42 and ORD-7', 'ORD-\\d+', true))
      .toEqual(['', 'ORD-42', ' and ', 'ORD-7', '']);
  });

  it('honours case sensitivity', () => {
    expect(splitOnRegex('ord-42', 'ORD-\\d+', true)).toEqual(['ord-42']);
    expect(splitOnRegex('ord-42', 'ORD-\\d+', false)).toEqual(['', 'ord-42', '']);
  });

  /** Un motif invalide arrive tel quel depuis le formulaire : il ne doit pas jeter en plein rendu. */
  it('marks nothing rather than throwing on an invalid pattern', () => {
    expect(splitOnRegex('a(b', '([', false)).toEqual(['a(b']);
  });

  /** `a*` matche la chaîne vide partout : sans avance forcée, la boucle ne s'arrête jamais. */
  it('terminates on a pattern that can match nothing', () => {
    expect(splitOnRegex('aab', 'a*', true).join('')).toBe('aab');
    expect(splitOnRegex('', 'x*', true)).toEqual(['']);
  });

  it('reassembles into the original text', () => {
    const text = '{"id":"ORD-42","total":1250}';
    expect(splitOnRegex(text, '"[a-z]+"', false).join('')).toBe(text);
  });
});

describe('highlightFor', () => {
  it('marks the literal of a text search and the pattern of a regex one', () => {
    expect(highlightFor(criteria({ mode: 'CONTAINS', query: 'ORD-42' })))
      .toEqual({ kind: 'TEXT', needle: 'ORD-42', caseSensitive: false });
    expect(highlightFor(criteria({ mode: 'REGEX', query: 'ORD-\\d+', caseSensitive: true })))
      .toEqual({ kind: 'REGEX', source: 'ORD-\\d+', caseSensitive: true });
  });

  /** Le trou d'origine : une recherche par champ / header / clé ne surlignait rien du tout. */
  it('marks the compared value of a field, header or key search', () => {
    expect(highlightFor(criteria({ mode: 'FIELD', field: 'order.id', value: 'ORD-42' })))
      .toEqual({ kind: 'TEXT', needle: 'ORD-42', caseSensitive: false });
    expect(highlightFor(criteria({ mode: 'KEY', value: 'ORD-42' })).kind).toBe('TEXT');
    expect(highlightFor(criteria({ mode: 'HEADER', field: 'correlation-id', value: 'abc' })).kind)
      .toBe('TEXT');
    expect(highlightFor(criteria({ mode: 'FIELD', field: 'id', operator: 'REGEX', value: '\\d+' })))
      .toEqual({ kind: 'REGEX', source: '\\d+', caseSensitive: false });
  });

  /** NEQ a matché *parce que* la valeur diffère : la marquer désignerait ce qui n'est pas là. */
  it('marks nothing when no literal decided the match', () => {
    expect(highlightFor(criteria({ mode: 'FIELD', field: 'id', operator: 'NEQ', value: 'x' })).kind)
      .toBe('NONE');
    expect(highlightFor(criteria({ mode: 'FIELD', field: 'total', operator: 'GT', value: '10' })).kind)
      .toBe('NONE');
    expect(highlightFor(criteria({ mode: 'FIELD', field: 'id', operator: 'EXISTS' })).kind)
      .toBe('NONE');
    expect(highlightFor(criteria({ mode: 'CONTAINS', query: '' })).kind).toBe('NONE');
  });

  it('drives the same split as a raw text search', () => {
    const highlight = highlightFor(criteria({ mode: 'FIELD', field: 'id', value: 'ORD' }));
    expect(splitForHighlight('x ORD y', highlight)).toEqual(['x ', 'ORD', ' y']);
    expect(splitForHighlight('x ORD y', { kind: 'NONE' })).toEqual(['x ORD y']);
  });
});

describe('headers on a hit', () => {
  it('names the header a header search compared', () => {
    expect(highlightedHeader(criteria({ mode: 'HEADER', field: 'correlation-id', value: 'a' })))
      .toBe('correlation-id');
    expect(highlightedHeader(criteria({ mode: 'CONTAINS', query: 'a' }))).toBeNull();
  });

  it('reveals them whenever the match could have been decided there', () => {
    expect(revealsHeaders(criteria({ mode: 'HEADER', field: 'x', value: 'a' }))).toBe(true);
    expect(revealsHeaders(criteria({ mode: 'CONTAINS', query: 'a', searchHeaders: true }))).toBe(true);
    expect(revealsHeaders(criteria({ mode: 'CONTAINS', query: 'a' }))).toBe(false);
    // La clé ne se compare pas aux headers : les déplier n'apprendrait rien.
    expect(revealsHeaders(criteria({ mode: 'KEY', value: 'a', searchHeaders: true }))).toBe(false);
  });
});

describe('buildSearchBody', () => {
  /** Le défaut : lire les records les plus récents, pas l'historique le plus ancien. */
  it('reads back from the end by default', () => {
    expect(buildSearchBody(criteria({ mode: 'CONTAINS', query: 'x' })).from).toBe('LAST_N');
  });

  /** LAST_N relève son plancher à la fenêtre au lieu de la remplacer : le serveur s'en charge. */
  it('keeps LAST_N with a time window, and sends the window along', () => {
    const body = buildSearchBody(criteria({ mode: 'CONTAINS', query: 'x', sinceMinutes: 60 }));
    expect(body.from).toBe('LAST_N');
    expect(body.sinceMinutes).toBe(60);
  });

  it('starts from the oldest record only when asked to', () => {
    expect(buildSearchBody(criteria({ query: 'x', direction: 'OLDEST' })).from).toBe('EARLIEST');
    expect(buildSearchBody(criteria({ query: 'x', direction: 'OLDEST', sinceMinutes: 15 })).from)
      .toBe('TIMESTAMP');
  });

  it('only sends the target a mode actually uses', () => {
    const text = buildSearchBody(criteria({ mode: 'CONTAINS', query: 'x' }));
    expect(text.field).toBeNull();
    expect(text.operator).toBeNull();
    expect(text.value).toBeNull();

    const field = buildSearchBody(criteria({ mode: 'FIELD', field: 'order.id', value: 'ORD-42' }));
    expect(field.field).toBe('order.id');
    expect(field.value).toBe('ORD-42');

    const key = buildSearchBody(criteria({ mode: 'KEY', value: 'ORD-42' }));
    expect(key.field).toBeNull();
    expect(key.value).toBe('ORD-42');
  });

  it('carries the cursor of a resumed pass and the budget of a deepened one', () => {
    expect(buildSearchBody(criteria({ query: 'x' }), { cursor: { '0': 12 } }).cursor)
      .toEqual({ '0': 12 });
    expect(buildSearchBody(criteria({ query: 'x' }), { maxScan: 40_000 }).maxScan).toBe(40_000);
    expect(buildSearchBody(criteria({ query: 'x' })).maxScan).toBeNull();
  });
});

describe('coverageOf', () => {
  /** Une réponse ne décrit que sa passe : trois « continue » lisent 60 000 records, pas 20 000. */
  it('adds up what every resumed pass read', () => {
    const first = coverageOf(response(), null, false);
    const second = coverageOf(response({ scanned: 18_000, matched: 2, elapsedMs: 3_000 }), first, true);

    expect(second.passes).toBe(2);
    expect(second.scanned).toBe(38_000);
    expect(second.matched).toBe(5);
    expect(second.elapsedMs).toBe(7_000);
  });

  /** Un élargissement relit depuis le même bout : cumuler compterait deux fois les mêmes records. */
  it('replaces the tally when the pass re-reads the same ground', () => {
    const first = coverageOf(response(), null, false);
    const deepened = coverageOf(response({ scanned: 40_000 }), first, false, 40_000);

    expect(deepened.passes).toBe(1);
    expect(deepened.scanned).toBe(40_000);
    expect(deepened.scanBudget).toBe(40_000);
  });

  it('keeps the stop state of the latest pass', () => {
    const first = coverageOf(response(), null, false);
    const done = coverageOf(response({ exhausted: true, stopReason: 'EXHAUSTED' }), first, true);
    expect(done.exhausted).toBe(true);
    expect(done.stopReason).toBe('EXHAUSTED');
  });
});

describe('describeCoverage', () => {
  const covered = (over: Partial<SearchCoverage>): SearchCoverage => ({
    passes: 1, scanned: 20_000, matched: 0, elapsedMs: 1_000,
    exhausted: true, stopReason: 'EXHAUSTED', ...over,
  });

  /**
   * « Whole range scanned » en partant de la fin voulait dire « j'ai lu jusqu'au dernier record »,
   * ce qu'un lecteur comprend comme « tout le topic » alors que l'historique n'a jamais été ouvert.
   */
  it('says how far back it read when the scan started from the newest end', () => {
    expect(describeCoverage(covered({}), criteria())).toBe('Newest 20,000 records scanned');
    expect(describeCoverage(covered({}), criteria({ sinceMinutes: 60 })))
      .toBe('Newest 20,000 records scanned in the time range');
  });

  it('says the topic is done only when the scan started from the oldest record', () => {
    expect(describeCoverage(covered({}), criteria({ direction: 'OLDEST' })))
      .toBe('Whole topic scanned');
    expect(describeCoverage(covered({}), criteria({ direction: 'OLDEST', sinceMinutes: 15 })))
      .toBe('Whole time range scanned');
  });

  it('reports the budget that stopped an unfinished pass', () => {
    expect(describeCoverage(covered({ exhausted: false, stopReason: 'TIMEOUT' }), criteria()))
      .toBe('time budget reached');
    expect(describeCoverage(covered({ exhausted: true, stopReason: 'ERROR' }), criteria()))
      .toBe('search failed');
  });
});

describe('nextScanAction', () => {
  const covered = (over: Partial<SearchCoverage>): SearchCoverage => ({
    passes: 1, scanned: 20_000, matched: 0, elapsedMs: 1_000,
    exhausted: false, stopReason: 'MAX_SCAN', ...over,
  });

  it('resumes forward while the pass has not reached the end of its range', () => {
    expect(nextScanAction(covered({}), criteria())?.kind).toBe('RESUME');
    expect(nextScanAction(covered({}), criteria({ direction: 'OLDEST' }))?.kind).toBe('RESUME');
  });

  /**
   * Un curseur pointe après le dernier record lu : reprendre ne ramène que ce qui est arrivé
   * depuis, jamais ce qui précède. Aller plus loin en arrière demande un budget plus large.
   */
  it('offers to read further back once the newest window is exhausted', () => {
    const action = nextScanAction(covered({ exhausted: true, stopReason: 'EXHAUSTED' }), criteria());
    expect(action?.kind).toBe('DEEPEN');
    expect(action && action.kind === 'DEEPEN' && action.maxScan).toBe(40_000);
  });

  it('has nothing left to offer when the whole topic was read', () => {
    expect(nextScanAction(
      covered({ exhausted: true, stopReason: 'EXHAUSTED' }),
      criteria({ direction: 'OLDEST' }),
    )).toBeNull();
  });

  it('stops offering at the server cap, and on an empty range or a failure', () => {
    expect(nextScanAction(covered({ exhausted: true, scanned: 1_000_000 }), criteria())).toBeNull();
    expect(nextScanAction(covered({ exhausted: true, scanned: 0 }), criteria())).toBeNull();
    expect(nextScanAction(covered({ stopReason: 'ERROR' }), criteria())).toBeNull();
  });

  it('doubles the budget it was given, not only what was read', () => {
    const action = nextScanAction(
      covered({ exhausted: true, scanned: 12_000, scanBudget: 40_000 }), criteria());
    expect(action && action.kind === 'DEEPEN' && action.maxScan).toBe(80_000);
  });
});

describe('seedFromQuery', () => {
  /*
   * Un lien peut décrire un point de départ sans dire quoi chercher : c'est ce que fait la
   * sparkline du tableau de bord, qui ouvre un topic réglé sur l'heure du bucket cliqué.
   * `criteriaFromQuery` a raison de répondre `null` — rien n'est exécutable — mais ne rien poser
   * ferait atterrir sur un formulaire vide, ce qui perd le seul renseignement que le lien portait.
   */
  it('carries a start with nothing to run', () => {
    const search = '?start=TIMESTAMP&at=2026-06-15T14:00';
    expect(criteriaFromQuery(search)).toBeNull();

    const seeded = seedFromQuery(search)!;
    expect(seeded.startMode).toBe('TIMESTAMP');
    expect(startTimestamp(seeded)).toBe(new Date(2026, 5, 15, 14, 0, 0).getTime());
  });

  it('leaves a runnable search to the other function, and says nothing about an empty URL', () => {
    // Deux fonctions, deux réponses : celle qui lance et celle qui amorce ne doivent pas répondre
    // ensemble, sans quoi une recherche portée par l'URL serait posée deux fois.
    expect(seedFromQuery('?mode=KEY&value=ORD-42')).toBeNull();
    expect(seedFromQuery('')).toBeNull();
    expect(seedFromQuery('?readMode=latest-offset')).toBeNull();
  });

  /** Un mode inconnu reste une URL cassée : la lire comme une recherche texte la réinterpréterait. */
  it('refuses a broken mode rather than falling back to text', () => {
    expect(seedFromQuery('?mode=NONSENSE&start=TIMESTAMP&at=2026-06-15T14:00')).toBeNull();
    expect(criteriaFromQuery('?mode=NONSENSE&q=x')).toBeNull();
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

  /** `EXISTS` ne compare rien : exiger une valeur rejetait une recherche que le panneau lance. */
  it('accepts an EXISTS search, which has no value to carry', () => {
    const criterion = criteriaFromQuery('?mode=FIELD&field=order.id&op=EXISTS');
    expect(criterion?.operator).toBe('EXISTS');
  });

  it('reads a field search with its operator and options', () => {
    const criterion = criteriaFromQuery('?mode=FIELD&field=order.id&op=REGEX&value=ORD-.*&case=1')!;
    expect(criterion.mode).toBe('FIELD');
    expect(criterion.field).toBe('order.id');
    expect(criterion.operator).toBe('REGEX');
    expect(criterion.value).toBe('ORD-.*');
    expect(criterion.caseSensitive).toBe(true);
  });

  it('falls back to the default operator rather than sending an unknown one', () => {
    expect(criteriaFromQuery('?mode=FIELD&field=id&value=1&op=BETWEEN')!.operator).toBe('EQ');
  });

  it('reads the scan direction, and defaults to the newest records', () => {
    expect(criteriaFromQuery('?mode=CONTAINS&q=x')!.direction).toBe('NEWEST');
    expect(criteriaFromQuery('?mode=CONTAINS&q=x&dir=OLDEST')!.direction).toBe('OLDEST');
    expect(criteriaFromQuery('?mode=CONTAINS&q=x&dir=sideways')!.direction).toBe('NEWEST');
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
    const criterion = criteriaFromQuery(buildTopicSearchQuery(trace))!;

    expect(criterion.mode).toBe('HEADER');
    expect(criterion.field).toBe('correlation-id');
    expect(criterion.value).toBe('ORD-42');
    expect(criterion.operator).toBe('CONTAINS');
    expect(criterion.caseSensitive).toBe(true);
    expect(criterion.sinceMinutes).toBe(60);

    const exact = criteriaFromQuery(buildTopicSearchQuery({ ...trace, searchPath: '', exactKey: true }))!;
    expect(exact.mode).toBe('KEY');
    expect(exact.keyPartitioning).toBe(true);
    expect(exact.value).toBe('ORD-42');
  });
});

describe('buildSearchQuery', () => {
  /** Une recherche partagée doit rouvrir la même : l'aller-retour est la seule garantie. */
  const roundTrip = (source: TopicSearchCriteria) => criteriaFromQuery(buildSearchQuery(source));

  it('round-trips a text search with its options', () => {
    const source = criteria({
      mode: 'CONTAINS', query: 'ORD-42', caseSensitive: true, searchHeaders: true, sinceMinutes: 60,
    });
    expect(roundTrip(source)).toEqual(source);
  });

  it('round-trips a field, header and key search', () => {
    const field = criteria({ mode: 'FIELD', field: 'order.id', operator: 'CONTAINS', value: 'ORD' });
    expect(roundTrip(field)).toEqual(field);

    const header = criteria({ mode: 'HEADER', field: 'correlation-id', value: 'abc' });
    expect(roundTrip(header)).toEqual(header);

    const key = criteria({ mode: 'KEY', value: 'ORD-42', keyPartitioning: true });
    expect(roundTrip(key)).toEqual(key);
  });

  it('round-trips the scan direction and a search that ignores record keys', () => {
    const oldest = criteria({ mode: 'CONTAINS', query: 'x', direction: 'OLDEST' });
    expect(roundTrip(oldest)).toEqual(oldest);

    const valueOnly = criteria({ mode: 'CONTAINS', query: 'x', searchKey: false });
    expect(roundTrip(valueOnly)).toEqual(valueOnly);
  });

  it('round-trips an EXISTS search, value or no value', () => {
    const exists = criteria({ mode: 'FIELD', field: 'order.id', operator: 'EXISTS' });
    expect(roundTrip(exists)).toEqual(exists);
  });

  it('round-trips a partition selection and a scan budget', () => {
    const narrowed = criteria({ mode: 'CONTAINS', query: 'x', partitions: [0, 3], maxScan: 100_000 });
    expect(roundTrip(narrowed)).toEqual(narrowed);
  });

  it('drops a partition list or a budget it cannot make sense of', () => {
    expect(criteriaFromQuery('?mode=CONTAINS&q=x&parts=2,nope,-1,2')!.partitions).toEqual([2]);
    expect(criteriaFromQuery('?mode=CONTAINS&q=x&scan=99')!.maxScan).toBe(0);
  });
});

describe('validateCriteria', () => {
  it('accepts a complete criterion', () => {
    expect(validateCriteria(criteria({ mode: 'CONTAINS', query: 'ORD' }))).toEqual({});
    expect(validateCriteria(criteria({ mode: 'FIELD', field: 'id', value: '4' }))).toEqual({});
    expect(validateCriteria(criteria({ mode: 'FIELD', field: 'id', operator: 'EXISTS' }))).toEqual({});
  });

  /** Le bouton était simplement grisé : l'écran ne disait pas ce qu'il attendait. */
  it('names every missing part at once, not one at a time', () => {
    const errors = validateCriteria(criteria({ mode: 'FIELD' }));
    expect(errors.field).toBeTruthy();
    expect(errors.value).toBeTruthy();
    expect(firstErrorField(errors)).toBe('field');
  });

  /** Une regex invalide n'a pas à faire un aller-retour serveur pour être signalée. */
  it('compiles the regex on the spot', () => {
    expect(validateCriteria(criteria({ mode: 'REGEX', query: 'ORD-[' })).query)
      .toMatch(/Invalid regular expression/);
    expect(validateCriteria(criteria({ mode: 'REGEX', query: 'ORD-\\d+' }))).toEqual({});
    expect(validateCriteria(criteria({ mode: 'KEY', operator: 'REGEX', value: '(' })).value)
      .toMatch(/Invalid regular expression/);
  });

  /** `>` compare des nombres côté serveur : une valeur texte ne matcherait jamais, en silence. */
  it('refuses a non-numeric value on a numeric comparison', () => {
    expect(validateCriteria(criteria({ mode: 'FIELD', field: 'total', operator: 'GT', value: 'abc' })).value)
      .toMatch(/compares numbers/);
    expect(validateCriteria(criteria({ mode: 'FIELD', field: 'total', operator: 'GT', value: '10' })))
      .toEqual({});
  });
});

describe('operatorsFor and key partitioning', () => {
  /** KEY + EXISTS répond vrai sans rien comparer : ce serait un critère qui ne filtre rien. */
  it('drops EXISTS from a key search only', () => {
    expect(operatorsFor('KEY').some(op => op.value === 'EXISTS')).toBe(false);
    expect(operatorsFor('FIELD').some(op => op.value === 'EXISTS')).toBe(true);
  });

  /** Le serveur ignore le partitionnement par clé dès qu'une partition est choisie, sans le dire. */
  it('applies only to an exact key search with no manual partition choice', () => {
    expect(keyPartitioningApplies(criteria({ mode: 'KEY', value: 'a' }))).toBe(true);
    expect(keyPartitioningApplies(criteria({ mode: 'KEY', value: 'a', operator: 'CONTAINS' }))).toBe(false);
    expect(keyPartitioningApplies(criteria({ mode: 'KEY', value: 'a', partitions: [1] }))).toBe(false);
    expect(keyPartitioningApplies(criteria({ mode: 'FIELD', field: 'id', value: 'a' }))).toBe(false);
  });
});

describe('switchMode', () => {
  /** La saisie restait dans `query`, ignorée par la recherche par champ qui allait partir. */
  it('carries the typed text into the input the new mode uses', () => {
    expect(switchMode(criteria({ mode: 'CONTAINS', query: 'ORD-42' }), 'KEY').value).toBe('ORD-42');
    expect(switchMode(criteria({ mode: 'KEY', value: 'ORD-42' }), 'CONTAINS').query).toBe('ORD-42');
  });

  it('never overwrites what the target field already holds', () => {
    const criterion = criteria({ mode: 'CONTAINS', query: 'typed', value: 'kept' });
    expect(switchMode(criterion, 'FIELD').value).toBe('kept');
  });

  /** Basculer sur KEY avec EXISTS laisserait un opérateur absent de la liste proposée. */
  it('resets an operator the new mode does not offer', () => {
    const criterion = criteria({ mode: 'FIELD', field: 'id', operator: 'EXISTS' });
    expect(switchMode(criterion, 'KEY').operator).toBe('EQ');
    expect(switchMode(criterion, 'HEADER').operator).toBe('EXISTS');
  });
});

describe('effectiveScanBudget', () => {
  it('prefers the widened pass, then the form, then the server default', () => {
    expect(effectiveScanBudget(criteria({ maxScan: 50_000 }), { maxScan: 80_000 })).toBe(80_000);
    expect(effectiveScanBudget(criteria({ maxScan: 50_000 }))).toBe(50_000);
    expect(effectiveScanBudget(criteria())).toBeUndefined();
  });

  it('travels in the request body', () => {
    expect(buildSearchBody(criteria({ query: 'x', maxScan: 50_000 })).maxScan).toBe(50_000);
    expect(buildSearchBody(criteria({ query: 'x', partitions: [1, 4] })).partitions).toEqual([1, 4]);
    expect(buildSearchBody(criteria({ query: 'x' })).partitions).toBeNull();
  });
});

describe('scope of a pass', () => {
  /** « 20 000 scanned » ne dit pas la même chose sur un topic de 30 000 et sur un de deux millions. */
  it('situates what was read against the topic', () => {
    expect(describeScanShare(20_000, 1_000_000)).toBe('≈2% of the topic');
    expect(describeScanShare(2_000, 1_000_000)).toBe('≈0.2% of the topic');
    expect(describeScanShare(500, 1_000_000)).toBe('≈<0.1% of the topic');
    expect(describeScanShare(20_000, 20_000)).toBeNull();
    expect(describeScanShare(20_000, 0)).toBeNull();
  });

  /** Le serveur ne prévient pas d'un scan restreint : sans cette ligne, le zéro serait trompeur. */
  it('states a partition restriction', () => {
    expect(describePartitionScope(criteria({ partitions: [3] }))).toBe('partition 3 only');
    expect(describePartitionScope(criteria({ partitions: [0, 3] }))).toBe('partitions 0, 3 only');
    expect(describePartitionScope(criteria())).toBeNull();
  });
});

describe('describeCriterion', () => {
  it('reads back as the search that was run', () => {
    expect(describeCriterion(criteria({ mode: 'CONTAINS', query: 'ORD-42' }))).toBe('ORD-42');
    expect(describeCriterion(criteria({ mode: 'REGEX', query: 'ORD-\\d+' }))).toBe('regex ORD-\\d+');
    expect(describeCriterion(criteria({ mode: 'FIELD', field: 'order.id', value: 'ORD-42' })))
      .toBe('order.id = ORD-42');
    expect(describeCriterion(criteria({ mode: 'FIELD', field: 'id', operator: 'EXISTS' })))
      .toBe('id exists');
    expect(describeCriterion(criteria({ mode: 'HEADER', field: 'correlation-id', operator: 'CONTAINS', value: 'abc' })))
      .toBe('header correlation-id contains abc');
    expect(describeCriterion(criteria({ mode: 'KEY', value: 'ORD-42' }))).toBe('key = ORD-42');
  });
});

describe('history', () => {
  beforeEach(() => localStorage.clear());

  it('keeps the newest first, per topic', () => {
    pushSearchHistory({ topic: 'orders', criteria: criteria({ query: 'a' }), ranAt: 1, hits: 1 });
    pushSearchHistory({ topic: 'payments', criteria: criteria({ query: 'b' }), ranAt: 2, hits: 2 });
    pushSearchHistory({ topic: 'orders', criteria: criteria({ query: 'c' }), ranAt: 3, hits: 3 });

    expect(readSearchHistory('orders').map(e => e.criteria.query)).toEqual(['c', 'a']);
    expect(readSearchHistory('payments').map(e => e.criteria.query)).toEqual(['b']);
    expect(readSearchHistory('shipments')).toEqual([]);
  });

  /** Dédoublonné sur le critère, pas sur son résultat : relancer la même recherche ne l'empile pas. */
  it('de-duplicates on the criterion', () => {
    pushSearchHistory({ topic: 'orders', criteria: criteria({ query: 'a' }), ranAt: 1, hits: 1 });
    const after = pushSearchHistory({
      topic: 'orders', criteria: criteria({ query: 'a' }), ranAt: 9, hits: 4,
    });
    expect(after).toHaveLength(1);
    expect(after[0].ranAt).toBe(9);
  });

  it('survives a corrupted or partial store', () => {
    localStorage.setItem(SEARCH_HISTORY_KEY, 'not json');
    expect(readSearchHistory('orders')).toEqual([]);

    localStorage.setItem(SEARCH_HISTORY_KEY, JSON.stringify([
      { topic: 'orders', criteria: { mode: 'CONTAINS', query: 'a' }, ranAt: 1, hits: 0 },
    ]));
    // Une entrée d'une version antérieure est complétée, pas rejouée trouée.
    expect(readSearchHistory('orders')[0].criteria.direction).toBe('NEWEST');
    expect(readSearchHistory('orders')[0].criteria.partitions).toEqual([]);
  });
});

describe('view mode', () => {
  beforeEach(() => localStorage.clear());

  /*
   * On arrive ici depuis le tableau de bord, donc sans avoir rien choisi, pour regarder ce qu'il
   * y a dans un topic — question à laquelle un tableau répond d'un coup d'œil.
   */
  it('defaults to the table, and remembers either choice', () => {
    expect(readViewMode()).toBe('table');
    writeViewMode('cards');
    expect(readViewMode()).toBe('cards');
    writeViewMode('table');
    expect(readViewMode()).toBe('table');
  });

  /*
   * Une valeur illisible retombe sur le défaut, pas sur l'autre vue : c'est la même règle que
   * `readActivityChoice`, et elle est ce qui distingue « changer un défaut » de « ignorer un
   * choix » — bâtie sur `=== 'table'`, la lecture aurait rendu le tableau à qui a choisi les
   * cartes.
   */
  it('falls back to the default on an unreadable value', () => {
    localStorage.setItem(VIEW_KEY, 'nonsense');
    expect(readViewMode()).toBe('table');
  });

  it('flattens a payload onto one line', () => {
    expect(previewOf('{\n  "id": 1\n}')).toBe('{ "id": 1 }');
    expect(previewOf('x'.repeat(300))).toHaveLength(241);
    expect(previewOf(null)).toBe('');
  });
});

describe('export', () => {
  const hit: TopicMessage = {
    partition: 2, offset: 88, timestamp: 1_700_000_000_000, key: 'ORD-42',
    headers: { 'correlation-id': 'abc', empty: null }, value: '{"id":1}',
    valueBytes: 8, truncated: false,
  };

  it('flattens a hit onto exportable columns', () => {
    const [row] = hitsToRows([hit]);
    expect(row.partition).toBe(2);
    expect(row.offset).toBe(88);
    expect(row.key).toBe('ORD-42');
    expect(row.headers).toBe('correlation-id=abc; empty=');
    expect(row.timestamp).toBe('2023-11-14T22:13:20.000Z');
  });

  /** Un export collé dans un ticket doit dire ce qu'il a couvert, pas seulement ce qu'il a vu. */
  it('carries the criterion and the coverage alongside the hits', () => {
    const covered = coverageOf(response({ exhausted: true, stopReason: 'EXHAUSTED' }), null, false);
    const parsed = JSON.parse(searchToJson(
      'orders', criteria({ mode: 'KEY', value: 'ORD-42', partitions: [2] }), covered, [hit], ['careful'],
    ));

    expect(parsed.topic).toBe('orders');
    expect(parsed.criterion).toBe('key = ORD-42');
    expect(parsed.coverage.scanned).toBe(20_000);
    expect(parsed.coverage.scope).toBe('partition 2 only');
    expect(parsed.coverage.summary).toContain('Newest');
    expect(parsed.warnings).toEqual(['careful']);
    expect(parsed.hits).toHaveLength(1);
  });
});

describe('scan start', () => {
  /** `fromTimestamp` et `fromOffset` existaient côté serveur sans que rien ne puisse les demander. */
  it('starts from an absolute instant, as a floor when reading back from the end', () => {
    const at = criteria({ mode: 'CONTAINS', query: 'x', startMode: 'TIMESTAMP', fromTime: '2026-08-05T14:02' });
    const body = buildSearchBody(at);

    expect(body.from).toBe('LAST_N');
    expect(body.fromTimestamp).toBe(Date.parse('2026-08-05T14:02'));
    expect(body.sinceMinutes).toBeNull();

    expect(buildSearchBody({ ...at, direction: 'OLDEST' }).from).toBe('TIMESTAMP');
  });

  /** Une lecture par offset va vers l'avant : le sens de scan ne s'y applique pas. */
  it('starts from an offset, whatever the scan direction says', () => {
    const at = criteria({ mode: 'CONTAINS', query: 'x', startMode: 'OFFSET', fromOffset: '128000' });
    const body = buildSearchBody(at);

    expect(body.from).toBe('OFFSET');
    expect(body.fromOffset).toBe(128_000);
    expect(directionApplies(at)).toBe(false);
    expect(buildSearchBody({ ...at, direction: 'OLDEST' }).from).toBe('OFFSET');
  });

  it('ignores the relative window once an absolute start is chosen', () => {
    const body = buildSearchBody(criteria({
      mode: 'CONTAINS', query: 'x', sinceMinutes: 60, startMode: 'OFFSET', fromOffset: '10',
    }));
    expect(body.sinceMinutes).toBeNull();
    expect(body.fromTimestamp).toBeNull();
  });

  it('sends nothing on a half-typed start rather than a wrong one', () => {
    expect(buildSearchBody(criteria({ query: 'x', startMode: 'OFFSET', fromOffset: '12.5' })).fromOffset)
      .toBeNull();
    expect(buildSearchBody(criteria({ query: 'x', startMode: 'TIMESTAMP', fromTime: 'nope' })).fromTimestamp)
      .toBeNull();
  });

  /** Un instant futur ne rend aucun offset : le plancher tomberait en silence. */
  it('refuses an empty, unparseable or future start', () => {
    const now = Date.parse('2026-08-05T12:00:00Z');
    expect(validateCriteria(criteria({ query: 'x', startMode: 'TIMESTAMP' }), now).fromTime)
      .toBeTruthy();
    expect(validateCriteria(criteria({
      query: 'x', startMode: 'TIMESTAMP', fromTime: '2027-01-01T00:00',
    }), now).fromTime).toMatch(/future/);
    expect(validateCriteria(criteria({ query: 'x', startMode: 'OFFSET', fromOffset: '-3' })).fromOffset)
      .toBeTruthy();
    expect(validateCriteria(criteria({ query: 'x', startMode: 'OFFSET', fromOffset: '0' })))
      .toEqual({});
  });

  /** Retrouver à la main l'heure qu'il était il y a une heure n'est pas un travail d'opérateur. */
  it('carries the relative window into the absolute field', () => {
    const now = Date.parse('2026-08-05T12:00:00Z');
    const switched = switchStart(criteria({ sinceMinutes: 60 }), 'TIMESTAMP', now);
    expect(switched.fromTime).toBe(toDateTimeLocal(now - 3_600_000));

    // Ce qui est déjà saisi n'est jamais écrasé.
    const typed = criteria({ sinceMinutes: 60, fromTime: '2026-01-01T08:00' });
    expect(switchStart(typed, 'TIMESTAMP', now).fromTime).toBe('2026-01-01T08:00');
  });

  it('says where the pass started once it reached the end', () => {
    const covered: SearchCoverage = {
      passes: 1, scanned: 500, matched: 1, elapsedMs: 10,
      exhausted: true, stopReason: 'EXHAUSTED',
    };
    expect(describeCoverage(covered, criteria({ startMode: 'OFFSET', fromOffset: '128000' })))
      .toBe('Read to the end from offset 128000');
    expect(describeCoverage(covered, criteria({ startMode: 'TIMESTAMP', fromTime: '2026-08-05T14:02' })))
      .toBe('Read to the end from that date');
  });

  /** Élargir le budget ne bouge pas un point de départ fixe : ce serait relire la même chose. */
  it('offers no deepening once a chosen start has been read to the end', () => {
    const covered: SearchCoverage = {
      passes: 1, scanned: 500, matched: 0, elapsedMs: 10,
      exhausted: true, stopReason: 'EXHAUSTED',
    };
    expect(nextScanAction(covered, criteria({ startMode: 'OFFSET', fromOffset: '10' }))).toBeNull();
    expect(nextScanAction(covered, criteria({ startMode: 'TIMESTAMP', fromTime: '2026-08-05T14:02' }))?.kind)
      .toBe('DEEPEN');
  });
});

describe('raiseHitCapAction', () => {
  const cappedAt = (stopReason: string): SearchCoverage => ({
    passes: 1, scanned: 20_000, matched: 340, elapsedMs: 900, exhausted: false, stopReason,
  });

  /**
   * Reprendre repart *après* la zone lue : les matches sautés au-delà du plafond y restent hors
   * d'atteinte pour toujours. Relever le plafond est le seul geste qui les ramène.
   */
  it('offers a higher cap only when the cap is what stopped the pass', () => {
    const action = raiseHitCapAction(cappedAt('MAX_HITS'), criteria());
    expect(action?.maxHits).toBe(400);
    expect(action?.hint).toMatch(/continuing/);
    expect(raiseHitCapAction(cappedAt('MAX_SCAN'), criteria())).toBeNull();
  });

  it('counts from the cap the pass actually ran with, and stops at the server maximum', () => {
    expect(raiseHitCapAction(cappedAt('MAX_HITS'), criteria({ maxHits: 250 }))?.maxHits).toBe(1_000);
    expect(raiseHitCapAction(cappedAt('MAX_HITS'), criteria({ maxHits: 1_000 }))).toBeNull();
  });

  it('travels in the request body and round-trips through the URL', () => {
    expect(buildSearchBody(criteria({ query: 'x', maxHits: 500 })).maxHits).toBe(500);
    expect(buildSearchBody(criteria({ query: 'x' })).maxHits).toBeNull();

    const capped = criteria({ mode: 'CONTAINS', query: 'x', maxHits: 500 });
    expect(criteriaFromQuery(buildSearchQuery(capped))).toEqual(capped);
  });
});

describe('help texts', () => {
  /** Un mode sans explication, c'est le mode qu'on choisit mal sans jamais savoir pourquoi. */
  it('explains every mode and every operator it offers', () => {
    for (const mode of SEARCH_MODES) {
      expect(describeMode(mode).length).toBeGreaterThan(20);
    }
    for (const operator of OPERATORS) {
      expect(describeOperator(operator.value).length).toBeGreaterThan(10);
    }
  });

  /** Les comparaisons numériques ne matchent jamais une valeur texte : le dire vaut mieux. */
  it('warns that a numeric comparison ignores anything that is not a number', () => {
    for (const operator of ['GT', 'GTE', 'LT', 'LTE']) {
      expect(describeOperator(operator)).toMatch(/not a number/);
    }
    expect(describeOperator('EXISTS')).toMatch(/nothing is compared/);
  });

  /** Le bandeau donne un état ; ce qu'il faut savoir, c'est ce que le bouton d'à côté fera. */
  it('explains every stop reason, cap included', () => {
    for (const reason of ['MAX_HITS', 'MAX_SCAN', 'TIMEOUT', 'EXHAUSTED', 'ERROR']) {
      expect(describeStopReason(reason).length).toBeGreaterThan(20);
    }
    expect(describeStopReason('MAX_HITS')).toMatch(/higher cap|cap/);
    expect(describeStopReason('WHATEVER')).toBe('The scan stopped.');
  });
});

describe('record permalink', () => {
  const message: TopicMessage = {
    partition: 2, offset: 88, timestamp: 0, key: null, headers: {},
    value: '{}', valueBytes: 2, truncated: false,
  };

  it('reads and writes a record link', () => {
    expect(recordFromQuery('?record=2:88')).toEqual({ partition: 2, offset: 88 });
    expect(recordParam({ partition: 2, offset: 88 })).toBe('2:88');
    expect(buildRecordLink('https://kse.example', '/topic/orders', message))
      .toBe('https://kse.example/topic/orders?record=2%3A88');
  });

  /** Une URL ordinaire, ou des coordonnées qui n'en sont pas, n'ouvrent rien. */
  it('ignores anything that is not a pair of coordinates', () => {
    expect(recordFromQuery('')).toBeNull();
    expect(recordFromQuery('?mode=CONTAINS&q=x')).toBeNull();
    expect(recordFromQuery('?record=')).toBeNull();
    expect(recordFromQuery('?record=2')).toBeNull();
    expect(recordFromQuery('?record=2:notanoffset')).toBeNull();
    expect(recordFromQuery('?record=-1:5')).toBeNull();
  });

  /** L'URL décrit la recherche *et* le message ouvert : l'une n'efface pas l'autre. */
  it('sits alongside a search criterion without disturbing it', () => {
    const search = buildSearchQuery(criteria({ mode: 'CONTAINS', query: 'ORD-42' }));
    const both = withRecord(search, { partition: 2, offset: 88 });

    expect(recordFromQuery(both)).toEqual({ partition: 2, offset: 88 });
    expect(criteriaFromQuery(both)?.query).toBe('ORD-42');

    const without = withRecord(both, null);
    expect(recordFromQuery(without)).toBeNull();
    expect(criteriaFromQuery(without)?.query).toBe('ORD-42');
  });

  /** Un lien vers un message seul ne déclenche aucune recherche. */
  it('does not read as a search on its own', () => {
    expect(criteriaFromQuery('?record=2:88')).toBeNull();
    expect(withRecord('', null)).toBe('');
  });
});

describe('follow', () => {
  /** Reprendre au curseur *est* un tail : sans curseur, il n'y a rien à reprendre. */
  it('needs a cursor to have anything to resume', () => {
    expect(canFollow({ '0': 42 })).toBe(true);
    expect(canFollow({})).toBe(false);
    expect(canFollow(null)).toBe(false);
    expect(canFollow(undefined)).toBe(false);
  });

  /** Jamais « en direct » : ce mode ne ramène que ce qui arrive après le point déjà lu. */
  it('says what it actually brings back', () => {
    expect(describeFollow(false, null)).toMatch(/every few seconds/);
    const following = describeFollow(true, {
      passes: 3, scanned: 10, matched: 1, elapsedMs: 5, exhausted: true, stopReason: 'EXHAUSTED',
    });
    expect(following).toMatch(/new records only/);
    expect(following).toMatch(/3 passes/);
  });
});

describe('valuesAtPath', () => {
  const sample = (value: string): TopicMessage => ({
    partition: 0, offset: 0, timestamp: 0, key: null, headers: {},
    value, valueBytes: value.length, truncated: false,
  });

  /** On ne sait pas de mémoire si le statut s'écrit SHIPPED, shipped ou Shipped. */
  it('collects the distinct values a path carries in the samples', () => {
    const samples = [
      sample('{"order":{"status":"NEW"}}'),
      sample('{"order":{"status":"SHIPPED"}}'),
      sample('{"order":{"status":"NEW"}}'),
      sample('{"order":{"total":12}}'),
    ];
    expect(valuesAtPath(samples, 'order.status')).toEqual(['NEW', 'SHIPPED']);
    expect(valuesAtPath(samples, '$.order.status')).toEqual(['NEW', 'SHIPPED']);
    expect(valuesAtPath(samples, 'order.total')).toEqual(['12']);
  });

  it('suggests nothing rather than the wrong thing', () => {
    const samples = [sample('{"items":[{"sku":"A"}]}'), sample('not json'), sample('{"a":{"b":1}}')];
    // Un chemin qui traverse un tableau ne désigne pas une valeur.
    expect(valuesAtPath(samples, 'items[].sku')).toEqual([]);
    expect(valuesAtPath(samples, '')).toEqual([]);
    // Un nœud objet n'est pas une valeur comparable.
    expect(valuesAtPath(samples, 'a')).toEqual([]);
    expect(valuesAtPath(samples, 'a.b')).toEqual(['1']);
  });

  it('stops at the cap', () => {
    const samples = Array.from({ length: 40 }, (_, i) => sample(`{"id":${i}}`));
    expect(valuesAtPath(samples, 'id', 5)).toHaveLength(5);
  });
});

describe('pinned searches', () => {
  beforeEach(() => localStorage.clear());

  /** L'historique se dévide ; un critère qu'on rejoue à chaque incident ne doit pas en dépendre. */
  it('pins, reports and unpins the same criterion', () => {
    const criterion = criteria({ mode: 'FIELD', field: 'order.id', value: 'ORD-42' });

    expect(isPinned(readPinned('orders'), criterion)).toBe(false);
    const after = togglePinned('orders', criterion);
    expect(after).toHaveLength(1);
    expect(isPinned(after, criterion)).toBe(true);
    expect(isPinned(readPinned('orders'), criterion)).toBe(true);

    expect(togglePinned('orders', criterion)).toHaveLength(0);
  });

  it('keeps each topic to itself', () => {
    togglePinned('orders', criteria({ query: 'a' }));
    togglePinned('payments', criteria({ query: 'b' }));

    expect(readPinned('orders')).toHaveLength(1);
    expect(readPinned('payments')[0].criteria.query).toBe('b');
    expect(readPinned('shipments')).toEqual([]);
  });

  it('survives a corrupted store', () => {
    localStorage.setItem(PINNED_KEY, '{oops');
    expect(readPinned('orders')).toEqual([]);
  });
});

describe('keyboard', () => {
  const hits = rankHits([0, 1, 2].map(offset => ({
    partition: 0, offset, timestamp: 0, key: null, headers: {},
    value: '{}', valueBytes: 2, truncated: false,
  })));

  /** Le déplacement suit la liste *affichée* : `j` ne doit pas sauter à un record filtré. */
  it('moves through the displayed hits and stops at the ends', () => {
    expect(nextSelectedRank(hits, null, 1)).toBe(1);
    expect(nextSelectedRank(hits, null, -1)).toBe(3);
    expect(nextSelectedRank(hits, 1, 1)).toBe(2);
    expect(nextSelectedRank(hits, 3, 1)).toBe(3);
    expect(nextSelectedRank(hits, 1, -1)).toBe(1);
    expect(nextSelectedRank([], 1, 1)).toBeNull();
    // Une sélection sortie du filtre repart du début plutôt que de rester introuvable.
    expect(nextSelectedRank(hits, 99, 1)).toBe(1);
  });

  /** Un raccourci à une touche pendant la saisie transformerait « j » en commande. */
  it('leaves a keystroke to the field that has the focus', () => {
    const input = document.createElement('input');
    const div = document.createElement('div');
    const editable = document.createElement('div');
    editable.contentEditable = 'true';

    expect(isTypingTarget(input)).toBe(true);
    expect(isTypingTarget(document.createElement('textarea'))).toBe(true);
    expect(isTypingTarget(document.createElement('select'))).toBe(true);
    expect(isTypingTarget(div)).toBe(false);
    expect(isTypingTarget(null)).toBe(false);
  });
});

describe('announceResult', () => {
  /** La disparition de « Searching… » ne dit rien à qui ne voit pas l'écran. */
  it('states the outcome once the pass is over', () => {
    const covered = coverageOf(response({ matched: 12, exhausted: true, stopReason: 'EXHAUSTED' }), null, false);

    expect(announceResult(true, covered, criteria(), 12)).toBe('Searching…');
    const done = announceResult(false, covered, criteria(), 12);
    expect(done).toMatch(/^12 matches, 20,000 records scanned/);
    expect(done).toMatch(/Newest/);
    expect(announceResult(false, null, null, 0)).toBe('');
  });

  it('agrees in number on a single match', () => {
    const covered = coverageOf(response({ matched: 1 }), null, false);
    expect(announceResult(false, covered, criteria(), 1)).toMatch(/^1 match,/);
  });
});

describe('mergeWarnings', () => {
  /** Ceux de la seule dernière réponse s'effaçaient à la reprise, alors qu'ils décrivaient
   *  toujours une partie des résultats affichés. */
  it('keeps what an earlier pass reported when the scan resumes', () => {
    expect(mergeWarnings(['no JSON in the 50 records scanned'], ['partition 3 only'], true))
      .toEqual(['no JSON in the 50 records scanned', 'partition 3 only']);
  });

  it('de-duplicates a warning both passes reported', () => {
    expect(mergeWarnings(['same'], ['same', 'new'], true)).toEqual(['same', 'new']);
  });

  /** Une relecture depuis le même bout remplace : ses avertissements décrivent tout l'affiché. */
  it('replaces when the pass re-reads the same ground', () => {
    expect(mergeWarnings(['old'], ['fresh'], false)).toEqual(['fresh']);
    expect(mergeWarnings(['old'], [], false)).toEqual([]);
  });
});

describe('reading the hits', () => {
  const hitAt = (over: Partial<TopicMessage> = {}): TopicMessage => ({
    partition: 0, offset: 1, timestamp: 1_700_000_000_000, key: null,
    headers: {}, value: '{}', valueBytes: 2, truncated: false, ...over,
  });

  it('ranks hits in scan order, and the rank survives filter and sort', () => {
    const ranked = rankHits([hitAt({ offset: 9 }), hitAt({ offset: 4 }), hitAt({ offset: 7 })]);
    expect(ranked.map(h => h.rank)).toEqual([1, 2, 3]);
    expect(sortHits(ranked, 'offset', false).map(h => h.rank)).toEqual([2, 3, 1]);
    expect(sortHits(ranked, 'offset', true).map(h => h.rank)).toEqual([1, 3, 2]);
    expect(sortHits(ranked, 'rank', false).map(h => h.rank)).toEqual([1, 2, 3]);
  });

  it('sorts by partition, then offset within a partition', () => {
    const ranked = rankHits([
      hitAt({ partition: 1, offset: 5 }), hitAt({ partition: 0, offset: 9 }),
      hitAt({ partition: 0, offset: 2 }),
    ]);
    expect(sortHits(ranked, 'partition', false).map(h => [h.partition, h.offset]))
      .toEqual([[0, 2], [0, 9], [1, 5]]);
  });

  it('filters on anything visible on the row', () => {
    const ranked = rankHits([
      hitAt({ key: 'ORD-42', value: '{"status":"NEW"}' }),
      hitAt({ partition: 3, offset: 88, key: 'ORD-7', value: '{"status":"SHIPPED"}',
        headers: { 'correlation-id': 'abc' } }),
    ]);
    expect(filterHits(ranked, 'ORD-42').map(h => h.rank)).toEqual([1]);
    expect(filterHits(ranked, 'shipped').map(h => h.rank)).toEqual([2]);
    expect(filterHits(ranked, 'correlation').map(h => h.rank)).toEqual([2]);
    expect(filterHits(ranked, 'p3').map(h => h.rank)).toEqual([2]);
    expect(filterHits(ranked, '  ')).toHaveLength(2);
  });

  /** Quarante lignes ne montrent pas d'elles-mêmes que trente-huit sont sur la même partition. */
  it('reads the hits instead of leaving them to be read', () => {
    const hits = [
      hitAt({ partition: 3, timestamp: 1_000, key: 'ORD-42' }),
      hitAt({ partition: 3, timestamp: 61_000, key: 'ORD-42' }),
      hitAt({ partition: 1, timestamp: 31_000, key: 'ORD-7' }),
    ];
    const insight = analyzeHits(hits);

    expect(insight.total).toBe(3);
    expect(insight.partitions[0]).toEqual({ partition: 3, count: 2 });
    expect(insight.spanMs).toBe(60_000);
    expect(insight.distinctKeys).toBe(2);
    expect(insight.repeatedKeys).toEqual([{ key: 'ORD-42', count: 2 }]);

    const notes = describeHitInsight(insight, 4);
    expect(notes[0]).toBe('2 of 3 on partition 3 (2 partitions)');
    expect(notes[1]).toBe('spanning 1 min 0 s');
    expect(notes[2]).toBe('key ORD-42 appears 2 times');
  });

  /** « Toutes sur p0 » sur un topic mono-partition n'est pas une observation. */
  it('says nothing about partitions the topic does not have', () => {
    const insight = analyzeHits([hitAt({ timestamp: 5 }), hitAt({ timestamp: 5 })]);
    expect(describeHitInsight(insight, 1)).not.toContain('all on partition 0');
    expect(describeHitInsight(insight, 1)).toContain('all within the same millisecond');
    expect(describeHitInsight(analyzeHits([]), 4)).toEqual([]);
  });
});

describe('highlightedPath', () => {
  it('designates the compared node when the path is a plain dot path', () => {
    expect(highlightedPath(criteria({ mode: 'FIELD', field: 'order.id', value: 'x' })))
      .toBe('order.id');
    expect(highlightedPath(criteria({ mode: 'FIELD', field: '$.order.id', value: 'x' })))
      .toBe('order.id');
  });

  /** Marquer à côté serait pire que ne rien marquer. */
  it('gives up on a path that does not designate a single node', () => {
    expect(highlightedPath(criteria({ mode: 'FIELD', field: 'items[].sku', value: 'x' }))).toBeNull();
    expect(highlightedPath(criteria({ mode: 'FIELD', field: '$..id', value: 'x' }))).toBeNull();
    expect(highlightedPath(criteria({ mode: 'HEADER', field: 'correlation-id', value: 'x' }))).toBeNull();
    expect(highlightedPath(criteria({ mode: 'CONTAINS', query: 'x' }))).toBeNull();
  });
});

describe('describeAdvanced', () => {
  /** Une option active qu'on ne voit pas est exactement ce qu'il ne faut pas replier en silence. */
  it('announces only what departs from the default', () => {
    expect(describeAdvanced(criteria())).toEqual([]);
    expect(describeAdvanced(criteria({ direction: 'OLDEST', maxScan: 100_000, partitions: [2] })))
      .toEqual(['Oldest first', 'Scan 100 000', 'partition 2 only']);
  });
});

describe('suggestWidenings', () => {
  it('turns the advice into reruns that each change one thing', () => {
    const suggestions = suggestWidenings(criteria({
      mode: 'FIELD', field: 'order.id', value: 'ORD-42', caseSensitive: true, sinceMinutes: 60,
    }));
    const ids = suggestions.map(s => s.id);

    expect(ids).toContain('contains');
    expect(ids).toContain('raw-text');
    expect(suggestions.find(s => s.id === 'contains')!.criteria.operator).toBe('CONTAINS');
    expect(suggestions.find(s => s.id === 'raw-text')!.criteria.query).toBe('ORD-42');
    expect(suggestions.length).toBeLessThanOrEqual(4);
  });

  it('offers headers and a bigger budget on a bare text search', () => {
    const ids = suggestWidenings(criteria({ mode: 'CONTAINS', query: 'ORD-42' })).map(s => s.id);
    expect(ids).toContain('headers');
    expect(ids).toContain('bigger-budget');
  });

  it('never suggests what is already the case', () => {
    const ids = suggestWidenings(criteria({
      mode: 'CONTAINS', query: 'x', searchHeaders: true, maxScan: 250_000,
    })).map(s => s.id);
    expect(ids).not.toContain('headers');
    expect(ids).not.toContain('bigger-budget');
    expect(ids).not.toContain('drop-window');
  });

  it('offers to widen a scan that was narrowed to some partitions', () => {
    const ids = suggestWidenings(criteria({ mode: 'CONTAINS', query: 'x', partitions: [1] }))
      .map(s => s.id);
    expect(ids).toContain('all-partitions');
  });
});

describe('brouillon du critère', () => {
  beforeEach(() => localStorage.clear());

  it('restitue le critère non lancé du même topic', () => {
    const criteria = { ...emptyCriteria, mode: 'FIELD' as const, field: 'status', value: 'SHIPPED' };
    saveCriteriaDraft('demo.orders', criteria);
    expect(readCriteriaDraft('demo.orders')).toEqual(criteria);
  });

  it('ne restitue pas le brouillon d\'un autre topic', () => {
    saveCriteriaDraft('demo.orders', { ...emptyCriteria, query: 'ORD-42' });
    // Un critère parlant d'un autre topic est pire qu'un formulaire vide.
    expect(readCriteriaDraft('demo.payments')).toBeNull();
  });

  it('efface le brouillon quand le formulaire revient à vide', () => {
    saveCriteriaDraft('demo.orders', { ...emptyCriteria, query: 'ORD-42' });
    saveCriteriaDraft('demo.orders', emptyCriteria);
    expect(readCriteriaDraft('demo.orders')).toBeNull();
  });

  it('complète un brouillon écrit par une version antérieure', () => {
    const partial = { topic: 'demo.orders', criteria: { mode: 'REGEX', query: 'ORD-\\d+' } };
    localStorage.setItem('kse:draft:topic-search',
      JSON.stringify({ v: 1, at: Date.now(), value: partial }));
    const restored = readCriteriaDraft('demo.orders');
    expect(restored?.query).toBe('ORD-\\d+');
    // Les champs absents prennent leur valeur par défaut plutôt que `undefined`.
    expect(restored?.direction).toBe(emptyCriteria.direction);
    expect(restored?.partitions).toEqual([]);
  });

  it('reconnaît un critère vide, tableaux compris', () => {
    expect(isEmptyCriteria(emptyCriteria)).toBe(true);
    expect(isEmptyCriteria({ ...emptyCriteria, partitions: [0] })).toBe(false);
    expect(isEmptyCriteria({ ...emptyCriteria, query: 'x' })).toBe(false);
  });
});

describe('normalizeTopicDetail', () => {
  const base = {
    topic: { name: 't', partitions: 1, minOffsets: {}, maxOffsets: {}, detectedFormat: null, estimatedSize: 0 },
    format: null, schema: { id: 'STRING' }, ddl: null,
    samples: [{ partition: 0, offset: 1, timestamp: 0, key: null, value: '{}', headers: {}, valueBytes: 2, truncated: false }],
  } as unknown as TopicDetailResponse;

  it('passes a well-formed response through unchanged', () => {
    const out = normalizeTopicDetail(base);
    expect(out.samples).toHaveLength(1);
    expect(out.schema).toEqual({ id: 'STRING' });
  });

  it('fills in what a response left out rather than letting the page dereference it', () => {
    // La page lit `data.samples.length` et `data.schema` à chaque rendu : sans ce garde, une
    // réponse incomplète emporte l'écran entier au lieu de la section concernée.
    const out = normalizeTopicDetail({ ...base, samples: undefined, schema: undefined } as unknown as TopicDetailResponse);
    expect(out.samples).toEqual([]);
    expect(out.schema).toEqual({});
  });

  it('does not invent samples out of a value that is not a list', () => {
    const out = normalizeTopicDetail({ ...base, samples: 'nope' } as unknown as TopicDetailResponse);
    expect(out.samples).toEqual([]);
  });
});
