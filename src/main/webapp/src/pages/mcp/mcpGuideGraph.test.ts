// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors

import { describe, expect, it } from 'vitest';
import { GRAPH_EDGES, GRAPH_ORDER, SCENARIOS } from './mcpGuideGraph';

describe('Graphe de référence Kex-anHarness', () => {
  it('fournit des étapes stables avec une source et un garde-fou pour chaque scénario', () => {
    const ids = [...GRAPH_ORDER];
    expect(new Set(ids).size).toBe(9);
    for (const scenario of Object.values(SCENARIOS)) {
      expect(scenario.nodes.map((node) => node.id)).toEqual(ids);
      expect(scenario.userQuestion).not.toBe('');
      expect(scenario.warning).not.toBe('');
      for (const node of scenario.nodes) {
        expect(node.description.length).toBeGreaterThan(15);
        expect(node.guardrail.length).toBeGreaterThan(15);
        expect(node.source).toMatch(/^https:\/\/github\.com\/devdownin\/(Kafkaexplorer|Kex-anHarness)\/blob\/main\//);
      }
    }
  });

  it('a un parcours orienté complet dont les retours restent explicitement distincts des appels', () => {
    expect(GRAPH_EDGES).toHaveLength(GRAPH_ORDER.length);
    expect(GRAPH_EDGES.map((edge) => edge[0])).toEqual(GRAPH_ORDER);
    expect(GRAPH_EDGES[GRAPH_EDGES.length - 1]).toEqual(['answer', 'request', 'return']);
    expect(GRAPH_EDGES.filter((edge) => edge[2] === 'call')).toHaveLength(6);
    expect(GRAPH_EDGES.filter((edge) => edge[2] === 'return')).toHaveLength(3);
  });
});
