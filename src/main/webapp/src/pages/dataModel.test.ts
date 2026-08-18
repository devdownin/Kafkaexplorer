// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors

import { describe, it, expect } from 'vitest';
import type { DataModelEntity, DataModelRelation, DataModelResponse } from '../api/types';
import {
  MAX_TOPICS, MAX_COLUMNS_SHOWN, NODE_W, HEADER_H, ROW_H, FOOTER_H, DOMAIN_PALETTE,
  filterTopics, toggleTopic, selectAll, topicsFromQuery, buildQuery,
  displayedColumns, entityHeight, computeLayout, describeModel, splitByConnectivity,
  columnRowY, anchorSpread, computeEdgeGeometry, crowFootPath, oneBarPath,
  graphBounds, fitTransform, topicDomains, domainColors,
  formatCount, describeRelation, matchingColumns, describeColumnMatches,
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

describe('columnRowY', () => {
  it('gives the centre of the displayed row', () => {
    const e = entity('t', 3);
    expect(columnRowY(e, 'col_1')).toBe(HEADER_H + 1 * ROW_H + ROW_H / 2);
  });

  it('is null for a column folded into the overflow, and for no column at all', () => {
    const e = entity('t', MAX_COLUMNS_SHOWN + 3);
    expect(columnRowY(e, `col_${MAX_COLUMNS_SHOWN + 2}`)).toBeNull();
    expect(columnRowY(e, null)).toBeNull();
    expect(columnRowY(e, 'nope')).toBeNull();
  });
});

describe('computeEdgeGeometry', () => {
  const positions = { facts: { x: 0, y: 0 }, dims: { x: 400, y: 50 } };

  function pair(): DataModelEntity[] {
    const facts = entity('facts', 4);
    facts.columns[2] = { name: 'dim_id', type: 'STRING', primaryKey: false, references: 'dims' };
    const dims = entity('dims', 3, { primaryKey: 'dim_id' });
    dims.columns[1] = { name: 'dim_id', type: 'STRING', primaryKey: true, references: null };
    return [facts, dims];
  }

  it('anchors each end on its column row, not the node centre', () => {
    const [geometry] = computeEdgeGeometry(
      [{ ...relation('facts', 'dims', 'dim_id'), toColumn: 'dim_id' }], pair(), positions);
    // Key columns are displayed first, so dim_id is row 0 in both nodes.
    expect(geometry!.y1).toBe(0 + HEADER_H + ROW_H / 2);
    expect(geometry!.y2).toBe(50 + HEADER_H + ROW_H / 2);
    expect(geometry!.x1).toBe(NODE_W);
    expect(geometry!.x2).toBe(400);
    expect(geometry!.d1).toBe(1);
    expect(geometry!.d2).toBe(-1);
  });

  it('a null toColumn falls back to the target key row, then the centre', () => {
    const [withPk] = computeEdgeGeometry(
      [relation('facts', 'dims', 'dim_id')], pair(), positions);
    expect(withPk!.y2).toBe(50 + HEADER_H + ROW_H / 2);

    const noKey = [entity('facts', 1), entity('dims', 2)];
    const [centred] = computeEdgeGeometry(
      [relation('facts', 'dims', 'col_0')], noKey, positions);
    expect(centred!.y2).toBe(50 + entityHeight(noKey[1]) / 2);
  });

  it('flips sides when the target is to the left', () => {
    const [geometry] = computeEdgeGeometry(
      [relation('dims', 'facts', 'col_0')], pair(), positions);
    expect(geometry!.x1).toBe(400);
    expect(geometry!.d1).toBe(-1);
    expect(geometry!.x2).toBe(NODE_W);
    expect(geometry!.d2).toBe(1);
  });

  it('spreads edges sharing the same anchor instead of stacking them', () => {
    const entities = [entity('a', 2), entity('b', 2), entity('sink', 2, { primaryKey: 'col_0' })];
    const threePos = { a: { x: 0, y: 0 }, b: { x: 0, y: 300 }, sink: { x: 400, y: 100 } };
    const [first, second] = computeEdgeGeometry(
      [relation('a', 'sink', 'col_0'), relation('b', 'sink', 'col_0')], entities, threePos);
    expect(first!.y2).not.toBe(second!.y2);
    // Centred around the shared row: the mean is the unspread anchor.
    const row = 100 + HEADER_H + ROW_H / 2;
    expect((first!.y2 + second!.y2) / 2).toBe(row);
  });

  it('a relation citing an unknown entity yields null, not a crash', () => {
    const [geometry] = computeEdgeGeometry(
      [relation('facts', 'ghost', 'x_id')], pair(), positions);
    expect(geometry).toBeNull();
  });
});

describe('anchorSpread', () => {
  it('is zero for a lone edge and symmetric for several', () => {
    expect(anchorSpread(0, 1)).toBe(0);
    expect(anchorSpread(0, 2)).toBe(-5);
    expect(anchorSpread(1, 2)).toBe(5);
    expect(anchorSpread(1, 3)).toBe(0);
  });
});

describe('cardinality glyphs', () => {
  it('the crow foot fans from 10px out back to the node edge', () => {
    expect(crowFootPath(100, 50, 1)).toBe('M110,50 L100,45 M110,50 L100,50 M110,50 L100,55');
    expect(crowFootPath(100, 50, -1)).toContain('M90,50');
  });

  it('the one-bar sits 7px out, perpendicular', () => {
    expect(oneBarPath(100, 50, -1)).toBe('M93,45 L93,55');
  });
});

describe('graphBounds / fitTransform', () => {
  it('bounds cover positions plus real node sizes', () => {
    const a = entity('a', 2);
    const b = entity('b', 8);
    const bounds = graphBounds([a, b], { a: { x: 0, y: 0 }, b: { x: 300, y: 100 } });
    expect(bounds).toEqual({
      minX: 0, minY: 0, maxX: 300 + NODE_W, maxY: 100 + entityHeight(b),
    });
  });

  it('bounds are null with nothing placed', () => {
    expect(graphBounds([entity('a', 1)], {})).toBeNull();
  });

  it('fit centres the graph and never scales above 1', () => {
    const t = fitTransform({ minX: 0, minY: 0, maxX: 100, maxY: 100 }, 1000, 800);
    expect(t.scale).toBe(1);
    expect(t.x).toBe((1000 - 100) / 2);
    expect(t.y).toBe((800 - 100) / 2);
  });

  it('fit shrinks a graph larger than the viewport', () => {
    const t = fitTransform({ minX: 0, minY: 0, maxX: 4000, maxY: 100 }, 1000, 800, 40);
    expect(t.scale).toBeCloseTo((1000 - 80) / 4000);
  });
});

describe('topicDomains / domainColors', () => {
  it('drops the leading segments every topic shares', () => {
    const domains = topicDomains(['demo.orders.nested', 'demo.payments.captured', 'demo.orders']);
    expect(domains.get('demo.orders.nested')).toBe('orders');
    expect(domains.get('demo.payments.captured')).toBe('payments');
    expect(domains.get('demo.orders')).toBe('orders');
  });

  it('keeps the first segment when nothing is shared, and survives single-segment names', () => {
    const domains = topicDomains(['orders', 'billing.invoices']);
    expect(domains.get('orders')).toBe('orders');
    expect(domains.get('billing.invoices')).toBe('billing');
  });

  it('does not strip a shared segment when a topic has nothing left behind it', () => {
    // "demo" alone would end up with no segment if the head were dropped.
    const domains = topicDomains(['demo', 'demo.orders']);
    expect(domains.get('demo')).toBe('demo');
    expect(domains.get('demo.orders')).toBe('demo');
  });

  it('assigns stable tints to sorted domains', () => {
    const colors = domainColors(new Map([['t1', 'zeta'], ['t2', 'alpha']]));
    expect(colors.get('alpha')).toBe(DOMAIN_PALETTE[0]);
    expect(colors.get('zeta')).toBe(DOMAIN_PALETTE[1]);
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

describe('splitByConnectivity', () => {
  it('separates entities a relation touches from those none does', () => {
    const a = entity('a', 1);
    const b = entity('b', 1);
    const lonely = entity('lonely', 1);
    const { connected, isolated } = splitByConnectivity(
      [a, b, lonely], [relation('a', 'b')]);
    expect(connected.map(e => e.id)).toEqual(['a', 'b']);
    expect(isolated.map(e => e.id)).toEqual(['lonely']);
  });

  it('a self-relation and a relation to an unknown entity connect nothing', () => {
    const { connected, isolated } = splitByConnectivity(
      [entity('a', 1)], [relation('a', 'a'), relation('a', 'ghost')]);
    expect(connected).toHaveLength(0);
    expect(isolated.map(e => e.id)).toEqual(['a']);
  });
});

describe('formatCount', () => {
  it('compacts thousands and millions, keeps small numbers whole', () => {
    expect(formatCount(847)).toBe('847');
    expect(formatCount(12_340)).toBe('12.3K');
    expect(formatCount(1_250_000)).toBe('1.3M');
    expect(formatCount(0)).toBe('0');
  });

  it('says so rather than printing a number it does not have', () => {
    expect(formatCount(Number.NaN)).toBe('—');
    expect(formatCount(Number.POSITIVE_INFINITY)).toBe('—');
  });
});

describe('describeRelation', () => {
  it('states the link, the grade and the evidence in one sentence', () => {
    const described = describeRelation({
      from: 'demo_payments', to: 'demo_orders',
      fromColumn: 'order_id', toColumn: 'order_id',
      confidence: 'HIGH', reason: "'order_id' names topic 'demo.orders'.",
    });
    expect(described).toBe(
      "demo_payments.order_id → demo_orders.order_id · high confidence · "
      + "'order_id' names topic 'demo.orders'.");
  });

  it('omits the target column when the target has no matching key', () => {
    expect(describeRelation(relation('a', 'b', 'x_id'))).toContain('a.x_id → b ·');
  });
});

describe('matchingColumns / describeColumnMatches', () => {
  const orders = entity('orders', 2, {
    columns: [
      { name: 'order_id', type: 'STRING', primaryKey: true, references: null },
      { name: 'amount', type: 'DOUBLE', primaryKey: false, references: null },
    ],
  });
  const payments = entity('payments', 2, {
    columns: [
      { name: 'ORDER_ID', type: 'STRING', primaryKey: false, references: 'orders' },
      { name: 'customer.id', type: 'STRING', primaryKey: false, references: null },
    ],
  });

  it('matches as a case-insensitive substring of the whole path', () => {
    const matches = matchingColumns([orders, payments], 'order_id');
    expect([...matches.get('orders')!]).toEqual(['order_id']);
    expect([...matches.get('payments')!]).toEqual(['ORDER_ID']);
  });

  it('a nested path answers to either half of it', () => {
    expect(matchingColumns([payments], 'customer').get('payments')).toBeDefined();
    expect(matchingColumns([payments], 'id').get('payments')!.size).toBe(2);
  });

  it('an empty term designates nothing — highlighting everything says nothing', () => {
    expect(matchingColumns([orders, payments], '   ').size).toBe(0);
    expect(describeColumnMatches(new Map(), '  ')).toBe('');
  });

  it('zero reads as a zero, not as an absent answer', () => {
    const matches = matchingColumns([orders], 'nope');
    expect(matches.size).toBe(0);
    expect(describeColumnMatches(matches, 'nope')).toBe('No column matches “nope”');
  });

  it('counts columns and entities, singular forms included', () => {
    expect(describeColumnMatches(matchingColumns([orders, payments], 'order_id'), 'order_id'))
      .toBe('2 columns in 2 entities');
    expect(describeColumnMatches(matchingColumns([orders], 'amount'), 'amount'))
      .toBe('1 column in 1 entity');
  });
});
