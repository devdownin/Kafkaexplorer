import { describe, expect, it } from 'vitest';
import {
  buildLayout, clampScale, describeCoverage, fitTransform, formatLatency, formatRelativeTime,
  parseFlowResponse, validateSearchPath, zoomAt,
  type FlowStats,
} from './streamFlow';

describe('validateSearchPath', () => {
  it('accepts an empty path (raw key / payload search)', () => {
    expect(validateSearchPath('')).toBeUndefined();
    expect(validateSearchPath('   ')).toBeUndefined();
  });

  it('accepts a JSONPath and an XPath', () => {
    expect(validateSearchPath('$.orderId')).toBeUndefined();
    expect(validateSearchPath('$.items[*].id')).toBeUndefined();
    expect(validateSearchPath('/order/id')).toBeUndefined();
    expect(validateSearchPath('//id')).toBeUndefined();
  });

  it('rejects a bare field name', () => {
    expect(validateSearchPath('orderId')).toMatch(/JSONPath/);
  });

  it('rejects unbalanced brackets', () => {
    expect(validateSearchPath('$.items[0')).toBe('Unbalanced brackets.');
  });
});

describe('parseFlowResponse', () => {
  const payload = {
    nodes: [
      { id: 'orders', label: 'orders', type: 'topic', timestamp: '100', hits: '2' },
      { id: 'billing', label: 'billing', type: 'topic', timestamp: '200', hits: '1' },
    ],
    edges: [{ from: 'orders', to: 'billing', label: '+100 ms' }],
    hits: [{
      topic: 'orders', occurrences: 2, firstTimestamp: 100, lastTimestamp: 300,
      firstPartition: 0, firstOffset: 10, firstKey: 'K-1', preview: 'created',
      latencyFromPreviousMs: null,
    }],
    stats: {
      topicsInScope: 3, topicsScanned: 3, topicsSkipped: 0, topicsFailed: 0,
      messagesScanned: 300, matches: 3, durationMs: 1200, truncated: false,
      stopReason: 'COMPLETE', maxMessagesPerTopic: 100, timeLimitMinutes: null,
    },
    warnings: ['heads up'],
  };

  it('reads nodes, edges, hits, stats and warnings', () => {
    const flow = parseFlowResponse(payload);
    expect(flow.nodes.map(n => n.id)).toEqual(['orders', 'billing']);
    expect(flow.nodes[0].timestamp).toBe(100);
    expect(flow.nodes[0].hits).toBe(2);
    expect(flow.edges).toEqual([{ source: 'orders', target: 'billing', label: '+100 ms' }]);
    expect(flow.hits).toHaveLength(1);
    expect(flow.stats?.messagesScanned).toBe(300);
    expect(flow.warnings).toEqual(['heads up']);
  });

  it('accepts the source/target spelling as well as from/to', () => {
    const flow = parseFlowResponse({
      nodes: [{ id: 'a', label: 'a' }, { id: 'b', label: 'b' }],
      edges: [{ source: 'a', target: 'b' }],
    });
    expect(flow.edges).toEqual([{ source: 'a', target: 'b', label: undefined }]);
  });

  it('drops edges pointing at an unknown node instead of breaking the layout', () => {
    const flow = parseFlowResponse({
      nodes: [{ id: 'a', label: 'a' }],
      edges: [{ from: 'a', to: 'ghost' }],
    });
    expect(flow.edges).toEqual([]);
  });

  it('survives an empty or malformed payload', () => {
    expect(parseFlowResponse(undefined)).toEqual({
      nodes: [], edges: [], hits: [], stats: null, warnings: [],
    });
    expect(parseFlowResponse({ nodes: [{ label: '' }] }).nodes).toEqual([]);
  });
});

describe('buildLayout', () => {
  it('lays a chain out in one column per hop', () => {
    const nodes = [
      { id: 'a', label: 'a' }, { id: 'b', label: 'b' }, { id: 'c', label: 'c' },
    ];
    const edges = [
      { source: 'a', target: 'b' }, { source: 'b', target: 'c' },
    ];
    const layout = buildLayout(nodes, edges);
    expect(layout.positions.a.x).toBe(0);
    expect(layout.positions.b.x).toBeGreaterThan(layout.positions.a.x);
    expect(layout.positions.c.x).toBeGreaterThan(layout.positions.b.x);
    // Une chaîne tient sur une seule ligne.
    expect(layout.positions.a.y).toBe(layout.positions.c.y);
    expect(layout.height).toBe(layout.nodeH);
  });

  it('stacks unconnected nodes in a single column instead of one each', () => {
    const nodes = [
      { id: 'a', label: 'a' }, { id: 'b', label: 'b' }, { id: 'c', label: 'c' },
    ];
    const layout = buildLayout(nodes, []);
    const columns = new Set(Object.values(layout.positions).map(p => p.x));
    expect(columns.size).toBe(1);
  });

  it('places a detached cycle in a trailing column rather than dropping it', () => {
    const nodes = [
      { id: 'a', label: 'a' }, { id: 'b', label: 'b' }, { id: 'x', label: 'x' }, { id: 'y', label: 'y' },
    ];
    // a → b est atteignable ; x ⇄ y forme un cycle sans racine.
    const edges = [
      { source: 'a', target: 'b' },
      { source: 'x', target: 'y' }, { source: 'y', target: 'x' },
    ];
    const layout = buildLayout(nodes, edges);
    expect(Object.keys(layout.positions).sort()).toEqual(['a', 'b', 'x', 'y']);
    expect(layout.positions.x.x).toBe(layout.positions.y.x);
  });

  it('returns an empty layout for an empty graph', () => {
    expect(buildLayout([], [])).toEqual(
      expect.objectContaining({ positions: {}, width: 0, height: 0 }),
    );
  });

  it('does not loop on a cyclic graph', () => {
    const nodes = [{ id: 'a', label: 'a' }, { id: 'b', label: 'b' }];
    const edges = [{ source: 'a', target: 'b' }, { source: 'b', target: 'a' }];
    const layout = buildLayout(nodes, edges);
    expect(Object.keys(layout.positions).sort()).toEqual(['a', 'b']);
  });
});

