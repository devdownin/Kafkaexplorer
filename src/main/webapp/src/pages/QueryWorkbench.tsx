import React, { useState, useEffect, useRef, useMemo, useCallback } from 'react';
import { useLocation } from 'react-router-dom';
import Editor, { useMonaco } from '@monaco-editor/react';
import type { editor, languages } from 'monaco-editor';
import axios from 'axios';
import { useToast } from '../components/Toast';
import { Button, Badge, Input, Select, EmptyState, useConfirm, cn } from '../components/ui';

/** Contrôle segmenté compact (mode d'exécution, offset). */
function Segmented<T extends string>({ value, onChange, options, ariaLabel }: {
  value: T; onChange: (v: T) => void; options: { value: T; label: string }[]; ariaLabel: string;
}) {
  return (
    <div role="group" aria-label={ariaLabel} className="inline-flex bg-surface-container-low border border-outline-variant rounded-md p-0.5">
      {options.map(o => (
        <button
          key={o.value}
          onClick={() => onChange(o.value)}
          aria-pressed={value === o.value}
          className={cn(
            'px-3 h-7 text-[12px] font-medium rounded transition-colors',
            value === o.value ? 'bg-surface-container-highest text-on-surface' : 'text-on-surface-variant hover:text-on-surface',
          )}
        >
          {o.label}
        </button>
      ))}
    </div>
  );
}

interface SchemaInfo { topics: string[]; tables: string[]; health: boolean; }
interface QueryResult { queryId: string; columns: string[]; rows: Record<string, unknown>[]; error: string | null; tableRegistered?: boolean; engine?: string; }
interface FlinkJobSubmission {
  queryId: string;
  flinkJobId: string;
  statementType: string;
  status: string;
  sql: string;
  startedAt: number;
  endedAt: number | null;
  cancelRequested: boolean;
}
interface Tab { id: string; name: string; sql: string; }
interface SavedQuery { id: string; name: string; sql: string; savedAt: number; }
type ExecutionMode = 'SYNC_READ' | 'ASYNC_JOB';

