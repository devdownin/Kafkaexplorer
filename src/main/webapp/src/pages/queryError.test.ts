// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors

import { describe, it, expect } from 'vitest';
import {
  describeQueryError, parseSqlLocation, extractApiErrorMessage, describeApiError, offsetLocation,
} from './queryError';

describe('offsetLocation', () => {
  it('leaves the position alone when the whole document was run', () => {
    expect(offsetLocation({ line: 3, column: 7 }, undefined)).toEqual({ line: 3, column: 7 });
  });

  it('shifts both line and column on the first line of a selection', () => {
    // Sélection démarrée en 5:10 ; le moteur signale 1:3 dans le fragment.
    expect(offsetLocation({ line: 1, column: 3 }, { line: 5, column: 10 }))
      .toEqual({ line: 5, column: 12 });
  });

  it('shifts only the line beyond the first, which starts at column 1 again', () => {
    expect(offsetLocation({ line: 2, column: 4 }, { line: 5, column: 10 }))
      .toEqual({ line: 6, column: 4 });
  });

  it('is identity for a selection starting at the top of the document', () => {
    expect(offsetLocation({ line: 2, column: 4 }, { line: 1, column: 1 }))
      .toEqual({ line: 2, column: 4 });
  });

  it('passes through an absent position', () => {
    expect(offsetLocation(undefined, { line: 5, column: 10 })).toBeUndefined();
  });
});

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

  it('names an unknown function', () => {
    const info = describeQueryError("No match found for function signature TO_TIMSTAMP(<CHARACTER>)");
    expect(info.title).toContain('TO_TIMSTAMP');
    expect(info.title.toLowerCase()).toContain('unknown function');
  });

  it('recognises a type mismatch and suggests a cast', () => {
    const info = describeQueryError("Cannot apply '>' to arguments of type '<VARCHAR> > <INTEGER>'");
    expect(info.title).toBe('Incompatible types');
    expect(info.hint).toMatch(/CAST/);
  });

  it('recognises a column missing from GROUP BY', () => {
    const info = describeQueryError("Expression 'status' is not being grouped");
    expect(info.title).toContain('status');
    expect(info.hint).toMatch(/GROUP BY/);
  });

  it('handles the "does not exist" phrasing for a missing table', () => {
    const info = describeQueryError("Table (or view) 'orders' does not exist");
    expect(info.title).toContain('orders');
    expect(info.title.toLowerCase()).toContain('unknown table');
  });

  it('falls back to the first non-empty line for unknown errors', () => {
    const info = describeQueryError('\n\nSomething entirely unexpected happened\n\tat foo');
    expect(info.title).toBe('Something entirely unexpected happened');
    expect(info.hint).toBeUndefined();
  });

  it('bounds the fallback title but keeps the full text in raw', () => {
    // Le backend chaîne désormais les causes : le titre ne doit pas devenir un pavé.
    const raw = `Failed to execute: ${'x'.repeat(500)}`;
    const info = describeQueryError(raw);
    expect(info.title.length).toBeLessThanOrEqual(181);
    expect(info.title.endsWith('…')).toBe(true);
    expect(info.raw).toBe(raw);
  });

  it('classifies a chained backend message by its innermost cause', () => {
    // SqlErrorClassifier.explain() aplatit la chaîne en "wrapper: cause".
    const info = describeQueryError(
      'Failed to execute query: SQL parse failed. Encountered "FORM" at line 4, column 3.');
    expect(info.title).toBe('Syntax error');
    expect(info.location).toEqual({ line: 4, column: 3 });
  });

  it('degrades gracefully on empty input', () => {
    const info = describeQueryError('');
    expect(info.title).toBe('Query failed');
    expect(info.raw).toBe('');
  });

  /**
   * Les refus que le moteur rend désormais à l'appelant au lieu de se replier sur le lecteur
   * direct. Ils arrivaient tous en titre brut, sans piste : la machinerie qui rend un message
   * correct actionnable existe, et ces familles-là n'y étaient pas.
   */
  describe('what the streaming planner refuses to build', () => {
    it('reads an unbounded ORDER BY as a bound to add, not as an engine fault', () => {
      const info = describeQueryError(
        'Sort on a non-time-attribute field is not supported.');
      expect(info.title).toMatch(/ORDER BY/);
      expect(info.hint).toMatch(/LIMIT/);
    });

    /**
     * Le refus d'une fenêtre faute d'attribut temporel, dans la forme où il arrive vraiment :
     * enveloppé dans la règle Calcite qui a échoué, ses arguments et le plan. Sans famille, ce
     * pavé passait pour titre.
     */
    it('reads a window on a column with no watermark as a table to fix', () => {
      const info = describeQueryError(
        'Error while applying rule StreamPhysicalWindowTableFunctionRule(in:LOGICAL,out:STREAM_PHYSICAL), '
        + "args [rel#27876:FlinkLogicalTableFunctionScan.LOGICAL.any.None: 0.[NONE].[NONE].[NONE]"
        + '(invocation=TUMBLE(TABLE(#0), DESCRIPTOR(_UTF-16LE\'event_time\'), 300000:INTERVAL MINUTE))]: '
        + 'The window function requires the timecol is a time attribute type, but is TIMESTAMP(3).');
      expect(info.title).toMatch(/not a time attribute/);
      expect(info.hint).toMatch(/WATERMARK/);
      expect(info.raw).toMatch(/rel#/);
    });

    it('gives the same reading to an OVER window with no time attribute', () => {
      const info = describeQueryError(
        "OVER windows' ordering in stream mode must be defined on a time attribute.");
      expect(info.title).toMatch(/not a time attribute/);
    });

    it('names the rewrite for a correlated subquery', () => {
      const info = describeQueryError(
        'org.apache.calcite.plan.RelOptPlanner$CannotPlanException: unexpected correlate variable $cor0');
      expect(info.title).toMatch(/Correlated subquery/);
      expect(info.hint).toMatch(/JOIN/);
    });
  });

  describe('a projection that does not fit its target table', () => {
    /**
     * Flink formule cette faute unique de plusieurs façons. La plus courante — « Different number
     * of columns » — tombait avant dans le repli générique, et « Incompatible types for sink
     * column » dans la famille « types incompatibles », qui disait de caster une colonne alors que
     * le problème est la liste des colonnes.
     */
    it('gives the same reading whichever wording Flink used', () => {
      for (const raw of [
        'Column types of query result and sink for \'default_catalog.default_database.k_sink\' do not match.',
        'Different number of columns.',
        'Incompatible types for sink column \'order_id\' at position 0.',
      ]) {
        const info = describeQueryError(raw);
        expect(info.title).toMatch(/does not fit the target table/);
        expect(info.hint).toMatch(/proc_time/);
      }
    });

    it('separates a sink that cannot be overwritten from one that does not match', () => {
      const info = describeQueryError(
        'INSERT OVERWRITE requires that the underlying DynamicTableSink of table \'k_sink\' implements the SupportsOverwrite interface.');
      expect(info.title).toMatch(/cannot be overwritten/);
    });

    it('points an options hint at the table rather than the view', () => {
      const info = describeQueryError(
        'View \'k_view\' cannot be enriched with new options.');
      expect(info.title).toMatch(/Options hint/);
    });
  });

  it('explains the CTAS refusal instead of quoting it back', () => {
    const info = describeQueryError(
      'CREATE TABLE … AS SELECT starts a job that writes rows, so it is not run here: the job '
      + 'would be invisible to the dashboard and could not be cancelled.');
    expect(info.title).toMatch(/AS SELECT is not run here/);
    expect(info.hint).toMatch(/INSERT INTO/);
  });

  it('names what the editor does run when the whitelist refuses a statement', () => {
    const info = describeQueryError(
      'Only SELECT, EXPLAIN, SHOW, DESCRIBE and CREATE TABLE statements are allowed.');
    expect(info.title).toBe('Statement not permitted');
    // SHOW et DESCRIBE sont désormais servis : la piste ne doit plus dire le contraire.
    expect(info.hint).toMatch(/SHOW/);
    expect(info.hint).toMatch(/DESCRIBE/);
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
