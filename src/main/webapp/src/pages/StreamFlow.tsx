import React, { useState, useMemo, useRef, useEffect, useCallback } from 'react';
import { Link, useLocation, useNavigate } from 'react-router-dom';
import axios from 'axios';
import {
  Badge, Button, EmptyState, Field, Input, NumberInput, Select, TopicInput,
  Table, TableBody, TableHead, TableRow, Td, Th,
} from '../components/ui';
import { useToast } from '../components/Toast';
import { describeApiError, type QueryErrorInfo } from './queryError';
import { toCsv } from './resultExport';
import {
  buildLayout, buildTraceQuery, clampScale, describeCoverage, describeSearchScope, fitTransform,
  formatAbsoluteTime, formatLatency, formatRelativeTime, hitsToRows, HIT_EXPORT_COLUMNS,
  parseFlowResponse, parseTraceParams, pushTraceHistory, readTraceHistory, traceToJson,
  validateSearchPath, zoomAt,
  type FlowHit, type FormErrors, type ParsedFlow, type TraceHistoryEntry,
  type TraceParams, type Transform,
} from './streamFlow';

/** Filet de sécurité côté client : le backend borne déjà la trace (explorer.stream-flow-timeout-ms). */
const REQUEST_TIMEOUT_MS = 120_000;

const EMPTY_FLOW: ParsedFlow = { nodes: [], edges: [], hits: [], stats: null, warnings: [] };

/** Interrupteur du panneau de critères — trois options identiques, un seul rendu. */
const Toggle: React.FC<{ label: string; hint?: string; checked: boolean; onChange: (v: boolean) => void }> =
  ({ label, hint, checked, onChange }) => (
    <div className="flex items-center justify-between gap-2">
      <span className="text-[12px] font-medium text-on-surface-variant" title={hint}>{label}</span>
      <button
        type="button"
        role="switch" aria-checked={checked} aria-label={label}
        onClick={() => onChange(!checked)}
        className={`relative inline-flex h-5 w-9 shrink-0 items-center rounded-full transition-colors ${checked ? 'bg-primary' : 'bg-surface-container-highest'}`}
      >
        <span className={`inline-block h-3.5 w-3.5 transform rounded-full transition-transform ${checked ? 'translate-x-[18px] bg-on-primary' : 'translate-x-1 bg-on-surface-variant'}`} />
      </button>
    </div>
  );

