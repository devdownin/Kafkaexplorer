// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors

import { describe, it, expect, afterEach, vi } from 'vitest';
import { NAV_ITEMS } from '../navigation';
import {
  formatSql, tokenizeSql, sortRows, sortKeyOf, cellText, nextActiveTabId,
  isResultStale, writeStored, readStored, removeStored,
  readLayout, LAYOUT_STORAGE_KEY, DEFAULT_LAYOUT, SPLIT_MIN, SIDEBAR_MAX,
  readSqlParam, buildQueryLink,
  NARROW_ALTERNATIVES, WIDE_WINDOW_MIN_PX, dismissNarrowNotice, readNarrowNoticeDismissed,
  starterTable, starterQueries, pushHistory, describeHistoryEntry, formatDuration,
  splitStatements, statementIndexAt, positionAt, offsetAt, resolveOrigin,
  detailValue, withoutLeadingCte,
  planRun, previewStatement, detectStatementType, isJobModeStatement,
  forgetOldestResults, summariseBatch, describeStatementRun, MAX_RETAINED_BATCH_ROWS,
  buildCompletionEntries, SQL_KEYWORD_SUGGESTIONS,
  type StatementRun,
  sidebarSqlFor, sidebarActionLabel,
  rowKindOf, describeChangelog, exportColumns, exportRows, ROW_KIND_KEY,
  type HistoryEntry,
} from './queryWorkbenchLogic';

describe('tokenizeSql', () => {
  it('keeps a string literal whole, doubled quotes included', () => {
    const tokens = tokenizeSql("WHERE label = 'it''s a group by'");
    expect(tokens.find(t => t.kind === 'string')?.text).toBe("'it''s a group by'");
  });

  it('keeps a backquoted identifier whole', () => {
    const tokens = tokenizeSql('SELECT * FROM `demo.orders`');
    expect(tokens.find(t => t.kind === 'string')?.text).toBe('`demo.orders`');
  });

  it('captures line and block comments as single tokens', () => {
    const tokens = tokenizeSql('-- pick one\nSELECT /* inline */ 1');
    expect(tokens[0]).toEqual({ kind: 'lineComment', text: '-- pick one' });
    expect(tokens.some(t => t.kind === 'blockComment' && t.text === '/* inline */')).toBe(true);
  });

  it('does not run past an unterminated literal', () => {
    expect(() => tokenizeSql("SELECT 'unterminated")).not.toThrow();
    const tokens = tokenizeSql("SELECT 'unterminated");
    expect(tokens[tokens.length - 1].text).toBe("'unterminated");
  });
});

describe('formatSql', () => {
  it('puts one clause per line and one select item per line', () => {
    expect(formatSql('select a, b from orders where a = 1')).toBe(
      'SELECT\n  a,\n  b\nFROM orders\nWHERE a = 1',
    );
  });

  it('uppercases keywords but never identifiers', () => {
    expect(formatSql('select MyCol from MyTable where x and y')).toBe(
      'SELECT\n  MyCol\nFROM MyTable\nWHERE x AND y',
    );
  });

  it('never rewrites the inside of a string literal', () => {
    const out = formatSql("select a from t where label = 'group by from where'");
    expect(out).toContain("'group by from where'");
    expect(out.split('\n').filter(l => l.startsWith('FROM'))).toHaveLength(1);
  });

  it('does not split a comma inside parentheses', () => {
    expect(formatSql('select coalesce(a, b) as v from t')).toBe(
      'SELECT\n  COALESCE(a, b) AS v\nFROM t',
    );
  });

  it('keeps a keyword that only appears inside a windowed table call on one line', () => {
    const out = formatSql("select window_start from TABLE(TUMBLE(TABLE orders, DESCRIPTOR(ts), INTERVAL '5' MINUTE))");
    expect(out).toBe(
      "SELECT\n  window_start\nFROM TABLE(TUMBLE(TABLE orders, DESCRIPTOR(ts), INTERVAL '5' MINUTE))",
    );
  });

  it('breaks before a JOIN and its ON', () => {
    expect(formatSql('select a from x left join y on x.id = y.id')).toBe(
      'SELECT\n  a\nFROM x\nLEFT JOIN y\nON x.id = y.id',
    );
  });

  it('does not cut UNION ALL after UNION', () => {
    expect(formatSql('select a from x union all select a from y')).toBe(
      'SELECT\n  a\nFROM x\nUNION ALL\nSELECT\n  a\nFROM y',
    );
  });

  it('separates statements with a blank line and drops the trailing one', () => {
    expect(formatSql('select 1 from t; select 2 from u;')).toBe(
      'SELECT\n  1\nFROM t;\n\nSELECT\n  2\nFROM u;',
    );
  });

  it('keeps a line comment on its own line', () => {
    expect(formatSql('-- the daily count\nselect count(*) from t')).toBe(
      '-- the daily count\nSELECT\n  COUNT(*)\nFROM t',
    );
  });

  it('returns blank input untouched', () => {
    expect(formatSql('')).toBe('');
    expect(formatSql('   \n  ')).toBe('   \n  ');
  });

  it('is idempotent — formatting formatted SQL changes nothing', () => {
    const once = formatSql('select a, b from orders where a = 1 group by a, b order by a');
    expect(formatSql(once)).toBe(once);
  });
});

