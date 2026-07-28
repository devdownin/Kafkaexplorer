import { beforeEach, describe, expect, it } from 'vitest';
import {
  analyzeChain, buildContinuation, buildLayout, buildTopicSearchQuery, buildTraceLinkForKey,
  buildTraceQuery, centerOn, clampScale, compareFlows, describeChainInsight, describeComparison,
  describeContinuation, describeCoverage, describeProgress, describeSearchScope, expandTopicPatterns,
  filterHits, fitTransform, formatDwell, isNodeVisible, parseSseBuffer, parseTopicList,
  progressRatio, slowestDivergence, sortHits, clampEvidencePct, readEvidencePct,
  writeEvidencePct, EVIDENCE_KEY, MIN_EVIDENCE_PCT, MAX_EVIDENCE_PCT, DEFAULT_EVIDENCE_PCT,
  formatLatency, formatRelativeTime, hitsToRows, HISTORY_KEY, HIT_EXPORT_COLUMNS, PANEL_KEY,
  parseFlowResponse, parseTraceParams, pushTraceHistory, readPanelOpen, readTraceHistory,
  sameCriterion, searchScopeOf, suggestWidenings, traceToJson, validateSearchPath, writePanelOpen,
  zoomAt,
  type FlowHit, type FlowStats, type TraceParams,
} from './streamFlow';
import { toCsv } from './resultExport';

describe('validateSearchPath', () => {
  it('accepts an empty path (raw key / payload search)', () => {
    expect(validateSearchPath('')).toBeUndefined();
    expect(validateSearchPath('   ')).toBeUndefined();
  });

  it('accepts a JSONPath, an XPath and a bare dot path', () => {
    expect(validateSearchPath('$.orderId')).toBeUndefined();
    expect(validateSearchPath('$.items[*].id')).toBeUndefined();
    expect(validateSearchPath('/order/id')).toBeUndefined();
    expect(validateSearchPath('//id')).toBeUndefined();
    // La notation que produit la liste de champs du Topic Explorer.
    expect(validateSearchPath('orderId')).toBeUndefined();
    expect(validateSearchPath('order.items[].sku')).toBeUndefined();
  });

  it('accepts a header search and rejects one without a name', () => {
    expect(validateSearchPath('header:correlation-id')).toBeUndefined();
    expect(validateSearchPath('header:  ')).toMatch(/header name/i);
  });

  it('rejects unbalanced brackets', () => {
    expect(validateSearchPath('$.items[0')).toBe('Unbalanced brackets.');
  });

  /** Recursive descent only means something to the JSONPath engine, which needs its `$`. */
  it('rejects recursive descent written as a bare path', () => {
    expect(validateSearchPath('order..id')).toMatch(/JSONPath/);
    expect(validateSearchPath('$..id')).toBeUndefined();
    expect(validateSearchPath('$.items[?(@.qty>1)].sku')).toBeUndefined();
  });
});

describe('searchScopeOf', () => {
  it('maps a path to what the backend will do with it', () => {
    expect(searchScopeOf('')).toBe('ANY');
    expect(searchScopeOf('order.id')).toBe('FIELD');
    expect(searchScopeOf('$.order.id')).toBe('FIELD');
    expect(searchScopeOf('/order/id')).toBe('XPATH');
    expect(searchScopeOf('$..id')).toBe('JSONPATH');
    expect(searchScopeOf('Header:Correlation-Id')).toBe('HEADER');
  });

  it('says whether headers are in scope for a raw search', () => {
    expect(describeSearchScope('', true)).toMatch(/header/i);
    expect(describeSearchScope('', false)).not.toMatch(/header/i);
    expect(describeSearchScope('header:x', true)).toContain('"x"');
  });

  /** L'option « clé exacte » l'emporte : le chemin n'est pas utilisé. */
  it('describes an exact key search whatever the path field holds', () => {
    expect(describeSearchScope('$.orderId', true, true)).toMatch(/record key/i);
    expect(describeSearchScope('$.orderId', true, true)).toMatch(/partition/i);
  });
});

