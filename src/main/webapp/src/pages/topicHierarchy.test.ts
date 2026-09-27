// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors

import { describe, expect, it } from 'vitest';
import { buildTopicHierarchy, topicSeparator } from './topicHierarchy';

describe('topic hierarchy', () => {
  it('accepts a single separator and rejects ambiguous or empty levels', () => {
    expect(topicSeparator('orders.eu.created')).toBe('.');
    expect(topicSeparator('orders-eu-created')).toBe('-');
    expect(topicSeparator('orders_eu_created')).toBe('_');
    for (const name of ['orders', 'orders.eu-created', '.orders', 'orders..created', 'orders_']) {
      expect(topicSeparator(name)).toBeNull();
    }
  });

  it('groups shared prefixes while retaining a topic that is also a parent', () => {
    const tree = buildTopicHierarchy(['sales.eu', 'sales.eu.orders', 'sales.us', 'orders-eu', 'sales..bad'], '.');
    expect(tree.map(branch => [branch.label, branch.count])).toEqual([['sales', 3]]);
    expect(tree[0].children.map(branch => [branch.path, branch.topic, branch.count])).toEqual([
      ['sales.eu', 'sales.eu', 2], ['sales.us', 'sales.us', 1],
    ]);
    expect(tree[0].children[0].children[0].topic).toBe('sales.eu.orders');
  });
});