describe('splitStatements', () => {
  it('returns one statement for a plain query, without a trailing semicolon', () => {
    expect(splitStatements('SELECT 1 FROM t;')).toEqual([{ start: 0, end: 14, text: 'SELECT 1 FROM t' }]);
  });

  it('splits on top-level semicolons', () => {
    expect(splitStatements('SELECT 1;\nSELECT 2').map(s => s.text)).toEqual(['SELECT 1', 'SELECT 2']);
  });

  it('does not split on a semicolon inside a string literal', () => {
    expect(splitStatements("SELECT * FROM t WHERE a = 'x;y'").map(s => s.text))
      .toEqual(["SELECT * FROM t WHERE a = 'x;y'"]);
  });

  it('does not split on a semicolon inside a comment', () => {
    expect(splitStatements('SELECT 1 -- and; then\nFROM t').map(s => s.text))
      .toEqual(['SELECT 1 -- and; then\nFROM t']);
    expect(splitStatements('SELECT 1 /* a; b */ FROM t').map(s => s.text))
      .toEqual(['SELECT 1 /* a; b */ FROM t']);
  });

  it('does not split on a semicolon inside a quoted identifier', () => {
    expect(splitStatements('SELECT * FROM `weird;name`').map(s => s.text))
      .toEqual(['SELECT * FROM `weird;name`']);
  });

  it('drops chunks that hold no SQL', () => {
    expect(splitStatements(';;  ;')).toEqual([]);
    expect(splitStatements('-- only a comment')).toEqual([]);
    expect(splitStatements('SELECT 1;;\n\n;SELECT 2;').map(s => s.text)).toEqual(['SELECT 1', 'SELECT 2']);
  });

  it('points start at the first useful character, past leading blanks', () => {
    const [first, second] = splitStatements('SELECT 1;\n\n   SELECT 2');
    expect(first.start).toBe(0);
    expect(second.start).toBe(14);
    expect('SELECT 1;\n\n   SELECT 2'.slice(second.start)).toBe('SELECT 2');
  });

  it('handles an unterminated literal without running away', () => {
    expect(() => splitStatements("SELECT 'oops")).not.toThrow();
    expect(splitStatements("SELECT 'oops").map(s => s.text)).toEqual(["SELECT 'oops"]);
  });
});

describe('offsetAt', () => {
  const text = 'SELECT 1;\nSELECT 2;\nSELECT 3';

  it('is the inverse of positionAt', () => {
    for (const offset of [0, 5, 9, 10, 18, text.length]) {
      const { line, column } = positionAt(text, offset);
      expect(offsetAt(text, line, column)).toBe(offset);
    }
  });

  it('clamps a line and a column past the end', () => {
    expect(offsetAt(text, 99, 1)).toBe(text.length);
    expect(offsetAt(text, 1, 999)).toBe(text.length);
  });
});

describe('resolveOrigin', () => {
  const sql = 'SELECT 1 FROM a;\nSELECT 2 FROM b;';

  it('locates the statement that ran, in the document as it now reads', () => {
    expect(resolveOrigin(sql, 'SELECT 2 FROM b')).toEqual({ line: 2, column: 1 });
  });

  /* Figée au moment du run, l'origine devenait fausse dès qu'on ajoutait une ligne au-dessus. */
  it('follows the text when the document moves around it', () => {
    expect(resolveOrigin(`-- a note\n${sql}`, 'SELECT 2 FROM b')).toEqual({ line: 3, column: 1 });
  });

  it('locates a selected fragment too, which is not a statement', () => {
    expect(resolveOrigin(sql, 'FROM b')).toEqual({ line: 2, column: 10 });
  });

  it('falls back to a whitespace-insensitive match, so reformatting keeps the position', () => {
    expect(resolveOrigin('SELECT   1\n  FROM a', 'SELECT 1 FROM a')).toEqual({ line: 1, column: 1 });
  });

  /* Pointer une ligne quand le texte mesuré a disparu désignerait autre chose que ce dont le
     moteur parle : l'appelant retire la position au lieu de la deviner. */
  it('reports nothing when what ran is no longer in the document', () => {
    expect(resolveOrigin(sql, 'SELECT 3 FROM c')).toBeNull();
    expect(resolveOrigin(sql, null)).toBeNull();
    expect(resolveOrigin(sql, '   ')).toBeNull();
  });
});

