// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors

/** One measured or predicted point, as used by both the CSV and JSON export. */
export interface ForecastCsvRow {
  timestamp: string;
  kind: string;
  value: number | null;
  q10: number | null;
  q50: number | null;
  q90: number | null;
}

const COLUMNS = ['timestamp', 'kind', 'value', 'q10', 'q50', 'q90'] as const;

function cell(value: string | number | null): string {
  if (value === null) return '';
  const text = String(value);
  return /[",\r\n]/.test(text) ? `"${text.replace(/"/g, '""')}"` : text;
}

/** RFC 4180-compatible rows; do not join on a literal backslash-n. */
export function forecastCsv(rows: readonly ForecastCsvRow[]): string {
  return [COLUMNS.join(','), ...rows.map(row => COLUMNS.map(key => cell(row[key])).join(','))].join('\r\n') + '\r\n';
}
