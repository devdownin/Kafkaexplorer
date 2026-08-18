// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors

import { describe, it, expect } from 'vitest';
import type { DataModelEntity, DataModelRelation, DataModelResponse } from '../api/types';
import {
  MAX_TOPICS, MAX_COLUMNS_SHOWN, NODE_W, HEADER_H, ROW_H, FOOTER_H,
  filterTopics, toggleTopic, selectAll, topicsFromQuery, buildQuery,
  displayedColumns, entityHeight, computeLayout, edgeAnchors, describeModel,
} from './dataModel';

function entity(id: string, columnCount: number, overrides: Partial<DataModelEntity> = {}): DataModelEntity {
  return {
    id,
    topic: id.replace(/_/g, '.'),
    format: 'JSON',
    columns: Array.from({ length: columnCount }, (_, i) => ({
      name: `col_${i}`, type: 'STRING', primaryKey: false, references: null,
    })),
    primaryKey: null,
    messageCount: null,
    ...overrides,
  };
}

function relation(from: string, to: string, fromColumn = 'x_id'): DataModelRelation {
  return { from, to, fromColumn, toColumn: null, confidence: 'HIGH', reason: 'test' };
}

describe('topic selection', () => {
  it('filters case-insensitively', () => {
    expect(filterTopics(['demo.Orders', 'demo.payments'], 'ORD')).toEqual(['demo.Orders']);
    expect(filterTopics(['a', 'b'], '  ')).toEqual(['a', 'b']);
  });

  it('toggles in and out', () => {
    expect(toggleTopic([], 't')).toEqual(['t']);
    expect(toggleTopic(['t'], 't')).toEqual([]);
  });

  it('select-all respects the server cap and skips internal topics', () => {
    const visible = ['internal.audit.history', ...Array.from({ length: 40 }, (_, i) => `t${i}`)];
    const selected = selectAll([], visible);
    expect(selected).toHaveLength(MAX_TOPICS);
    expect(selected).not.toContain('internal.audit.history');
  });

  it('select-all keeps what was already selected', () => {
    expect(selectAll(['z'], ['a'])).toEqual(['z', 'a']);
  });
});

describe('URL round-trip', () => {
  it('round-trips a selection', () => {
    const query = buildQuery(['demo.orders', 'demo.payments']);
    expect(topicsFromQuery(query)).toEqual(['demo.orders', 'demo.payments']);
  });

  it('deduplicates and drops blanks on read', () => {
    expect(topicsFromQuery('?topics=a,,a,%20,b')).toEqual(['a', 'b']);
  });

  it('an empty selection produces no query string', () => {
    expect(buildQuery([])).toBe('');
    expect(topicsFromQuery('')).toEqual([]);
  });
});

describe('displayedColumns / entityHeight', () => {
  it('lists everything under the cap', () => {
    const { columns, hidden } = displayedColumns(entity('t', 5));
    expect(columns).toHaveLength(5);
    expect(hidden).toBe(0);
  });

  it('caps and counts the rest — a node is not a page', () => {
    const { columns, hidden } = displayedColumns(entity('t', MAX_COLUMNS_SHOWN + 7));
    expect(columns).toHaveLength(MAX_COLUMNS_SHOWN);
    expect(hidden).toBe(7);
  });

  it('key columns are shown first — they carry the edges', () => {
    const e = entity('t', MAX_COLUMNS_SHOWN + 2);
    e.columns[MAX_COLUMNS_SHOWN + 1] = {
      name: 'order_id', type: 'STRING', primaryKey: true, references: null,
    };
    const { columns } = displayedColumns(e);
    expect(columns[0].name).toBe('order_id');
  });

  it('height follows the displayed rows, plus one for the overflow line', () => {
    expect(entityHeight(entity('t', 3))).toBe(HEADER_H + 3 * ROW_H + FOOTER_H);
    expect(entityHeight(entity('t', MAX_COLUMNS_SHOWN + 7)))
      .toBe(HEADER_H + (MAX_COLUMNS_SHOWN + 1) * ROW_H + FOOTER_H);
  });
});