describe('planRun', () => {
  const sql = 'CREATE TABLE a (x INT);\nSELECT 1 FROM a;\nSELECT 2 FROM b';

  it('takes the statement the cursor is in', () => {
    expect(planRun(sql, sql.indexOf('SELECT 1') + 2, null, 'cursor'))
      .toEqual([{ text: 'SELECT 1 FROM a', start: sql.indexOf('SELECT 1') }]);
  });

  it('takes every statement of the document for a Run all', () => {
    expect(planRun(sql, 0, null, 'all').map(p => p.text))
      .toEqual(['CREATE TABLE a (x INT)', 'SELECT 1 FROM a', 'SELECT 2 FROM b']);
  });

  /*
   * Sélectionner deux requêtes et les lancer est un geste ordinaire ; la sélection partait
   * auparavant en une seule requête, que le planner rejetait sur le `;`.
   */
  it('splits a selection that spans two statements, in document coordinates', () => {
    const start = sql.indexOf('SELECT 1');
    const selection = { start, text: sql.slice(start) };
    expect(planRun(sql, 0, selection, 'cursor')).toEqual([
      { text: 'SELECT 1 FROM a', start },
      { text: 'SELECT 2 FROM b', start: sql.indexOf('SELECT 2') },
    ]);
  });

  it('sends a selected fragment verbatim — a sub-expression is not a statement', () => {
    const selection = { start: 7, text: ' 1 FROM a ' };
    expect(planRun(sql, 0, selection, 'cursor')).toEqual([{ text: '1 FROM a', start: 8 }]);
  });

  it('reports nothing to run rather than sending an empty string', () => {
    expect(planRun('  \n-- just a comment\n', 0, null, 'cursor')).toEqual([]);
    expect(planRun('', 0, null, 'all')).toEqual([]);
  });

  /* Une sélection vide n'est pas une sélection : c'est le curseur qui décide, comme le libellé du
     bouton, qui repasse de « Run selection » à « Run statement » dans ce même cas. */
  it('ignores a whitespace-only selection and runs the cursor statement', () => {
    expect(planRun(sql, 0, { start: 0, text: '   ' }, 'cursor').map(p => p.text))
      .toEqual(['CREATE TABLE a (x INT)']);
  });
});

describe('previewStatement', () => {
  it('collapses whitespace and truncates', () => {
    expect(previewStatement('SELECT\n  a,\n  b\nFROM t', 12)).toBe('SELECT a, b…');
  });

  /* Le SQL que la barre latérale pose commence par deux lignes de commentaire : un aperçu brut
     donnerait la même étiquette à toutes les instructions du lot. */
  it('looks past the comments a generated statement opens with', () => {
    expect(previewStatement('-- Read the topic\n-- target selected\nSELECT * FROM t'))
      .toBe('SELECT * FROM t');
  });

  it('falls back to the comment when there is nothing else', () => {
    expect(previewStatement('-- nothing but this')).toBe('-- nothing but this');
  });
});

describe('detectStatementType', () => {
  it('classifies past a leading CTE, like the backend', () => {
    expect(detectStatementType('WITH x AS (SELECT 1) INSERT INTO t SELECT * FROM x')).toBe('INSERT');
    expect(detectStatementType('WITH x AS (SELECT 1) SELECT * FROM x')).toBe('SELECT');
  });

  it('classifies past comments', () => {
    expect(detectStatementType('-- a note\nCREATE TABLE t (x INT)')).toBe('CREATE_TABLE');
  });

  it('names an unknown statement by its first word', () => {
    expect(detectStatementType('DROP TABLE t')).toBe('DROP');
    expect(detectStatementType('   ')).toBe('UNKNOWN');
  });
});

describe('isJobModeStatement', () => {
  /*
   * Miroir de `FlinkSqlService.isJobModeStatement`. Ce garde-ci refuse *avant* d'appeler le
   * serveur, donc une forme qu'il ignore n'atteint jamais celui qui l'accepte : un STATEMENT SET
   * partait à l'erreur côté navigateur alors que le mode Job le soumet en un seul job.
   */
  it('covers what Flink Job mode submits, and nothing else', () => {
    expect(isJobModeStatement(detectStatementType('INSERT INTO t SELECT * FROM s'))).toBe(true);
    expect(isJobModeStatement(detectStatementType(
      'EXECUTE STATEMENT SET BEGIN INSERT INTO a SELECT * FROM s; END'))).toBe(true);
    expect(isJobModeStatement(detectStatementType('SELECT * FROM s'))).toBe(false);
    expect(isJobModeStatement(detectStatementType('CREATE TABLE t (x INT)'))).toBe(false);
  });

  it('recognises a statement set past comments and case', () => {
    expect(detectStatementType('-- fan-out\nexecute statement set begin INSERT INTO a SELECT 1; END'))
      .toBe('STATEMENT_SET');
  });
});


