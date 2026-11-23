// SPDX-License-Identifier: AGPL-3.0-or-later
import { describe, expect, it } from 'vitest';
import { forecastCsv } from './forecastCsv';

describe('forecastCsv', () => {
  it('writes separate CRLF-delimited rows with empty cells for missing measurements', () => {
    expect(forecastCsv([
      { timestamp: '2026-11-23T10:00:00.000Z', kind: 'observed', value: 12, q10: null, q50: null, q90: null },
      { timestamp: '2026-11-23T10:01:00.000Z', kind: 'forecast', value: null, q10: 10, q50: 13, q90: 15 },
    ])).toBe('timestamp,kind,value,q10,q50,q90\r\n'
      + '2026-11-23T10:00:00.000Z,observed,12,,,\r\n'
      + '2026-11-23T10:01:00.000Z,forecast,,10,13,15\r\n');
  });

  it('escapes commas, quotes and embedded newlines instead of corrupting a CSV column', () => {
    const csv = forecastCsv([
      { timestamp: 'x', kind: 'label, "quoted"\nnext', value: null, q10: null, q50: null, q90: null },
    ]);
    expect(csv).toContain('"label, ""quoted""\nnext"');
    expect(csv).toMatch(/\r\n$/);
  });
});
