import React, { useState, useEffect, useCallback } from 'react';
import { useNavigate } from 'react-router-dom';
import axios from 'axios';
import Editor, { useMonaco } from '@monaco-editor/react';
import { AreaChart, Area, ResponsiveContainer, ReferenceLine, Tooltip, YAxis } from 'recharts';
import { useToast } from '../components/Toast';

interface MetricConfig {
  id: string;
  name: string;
  type: string;
  sql: string;
  description: string;
  warningThreshold: number | null;
  criticalThreshold: number | null;
  lastValue: number | null;
  lastUpdateTime: number | null;
  errorMessage: string | null;
  history: number[];
  createTableSql?: string | null;
  labelTopic?: string | null;
  labelFields?: string[];
}

interface MetricLabelPreview {
  topic: string;
  timestamp: number | null;
  message: string | null;
  fields: Record<string, string>;
}

// ── Type visual config ─────────────────────────────────────────────────────
const TYPE_META: Record<string, { color: string; bg: string; border: string; icon: string; badge: string }> = {
  GAUGE:     { color: 'text-primary',     bg: 'bg-primary/5',      border: 'border-primary/20',     icon: 'speed',       badge: 'bg-primary/10 text-primary' },
  COUNTER:   { color: 'text-emerald-400', bg: 'bg-emerald-500/5',  border: 'border-emerald-500/20', icon: 'add_circle',  badge: 'bg-emerald-500/10 text-emerald-400' },
  HISTOGRAM: { color: 'text-violet-400',  bg: 'bg-violet-500/5',   border: 'border-violet-500/20',  icon: 'bar_chart',   badge: 'bg-violet-500/10 text-violet-400' },
  SUMMARY:   { color: 'text-amber-400',   bg: 'bg-amber-500/5',    border: 'border-amber-500/20',   icon: 'query_stats', badge: 'bg-amber-500/10 text-amber-400' },
};

// ── Example template for each type shown in the empty state ───────────────
const TYPE_EXAMPLES: Array<{
  type: string;
  title: string;
  description: string;
  sql: (table: string) => string;
  warn: number | null;
  crit: number | null;
}> = [
  {
    type: 'GAUGE',
    title: 'Queue Depth',
    description: 'Point-in-time count of events in the topic — goes up and down.',
    sql: t => `SELECT COUNT(*) AS metric_value\nFROM ${t}`,
    warn: 500, crit: 1000,
  },
  {
    type: 'COUNTER',
    title: 'Total Events',
    description: 'Cumulative total — monotonically increasing. Service tracks the delta automatically.',
    sql: t => `SELECT COUNT(*) AS metric_value\nFROM ${t}`,
    warn: null, crit: null,
  },
  {
    type: 'HISTOGRAM',
    title: 'Payload Size Distribution',
    description: 'One row = one observation. The engine auto-buckets metric_value → Prometheus _bucket/_count/_sum.',
    sql: t =>
      `SELECT CAST(LENGTH(value) AS DOUBLE) AS metric_value\n` +
      `FROM ${t}\n` +
      `-- one row per event; the engine auto-buckets the observed values`,
    warn: null, crit: null,
  },
  {
    type: 'SUMMARY',
    title: 'Value Quantiles',
    description: 'One row = one observation. Micrometer computes P50/P75/P90/P95/P99 from them.',
    sql: t =>
      `SELECT CAST(value AS DOUBLE) AS metric_value\n` +
      `FROM ${t}\n` +
      `-- one row per event; replace value with your numeric column`,
    warn: null, crit: null,
  },
];

// ── SQL / form validation ──────────────────────────────────────────────────

interface ValidationMsg {
  level: 'error' | 'warning' | 'info';
  text: string;
}