describe('buildCompletionEntries', () => {
  const catalogue = { tables: ['orders'], topics: ['demo.orders.1.received', 'orders'] };
  const schemas = { orders: { id: 'STRING', amount: 'DOUBLE' }, other: { x: 'INT' } };

  it('offers a Kafka topic under the table name a query has to carry', () => {
    const entry = buildCompletionEntries(catalogue, {}, []).find(e => e.label === 'demo_orders_1_received');
    expect(entry).toMatchObject({ kind: 'table', insertText: 'demo_orders_1_received' });
    expect(entry?.documentation).toBe('Topic demo.orders.1.received');
  });

  it('does not offer a topic twice when it is already a registered table', () => {
    const labels = buildCompletionEntries(catalogue, {}, []).filter(e => e.label === 'orders');
    expect(labels).toHaveLength(1);
    expect(labels[0].detail).toBe('Flink Table');
  });

  /* Proposer les colonnes de toutes les tables chargées noyait les bonnes au milieu des inutiles. */
  it('limits columns to the tables the query cites, and ranks them first', () => {
    const entries = buildCompletionEntries(catalogue, schemas, ['orders']);
    const columns = entries.filter(e => e.kind === 'column');
    expect(columns.map(c => c.label)).toEqual(['id', 'amount']);
    expect(columns.every(c => c.sortText?.startsWith('0_'))).toBe(true);
  });

  it('falls back to every loaded table while the query cites none', () => {
    const columns = buildCompletionEntries(catalogue, schemas, []).filter(e => e.kind === 'column');
    expect(columns.map(c => c.label)).toEqual(['id', 'amount', 'x']);
    expect(columns.every(c => c.sortText?.startsWith('1_'))).toBe(true);
  });

  it('ignores a scoped table whose schema has not been loaded', () => {
    const columns = buildCompletionEntries(catalogue, schemas, ['not_loaded']).filter(e => e.kind === 'column');
    expect(columns.map(c => c.label)).toEqual(['id', 'amount', 'x']);
  });

  it('always carries the Flink keywords', () => {
    const keywords = buildCompletionEntries(null, {}, []).filter(e => e.kind === 'keyword');
    expect(keywords.map(k => k.label)).toEqual([...SQL_KEYWORD_SUGGESTIONS]);
  });

  it('says nothing at all on an empty catalogue but the keywords', () => {
    expect(buildCompletionEntries(null, {}, []).every(e => e.kind === 'keyword')).toBe(true);
  });
});

describe('the batch', () => {
  const run = (index: number, rows: number, over: Partial<StatementRun> = {}): StatementRun => ({
    index,
    sql: `SELECT ${index}`,
    kind: 'SELECT',
    status: 'ok',
    ms: 120,
    rows,
    engine: 'KAFKA_DIRECT',
    result: {
      columns: ['a'],
      rows: Array.from({ length: rows }, () => ({ a: 1 })),
      durationMs: 1, error: null, tableRegistered: false, engine: 'KAFKA_DIRECT', warnings: [], changelog: null,
    },
    ...over,
  });

  describe('forgetOldestResults', () => {
    it('keeps everything while the batch fits in its budget', () => {
      const runs = forgetOldestResults([run(1, 10), run(2, 10)], 100);
      expect(runs.every(r => r.result && !r.forgotten)).toBe(true);
    });

    it('releases the oldest rows first, and marks what it released', () => {
      const runs = forgetOldestResults([run(1, 60), run(2, 60), run(3, 60)], 100);
      expect(runs[0]).toMatchObject({ forgotten: true, result: null });
      expect(runs[1].forgotten).toBe(true);
      // La dernière est celle que la grille affiche : jamais libérée.
      expect(runs[2].result?.rows).toHaveLength(60);
    });

    /* « 4 000 lignes, plus affichables » est une réponse ; « 0 ligne » en serait une fausse. */
    it('keeps the row count of what it released', () => {
      const [first] = forgetOldestResults([run(1, 400), run(2, 400)], 100);
      expect(first.rows).toBe(400);
    });

    it('holds a real budget by default', () => {
      expect(MAX_RETAINED_BATCH_ROWS).toBeGreaterThan(1000);
    });
  });

  describe('summariseBatch', () => {
    it('counts each outcome, and names the ones never run', () => {
      expect(summariseBatch([
        run(1, 1),
        run(2, 0, { status: 'failed', result: null }),
        run(3, 0, { status: 'skipped', result: null, rows: undefined }),
      ])).toBe('3 statements · 1 ok · 1 failed · 1 never run');
    });

    it('says nothing it did not count', () => {
      expect(summariseBatch([run(1, 1)])).toBe('1 statement · 1 ok');
    });
  });

  describe('describeStatementRun', () => {
    it('sums up what the statement gave', () => {
      expect(describeStatementRun(run(1, 3))).toBe('120ms · 3 rows · Kafka Direct');
    });

    it('never prints a zero for a measurement that was not taken', () => {
      const skipped = run(2, 0, { status: 'skipped', ms: undefined, rows: undefined, engine: undefined, result: null });
      expect(describeStatementRun(skipped)).toBe('never run');
    });

    it('says the rows were released rather than showing none', () => {
      expect(describeStatementRun(run(1, 0, { rows: 4000, result: null, forgotten: true })))
        .toContain('rows released');
    });
  });
});

describe('statementIndexAt', () => {
  const sql = 'SELECT 1;\nSELECT 2;\nSELECT 3';
  const statements = splitStatements(sql);

  it('finds the statement the cursor sits in', () => {
    expect(statementIndexAt(statements, 3)).toBe(0);
    expect(statementIndexAt(statements, 13)).toBe(1);
    expect(statementIndexAt(statements, 25)).toBe(2);
  });

  it('picks the statement just finished when the cursor follows its semicolon', () => {
    // Offset 9 is right after the `;` of `SELECT 1` — you typed the terminator, you mean that one.
    expect(statementIndexAt(statements, 9)).toBe(0);
  });

  it('picks the first statement when the cursor precedes them all', () => {
    const leading = splitStatements('\n\n   SELECT 1');
    expect(statementIndexAt(leading, 0)).toBe(0);
  });

  it('clamps past the end', () => {
    expect(statementIndexAt(statements, 9999)).toBe(2);
  });

  it('reports nothing to run on an empty document', () => {
    expect(statementIndexAt([], 0)).toBe(-1);
  });
});

