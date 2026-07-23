import { describe, it, expect } from 'vitest';
import { describeQueryError, parseSqlLocation } from './queryError';

describe('parseSqlLocation', () => {
  it('extracts a line/column pair from a Calcite message', () => {
    expect(parseSqlLocation('SQL parse failed. Encountered "FORM" at line 3, column 5.'))
      .toEqual({ line: 3, column: 5 });
  });
  it('returns undefined when there is no position', () => {
    expect(parseSqlLocation('Object \'orders\' not found')).toBeUndefined();
  });
});

describe('describeQueryError', () => {
  it('classifies a syntax error and keeps the location + raw text', () => {
    const raw = 'org.apache.flink.table.api.SqlParserException: SQL parse failed. Encountered "FORM" at line 2, column 8.\n\tat ...';
    const info = describeQueryError(raw);
    expect(info.title).toBe('Syntax error');
    expect(info.location).toEqual({ line: 2, column: 8 });
    expect(info.hint).toContain('2:8');
    expect(info.raw).toBe(raw); // rien n'est perdu
  });

  it('names the missing table and suggests the schema browser', () => {
    const info = describeQueryError("Object 'orders_stream' not found");
    expect(info.title).toContain('orders_stream');
    expect(info.title.toLowerCase()).toContain('unknown table');
    expect(info.hint).toMatch(/schema browser/i);
  });

  it('handles the double-quoted "Table" variant too', () => {
    const info = describeQueryError('Table "clicks" not found within cluster');
    expect(info.title).toContain('clicks');
    expect(info.title.toLowerCase()).toContain('unknown table');
  });

  it('names the missing column', () => {
    const info = describeQueryError("Column 'amountt' not found in any table");
    expect(info.title).toContain('amountt');
    expect(info.title.toLowerCase()).toContain('unknown column');
  });

  it('recognises a query timeout', () => {
    const info = describeQueryError('Query timed out after 10000 ms');
    expect(info.title).toBe('Query timed out');
    expect(info.hint).toMatch(/limit/i);
  });

  it('recognises an unreachable broker', () => {
    const info = describeQueryError('Cannot reach Kafka broker: Connection to localhost:9092 refused');
    expect(info.title).toBe('Kafka is unreachable');
  });

  it('unwraps the validator prefix and flags forbidden statements', () => {
    const info = describeQueryError('SQL Validation Error: Cross joins are not allowed in this environment.');
    expect(info.title).toBe('Statement not permitted');
    expect(info.hint).toMatch(/SELECT/);
  });

  it('falls back to the first non-empty line for unknown errors', () => {
    const info = describeQueryError('\n\nSomething entirely unexpected happened\n\tat foo');
    expect(info.title).toBe('Something entirely unexpected happened');
    expect(info.hint).toBeUndefined();
  });

  it('degrades gracefully on empty input', () => {
    const info = describeQueryError('');
    expect(info.title).toBe('Query failed');
    expect(info.raw).toBe('');
  });
});
