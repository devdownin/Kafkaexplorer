import { describe, it, expect, afterEach, vi } from 'vitest';
import {
  formatSql, tokenizeSql, sortRows, sortKeyOf, cellText, nextActiveTabId,
  isResultStale, writeStored, readStored, removeStored,
  readLayout, LAYOUT_STORAGE_KEY, DEFAULT_LAYOUT, SPLIT_MIN, SIDEBAR_MAX,
  starterTable, starterQueries, pushHistory, describeHistoryEntry, formatDuration,
  type HistoryEntry,
} from './queryWorkbench';

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
    writeStored(LAYOUT_STORAGE_KEY, { splitPercent: 70, sidebarWidth: 320 });
    expect(readLayout()).toEqual({ splitPercent: 70, sidebarWidth: 320 });
  });

  it('clamps a value that would make a pane unusable', () => {
    writeStored(LAYOUT_STORAGE_KEY, { splitPercent: 2, sidebarWidth: 9000 });
    expect(readLayout()).toEqual({ splitPercent: SPLIT_MIN, sidebarWidth: SIDEBAR_MAX });
  });

  it('ignores a value of the wrong shape rather than propagating NaN', () => {
    writeStored(LAYOUT_STORAGE_KEY, { splitPercent: 'wide', sidebarWidth: null });
    expect(readLayout()).toEqual(DEFAULT_LAYOUT);
  });
});