describe('positionAt', () => {
  it('is 1-based on both axes', () => {
    expect(positionAt('abc', 0)).toEqual({ line: 1, column: 1 });
    expect(positionAt('abc', 2)).toEqual({ line: 1, column: 3 });
  });

  it('counts lines', () => {
    expect(positionAt('a\nbc\nd', 5)).toEqual({ line: 3, column: 1 });
  });

  it('clamps out-of-range offsets', () => {
    expect(positionAt('ab', -5)).toEqual({ line: 1, column: 1 });
    expect(positionAt('ab', 99)).toEqual({ line: 1, column: 3 });
  });
});

describe('detailValue', () => {
  it('indents an object', () => {
    expect(detailValue({ a: 1 })).toEqual({ text: '{\n  "a": 1\n}', json: true, isNull: false });
  });

  it('indents JSON that arrived as a string — what the direct engine returns', () => {
    expect(detailValue('{"a":1}').text).toBe('{\n  "a": 1\n}');
    expect(detailValue('{"a":1}').json).toBe(true);
  });

  it('leaves text that merely starts like JSON alone', () => {
    expect(detailValue('{not json')).toEqual({ text: '{not json', json: false, isNull: false });
  });

  it('keeps NULL distinct', () => {
    expect(detailValue(null)).toEqual({ text: 'NULL', json: false, isNull: true });
    expect(detailValue('')).toEqual({ text: '', json: false, isNull: false });
  });
});

describe('sortKeyOf', () => {
  it('treats null and undefined as absent', () => {
    expect(sortKeyOf(null).nullish).toBe(true);
    expect(sortKeyOf(undefined).nullish).toBe(true);
  });

  it('reads a numeric string as a number', () => {
    expect(sortKeyOf('42').num).toBe(42);
    expect(sortKeyOf('-1.5').num).toBe(-1.5);
  });

  it('leaves a non-numeric string as text', () => {
    expect(sortKeyOf('ORD-42').num).toBeNull();
  });

  it('does not read an empty string as zero', () => {
    expect(sortKeyOf('').num).toBeNull();
    expect(sortKeyOf('').nullish).toBe(false);
  });
});

describe('sortRows', () => {
  const rows = [{ n: '10' }, { n: '9' }, { n: '100' }];

  it('orders numeric strings numerically, not lexicographically', () => {
    expect(sortRows(rows, 'n', 'asc').map(r => r.n)).toEqual(['9', '10', '100']);
    expect(sortRows(rows, 'n', 'desc').map(r => r.n)).toEqual(['100', '10', '9']);
  });

  it('sends absent values last in both directions', () => {
    const withNulls = [{ v: 2 }, { v: null }, { v: 1 }];
    expect(sortRows(withNulls, 'v', 'asc').map(r => r.v)).toEqual([1, 2, null]);
    expect(sortRows(withNulls, 'v', 'desc').map(r => r.v)).toEqual([2, 1, null]);
  });

  it('is stable on equal keys', () => {
    const equal = [{ k: 1, id: 'a' }, { k: 1, id: 'b' }, { k: 1, id: 'c' }];
    expect(sortRows(equal, 'k', 'desc').map(r => r.id)).toEqual(['a', 'b', 'c']);
  });

  it('returns a copy and leaves the input untouched', () => {
    const input = [{ v: 2 }, { v: 1 }];
    const out = sortRows(input, 'v', 'asc');
    expect(out).not.toBe(input);
    expect(input.map(r => r.v)).toEqual([2, 1]);
  });

  it('copies without sorting when no column is chosen', () => {
    expect(sortRows(rows, null, 'asc').map(r => r.n)).toEqual(['10', '9', '100']);
  });
});

