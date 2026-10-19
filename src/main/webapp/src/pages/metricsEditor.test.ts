// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors
import { describe, expect, it } from 'vitest';
import { validateDdlSql } from './metricsEditor';

const ddl = (topic: string) => `CREATE TABLE t (id STRING) WITH ('connector' = 'kafka', 'topic' = '${topic}')`;

describe('validateDdlSql', () => {
  it('refuses a destructive statement', () => {
    expect(validateDdlSql('DROP TABLE t').map(m => m.level)).toContain('error');
    expect(validateDdlSql(`${ddl('orders')}; DELETE FROM t`).some(m => m.level === 'error')).toBe(true);
  });

  it('reads the keywords outside the quoted values, not inside a topic name', () => {
    // The auto-generated template for this topic blocked Save and Preview on "delete".
    for (const topic of ['account-delete-requests', 'drop.events', 'truncate_log'])
      expect(validateDdlSql(ddl(topic)).filter(m => m.level === 'error')).toEqual([]);
  });

  it('still reads a backtick-quoted column outside its own name', () => {
    expect(validateDdlSql('CREATE TABLE t (`delete` STRING) WITH (\'connector\' = \'kafka\')')
      .filter(m => m.level === 'error')).toEqual([]);
  });
});
