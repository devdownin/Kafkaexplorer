import React, { useState, useEffect, useRef, useMemo, useCallback } from 'react';
import axios from 'axios';
import { useToast } from '../components/Toast';
import { Button, EmptyState } from '../components/ui';

interface LineageNode {
  id: string;
  label: string;
  type: 'topic' | 'table' | 'view' | 'query' | string;
  messageCount?: number;
}

interface LineageEdge {
  from: string;
  to: string;
  label?: string;
}

interface LineageData {
  nodes: LineageNode[];
  edges: LineageEdge[];
  /**
   * Statements Flink's parser could not resolve, whose dependencies were read off the SQL text
   * instead. A missing edge and an absent dependency look identical on a graph — so the graph says
   * when it had to guess.
   */
  warnings?: string[];
}

const nodeConfig: Record<string, { shape: 'circle' | 'rect' | 'diamond' | 'hex'; color: string; bg: string }> = {
  topic:  { shape: 'circle',  color: '#a3adff', bg: '#12151a' },
  table:  { shape: 'rect',    color: '#a3adff', bg: '#14402a' },
  view:   { shape: 'diamond', color: '#c9a9f7', bg: '#3b2762' },
  query:  { shape: 'hex',     color: '#f5c264', bg: '#4a3a12' },
};

const NODE_W = 140;
const NODE_H = 48;
const COL_GAP = 240;
const ROW_GAP = 90;

function formatMsgCount(n: number): string {
  if (n >= 1_000_000) return (n / 1_000_000).toFixed(1) + 'M';
  if (n >= 1_000) return (n / 1_000).toFixed(1) + 'K';
  return String(n);
}

/** Columns used to grid-wrap nodes that have no edge at all. */
const ORPHAN_COLS = 6;
const ORPHAN_GAP = 60;

function computeLayout(nodes: LineageNode[], edges: LineageEdge[]): Record<string, { x: number; y: number }> {
  if (nodes.length === 0) return {};

  // Adjacency built once — filtering `edges` inside the traversal made this O(nodes × edges).
  const known = new Set(nodes.map(n => n.id));
  const adjacency = new Map<string, string[]>();
  const inDegree = new Map<string, number>();
  const touched = new Set<string>();
  nodes.forEach(n => { adjacency.set(n.id, []); inDegree.set(n.id, 0); });
  edges.forEach(e => {
    if (!known.has(e.from) || !known.has(e.to)) return;
    adjacency.get(e.from)!.push(e.to);
    inDegree.set(e.to, (inDegree.get(e.to) ?? 0) + 1);
    touched.add(e.from);
    touched.add(e.to);
  });

  // Wired nodes get the layered DAG treatment; the rest are gridded below, so a cluster
  // with no edges (e.g. every Kafka topic when nothing is wired) cannot stretch the graph
  // into one column thousands of pixels tall.
  const connected = nodes.filter(n => touched.has(n.id));
  const isolated = nodes.filter(n => !touched.has(n.id));

  const layers: string[][] = [];
  const visited = new Set<string>();
  let queue = connected.filter(n => (inDegree.get(n.id) ?? 0) === 0).map(n => n.id);
  if (queue.length === 0 && connected.length > 0) queue = [connected[0].id];

  while (queue.length > 0) {
    const layer: string[] = [];
    const next: string[] = [];
    for (const id of queue) {
      if (visited.has(id)) continue;
      visited.add(id);
      layer.push(id);
      for (const to of adjacency.get(id) ?? []) {
        if (!visited.has(to)) next.push(to);
      }
    }
    if (layer.length) layers.push(layer);
    queue = next;
  }
  // Nodes only reachable through a cycle share one trailing column instead of one each.
  const leftover = connected.filter(n => !visited.has(n.id)).map(n => n.id);
  if (leftover.length) layers.push(leftover);

  const maxRows = layers.reduce((m, l) => Math.max(m, l.length), 1);
  const positions: Record<string, { x: number; y: number }> = {};
  layers.forEach((layer, col) => {
    const totalH = layer.length * NODE_H + (layer.length - 1) * ROW_GAP;
    const startY = (maxRows * (NODE_H + ROW_GAP) - totalH) / 2 + 40;
    layer.forEach((id, row) => {
      positions[id] = { x: col * (NODE_W + COL_GAP) + 60, y: startY + row * (NODE_H + ROW_GAP) };
    });
  });

  if (isolated.length > 0) {
    const gridTop = layers.length > 0
      ? maxRows * (NODE_H + ROW_GAP) + 40 + ROW_GAP
      : 40;
    isolated.forEach((n, i) => {
      positions[n.id] = {
        x: (i % ORPHAN_COLS) * (NODE_W + ORPHAN_GAP) + 60,
        y: gridTop + Math.floor(i / ORPHAN_COLS) * (NODE_H + ORPHAN_GAP),
      };
    });
  }
  return positions;
}