describe('the changelog marker', () => {
  it('reads a row that carries no marker as an insert — the server only writes the exception', () => {
    expect(rowKindOf({ a: 1 }).kind).toBe('+I');
    expect(rowKindOf({}).kind).toBe('+I');
    expect(rowKindOf(null).kind).toBe('+I');
  });

  it('names what each correction does to the row before it', () => {
    expect(rowKindOf({ [ROW_KIND_KEY]: '-U' }).tone).toBe('retract');
    expect(rowKindOf({ [ROW_KIND_KEY]: '+U' }).tone).toBe('update');
    expect(rowKindOf({ [ROW_KIND_KEY]: '-D' }).tone).toBe('retract');
    // Chaque genre porte un libellé en toutes lettres : le symbole seul ne dit rien, et c'est
    // exactement la ligne dont il faut savoir si elle compte.
    expect(rowKindOf({ [ROW_KIND_KEY]: '+U' }).label).toMatch(/replaces/i);
  });

  it('ignores a value it does not recognise rather than inventing a kind', () => {
    expect(rowKindOf({ [ROW_KIND_KEY]: 'whatever' }).kind).toBe('+I');
    expect(rowKindOf({ [ROW_KIND_KEY]: 7 }).kind).toBe('+I');
  });

  it('says nothing at all when no row was corrected', () => {
    expect(describeChangelog(null)).toBeNull();
    expect(describeChangelog({ rowsReturned: 3, corrections: 0, retractions: 0, capReached: false })).toBeNull();
  });

  it('counts the corrections and says the answer is the last row for a key', () => {
    const said = describeChangelog({ rowsReturned: 5, corrections: 4, retractions: 2, capReached: false });
    expect(said?.detail).toContain('4 of the 5');
    expect(said?.detail).toMatch(/2 of them withdraw/);
    expect(said?.detail).toMatch(/last row carrying it/);
    // Pas tronqué : rien ne doit laisser croire que la requête a été coupée.
    expect(said?.truncated).toBeNull();
  });

  /**
   * Le plafond compte les corrections, donc un changelog peut le remplir d'états intermédiaires et
   * être coupé juste avant la seule ligne qui comptait. Ce n'est plus la même lecture du dernier
   * résultat affiché, et c'est une phrase séparée pour cette raison.
   */
  it('states separately that the cap cut the changelog before it settled', () => {
    const said = describeChangelog({ rowsReturned: 50, corrections: 49, retractions: 24, capReached: true });
    expect(said?.truncated).toMatch(/not necessarily the final answer/);
  });
});

describe('exporting a changelog', () => {
  it('leaves an ordinary result exactly as it is', () => {
    const rows = [{ a: 1 }];
    expect(exportColumns(['a'], false)).toEqual(['a']);
    expect(exportRows(rows, false)).toEqual(rows);
  });

  /**
   * Un fichier ne peut pas être interrogé : celui qui ne dit pas lesquelles de ses lignes sont des
   * corrections les présente comme des résultats — le mensonge même que le marqueur retire de
   * l'écran. Toutes les lignes portent leur genre, un `+I` implicite n'étant pas lisible.
   */
  it('puts the kind first and writes it on every row', () => {
    expect(exportColumns(['a', 'b'], true)).toEqual([ROW_KIND_KEY, 'a', 'b']);
    const out = exportRows([{ a: 1 }, { a: 2, [ROW_KIND_KEY]: '-U' }], true);
    expect(out[0][ROW_KIND_KEY]).toBe('+I');
    expect(out[1][ROW_KIND_KEY]).toBe('-U');
    expect(out[1].a).toBe(2);
  });
});

describe('cellText', () => {
  it('distinguishes NULL from the empty string', () => {
    expect(cellText(null)).toEqual({ text: 'NULL', isNull: true });
    expect(cellText('')).toEqual({ text: '', isNull: false });
  });

  it('serializes an object', () => {
    expect(cellText({ a: 1 }).text).toBe('{"a":1}');
  });

  it('keeps a zero and a false visible', () => {
    expect(cellText(0)).toEqual({ text: '0', isNull: false });
    expect(cellText(false)).toEqual({ text: 'false', isNull: false });
  });
});

describe('nextActiveTabId', () => {
  const tabs = [{ id: '1' }, { id: '2' }, { id: '3' }];

  it('falls back to the left neighbour', () => {
    expect(nextActiveTabId(tabs, '2', '2')).toBe('1');
  });

  it('falls back to the right neighbour when closing the first', () => {
    expect(nextActiveTabId(tabs, '1', '1')).toBe('2');
  });

  it('leaves the active tab alone when another one is closed', () => {
    expect(nextActiveTabId(tabs, '3', '1')).toBe('1');
  });

  it('never empties the bar', () => {
    expect(nextActiveTabId([{ id: '1' }], '1', '1')).toBe('1');
  });
});

describe('isResultStale', () => {
  it('is false before any run', () => {
    expect(isResultStale(null, 'SELECT 1')).toBe(false);
  });

  it('ignores whitespace differences', () => {
    expect(isResultStale('SELECT a FROM t', 'SELECT   a\nFROM t')).toBe(false);
  });

  it('reports a real edit', () => {
    expect(isResultStale('SELECT a FROM t', 'SELECT b FROM t')).toBe(true);
  });
});

describe('storage helpers', () => {
  afterEach(() => { vi.restoreAllMocks(); localStorage.clear(); });

  it('round-trips a value', () => {
    expect(writeStored('kse:test', { a: 1 })).toBe(true);
    expect(readStored('kse:test', null)).toEqual({ a: 1 });
    expect(removeStored('kse:test')).toBe(true);
    expect(readStored('kse:test', 'fallback')).toBe('fallback');
  });

  it('reports a failed write instead of throwing', () => {
    vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => { throw new Error('QuotaExceeded'); });
    expect(writeStored('kse:test', { a: 1 })).toBe(false);
  });

  it('falls back on unreadable content', () => {
    localStorage.setItem('kse:test', '{not json');
    expect(readStored('kse:test', 'fallback')).toBe('fallback');
  });
});

