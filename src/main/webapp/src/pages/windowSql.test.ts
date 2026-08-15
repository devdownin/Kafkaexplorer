// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors

import { describe, it, expect } from 'vitest';
import { buildWindowSql, windowFunctionCall, windowCaveat, guessTimeColumn, type WindowSpec } from './windowSql';

const base: WindowSpec = { kind: 'TUMBLE', table: 'orders', timeColumn: 'event_time', size: 5, unit: 'MINUTE' };

describe('windowFunctionCall', () => {
  it('builds a tumbling window with a single interval', () => {
    expect(windowFunctionCall(base))
      .toBe("TUMBLE(TABLE orders, DESCRIPTOR(event_time), INTERVAL '5' MINUTE)");
  });

  it('builds a hopping window with the slide first, as Flink expects', () => {
    expect(windowFunctionCall({ ...base, kind: 'HOP', slide: 1 }))
      .toBe("HOP(TABLE orders, DESCRIPTOR(event_time), INTERVAL '1' MINUTE, INTERVAL '5' MINUTE)");
  });

  it('falls back to half the size when the slide would not overlap', () => {
    // Un pas >= taille ne glisse pas ; la fenêtre ne serait pas « hopping ».
    expect(windowFunctionCall({ ...base, kind: 'HOP', size: 10, slide: 10 }))
      .toContain("INTERVAL '5' MINUTE, INTERVAL '10' MINUTE");
  });

  it('builds a session window with its PARTITION BY key', () => {
    expect(windowFunctionCall({ ...base, kind: 'SESSION', partitionBy: 'user_id' }))
      .toBe("SESSION(TABLE orders PARTITION BY user_id, DESCRIPTOR(event_time), INTERVAL '5' MINUTE)");
  });

  it('emits distinct SQL per window type — the dropdown used to be decorative', () => {
    const tumble = windowFunctionCall(base);
    const hop = windowFunctionCall({ ...base, kind: 'HOP', slide: 2 });
    const session = windowFunctionCall({ ...base, kind: 'SESSION', partitionBy: 'k' });
    expect(new Set([tumble, hop, session]).size).toBe(3);
  });

  it('backticks a topic name that is not a bare identifier', () => {
    expect(windowFunctionCall({ ...base, table: 'my.topic-name' }))
      .toContain('TABLE `my.topic-name`');
  });

  it('coerces a non-positive or fractional size to a whole positive interval', () => {
    expect(windowFunctionCall({ ...base, size: 0 })).toContain("INTERVAL '1'");
    expect(windowFunctionCall({ ...base, size: 7.9 })).toContain("INTERVAL '7'");
  });
});

describe('buildWindowSql', () => {
  it('wraps the call in a grouped aggregation', () => {
    const sql = buildWindowSql(base);
    expect(sql).toContain('FROM TABLE(TUMBLE(TABLE orders');
    expect(sql).toContain('GROUP BY window_start, window_end');
    expect(sql.startsWith('SELECT')).toBe(true);
  });
});

describe('windowCaveat', () => {
  it('says nothing for a plain tumbling window', () => {
    expect(windowCaveat(base)).toBe('');
  });
  it('demands a partition key for SESSION before the planner rejects it', () => {
    expect(windowCaveat({ ...base, kind: 'SESSION' })).toMatch(/PARTITION BY/);
  });
  it('warns that the direct reader only approximates HOP', () => {
    expect(windowCaveat({ ...base, kind: 'HOP' })).toMatch(/approximated|tumbling/i);
  });
});

describe('guessTimeColumn', () => {
  it('prefers a column whose type is temporal', () => {
    expect(guessTimeColumn({ id: 'STRING', created: 'TIMESTAMP(3)' })).toBe('created');
  });
  it('falls back to a time-ish name when no type matches', () => {
    expect(guessTimeColumn({ id: 'STRING', event_ts: 'BIGINT' })).toBe('event_ts');
  });
  it('returns null when nothing looks temporal', () => {
    expect(guessTimeColumn({ id: 'STRING', label: 'STRING' })).toBeNull();
    expect(guessTimeColumn(undefined)).toBeNull();
  });
});