// ── NodeShape ─────────────────────────────────────────────────────────────────

const NodeShape: React.FC<{
  node: LineageNode;
  x: number; y: number;
  selected: boolean;
  onClick: () => void;
  onHoverEnter: (clientX: number, clientY: number) => void;
  onHoverLeave: () => void;
}> = ({ node, x, y, selected, onClick, onHoverEnter, onHoverLeave }) => {
  const cfg = nodeConfig[node.type] ?? nodeConfig.table;
  const cx = x + NODE_W / 2;
  const cy = y + NODE_H / 2;
  const label = node.label.length > 16 ? node.label.slice(0, 15) + '…' : node.label;
  const stroke = selected ? '#ffffff' : cfg.color;
  const sw = selected ? 2.5 : 1.5;

  const renderShape = () => {
    if (cfg.shape === 'circle') {
      return <ellipse cx={cx} cy={cy} rx={NODE_W / 2} ry={NODE_H / 2} fill={cfg.bg} stroke={stroke} strokeWidth={sw} />;
    }
    if (cfg.shape === 'diamond') {
      return <polygon points={`${cx},${y} ${x + NODE_W},${cy} ${cx},${y + NODE_H} ${x},${cy}`} fill={cfg.bg} stroke={stroke} strokeWidth={sw} />;
    }
    if (cfg.shape === 'hex') {
      const qw = NODE_W / 4;
      return <polygon
        points={`${x + qw},${y} ${x + NODE_W - qw},${y} ${x + NODE_W},${cy} ${x + NODE_W - qw},${y + NODE_H} ${x + qw},${y + NODE_H} ${x},${cy}`}
        fill={cfg.bg} stroke={stroke} strokeWidth={sw}
      />;
    }
    return <rect x={x} y={y} width={NODE_W} height={NODE_H} rx={8} fill={cfg.bg} stroke={stroke} strokeWidth={sw} />;
  };

  const subLabel = node.type === 'topic' && node.messageCount !== undefined
    ? `${node.type.toUpperCase()} · ${formatMsgCount(node.messageCount)}`
    : node.type.toUpperCase();

  return (
    <g
      className="cursor-pointer focus:outline-none"
      // Chaque nœud est atteignable au clavier : la sélection ouvre le panneau de détails, et
      // elle n'existait qu'à la souris.
      tabIndex={0}
      role="button"
      aria-pressed={selected}
      aria-label={`${node.label}, ${subLabel}`}
      onClick={onClick}
      onKeyDown={e => {
        if (e.key !== 'Enter' && e.key !== ' ') return;
        e.preventDefault();
        e.stopPropagation();
        onClick();
      }}
      onFocus={e => {
        const box = (e.currentTarget as SVGGElement).getBoundingClientRect();
        onHoverEnter(box.left + box.width / 2, box.top);
      }}
      onBlur={onHoverLeave}
      onMouseEnter={e => onHoverEnter(e.clientX, e.clientY)}
      onMouseLeave={onHoverLeave}
    >
      {selected && (
        <rect x={x - 5} y={y - 5} width={NODE_W + 10} height={NODE_H + 10} rx={12}
          fill={cfg.color} fillOpacity={0.1} stroke={cfg.color} strokeWidth={1} strokeOpacity={0.3} />
      )}
      {renderShape()}
      <text x={cx} y={cy + 4} textAnchor="middle" fill="white" fontSize={11}
        fontFamily="JetBrains Mono, monospace" fontWeight={selected ? 'bold' : 'normal'}>
        {label}
      </text>
      <text x={cx} y={y + NODE_H + 15} textAnchor="middle" fill={cfg.color}
        fontSize={9} fontFamily="Inter, sans-serif" opacity={0.65}>
        {subLabel}
      </text>
    </g>
  );
};

// ── Lineage page ──────────────────────────────────────────────────────────────