describe('parseSseBuffer', () => {
  it('reads complete events and keeps the unfinished tail', () => {
    const { events, rest } = parseSseBuffer(
      'event: progress\ndata: {"topicsCompleted":1}\n\nevent: flow\ndata: {"nodes":[]}\n\nevent: res');

    expect(events).toEqual([
      { event: 'progress', data: '{"topicsCompleted":1}' },
      { event: 'flow', data: '{"nodes":[]}' },
    ]);
    expect(rest).toBe('event: res');
  });

  /** Un événement coupé en deux lectures doit se recoller, pas se perdre. */
  it('reassembles an event split across two chunks', () => {
    const first = parseSseBuffer('event: progress\ndata: {"topics');
    expect(first.events).toEqual([]);

    const second = parseSseBuffer(`${first.rest}Completed":2}\n\n`);
    expect(second.events).toEqual([{ event: 'progress', data: '{"topicsCompleted":2}' }]);
    expect(second.rest).toBe('');
  });

  it('joins a multi-line data field and ignores comments', () => {
    const { events } = parseSseBuffer(': keep-alive\n\nevent: flow\ndata: line one\ndata: line two\n\n');

    expect(events).toEqual([{ event: 'flow', data: 'line one\nline two' }]);
  });

  it('handles CRLF line endings and a missing event name', () => {
    const { events } = parseSseBuffer('data: bare\r\n\r\n');

    expect(events).toEqual([{ event: 'message', data: 'bare' }]);
  });

  it('returns nothing for an empty buffer', () => {
    expect(parseSseBuffer('')).toEqual({ events: [], rest: '' });
  });
});

describe('progress', () => {
  const progress = {
    topicsCompleted: 42, topicsInScope: 250, messagesScanned: 12_800,
    matches: 3, elapsedMs: 4_000, lastTopic: 'orders',
  };

  it('states what has been covered so far', () => {
    const text = describeProgress(progress);
    expect(text).toContain('42/250 topics');
    expect(text).toContain('12,800 messages');
    expect(text).toContain('3 matches');
    expect(text).toContain('orders');
    expect(describeProgress(null)).toBe('');
  });

  it('gives a ratio the bar can trust', () => {
    expect(progressRatio(progress)).toBeCloseTo(42 / 250);
    expect(progressRatio(null)).toBe(0);
    expect(progressRatio({ ...progress, topicsInScope: 0 })).toBe(0);
    // Un serveur qui compte au-delà de la portée ne fait pas déborder la barre.
    expect(progressRatio({ ...progress, topicsCompleted: 300 })).toBe(1);
  });

  it('says "1 match" without an s', () => {
    expect(describeProgress({ ...progress, matches: 1 })).toContain('1 match ');
  });
});

describe('trace params', () => {
  const params: TraceParams = {
    messageKey: 'ORD 42',
    searchPath: '$.orderId',
    topics: ['orders', 'billing'],
    windowMode: 'window',
    timeLimitMinutes: 30,
    maxMessages: 500,
    useRegex: true,
    exactKey: false,
    caseSensitive: true,
    searchHeaders: false,
  };

  it('round-trips through the query string', () => {
    expect(parseTraceParams(buildTraceQuery(params))).toEqual(params);
  });

  it('only serialises what differs from the defaults', () => {
    const query = buildTraceQuery({
      ...params, searchPath: '', topics: [], windowMode: 'recent',
      maxMessages: 100, useRegex: false, exactKey: false, caseSensitive: false, searchHeaders: true,
    });
    expect(query).toBe('?key=ORD+42');
  });

  it('carries the exact-key option through the link', () => {
    expect(buildTraceQuery({ ...params, exactKey: true })).toContain('exact=1');
    expect(parseTraceParams('?key=K-1&exact=1').exactKey).toBe(true);
    expect(parseTraceParams('?key=K-1').exactKey).toBe(false);
  });

  it('falls back to the defaults on a malformed query', () => {
    const parsed = parseTraceParams('?max=nonsense&window=abc');
    expect(parsed.maxMessages).toBe(100);
    expect(parsed.windowMode).toBe('recent');
    expect(parsed.searchHeaders).toBe(true);
  });

  it('clamps out-of-range numbers instead of trusting the URL', () => {
    expect(parseTraceParams('?max=99999').maxMessages).toBe(1000);
    expect(parseTraceParams('?window=99999').timeLimitMinutes).toBe(1440);
  });

  it('is empty for an empty query', () => {
    expect(buildTraceQuery(parseTraceParams(''))).toBe('');
  });
});

