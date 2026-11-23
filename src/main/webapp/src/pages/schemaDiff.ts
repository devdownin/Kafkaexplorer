// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors

/** Normalize JSON schemas for a useful diff; preserve Protobuf and other text as written. */
export function prettySchema(source: string): string {
  try { return JSON.stringify(JSON.parse(source), null, 2); }
  catch { return source; }
}

export type SchemaDiffRow = {
  before: string | null;
  after: string | null;
  kind: 'same' | 'added' | 'removed';
};

/**
 * A line-wise LCS diff, bounded to prevent O(n²) work on very large schemas.
 * Each row occupies both comparison columns so additions/deletions remain aligned.
 */
export function schemaDiff(before: string, after: string) {
  const limit = 350;
  const beforeAll = prettySchema(before).split('\n');
  const afterAll = prettySchema(after).split('\n');
  const oldLines = beforeAll.slice(0, limit);
  const newLines = afterAll.slice(0, limit);
  const dp = Array.from({ length: oldLines.length + 1 }, () => new Uint16Array(newLines.length + 1));
  for (let i = oldLines.length - 1; i >= 0; i--) {
    for (let j = newLines.length - 1; j >= 0; j--) {
      dp[i][j] = oldLines[i] === newLines[j]
        ? dp[i + 1][j + 1] + 1
        : Math.max(dp[i + 1][j], dp[i][j + 1]);
    }
  }
  const rows: SchemaDiffRow[] = [];
  let i = 0, j = 0, added = 0, removed = 0;
  while (i < oldLines.length || j < newLines.length) {
    if (i < oldLines.length && j < newLines.length && oldLines[i] === newLines[j]) {
      rows.push({ before: oldLines[i++], after: newLines[j++], kind: 'same' });
    } else if (j < newLines.length && (i === oldLines.length || dp[i][j + 1] > dp[i + 1][j])) {
      rows.push({ before: null, after: newLines[j++], kind: 'added' });
      added++;
    } else {
      rows.push({ before: oldLines[i++], after: null, kind: 'removed' });
      removed++;
    }
  }
  return {
    rows,
    added,
    removed,
    truncated: beforeAll.length > limit || afterAll.length > limit,
  };
}