const StreamFlow: React.FC = () => {
  const location = useLocation();
  const navigate = useNavigate();
  const { toast } = useToast();

  // Le formulaire s'initialise depuis l'URL : une trace se partage telle quelle.
  const initial = useMemo(() => parseTraceParams(location.search), []); // eslint-disable-line react-hooks/exhaustive-deps

  const [messageKey, setMessageKey]         = useState(initial.messageKey);
  const [searchPath, setSearchPath]         = useState(initial.searchPath);
  /** Topics cibles saisis un par un, au lieu d'un textarea libre « un par ligne ». */
  const [selectedTopics, setSelectedTopics] = useState<string[]>(initial.topics);
  const [topicDraft, setTopicDraft]         = useState('');
  const [fieldErrors, setFieldErrors]       = useState<FormErrors>({});
  const [maxMessages, setMaxMessages]       = useState(initial.maxMessages);
  /** « recent » = derniers messages de chaque topic ; « window » = fenêtre temporelle. */
  const [windowMode, setWindowMode]         = useState(initial.windowMode);
  const [timeLimitMinutes, setTimeLimitMinutes] = useState(initial.timeLimitMinutes);
  const [useRegex, setUseRegex]             = useState(initial.useRegex);
  const [caseSensitive, setCaseSensitive]   = useState(initial.caseSensitive);
  const [searchHeaders, setSearchHeaders]   = useState(initial.searchHeaders);

  const [loading, setLoading]               = useState(false);
  const [error, setError]                   = useState<QueryErrorInfo | null>(null);
  const [notice, setNotice]                 = useState<string | null>(null);
  const [flow, setFlow]                     = useState<ParsedFlow>(EMPTY_FLOW);
  const [hasResult, setHasResult]           = useState(false);
  const [selectedTopic, setSelectedTopic]   = useState<string | null>(null);
  const [detailsOpen, setDetailsOpen]       = useState(true);
  const [history, setHistory]               = useState<TraceHistoryEntry[]>(() => readTraceHistory());
  const [historyOpen, setHistoryOpen]       = useState(false);

  const messageKeyRef = useRef<HTMLInputElement>(null);
  const searchPathRef = useRef<HTMLInputElement>(null);
  /** Requête en vol — annulable, une trace peut légitimement durer une minute. */
  const abortRef      = useRef<AbortController | null>(null);

  // Pan & zoom
  const [transform, setTransform] = useState<Transform>({ x: 48, y: 48, scale: 1 });
  const svgRef                    = useRef<SVGSVGElement>(null);
  const isPanning                 = useRef(false);
  const lastPos                   = useRef({ x: 0, y: 0 });
  /** Un glissé ne doit pas se terminer en clic sur le nœud relâché. */
  const dragged                   = useRef(false);

  const { nodes, edges, hits, stats, warnings } = flow;
  const layout = useMemo(() => buildLayout(nodes, edges), [nodes, edges]);

  const currentParams = useCallback((): TraceParams => ({
    messageKey: messageKey.trim(),
    searchPath: searchPath.trim(),
    topics: selectedTopics,
    windowMode,
    timeLimitMinutes,
    maxMessages,
    useRegex,
    caseSensitive,
    searchHeaders,
  }), [messageKey, searchPath, selectedTopics, windowMode, timeLimitMinutes, maxMessages,
    useRegex, caseSensitive, searchHeaders]);

  const fitView = useCallback(() => {
    const el = svgRef.current;
    if (!el) return;
    const box = el.getBoundingClientRect();
    setTransform(fitTransform(layout, { width: box.width, height: box.height }));
  }, [layout]);

  // Le graphe est cadré dès son arrivée : un « reset » à translate(40,40) scale(1) laissait
  // la moitié d'une chaîne de sept topics hors écran.
  useEffect(() => {
    if (nodes.length > 0) fitView();
  }, [nodes, fitView]);

  useEffect(() => {
    const el = svgRef.current;
    if (!el) return;
    const handler = (e: WheelEvent) => {
      e.preventDefault();
      const box = el.getBoundingClientRect();
      const factor = e.deltaY > 0 ? 0.9 : 1.1;
      setTransform(t => zoomAt(t, factor, e.clientX - box.left, e.clientY - box.top));
    };
    el.addEventListener('wheel', handler, { passive: false });
    return () => el.removeEventListener('wheel', handler);
  }, [hasResult, nodes.length]); // re-attach after the SVG mounts

  useEffect(() => () => abortRef.current?.abort(), []);

  const onMouseDown = useCallback((e: React.MouseEvent<SVGSVGElement>) => {
    isPanning.current = true;
    dragged.current = false;
    lastPos.current = { x: e.clientX, y: e.clientY };
    e.currentTarget.style.cursor = 'grabbing';
  }, []);

  const onMouseMove = useCallback((e: React.MouseEvent<SVGSVGElement>) => {
    if (!isPanning.current) return;
    const dx = e.clientX - lastPos.current.x;
    const dy = e.clientY - lastPos.current.y;
    if (Math.abs(dx) + Math.abs(dy) > 2) dragged.current = true;
    lastPos.current = { x: e.clientX, y: e.clientY };
    setTransform(t => ({ ...t, x: t.x + dx, y: t.y + dy }));
  }, []);

  const onMouseUp = useCallback((e: React.MouseEvent<SVGSVGElement>) => {
    isPanning.current = false;
    e.currentTarget.style.cursor = 'grab';
  }, []);

  const clearFieldError = (key: keyof FormErrors) =>
    setFieldErrors(prev => (prev[key] ? { ...prev, [key]: undefined } : prev));

  /** Valide le brouillon puis l'ajoute à la liste ; ignore les doublons et les vides. */
  const addTopic = () => {
    const topic = topicDraft.trim();
    if (!topic) return;
    setSelectedTopics(list => (list.includes(topic) ? list : [...list, topic]));
    setTopicDraft('');
  };

  const cancelRun = () => {
    abortRef.current?.abort();
    abortRef.current = null;
  };

  const runTrace = useCallback(async (params: TraceParams) => {
    const errors: FormErrors = {};
    if (!params.messageKey) errors.messageKey = 'A message key is required.';
    const pathError = validateSearchPath(params.searchPath);
    if (pathError) errors.searchPath = pathError;
    setFieldErrors(errors);
    if (errors.messageKey) { messageKeyRef.current?.focus(); return; }
    if (errors.searchPath) { searchPathRef.current?.focus(); return; }

    const controller = new AbortController();
    abortRef.current = controller;
    setLoading(true);
    setError(null);
    setNotice(null);
    setSelectedTopic(null);
    try {
      const res = await axios.post('/api/stream-flow', {
        messageKey: params.messageKey,
        maxMessagesPerTopic: params.maxMessages,
        searchPath: params.searchPath || null,
        timeLimitMinutes: params.windowMode === 'window' ? params.timeLimitMinutes : null,
        useRegex: params.useRegex,
        caseSensitive: params.caseSensitive,
        searchHeaders: params.searchHeaders,
        targetTopics: params.topics,
      }, { signal: controller.signal, timeout: REQUEST_TIMEOUT_MS });

      const parsed = parseFlowResponse(res.data);
      setFlow(parsed);
      setDetailsOpen(true);
      setHasResult(true);
      // L'URL suit la trace affichée : elle est copiable à tout moment, pas seulement
      // depuis le bouton de partage.
      navigate({ search: buildTraceQuery(params) }, { replace: true });
      setHistory(pushTraceHistory({ ...params, ranAt: Date.now(), topicsFound: parsed.nodes.length }));
    } catch (err) {
      if (axios.isCancel(err) || (err as { code?: string })?.code === 'ERR_CANCELED') {
        setNotice('Trace cancelled — nothing was changed on the cluster.');
      } else {
        // Surface the real backend cause when there is one (invalid regex, malformed
        // search path, unreachable broker); otherwise fall back to a generic hint.
        setError(describeApiError(err, 'Failed to trace stream flow.'));
        // The previous graph described a different search: keeping it on screen next to a
        // fresh error would present stale coverage as the result of this run.
        setFlow(EMPTY_FLOW);
        setHasResult(false);
      }
    } finally {
      abortRef.current = null;
      setLoading(false);
    }
  }, [navigate]);

  const handleSubmit = () => {
    // Un topic encore dans le champ de saisie compte : on ne le perd pas parce que
    // l'utilisateur a cliqué « Trace » sans valider par Entrée.
    const pending = topicDraft.trim();
    const params = currentParams();
    if (pending && !params.topics.includes(pending)) {
      params.topics = [...params.topics, pending];
      setSelectedTopics(params.topics);
      setTopicDraft('');
    }
    void runTrace(params);
  };

  /** Une trace ouverte depuis un lien partagé s'exécute d'elle-même : c'est ce qu'on partage. */
  const autoRan = useRef(false);
  useEffect(() => {
    if (autoRan.current) return;
    autoRan.current = true;
    if (initial.messageKey.trim()) void runTrace(initial);
  }, [initial, runTrace]);

  const replay = (entry: TraceHistoryEntry) => {
    setMessageKey(entry.messageKey);
    setSearchPath(entry.searchPath);
    setSelectedTopics(entry.topics);
    setWindowMode(entry.windowMode);
    setTimeLimitMinutes(entry.timeLimitMinutes);
    setMaxMessages(entry.maxMessages);
    setUseRegex(entry.useRegex);
    setCaseSensitive(entry.caseSensitive);
    setSearchHeaders(entry.searchHeaders);
    setHistoryOpen(false);
    void runTrace(entry);
  };

  const copyLink = async () => {
    const url = `${window.location.origin}${window.location.pathname}${buildTraceQuery(currentParams())}`;
    try {
      await navigator.clipboard.writeText(url);
      toast('Trace link copied', 'success');
    } catch {
      toast('Could not copy — the browser refused clipboard access', 'error');
    }
  };

  const download = (content: string, mime: string, ext: string) => {
    const blob = new Blob([content], { type: mime });
    const url = URL.createObjectURL(blob);
    const a = document.createElement('a');
    a.href = url;
    a.download = `stream-flow-${messageKey.trim().replace(/[^\w.-]+/g, '_') || 'trace'}.${ext}`;
    a.click();
    URL.revokeObjectURL(url);
    toast(`Exported as ${ext.toUpperCase()}`, 'success');
  };

  const coverage = describeCoverage(stats);
  const hitByTopic = useMemo(() => new Map(hits.map(h => [h.topic, h])), [hits]);
  const scopeHint = describeSearchScope(searchPath, searchHeaders);

  return (
    <div className="flex h-full overflow-hidden">

      {/* ── Config Panel ── */}
      {/* Un vrai <form> : Entrée depuis n'importe quel champ lance la trace, au lieu d'un
          onKeyDown bricolé sur le seul champ « Message Key ». */}
      <form
        noValidate
        onSubmit={e => { e.preventDefault(); handleSubmit(); }}
        aria-label="Stream flow tracer"
        className="w-72 border-r border-outline-variant/60 bg-surface-container-low p-5 flex flex-col gap-5 shrink-0 overflow-y-auto"
      >
        <div>
          <div className="flex items-start justify-between gap-2 mb-4">
            <h3 className="text-xs font-bold text-on-surface-variant uppercase tracking-widest">Stream Flow Tracer</h3>
            {history.length > 0 && (
              <button
                type="button"
                onClick={() => setHistoryOpen(o => !o)}
                aria-expanded={historyOpen}
                className="text-on-surface-variant hover:text-on-surface"
                title="Recent traces"
              >
                <span aria-hidden="true" className="material-symbols-outlined text-[18px]">history</span>
              </button>
            )}
          </div>
          <p className="text-xs text-on-surface-variant">Trace a message key across Kafka topics to visualize the data flow pipeline.</p>
        </div>

        {historyOpen && history.length > 0 && (
          <ul className="space-y-1 border border-outline-variant/60 rounded-md p-1.5" aria-label="Recent traces">
            {history.map(entry => (
              <li key={`${entry.ranAt}`}>
                <button
                  type="button"
                  onClick={() => replay(entry)}
                  className="w-full text-left px-1.5 py-1 rounded hover:bg-surface-container-high"
                >
                  <span className="block font-mono text-[11px] text-on-surface truncate">{entry.messageKey}</span>
                  <span className="block text-[10px] text-outline">
                    {entry.searchPath ? `${entry.searchPath} · ` : ''}
                    {entry.topicsFound} topic{entry.topicsFound === 1 ? '' : 's'} · {formatRelativeTime(entry.ranAt)}
                  </span>
                </button>
              </li>
            ))}
          </ul>
        )}

        <div className="space-y-4">
          {/* Message Key */}
          <Field label={useRegex ? 'Message Key (regex)' : 'Message Key'} required error={fieldErrors.messageKey}>
            {p => (
              <Input
                {...p}
                ref={messageKeyRef}
                className="font-mono"
                value={messageKey}
                onChange={e => { setMessageKey(e.target.value); clearFieldError('messageKey'); }}
                placeholder={useRegex ? 'e.g. order_\\d+' : 'e.g. order_88219'}
                autoComplete="off"
                spellCheck={false}
              />
            )}
          </Field>

          {/* Search Path */}
          <Field
            label="Search Path"
            error={fieldErrors.searchPath}
            description={scopeHint}
          >
            {p => (
              <Input
                {...p}
                ref={searchPathRef}
                className="font-mono"
                value={searchPath}
                onChange={e => { setSearchPath(e.target.value); clearFieldError('searchPath'); }}
                placeholder="order.id · $.orderId · /order/id · header:correlation-id"
                autoComplete="off"
                spellCheck={false}
              />
            )}
          </Field>

          {/* Target Topics — suggérés depuis le catalogue, ajoutés un par un */}
          <div className="space-y-1.5">
            <span className="block text-[12px] font-medium text-on-surface-variant">Target Topics</span>
            <TopicInput
              aria-label="Add a target topic"
              value={topicDraft}
              onChange={setTopicDraft}
              onEnter={addTopic}
              placeholder="Type or pick a topic…"
            />
            {selectedTopics.length > 0 && (
              <ul className="flex flex-wrap gap-1 pt-1">
                {selectedTopics.map(topic => (
                  <li key={topic}>
                    <span className="inline-flex items-center gap-1 rounded-md border border-outline-variant bg-surface-container-high px-1.5 py-0.5 text-[11px] font-mono text-on-surface">
                      {topic}
                      <button
                        type="button"
                        onClick={() => setSelectedTopics(list => list.filter(t => t !== topic))}
                        aria-label={`Remove ${topic}`}
                        className="text-outline hover:text-error"
                      >
                        <span aria-hidden="true" className="material-symbols-outlined text-[13px] align-middle">close</span>
                      </button>
                    </span>
                  </li>
                ))}
              </ul>
            )}
            <p className="text-[10px] text-on-surface-variant">
              {selectedTopics.length === 0
                ? 'None selected — the whole cluster is scanned, one consumer per topic. Naming the topics is much faster.'
                : `${selectedTopics.length} topic(s) will be scanned.`}
            </p>
          </div>

          {/* Fenêtre lue */}
          <Field
            label="Scan window"
            description={windowMode === 'window'
              ? 'Reads forward from the start of the window: on a busy topic the cap can be reached before the newest messages.'
              : 'Reads the newest messages of each topic.'}
          >
            {p => (
              <Select
                {...p}
                value={windowMode}
                onChange={e => setWindowMode(e.target.value as 'recent' | 'window')}
              >
                <option value="recent">Most recent messages</option>
                <option value="window">Time window</option>
              </Select>
            )}
          </Field>

          {windowMode === 'window' && (
            <Field label="Window length (minutes)">
              {p => (
                <NumberInput {...p} min={1} max={1440} fallback={5}
                  value={timeLimitMinutes} onChange={setTimeLimitMinutes} />
              )}
            </Field>
          )}

          {/* Max Messages */}
          <div className="space-y-1.5">
            <label htmlFor="sf-max-messages" className="text-[12px] font-medium text-on-surface-variant">
              Max Messages / Topic: <span className="text-primary">{maxMessages}</span>
            </label>
            <input
              id="sf-max-messages"
              type="range" min={10} max={1000} step={10} value={maxMessages}
              onChange={e => setMaxMessages(Number(e.target.value))}
              className="w-full accent-primary"
            />
          </div>

          <div className="space-y-2.5 pt-1">
            <Toggle label="Use Regex" checked={useRegex} onChange={setUseRegex}
              hint="Treat the message key as a regular expression." />
            <Toggle label="Case sensitive" checked={caseSensitive} onChange={setCaseSensitive}
              hint="Off by default, like the topic search." />
            <Toggle label="Search headers" checked={searchHeaders} onChange={setSearchHeaders}
              hint="Also match Kafka header values — correlation ids often travel only there." />
          </div>
        </div>

        <div className="mt-auto space-y-2">
          <Button
            type="submit"
            variant="primary"
            className="w-full"
            icon={loading ? undefined : 'route'}
            loading={loading}
            disabled={loading || !messageKey.trim()}
          >
            {loading ? 'Tracing…' : 'Trace Flow'}
          </Button>
          {loading && (
            <Button type="button" variant="outline" className="w-full" icon="close" onClick={cancelRun}>
              Cancel
            </Button>
          )}
        </div>
      </form>

      {/* ── Graph Area ── */}
      <main className="flex-1 relative bg-background-dark overflow-hidden flex flex-col min-w-0">

        <div className="relative flex-1 min-h-0">

          {/* Stats badge */}
          {hasResult && nodes.length > 0 && (
            <div className="absolute top-4 left-4 z-10 pointer-events-none">
              <div className="flex items-center gap-2 bg-surface-container/90 border border-outline-variant px-3 py-1.5 rounded-full text-xs">
                <span aria-hidden="true" className="material-symbols-outlined text-sm text-primary">route</span>
                <span className="text-on-surface-variant">{nodes.length} topics</span>
                <span className="text-outline">·</span>
                <span className="text-on-surface-variant">{stats?.matches ?? 0} matches</span>
                {stats?.truncated && (
                  <>
                    <span className="text-outline">·</span>
                    <span className="text-warning" title="At least one topic was not scanned to the end — older matches may exist.">partial scan</span>
                  </>
                )}
              </div>
            </div>
          )}

          {/* Partage & export */}
          {hasResult && (
            <div className="absolute top-4 right-4 z-10 flex items-center gap-1">
              <Button size="sm" variant="ghost" icon="link" onClick={() => void copyLink()} title="Copy a link that reruns this exact trace">
                Link
              </Button>
              {hits.length > 0 && (
                <>
                  <Button size="sm" variant="ghost" icon="download"
                    onClick={() => download(toCsv(HIT_EXPORT_COLUMNS, hitsToRows(hits)), 'text/csv', 'csv')}>
                    CSV
                  </Button>
                  <Button size="sm" variant="ghost" icon="download"
                    onClick={() => download(traceToJson(currentParams(), flow), 'application/json', 'json')}
                    title="Criterion, coverage, warnings and hops">
                    JSON
                  </Button>
                </>
              )}
            </div>
          )}

          {/* Zoom controls */}
          {hasResult && nodes.length > 0 && (
            <div className="absolute bottom-6 left-4 z-10 flex flex-col bg-background-dark border border-outline-variant rounded-xl overflow-hidden shadow-xl">
              <button type="button" aria-label="Zoom in"
                onClick={() => setTransform(t => ({ ...t, scale: clampScale(t.scale * 1.25) }))}
                className="p-2 hover:bg-surface-container-high text-on-surface-variant hover:text-on-surface border-b border-outline-variant/60 transition-colors">
                <span aria-hidden="true" className="material-symbols-outlined text-lg">add</span>
              </button>
              <button type="button" aria-label="Zoom out"
                onClick={() => setTransform(t => ({ ...t, scale: clampScale(t.scale * 0.8) }))}
                className="p-2 hover:bg-surface-container-high text-on-surface-variant hover:text-on-surface border-b border-outline-variant/60 transition-colors">
                <span aria-hidden="true" className="material-symbols-outlined text-lg">remove</span>
              </button>
              <button type="button" aria-label="Fit graph to view" onClick={fitView}
                className="p-2 hover:bg-surface-container-high text-on-surface-variant hover:text-on-surface transition-colors" title="Fit to view">
                <span aria-hidden="true" className="material-symbols-outlined text-lg">center_focus_weak</span>
              </button>
            </div>
          )}

          {/* Error */}
          {error && (
            <div className="absolute top-4 left-1/2 -translate-x-1/2 z-10 max-w-md bg-error/10 border border-error/20 text-error text-xs px-4 py-2 rounded-lg flex items-start gap-2" role="alert" title={error.raw}>
              <span aria-hidden="true" className="material-symbols-outlined text-sm mt-0.5 shrink-0">warning</span>
              <span className="min-w-0">
                <span className="font-semibold">{error.title}</span>
                {error.hint && <span className="block text-error/80 mt-0.5 leading-relaxed">{error.hint}</span>}
              </span>
            </div>
          )}

          {/* Notice (annulation) */}
          {notice && !error && (
            <div className="absolute top-4 left-1/2 -translate-x-1/2 z-10 max-w-md bg-surface-container border border-outline-variant text-on-surface-variant text-xs px-4 py-2 rounded-lg" role="status">
              {notice}
            </div>
          )}

          {/* Empty state */}
          {!hasResult && !loading && (
            <div className="h-full flex items-center justify-center">
              <EmptyState
                icon="waves"
                title="No flow traced yet"
                description="Enter a message key and click Trace Flow to visualize the stream pipeline."
              />
            </div>
          )}

          {/* Loading */}
          {loading && (
            <div className="h-full flex flex-col items-center justify-center gap-3" role="status" aria-live="polite">
              <span aria-hidden="true" className="material-symbols-outlined animate-spin text-[32px] text-primary">progress_activity</span>
              <p className="text-[13px] text-on-surface-variant">Tracing message across topics…</p>
              <p className="text-[11px] text-outline">Scanning up to {maxMessages} messages per topic. Cancel any time.</p>
            </div>
          )}

          {/* SVG Graph with pan/zoom */}
          {hasResult && !loading && nodes.length > 0 && (
            <svg
              ref={svgRef}
              className="w-full h-full select-none"
              style={{ cursor: 'grab' }}
              role="img"
              aria-label={`Message flow through ${nodes.map(n => n.label).join(', then ')}`}
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
                    <g key={`${edge.source}->${edge.target}-${i}`}>
                      <path
                        d={`M${x1},${y1} C${mx},${y1} ${mx},${y2} ${x2},${y2}`}
                        fill="none" stroke="#a3adff" strokeWidth="2" opacity="0.5"
                        markerEnd="url(#sf-arrow)"
                      />
                      {edge.label && (
                        <text x={mx} y={Math.min(y1, y2) - 8} textAnchor="middle" fill="#9aa3b2" fontSize="10">
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
                  const ts = formatRelativeTime(node.timestamp);
                  const selected = selectedTopic === node.id;
                  const capped = hitByTopic.get(node.id)?.occurrencesCapped;
                  return (
                    <g
                      key={node.id}
                      transform={`translate(${pos.x}, ${pos.y})`}
                      style={{ cursor: 'pointer' }}
                      onClick={() => { if (!dragged.current) setSelectedTopic(node.id); }}
                    >
                      <title>{`${node.label} — ${node.hits ?? 0}${capped ? '+' : ''} match(es), first seen ${formatAbsoluteTime(node.timestamp)}`}</title>
                      <rect width={nodeW} height={nodeH} rx="8"
                        fill={selected ? '#1b2030' : '#12151a'} stroke="#a3adff"
                        strokeWidth={selected ? 2.5 : 1.5} strokeOpacity={selected ? 1 : 0.6} />
                      <text x={cx} y={ts ? nodeH / 2 - 8 : nodeH / 2 + 4}
                        textAnchor="middle" fill="white" fontSize="11"
                        fontFamily="JetBrains Mono, monospace" fontWeight="bold">
                        {node.label.length > 20 ? node.label.slice(0, 19) + '…' : node.label}
                      </text>
                      {ts && (
                        <text x={cx} y={nodeH / 2 + 10} textAnchor="middle"
                          fill="#a3adff" fontSize="9" fontFamily="Inter, sans-serif" opacity="0.7">
                          {ts}{node.hits ? ` · ${node.hits}${capped ? '+' : ''} match${node.hits > 1 || capped ? 'es' : ''}` : ''}
                        </text>
                      )}
                    </g>
                  );
                })}
              </g>
            </svg>
          )}

          {/* No result — dit ce qui a été lu, sinon « introuvable » se confond avec « hors fenêtre » */}
          {hasResult && !loading && nodes.length === 0 && (
            <div className="h-full flex flex-col items-center justify-center gap-3 text-center p-12">
              <span aria-hidden="true" className="material-symbols-outlined text-5xl text-outline">search_off</span>
              <div className="max-w-lg">
                <p className="font-bold text-on-surface-variant">No flow found</p>
                <p className="text-sm text-outline mt-1">
                  The key was not in what was scanned{coverage ? ` — ${coverage}.` : '.'}
                </p>
                <p className="text-xs text-outline mt-2">
                  Widen the scan window, raise the per-topic cap, or check the search path.
                </p>
              </div>
            </div>
          )}
        </div>

        {/* ── Coverage, warnings & evidence ── */}
        {hasResult && !loading && (
          <section className="border-t border-outline-variant/60 bg-surface-container-low shrink-0 max-h-[45%] overflow-y-auto">
            <div className="flex items-center gap-3 px-4 py-2 sticky top-0 bg-surface-container-low z-10 border-b border-outline-variant/40">
              <button
                type="button"
                onClick={() => setDetailsOpen(o => !o)}
                aria-expanded={detailsOpen}
                className="flex items-center gap-1.5 text-[12px] font-semibold text-on-surface-variant hover:text-on-surface"
              >
                <span aria-hidden="true" className="material-symbols-outlined text-base">
                  {detailsOpen ? 'expand_more' : 'chevron_right'}
                </span>
                Matches ({hits.length})
              </button>
              <span className="text-[11px] text-outline truncate" title={coverage}>{coverage}</span>
              {warnings.length > 0 && (
                <Badge tone="warning" className="ml-auto shrink-0">
                  {warnings.length} note{warnings.length > 1 ? 's' : ''}
                </Badge>
              )}
            </div>

            {detailsOpen && (
              <div className="p-4 space-y-4">
                {warnings.length > 0 && (
                  <ul className="space-y-1.5" aria-label="Scan warnings">
                    {warnings.map((warning, i) => (
                      <li key={i} className="flex items-start gap-2 text-[11px] text-warning leading-relaxed">
                        <span aria-hidden="true" className="material-symbols-outlined text-[14px] mt-0.5 shrink-0">info</span>
                        <span>{warning}</span>
                      </li>
                    ))}
                  </ul>
                )}

                {hits.length > 0 && (
                  <Table rowCount={hits.length} scrollThreshold={12} maxBodyHeight="18rem">
                    <TableHead>
                      <tr>
                        <Th>#</Th>
                        <Th>Topic</Th>
                        <Th>Matches</Th>
                        <Th>First seen</Th>
                        <Th>Δ from previous</Th>
                        <Th>Partition / Offset</Th>
                        <Th>Preview</Th>
                      </tr>
                    </TableHead>
                    <TableBody>
                      {hits.map((hit: FlowHit, i: number) => (
                        <TableRow
                          key={hit.topic}
                          onClick={() => setSelectedTopic(hit.topic)}
                          className={selectedTopic === hit.topic ? 'bg-primary/10 cursor-pointer' : 'cursor-pointer'}
                        >
                          <Td className="text-outline">{i + 1}</Td>
                          <Td>
                            <Link
                              to={`/topic/${encodeURIComponent(hit.topic)}`}
                              className="font-mono text-[12px] text-primary hover:underline"
                              onClick={e => e.stopPropagation()}
                            >
                              {hit.topic}
                            </Link>
                          </Td>
                          <Td title={hit.occurrencesCapped ? 'More matches exist than the scan kept for this topic' : undefined}>
                            {hit.occurrences}{hit.occurrencesCapped ? '+' : ''}
                          </Td>
                          <Td title={formatAbsoluteTime(hit.firstTimestamp)}>
                            {formatRelativeTime(hit.firstTimestamp) || '—'}
                          </Td>
                          <Td className={hit.latencyFromPreviousMs === null ? 'text-outline' : ''}>
                            {formatLatency(hit.latencyFromPreviousMs)}
                          </Td>
                          <Td className="font-mono text-[11px] text-on-surface-variant">
                            {hit.firstPartition} / {hit.firstOffset}
                          </Td>
                          <Td className="font-mono text-[11px] text-on-surface-variant max-w-md truncate" title={hit.preview ?? ''}>
                            {hit.preview ?? '—'}
                          </Td>
                        </TableRow>
                      ))}
                    </TableBody>
                  </Table>
                )}

                {selectedTopic && hitByTopic.get(selectedTopic)?.firstKey && (
                  <p className="text-[11px] text-on-surface-variant">
                    Record key in <span className="font-mono">{selectedTopic}</span>:{' '}
                    <span className="font-mono text-on-surface">{hitByTopic.get(selectedTopic)?.firstKey}</span>
                  </p>
                )}
              </div>
            )}
          </section>
        )}
      </main>
    </div>
  );
};

export default StreamFlow;