describe('sameCriterion', () => {
  const base = parseTraceParams('?key=ORD-42&path=order.id');

  it('ignores what does not change the search', () => {
    expect(sameCriterion(base, { ...base })).toBe(true);
    // La fenêtre est ignorée tant qu'on lit « les plus récents » — comme dans le lien partagé.
    expect(sameCriterion(base, { ...base, timeLimitMinutes: 99 })).toBe(true);
  });

  it('sees an edited field', () => {
    expect(sameCriterion(base, { ...base, messageKey: 'ORD-43' })).toBe(false);
    expect(sameCriterion(base, { ...base, searchHeaders: false })).toBe(false);
    expect(sameCriterion(base, { ...base, topics: ['orders'] })).toBe(false);
  });
});

describe('panel state', () => {
  beforeEach(() => localStorage.clear());

  it('falls back to the caller default until a choice is stored', () => {
    expect(readPanelOpen(true)).toBe(true);
    expect(readPanelOpen(false)).toBe(false);
    writePanelOpen(false);
    expect(readPanelOpen(true)).toBe(false);
    writePanelOpen(true);
    expect(readPanelOpen(false)).toBe(true);
  });

  it('keeps the default when the stored value means nothing', () => {
    localStorage.setItem(PANEL_KEY, 'yes please');
    expect(readPanelOpen(true)).toBe(true);
    expect(readPanelOpen(false)).toBe(false);
  });
});

describe('trace history', () => {
  beforeEach(() => localStorage.clear());

  const entry = (key: string) => ({
    ...parseTraceParams(`?key=${key}`), ranAt: Date.now(), topicsFound: 2,
  });

  it('keeps the most recent trace first and de-duplicates on the criterion', () => {
    pushTraceHistory(entry('a'));
    pushTraceHistory(entry('b'));
    const list = pushTraceHistory(entry('a'));

    expect(list.map(e => e.messageKey)).toEqual(['a', 'b']);
    expect(readTraceHistory().map(e => e.messageKey)).toEqual(['a', 'b']);
  });

  it('caps the list at ten entries', () => {
    for (let i = 0; i < 15; i++) pushTraceHistory(entry(`k${i}`));
    expect(readTraceHistory()).toHaveLength(10);
    expect(readTraceHistory()[0].messageKey).toBe('k14');
  });

  it('survives a corrupted store rather than breaking the page', () => {
    localStorage.setItem(HISTORY_KEY, '{not json');
    expect(readTraceHistory()).toEqual([]);
  });
});

