// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors

import { describe, it, expect } from 'vitest';
import { csvCell, toCsv } from './resultExport';

describe('csvCell', () => {
  it('leaves a plain value unquoted', () => {
    expect(csvCell('hello')).toBe('hello');
    expect(csvCell(42)).toBe('42');
  });

  it('quotes a value containing a comma, a quote or a newline', () => {
    expect(csvCell('a,b')).toBe('"a,b"');
    expect(csvCell('say "hi"')).toBe('"say ""hi"""');
    expect(csvCell('line1\nline2')).toBe('"line1\nline2"');
  });

  it('renders null and undefined as an empty cell, not the string "null"', () => {
    expect(csvCell(null)).toBe('');
    expect(csvCell(undefined)).toBe('');
  });

  it('quotes a serialised object so its internal comma cannot shift the columns', () => {
    // Le bug d'origine : JSON.stringify({a:1,b:2}) partait tel quel, virgule comprise.
    const cell = csvCell({ a: 1, b: 2 });
    expect(cell).toBe('"{""a"":1,""b"":2}"');
    expect(cell.startsWith('"')).toBe(true);
  });

  it('keeps false and 0 rather than blanking them', () => {
    expect(csvCell(false)).toBe('false');
    expect(csvCell(0)).toBe('0');
  });
});

describe('toCsv', () => {
  it('keeps one field per column even when a value contains commas', () => {
    const csv = toCsv(['id', 'payload'], [{ id: 1, payload: { a: 1, b: 2 } }]);
    const dataLine = csv.split('\r\n')[1];

    // Un parseur naïf découperait sur la virgule ; comptons les virgules hors guillemets.
    let inQuotes = false, separators = 0;
    for (let i = 0; i < dataLine.length; i++) {
      if (dataLine[i] === '"') inQuotes = !inQuotes;
      else if (dataLine[i] === ',' && !inQuotes) separators++;
    }
    expect(separators).toBe(1);
  });

  it('escapes the header row too', () => {
    expect(toCsv(['odd,name'], []).split('\r\n')[0]).toBe('"odd,name"');
  });

  it('emits an empty cell for a column missing from a row', () => {
    expect(toCsv(['a', 'b'], [{ a: 1 }])).toBe('a,b\r\n1,');
  });

  it('separates records with CRLF, per RFC 4180', () => {
    expect(toCsv(['a'], [{ a: 1 }, { a: 2 }])).toBe('a\r\n1\r\n2');
  });
});