function validateMetricSql(sql: string, type: string): ValidationMsg[] {
  if (!sql.trim()) return [];
  // Strip comments before analysis (mirrors backend SqlQueryValidator behaviour)
  const stripped = sql.replace(/--[^\n]*/g, '').replace(/\/\*[\s\S]*?\*\//g, '').trim();
  const msgs: ValidationMsg[] = [];

  if (!/^SELECT\b/i.test(stripped))
    msgs.push({ level: 'error', text: 'Only SELECT queries are allowed (INSERT / UPDATE / DELETE are rejected by the server).' });

  if (!/\bAS\s+metric_value\b/i.test(stripped))
    msgs.push({ level: 'error', text: 'Result column must be aliased as metric_value — e.g. COUNT(*) AS metric_value.' });

  if (!/\bFROM\b/i.test(stripped))
    msgs.push({ level: 'error', text: 'SQL must include a FROM clause.' });

  const hasAggregate = /\b(COUNT|SUM|AVG|MAX|MIN)\s*\(/i.test(stripped);
  const hasGroupBy   = /\bGROUP\s+BY\b/i.test(stripped);
  const isDistribution = type === 'HISTOGRAM' || type === 'SUMMARY';

  if (isDistribution) {
    // HISTOGRAM/SUMMARY record ONE observation per returned row. A bare aggregate collapses to a
    // single row, giving a degenerate distribution — the query should return the raw values.
    if (hasAggregate && !hasGroupBy)
      msgs.push({ level: 'warning', text: `${type} records one observation per row. A bare aggregate (AVG/SUM/COUNT/…) collapses to a single row → a degenerate distribution. Select the raw numeric column instead — e.g. SELECT amount AS metric_value FROM …` });
    if (type === 'HISTOGRAM' && /\bAS\s+le\b/i.test(stripped))
      msgs.push({ level: 'info', text: 'An "le" column is exported as an ordinary label, not a native Prometheus bucket boundary — the engine auto-buckets metric_value itself.' });
  } else {
    // GAUGE / COUNTER expose a single point-in-time / cumulative numeric value.
    if (!hasAggregate)
      msgs.push({ level: 'warning', text: 'No aggregate function found (COUNT, SUM, AVG, MAX, MIN). GAUGE/COUNTER should return a single numeric value.' });
  }

  return msgs;
}

function validateDdlSql(ddl: string): ValidationMsg[] {
  if (!ddl.trim()) return [];
  const stripped = ddl.replace(/--[^\n]*/g, '').replace(/\/\*[\s\S]*?\*\//g, '').trim();
  const msgs: ValidationMsg[] = [];

  if (!/^CREATE\s+TABLE\b/i.test(stripped))
    msgs.push({ level: 'error', text: 'DDL must be a CREATE TABLE statement.' });

  if (/\b(DROP|DELETE|TRUNCATE)\b/i.test(stripped))
    msgs.push({ level: 'error', text: 'Destructive keywords (DROP, DELETE, TRUNCATE) are not allowed in the DDL field.' });

  if (!/\bWITH\b/i.test(stripped))
    msgs.push({ level: 'warning', text: "Missing WITH clause — Flink connector options (connector, topic, …) are required." });

  return msgs;
}

function validateMetricName(name: string): ValidationMsg[] {
  if (!name.trim()) return [];
  if (!/^[a-zA-Z_][a-zA-Z0-9_]*$/.test(name.trim()))
    return [{ level: 'error', text: 'Metric name must start with a letter or underscore and contain only letters, digits, and underscores (Prometheus naming rules).' }];
  return [];
}

function validateThresholds(warn: number | null, crit: number | null): ValidationMsg[] {
  if (warn !== null && crit !== null && warn >= crit)
    return [{ level: 'error', text: 'Warning threshold must be strictly less than the Critical threshold.' }];
  return [];
}

// ── Inline validation hint list ───────────────────────────────────────────

const HINT_ICONS: Record<ValidationMsg['level'], string> = { error: 'error', warning: 'warning', info: 'info' };
const HINT_COLORS: Record<ValidationMsg['level'], string> = {
  error:   'text-red-400',
  warning: 'text-amber-400',
  info:    'text-slate-400',
};

const ValidationHints: React.FC<{ messages: ValidationMsg[] }> = ({ messages }) => {
  if (messages.length === 0) return null;
  return (
    <div className="flex flex-col gap-1 px-4 py-2.5 border-t border-primary/10 bg-background-dark/70">
      {messages.map((m, i) => (
        <div key={i} className={`flex items-start gap-1.5 text-[11px] leading-snug ${HINT_COLORS[m.level]}`}>
          <span className="material-symbols-outlined text-[13px] shrink-0 mt-px">{HINT_ICONS[m.level]}</span>
          <span>{m.text}</span>
        </div>
      ))}
    </div>
  );
};

// ── Auto-generate a Prometheus-safe metric name from type + topic ──────────
function buildAutoName(type: string, topic: string): string {
  const typePart  = (type || 'gauge').toLowerCase();
  const topicPart = topic ? topicToTable(topic) : 'my_table';
  return `${typePart}_${topicPart}`;
}

function extractTopicFromDdl(ddl?: string | null): string {
  if (!ddl) return '';
  const match = ddl.match(/'topic'\s*=\s*'([^']+)'/i);
  return match?.[1] ?? '';
}

function formatPreviewTimestamp(timestamp: number | null): string {
  if (!timestamp) return 'unknown';
  return new Date(timestamp).toLocaleString();
}

// ── Convert Kafka topic name to a valid Flink table identifier ─────────────
function topicToTable(topic: string): string {
  return (
    topic.replace(/[.\-]/g, '_').replace(/[^a-zA-Z0-9_]/g, '').replace(/^_+|_+$/g, '') || 'my_table'
  );
}

// ── Auto-generate a CREATE TABLE DDL for a topic ──────────────────────────
function buildDdlTemplate(topic: string, bootstrapServers: string): string {
  const t = topicToTable(topic);
  return (
    `CREATE TABLE IF NOT EXISTS ${t} (\n` +
    `  \`raw_key\`  STRING,\n` +
    `  \`value\`    STRING,\n` +
    `  \`ts\`       TIMESTAMP(3) METADATA FROM 'timestamp',\n` +
    `  WATERMARK FOR \`ts\` AS \`ts\` - INTERVAL '5' SECOND\n` +
    `) WITH (\n` +
    `  'connector'                    = 'kafka',\n` +
    `  'topic'                        = '${topic}',\n` +
    `  'properties.bootstrap.servers' = '${bootstrapServers}',\n` +
    `  'scan.startup.mode'            = 'earliest-offset',\n` +
    `  'format'                       = 'json',\n` +
    `  'json.ignore-parse-errors'     = 'true'\n` +
    `);`
  );
}

// ── SQL templates (dynamic, use selected table name) ──────────────────────
function getSqlTemplates(table: string) {
  return [
    {
      group: 'GAUGE — point-in-time',
      color: 'text-primary',
      items: [
        { label: 'Queue depth',    sql: `SELECT COUNT(*) AS metric_value\nFROM ${table}` },
        { label: 'Filtered count', sql: `SELECT COUNT(*) AS metric_value\nFROM ${table}\nWHERE status = 'PROCESSING'` },
        { label: 'Distinct users', sql: `SELECT COUNT(DISTINCT user_id) AS metric_value\nFROM ${table}` },
        { label: 'Average amount', sql: `SELECT AVG(amount) AS metric_value\nFROM ${table}` },
      ],
    },
    {
      group: 'COUNTER — cumulative total',
      color: 'text-emerald-400',
      items: [
        { label: 'Total events',    sql: `SELECT COUNT(*) AS metric_value\nFROM ${table}` },
        { label: 'Total per status',sql: `SELECT COUNT(*) AS metric_value, status\nFROM ${table}\nGROUP BY status` },
        { label: 'Total revenue',   sql: `SELECT SUM(amount) AS metric_value\nFROM ${table}` },
      ],
    },
    {
      group: 'HISTOGRAM — value distribution',
      color: 'text-violet-400',
      items: [
        { label: 'Amount observations',  sql: `SELECT CAST(amount AS DOUBLE) AS metric_value\nFROM ${table}\n-- one row per event → engine auto-buckets the values` },
        { label: 'Payload size',         sql: `SELECT CAST(LENGTH(value) AS DOUBLE) AS metric_value\nFROM ${table}\n-- one row per event → engine auto-buckets the values` },
      ],
    },
    {
      group: 'SUMMARY — quantile observations',
      color: 'text-amber-400',
      items: [
        { label: 'Amount observations',  sql: `SELECT CAST(amount AS DOUBLE) AS metric_value\nFROM ${table}\n-- one row per event → Micrometer computes P50/P75/P90/P95/P99` },
        { label: 'Latency observations', sql: `SELECT CAST(latency_ms AS DOUBLE) AS metric_value\nFROM ${table}\n-- one row per event; replace latency_ms with your numeric column` },
      ],
    },
  ];
}

// ─────────────────────────────────────────────────────────────────────────────

function relativeTime(ms: number | null): string {
  if (!ms) return 'Never';
  const diff = Date.now() - ms;
  if (diff < 5000) return 'Just now';
  if (diff < 60000) return `${Math.floor(diff / 1000)}s ago`;
  if (diff < 3600000) return `${Math.floor(diff / 60000)}m ago`;
  return new Date(ms).toLocaleTimeString();
}

function getStatus(m: MetricConfig): 'error' | 'critical' | 'warning' | 'ok' | 'pending' {
  if (m.errorMessage) return 'error';
  if (m.lastValue === null) return 'pending';
  if (m.criticalThreshold !== null && m.lastValue >= m.criticalThreshold) return 'critical';
  if (m.warningThreshold !== null && m.lastValue >= m.warningThreshold) return 'warning';
  return 'ok';
}

const STATUS_STYLES = {
  ok:       { dot: 'bg-emerald-400', text: 'text-emerald-400', label: 'Healthy' },
  warning:  { dot: 'bg-amber-400',   text: 'text-amber-400',   label: 'Warning' },
  critical: { dot: 'bg-red-500',     text: 'text-red-400',     label: 'Critical' },
  error:    { dot: 'bg-red-600',     text: 'text-red-500',     label: 'Error' },
  pending:  { dot: 'bg-slate-500',   text: 'text-slate-400',   label: 'Pending' },
};

// ── MetricCard ────────────────────────────────────────────────────────────
const MetricCard: React.FC<{
  metric: MetricConfig;
  onEdit: () => void;
  onDelete: () => void;
  onRefresh: () => void;
  refreshing: boolean;
}> = ({ metric, onEdit, onDelete, onRefresh, refreshing }) => {
  const { toast } = useToast();
  const status = getStatus(metric);
  const st = STATUS_STYLES[status];
  const tm = TYPE_META[metric.type] ?? TYPE_META.GAUGE;

  const chartData = (metric.history?.length === 1
    ? [metric.history[0], metric.history[0]]
    : metric.history ?? []
  ).map((v, i) => ({ i, v }));

  const strokeColor = status === 'critical' ? '#f87171' : status === 'warning' ? '#fbbf24' : '#25f4f4';

  return (
    <div className="flex flex-col border border-primary/10 rounded-xl bg-primary/5 overflow-hidden hover:border-primary/25 transition-colors">
      {/* Header */}
      <div className="flex items-center justify-between px-4 pt-4 pb-2 gap-2">
        <div className="flex items-center gap-2 min-w-0">
          <span className={`w-2 h-2 rounded-full shrink-0 ${st.dot} ${status === 'ok' ? 'animate-pulse' : ''}`} />
          <h3 className="font-bold text-slate-100 truncate font-mono text-sm">{metric.name}</h3>
          <span className={`px-1.5 py-0.5 rounded text-[9px] font-bold uppercase shrink-0 ${tm.badge}`}>
            {metric.type}
          </span>
          {metric.createTableSql && (
            <span title="Has attached CREATE TABLE DDL"
              className="px-1.5 py-0.5 rounded text-[9px] font-bold uppercase shrink-0 bg-slate-700 text-slate-400">
              DDL
            </span>
          )}
          {(metric.labelFields?.length ?? 0) > 0 && (
            <span title="Latest-message labels configured"
              className="px-1.5 py-0.5 rounded text-[9px] font-bold uppercase shrink-0 bg-sky-500/10 text-sky-300">
              {metric.labelFields!.length} labels
            </span>
          )}
        </div>
        <div className="flex items-center gap-1 shrink-0">
          <button onClick={onRefresh} disabled={refreshing} title="Refresh now"
            className="p-1 text-slate-500 hover:text-primary transition-colors disabled:opacity-40">
            <span className={`material-symbols-outlined text-base ${refreshing ? 'animate-spin' : ''}`}>refresh</span>
          </button>
          <button onClick={onEdit} title="Edit"
            className="p-1 text-slate-500 hover:text-primary transition-colors">
            <span className="material-symbols-outlined text-base">edit</span>
          </button>
          <button onClick={onDelete} title="Delete"
            className="p-1 text-slate-500 hover:text-red-400 transition-colors">
            <span className="material-symbols-outlined text-base">delete</span>
          </button>
        </div>
      </div>

      {/* Value */}
      <div className="px-4 pb-2">
        <div className={`text-3xl font-bold tabular-nums ${st.text}`}>
          {metric.lastValue !== null
            ? metric.lastValue.toLocaleString(undefined, { maximumFractionDigits: 2 })
            : '—'}
        </div>
        <div className="flex items-center gap-3 mt-0.5 flex-wrap">
          {metric.description && <p className="text-xs text-slate-500 truncate">{metric.description}</p>}
          {metric.warningThreshold !== null && (
            <span className="flex items-center gap-0.5 text-[10px] text-amber-400 font-mono shrink-0">
              <span className="material-symbols-outlined text-[11px]">warning</span>≥ {metric.warningThreshold.toLocaleString()}
            </span>
          )}
          {metric.criticalThreshold !== null && (
            <span className="flex items-center gap-0.5 text-[10px] text-red-400 font-mono shrink-0">
              <span className="material-symbols-outlined text-[11px]">emergency</span>≥ {metric.criticalThreshold.toLocaleString()}
            </span>
          )}
        </div>
        {metric.errorMessage && (
          <p className="text-[10px] text-red-400 mt-1 line-clamp-2" title={metric.errorMessage}>
            <span className="material-symbols-outlined text-[11px] align-middle">error</span>{' '}{metric.errorMessage}
          </p>
        )}
      </div>

      {/* Sparkline */}
      <div className="px-3 pb-2">
        {chartData.length > 0 ? (() => {
          const vals = chartData.map(d => d.v);
          const minV = Math.min(...vals);
          const maxV = Math.max(...vals);
          const pad = (maxV - minV) * 0.15 || 1;
          return (
            <div className="rounded-lg overflow-hidden border border-primary/10 bg-background-dark/60">
              <ResponsiveContainer width="100%" height={80}>
                <AreaChart data={chartData} margin={{ top: 8, right: 8, left: 0, bottom: 4 }}>
                  <defs>
                    <linearGradient id={`grad-${metric.id}`} x1="0" y1="0" x2="0" y2="1">
                      <stop offset="0%"   stopColor={strokeColor} stopOpacity={0.35} />
                      <stop offset="100%" stopColor={strokeColor} stopOpacity={0.02} />
                    </linearGradient>
                  </defs>
                  <YAxis domain={[minV - pad, maxV + pad]} hide />
                  {metric.warningThreshold !== null && (
                    <ReferenceLine y={metric.warningThreshold} stroke="#fbbf24" strokeDasharray="4 3" strokeWidth={1}
                      label={{ value: 'warn', position: 'insideTopRight', fontSize: 8, fill: '#fbbf24', dy: -2 }} />
                  )}
                  {metric.criticalThreshold !== null && (
                    <ReferenceLine y={metric.criticalThreshold} stroke="#f87171" strokeDasharray="4 3" strokeWidth={1}
                      label={{ value: 'crit', position: 'insideTopRight', fontSize: 8, fill: '#f87171', dy: -2 }} />
                  )}
                  <Area type="monotone" dataKey="v" stroke={strokeColor} strokeWidth={2}
                    fill={`url(#grad-${metric.id})`} dot={false}
                    activeDot={{ r: 3, fill: strokeColor, strokeWidth: 0 }} isAnimationActive={false} />
                  <Tooltip cursor={{ stroke: strokeColor, strokeWidth: 1, strokeOpacity: 0.3 }}
                    content={({ active, payload }) =>
                      active && payload?.length ? (
                        <div className="bg-background-dark border border-primary/20 px-2 py-1 rounded-lg text-[10px] font-mono" style={{ color: strokeColor }}>
                          {Number(payload[0].value).toLocaleString(undefined, { maximumFractionDigits: 2 })}
                        </div>
                      ) : null
                    }
                  />
                </AreaChart>
              </ResponsiveContainer>
              <div className="flex items-center justify-between px-3 py-1.5 border-t border-primary/5 text-[10px] font-mono">
                <span className="text-slate-600">min <span className="text-slate-400">{minV.toLocaleString(undefined, { maximumFractionDigits: 1 })}</span></span>
                <span className="text-slate-600">{chartData.length} pts</span>
                <span className="text-slate-600">max <span className="text-slate-400">{maxV.toLocaleString(undefined, { maximumFractionDigits: 1 })}</span></span>
              </div>
            </div>
          );
        })() : (
          <div className="rounded-lg border border-primary/10 bg-background-dark/60 h-24 flex flex-col items-center justify-center gap-1.5">
            <div className="flex items-end gap-0.5 h-6 opacity-20">
              {Array.from({ length: 20 }).map((_, i) => (
                <div key={i} className="w-1 bg-primary rounded-sm" style={{ height: `${Math.random() * 100}%` }} />
              ))}
            </div>
            <span className="text-[10px] text-slate-600 uppercase tracking-wider">Waiting for data…</span>
          </div>
        )}
      </div>

      {/* SQL footer */}
      <div className="group border-t border-primary/10 bg-background-dark/40 px-4 py-2.5 flex items-start gap-2">
        <span className="material-symbols-outlined text-primary/40 text-base shrink-0 mt-0.5">code</span>
        <pre className="flex-1 text-[10px] font-mono text-slate-500 truncate leading-relaxed whitespace-nowrap overflow-hidden">
          {metric.sql.replace(/\s+/g, ' ')}
        </pre>
        <button onClick={() => { navigator.clipboard.writeText(metric.sql); toast('SQL copied', 'success'); }}
          title="Copy SQL" className="shrink-0 text-slate-600 hover:text-primary transition-colors opacity-0 group-hover:opacity-100">
          <span className="material-symbols-outlined text-base">content_copy</span>
        </button>
      </div>

      {/* Footer */}
      <div className="px-4 py-1.5 flex items-center justify-between border-t border-primary/5">
        <span className="text-[10px] text-slate-600 flex items-center gap-1">
          <span className="material-symbols-outlined text-[11px]">schedule</span>
          {relativeTime(metric.lastUpdateTime)}
        </span>
        <span className={`text-[10px] font-bold ${st.text}`}>{st.label}</span>
      </div>
    </div>
  );
};

// ─────────────────────────────────────────────────────────────────────────────

const EMPTY_METRIC: Partial<MetricConfig> = {
  name: '', type: 'GAUGE',
  sql: 'SELECT COUNT(*) AS metric_value FROM my_table',
  description: '',
  warningThreshold: null, criticalThreshold: null,
  createTableSql: '',
  labelTopic: '',
  labelFields: [],
};

const Metrics: React.FC = () => {
  const { toast } = useToast();
  const navigate = useNavigate();
  const [metrics, setMetrics]           = useState<MetricConfig[]>([]);
  const [metadata, setMetadata]         = useState<Record<string, string[]>>({});
  const [topics, setTopics]             = useState<string[]>([]);
  const [bootstrapServers, setBootstrapServers] = useState<string>('localhost:9092');
  const [loading, setLoading]           = useState(true);
  const [isModalOpen, setIsModalOpen]   = useState(false);
  const [editingMetric, setEditingMetric] = useState<Partial<MetricConfig>>(EMPTY_METRIC);
  const [selectedTopic, setSelectedTopic] = useState<string>('');
  const [editorTab, setEditorTab]       = useState<'metric' | 'ddl'>('metric');
  const [saving, setSaving]             = useState(false);
  const [previewing, setPreviewing]     = useState(false);
  const [previewResult, setPreviewResult] = useState<{ value?: unknown; rows?: unknown[]; error?: string } | null>(null);
  const [refreshingId, setRefreshingId] = useState<string | null>(null);
  const [filterType, setFilterType]     = useState<string>('all');
  const [filterStatus, setFilterStatus] = useState<string>('all');
  const [nameIsAuto, setNameIsAuto]     = useState(false);
  const [labelPreview, setLabelPreview] = useState<MetricLabelPreview | null>(null);
  const [labelPreviewLoading, setLabelPreviewLoading] = useState(false);

  const monaco = useMonaco();

  const fetchMetrics = useCallback(async () => {
    try {
      const res = await axios.get<MetricConfig[]>('/api/metrics');
      setMetrics(res.data);
    } catch {
      toast('Failed to fetch metrics', 'error');
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    fetchMetrics();
    axios.get<Record<string, string[]>>('/api/metrics/metadata').then(r => setMetadata(r.data)).catch(() => { toast('Failed to load table metadata', 'error'); });
    axios.get<{ topics: string[] }>('/api/dashboard').then(r => setTopics(r.data.topics ?? [])).catch(() => {});
    axios.get<{ bootstrapServers: string }>('/api/config').then(r => {
      if (r.data.bootstrapServers) setBootstrapServers(r.data.bootstrapServers);
    }).catch(() => {});
    const iv = setInterval(fetchMetrics, 15000);
    return () => clearInterval(iv);
  }, []);

  useEffect(() => {
    if (!isModalOpen || !selectedTopic) {
      setLabelPreview(null);
      setLabelPreviewLoading(false);
      return;
    }

    let cancelled = false;
    setLabelPreviewLoading(true);
    axios.get<MetricLabelPreview>(`/api/metrics/label-preview?topic=${encodeURIComponent(selectedTopic)}`)
      .then(response => {
        if (!cancelled) setLabelPreview(response.data);
      })
      .catch(() => {
        if (!cancelled) {
          setLabelPreview(null);
          toast('Failed to load latest message for label selection', 'error');
        }
      })
      .finally(() => {
        if (!cancelled) setLabelPreviewLoading(false);
      });

    return () => {
      cancelled = true;
    };
  }, [isModalOpen, selectedTopic]);

  // U9 — close the modal on Escape.
  useEffect(() => {
    if (!isModalOpen) return;
    const onKey = (e: KeyboardEvent) => { if (e.key === 'Escape') setIsModalOpen(false); };
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, [isModalOpen]);

  // SQL autocomplete
  useEffect(() => {
    if (!monaco) return;
    const provider = monaco.languages.registerCompletionItemProvider('sql', {
      provideCompletionItems: () => {
        const suggestions: import('monaco-editor').languages.CompletionItem[] = [];
        Object.keys(metadata).forEach(table => {
          suggestions.push({ label: table, kind: monaco.languages.CompletionItemKind.Class, insertText: table, detail: 'Table', range: undefined as never });
          metadata[table].forEach(col => {
            suggestions.push({ label: col, kind: monaco.languages.CompletionItemKind.Field, insertText: col, detail: `Column (${table})`, range: undefined as never });
          });
        });
        ['SELECT', 'FROM', 'WHERE', 'GROUP BY', 'HAVING', 'ORDER BY', 'LIMIT',
          'JOIN', 'ON', 'AS', 'COUNT', 'AVG', 'SUM', 'MAX', 'MIN',
          'TUMBLE', 'HOP', 'DESCRIPTOR', 'INTERVAL', 'metric_value',
          'CREATE', 'TABLE', 'IF', 'NOT', 'EXISTS', 'WITH', 'WATERMARK', 'FOR',
        ].forEach(kw => {
          suggestions.push({ label: kw, kind: monaco.languages.CompletionItemKind.Keyword, insertText: kw, range: undefined as never });
        });
        return { suggestions };
      },
    });
    return () => provider.dispose();
  }, [monaco, metadata]);

  // ── Open modal ────────────────────────────────────────────────────────────
  const openEdit = (metric?: MetricConfig, typeOverride?: string, sqlFn?: (t: string) => string,
                    warn?: number | null, crit?: number | null) => {
    const firstTopic = topics[0] ?? '';
    const tableName  = firstTopic ? topicToTable(firstTopic) : 'my_table';

    if (metric) {
      const metricTopic = metric.labelTopic ?? extractTopicFromDdl(metric.createTableSql);
      setEditingMetric({
        ...metric,
        labelTopic: metricTopic,
        labelFields: metric.labelFields ?? [],
      });
      setSelectedTopic(metricTopic);
      setNameIsAuto(false);
    } else {
      const type       = typeOverride ?? 'GAUGE';
      const initialSql = sqlFn ? sqlFn(tableName) : `SELECT COUNT(*) AS metric_value\nFROM ${tableName}`;
      const initialDdl = firstTopic ? buildDdlTemplate(firstTopic, bootstrapServers) : '';
      setEditingMetric({
        ...EMPTY_METRIC,
        type,
        name: buildAutoName(type, firstTopic),
        sql:  initialSql,
        warningThreshold:  warn  !== undefined ? warn  : null,
        criticalThreshold: crit  !== undefined ? crit  : null,
        createTableSql: initialDdl,
        labelTopic: firstTopic,
        labelFields: [],
      });
      setSelectedTopic(firstTopic);
      setNameIsAuto(true);
    }
    setEditorTab('metric');
    setPreviewResult(null);
    setIsModalOpen(true);
  };

  // When selected topic changes, update DDL template and replace old table name in metric SQL
  const onTopicChange = (topic: string) => {
    const oldTable = selectedTopic ? topicToTable(selectedTopic) : 'my_table';
    const newTable = topic ? topicToTable(topic) : 'my_table';
    setSelectedTopic(topic);
    setEditingMetric(m => ({
      ...m,
      name: nameIsAuto ? buildAutoName(m.type ?? 'GAUGE', topic) : m.name,
      // Only rewrite the table where it is actually referenced (FROM/JOIN/TABLE …), never inside
      // column names or string literals that happen to match the old table token.
      sql: m.sql ? m.sql.replace(new RegExp(`\\b(FROM|JOIN|TABLE)\\s+${oldTable}\\b`, 'gi'), `$1 ${newTable}`) : m.sql,
      createTableSql: topic ? buildDdlTemplate(topic, bootstrapServers) : (m.createTableSql ?? ''),
      labelTopic: topic,
      labelFields: m.labelTopic === topic ? (m.labelFields ?? []) : [],
    }));
  };

  const toggleLabelField = (field: string) => {
    setEditingMetric(m => {
      const current = m.labelFields ?? [];
      return {
        ...m,
        labelFields: current.includes(field)
          ? current.filter(entry => entry !== field)
          : [...current, field],
      };
    });
  };

  const tableName = selectedTopic ? topicToTable(selectedTopic) : 'my_table';
  const sqlTemplates = getSqlTemplates(tableName);
  const selectedLabelFields = editingMetric.labelFields ?? [];
  const availableLabelFields = Object.entries(labelPreview?.fields ?? {});

  // ── Live validation (derived, no state needed) ────────────────────────────
  const nameValidationBase  = validateMetricName(editingMetric.name ?? '');
  // U10 — warn (non-blocking) when the name collides with a different existing metric: the
  // metric_name Prometheus label would then be shared across two distinct series.
  const nameCollision = (editingMetric.name ?? '').trim().length > 0
    && metrics.some(m => m.name === editingMetric.name?.trim() && m.id !== editingMetric.id);
  const nameValidation: ValidationMsg[] = nameCollision
    ? [...nameValidationBase, { level: 'warning', text: 'Another metric already uses this name — the metric_name label will be shared across both series.' }]
    : nameValidationBase;
  const sqlValidation       = validateMetricSql(editingMetric.sql ?? '', editingMetric.type ?? 'GAUGE');
  const ddlValidation       = validateDdlSql(editingMetric.createTableSql ?? '');
  const thresholdValidation = validateThresholds(
    editingMetric.warningThreshold ?? null,
    editingMetric.criticalThreshold ?? null,
  );
  // U7 — thresholds on a COUNTER compare against an ever-growing cumulative total, so any
  // threshold eventually trips. Surface this as a non-blocking warning.
  const counterThresholdWarning: ValidationMsg[] =
    (editingMetric.type === 'COUNTER'
      && (editingMetric.warningThreshold !== null || editingMetric.criticalThreshold !== null))
      ? [{ level: 'warning', text: 'Thresholds on a COUNTER compare against a cumulative total that only grows, so they will eventually always trip. Consider a GAUGE (e.g. a rate or point-in-time count) for alerting.' }]
      : [];
  const thresholdHints = [...thresholdValidation, ...counterThresholdWarning];
  const hasBlockingErrors = [...nameValidation, ...sqlValidation, ...ddlValidation, ...thresholdValidation]
    .some(m => m.level === 'error');

  // ── Save ──────────────────────────────────────────────────────────────────
  const handleSave = async () => {
    if (!editingMetric.name?.trim()) { toast('Name is required', 'error'); return; }
    if (!editingMetric.sql?.trim())  { toast('SQL is required', 'error'); return; }
    if (hasBlockingErrors) {
      const first = [...nameValidation, ...sqlValidation, ...ddlValidation, ...thresholdValidation].find(m => m.level === 'error');
      toast(first!.text, 'error');
      return;
    }
    setSaving(true);
    try {
      await axios.post('/api/metrics', editingMetric);
      toast('Metric saved', 'success');
      setIsModalOpen(false);
      fetchMetrics();
    } catch {
      toast('Failed to save metric', 'error');
    } finally {
      setSaving(false);
    }
  };

  const handleDelete = async (id: string) => {
    if (!confirm('Delete this metric?')) return;
    try {
      await axios.delete(`/api/metrics/${id}`);
      toast('Metric deleted', 'success');
      setMetrics(prev => prev.filter(m => m.id !== id));
    } catch {
      toast('Failed to delete metric', 'error');
    }
  };

  const handlePreview = async () => {
    if (!editingMetric.sql?.trim()) return;
    setPreviewing(true);
    setPreviewResult(null);
    try {
      // Preview through the template endpoint so the attached CREATE TABLE DDL is executed first
      // (mirrors the scheduled refresh) and the value is computed with the metric's real type.
      const res = await axios.post<{ value?: unknown; rows?: unknown[]; error?: string }>(
        '/api/metrics/preview-template', editingMetric);
      setPreviewResult(res.data);
    } catch {
      setPreviewResult({ error: 'Preview request failed' });
    } finally {
      setPreviewing(false);
    }
  };

  const handleRefreshOne = async (id: string) => {
    setRefreshingId(id);
    try {
      // Force an immediate server-side recompute of this metric (not just a list re-fetch).
      const res = await axios.post<MetricConfig>(`/api/metrics/${id}/refresh`);
      setMetrics(prev => prev.map(m => (m.id === id ? res.data : m)));
      if (res.data.errorMessage) {
        toast(`Refreshed with error: ${res.data.errorMessage}`, 'error');
      } else {
        toast('Metric refreshed', 'success');
      }
    } catch {
      toast('Failed to refresh metric', 'error');
    } finally {
      setRefreshingId(null);
    }
  };

  const counts = metrics.reduce(
    (acc, m) => { acc[getStatus(m)]++; return acc; },
    { ok: 0, warning: 0, critical: 0, error: 0, pending: 0 }
  );

  const SEVERITY_ORDER: Record<string, number> = { error: 0, critical: 1, warning: 2, ok: 3, pending: 4 };
  const filteredMetrics = metrics
    .filter(m => filterType === 'all' || m.type === filterType)
    .filter(m => filterStatus === 'all' || getStatus(m) === filterStatus)
    .sort((a, b) => SEVERITY_ORDER[getStatus(a)] - SEVERITY_ORDER[getStatus(b)]);

  return (
    <div className="p-6 space-y-6 overflow-y-auto h-full">

      {/* Header */}
      <div className="flex items-center justify-between gap-4">
        <div>
          <h1 className="text-2xl font-bold text-slate-100">Business Metrics</h1>
          <p className="text-sm text-slate-500 mt-0.5">
            Flink SQL queries scheduled continuously — values exported to Prometheus.
          </p>
        </div>
        <div className="flex items-center gap-2">
          <button onClick={fetchMetrics} className="p-2 text-slate-500 hover:text-primary transition-colors" title="Refresh all">
            <span className="material-symbols-outlined">refresh</span>
          </button>
          <button onClick={() => navigate('/metrics/help')} className="p-2 text-slate-500 hover:text-primary transition-colors" title="Help">
            <span className="material-symbols-outlined">help</span>
          </button>
          <button onClick={() => openEdit()}
            className="flex items-center gap-2 bg-primary text-background-dark font-bold px-4 py-2 rounded-lg hover:brightness-110 transition-all text-sm">
            <span className="material-symbols-outlined text-lg">add</span>
            Add Metric
          </button>
        </div>
      </div>

      {/* Summary bar */}
      <div className="grid grid-cols-4 gap-3">
        {[
          { label: 'Total',    value: metrics.length,              color: 'text-slate-300', border: 'border-primary/10' },
          { label: 'Healthy',  value: counts.ok,                   color: 'text-emerald-400', border: 'border-emerald-500/20' },
          { label: 'Warning',  value: counts.warning,              color: 'text-amber-400',   border: 'border-amber-500/20' },
          { label: 'Critical', value: counts.critical + counts.error, color: 'text-red-400',  border: 'border-red-500/20' },
        ].map(s => (
          <div key={s.label} className={`border ${s.border} bg-primary/5 rounded-xl px-4 py-3 flex items-center justify-between`}>
            <span className="text-xs text-slate-500 uppercase font-bold tracking-wider">{s.label}</span>
            <span className={`text-2xl font-bold tabular-nums ${s.color}`}>{s.value}</span>
          </div>
        ))}
      </div>

      {/* Filters */}
      {!loading && metrics.length > 0 && (
        <div className="flex items-center gap-3 flex-wrap">
          <select value={filterType} onChange={e => setFilterType(e.target.value)}
            className="bg-background-dark border border-primary/20 rounded-lg px-3 py-1.5 text-xs text-slate-300 focus:ring-1 focus:ring-primary outline-none">
            <option value="all"       className="bg-[#102222] text-slate-300">All types</option>
            <option value="GAUGE"     className="bg-[#102222] text-slate-100">GAUGE</option>
            <option value="COUNTER"   className="bg-[#102222] text-slate-100">COUNTER</option>
            <option value="HISTOGRAM" className="bg-[#102222] text-slate-100">HISTOGRAM</option>
            <option value="SUMMARY"   className="bg-[#102222] text-slate-100">SUMMARY</option>
          </select>
          <select value={filterStatus} onChange={e => setFilterStatus(e.target.value)}
            className="bg-background-dark border border-primary/20 rounded-lg px-3 py-1.5 text-xs text-slate-300 focus:ring-1 focus:ring-primary outline-none">
            <option value="all"      className="bg-[#102222] text-slate-300">All statuses</option>
            <option value="error"    className="bg-[#102222] text-slate-100">Error</option>
            <option value="critical" className="bg-[#102222] text-slate-100">Critical</option>
            <option value="warning"  className="bg-[#102222] text-slate-100">Warning</option>
            <option value="ok"       className="bg-[#102222] text-slate-100">Healthy</option>
            <option value="pending"  className="bg-[#102222] text-slate-100">Pending</option>
          </select>
          {(filterType !== 'all' || filterStatus !== 'all') && (
            <button onClick={() => { setFilterType('all'); setFilterStatus('all'); }}
              className="text-xs text-slate-500 hover:text-primary transition-colors flex items-center gap-1">
              <span className="material-symbols-outlined text-sm">close</span>Clear filters
            </button>
          )}
          <span className="text-xs text-slate-600 ml-auto">
            {filteredMetrics.length} / {metrics.length} metrics · sorted by severity
          </span>
        </div>
      )}

      {/* Content */}
      {loading ? (
        <div className="flex items-center justify-center py-16">
          <div className="animate-spin rounded-full h-8 w-8 border-2 border-primary border-t-transparent" />
        </div>
      ) : metrics.length === 0 ? (
        <div className="border border-dashed border-primary/20 rounded-xl p-6 text-center space-y-2">
          <span className="material-symbols-outlined text-4xl text-slate-700">monitoring</span>
          <p className="font-bold text-slate-400">No metrics yet</p>
          <p className="text-sm text-slate-600 max-w-md mx-auto">
            Pick one of the templates below to get started, or click{' '}
            <button onClick={() => openEdit()} className="text-primary underline underline-offset-2 font-bold">Add Metric</button>{' '}
            to create your own.
            {topics.length === 0 && (
              <span className="block mt-1 text-amber-400/80 text-xs">
                No Kafka topics found — make sure the broker is reachable.
              </span>
            )}
          </p>
        </div>
      ) : (
        <div className="grid grid-cols-1 md:grid-cols-2 xl:grid-cols-3 gap-4">
          {filteredMetrics.length > 0 ? filteredMetrics.map(metric => (
            <MetricCard
              key={metric.id}
              metric={metric}
              onEdit={() => openEdit(metric)}
              onDelete={() => handleDelete(metric.id)}
              onRefresh={() => handleRefreshOne(metric.id)}
              refreshing={refreshingId === metric.id}
            />
          )) : (
            <div className="col-span-full text-center py-12 text-slate-500 text-sm">
              No metrics match the current filters.
            </div>
          )}
        </div>
      )}

      {/* ── Quick-start templates — always visible ─────────────────────────── */}
      {!loading && (
        <div>
          <p className="text-xs font-bold text-slate-500 uppercase tracking-widest mb-3">Quick-start templates</p>
          <div className="grid grid-cols-1 md:grid-cols-2 xl:grid-cols-4 gap-4">
            {TYPE_EXAMPLES.map(ex => {
              const tm = TYPE_META[ex.type] ?? TYPE_META.GAUGE;
              const firstTable = topics.length > 0 ? topicToTable(topics[0]) : 'my_table';
              return (
                <div key={ex.type} className={`flex flex-col border ${tm.border} ${tm.bg} rounded-xl p-4 gap-3`}>
                  <div className="flex items-center gap-2">
                    <span className={`material-symbols-outlined text-xl ${tm.color}`}>{tm.icon}</span>
                    <span className={`px-2 py-0.5 rounded text-[10px] font-bold uppercase ${tm.badge}`}>{ex.type}</span>
                  </div>
                  <div>
                    <p className="font-bold text-slate-200 text-sm">{ex.title}</p>
                    <p className="text-xs text-slate-500 mt-0.5 leading-relaxed">{ex.description}</p>
                  </div>
                  <pre className="text-[10px] font-mono text-slate-500 bg-black/20 rounded-lg p-2 overflow-hidden line-clamp-4 whitespace-pre-wrap">
                    {ex.sql(firstTable)}
                  </pre>
                  <button
                    onClick={() => openEdit(undefined, ex.type, ex.sql, ex.warn, ex.crit)}
                    className={`mt-auto w-full flex items-center justify-center gap-1.5 py-2 rounded-lg border ${tm.border} ${tm.color} font-bold text-xs hover:bg-black/20 transition-colors`}
                  >
                    <span className="material-symbols-outlined text-sm">add_circle</span>
                    Use this template
                  </button>
                </div>
              );
            })}
          </div>
        </div>
      )}

      {/* ── Modal ──────────────────────────────────────────────────────────── */}
      {isModalOpen && (
        <div className="fixed inset-0 z-50 flex items-center justify-center p-4 bg-black/60 backdrop-blur-sm"
          role="dialog" aria-modal="true" onClick={() => setIsModalOpen(false)}>
          <div className="bg-background-dark border border-primary/20 rounded-2xl w-full max-w-5xl max-h-[92vh] overflow-hidden flex flex-col shadow-2xl"
            onClick={e => e.stopPropagation()}>

            {/* Modal header */}
            <div className="flex items-center justify-between px-6 py-4 border-b border-primary/10">
              <h2 className="text-lg font-bold text-slate-100">
                {editingMetric.id ? 'Edit Metric' : 'New SQL Metric'}
              </h2>
              <button onClick={() => setIsModalOpen(false)} className="text-slate-500 hover:text-slate-300 transition-colors">
                <span className="material-symbols-outlined">close</span>
              </button>
            </div>

            {/* Modal body */}
            <div className="flex flex-1 overflow-hidden">

              {/* Left: form */}
              <div className="w-72 border-r border-primary/10 flex flex-col shrink-0 overflow-y-auto p-5 space-y-4">

                {/* Name */}
                <div className="space-y-1.5">
                  <div className="flex items-center justify-between">
                    <label className="text-[10px] uppercase font-bold text-slate-500 tracking-wider">Metric Name *</label>
                    {nameIsAuto ? (
                      <span className="flex items-center gap-0.5 text-[9px] text-primary/70 font-bold uppercase tracking-wider">
                        <span className="material-symbols-outlined text-[11px]">auto_awesome</span>auto
                      </span>
                    ) : (
                      <button
                        type="button"
                        title="Regenerate name from type + topic"
                        onClick={() => {
                          setNameIsAuto(true);
                          setEditingMetric(m => ({ ...m, name: buildAutoName(m.type ?? 'GAUGE', selectedTopic) }));
                        }}
                        className="flex items-center gap-0.5 text-[9px] text-slate-600 hover:text-primary transition-colors uppercase tracking-wider"
                      >
                        <span className="material-symbols-outlined text-[11px]">refresh</span>regenerate
                      </button>
                    )}
                  </div>
                  <input type="text" value={editingMetric.name ?? ''}
                    onChange={e => { setNameIsAuto(false); setEditingMetric(m => ({ ...m, name: e.target.value })); }}
                    placeholder="e.g. gauge_orders_topic"
                    className={`w-full bg-primary/5 border rounded-lg px-3 py-2 text-sm font-mono text-slate-100 placeholder:text-slate-600 focus:ring-1 outline-none ${
                      nameValidation.some(v => v.level === 'error')
                        ? 'border-red-500/50 focus:ring-red-400'
                        : 'border-primary/20 focus:ring-primary'
                    }`} />
                  {nameValidation.map((m, i) => (
                    <p key={i} className={`text-[10px] flex items-start gap-1 ${HINT_COLORS[m.level]}`}>
                      <span className="material-symbols-outlined text-[11px] shrink-0 mt-px">{HINT_ICONS[m.level]}</span>
                      {m.text}
                    </p>
                  ))}
                </div>

                {/* Type */}
                <div className="space-y-1.5">
                  <label className="text-[10px] uppercase font-bold text-slate-500 tracking-wider">Type</label>
                  <select value={editingMetric.type ?? 'GAUGE'}
                    onChange={e => {
                      const newType = e.target.value;
                      setEditingMetric(m => ({
                        ...m,
                        type: newType,
                        name: nameIsAuto ? buildAutoName(newType, selectedTopic) : m.name,
                      }));
                    }}
                    className="w-full bg-background-dark border border-primary/20 rounded-lg px-3 py-2 text-sm text-slate-100 focus:ring-1 focus:ring-primary outline-none">
                    <option value="GAUGE"     className="bg-[#102222] text-slate-100">GAUGE — point-in-time value</option>
                    <option value="COUNTER"   className="bg-[#102222] text-slate-100">COUNTER — cumulative total</option>
                    <option value="HISTOGRAM" className="bg-[#102222] text-slate-100">HISTOGRAM — bucket distribution</option>
                    <option value="SUMMARY"   className="bg-[#102222] text-slate-100">SUMMARY — quantile observations</option>
                  </select>
                  <p className="text-[10px] text-slate-600">
                    {{ GAUGE: '→ explorer_metric_gauge{…}', COUNTER: '→ explorer_metric_counter_total{…}',
                       HISTOGRAM: '→ explorer_metric_histogram_bucket{le=…} — auto-bucketed over metric_value', SUMMARY: '→ explorer_metric_summary{quantile=0.95,…}',
                    }[editingMetric.type ?? 'GAUGE']}
                  </p>
                </div>

                {/* Topic selector */}
                <div className="space-y-1.5">
                  <label className="text-[10px] uppercase font-bold text-slate-500 tracking-wider">
                    Kafka Topic
                    <span className="ml-1 text-slate-600 font-normal normal-case">— used in SQL templates &amp; DDL</span>
                  </label>
                  {topics.length > 0 ? (
                    <select value={selectedTopic} onChange={e => onTopicChange(e.target.value)}
                      className="w-full bg-background-dark border border-primary/20 rounded-lg px-3 py-2 text-sm font-mono text-slate-100 focus:ring-1 focus:ring-primary outline-none">
                      <option value="" className="bg-[#102222] text-slate-400">— select a topic —</option>
                      {topics.map(t => <option key={t} value={t} className="bg-[#102222] text-slate-100">{t}</option>)}
                    </select>
                  ) : (
                    <input type="text" value={selectedTopic}
                      onChange={e => onTopicChange(e.target.value)}
                      placeholder="my_topic (type manually)"
                      className="w-full bg-primary/5 border border-primary/20 rounded-lg px-3 py-2 text-sm font-mono text-slate-100 placeholder:text-slate-600 focus:ring-1 focus:ring-primary outline-none" />
                  )}
                  {selectedTopic && (
                    <p className="text-[10px] text-slate-600">
                      Table: <span className="text-primary font-mono">{topicToTable(selectedTopic)}</span>
                    </p>
                  )}
                </div>

                {/* Prometheus labels from latest Kafka message */}
                <div className="space-y-2 rounded-xl border border-primary/10 bg-primary/5 p-3">
                  <div className="flex items-start justify-between gap-2">
                    <div>
                      <label className="text-[10px] uppercase font-bold text-slate-500 tracking-wider">Prometheus Labels</label>
                      <p className="text-[10px] text-slate-600 mt-1 leading-relaxed">
                        Select fields from the latest message on this topic. Their current values will be exported as labels on each metric refresh.
                      </p>
                    </div>
                    {selectedTopic && (
                      <button
                        type="button"
                        onClick={() => {
                          setLabelPreviewLoading(true);
                          axios.get<MetricLabelPreview>(`/api/metrics/label-preview?topic=${encodeURIComponent(selectedTopic)}`)
                            .then(response => setLabelPreview(response.data))
                            .catch(() => toast('Failed to refresh latest message', 'error'))
                            .finally(() => setLabelPreviewLoading(false));
                        }}
                        className="shrink-0 text-slate-500 hover:text-primary transition-colors"
                        title="Refresh latest message"
                      >
                        <span className={`material-symbols-outlined text-base ${labelPreviewLoading ? 'animate-spin' : ''}`}>refresh</span>
                      </button>
                    )}
                  </div>

                  {!selectedTopic ? (
                    <p className="text-[11px] text-slate-500">Select a topic to inspect its latest message.</p>
                  ) : labelPreviewLoading ? (
                    <div className="flex items-center gap-2 text-[11px] text-slate-500">
                      <span className="material-symbols-outlined text-sm animate-spin">progress_activity</span>
                      Loading latest message…
                    </div>
                  ) : !labelPreview?.message ? (
                    <p className="text-[11px] text-slate-500">No recent message available on this topic.</p>
                  ) : (
                    <>
                      <div className="flex items-center justify-between gap-2 text-[10px] text-slate-600">
                        <span>Latest message at {formatPreviewTimestamp(labelPreview.timestamp)}</span>
                        <span>{selectedLabelFields.length} selected</span>
                      </div>

                      {availableLabelFields.length > 0 ? (
                        <div className="max-h-44 overflow-y-auto rounded-lg border border-primary/10 bg-background-dark/40 divide-y divide-primary/5">
                          {availableLabelFields.map(([field, value]) => {
                            const checked = selectedLabelFields.includes(field);
                            return (
                              <label
                                key={field}
                                className={`flex items-start gap-2 px-3 py-2 cursor-pointer transition-colors ${
                                  checked ? 'bg-primary/10' : 'hover:bg-primary/5'
                                }`}
                              >
                                <input
                                  type="checkbox"
                                  checked={checked}
                                  onChange={() => toggleLabelField(field)}
                                  className="mt-0.5 rounded border-primary/30 bg-background-dark text-primary focus:ring-primary"
                                />
                                <div className="min-w-0">
                                  <div className="font-mono text-[11px] text-slate-200 break-all">{field}</div>
                                  <div className="font-mono text-[10px] text-slate-500 break-all">{value || '""'}</div>
                                </div>
                              </label>
                            );
                          })}
                        </div>
                      ) : (
                        <p className="text-[11px] text-slate-500">
                          The latest message format does not expose selectable leaf fields.
                        </p>
                      )}

                      <div className="rounded-lg border border-primary/10 bg-background-dark/40 p-2">
                        <p className="text-[10px] uppercase font-bold tracking-wider text-slate-500 mb-2">Latest Message</p>
                        <pre className="max-h-36 overflow-auto whitespace-pre-wrap break-all text-[10px] font-mono text-slate-400">
                          {labelPreview.message}
                        </pre>
                      </div>
                    </>
                  )}
                </div>

                {/* Description */}
                <div className="space-y-1.5">
                  <label className="text-[10px] uppercase font-bold text-slate-500 tracking-wider">Description</label>
                  <textarea value={editingMetric.description ?? ''}
                    onChange={e => setEditingMetric(m => ({ ...m, description: e.target.value }))}
                    placeholder="What does this metric track?" rows={2}
                    className="w-full bg-primary/5 border border-primary/20 rounded-lg px-3 py-2 text-sm text-slate-100 placeholder:text-slate-600 focus:ring-1 focus:ring-primary outline-none resize-none" />
                </div>

                {/* Thresholds */}
                <div className="space-y-1.5">
                  <div className="grid grid-cols-2 gap-3">
                    <div className="space-y-1.5">
                      <label className="text-[10px] uppercase font-bold text-amber-500/80 tracking-wider">⚠ Warning</label>
                      <input type="number" value={editingMetric.warningThreshold ?? ''}
                        onChange={e => setEditingMetric(m => ({ ...m, warningThreshold: e.target.value ? parseFloat(e.target.value) : null }))}
                        className={`w-full bg-amber-500/5 border rounded-lg px-3 py-2 text-sm text-slate-100 focus:ring-1 outline-none ${
                          thresholdValidation.length > 0 ? 'border-red-500/40' : 'border-amber-500/20 focus:ring-amber-400'
                        }`} />
                    </div>
                    <div className="space-y-1.5">
                      <label className="text-[10px] uppercase font-bold text-red-500/80 tracking-wider">🔴 Critical</label>
                      <input type="number" value={editingMetric.criticalThreshold ?? ''}
                        onChange={e => setEditingMetric(m => ({ ...m, criticalThreshold: e.target.value ? parseFloat(e.target.value) : null }))}
                        className={`w-full bg-red-500/5 border rounded-lg px-3 py-2 text-sm text-slate-100 focus:ring-1 outline-none ${
                          thresholdValidation.length > 0 ? 'border-red-500/40' : 'border-red-500/20 focus:ring-red-400'
                        }`} />
                    </div>
                  </div>
                  {thresholdHints.map((m, i) => (
                    <p key={i} className={`text-[10px] flex items-start gap-1 ${HINT_COLORS[m.level]}`}>
                      <span className="material-symbols-outlined text-[11px] shrink-0 mt-px">{HINT_ICONS[m.level]}</span>
                      {m.text}
                    </p>
                  ))}
                </div>

                {/* SQL templates */}
                <div className="border-t border-primary/10 pt-4">
                  <p className="text-[10px] uppercase font-bold text-slate-500 tracking-wider mb-3">SQL Templates</p>
                  <div className="space-y-3">
                    {sqlTemplates.map(group => (
                      <div key={group.group}>
                        <p className={`text-[10px] font-bold uppercase tracking-wider mb-1.5 ${group.color}`}>{group.group}</p>
                        <div className="space-y-1">
                          {group.items.map(t => (
                            <button key={t.label}
                              onClick={() => { setEditingMetric(m => ({ ...m, sql: t.sql })); setPreviewResult(null); setEditorTab('metric'); }}
                              className="w-full text-left text-xs px-3 py-1.5 rounded-lg text-slate-400 hover:text-primary hover:bg-primary/10 transition-colors font-mono">
                              {t.label}
                            </button>
                          ))}
                        </div>
                      </div>
                    ))}
                  </div>
                </div>
              </div>

              {/* Right: tabbed editors */}
              <div className="flex-1 flex flex-col overflow-hidden">

                {/* Tabs */}
                <div className="flex items-center border-b border-primary/10 bg-primary/5 px-4 gap-1">
                  {(['metric', 'ddl'] as const).map(tab => (
                    <button key={tab} onClick={() => setEditorTab(tab)}
                      className={`px-4 py-2.5 text-xs font-bold uppercase tracking-wider transition-colors border-b-2 ${
                        editorTab === tab
                          ? 'border-primary text-primary'
                          : 'border-transparent text-slate-500 hover:text-slate-300'
                      }`}>
                      {tab === 'metric' ? (
                        <span className="flex items-center gap-1.5">
                          <span className="material-symbols-outlined text-sm">code</span>
                          Metric SQL
                        </span>
                      ) : (
                        <span className="flex items-center gap-1.5">
                          <span className="material-symbols-outlined text-sm">table</span>
                          Table DDL
                          {editingMetric.createTableSql?.trim() && (
                            <span className="w-1.5 h-1.5 rounded-full bg-emerald-400" />
                          )}
                        </span>
                      )}
                    </button>
                  ))}

                  {editorTab === 'metric' && (
                    <button onClick={handlePreview} disabled={previewing || !editingMetric.sql?.trim() || hasBlockingErrors}
                      className="ml-auto flex items-center gap-1.5 px-3 py-1 bg-primary/10 border border-primary/20 text-primary rounded-lg text-xs font-bold hover:bg-primary/20 disabled:opacity-40 transition-all">
                      {previewing
                        ? <span className="material-symbols-outlined text-sm animate-spin">refresh</span>
                        : <span className="material-symbols-outlined text-sm">play_arrow</span>}
                      {previewing ? 'Running…' : 'Preview'}
                    </button>
                  )}

                  {editorTab === 'ddl' && (
                    <span className="ml-auto text-[10px] text-slate-600 pr-1">
                      Executed before metric SQL — <code className="text-slate-400">IF NOT EXISTS</code> is auto-added
                    </span>
                  )}
                </div>

                {/* Editor area */}
                <div className="flex-1 overflow-hidden">
                  {editorTab === 'metric' ? (
                    <Editor height="100%" defaultLanguage="sql" theme="vs-dark"
                      value={editingMetric.sql ?? ''}
                      onChange={val => { setEditingMetric(m => ({ ...m, sql: val ?? '' })); setPreviewResult(null); }}
                      options={{ minimap: { enabled: false }, fontSize: 13, fontFamily: 'JetBrains Mono, monospace',
                        lineNumbers: 'on', scrollBeyondLastLine: false, padding: { top: 12, bottom: 12 },
                        wordWrap: 'on', suggest: { showKeywords: true } }}
                    />
                  ) : (
                    <Editor height="100%" defaultLanguage="sql" theme="vs-dark"
                      value={editingMetric.createTableSql ?? ''}
                      onChange={val => setEditingMetric(m => ({ ...m, createTableSql: val ?? '' }))}
                      options={{ minimap: { enabled: false }, fontSize: 13, fontFamily: 'JetBrains Mono, monospace',
                        lineNumbers: 'on', scrollBeyondLastLine: false, padding: { top: 12, bottom: 12 },
                        wordWrap: 'on' }}
                    />
                  )}
                </div>

                {/* SQL validation hints (metric tab only) */}
                {editorTab === 'metric' && <ValidationHints messages={sqlValidation} />}

                {/* DDL validation hints */}
                {editorTab === 'ddl' && <ValidationHints messages={ddlValidation} />}

                {/* Preview result (metric tab only) */}
                {editorTab === 'metric' && previewResult && (
                  <div className={`border-t px-4 py-3 text-xs font-mono ${
                    previewResult.error
                      ? 'border-red-500/20 bg-red-500/5 text-red-400'
                      : 'border-emerald-500/20 bg-emerald-500/5 text-emerald-400'
                  }`}>
                    {previewResult.error ? (
                      <span className="flex items-center gap-2">
                        <span className="material-symbols-outlined text-sm">error</span>
                        {previewResult.error}
                      </span>
                    ) : (
                      <span className="flex items-center gap-2">
                        <span className="material-symbols-outlined text-sm">check_circle</span>
                        metric_value = <strong>{String(previewResult.value)}</strong>
                        {Array.isArray(previewResult.rows) && previewResult.rows.length > 1 && (
                          <span className="text-emerald-600 ml-2">({previewResult.rows.length} rows total)</span>
                        )}
                      </span>
                    )}
                  </div>
                )}

                {/* DDL hint panel */}
                {editorTab === 'ddl' && !editingMetric.createTableSql?.trim() && (
                  <div className="border-t border-primary/10 px-4 py-3 text-xs text-slate-500 bg-primary/5 space-y-1">
                    <p className="font-bold text-slate-400">No DDL defined</p>
                    <p>The metric SQL will run against tables already registered in Flink.</p>
                    <p>
                      Select a <span className="text-primary">Kafka Topic</span> on the left to auto-generate a{' '}
                      <code className="text-slate-300">CREATE TABLE IF NOT EXISTS</code> template.
                    </p>
                  </div>
                )}
              </div>
            </div>

            {/* Modal footer */}
            <div className="flex items-center justify-end gap-3 px-6 py-4 border-t border-primary/10 bg-primary/5">
              <button onClick={() => setIsModalOpen(false)}
                className="px-4 py-2 text-sm font-bold text-slate-500 hover:text-slate-300 transition-colors">
                Cancel
              </button>
              {hasBlockingErrors && (
                <span className="flex items-center gap-1 text-xs text-red-400 mr-2">
                  <span className="material-symbols-outlined text-sm">error</span>
                  Fix errors before saving
                </span>
              )}
              <button onClick={handleSave} disabled={saving || hasBlockingErrors}
                className="flex items-center gap-2 bg-primary text-background-dark font-bold px-6 py-2 rounded-lg hover:brightness-110 disabled:opacity-50 disabled:cursor-not-allowed transition-all text-sm">
                {saving
                  ? <span className="material-symbols-outlined text-lg animate-spin">refresh</span>
                  : <span className="material-symbols-outlined text-lg">save</span>}
                {saving ? 'Saving…' : 'Save & Activate'}
              </button>
            </div>
          </div>
        </div>
      )}
    </div>
  );
};

export default Metrics;