describe('export', () => {
  const hits: FlowHit[] = [
    {
      topic: 'orders', occurrences: 2, firstTimestamp: 1_700_000_000_000,
      lastTimestamp: 1_700_000_001_000, firstPartition: 0, firstOffset: 10,
      firstKey: 'K-1', preview: 'a,b"c', latencyFromPreviousMs: null,
    },
    {
      topic: 'billing', occurrences: 1, firstTimestamp: 1_700_000_002_000,
      lastTimestamp: 1_700_000_002_000, firstPartition: 1, firstOffset: 42,
      firstKey: null, preview: null, latencyFromPreviousMs: 2000, occurrencesCapped: true,
    },
  ];

  it('numbers the hops and renders timestamps as ISO', () => {
    const rows = hitsToRows(hits);
    expect(rows[0].hop).toBe(1);
    expect(rows[0].firstSeen).toBe('2023-11-14T22:13:20.000Z');
    expect(rows[1].latencyFromPreviousMs).toBe(2000);
    expect(rows[0].latencyFromPreviousMs).toBe('');
    expect(rows[1].occurrencesCapped).toBe(true);
  });

  it('escapes a preview containing commas and quotes', () => {
    const csv = toCsv(HIT_EXPORT_COLUMNS, hitsToRows(hits));
    expect(csv.split('\r\n')[1]).toContain('"a,b""c"');
  });

  it('exports the criterion and the coverage, not only the hops', () => {
    const params = parseTraceParams('?key=K-1&path=$.id');
    const json = JSON.parse(traceToJson(params, {
      nodes: [], edges: [], hits, warnings: ['heads up'],
      stats: {
        topicsInScope: 2, topicsScanned: 2, topicsSkipped: 0, topicsFailed: 0,
        messagesScanned: 10, matches: 3, durationMs: 5, truncated: false,
        stopReason: 'COMPLETE', maxMessagesPerTopic: 100, timeLimitMinutes: null,
      },
    }));

    expect(json.criterion.messageKey).toBe('K-1');
    expect(json.criterion.searchPath).toBe('$.id');
    expect(json.warnings).toEqual(['heads up']);
    expect(json.hits).toHaveLength(2);
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

  /** Rendre `—` masquait la seule information qui explique un ordre de chaîne douteux. */
  it('shows a hop that goes backwards instead of hiding it', () => {
    expect(formatLatency(-40)).toBe('-40 ms');
    expect(formatLatency(-1500)).toBe('-1.5 s');
  });

  it('renders the dwell time inside a topic, and nothing for an instant one', () => {
    expect(formatDwell(0)).toBe('');
    expect(formatDwell(-5)).toBe('');
    expect(formatDwell(2500)).toBe('2.5 s');
  });
});

describe('analyzeChain', () => {
  const hop = (topic: string, latency: number | null, occurrences = 1, dwell = 0): FlowHit => ({
    topic, occurrences, firstTimestamp: 1_000, lastTimestamp: 1_000 + dwell,
    firstPartition: 0, firstOffset: 1, firstKey: null, preview: null,
    latencyFromPreviousMs: latency,
  });

  it('is empty for an empty chain', () => {
    expect(analyzeChain([])).toEqual({
      totalElapsedMs: null, slowestHopTopic: null, slowestHopMs: null,
      repeatedTopics: [], clockSkewTopics: [], lastTopic: null,
    });
  });

  it('names the slowest hop once there is something to compare', () => {
    const chain = [hop('a', null), hop('b', 100), hop('c', 4000)];
    const insight = analyzeChain(chain);

    expect(insight.slowestHopTopic).toBe('c');
    expect(insight.slowestHopMs).toBe(4000);
    expect(insight.lastTopic).toBe('c');
  });

  /** Sur un seul saut, « le plus lent » est aussi le seul : le signaler ne dit rien. */
  it('does not call a lone hop the slowest', () => {
    expect(analyzeChain([hop('a', null), hop('b', 4000)]).slowestHopTopic).toBeNull();
  });

  it('reports repeated sightings and capped counts', () => {
    const capped: FlowHit = { ...hop('c', 10), occurrences: 1, occurrencesCapped: true };
    const insight = analyzeChain([hop('a', null), hop('b', 5, 3), capped]);

    expect(insight.repeatedTopics).toEqual(['b', 'c']);
  });

  it('flags a hop that goes backwards as clock skew', () => {
    const insight = analyzeChain([hop('a', null), hop('b', -20)]);

    expect(insight.clockSkewTopics).toEqual(['b']);
    expect(insight.slowestHopTopic).toBeNull();
  });

  it('measures the chain end to end from the first sightings', () => {
    const chain = [
      { ...hop('a', null), firstTimestamp: 1_000 },
      { ...hop('b', 500), firstTimestamp: 1_500 },
      { ...hop('c', 2_000), firstTimestamp: 3_500 },
    ];
    expect(analyzeChain(chain).totalElapsedMs).toBe(2_500);
    expect(analyzeChain([hop('a', null)]).totalElapsedMs).toBeNull();
  });
});

describe('describeChainInsight', () => {
  it('turns the findings into short phrases, and says nothing when there is nothing', () => {
    expect(describeChainInsight(analyzeChain([]))).toEqual([]);

    const notes = describeChainInsight({
      totalElapsedMs: 2_500, slowestHopTopic: 'billing', slowestHopMs: 2_000,
      repeatedTopics: ['orders'], clockSkewTopics: ['audit'], lastTopic: 'audit',
    });

    expect(notes[0]).toBe('End to end 2.5 s');
    expect(notes[1]).toContain('slowest hop into billing');
    expect(notes[2]).toContain('1 topic saw the key more than once');
    expect(notes[3]).toContain('clock skew');
  });
});

describe('buildTopicSearchQuery', () => {
  const base = parseTraceParams('?key=ORD-42');
  const parse = (params: TraceParams) => new URLSearchParams(buildTopicSearchQuery(params));

  it('carries an exact record key as a KEY search on its own partition', () => {
    const query = parse({ ...base, exactKey: true });
    expect(query.get('mode')).toBe('KEY');
    expect(query.get('op')).toBe('EQ');
    expect(query.get('value')).toBe('ORD-42');
    expect(query.get('keyPartitioning')).toBe('1');
  });

  it('carries a header criterion as a HEADER search on that header', () => {
    const query = parse({ ...base, searchPath: 'header:correlation-id' });
    expect(query.get('mode')).toBe('HEADER');
    expect(query.get('field')).toBe('correlation-id');
    expect(query.get('op')).toBe('CONTAINS');
    expect(query.get('value')).toBe('ORD-42');
  });

  it('carries a dot path as a FIELD search, and a regex as the operator', () => {
    const query = parse({ ...base, searchPath: 'order.id', useRegex: true });
    expect(query.get('mode')).toBe('FIELD');
    expect(query.get('field')).toBe('order.id');
    expect(query.get('op')).toBe('REGEX');
  });

  /**
   * Le Topic Explorer n'expose ni XPath ni JSONPath : le chemin est abandonné au profit d'une
   * recherche texte, qui ramène un sur-ensemble. Prétendre appliquer le chemin serait un mensonge.
   */
  it('falls back to a text search when the path engine has no equivalent', () => {
    for (const path of ['/order/id', '$..id']) {
      const query = parse({ ...base, searchPath: path });
      expect(query.get('mode')).toBe('CONTAINS');
      expect(query.get('q')).toBe('ORD-42');
      expect(query.get('field')).toBeNull();
    }
  });

  it('passes the case and window options along', () => {
    const query = parse({ ...base, caseSensitive: true, windowMode: 'window', timeLimitMinutes: 30 });
    expect(query.get('case')).toBe('1');
    expect(query.get('since')).toBe('30');
    expect(parse(base).get('since')).toBeNull();
  });
});

describe('filterHits', () => {
  const hit = (topic: string, key: string | null, preview: string | null): FlowHit => ({
    topic, occurrences: 1, firstTimestamp: 1, lastTimestamp: 1, firstPartition: 0,
    firstOffset: 0, firstKey: key, preview, latencyFromPreviousMs: null,
  });
  const hits = [hit('orders.v2', 'ORD-1', '{"status":"NEW"}'), hit('billing', null, '{"amount":12}')];

  it('returns everything for an empty filter', () => {
    expect(filterHits(hits, '  ')).toBe(hits);
  });

  it('matches the topic, the record key or the preview, ignoring case', () => {
    expect(filterHits(hits, 'ORDERS').map(h => h.topic)).toEqual(['orders.v2']);
    expect(filterHits(hits, 'ord-1').map(h => h.topic)).toEqual(['orders.v2']);
    expect(filterHits(hits, 'amount').map(h => h.topic)).toEqual(['billing']);
    expect(filterHits(hits, 'nothing')).toEqual([]);
  });
});

describe('suggestWidenings', () => {
  const base = parseTraceParams('?key=ORD-42');
  const ids = (params: TraceParams) => suggestWidenings(params).map(s => s.id);

  it('offers to drop a search path first — the most likely reason for a miss', () => {
    const suggestions = suggestWidenings({ ...base, searchPath: 'order.id' });
    expect(suggestions[0].id).toBe('drop-path');
    expect(suggestions[0].params.searchPath).toBe('');
    expect(suggestions[0].hint).toContain('order.id');
  });

  it('offers the headers only for a raw search that ignores them', () => {
    expect(ids({ ...base, searchHeaders: false })).toContain('headers');
    expect(ids({ ...base, searchHeaders: false, searchPath: 'order.id' })).not.toContain('headers');
    expect(ids({ ...base, searchHeaders: true })).not.toContain('headers');
  });

  it('only offers to widen what was actually narrowed', () => {
    expect(ids({ ...base, maxMessages: 1000 })).not.toContain('raise-cap');
    expect(ids(base)).not.toContain('drop-window');
    expect(ids({ ...base, windowMode: 'window' })).toContain('drop-window');
    expect(ids(base)).not.toContain('whole-cluster');
    expect(ids({ ...base, topics: ['orders'] })).toContain('whole-cluster');
  });

  it('changes one thing at a time and keeps the rest of the criterion', () => {
    const suggestion = suggestWidenings({ ...base, topics: ['orders'], caseSensitive: true })
      .find(s => s.id === 'whole-cluster')!;
    expect(suggestion.params.topics).toEqual([]);
    expect(suggestion.params.messageKey).toBe('ORD-42');
    expect(suggestion.params.caseSensitive).toBe(true);
  });

  it('stops at four proposals — a wall of buttons is not a hint', () => {
    const everything: TraceParams = {
      ...base, searchPath: 'order.id', windowMode: 'window', maxMessages: 50,
      caseSensitive: true, topics: ['a', 'b'],
    };
    expect(suggestWidenings(everything)).toHaveLength(4);
  });
});

describe('sortHits', () => {
  const hit = (topic: string, occurrences: number, first: number, latency: number | null): FlowHit => ({
    topic, occurrences, firstTimestamp: first, lastTimestamp: first, firstPartition: 0,
    firstOffset: 1, firstKey: null, preview: null, latencyFromPreviousMs: latency,
  });
  const hits = [
    hit('orders', 3, 1_000, null),
    hit('billing', 1, 1_200, 200),
    hit('audit', 2, 1_900, 700),
  ];

  it('leaves the chain order alone by default, and reverses it on demand', () => {
    expect(sortHits(hits, 'chain', false)).toBe(hits);
    expect(sortHits(hits, 'chain', true).map(h => h.topic)).toEqual(['audit', 'billing', 'orders']);
  });

  it('sorts by occurrences, by first sighting and by name', () => {
    expect(sortHits(hits, 'occurrences', true).map(h => h.topic)).toEqual(['orders', 'audit', 'billing']);
    expect(sortHits(hits, 'firstTimestamp', false).map(h => h.topic)).toEqual(['orders', 'billing', 'audit']);
    expect(sortHits(hits, 'topic', false).map(h => h.topic)).toEqual(['audit', 'billing', 'orders']);
  });

  /** Demander « le saut le plus lent » et lire un tiret en tête ne répondrait pas à la question. */
  it('keeps a hop with no latency last, whichever way the column is sorted', () => {
    expect(sortHits(hits, 'latency', true).map(h => h.topic)).toEqual(['audit', 'billing', 'orders']);
    expect(sortHits(hits, 'latency', false).map(h => h.topic)).toEqual(['billing', 'audit', 'orders']);
  });

  it('does not mutate the array it was given', () => {
    const original = [...hits];
    sortHits(hits, 'occurrences', true);
    expect(hits).toEqual(original);
  });

  it('breaks ties by name so two runs sort the same way', () => {
    const tied = [hit('zulu', 1, 5, 10), hit('alpha', 1, 5, 10)];
    expect(sortHits(tied, 'occurrences', false).map(h => h.topic)).toEqual(['alpha', 'zulu']);
  });
});

describe('evidence panel height', () => {
  beforeEach(() => localStorage.clear());

  it('keeps both panes usable', () => {
    expect(clampEvidencePct(0)).toBe(MIN_EVIDENCE_PCT);
    expect(clampEvidencePct(99)).toBe(MAX_EVIDENCE_PCT);
    expect(clampEvidencePct(50)).toBe(50);
    expect(clampEvidencePct(Number.NaN)).toBe(DEFAULT_EVIDENCE_PCT);
  });

  it('remembers the split, and clamps whatever was stored', () => {
    expect(readEvidencePct()).toBe(DEFAULT_EVIDENCE_PCT);
    writeEvidencePct(60);
    expect(readEvidencePct()).toBe(60);
    localStorage.setItem(EVIDENCE_KEY, '5');
    expect(readEvidencePct()).toBe(MIN_EVIDENCE_PCT);
    localStorage.setItem(EVIDENCE_KEY, 'huge');
    expect(readEvidencePct()).toBe(DEFAULT_EVIDENCE_PCT);
  });
});

describe('target topic entry', () => {
  it('splits a pasted list on commas, spaces and newlines, without duplicates', () => {
    expect(parseTopicList('orders, billing\nshipping;orders  audit'))
      .toEqual(['orders', 'billing', 'shipping', 'audit']);
    expect(parseTopicList('   ')).toEqual([]);
  });

  it('expands a pattern over the catalogue and leaves plain names alone', () => {
    const known = ['orders.inbound', 'orders.validated', 'billing.charges'];
    expect(expandTopicPatterns(['orders.*', 'billing.charges'], known))
      .toEqual({ topics: ['orders.inbound', 'orders.validated', 'billing.charges'], unmatched: [] });
  });

  /** Un motif sans correspondance ne doit pas partir comme nom de topic : il n'en est pas un. */
  it('reports a pattern that matches nothing instead of sending it as a name', () => {
    const result = expandTopicPatterns(['nope.*', 'orders.inbound'], ['orders.inbound']);
    expect(result.topics).toEqual(['orders.inbound']);
    expect(result.unmatched).toEqual(['nope.*']);
  });

  it('treats the dot as a literal and only * as a wildcard', () => {
    const known = ['ordersXinbound', 'orders.inbound'];
    expect(expandTopicPatterns(['orders.*'], known).topics).toEqual(['orders.inbound']);
  });

  /** Le catalogue a 30 s de retard : un topic tout juste créé reste saisissable. */
  it('keeps an unknown plain name, which may simply be newer than the catalogue', () => {
    expect(expandTopicPatterns(['brand.new'], []).topics).toEqual(['brand.new']);
  });
});

describe('buildTraceLinkForKey', () => {
  it('traces the record key as an exact key search', () => {
    expect(buildTraceLinkForKey('ORD-42')).toBe('/stream-flow?key=ORD-42&exact=1');
  });

  it('escapes a key that would otherwise break the query string', () => {
    const link = buildTraceLinkForKey('a b&c=d');
    expect(parseTraceParams(link.slice(link.indexOf('?'))).messageKey).toBe('a b&c=d');
  });
});

describe('compareFlows', () => {
  const hop = (topic: string, latency: number | null, first: number): FlowHit => ({
    topic, occurrences: 1, firstTimestamp: first, lastTimestamp: first, firstPartition: 0,
    firstOffset: 1, firstKey: null, preview: null, latencyFromPreviousMs: latency,
  });

  const a = [hop('orders', null, 1_000), hop('billing', 200, 1_200), hop('shipping', 300, 1_500)];

  it('reads the same route as the same route', () => {
    const b = [hop('orders', null, 9_000), hop('billing', 250, 9_250), hop('shipping', 350, 9_600)];
    const comparison = compareFlows(a, b);

    expect(comparison.sameRoute).toBe(true);
    expect(comparison.shared).toBe(3);
    expect(comparison.onlyA + comparison.onlyB).toBe(0);
    expect(describeComparison(comparison)).toContain('Same route through 3 topics');
  });

  /** Ce sont les latences de saut qui se comparent : deux clés traitées à une heure d'écart
      n'ont rien à se dire dans l'absolu. */
  it('compares hop latencies, not absolute timestamps', () => {
    const b = [hop('orders', null, 9_000), hop('billing', 4_200, 13_200), hop('shipping', 300, 13_500)];
    const comparison = compareFlows(a, b);

    const billing = comparison.rows.find(r => r.topic === 'billing')!;
    expect(billing.latencyA).toBe(200);
    expect(billing.latencyB).toBe(4_200);
    expect(billing.deltaMs).toBe(4_000);
    expect(slowestDivergence(comparison)?.topic).toBe('billing');
  });

  it('marks what only one side saw, A first then B', () => {
    const b = [hop('orders', null, 9_000), hop('audit', 100, 9_100)];
    const comparison = compareFlows(a, b);

    expect(comparison.rows.map(r => [r.topic, r.status])).toEqual([
      ['orders', 'BOTH'], ['billing', 'ONLY_A'], ['shipping', 'ONLY_A'], ['audit', 'ONLY_B'],
    ]);
    expect(comparison.sameRoute).toBe(false);
    const text = describeComparison(comparison);
    expect(text).toContain('2 only in A');
    expect(text).toContain('1 only in B');
  });

  it('keeps each chain hop number so a row can be located in either trace', () => {
    const b = [hop('audit', null, 9_000), hop('billing', 100, 9_100)];
    const billing = compareFlows(a, b).rows.find(r => r.topic === 'billing')!;
    expect(billing.hopA).toBe(2);
    expect(billing.hopB).toBe(2);
  });

  /** Deux traces vides ne se ressemblent pas : elles ne disent rien. */
  it('does not call two empty traces identical', () => {
    const comparison = compareFlows([], []);
    expect(comparison.sameRoute).toBe(false);
    expect(describeComparison(comparison)).toMatch(/anything to compare/i);
    expect(slowestDivergence(comparison)).toBeNull();
  });

  it('has no divergence to report when every shared hop took the same time', () => {
    expect(slowestDivergence(compareFlows(a, a))).toBeNull();
  });
});

describe('buildContinuation', () => {
  const stats: FlowStats = {
    topicsInScope: 250, topicsScanned: 248, topicsSkipped: 2, topicsFailed: 0,
    skippedTopics: ['billing', 'shipping'],
    messagesScanned: 24_800, matches: 3, durationMs: 12_400, truncated: true,
    stopReason: 'TIME_BUDGET', maxMessagesPerTopic: 100, timeLimitMinutes: null,
  };
  const hit: FlowHit = {
    topic: 'orders', occurrences: 1, firstTimestamp: 10, lastTimestamp: 10, firstPartition: 0,
    firstOffset: 3, firstKey: 'K-1', preview: null, latencyFromPreviousMs: null,
  };
  const flow = { nodes: [], edges: [], hits: [hit], stats, warnings: [] };

  it('carries the unread topics, the hops found and what the first pass covered', () => {
    const continuation = buildContinuation(flow)!;
    expect(continuation.topics).toEqual(['billing', 'shipping']);
    expect(continuation.priorHits).toEqual([hit]);
    expect(continuation.priorCoverage).toEqual({
      topicsScanned: 248, messagesScanned: 24_800, matches: 3, durationMs: 12_400,
    });
  });

  it('offers itself after a cancellation too — the same topics are missing', () => {
    expect(buildContinuation({ ...flow, stats: { ...stats, stopReason: 'CANCELLED' } })).not.toBeNull();
  });

  it('has nothing to offer on a completed trace, or with no topic named', () => {
    expect(buildContinuation({ ...flow, stats: { ...stats, stopReason: 'COMPLETE' } })).toBeNull();
    expect(buildContinuation({ ...flow, stats: { ...stats, skippedTopics: [] } })).toBeNull();
    // Un serveur plus ancien ne renvoie pas la liste : on ne propose pas une reprise sans portée.
    expect(buildContinuation({ ...flow, stats: { ...stats, skippedTopics: undefined } })).toBeNull();
    expect(buildContinuation({ ...flow, stats: null })).toBeNull();
  });

  /** La liste des noms est bornée côté serveur : le libellé ne doit pas promettre plus. */
  it('states the real scope when only some of the missing topics are named', () => {
    expect(describeContinuation(buildContinuation(flow)!))
      .toBe('Continue on the 2 topics never read');

    const many = buildContinuation({ ...flow, stats: { ...stats, topicsSkipped: 412 } })!;
    expect(many.pending).toBe(412);
    expect(describeContinuation(many)).toBe('Continue on 2 of the 412 topics never read');
  });

  it('trusts the named list when the count disagrees with it', () => {
    const continuation = buildContinuation({ ...flow, stats: { ...stats, topicsSkipped: 0 } })!;
    expect(continuation.pending).toBe(2);
    expect(describeContinuation(continuation)).toBe('Continue on the 2 topics never read');
  });
});

describe('graph focus', () => {
  const layout = buildLayout(
    [{ id: 'a', label: 'a' }, { id: 'b', label: 'b' }, { id: 'c', label: 'c' }],
    [{ source: 'a', target: 'b' }, { source: 'b', target: 'c' }],
  );
  const viewport = { width: 400, height: 300 };

  it('knows when a node is off screen', () => {
    const identity = { x: 0, y: 0, scale: 1 };
    expect(isNodeVisible(layout, viewport, identity, 'a')).toBe(true);
    expect(isNodeVisible(layout, viewport, identity, 'c')).toBe(false);
    expect(isNodeVisible(layout, viewport, identity, 'unknown')).toBe(false);
  });

  it('brings a node to the centre without changing the scale', () => {
    const centred = centerOn(layout, viewport, 'c', 0.5)!;
    expect(centred.scale).toBe(0.5);
    expect(isNodeVisible(layout, viewport, centred, 'c')).toBe(true);
    expect(centerOn(layout, viewport, 'unknown', 1)).toBeNull();
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
