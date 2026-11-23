// SPDX-License-Identifier: AGPL-3.0-or-later
import { describe, expect, it } from 'vitest';
import { deriveStrataLinks, type StrataLineage } from './strataLineage';

const graph: StrataLineage = {
  nodes: [
    { id: 'topic_a', label: 'a', type: 'topic' },
    { id: 'topic_b', label: 'b', type: 'topic' },
    { id: 'topic_c', label: 'c', type: 'topic' },
    { id: 'table_a', label: 'ta', type: 'table' },
    { id: 'table_b', label: 'tb', type: 'table' },
    { id: 'table_c', label: 'tc', type: 'table' },
    { id: 'query_job', label: 'INSERT', type: 'query' },
  ],
  edges: [
    { from: 'topic_a', to: 'table_a', label: 'source' },
    { from: 'topic_b', to: 'table_b', label: 'source' },
    { from: 'topic_c', to: 'table_c', label: 'source' },
    { from: 'table_a', to: 'query_job', label: 'reads' },
    { from: 'query_job', to: 'table_b', label: 'writes' },
  ],
};
describe('deriveStrataLinks', () => {
  it('links topics only through a declared Flink INSERT read/write path', () => {
    expect(deriveStrataLinks(graph, ['a', 'b', 'c'])).toEqual([{ from: 'a', to: 'b', via: 'query_job' }]);
  });
  it('does not invent a relationship for tables sharing a cluster', () => {
    expect(deriveStrataLinks({ ...graph, edges: graph.edges.filter(e => e.label !== 'writes') }, ['a', 'b', 'c'])).toEqual([]);
  });
  it('respects bounded visible scope and does not render self-links', () => {
    expect(deriveStrataLinks(graph, ['a'])).toEqual([]);
    expect(deriveStrataLinks({ ...graph, edges: [...graph.edges, { from: 'query_job', to: 'table_a', label: 'writes' }] }, ['a', 'b']))
      .toEqual([{ from: 'a', to: 'b', via: 'query_job' }]);
  });
});