describe('fitTransform', () => {
  it('centres the graph and never enlarges it past 1:1', () => {
    const layout = buildLayout(
      [{ id: 'a', label: 'a' }, { id: 'b', label: 'b' }],
      [{ source: 'a', target: 'b' }],
    );
    const transform = fitTransform(layout, { width: 4000, height: 3000 });
    expect(transform.scale).toBe(1);
    expect(transform.x).toBeCloseTo((4000 - layout.width) / 2);
  });

  it('shrinks a wide graph to fit', () => {
    const nodes = Array.from({ length: 12 }, (_, i) => ({ id: `t${i}`, label: `t${i}` }));
    const edges = nodes.slice(1).map((n, i) => ({ source: nodes[i].id, target: n.id }));
    const layout = buildLayout(nodes, edges);
    const transform = fitTransform(layout, { width: 800, height: 600 });
    expect(transform.scale).toBeLessThan(1);
    expect(layout.width * transform.scale).toBeLessThanOrEqual(800);
  });

  it('falls back to a default when there is nothing to fit', () => {
    expect(fitTransform(buildLayout([], []), { width: 800, height: 600 }))
      .toEqual({ x: 48, y: 48, scale: 1 });
  });
});

describe('zoomAt', () => {
  it('keeps the point under the cursor fixed', () => {
    const before = { x: 0, y: 0, scale: 1 };
    const after = zoomAt(before, 2, 100, 50);
    // Coordonnée graphe du point survolé, avant et après.
    expect((100 - before.x) / before.scale).toBeCloseTo((100 - after.x) / after.scale);
    expect((50 - before.y) / before.scale).toBeCloseTo((50 - after.y) / after.scale);
  });

  it('clamps the scale', () => {
    expect(zoomAt({ x: 0, y: 0, scale: 3.9 }, 4, 0, 0).scale).toBe(4);
    expect(zoomAt({ x: 0, y: 0, scale: 0.2 }, 0.1, 0, 0).scale).toBe(0.15);
    expect(clampScale(1000)).toBe(4);
  });
});

describe('formatting', () => {
  it('renders relative times', () => {
    const now = 1_000_000_000;
    expect(formatRelativeTime(undefined, now)).toBe('');
    expect(formatRelativeTime(0, now)).toBe('');
    expect(formatRelativeTime(now - 30_000, now)).toBe('< 1 min ago');
    expect(formatRelativeTime(now - 300_000, now)).toBe('5 min ago');
    expect(formatRelativeTime(now - 7_200_000, now)).toBe('2h ago');
  });

  it('renders hop latencies, including the first hop', () => {
    expect(formatLatency(null)).toBe('—');
    expect(formatLatency(120)).toBe('+120 ms');
    expect(formatLatency(1500)).toBe('+1.5 s');
    expect(formatLatency(120_000)).toBe('+2 min');
  });
});

describe('describeCoverage', () => {
  const base: FlowStats = {
    topicsInScope: 12, topicsScanned: 10, topicsSkipped: 2, topicsFailed: 0,
    messagesScanned: 1000, matches: 3, durationMs: 2500, truncated: false,
    stopReason: 'TIME_BUDGET', maxMessagesPerTopic: 100, timeLimitMinutes: null,
  };

  it('states what was read and what was not', () => {
    const text = describeCoverage(base);
    expect(text).toContain('10/12 topics scanned');
    expect(text).toContain('1,000 messages read');
    expect(text).toContain('up to 100 most recent per topic');
    expect(text).toContain('2 skipped (time budget)');
  });

  it('names the time window when one was applied', () => {
    expect(describeCoverage({ ...base, timeLimitMinutes: 15 })).toContain('last 15 min window');
  });

  it('is empty without stats', () => {
    expect(describeCoverage(null)).toBe('');
  });
});
