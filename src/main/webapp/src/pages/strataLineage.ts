// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors

/** Topic-to-topic links from known Flink INSERT jobs, not observed message hops. */
export interface StrataLineageNode {
  id: string;
  label: string;
  type: string;
}
export interface StrataLineageEdge {
  from: string;
  to: string;
  label?: string;
}
export interface StrataLineage {
  nodes: StrataLineageNode[];
  edges: StrataLineageEdge[];
  warnings?: string[];
}
export interface StrataLink {
  from: string;
  to: string;
  via: string;
}

/**
 * Flink's graph connects Kafka topics to tables in the source direction,
 * even when the table is used as a sink. We must therefore only connect
 * topics through an explicit READS -> INSERT job -> WRITES path.
 * Proximity, matching names, and unordered graph reachability aren't evidence.
 */
export function deriveStrataLinks(lineage: StrataLineage, displayedTopics: readonly string[], limit = 80): StrataLink[] {
  const visible = new Set(displayedTopics);
  const nodes = new Map(lineage.nodes.map(n => [n.id, n]));
  const attachedTopics = new Map<string, Set<string>>();
  for (const edge of lineage.edges) {
    const from = nodes.get(edge.from);
    const to = nodes.get(edge.to);
    if (edge.label !== 'source' || from?.type !== 'topic' || !to || to.type !== 'table') continue;
    if (!visible.has(from.label)) continue;
    if (!attachedTopics.has(to.id)) attachedTopics.set(to.id, new Set());
    attachedTopics.get(to.id)!.add(from.label);
  }

  // Walk backwards through known view dependencies, never through a write edge.
  const inputsOf = (nodeId: string, visited: Set<string>): Set<string> => {
    if (visited.has(nodeId) || visited.size >= 100) return new Set();
    visited.add(nodeId);
    const found = new Set(attachedTopics.get(nodeId) ?? []);
    if (nodes.get(nodeId)?.type === 'view') {
      for (const e of lineage.edges) {
        if (e.to !== nodeId || e.label !== 'depends') continue;
        for (const source of inputsOf(e.from, visited)) found.add(source);
      }
    }
    return found;
  };

  const links: StrataLink[] = [];
  const seen = new Set<string>();
  for (const query of lineage.nodes.filter(n => n.type === 'query')) {
    const sources = lineage.edges.filter(e => e.to === query.id && e.label === 'reads');
    const sinks = lineage.edges.filter(e => e.from === query.id && e.label === 'writes');
    for (const source of sources) {
      const upstream = inputsOf(source.from, new Set());
      for (const sink of sinks) {
        const downstream = attachedTopics.get(sink.to) ?? new Set<string>();
        for (const from of upstream) for (const to of downstream) {
          if (from === to) continue;
          const id = JSON.stringify([from, to, query.id]);
          if (seen.has(id)) continue;
          seen.add(id);
          links.push({ from, to, via: query.id });
          if (links.length >= limit) return links;
        }
      }
    }
  }
  return links;
}
