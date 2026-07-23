import { describe, it, expect } from 'vitest';
import { describeQueryError, parseSqlLocation, extractApiErrorMessage, describeApiError } from './queryError';

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

describe('extractApiErrorMessage', () => {
  it('prefers the backend response body message', () => {
    const err = { response: { data: { message: 'Column \'x\' not found' }, status: 400 } };
    expect(extractApiErrorMessage(err)).toBe("Column 'x' not found");
  });
  it('falls back to the response error field, then to HTTP status', () => {
    expect(extractApiErrorMessage({ response: { data: { error: 'boom' } } })).toBe('boom');
    expect(extractApiErrorMessage({ response: { status: 503, statusText: 'Service Unavailable' } }))
      .toBe('Server responded 503 Service Unavailable');
  });
  it('uses the exception message and finally the fallback', () => {
    expect(extractApiErrorMessage({ message: 'kaboom' })).toBe('kaboom');
    expect(extractApiErrorMessage({}, 'nothing useful')).toBe('nothing useful');
  });
});

describe('describeApiError', () => {
  it('classifies a network failure with no response', () => {
    const info = describeApiError({ code: 'ERR_NETWORK', message: 'Network Error' });
    expect(info.title).toBe('Cannot reach the server');
    expect(info.hint).toMatch(/offline|unreachable/i);
  });
  it('classifies an aborted (timeout) request', () => {
    const info = describeApiError({ code: 'ECONNABORTED', message: 'timeout of 10000ms exceeded' });
    expect(info.title).toBe('Request timed out');
  });
  it('delegates a server-provided SQL message to the SQL classifier', () => {
    const info = describeApiError({ response: { data: { message: "Object 'orders' not found" }, status: 400 } });
    expect(info.title).toContain('orders');
    expect(info.title.toLowerCase()).toContain('unknown table');
  });
  it('uses the fallback when the error is opaque', () => {
    const info = describeApiError({}, 'Failed to trace stream flow.');
    expect(info.title).toBe('Failed to trace stream flow.');
  });
});
