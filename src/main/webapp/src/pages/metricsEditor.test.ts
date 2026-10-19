// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors
import { describe, expect, it } from 'vitest';
import { buildDdlTemplate, carryTemplateParams, retargetDdl, validateDdlSql } from './metricsEditor';

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

describe('retargetDdl', () => {
  const servers = 'kafka:9092';

  it('regenerates the template while the DDL is still the template, or empty', () => {
    expect(retargetDdl(buildDdlTemplate('orders', servers), 'orders', 'payments', servers))
      .toBe(buildDdlTemplate('payments', servers));
    expect(retargetDdl('', '', 'payments', servers)).toBe(buildDdlTemplate('payments', servers));
  });

  it('keeps a DDL edited by hand and retargets only the topic and the table', () => {
    // Typing one more character used to put the JSON template back over this one, with no undo.
    const edited = buildDdlTemplate('orders', servers).replace("'json'", "'avro'").replace('`ts`       TIMESTAMP', '`extra` INT,\n  `ts`       TIMESTAMP');
    const next = retargetDdl(edited, 'orders', 'orders.v2', servers);
    expect(next).toContain("'format'                       = 'avro'");
    expect(next).toContain('`extra` INT');
    expect(next).toContain("'topic'                        = 'orders.v2'");
    expect(next).toContain('CREATE TABLE IF NOT EXISTS orders_v2 (');
    expect(next).not.toContain("'orders'");
  });

  it('leaves a DDL whose topic was changed by hand alone, and an emptied field as it was', () => {
    const own = "CREATE TABLE t (id STRING) WITH ('connector' = 'kafka', 'topic' = 'something-else')";
    expect(retargetDdl(own, 'orders', 'payments', servers)).toBe(own);
    expect(retargetDdl(own, 'orders', '', servers)).toBe(own);
  });
});

describe('carryTemplateParams', () => {
  const countDelta = { requiredParams: ['leftTopic', 'rightTopic'] };
  const lag = { requiredParams: ['topic', 'group'] };
  const timeLag = { requiredParams: ['topic', 'group'] };

  it('drops what the new template does not require, so the card cannot label it with the old pair', () => {
    expect(carryTemplateParams({ leftTopic: 'a', rightTopic: 'b', window: 'TOTAL' }, countDelta, lag)).toEqual({});
  });

  it('carries what both templates require', () => {
    expect(carryTemplateParams({ topic: 'orders', group: 'billing', extra: 1 }, lag, timeLag))
      .toEqual({ topic: 'orders', group: 'billing' });
  });

  it('keeps everything when a descriptor is unknown rather than guessing', () => {
    expect(carryTemplateParams({ topic: 'orders' }, undefined, lag)).toEqual({ topic: 'orders' });
    expect(carryTemplateParams(null, lag, timeLag)).toEqual({});
  });
});