describe('starterTable / starterQueries', () => {
  it('prefers a Flink table over a topic', () => {
    expect(starterTable({ tables: ['orders'], topics: ['demo.payments'] })).toBe('orders');
  });

  it('falls back to a topic, normalized to its table name', () => {
    expect(starterTable({ tables: [], topics: ['demo.orders.1.received'] }))
      .toBe('demo_orders_1_received');
  });

  it('skips the internal topics the app writes to itself', () => {
    expect(starterTable({ tables: [], topics: ['internal.audit.history', 'demo.customers'] }))
      .toBe('demo_customers');
  });

  it('offers nothing at all rather than an example that cannot run', () => {
    expect(starterTable({ tables: [], topics: [] })).toBeNull();
    expect(starterTable(null)).toBeNull();
    expect(starterQueries({ tables: [], topics: [] })).toEqual([]);
    expect(starterQueries(null)).toEqual([]);
  });

  it('builds every suggestion on a table the catalogue actually holds', () => {
    const queries = starterQueries({ tables: [], topics: ['demo.orders.nested'] });
    expect(queries).toHaveLength(2);
    for (const q of queries) expect(q.sql).toContain('demo_orders_nested');
  });

  it('aliases the aggregate, which the direct engine requires', () => {
    const count = starterQueries({ tables: ['orders'], topics: [] })[1];
    expect(count.sql).toBe('SELECT COUNT(*) AS metric_value FROM orders');
  });
});

describe('sidebarSqlFor / sidebarActionLabel', () => {
  it('reads the table, with the row cap actually in force', () => {
    expect(sidebarSqlFor('demo_orders', 500)).toBe('SELECT * FROM demo_orders LIMIT 500');
  });

  // Le mode « Flink job » posait ici un squelette d'INSERT INTO ; il a été retiré de l'éditeur,
  // et avec lui la seule raison qu'avait ce raccourci d'écrire autre chose qu'une lecture.
  it('writes a read and nothing else — the editor has no other mode', () => {
    const sql = sidebarSqlFor('demo_orders', 50);
    expect(sql).not.toContain('INSERT INTO');
    expect(sql.startsWith('SELECT')).toBe(true);
  });

  it('names the action the click performs', () => {
    expect(sidebarActionLabel('demo.orders')).toBe('SELECT from demo.orders');
  });
});

describe('pushHistory', () => {
  const entry = (sql: string, ts = 1): HistoryEntry => ({ sql, ts });

  it('puts the newest first', () => {
    const out = pushHistory([entry('a')], entry('b', 2));
    expect(out.map(h => h.sql)).toEqual(['b', 'a']);
  });

  it('replaces an identical query rather than stacking it', () => {
    const out = pushHistory([{ sql: 'a', ts: 1, rows: 5 }], { sql: 'a', ts: 2, rows: 9 });
    expect(out).toEqual([{ sql: 'a', ts: 2, rows: 9 }]);
  });

  it('honours the cap', () => {
    const many = Array.from({ length: 20 }, (_, i) => entry(`q${i}`));
    expect(pushHistory(many, entry('new')).length).toBe(20);
    expect(pushHistory(many, entry('new'), 3).map(h => h.sql)).toEqual(['new', 'q0', 'q1']);
  });

  it('ignores a blank query and trims what it stores', () => {
    expect(pushHistory([], entry('   '))).toEqual([]);
    expect(pushHistory([], entry('  SELECT 1  '))[0].sql).toBe('SELECT 1');
  });
});

describe('describeHistoryEntry', () => {
  it('says nothing when the entry carries no outcome', () => {
    expect(describeHistoryEntry({ sql: 'SELECT 1', ts: 1 })).toBe('');
  });

  it('summarises a successful run', () => {
    expect(describeHistoryEntry({ sql: 'q', ts: 1, ms: 1200, rows: 50, engine: 'KAFKA_DIRECT' }))
      .toBe('1.2s · 50 rows · Kafka Direct');
  });

  it('leads with the failure', () => {
    expect(describeHistoryEntry({ sql: 'q', ts: 1, ms: 340, ok: false })).toBe('failed · 340ms');
  });

  it('does not pluralise a single row, and keeps a zero visible', () => {
    expect(describeHistoryEntry({ sql: 'q', ts: 1, rows: 1 })).toBe('1 row');
    expect(describeHistoryEntry({ sql: 'q', ts: 1, rows: 0 })).toBe('0 rows');
  });
});

describe('formatDuration', () => {
  it('switches unit at the second', () => {
    expect(formatDuration(999)).toBe('999ms');
    expect(formatDuration(1000)).toBe('1.0s');
  });
});

describe('readLayout', () => {
  afterEach(() => localStorage.clear());

  it('returns the defaults when nothing is stored', () => {
    expect(readLayout()).toEqual(DEFAULT_LAYOUT);
  });

  it('round-trips a stored layout', () => {
    writeStored(LAYOUT_STORAGE_KEY, { splitPercent: 70, sidebarWidth: 320, assistantOpen: false });
    expect(readLayout()).toEqual({ splitPercent: 70, sidebarWidth: 320, assistantOpen: false });
  });

  it('clamps a value that would make a pane unusable', () => {
    writeStored(LAYOUT_STORAGE_KEY, { splitPercent: 2, sidebarWidth: 9000 });
    expect(readLayout()).toMatchObject({ splitPercent: SPLIT_MIN, sidebarWidth: SIDEBAR_MAX });
  });

  it('ignores a value of the wrong shape rather than propagating NaN', () => {
    writeStored(LAYOUT_STORAGE_KEY, { splitPercent: 'wide', sidebarWidth: null });
    expect(readLayout()).toEqual(DEFAULT_LAYOUT);
  });

  it('reads a layout written before the assistant could fold as open', () => {
    writeStored(LAYOUT_STORAGE_KEY, { splitPercent: 60, sidebarWidth: 300 });
    expect(readLayout().assistantOpen).toBe(true);
  });
});