const DEFAULT_LIMIT = 50;
let tabCounter = 1;
const newTab = (sql = ''): Tab => ({ id: String(++tabCounter), name: `Query ${tabCounter}`, sql });
const detectStatementType = (sql: string) => {
  const stripped = sql.replace(/\/\*[\s\S]*?\*\//g, ' ').replace(/--[^\n]*/g, '').trim().toUpperCase();
  if (stripped.startsWith('INSERT INTO')) return 'INSERT';
  if (stripped.startsWith('CREATE TABLE')) return 'CREATE_TABLE';
  if (stripped.startsWith('SELECT')) return 'SELECT';
  if (stripped.startsWith('EXPLAIN')) return 'EXPLAIN';
  return stripped.split(/\s+/, 1)[0] ?? 'UNKNOWN';
};

const QueryWorkbench: React.FC = () => {
  const { toast } = useToast();
  const confirm = useConfirm();
  const location = useLocation();

  // ── Schema state ──────────────────────────────────────────────────────────────
  const [schema, setSchema] = useState<SchemaInfo | null>(null);
  const [schemaLoading, setSchemaLoading] = useState(false);
  const [expandedTables, setExpandedTables] = useState<Record<string, boolean>>({});
  const [tableSchemas, setTableSchemas] = useState<Record<string, Record<string, string>>>({});
  const schemaRef = useRef<SchemaInfo | null>(null);
  const tableSchemasRef = useRef<Record<string, Record<string, string>>>({});
  useEffect(() => { schemaRef.current = schema; }, [schema]);
  useEffect(() => { tableSchemasRef.current = tableSchemas; }, [tableSchemas]);

  // ── Tabs ──────────────────────────────────────────────────────────────────────
  const defaultSql = "SELECT\n  window_start, window_end, product_id,\n  SUM(quantity) AS total_sales\nFROM orders_stream\nWINDOW TUMBLING (SIZE 5 MINUTES)\nGROUP BY\n  window_start, window_end, product_id\nEMIT CHANGES;";
  const urlSql = new URLSearchParams(location.search).get('sql');
  const [tabs, setTabs] = useState<Tab[]>([{ id: '1', name: 'Query 1', sql: urlSql ? decodeURIComponent(urlSql) : defaultSql }]);
  const [activeTabId, setActiveTabId] = useState('1');
  const [renamingTabId, setRenamingTabId] = useState<string | null>(null);
  const [renamingName, setRenamingName] = useState('');

  const activeTab = tabs.find(t => t.id === activeTabId) ?? tabs[0];
  const sql = activeTab.sql;
  const setSql = useCallback((newSql: string) => {
    setTabs(prev => prev.map(t => t.id === activeTabId ? { ...t, sql: newSql } : t));
  }, [activeTabId]);

  const addTab = () => {
    const t = newTab();
    setTabs(prev => [...prev, t]);
    setActiveTabId(t.id);
  };

  const closeTab = (id: string, e: React.MouseEvent) => {
    e.stopPropagation();
    setTabs(prev => {
      if (prev.length === 1) return prev;
      const next = prev.filter(t => t.id !== id);
      if (activeTabId === id) setActiveTabId(next[Math.max(0, prev.findIndex(t => t.id === id) - 1)].id);
      return next;
    });
  };

  const startRename = (tab: Tab, e: React.MouseEvent) => {
    e.stopPropagation();
    setRenamingTabId(tab.id);
    setRenamingName(tab.name);
  };

  const commitRename = () => {
    if (renamingTabId && renamingName.trim()) {
      setTabs(prev => prev.map(t => t.id === renamingTabId ? { ...t, name: renamingName.trim() } : t));
    }
    setRenamingTabId(null);
  };

  // ── Named saved queries ───────────────────────────────────────────────────────
  const [savedQueries, setSavedQueries] = useState<SavedQuery[]>(() => {
    try { return JSON.parse(localStorage.getItem('kse:saved-queries') || '[]'); } catch { return []; }
  });
  const [saveInputVisible, setSaveInputVisible] = useState(false);
  const [saveInputName, setSaveInputName] = useState('');

  const persistSaved = (next: SavedQuery[]) => {
    setSavedQueries(next);
    localStorage.setItem('kse:saved-queries', JSON.stringify(next));
  };

  const saveQuery = () => {
    const name = saveInputName.trim() || activeTab.name;
    const entry: SavedQuery = { id: crypto.randomUUID(), name, sql, savedAt: Date.now() };
    persistSaved([entry, ...savedQueries]);
    setSaveInputVisible(false);
    setSaveInputName('');
    toast(`Saved "${name}"`, 'success');
  };

  const deleteSavedQuery = async (id: string) => {
    const q = savedQueries.find(s => s.id === id);
    const ok = await confirm({
      title: 'Delete saved query?',
      description: q ? <>“{q.name}” will be removed from your saved queries.</> : undefined,
      confirmLabel: 'Delete',
      tone: 'danger',
      icon: 'delete',
    });
    if (ok) persistSaved(savedQueries.filter(s => s.id !== id));
  };

  const loadSavedQuery = (q: SavedQuery) => {
    const t = newTab(q.sql);
    t.name = q.name;
    setTabs(prev => [...prev, t]);
    setActiveTabId(t.id);
    toast(`Loaded "${q.name}" in new tab`, 'success');
  };

  // ── Monaco: editor ref + keybindings + providers ──────────────────────────────
  const editorRef = useRef<editor.IStandaloneCodeEditor | null>(null);
  const monaco = useMonaco();
  const runQueryRef = useRef<() => void>(() => {});

  useEffect(() => {
    if (!editorRef.current || !monaco) return;
    editorRef.current.addCommand(monaco.KeyMod.CtrlCmd | monaco.KeyCode.Enter, () => runQueryRef.current());
  }, [monaco]);

  // Auto-completion provider
  useEffect(() => {
    if (!monaco) return;
    const disp = monaco.languages.registerCompletionItemProvider('sql', {
      triggerCharacters: [' ', '\n', '.'],
      provideCompletionItems: (_model, position) => {
        const word = _model.getWordUntilPosition(position);
        const range = { startLineNumber: position.lineNumber, endLineNumber: position.lineNumber, startColumn: word.startColumn, endColumn: word.endColumn };
        const suggestions: languages.CompletionItem[] = [];
        schemaRef.current?.tables.forEach(table => suggestions.push({
          label: table, kind: monaco.languages.CompletionItemKind.Class, insertText: table, range, detail: 'Flink Table',
        }));
        Object.entries(tableSchemasRef.current).forEach(([tableName, cols]) =>
          Object.entries(cols).forEach(([col, type]) => suggestions.push({
            label: col, kind: monaco.languages.CompletionItemKind.Field, insertText: col, range, detail: type, documentation: `${tableName}.${col}`,
          }))
        );
        ['TUMBLE', 'HOP', 'SESSION', 'CUMULATE', 'DESCRIPTOR', 'PROCTIME', 'ROWTIME',
          'WATERMARK', 'EMIT CHANGES', 'INTERVAL', 'OVER', 'PARTITION BY',
          'JSON_VALUE', 'JSON_QUERY', 'JSON_EXISTS'].forEach(kw =>
          suggestions.push({ label: kw, kind: monaco.languages.CompletionItemKind.Keyword, insertText: kw, range })
        );
        return { suggestions };
      },
    });
    return () => disp.dispose();
  }, [monaco]);

  // Hover provider: schema tooltip on table names
  useEffect(() => {
    if (!monaco) return;
    const disp = monaco.languages.registerHoverProvider('sql', {
      provideHover: (_model, position) => {
        const word = _model.getWordAtPosition(position);
        if (!word) return null;
        const tables = schemaRef.current?.tables ?? [];
        if (!tables.includes(word.word)) return null;
        const cols = tableSchemasRef.current[word.word];
        const body = cols
          ? `**Flink Table** \`${word.word}\`\n\n| Column | Type |\n|--------|------|\n${Object.entries(cols).map(([c, t]) => `| \`${c}\` | \`${t}\` |`).join('\n')}`
          : `**Flink Table** \`${word.word}\`\n\n_Expand in sidebar to load schema_`;
        return {
          range: new monaco.Range(position.lineNumber, word.startColumn, position.lineNumber, word.endColumn),
          contents: [{ value: body }],
        };
      },
    });
    return () => disp.dispose();
  }, [monaco]);

  // ── Query state ───────────────────────────────────────────────────────────────
  const [results, setResults] = useState<QueryResult | null>(null);
  const [submittedJob, setSubmittedJob] = useState<FlinkJobSubmission | null>(null);
  const [executing, setExecuting] = useState(false);
  const [executionMs, setExecutionMs] = useState<number | null>(null);
  const [executionMode, setExecutionMode] = useState<ExecutionMode>('SYNC_READ');
  const [offsetMode, setOffsetMode] = useState<'EARLIEST' | 'LATEST'>('EARLIEST');
  const [validationError, setValidationError] = useState<string | null>(null);
  const [sortCol, setSortCol] = useState<string | null>(null);
  const [sortDir, setSortDir] = useState<'asc' | 'desc'>('asc');
  const [showErrorDetails, setShowErrorDetails] = useState(false);
  useEffect(() => { setShowErrorDetails(false); }, [results]);

  const sortedRows = useMemo(() => {
    if (!results?.rows || !sortCol) return results?.rows ?? [];
    return [...results.rows].sort((a, b) => {
      const va = String(a[sortCol] ?? ''), vb = String(b[sortCol] ?? '');
      const cmp = va.localeCompare(vb, undefined, { numeric: true });
      return sortDir === 'asc' ? cmp : -cmp;
    });
  }, [results?.rows, sortCol, sortDir]);

  const [errorSummary, errorDetails] = useMemo(() => {
    if (!results?.error) return [null, null];
    const idx = results.error.indexOf('\n');
    return idx === -1 ? [results.error, null] : [results.error.substring(0, idx), results.error.substring(idx + 1)];
  }, [results?.error]);

  // ── DDL preview ───────────────────────────────────────────────────────────────
  const [ddlPreviewTopic, setDdlPreviewTopic] = useState<string | null>(null);
  const [ddlPreview, setDdlPreview] = useState<string | null>(null);
  const [ddlPreviewLoading, setDdlPreviewLoading] = useState(false);

  const fetchDdlPreview = async (topicName: string) => {
    setDdlPreviewTopic(topicName);
    setDdlPreview(null);
    setDdlPreviewLoading(true);
    try {
      const res = await axios.get<{ ddl?: string; error?: string }>(`/api/query/ddl-preview?topic=${encodeURIComponent(topicName)}`);
      setDdlPreview(res.data.ddl ?? null);
      if (res.data.error) toast(`DDL preview failed: ${res.data.error}`, 'error');
    } catch { toast('Failed to generate DDL preview', 'error'); }
    finally { setDdlPreviewLoading(false); }
  };

  // ── History ───────────────────────────────────────────────────────────────────
  const [showHistory, setShowHistory] = useState(false);
  const [history, setHistory] = useState<{ sql: string; ts: number }[]>(() => {
    try { return JSON.parse(localStorage.getItem('kse:query-history') || '[]'); } catch { return []; }
  });
  const saveToHistory = (sqlStr: string) => {
    const entry = { sql: sqlStr.trim(), ts: Date.now() };
    const next = [entry, ...history.filter(h => h.sql !== entry.sql)].slice(0, 20);
    setHistory(next);
    localStorage.setItem('kse:query-history', JSON.stringify(next));
  };

  // ── Window assistant ──────────────────────────────────────────────────────────
  const [windowType, setWindowType] = useState('Tumbling (Non-overlapping)');
  const [windowSize, setWindowSize] = useState(5);
  const [windowUnit, setWindowUnit] = useState('MIN');

  // ── Resize: split pane (vertical) + sidebar (horizontal) ─────────────────────
  const containerRef = useRef<HTMLDivElement>(null);
  const isDragging = useRef(false);
  const isSidebarDragging = useRef(false);
  const [splitPercent, setSplitPercent] = useState(55);
  const [sidebarWidth, setSidebarWidth] = useState(288);

  useEffect(() => {
    const onMouseMove = (e: MouseEvent) => {
      if (isDragging.current && containerRef.current) {
        const rect = containerRef.current.getBoundingClientRect();
        setSplitPercent(Math.max(20, Math.min(80, ((e.clientY - rect.top) / rect.height) * 100)));
      }
      if (isSidebarDragging.current) {
        setSidebarWidth(Math.max(200, Math.min(480, e.clientX)));
      }
    };
    const onMouseUp = () => {
      isDragging.current = false;
      isSidebarDragging.current = false;
      document.body.style.cursor = '';
    };
    document.addEventListener('mousemove', onMouseMove);
    document.addEventListener('mouseup', onMouseUp);
    return () => { document.removeEventListener('mousemove', onMouseMove); document.removeEventListener('mouseup', onMouseUp); };
  }, []);

  // ── Unsaved changes warning on page unload ────────────────────────────────────
  useEffect(() => {
    const handler = (e: BeforeUnloadEvent) => {
      const hasUnsaved = tabs.some(t => t.sql.trim() !== '' && t.sql !== defaultSql);
      if (hasUnsaved) { e.preventDefault(); }
    };
    window.addEventListener('beforeunload', handler);
    return () => window.removeEventListener('beforeunload', handler);
  }, [tabs]);

  // ── Actions ───────────────────────────────────────────────────────────────────
  // eslint-disable-next-line react-hooks/exhaustive-deps -- fetch once on mount
  useEffect(() => { fetchSchema(); }, []);

  const fetchSchema = async () => {
    setSchemaLoading(true);
    try { const r = await axios.get('/api/query/init'); setSchema(r.data); }
    catch { toast('Failed to load schema', 'error'); }
    finally { setSchemaLoading(false); }
  };

  const toggleTable = async (tableName: string) => {
    const isExpanded = !!expandedTables[tableName];
    setExpandedTables(prev => ({ ...prev, [tableName]: !isExpanded }));
    if (!isExpanded && !tableSchemas[tableName]) {
      try { const r = await axios.get(`/api/query/schema/${tableName}`); setTableSchemas(prev => ({ ...prev, [tableName]: r.data })); }
      catch { toast(`Failed to load schema for ${tableName}`, 'error'); }
    }
  };

  const runQuery = async () => {
    setValidationError(null);
    const statementType = detectStatementType(sql);

    if (executionMode === 'SYNC_READ' && statementType === 'INSERT') {
      setValidationError('INSERT INTO must be submitted in Flink Job mode.');
      return;
    }
    if (executionMode === 'ASYNC_JOB' && statementType !== 'INSERT') {
      setValidationError('Flink Job mode only accepts INSERT INTO statements.');
      return;
    }

    try {
      const vRes = await axios.post<{ valid: boolean; error?: string }>('/api/query/validate', { sql });
      if (!vRes.data.valid) { setValidationError(vRes.data.error ?? 'SQL validation failed'); return; }
    } catch { /* let execution handle it */ }

    setExecuting(true); setResults(null); setSubmittedJob(null); setSortCol(null);
    const start = Date.now();
    try {
      if (executionMode === 'ASYNC_JOB') {
        const response = await axios.post<FlinkJobSubmission>('/api/query/jobs', { sql });
        setExecutionMs(Date.now() - start);
        setSubmittedJob(response.data);
        setResults(null);
        saveToHistory(sql);
        toast(`Streaming job submitted: ${response.data.status}`, 'success');
      } else {
        const readMode = offsetMode === 'LATEST' ? 'latest-offset' : 'earliest-offset';
        const response = await axios.post<QueryResult>('/api/query/run-sync', { sql, readMode });
        setExecutionMs(Date.now() - start);
        setResults(response.data);
        if (!response.data.error) {
          saveToHistory(sql);
          // Refresh the schema browser when:
          // 1. The user explicitly ran a CREATE TABLE statement.
          // 2. The backend auto-registered a Flink table during query execution.
          const strippedForCheck = sql.replace(/\/\*[\s\S]*?\*\//g, ' ').replace(/--[^\n]*/g, '').trim();
          if (strippedForCheck.toUpperCase().startsWith('CREATE TABLE') || response.data.tableRegistered) {
            fetchSchema();
          }
        }
      }
    } catch (error) {
      setExecutionMs(Date.now() - start);
      const message = axios.isAxiosError(error)
        ? String(error.response?.data?.message ?? error.response?.data?.error ?? 'Query execution failed')
        : 'Query execution failed';
      setResults({ queryId: '', columns: [], rows: [], error: message });
      setSubmittedJob(null);
      toast(message, 'error');
    } finally { setExecuting(false); }
  };
  runQueryRef.current = runQuery;

  const handleSortColumn = (col: string) => {
    if (sortCol === col) setSortDir(d => d === 'asc' ? 'desc' : 'asc');
    else { setSortCol(col); setSortDir('asc'); }
  };

  const copyCell = (value: unknown) => {
    const text = typeof value === 'object' ? JSON.stringify(value) : String(value ?? '');
    navigator.clipboard.writeText(text).then(() => toast('Copied', 'success'));
  };

  const exportResults = (format: 'csv' | 'json') => {
    if (!results?.rows.length) return;
    let content: string, mime: string, ext: string;
    if (format === 'json') { content = JSON.stringify(results.rows, null, 2); mime = 'application/json'; ext = 'json'; }
    else {
      const header = results.columns.join(',');
      const rows = results.rows.map(r => results.columns.map(c => JSON.stringify(r[c] ?? '')).join(','));
      content = [header, ...rows].join('\n'); mime = 'text/csv'; ext = 'csv';
    }
    const blob = new Blob([content], { type: mime });
    const url = URL.createObjectURL(blob);
    const a = document.createElement('a');
    a.href = url; a.download = `query-results.${ext}`; a.click();
    URL.revokeObjectURL(url);
    toast(`Exported as ${ext.toUpperCase()}`, 'success');
  };

  const applyWindowLogic = () => {
    const unitMap: Record<string, string> = { MIN: 'MINUTES', SEC: 'SECONDS', HOUR: 'HOURS' };
    const unit = unitMap[windowUnit] || 'MINUTES';
    const fromMatch = sql.match(/\bFROM\s+`?([\w.\\-]+)`?/i);
    const tableInEditor = fromMatch?.[1];
    const tableName = tableInEditor || schema?.tables[0] || 'source_table';
    const newSql = `-- Window: ${windowType}, Size: ${windowSize} ${unit}\nSELECT\n  window_start,\n  window_end,\n  COUNT(*) AS event_count\nFROM TABLE(\n  TUMBLE(TABLE ${tableName}, DESCRIPTOR(event_time), INTERVAL '${windowSize}' ${unit})\n)\nGROUP BY window_start, window_end;`;
    setSql(newSql);
    toast('Window logic applied to editor', 'success');
  };

  const formatMs = (ms: number) => ms < 1000 ? `${ms}ms` : `${(ms / 1000).toFixed(1)}s`;

  // ── Render ────────────────────────────────────────────────────────────────────
  return (
    <div className="flex h-full overflow-hidden">

      {/* ── DDL Preview Modal ────────────────────────────────────────────────── */}
      {ddlPreviewTopic && (
        <div className="fixed inset-0 glass-overlay z-50 flex items-center justify-center p-8"
          role="dialog" aria-modal="true" aria-label="DDL preview"
          onClick={() => setDdlPreviewTopic(null)}>
          <div onClick={e => e.stopPropagation()}
            className="bg-surface-container border border-outline-variant rounded-xl w-full max-w-2xl max-h-[80vh] flex flex-col shadow-2xl">
            <div className="flex items-center justify-between p-4 border-b border-outline-variant">
              <div>
                <h2 className="text-[14px] font-semibold text-on-surface">DDL Preview</h2>
                <p className="text-[11px] text-on-surface-variant font-mono mt-0.5">{ddlPreviewTopic}</p>
              </div>
              <button onClick={() => setDdlPreviewTopic(null)} aria-label="Close"
                className="p-1.5 rounded-md text-on-surface-variant hover:text-on-surface hover:bg-surface-container-high transition-colors">
                <span className="material-symbols-outlined text-[20px]">close</span>
              </button>
            </div>
            <div className="flex-1 overflow-auto p-4">
              {ddlPreviewLoading
                ? <div className="flex items-center gap-2 text-on-surface-variant py-4"><span className="material-symbols-outlined animate-spin text-[16px]">progress_activity</span><span className="text-[13px]">Inferring schema…</span></div>
                : ddlPreview
                  ? <pre className="text-[12px] font-mono text-on-surface whitespace-pre-wrap leading-relaxed">{ddlPreview}</pre>
                  : <p className="text-[13px] text-on-surface-variant">Failed to generate DDL</p>
              }
            </div>
            <div className="p-4 border-t border-outline-variant flex items-center justify-end gap-2">
              <Button variant="outline" size="sm" icon="content_copy" disabled={!ddlPreview}
                onClick={() => { if (ddlPreview) { navigator.clipboard.writeText(ddlPreview); toast('DDL copied', 'success'); } }}>
                Copy
              </Button>
              <Button variant="primary" size="sm" icon="edit_note" disabled={!ddlPreview}
                onClick={() => { if (ddlPreview) { setSql(ddlPreview); setDdlPreviewTopic(null); toast('DDL inserted in editor', 'success'); } }}>
                Insert in editor
              </Button>
            </div>
          </div>
        </div>
      )}

      {/* ── Schema Browser Sidebar (resizable) ──────────────────────────────── */}
      <aside className="relative flex-shrink-0 flex flex-col border-r border-outline-variant/60 bg-surface-container-low" style={{ width: sidebarWidth }}>
        {/* Sidebar drag handle */}
        <div
          onMouseDown={e => { isSidebarDragging.current = true; document.body.style.cursor = 'col-resize'; e.preventDefault(); }}
          className="absolute right-0 top-0 bottom-0 w-1 cursor-col-resize hover:bg-primary/40 transition-colors z-10"
        />

        <div className="p-4 flex items-center gap-3 border-b border-outline-variant/60">
          <div className="size-8 bg-primary/15 text-primary rounded-lg flex items-center justify-center shrink-0">
            <span className="material-symbols-outlined text-[18px]">database</span>
          </div>
          <div className="min-w-0">
            <h1 className="text-[14px] font-semibold tracking-tight text-on-surface">SQL Workbench</h1>
            <p className="text-[11px] text-on-surface-variant">Flink SQL Engine</p>
          </div>
        </div>

        <div className="flex-1 overflow-y-auto custom-scrollbar p-4">
          <div className="flex items-center justify-between mb-4">
            <h2 className="text-[11px] font-medium text-on-surface-variant uppercase tracking-[0.05em]">Schema Browser</h2>
            <button onClick={fetchSchema} disabled={schemaLoading} aria-label="Refresh schema" className="p-1 rounded-md text-on-surface-variant hover:text-on-surface hover:bg-surface-container-high transition-colors disabled:opacity-50" title="Refresh">
              <span className={`material-symbols-outlined text-[18px] ${schemaLoading ? 'animate-spin' : ''}`}>{schemaLoading ? 'progress_activity' : 'refresh'}</span>
            </button>
          </div>

          <div className="space-y-1">
            {/* Flink Tables */}
            <details className="group" open>
              <summary className="flex items-center gap-2 py-1.5 px-2 rounded hover:bg-primary/10 cursor-pointer list-none">
                <span className="material-symbols-outlined text-sm text-primary group-open:rotate-90 transition-transform">chevron_right</span>
                <span className="material-symbols-outlined text-base text-on-surface-variant">grid_view</span>
                <span className="text-sm font-medium">Flink Tables</span>
                <span className="ml-auto text-[10px] bg-surface-container-highest px-1.5 py-0.5 rounded-full text-on-surface-variant tabular-nums">{schema?.tables.length ?? 0}</span>
              </summary>
              <div className="pl-4 pt-1 space-y-0.5">
                {schema?.tables.map(table => (
                  <div key={table}>
                    <div className="flex items-center py-1 px-2 rounded hover:bg-primary/5 transition-colors group/tbl">
                      <div onClick={() => toggleTable(table)} className="flex-1 flex items-center gap-1 min-w-0 cursor-pointer">
                        <span className={`material-symbols-outlined text-xs text-on-surface-variant transition-transform duration-200 shrink-0 ${expandedTables[table] ? 'rotate-90' : ''}`}>chevron_right</span>
                        <span className="text-xs text-on-surface truncate font-mono">{table}</span>
                      </div>
                      <button onClick={() => setSql(`SELECT * FROM ${table} LIMIT 50`)}
                        className="opacity-0 group-hover/tbl:opacity-100 text-outline hover:text-primary transition-all shrink-0 ml-1" title="SELECT from this table">
                        <span className="material-symbols-outlined text-sm">play_arrow</span>
                      </button>
                    </div>
                    {expandedTables[table] && tableSchemas[table] && (
                      <div className="ml-6 pl-3 border-l border-primary/20 py-1 space-y-1">
                        {Object.entries(tableSchemas[table]).map(([col, type]) => (
                          <div key={col} className="flex justify-between items-center text-[10px] py-0.5">
                            <span className="text-on-surface-variant truncate pr-2 font-mono">{col}</span>
                            <span className="text-primary/60 font-mono uppercase shrink-0">{type}</span>
                          </div>
                        ))}
                      </div>
                    )}
                  </div>
                ))}
              </div>
            </details>

            {/* Kafka Topics */}
            <details className="group" open>
              <summary className="flex items-center gap-2 py-1.5 px-2 rounded hover:bg-primary/10 cursor-pointer list-none">
                <span className="material-symbols-outlined text-sm text-primary group-open:rotate-90 transition-transform">chevron_right</span>
                <span className="material-symbols-outlined text-base text-on-surface-variant">topic</span>
                <span className="text-sm font-medium">Kafka Topics</span>
                <span className="ml-auto text-[10px] bg-surface-container-highest px-1.5 py-0.5 rounded-full text-on-surface-variant tabular-nums">{schema?.topics.length ?? 0}</span>
              </summary>
              <div className="pl-4 pt-1 space-y-0.5">
                {schemaLoading ? (
                  <div className="flex items-center gap-2 py-3 px-2 text-on-surface-variant">
                    <span className="material-symbols-outlined text-sm animate-spin">refresh</span>
                    <span className="text-xs">Loading…</span>
                  </div>
                ) : schema?.topics.length === 0 ? (
                  <p className="text-[10px] text-outline px-2 py-2">No topics with messages</p>
                ) : schema?.topics.map(topic => (
                  <div key={topic} className="flex items-center py-1 px-2 rounded hover:bg-primary/5 transition-colors group/topic">
                    <div onClick={() => setSql(`SELECT * FROM ${topic.replace(/[.-]/g, '_')} LIMIT 50`)} className="flex-1 min-w-0 cursor-pointer">
                      <span className="text-xs text-on-surface-variant hover:text-primary font-mono truncate block">{topic}</span>
                    </div>
                    <button onClick={e => { e.stopPropagation(); fetchDdlPreview(topic); }}
                      className="opacity-0 group-hover/topic:opacity-100 text-outline hover:text-primary transition-all shrink-0 ml-1" title="Preview DDL">
                      <span className="material-symbols-outlined text-sm">code</span>
                    </button>
                  </div>
                ))}
                <p className="text-[10px] text-outline px-2 pt-1">Only topics with messages shown</p>
              </div>
            </details>

            {/* ── Saved Queries ── */}
            <details className="group">
              <summary className="flex items-center gap-2 py-1.5 px-2 rounded hover:bg-primary/10 cursor-pointer list-none">
                <span className="material-symbols-outlined text-sm text-primary group-open:rotate-90 transition-transform">chevron_right</span>
                <span className="material-symbols-outlined text-base text-on-surface-variant">bookmark</span>
                <span className="text-sm font-medium">Saved Queries</span>
                <span className="ml-auto text-[10px] bg-surface-container-highest px-1.5 py-0.5 rounded-full text-on-surface-variant tabular-nums">{savedQueries.length}</span>
              </summary>
              <div className="pl-4 pt-2 space-y-1">
                {/* Save current query */}
                {saveInputVisible ? (
                  <div className="flex items-center gap-1 px-2 pb-2">
                    <Input
                      autoFocus
                      aria-label="Saved query name"
                      value={saveInputName}
                      onChange={e => setSaveInputName(e.target.value)}
                      onKeyDown={e => { if (e.key === 'Enter') saveQuery(); if (e.key === 'Escape') setSaveInputVisible(false); }}
                      placeholder={activeTab.name}
                      className="flex-1 h-8 text-[12px]"
                    />
                    <button onClick={saveQuery} aria-label="Confirm save" className="p-1 rounded-md text-primary hover:bg-surface-container-high">
                      <span className="material-symbols-outlined text-[18px]">check</span>
                    </button>
                    <button onClick={() => setSaveInputVisible(false)} aria-label="Cancel" className="p-1 rounded-md text-outline hover:text-on-surface hover:bg-surface-container-high">
                      <span className="material-symbols-outlined text-[18px]">close</span>
                    </button>
                  </div>
                ) : (
                  <button onClick={() => { setSaveInputVisible(true); setSaveInputName(activeTab.name); }}
                    className="flex items-center gap-1.5 w-full px-2 py-1.5 text-[12px] font-medium text-on-surface-variant hover:text-on-surface border border-dashed border-outline-variant hover:border-outline rounded-md transition-colors">
                    <span className="material-symbols-outlined text-[16px]">add</span>Save current query
                  </button>
                )}
                {savedQueries.length === 0 && !saveInputVisible && (
                  <p className="text-[11px] text-outline px-2 py-1">No saved queries yet</p>
                )}
                {savedQueries.map(q => (
                  <div key={q.id} className="flex items-center gap-1 py-1 px-2 rounded hover:bg-primary/5 transition-colors group/saved">
                    <div onClick={() => loadSavedQuery(q)} className="flex-1 min-w-0 cursor-pointer">
                      <p className="text-xs text-on-surface truncate font-medium">{q.name}</p>
                      <p className="text-[10px] text-outline">{new Date(q.savedAt).toLocaleDateString()}</p>
                    </div>
                    <button onClick={() => deleteSavedQuery(q.id)}
                      className="opacity-0 group-hover/saved:opacity-100 text-outline hover:text-error transition-all shrink-0" title="Delete">
                      <span className="material-symbols-outlined text-sm">delete</span>
                    </button>
                  </div>
                ))}
              </div>
            </details>
          </div>
        </div>

        <div className="p-3 border-t border-outline-variant/60 flex items-center gap-2 text-[11px] text-on-surface-variant">
          <span className={`w-1.5 h-1.5 rounded-full ${schema?.health ? 'bg-success' : 'bg-outline'}`} />
          <span>{schema?.health ? 'Engine connected' : 'Engine offline'}</span>
          <span className="ml-auto tabular-nums">{schema?.tables.length ?? 0} tables · {schema?.topics.length ?? 0} topics</span>
        </div>
      </aside>

      {/* ── Main Content ─────────────────────────────────────────────────────── */}
      <main className="flex-1 flex flex-col min-w-0">
        {/* Toolbar */}
        <header className="h-14 border-b border-outline-variant/60 flex items-center px-4 md:px-6 justify-between gap-4 bg-surface-container-low/40 shrink-0">
          <div className="flex items-center gap-4 min-w-0">
            <div className="flex items-center gap-2">
              <span className="text-[12px] text-on-surface-variant hidden sm:inline">Mode</span>
              <Segmented
                ariaLabel="Execution mode"
                value={executionMode}
                onChange={setExecutionMode}
                options={[{ value: 'SYNC_READ', label: 'Sync read' }, { value: 'ASYNC_JOB', label: 'Flink job' }]}
              />
            </div>
            {executionMode === 'SYNC_READ' && (
              <div className="hidden md:flex items-center gap-2">
                <span className="text-[12px] text-on-surface-variant">Offset</span>
                <Segmented
                  ariaLabel="Offset mode"
                  value={offsetMode}
                  onChange={setOffsetMode}
                  options={[{ value: 'EARLIEST', label: 'Earliest' }, { value: 'LATEST', label: 'Latest' }]}
                />
              </div>
            )}
          </div>
          <div className="flex items-center gap-2 shrink-0">
            <div className="relative">
              <button onClick={() => setShowHistory(h => !h)} aria-label="Query history"
                className="inline-flex items-center gap-1.5 h-9 px-3 text-on-surface-variant hover:text-on-surface border border-outline-variant rounded-md hover:bg-surface-container-high transition-colors" title="Query history">
                <span className="material-symbols-outlined text-[18px]">history</span>
                {history.length > 0 && <span className="text-[10px] bg-primary text-on-primary font-semibold px-1.5 rounded-full tabular-nums">{history.length}</span>}
              </button>
              {showHistory && (
                <div className="absolute right-0 top-full mt-1.5 w-96 max-h-64 overflow-y-auto bg-surface-container-high border border-outline-variant rounded-xl shadow-2xl z-30 animate-slide-up">
                  <div className="px-3 py-2 border-b border-outline-variant/60 flex items-center justify-between sticky top-0 bg-surface-container-high">
                    <span className="text-[11px] font-medium text-on-surface-variant uppercase tracking-[0.05em]">Recent Queries</span>
                    <button onClick={() => { setHistory([]); localStorage.removeItem('kse:query-history'); }} className="text-[11px] text-outline hover:text-error transition-colors">Clear</button>
                  </div>
                  {history.length === 0 ? <p className="p-4 text-[12px] text-outline">No history yet</p>
                    : history.map((h, i) => (
                      <button key={i} onClick={() => { setSql(h.sql); setShowHistory(false); }}
                        className="w-full text-left px-3 py-2 hover:bg-primary/10 border-b border-outline-variant/40 last:border-0 transition-colors">
                        <p className="font-mono text-[12px] text-on-surface truncate">{h.sql.replace(/\s+/g, ' ')}</p>
                        <p className="text-[11px] text-outline mt-0.5">{new Date(h.ts).toLocaleString()}</p>
                      </button>
                    ))
                  }
                </div>
              )}
            </div>
            <span className="text-[11px] text-outline hidden lg:block font-mono">⌘↵</span>
            <Button
              variant="primary"
              onClick={runQuery}
              loading={executing}
              icon={executing ? undefined : 'play_arrow'}
            >
              {executing ? 'Running…' : executionMode === 'ASYNC_JOB' ? 'Submit job' : 'Run query'}
            </Button>
          </div>
        </header>

        {/* Split pane */}
        <div ref={containerRef} className="flex-1 flex flex-col min-h-0 overflow-hidden">

          {/* Editor + Window Assistant */}
          <div className="flex overflow-hidden" style={{ height: `${splitPercent}%` }}>
            <div className="flex-1 flex flex-col min-w-0 bg-surface/40">

              {/* ── Tab bar ── */}
              <div className="flex items-center border-b border-outline-variant/60 bg-surface-container-low/60 shrink-0 overflow-x-auto">
                {tabs.map(tab => (
                  <div
                    key={tab.id}
                    onClick={() => setActiveTabId(tab.id)}
                    className={`group/tab flex items-center gap-1.5 px-3 py-2 border-r border-outline-variant/40 cursor-pointer shrink-0 transition-colors ${tab.id === activeTabId ? 'bg-surface text-on-surface border-b-2 border-b-primary' : 'text-on-surface-variant hover:text-on-surface hover:bg-surface-container-high/50'}`}
                  >
                    {renamingTabId === tab.id ? (
                      <input
                        autoFocus
                        aria-label="Rename tab"
                        value={renamingName}
                        onChange={e => setRenamingName(e.target.value)}
                        onBlur={commitRename}
                        onKeyDown={e => { if (e.key === 'Enter') commitRename(); if (e.key === 'Escape') setRenamingTabId(null); }}
                        onClick={e => e.stopPropagation()}
                        className="bg-transparent border-none outline-none text-[12px] font-medium w-24 text-primary"
                      />
                    ) : (
                      <span
                        className="text-[12px] font-medium"
                        onDoubleClick={e => startRename(tab, e)}
                        title="Double-click to rename"
                      >{tab.name}</span>
                    )}
                    {tabs.length > 1 && (
                      <button onClick={e => closeTab(tab.id, e)} aria-label="Close tab"
                        className="opacity-0 group-hover/tab:opacity-100 text-outline hover:text-on-surface transition-all">
                        <span className="material-symbols-outlined text-[14px]">close</span>
                      </button>
                    )}
                  </div>
                ))}
                <button onClick={addTab} className="px-2.5 py-2 text-outline hover:text-on-surface transition-colors shrink-0" title="New tab" aria-label="New tab">
                  <span className="material-symbols-outlined text-[16px]">add</span>
                </button>
                {/* Format button pushed to the right */}
                <div className="ml-auto px-3 flex items-center">
                  <button onClick={() => editorRef.current?.getAction('editor.action.formatDocument')?.run()}
                    className="flex items-center gap-1 text-[12px] text-on-surface-variant hover:text-on-surface transition-colors" title="Format SQL (Shift+Alt+F)">
                    <span className="material-symbols-outlined text-[16px]">auto_fix_high</span>
                    Format
                  </button>
                </div>
              </div>

              <div className="flex-1 overflow-hidden">
                <Editor
                  height="100%"
                  defaultLanguage="sql"
                  theme="vs-dark"
                  value={sql}
                  onChange={val => setSql(val || '')}
                  onMount={editor => { editorRef.current = editor; }}
                  options={{
                    fontSize: 14, fontFamily: 'JetBrains Mono', minimap: { enabled: false },
                    padding: { top: 16 }, scrollBeyondLastLine: false, automaticLayout: true,
                    suggestOnTriggerCharacters: true,
                    quickSuggestions: { other: true, comments: false, strings: false },
                  }}
                />
              </div>
            </div>

            {/* Window Assistant */}
            <div className="w-72 border-l border-outline-variant/60 bg-surface-container-low/40 p-4 flex flex-col gap-4 overflow-y-auto shrink-0">
              <div className="flex items-center gap-2">
                <span className="material-symbols-outlined text-primary text-[20px]">magic_button</span>
                <h3 className="text-[14px] font-semibold text-on-surface">Window Assistant</h3>
              </div>
              <p className="text-[12px] text-on-surface-variant leading-relaxed">Generate windowing SQL for your streaming queries.</p>
              <div className="space-y-3">
                <div className="space-y-1.5">
                  <label className="block text-[12px] font-medium text-on-surface-variant">Window type</label>
                  <Select value={windowType} onChange={e => setWindowType(e.target.value)}>
                    <option>Tumbling (Non-overlapping)</option>
                    <option>Hopping (Overlapping)</option>
                    <option>Session (Inactivity based)</option>
                  </Select>
                </div>
                <div className="flex gap-2">
                  <div className="flex-1 space-y-1.5">
                    <label className="block text-[12px] font-medium text-on-surface-variant">Size</label>
                    <Input type="number" value={windowSize} onChange={e => setWindowSize(parseInt(e.target.value))} />
                  </div>
                  <div className="w-24 space-y-1.5">
                    <label className="block text-[12px] font-medium text-on-surface-variant">Unit</label>
                    <Select value={windowUnit} onChange={e => setWindowUnit(e.target.value)}>
                      <option>MIN</option><option>SEC</option><option>HOUR</option>
                    </Select>
                  </div>
                </div>
                <div className="p-3 bg-primary/5 border border-primary/20 rounded-lg">
                  <p className="text-[11px] text-on-surface-variant leading-snug"><span className="font-semibold text-primary">Tip:</span> Tumbling windows are ideal for periodic metrics like “Orders per 5 min”.</p>
                </div>
                <Button variant="secondary" className="w-full" icon="bolt" onClick={applyWindowLogic}>Apply to editor</Button>
              </div>
            </div>
          </div>

          {/* Drag handle */}
          <div onMouseDown={e => { isDragging.current = true; document.body.style.cursor = 'row-resize'; e.preventDefault(); }}
            className="h-2 border-y border-outline-variant/60 bg-surface-container-low/60 hover:bg-primary/10 cursor-row-resize flex items-center justify-center transition-colors shrink-0 group">
            <div className="w-16 h-0.5 bg-primary/20 group-hover:bg-primary/50 rounded-full transition-colors" />
          </div>

          {/* Results panel */}
          <div className="flex flex-col bg-surface-container-low/40 overflow-hidden" style={{ height: `calc(${100 - splitPercent}% - 0.5rem)` }}>
            <div className="h-10 border-b border-outline-variant/60 flex items-center px-4 justify-between gap-3 bg-surface-container-low/80 shrink-0">
              <div className="flex items-center gap-5 min-w-0">
                <div className="flex items-center gap-1.5">
                  <span className="material-symbols-outlined text-[16px] text-on-surface-variant">timer</span>
                  <span className="text-[11px] text-on-surface-variant">Execution <span className="text-on-surface tabular-nums">{executing ? '…' : executionMs !== null ? formatMs(executionMs) : '—'}</span></span>
                </div>
                <div className="flex items-center gap-1.5">
                  <span className="material-symbols-outlined text-[16px] text-on-surface-variant">list_alt</span>
                  <span className="text-[11px] text-on-surface-variant">
                    Rows <span className={`tabular-nums ${results?.rows.length === DEFAULT_LIMIT ? 'text-warning' : 'text-on-surface'}`}>{results?.rows.length ?? 0}</span>
                    {results?.rows.length === DEFAULT_LIMIT && <span className="ml-1.5 text-[10px] text-warning font-medium" title="Result set may be truncated">limit reached</span>}
                  </span>
                </div>
                {(results?.engine || (executionMode === 'SYNC_READ' && (results || executing))) && (
                  <span title={
                    (results?.engine ?? 'KAFKA_DIRECT') === 'KAFKA_DIRECT'
                      ? 'Kafka Direct: bounded scan over Kafka messages. Supports SELECT, WHERE, aggregates and TUMBLE windows. No multi-topic JOINs.'
                      : 'Flink: executed by the embedded Flink SQL engine (EXPLAIN / DDL).'
                  }>
                    <Badge tone={(results?.engine ?? 'KAFKA_DIRECT') === 'KAFKA_DIRECT' ? 'primary' : 'secondary'}>
                      {results?.engine ?? 'Kafka Direct'}
                    </Badge>
                  </span>
                )}
              </div>
              <div className="flex items-center gap-3 shrink-0">
                {results && !results.error && results.rows.length > 0 && (
                  <div className="flex items-center gap-1">
                    <button onClick={() => exportResults('csv')} className="flex items-center gap-1 text-[11px] text-on-surface-variant hover:text-on-surface transition-colors"><span className="material-symbols-outlined text-[16px]">download</span>CSV</button>
                    <span className="text-outline">·</span>
                    <button onClick={() => exportResults('json')} className="flex items-center gap-1 text-[11px] text-on-surface-variant hover:text-on-surface transition-colors"><span className="material-symbols-outlined text-[16px]">download</span>JSON</button>
                  </div>
                )}
                <Badge tone={executing ? 'primary' : (results?.error ? 'error' : (results || submittedJob) ? 'success' : 'neutral')} dot>
                  {executing ? 'Running' : submittedJob ? 'Job submitted' : results?.error ? 'Error' : results ? 'Complete' : 'Idle'}
                </Badge>
              </div>
            </div>

            {validationError && (
              <div className="mx-4 mt-3 flex items-center gap-2 px-3 py-2 rounded-lg border border-warning/30 bg-warning/10 text-warning text-[12px] shrink-0" role="alert">
                <span className="material-symbols-outlined text-[18px]">warning</span>
                <span>{validationError}</span>
                <button onClick={() => setValidationError(null)} aria-label="Dismiss" className="ml-auto"><span className="material-symbols-outlined text-[18px]">close</span></button>
              </div>
            )}

            <div className="flex-1 overflow-auto custom-scrollbar">
              {results?.error ? (
                <div className="p-4">
                  <div className="flex items-start gap-3 p-3 rounded-lg border border-error/30 bg-error/10">
                    <span className="material-symbols-outlined text-error text-base mt-0.5 shrink-0">error</span>
                    <div className="flex-1 min-w-0">
                      <p className="text-error font-mono text-sm break-words">{errorSummary}</p>
                      {errorDetails && (
                        <button onClick={() => setShowErrorDetails(s => !s)} className="text-[10px] text-on-surface-variant hover:text-on-surface mt-1.5 transition-colors">
                          {showErrorDetails ? '▲ Hide details' : '▼ Show details'}
                        </button>
                      )}
                      {showErrorDetails && errorDetails && (
                        <pre className="mt-2 text-[10px] text-on-surface-variant font-mono whitespace-pre-wrap overflow-x-auto leading-relaxed border-t border-error/20 pt-2">{errorDetails}</pre>
                      )}
                    </div>
                    <button onClick={() => navigator.clipboard.writeText(results.error!).then(() => toast('Error copied', 'success'))}
                      className="text-outline hover:text-on-surface shrink-0 transition-colors" title="Copy error">
                      <span className="material-symbols-outlined text-sm">content_copy</span>
                    </button>
                  </div>
                </div>
              ) : submittedJob ? (
                <div className="p-4">
                  <div className="flex items-start gap-3 p-4 rounded-lg border border-success/30 bg-success/10">
                    <span className="material-symbols-outlined text-success text-base mt-0.5 shrink-0">rocket_launch</span>
                    <div className="flex-1 min-w-0 space-y-2">
                      <div>
                        <p className="text-success font-semibold text-sm">Flink job submitted</p>
                        <p className="text-xs text-on-surface-variant">The SQL was accepted in asynchronous job mode and is now tracked by the dashboard.</p>
                      </div>
                      <div className="grid grid-cols-1 md:grid-cols-2 gap-2 text-[11px] font-mono text-on-surface">
                        <div>
                          <p className="text-on-surface-variant uppercase tracking-wider text-[10px]">Status</p>
                          <p>{submittedJob.status}</p>
                        </div>
                        <div>
                          <p className="text-on-surface-variant uppercase tracking-wider text-[10px]">Type</p>
                          <p>{submittedJob.statementType.replace('_', ' ')}</p>
                        </div>
                        <div>
                          <p className="text-on-surface-variant uppercase tracking-wider text-[10px]">Query ID</p>
                          <p className="break-all">{submittedJob.queryId}</p>
                        </div>
                        <div>
                          <p className="text-on-surface-variant uppercase tracking-wider text-[10px]">Flink Job ID</p>
                          <p className="break-all">{submittedJob.flinkJobId}</p>
                        </div>
                      </div>
                      <pre className="text-[10px] text-on-surface-variant font-mono whitespace-pre-wrap overflow-x-auto leading-relaxed border-t border-success/20 pt-2">{submittedJob.sql}</pre>
                    </div>
                  </div>
                </div>
              ) : results?.columns.length ? (
                <table className="w-full text-left border-collapse">
                  <thead className="sticky top-0 bg-surface-container-high/90 backdrop-blur-sm z-10">
                    <tr>
                      {results.columns.map(col => (
                        <th key={col} onClick={() => handleSortColumn(col)} scope="col"
                          className="px-4 py-2.5 border-b border-outline-variant/60 text-[11px] font-medium text-on-surface-variant uppercase tracking-[0.05em] cursor-pointer hover:text-on-surface select-none transition-colors whitespace-nowrap">
                          <span className="flex items-center gap-1">
                            {col}
                            {sortCol === col
                              ? <span className="material-symbols-outlined text-[14px] text-primary">{sortDir === 'asc' ? 'arrow_upward' : 'arrow_downward'}</span>
                              : <span className="material-symbols-outlined text-[14px] text-outline">unfold_more</span>}
                          </span>
                        </th>
                      ))}
                    </tr>
                  </thead>
                  <tbody className="divide-y divide-outline-variant/40">
                    {sortedRows.map((row, i) => (
                      <tr key={i} className="hover:bg-surface-container-high/40 transition-colors">
                        {results.columns.map(col => (
                          <td key={col} onClick={() => copyCell(row[col])}
                            className="px-4 py-2.5 text-[12px] font-mono text-on-surface cursor-pointer hover:text-primary transition-colors" title="Click to copy">
                            {typeof row[col] === 'object' ? JSON.stringify(row[col]) : String(row[col] ?? '')}
                          </td>
                        ))}
                      </tr>
                    ))}
                  </tbody>
                </table>
              ) : (
                <EmptyState
                  icon="terminal"
                  title={executionMode === 'ASYNC_JOB' ? 'No job submitted yet' : 'No results yet'}
                  description={executionMode === 'ASYNC_JOB' ? 'Submit an INSERT INTO statement to launch a streaming job and track it here.' : 'Run a query with ⌘↵ to see results in this panel.'}
                />
              )}
            </div>
          </div>
        </div>
      </main>
    </div>
  );
};

export default QueryWorkbench;
