import React, { useState, useMemo, useRef, useEffect, useCallback } from 'react';
import axios from 'axios';
import { Button, EmptyState } from '../components/ui';

interface FlowNode {
  id: string;
  label: string;
  type?: string;
  timestamp?: string;
}

interface FlowEdge {
  source: string;
  target: string;
  label?: string;
}

interface StreamFlowResponse {
  nodes: Record<string, string>[];
  edges: Record<string, string>[];
}

function formatTs(tsStr: string | undefined): string {
  if (!tsStr) return '';
  const ts = Number(tsStr);
  if (!ts) return '';
  const diff = Date.now() - ts;
  if (diff < 60_000) return '< 1 min ago';
  if (diff < 3_600_000) return `${Math.floor(diff / 60_000)} min ago`;
  if (diff < 86_400_000) return `${Math.floor(diff / 3_600_000)}h ago`;
  return new Date(ts).toLocaleTimeString(undefined, { hour: '2-digit', minute: '2-digit' });
}

const StreamFlow: React.FC = () => {
  const [messageKey, setMessageKey]         = useState('');
  const [searchPath, setSearchPath]         = useState('');
  const [targetTopics, setTargetTopics]     = useState('');
  const [maxMessages, setMaxMessages]       = useState(50);
  const [timeLimitMinutes, setTimeLimitMinutes] = useState(5);
  const [useRegex, setUseRegex]             = useState(false);
  const [loading, setLoading]               = useState(false);
  const [error, setError]                   = useState<string | null>(null);
  const [nodes, setNodes]                   = useState<FlowNode[]>([]);
  const [edges, setEdges]                   = useState<FlowEdge[]>([]);
  const [hasResult, setHasResult]           = useState(false);

  // Pan & zoom
  const [transform, setTransform]  = useState({ x: 40, y: 40, scale: 1 });
  const svgRef                     = useRef<SVGSVGElement>(null);
  const isPanning                  = useRef(false);
  const lastPos                    = useRef({ x: 0, y: 0 });

  useEffect(() => {
    const el = svgRef.current;
    if (!el) return;
    const handler = (e: WheelEvent) => {
      e.preventDefault();
      const factor = e.deltaY > 0 ? 0.9 : 1.1;
      setTransform(t => ({ ...t, scale: Math.max(0.15, Math.min(4, t.scale * factor)) }));
    };
    el.addEventListener('wheel', handler, { passive: false });
    return () => el.removeEventListener('wheel', handler);
  }, [hasResult]); // re-attach after SVG mounts

  const onMouseDown = useCallback((e: React.MouseEvent<SVGSVGElement>) => {
    isPanning.current = true;
    lastPos.current = { x: e.clientX, y: e.clientY };
    e.currentTarget.style.cursor = 'grabbing';
  }, []);

  const onMouseMove = useCallback((e: React.MouseEvent<SVGSVGElement>) => {
    if (!isPanning.current) return;
    const dx = e.clientX - lastPos.current.x;
    const dy = e.clientY - lastPos.current.y;
    lastPos.current = { x: e.clientX, y: e.clientY };
    setTransform(t => ({ ...t, x: t.x + dx, y: t.y + dy }));
  }, []);

  const onMouseUp = useCallback((e: React.MouseEvent<SVGSVGElement>) => {
    isPanning.current = false;
    e.currentTarget.style.cursor = 'grab';
  }, []);

  const handleRun = async () => {
    if (!messageKey.trim()) return;
    setLoading(true);
    setError(null);
    setTransform({ x: 40, y: 40, scale: 1 });
    try {
      // Bug fix: split on commas AND newlines
      const parsedTopics = targetTopics
        .split(/[,\n]/)
        .map(t => t.trim())
        .filter(Boolean);

      const res = await axios.post<StreamFlowResponse>('/api/stream-flow', {
        messageKey: messageKey.trim(),
        maxMessagesPerTopic: maxMessages,
        searchPath: searchPath.trim() || null,
        timeLimitMinutes,
        useRegex,
        targetTopics: parsedTopics,
      });

      const parsedNodes: FlowNode[] = res.data.nodes.map(n => ({
        id:        n.id    ?? n.label ?? Object.values(n)[0],
        label:     n.label ?? n.id    ?? Object.values(n)[0],
        type:      n.type,
        timestamp: n.timestamp,
      }));
      const parsedEdges: FlowEdge[] = res.data.edges.map(e => ({
        source: e.source ?? e.from ?? Object.values(e)[0],
        target: e.target ?? e.to   ?? Object.values(e)[1],
        label:  e.label,
      }));
      setNodes(parsedNodes);
      setEdges(parsedEdges);
      setHasResult(true);
    } catch {
      setError('Failed to trace stream flow. Ensure the message key exists in the target topics.');
    } finally {
      setLoading(false);
    }
  };

  // Simple layered BFS layout
  const layout = useMemo(() => {
    const empty = { positions: {} as Record<string, { x: number; y: number }>, nodeW: 160, nodeH: 60 };
    if (nodes.length === 0) return empty;

    const inDegree: Record<string, number> = {};
    nodes.forEach(n => { inDegree[n.id] = 0; });
    edges.forEach(e => { inDegree[e.target] = (inDegree[e.target] ?? 0) + 1; });

    const layers: string[][] = [];
    const visited = new Set<string>();
    const queue: string[] = nodes.filter(n => inDegree[n.id] === 0).map(n => n.id);
    if (queue.length === 0 && nodes.length > 0) queue.push(nodes[0].id);

    while (queue.length > 0) {
      const layer: string[] = [];
      const next: string[] = [];
      for (const id of [...queue]) {
        if (!visited.has(id)) {
          visited.add(id);
          layer.push(id);
          edges.filter(e => e.source === id).forEach(e => {
            if (!visited.has(e.target)) next.push(e.target);
          });
        }
      }
      if (layer.length > 0) layers.push(layer);
      queue.length = 0;
      queue.push(...next);
    }
    nodes.forEach(n => { if (!visited.has(n.id)) layers.push([n.id]); });

    const nodeW = 160, nodeH = 60, colGap = 220, rowGap = 90;
    const positions: Record<string, { x: number; y: number }> = {};
    const maxRows = Math.max(...layers.map(l => l.length), 1);
    layers.forEach((layer, col) => {
      const totalH = layer.length * nodeH + (layer.length - 1) * rowGap;
      const startY = (maxRows * (nodeH + rowGap) - totalH) / 2;
      layer.forEach((id, row) => {
        positions[id] = {
          x: col * (nodeW + colGap),
          y: startY + row * (nodeH + rowGap),
        };
      });
    });

    return { positions, nodeW, nodeH };
  }, [nodes, edges]);

  return (
    <div className="flex h-full overflow-hidden">

      {/* ── Config Panel ── */}
      <aside className="w-72 border-r border-outline-variant/60 bg-surface-container-low p-5 flex flex-col gap-5 shrink-0 overflow-y-auto">
        <div>
          <h3 className="text-xs font-bold text-on-surface-variant uppercase tracking-widest mb-4">Stream Flow Tracer</h3>
          <p className="text-xs text-on-surface-variant">Trace a message key across Kafka topics to visualize the data flow pipeline.</p>
        </div>

        <div className="space-y-4">
          {/* Message Key */}
          <div className="space-y-1.5">
            <label className="text-[12px] font-medium text-on-surface-variant">Message Key *</label>
            <input
              type="text"
              value={messageKey}
              onChange={e => setMessageKey(e.target.value)}
              onKeyDown={e => { if (e.key === 'Enter' && !loading && messageKey.trim()) handleRun(); }}
              placeholder="e.g. order_88219"
              className="w-full bg-surface-container-low border border-outline-variant rounded-md px-3 py-2 text-sm font-mono text-on-surface placeholder:text-outline focus:border-primary/60 outline-none"
            />
          </div>

          {/* Search Path */}
          <div className="space-y-1.5">
            <label className="text-[12px] font-medium text-on-surface-variant">Search Path (JSONPath / XPath)</label>
            <input
              type="text"
              value={searchPath}
              onChange={e => setSearchPath(e.target.value)}
              placeholder="e.g. $.orderId"
              className="w-full bg-surface-container-low border border-outline-variant rounded-md px-3 py-2 text-sm font-mono text-on-surface placeholder:text-outline focus:border-primary/60 outline-none"
            />
          </div>

          {/* Target Topics */}
          <div className="space-y-1.5">
            <label className="text-[12px] font-medium text-on-surface-variant">Target Topics</label>
            <textarea
              value={targetTopics}
              onChange={e => setTargetTopics(e.target.value)}
              placeholder={"orders_raw\norders_processed\n..."}
              rows={3}
              className="w-full bg-surface-container-low border border-outline-variant rounded-md px-3 py-2 text-sm font-mono text-on-surface placeholder:text-outline focus:border-primary/60 outline-none resize-none"
            />
            <p className="text-[10px] text-on-surface-variant">One per line or comma-separated. Empty = scan all topics.</p>
          </div>

          {/* Max Messages */}
          <div className="space-y-1.5">
            <label className="text-[12px] font-medium text-on-surface-variant">
              Max Messages / Topic: <span className="text-primary">{maxMessages}</span>
            </label>
            <input
              type="range" min={10} max={500} step={10} value={maxMessages}
              onChange={e => setMaxMessages(Number(e.target.value))}
              className="w-full accent-primary"
            />
          </div>

          {/* Time Limit */}
          <div className="space-y-1.5">
            <label className="text-[12px] font-medium text-on-surface-variant">Time Limit (minutes)</label>
            <input
              type="number" min={1} max={60} value={timeLimitMinutes}
              onChange={e => setTimeLimitMinutes(Number(e.target.value))}
              className="w-full bg-surface-container-low border border-outline-variant rounded-md px-3 py-2 text-sm text-on-surface focus:border-primary/60 outline-none"
            />
          </div>

          {/* Use Regex */}
          <div className="flex items-center justify-between">
            <span className="text-[12px] font-medium text-on-surface-variant">Use Regex</span>
            <button
              role="switch" aria-checked={useRegex} aria-label="Use Regex"
              onClick={() => setUseRegex(!useRegex)}
              className={`relative inline-flex h-5 w-9 items-center rounded-full transition-colors ${useRegex ? 'bg-primary' : 'bg-surface-container-highest'}`}
            >
              <span className={`inline-block h-3.5 w-3.5 transform rounded-full transition-transform ${useRegex ? 'translate-x-[18px] bg-on-primary' : 'translate-x-1 bg-on-surface-variant'}`} />
            </button>
          </div>
        </div>

        <Button
          variant="primary"
          className="mt-auto w-full"
          icon={loading ? undefined : 'route'}
          loading={loading}
          onClick={handleRun}
          disabled={loading || !messageKey.trim()}
        >
          {loading ? 'Tracing…' : 'Trace Flow'}
        </Button>
      </aside>

      {/* ── Graph Area ── */}
      <main className="flex-1 relative bg-background-dark overflow-hidden flex flex-col">

        {/* Stats badge */}
        {hasResult && nodes.length > 0 && (
          <div className="absolute top-4 left-4 z-10 pointer-events-none">
            <div className="flex items-center gap-2 bg-surface-container/90 border border-outline-variant px-3 py-1.5 rounded-full text-xs">
              <span className="material-symbols-outlined text-sm text-primary">route</span>
              <span className="text-on-surface-variant">{nodes.length} topics</span>
              <span className="text-outline">·</span>
              <span className="text-on-surface-variant">{edges.length} edges</span>
            </div>
          </div>
        )}

        {/* Zoom controls */}
        {hasResult && nodes.length > 0 && (
          <div className="absolute bottom-6 left-4 z-10 flex flex-col bg-background-dark border border-outline-variant rounded-xl overflow-hidden shadow-xl">
            <button onClick={() => setTransform(t => ({ ...t, scale: Math.min(4, t.scale * 1.25) }))}
              className="p-2 hover:bg-surface-container-high text-on-surface-variant hover:text-on-surface border-b border-outline-variant/60 transition-colors">
              <span className="material-symbols-outlined text-lg">add</span>
            </button>
            <button onClick={() => setTransform(t => ({ ...t, scale: Math.max(0.15, t.scale * 0.8) }))}
              className="p-2 hover:bg-surface-container-high text-on-surface-variant hover:text-on-surface border-b border-outline-variant/60 transition-colors">
              <span className="material-symbols-outlined text-lg">remove</span>
            </button>
            <button onClick={() => setTransform({ x: 40, y: 40, scale: 1 })}
              className="p-2 hover:bg-surface-container-high text-on-surface-variant hover:text-on-surface transition-colors" title="Reset view">
              <span className="material-symbols-outlined text-lg">center_focus_weak</span>
            </button>
          </div>
        )}

        {/* Error */}
        {error && (
          <div className="absolute top-4 left-1/2 -translate-x-1/2 z-10 bg-error/10 border border-error/20 text-error text-xs px-4 py-2 rounded-lg flex items-center gap-2">
            <span className="material-symbols-outlined text-sm">warning</span>
            {error}
          </div>
        )}

        {/* Empty state */}
        {!hasResult && !loading && (
          <div className="flex-1 flex items-center justify-center">
            <EmptyState
              icon="waves"
              title="No flow traced yet"
              description="Enter a message key and click Trace Flow to visualize the stream pipeline."
            />
          </div>
        )}

        {/* Loading */}
        {loading && (
          <div className="flex-1 flex flex-col items-center justify-center gap-3" role="status" aria-live="polite">
            <span aria-hidden="true" className="material-symbols-outlined animate-spin text-[32px] text-primary">progress_activity</span>
            <p className="text-[13px] text-on-surface-variant">Tracing message across topics…</p>
          </div>
        )}

        {/* SVG Graph with pan/zoom */}
        {hasResult && !loading && nodes.length > 0 && (
          <svg
            ref={svgRef}
            className="w-full h-full select-none"
            style={{ cursor: 'grab' }}
            onMouseDown={onMouseDown}
            onMouseMove={onMouseMove}
            onMouseUp={onMouseUp}
            onMouseLeave={onMouseUp}
          >
            <defs>
              <marker id="sf-arrow" markerWidth="10" markerHeight="7" refX="9" refY="3.5" orient="auto">
                <polygon points="0 0, 10 3.5, 0 7" fill="#a3adff" opacity="0.6" />
              </marker>
            </defs>

            <g transform={`translate(${transform.x}, ${transform.y}) scale(${transform.scale})`}>

              {/* Edges */}
              {edges.map((edge, i) => {
                const s = layout.positions[edge.source];
                const t = layout.positions[edge.target];
                if (!s || !t) return null;
                const { nodeW, nodeH } = layout;
                const x1 = s.x + nodeW, y1 = s.y + nodeH / 2;
                const x2 = t.x,          y2 = t.y + nodeH / 2;
                const mx = (x1 + x2) / 2;
                return (
                  <g key={i}>
                    <path
                      d={`M${x1},${y1} C${mx},${y1} ${mx},${y2} ${x2},${y2}`}
                      fill="none" stroke="#a3adff" strokeWidth="2" opacity="0.5"
                      markerEnd="url(#sf-arrow)"
                    />
                    {edge.label && (
                      <text x={mx} y={Math.min(y1, y2) - 6} textAnchor="middle" fill="#9aa3b2" fontSize="10">
                        {edge.label}
                      </text>
                    )}
                  </g>
                );
              })}

              {/* Nodes */}
              {nodes.map(node => {
                const pos = layout.positions[node.id];
                if (!pos) return null;
                const { nodeW, nodeH } = layout;
                const cx = nodeW / 2;
                const ts = formatTs(node.timestamp);
                return (
                  <g key={node.id} transform={`translate(${pos.x}, ${pos.y})`}>
                    <rect width={nodeW} height={nodeH} rx="8"
                      fill="#12151a" stroke="#a3adff" strokeWidth="1.5" strokeOpacity="0.6" />
                    <text x={cx} y={ts ? nodeH / 2 - 8 : nodeH / 2 + 4}
                      textAnchor="middle" fill="white" fontSize="11"
                      fontFamily="JetBrains Mono, monospace" fontWeight="bold">
                      {node.label.length > 18 ? node.label.slice(0, 17) + '…' : node.label}
                    </text>
                    {ts && (
                      <text x={cx} y={nodeH / 2 + 10} textAnchor="middle"
                        fill="#a3adff" fontSize="9" fontFamily="Inter, sans-serif" opacity="0.7">
                        {ts}
                      </text>
                    )}
                  </g>
                );
              })}
            </g>
          </svg>
        )}

        {/* No result */}
        {hasResult && !loading && nodes.length === 0 && (
          <div className="flex-1 flex flex-col items-center justify-center gap-3 text-center p-12">
            <span className="material-symbols-outlined text-5xl text-outline">search_off</span>
            <div>
              <p className="font-bold text-on-surface-variant">No flow found</p>
              <p className="text-sm text-outline mt-1">The message key was not found in the specified topics.</p>
            </div>
          </div>
        )}
      </main>
    </div>
  );
};

export default StreamFlow;