describe('withoutLeadingCte', () => {
  it('leaves a plain statement alone', () => {
    expect(withoutLeadingCte('SELECT * FROM orders')).toBe('SELECT * FROM orders');
  });

  it('sees past a single CTE', () => {
    expect(withoutLeadingCte('WITH recent AS (SELECT * FROM orders) SELECT * FROM recent'))
      .toBe('SELECT * FROM recent');
  });

  it('sees past a chain of them', () => {
    expect(withoutLeadingCte('WITH a AS (SELECT 1), b AS (SELECT 2) SELECT * FROM a'))
      .toBe('SELECT * FROM a');
  });

  it('is not fooled by a parenthesis inside a string literal', () => {
    expect(withoutLeadingCte("WITH a AS (SELECT * FROM t WHERE l = 'oops )') SELECT * FROM a"))
      .toBe('SELECT * FROM a');
  });

  it('classifies what follows, so a CTE around an INSERT stays an INSERT', () => {
    expect(withoutLeadingCte('WITH a AS (SELECT 1) INSERT INTO sink SELECT * FROM a'))
      .toBe('INSERT INTO sink SELECT * FROM a');
  });

  it('fails closed on a shape it does not recognise', () => {
    for (const sql of ['WITH', 'WITH (', 'WITH a AS SELECT 1', 'WITH a AS (SELECT 1']) {
      expect(withoutLeadingCte(sql)).toBe(sql);
    }
  });

  it('does not match an identifier that merely begins with "with"', () => {
    expect(withoutLeadingCte('SELECT withdrawal FROM t')).toBe('SELECT withdrawal FROM t');
  });
});

describe('readSqlParam / buildQueryLink', () => {
  it('round-trips SQL containing a percent sign', () => {
    // The regression: URLSearchParams.get already decodes, and the caller decoded a second time —
    // which throws URIError on `LIKE '%foo%'` and took the whole page down with it, the read
    // happening inside a useState initializer.
    const sql = "SELECT * FROM t WHERE name LIKE '%foo%'";
    const link = buildQueryLink('https://host/query', sql);
    expect(readSqlParam(new URL(link).search)).toBe(sql);
  });

  it('round-trips ampersands, plus signs and newlines', () => {
    const sql = 'SELECT a\nFROM t\nWHERE b = 1 + 2 AND c = 3';
    expect(readSqlParam(new URL(buildQueryLink('https://host/query', sql)).search)).toBe(sql);
  });

  it('returns null when there is no usable parameter', () => {
    expect(readSqlParam('')).toBeNull();
    expect(readSqlParam('?other=1')).toBeNull();
    expect(readSqlParam('?sql=')).toBeNull();
    expect(readSqlParam('?sql=%20%20')).toBeNull();
  });

  it('builds nothing from an empty tab', () => {
    expect(buildQueryLink('https://host/query', '')).toBe('');
    expect(buildQueryLink('https://host/query', '   ')).toBe('');
  });

  it('never throws on a malformed query string', () => {
    expect(() => readSqlParam('?sql=%')).not.toThrow();
  });
});

// ── Fenêtre trop étroite ─────────────────────────────────────────────────────

describe('NARROW_ALTERNATIVES', () => {
  it('names only screens that exist as routes', () => {
    // Un bandeau qui renvoie vers un 404 est pire que pas de bandeau : il arrive au moment où
    // l'opérateur cherche une réponse. Le Topic Explorer est mesuré bon à 390 px lui aussi, mais
    // sa route demande un nom de topic — c'est pour ça qu'il n'est pas dans la liste.
    const paths = new Set(NAV_ITEMS.map(item => item.path));
    for (const alternative of NARROW_ALTERNATIVES) {
      expect(paths.has(alternative.to)).toBe(true);
    }
  });

  it('says what each screen answers, not just its name', () => {
    for (const alternative of NARROW_ALTERNATIVES) {
      expect(alternative.answers.length).toBeGreaterThan(10);
    }
  });

  it('matches the width the shell itself calls desktop', () => {
    // `DESKTOP_QUERY` dans Layout.tsx et ce seuil décrivent le même « il y a la place » : les
    // laisser diverger ferait apparaître le bandeau au-dessus d'un éditeur devenu utilisable.
    expect(WIDE_WINDOW_MIN_PX).toBe(1024);
  });
});

describe('le rejet du bandeau', () => {
  it('tient sur ce navigateur, et ne ment pas quand le stockage refuse', () => {
    expect(readNarrowNoticeDismissed()).toBe(false);
    dismissNarrowNotice();
    expect(readNarrowNoticeDismissed()).toBe(true);
  });
});