describe('computeLayout', () => {
  it('an empty model has no positions', () => {
    expect(computeLayout([], [])).toEqual({});
  });

  it('a referenced entity lands in a later column than its referencer', () => {
    const a = entity('facts', 2);
    const b = entity('dims', 2);
    const positions = computeLayout([a, b], [relation('facts', 'dims')]);
    expect(positions.dims.x).toBeGreaterThan(positions.facts.x);
  });

  it('entities without relations are gridded below the graph, not stacked in one column', () => {
    const connected = [entity('a', 2), entity('b', 2)];
    const isolatedEntities = Array.from({ length: 6 }, (_, i) => entity(`iso${i}`, 2));
    const positions = computeLayout([...connected, ...isolatedEntities], [relation('a', 'b')]);
    const graphBottom = Math.max(positions.a.y, positions.b.y);
    expect(positions.iso0.y).toBeGreaterThan(graphBottom);
    // Grid wraps: not all isolated nodes share one x.
    const xs = new Set(isolatedEntities.map(e => positions[e.id].x));
    expect(xs.size).toBeGreaterThan(1);
  });

  it('stacked nodes never overlap whatever their heights', () => {
    const tall = entity('tall', 20);
    const small = entity('small', 2);
    const sink = entity('sink', 2);
    const positions = computeLayout(
      [tall, small, sink],
      [relation('tall', 'sink'), relation('small', 'sink')]);
    // tall and small share the first column; small starts below tall's real height.
    const first = Math.min(positions.tall.y, positions.small.y);
    const second = Math.max(positions.tall.y, positions.small.y);
    const firstEntity = positions.tall.y === first ? tall : small;
    expect(second).toBeGreaterThanOrEqual(first + entityHeight(firstEntity));
  });

  it('a relation citing an unknown entity is ignored rather than crashing the layout', () => {
    const positions = computeLayout([entity('a', 1)], [relation('a', 'ghost')]);
    expect(positions.a).toBeDefined();
    expect(positions.ghost).toBeUndefined();
  });

  it('a cycle still places every node', () => {
    const positions = computeLayout(
      [entity('a', 1), entity('b', 1)],
      [relation('a', 'b'), relation('b', 'a')]);
    expect(positions.a).toBeDefined();
    expect(positions.b).toBeDefined();
  });
});

describe('edgeAnchors', () => {
  it('connects right side to left side when the target is to the right', () => {
    const { x1, x2 } = edgeAnchors(
      { x: 0, y: 0, height: 100 }, { x: 400, y: 0, height: 100 });
    expect(x1).toBe(NODE_W);
    expect(x2).toBe(400);
  });

  it('connects left side to right side when the target is to the left', () => {
    const { x1, x2 } = edgeAnchors(
      { x: 400, y: 0, height: 100 }, { x: 0, y: 0, height: 100 });
    expect(x1).toBe(400);
    expect(x2).toBe(NODE_W);
  });

  it('anchors at each node\'s vertical centre, whatever its height', () => {
    const { y1, y2 } = edgeAnchors(
      { x: 0, y: 0, height: 200 }, { x: 400, y: 100, height: 60 });
    expect(y1).toBe(100);
    expect(y2).toBe(130);
  });
});

describe('describeModel', () => {
  const base: DataModelResponse = {
    entities: [], relations: [], warnings: [],
    topicsRequested: 3, topicsAnalyzed: 3, truncated: false,
  };

  it('states a complete model plainly', () => {
    expect(describeModel({ ...base, relations: [relation('a', 'b')] }))
      .toBe('3 entities · 1 relation deduced');
  });

  it('a partial model says what it is partial over', () => {
    expect(describeModel({ ...base, topicsAnalyzed: 2 }))
      .toBe('2 entities (of 3 topics selected) · 0 relations deduced');
  });

  it('singular forms read correctly', () => {
    expect(describeModel({ ...base, topicsRequested: 1, topicsAnalyzed: 1 }))
      .toBe('1 entity · 0 relations deduced');
  });
});