const Lineage: React.FC = () => {
  const { toast } = useToast();
  const [data, setData]                   = useState<LineageData>({ nodes: [], edges: [], warnings: [] });
  const [loading, setLoading]             = useState(true);
  const [connectedOnly, setConnectedOnly] = useState(false);
  const [selectedNode, setSelectedNode]   = useState<LineageNode | null>(null);
  const [ddl, setDdl]                     = useState<string | null>(null);
  const [loadingDdl, setLoadingDdl]       = useState(false);
  const [searchTerm, setSearchTerm]       = useState('');
  const [tooltip, setTooltip]             = useState<{ node: LineageNode; x: number; y: number } | null>(null);
  const [transform, setTransform]         = useState({ x: 40, y: 20, scale: 1 });

  const svgRef    = useRef<SVGSVGElement>(null);
  const isPanning = useRef(false);
  const lastPos   = useRef({ x: 0, y: 0 });

  // ── Data fetching ───────────────────────────────────────────────────────────

  // Tracks the newest in-flight request so a slow earlier response cannot overwrite it
  // (toggling "Connected only" twice quickly used to be enough to land stale data).
  const requestSeq = useRef(0);
  const abortRef = useRef<AbortController | null>(null);

  const fetchLineage = useCallback(async () => {
    abortRef.current?.abort();
    const controller = new AbortController();
    abortRef.current = controller;
    const seq = ++requestSeq.current;

    setLoading(true);
    try {
      const res = await axios.get<LineageData>(
        `/api/lineage?connectedOnly=${connectedOnly}`,
        { signal: controller.signal }
      );
      if (seq !== requestSeq.current) return;
      const d = res.data;
      setData({ nodes: d.nodes ?? [], edges: d.edges ?? [], warnings: d.warnings ?? [] });
      // Clear selection if node no longer exists
      setSelectedNode(prev =>
        prev && (d.nodes ?? []).some(n => n.id === prev.id) ? prev : null
      );
    } catch (err) {
      if (axios.isCancel(err) || seq !== requestSeq.current) return;
      toast('Failed to load lineage graph', 'error');
    } finally {
      if (seq === requestSeq.current) setLoading(false);
    }
  // eslint-disable-next-line react-hooks/exhaustive-deps -- toast is stable
  }, [connectedOnly]);

  useEffect(() => {
    fetchLineage();
    return () => abortRef.current?.abort();
  }, [fetchLineage]);

  // ── Wheel zoom ──────────────────────────────────────────────────────────────

  /** Scales around a fixed point in viewport coordinates, so that point stays put. */
  const zoomAround = useCallback((factor: number, px: number, py: number) => {
    setTransform(t => {
      const scale = Math.max(0.15, Math.min(4, t.scale * factor));
      const k = scale / t.scale;
      return { scale, x: px - (px - t.x) * k, y: py - (py - t.y) * k };
    });
  }, []);

  /** Zoom buttons keep the centre of the canvas anchored. */
  const zoomFromCenter = useCallback((factor: number) => {
    const rect = svgRef.current?.getBoundingClientRect();
    zoomAround(factor, (rect?.width ?? 0) / 2, (rect?.height ?? 0) / 2);
  }, [zoomAround]);

  useEffect(() => {
    const el = svgRef.current;
    if (!el) return;
    const handler = (e: WheelEvent) => {
      e.preventDefault();
      const rect = el.getBoundingClientRect();
      zoomAround(e.deltaY > 0 ? 0.9 : 1.1, e.clientX - rect.left, e.clientY - rect.top);
    };
    el.addEventListener('wheel', handler, { passive: false });
    return () => el.removeEventListener('wheel', handler);
  }, [zoomAround]);

  // ── Derived state ───────────────────────────────────────────────────────────

  const positions = useMemo(() => computeLayout(data.nodes, data.edges), [data]);

  /** IDs of selected node + its direct neighbors — used for focus dimming. */
  const neighborIds = useMemo<Set<string> | null>(() => {
    if (!selectedNode) return null;
    const ids = new Set<string>([selectedNode.id]);
    data.edges.forEach(e => {
      if (e.from === selectedNode.id || e.to === selectedNode.id) {
        ids.add(e.from); ids.add(e.to);
      }
    });
    return ids;
  }, [selectedNode, data.edges]);

  /** Fast node lookup by id — used in inspector to show labels instead of raw IDs. */
  const nodeById = useMemo(
    () => Object.fromEntries(data.nodes.map(n => [n.id, n])),
    [data.nodes]
  );

  const searchResults = useMemo(() => {
    if (!searchTerm.trim()) return [];
    const lower = searchTerm.toLowerCase();
    return data.nodes.filter(n => n.label.toLowerCase().includes(lower)).slice(0, 8);
  }, [searchTerm, data.nodes]);

  /** Edges touching the selection, split by direction — memoized: the inspector and the
   *  canvas together read these on every render. */
  const outgoingEdges = useMemo(
    () => (selectedNode ? data.edges.filter(e => e.from === selectedNode.id) : []),
    [selectedNode, data.edges]
  );
  const incomingEdges = useMemo(
    () => (selectedNode ? data.edges.filter(e => e.to === selectedNode.id) : []),
    [selectedNode, data.edges]
  );
  const selectedEdges = useMemo(
    () => [...outgoingEdges, ...incomingEdges],
    [outgoingEdges, incomingEdges]
  );
  /** Membership test for the edge loop — `Array.includes` there made rendering O(edges²). */
  const selectedEdgeSet = useMemo(() => new Set(selectedEdges), [selectedEdges]);

  // ── Center canvas on a node ─────────────────────────────────────────────────

  const centerOnNode = useCallback((nodeId: string) => {
    const pos = positions[nodeId];
    if (!pos || !svgRef.current) return;
    const rect = svgRef.current.getBoundingClientRect();
    setTransform(prev => ({
      ...prev,
      x: rect.width  / 2 - (pos.x + NODE_W / 2) * prev.scale,
      y: rect.height / 2 - (pos.y + NODE_H / 2) * prev.scale,
    }));
  }, [positions]);

  // ── Pan handlers ────────────────────────────────────────────────────────────

  // Événements *pointeur* et non souris : le même code fait glisser le graphe au doigt sur une
  // tablette, où le déplacement était jusqu'ici impossible (`touch-action: none` empêche la page
  // de défiler sous le geste). Même traitement que le graphe de Stream Flow.
  const onPointerDown = useCallback((e: React.PointerEvent<SVGSVGElement>) => {
    if ((e.target as Element).closest('[data-node]')) return;
    isPanning.current = true;
    lastPos.current = { x: e.clientX, y: e.clientY };
    e.currentTarget.style.cursor = 'grabbing';
  }, []);

  const onPointerMove = useCallback((e: React.PointerEvent<SVGSVGElement>) => {
    if (!isPanning.current) return;
    const dx = e.clientX - lastPos.current.x;
    const dy = e.clientY - lastPos.current.y;
    lastPos.current = { x: e.clientX, y: e.clientY };
    setTransform(t => ({ ...t, x: t.x + dx, y: t.y + dy }));
    setTooltip(null);
  }, []);

  const onPointerUp = useCallback((e: React.PointerEvent<SVGSVGElement>) => {
    isPanning.current = false;
    e.currentTarget.style.cursor = 'grab';
  }, []);

  const resetView = useCallback(() => setTransform({ x: 40, y: 20, scale: 1 }), []);

  /**
   * Le graphe se pilote au clavier : flèches pour se déplacer, +/− pour zoomer, 0 pour recadrer,
   * Échap pour désélectionner. Sans cela, un graphe de dépendances n'existait qu'à la souris.
   */
  const onGraphKeyDown = useCallback((e: React.KeyboardEvent<SVGSVGElement>) => {
    const step = e.shiftKey ? 160 : 48;
    switch (e.key) {
      case 'ArrowLeft':  setTransform(t => ({ ...t, x: t.x + step })); break;
      case 'ArrowRight': setTransform(t => ({ ...t, x: t.x - step })); break;
      case 'ArrowUp':    setTransform(t => ({ ...t, y: t.y + step })); break;
      case 'ArrowDown':  setTransform(t => ({ ...t, y: t.y - step })); break;
      case '+': case '=': zoomFromCenter(1.25); break;
      case '-': case '_': zoomFromCenter(0.8); break;
      case '0': resetView(); break;
      case 'Escape': setSelectedNode(null); break;
      default: return;
    }
    e.preventDefault();
  }, [resetView, zoomFromCenter]);
  const isEmpty   = !loading && data.nodes.length === 0;

  // ── Render ──────────────────────────────────────────────────────────────────

  return (
    <div style={{ position: 'absolute', inset: 0, display: 'flex', overflow: 'hidden' }}>

      {/* ── Floating tooltip ── */}
      {tooltip && (
        <div
          style={{ position: 'fixed', left: tooltip.x + 14, top: tooltip.y - 64, zIndex: 100, pointerEvents: 'none' }}
          className="bg-background-dark/95 border border-primary/30 rounded-lg px-3 py-2 shadow-2xl text-xs max-w-[220px]"
        >
          <p className="font-mono font-bold text-on-surface break-all leading-snug">{tooltip.node.label}</p>
          <p className="text-on-surface-variant uppercase text-[10px] mt-0.5">{tooltip.node.type}</p>
          {tooltip.node.type === 'topic' && tooltip.node.messageCount !== undefined && (
            <p className="text-primary font-mono text-[10px] mt-0.5">
              {tooltip.node.messageCount.toLocaleString()} messages
            </p>
          )}
        </div>
      )}

      {/* ── Sidebar ── */}
      <aside className="w-56 border-r border-outline-variant/60 bg-background-dark flex flex-col gap-4 shrink-0 p-4 overflow-y-auto">

        {/* Search */}
        <div className="relative">
          <div className="flex items-center gap-2 bg-surface-container-low border border-outline-variant rounded-md px-2.5 py-1.5 focus-within:border-primary/40 transition-colors">
            <span className="material-symbols-outlined text-on-surface-variant text-base shrink-0">search</span>
            <input
              value={searchTerm}
              onChange={e => setSearchTerm(e.target.value)}
              placeholder="Search nodes…"
              className="bg-transparent outline-none text-xs text-on-surface w-full placeholder:text-outline"
            />
            {searchTerm && (
              <button onClick={() => setSearchTerm('')} className="text-outline hover:text-on-surface">
                <span className="material-symbols-outlined text-sm">close</span>
              </button>
            )}
          </div>
          {searchResults.length > 0 && (
            <div className="absolute top-full mt-1 left-0 right-0 bg-surface-container border border-outline-variant rounded-lg shadow-xl z-20 overflow-hidden">
              {searchResults.map(n => {
                const cfg = nodeConfig[n.type] ?? nodeConfig.table;
                return (
                  <button
                    key={n.id}
                    onClick={() => {
                      setSelectedNode(n);
                      setDdl(null);
                      centerOnNode(n.id);
                      setSearchTerm('');
                    }}
                    className="w-full flex items-center gap-2 px-3 py-2 hover:bg-primary/10 transition-colors text-left"
                  >
                    <span className="w-2 h-2 rounded-sm shrink-0" style={{ backgroundColor: cfg.color }} />
                    <span className="text-xs font-mono text-on-surface truncate">{n.label}</span>
                    <span className="text-[9px] text-outline shrink-0 ml-auto uppercase">{n.type}</span>
                  </button>
                );
              })}
            </div>
          )}
        </div>

        {/* Node type legend */}
        <div>
          <h3 className="text-xs font-bold text-on-surface-variant uppercase tracking-widest mb-2">Node Types</h3>
          <div className="space-y-1">
            {(['topic', 'table', 'view', 'query'] as const).map(type => {
              const count = data.nodes.filter(n => n.type === type).length;
              const cfg = nodeConfig[type];
              return (
                <div key={type} className="flex items-center gap-2.5 px-2.5 py-1.5 rounded-lg bg-surface-container-high">
                  <span className="w-2.5 h-2.5 rounded-sm shrink-0" style={{ backgroundColor: cfg.color, opacity: 0.8 }} />
                  <span className="capitalize text-on-surface text-xs">{type}s</span>
                  <span className="ml-auto text-[10px] bg-primary/10 px-1.5 py-0.5 rounded text-primary font-mono">{count}</span>
                </div>
              );
            })}
          </div>
        </div>

        {/* Options */}
        <div className="border-t border-outline-variant/60 pt-3 space-y-2.5">
          <h3 className="text-xs font-bold text-on-surface-variant uppercase tracking-widest mb-2">Options</h3>

          <label className="flex items-center justify-between cursor-pointer select-none group">
            <span className="text-xs text-on-surface-variant group-hover:text-on-surface transition-colors leading-none">
              Connected only
            </span>
            <button
              role="switch"
              aria-checked={connectedOnly}
              onClick={() => setConnectedOnly(v => !v)}
              className={`relative inline-flex h-5 w-9 items-center rounded-full transition-colors shrink-0 ${connectedOnly ? 'bg-primary' : 'bg-surface-container-high'}`}
            >
              <span className={`inline-block h-3 w-3 transform rounded-full bg-on-primary transition-transform ${connectedOnly ? 'translate-x-5' : 'translate-x-1'}`} />
            </button>
          </label>

          <Button variant="ghost" size="sm" className="w-full justify-start"
            icon={loading ? undefined : 'refresh'} loading={loading}
            onClick={fetchLineage} disabled={loading}>
            {loading ? 'Loading…' : 'Refresh graph'}
          </Button>
        </div>

        {/* Stats */}
        <div className="border-t border-outline-variant/60 pt-3 text-xs text-on-surface-variant space-y-1">
          <p className="font-bold text-on-surface-variant uppercase tracking-wider text-[10px]">Stats</p>
          <p>{data.nodes.length} nodes · {data.edges.length} edges</p>
          {selectedNode && (
            <p className="text-primary truncate">
              <span className="text-outline">Focus: </span>{selectedNode.label}
            </p>
          )}
        </div>

        {/* Controls hint */}
        <div className="mt-auto text-[10px] text-outline space-y-0.5 border-t border-outline-variant/60 pt-3">
          <p>Scroll to zoom</p>
          <p>Drag to pan</p>
          <p>Click node for details</p>
          {selectedNode && <p className="text-primary/60">Neighbors highlighted</p>}
        </div>
      </aside>

      {/* ── Canvas ── */}
      <main className="flex-1 relative overflow-hidden graph-bg bg-background-dark">

        {/* Top badge */}
        <div className="absolute top-4 left-4 right-4 z-10 flex flex-col items-start gap-2 pointer-events-none">
          <div className="flex items-center gap-2 bg-surface-container/90 border border-outline-variant px-3 py-1.5 rounded-full text-xs">
            <span className="text-on-surface-variant">Lineage</span>
            <span className="text-primary/40">/</span>
            <span className="text-primary font-semibold">Dependency Graph</span>
            {connectedOnly && (
              <span className="px-1.5 py-0.5 rounded bg-primary/20 text-primary text-[9px] font-bold uppercase">
                connected only
              </span>
            )}
          </div>
          {/* Une arête manquante et une dépendance inexistante se ressemblent sur un graphe :
              quand le parseur n'a pas pu résoudre un statement, le graphe le dit. */}
          {(data.warnings ?? []).map(warning => (
            <div
              key={warning}
              className="max-w-lg flex items-start gap-2 bg-warning/10 border border-warning/30 text-warning px-3 py-1.5 rounded-lg text-[11px] leading-relaxed"
              role="status"
            >
              <span aria-hidden="true" className="material-symbols-outlined text-[14px] mt-0.5 shrink-0">info</span>
              <span>{warning}</span>
            </div>
          ))}
        </div>

        {/* Zoom controls */}
        <div className="absolute bottom-6 left-4 z-10 flex flex-col bg-surface-container border border-outline-variant rounded-xl overflow-hidden shadow-xl">
          <button onClick={() => zoomFromCenter(1.25)}
            className="p-2 hover:bg-surface-container-high text-on-surface-variant hover:text-on-surface border-b border-outline-variant/60 transition-colors">
            <span className="material-symbols-outlined text-lg">add</span>
          </button>
          <button onClick={() => zoomFromCenter(0.8)}
            className="p-2 hover:bg-surface-container-high text-on-surface-variant hover:text-on-surface border-b border-outline-variant/60 transition-colors">
            <span className="material-symbols-outlined text-lg">remove</span>
          </button>
          <button onClick={resetView} title="Reset view"
            className="p-2 hover:bg-surface-container-high text-on-surface-variant hover:text-on-surface transition-colors">
            <span className="material-symbols-outlined text-lg">center_focus_weak</span>
          </button>
        </div>

        {/* Empty state */}
        {isEmpty && (
          <div className="absolute inset-0 flex flex-col items-center justify-center gap-4 z-10">
            <EmptyState
              icon="account_tree"
              title="No lineage data available"
              description={connectedOnly
                ? 'No connected topics found. Disable “Connected only” to see all topics.'
                : 'Create Flink SQL tables and run INSERT INTO jobs to visualize the pipeline.'}
            />
            {!connectedOnly && (
              <div className="bg-surface-container border border-outline-variant rounded-xl px-5 py-3 text-xs font-mono text-on-surface-variant text-left space-y-1 max-w-sm">
                <p className="text-primary text-[11px] uppercase tracking-[0.05em] mb-2">Example</p>
                <p>CREATE TABLE orders_raw (...);</p>
                <p>INSERT INTO orders_out</p>
                <p className="pl-4">SELECT * FROM orders_raw;</p>
              </div>
            )}
          </div>
        )}

        {/* SVG graph */}
        {!isEmpty && (
          <svg
            ref={svgRef}
            className="w-full h-full select-none focus:outline-none focus-visible:ring-1 focus-visible:ring-primary/60"
            style={{ cursor: 'grab', touchAction: 'none' }}
            role="application"
            tabIndex={0}
            aria-label={`Dependency graph, ${data.nodes.length} nodes. Arrow keys pan, plus and minus zoom, 0 resets.`}
            onPointerDown={onPointerDown}
            onPointerMove={onPointerMove}
            onPointerUp={onPointerUp}
            onPointerLeave={onPointerUp}
            onKeyDown={onGraphKeyDown}
            // Cliquer le fond désélectionne, comme sur le graphe de Stream Flow.
            onClick={e => { if (e.target === e.currentTarget) setSelectedNode(null); }}
          >
            <defs>
              <marker id="arrow-lin" markerWidth="8" markerHeight="6" refX="7" refY="3" orient="auto">
                <polygon points="0 0, 8 3, 0 6" fill="#a3adff" opacity="0.6" />
              </marker>
              <marker id="arrow-lin-hi" markerWidth="8" markerHeight="6" refX="7" refY="3" orient="auto">
                <polygon points="0 0, 8 3, 0 6" fill="#ffffff" opacity="0.9" />
              </marker>
            </defs>

            <g transform={`translate(${transform.x}, ${transform.y}) scale(${transform.scale})`}>

              {/* Edges */}
              {data.edges.map((edge, i) => {
                const s = positions[edge.from];
                const t = positions[edge.to];
                if (!s || !t) return null;

                const hi = selectedEdgeSet.has(edge);
                // Dim edge when a node is selected but this edge is not connected to it
                const dim = neighborIds !== null && !hi;

                const x1 = s.x + NODE_W, y1 = s.y + NODE_H / 2;
                const x2 = t.x,          y2 = t.y + NODE_H / 2;
                const mx = (x1 + x2) / 2;

                return (
                  <g key={i}>
                    <path
                      d={`M${x1},${y1} C${mx},${y1} ${mx},${y2} ${x2},${y2}`}
                      fill="none"
                      stroke={hi ? '#ffffff' : '#a3adff'}
                      strokeWidth={hi ? 2 : 1.5}
                      opacity={dim ? 0.05 : (hi ? 0.9 : 0.3)}
                      // Remove marker on dimmed edges (markers ignore parent opacity)
                      markerEnd={dim ? undefined : (hi ? 'url(#arrow-lin-hi)' : 'url(#arrow-lin)')}
                    />
                    {edge.label && !dim && (
                      <text x={mx} y={Math.min(y1, y2) - 7} textAnchor="middle"
                        fill="#79839a" fontSize={9}>{edge.label}</text>
                    )}
                  </g>
                );
              })}

              {/* Nodes */}
              {data.nodes.map(node => {
                const pos = positions[node.id];
                if (!pos) return null;
                const dimNode = neighborIds !== null && !neighborIds.has(node.id);

                return (
                  <g
                    key={node.id}
                    data-node="true"
                    style={{ opacity: dimNode ? 0.1 : 1, transition: 'opacity 0.15s' }}
                  >
                    <NodeShape
                      node={node}
                      x={pos.x} y={pos.y}
                      selected={selectedNode?.id === node.id}
                      onClick={() => { setSelectedNode(prev => prev?.id === node.id ? null : node); setDdl(null); }}
                      onHoverEnter={(cx, cy) => {
                        if (!isPanning.current) setTooltip({ node, x: cx, y: cy });
                      }}
                      onHoverLeave={() => setTooltip(null)}
                    />
                  </g>
                );
              })}
            </g>
          </svg>
        )}
      </main>

      {/* ── Inspector panel ── */}
      {selectedNode && (
        <aside className="border-l border-outline-variant/60 bg-background-dark flex flex-col shrink-0 overflow-hidden" style={{ width: 300 }}>
          <div className="p-4 border-b border-outline-variant/60">
            <div className="flex items-center justify-between mb-2">
              <span
                className="px-2 py-0.5 rounded text-[10px] font-bold uppercase tracking-wider"
                style={{
                  backgroundColor: `${(nodeConfig[selectedNode.type] ?? nodeConfig.table).color}20`,
                  color: (nodeConfig[selectedNode.type] ?? nodeConfig.table).color,
                }}
              >
                {selectedNode.type}
              </span>
              <button onClick={() => { setSelectedNode(null); setDdl(null); }}
                className="text-on-surface-variant hover:text-on-surface transition-colors">
                <span className="material-symbols-outlined text-lg">close</span>
              </button>
            </div>
            <h2 className="text-base font-bold font-mono text-on-surface break-all">{selectedNode.label}</h2>
            {selectedNode.type === 'topic' && selectedNode.messageCount !== undefined && (
              <p className="text-[11px] text-primary font-mono mt-1">
                {selectedNode.messageCount.toLocaleString()} messages
              </p>
            )}
            <p className="text-[10px] text-outline font-mono mt-0.5">ID: {selectedNode.id}</p>
          </div>

          <div className="flex-1 overflow-y-auto custom-scrollbar p-4 space-y-4">

            {/* Writes to (outgoing) */}
            {outgoingEdges.length > 0 && (
              <section>
                <h3 className="text-[10px] font-bold text-on-surface-variant uppercase tracking-widest mb-2">Writes to</h3>
                <div className="space-y-1.5">
                  {outgoingEdges.map((e, i) => (
                    <button
                      key={i}
                      onClick={() => {
                        const n = nodeById[e.to];
                        if (n) { setSelectedNode(n); setDdl(null); centerOnNode(n.id); }
                      }}
                      className="w-full flex items-center gap-2 text-xs bg-surface-container-high rounded-lg px-3 py-2 hover:bg-surface-container-highest transition-colors text-left"
                    >
                      <span className="material-symbols-outlined text-primary text-sm">arrow_forward</span>
                      <span className="font-mono text-on-surface truncate">
                        {nodeById[e.to]?.label ?? e.to}
                      </span>
                      {e.label && <span className="text-[9px] text-outline shrink-0">({e.label})</span>}
                    </button>
                  ))}
                </div>
              </section>
            )}

            {/* Reads from (incoming) */}
            {incomingEdges.length > 0 && (
              <section>
                <h3 className="text-[10px] font-bold text-on-surface-variant uppercase tracking-widest mb-2">Reads from</h3>
                <div className="space-y-1.5">
                  {incomingEdges.map((e, i) => (
                    <button
                      key={i}
                      onClick={() => {
                        const n = nodeById[e.from];
                        if (n) { setSelectedNode(n); setDdl(null); centerOnNode(n.id); }
                      }}
                      className="w-full flex items-center gap-2 text-xs bg-surface-container-high rounded-lg px-3 py-2 hover:bg-surface-container-highest transition-colors text-left"
                    >
                      <span className="material-symbols-outlined text-on-surface-variant text-sm">arrow_back</span>
                      <span className="font-mono text-on-surface-variant truncate">
                        {nodeById[e.from]?.label ?? e.from}
                      </span>
                      {e.label && <span className="text-[9px] text-outline shrink-0">({e.label})</span>}
                    </button>
                  ))}
                </div>
              </section>
            )}

            {selectedEdges.length === 0 && (
              <p className="text-xs text-outline">No connections</p>
            )}
          </div>

          {/* Only Flink objects have DDL. Topic and query nodes carry a prefixed id
              ("topic_orders", "query_<uuid>") that is not a table name, so the endpoint
              could never resolve them — the footer is hidden entirely for those. */}
          {(selectedNode.type === 'table' || selectedNode.type === 'view') && (
          <div className="p-4 border-t border-outline-variant/60 space-y-3">
            <Button
              variant="outline" className="w-full"
              icon={loadingDdl ? undefined : 'edit_note'} loading={loadingDdl}
              onClick={async () => {
                setLoadingDdl(true); setDdl(null);
                try {
                  const res = await axios.get<string>(
                    `/api/lineage/ddl/${encodeURIComponent(selectedNode.label)}`,
                    { responseType: 'text' }
                  );
                  setDdl(res.data);
                } catch {
                  setDdl('-- Failed to load DDL');
                } finally {
                  setLoadingDdl(false);
                }
              }}
            >
              {loadingDdl ? 'Loading…' : 'View DDL'}
            </Button>
            {ddl && (
              <div className="rounded-lg border border-outline-variant/60 bg-surface-container-low p-3 font-mono text-[11px] text-on-surface leading-relaxed max-h-48 overflow-y-auto custom-scrollbar whitespace-pre-wrap">
                {ddl}
              </div>
            )}
          </div>
          )}
        </aside>
      )}
    </div>
  );
};

export default Lineage;
