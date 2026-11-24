// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors
import { describe, expect, it } from 'vitest';
import { prettySchema, schemaDiff } from './schemaDiff';

describe('schemaDiff', () => {
  it('normalizes Avro JSON before comparing and highlights changed fields', () => {
    const oldSchema = '{"type":"record","fields":[{"name":"id","type":"string"}]}';
    const newSchema = '{"type":"record","fields":[{"name":"id","type":"string"},{"name":"amount","type":"double"}]}';
    const diff = schemaDiff(oldSchema, newSchema);
    expect(diff.truncated).toBe(false);
    expect(diff.added).toBeGreaterThan(0);
    expect(diff.rows.some(row => row.kind === 'added' && row.after?.includes('"amount"'))).toBe(true);
    expect(schemaDiff(oldSchema, oldSchema).added).toBe(0);
    expect(schemaDiff(oldSchema, oldSchema).removed).toBe(0);
  });

  it('keeps Protobuf text intact and bounds large diffs', () => {
    expect(prettySchema('message Order { string id = 1; }')).toBe('message Order { string id = 1; }');
    const large = Array.from({ length: 500 }, (_, i) => `line ${i}`).join('\n');
    expect(schemaDiff(large, large).truncated).toBe(true);
    expect(schemaDiff(large, large).rows.length).toBe(350);
  });
});
