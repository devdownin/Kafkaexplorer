// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors

import React, { useState, useEffect, useCallback, useMemo } from 'react';
import { useNavigate } from 'react-router-dom';
import axios from 'axios';
import Editor, { useMonaco } from '@monaco-editor/react';
import '../monaco-setup';
import { AreaChart, Area, ResponsiveContainer, ReferenceLine, Tooltip, YAxis } from 'recharts';
import { useToast } from '../components/Toast';
import { useCatalog } from '../catalogStore';
import { describeApiError, type QueryErrorInfo } from './queryError';
import {
  // Recharts exporte lui aussi un `Tooltip` (celui des graphes) : le nôtre est aliasé pour que
  // le fichier dise lequel des deux il utilise à chaque endroit.
  Tooltip as InfoTooltip,
  PageHeader, Button, Stat, Select, EmptyState, CardSkeleton, TopicInput,
  Field, Input, Textarea, useConfirm,
  ErrorPanel,
} from '../components/ui';
import { describeQueryError } from './queryError';
import { clearDraft, readDraft, writeDraft } from '../draftStore';
import { copyText } from '../clipboard';
// La forme vit dans api/types.ts, où check-api-types.py la résout contre le record Java —
// une interface écrite dans la page est exactement ce qui a divergé sans bruit ailleurs.
import type { AuditHistory, MetricConfig, MetricSuggestion, MetricSuggestions, MetricTestResponse } from '../api/types';
import { SuggestionsPanel } from '../components/metrics/SuggestionsPanel';
import { readFlowChains } from './flowChains';
import { newerAuditNote, suggestionToDraft } from './metricSuggestions';

interface MetricTemplateDescriptor {
  type: string;
  label: string;
  description: string;
  supportedMetricTypes: string[];
  requiredParams: string[];
}

interface MetricLabelPreview {
  topic: string;
  timestamp: number | null;
  message: string | null;
  fields: Record<string, string>;
}

// ── Metric template metadata (mirrors the backend TEMPLATE_DESCRIPTORS) ──────
/** Hauteurs de l'ornement affiché tant qu'aucune donnée n'est arrivée. */
const PLACEHOLDER_BARS = [35, 58, 24, 71, 46, 88, 30, 62, 41, 77, 19, 54, 66, 28, 83, 37, 49, 74, 22, 60];

const RAW_SQL = 'RAW_SQL';

const DELTA_OPERATIONS: Array<{ value: string; label: string }> = [
  { value: 'LEFT_MINUS_RIGHT', label: 'Left − Right' },
  { value: 'ABS_DIFF',         label: '|Left − Right|' },
  { value: 'RATIO',            label: 'Left ÷ Right' },
  { value: 'PERCENT_GAP',      label: '(Left − Right) ÷ Right × 100' },
];

const EXECUTION_MODES: Array<{ value: string; label: string; note: string }> = [
  { value: 'TEMPLATE_BOUNDED_SCAN', label: 'Bounded scan (default)', note: 'Re-scans bounded data every refresh cycle.' },
  { value: 'FLINK_MANAGED_JOB',     label: 'Flink managed job (planned)', note: 'Continuous execution — planned; previews use a bounded scan for now.' },
];

// ── Type visual config ─────────────────────────────────────────────────────
const TYPE_META: Record<string, { color: string; bg: string; border: string; icon: string; badge: string }> = {
  GAUGE:     { color: 'text-primary',     bg: 'bg-primary/5',      border: 'border-primary/20',     icon: 'speed',       badge: 'bg-primary/10 text-primary' },
  COUNTER:   { color: 'text-success', bg: 'bg-success/5',  border: 'border-success/20', icon: 'add_circle',  badge: 'bg-success/10 text-success' },
  HISTOGRAM: { color: 'text-secondary',  bg: 'bg-secondary/5',   border: 'border-secondary/20',  icon: 'bar_chart',   badge: 'bg-secondary/10 text-secondary' },
  SUMMARY:   { color: 'text-warning',   bg: 'bg-warning/5',    border: 'border-warning/20',   icon: 'query_stats', badge: 'bg-warning/10 text-warning' },
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

function paramStr(params: Record<string, unknown>, key: string): string {
  const v = params[key];
  return v == null ? '' : String(v);
}

// Front-end mirror of the backend template validation (MetricService.validateMetric).
function validateTemplate(templateType: string, metricType: string,
                          params: Record<string, unknown>): ValidationMsg[] {
  const msgs: ValidationMsg[] = [];
  if (templateType === 'TOPIC_COUNT_DELTA') {
    if (!paramStr(params, 'leftSql').trim())  msgs.push({ level: 'error', text: 'Left query (leftSql) is required.' });
    if (!paramStr(params, 'rightSql').trim()) msgs.push({ level: 'error', text: 'Right query (rightSql) is required.' });
    if (metricType !== 'GAUGE')               msgs.push({ level: 'error', text: 'Topic Count Delta supports GAUGE metrics only.' });
  } else if (templateType === 'TOPIC_TRANSIT_LATENCY') {
    if (!paramStr(params, 'sourceSql').trim()) msgs.push({ level: 'error', text: 'Source query (sourceSql) is required.' });
    if (!paramStr(params, 'targetSql').trim()) msgs.push({ level: 'error', text: 'Target query (targetSql) is required.' });
    if (!['GAUGE', 'HISTOGRAM', 'SUMMARY'].includes(metricType))
      msgs.push({ level: 'error', text: 'Topic Transit Latency supports GAUGE, HISTOGRAM or SUMMARY.' });
    msgs.push({ level: 'info', text: 'Both queries must return match_key and event_time columns (event_time as ISO-8601 or epoch).' });
  } else if (templateType === 'CONSUMER_TIME_LAG') {
    if (!paramStr(params, 'topic').trim()) msgs.push({ level: 'error', text: 'A topic is required.' });
    // Le groupe est exigé, pas déduit : « le groupe le plus en retard » changerait d'une mesure à
    // l'autre, donc la série changerait de sujet sans le dire.
    if (!paramStr(params, 'group').trim()) msgs.push({ level: 'error', text: 'A consumer group is required — a delay is always somebody’s.' });
    if (metricType !== 'GAUGE')            msgs.push({ level: 'error', text: 'Consumer Lag in Time supports GAUGE metrics only.' });
    msgs.push({ level: 'info', text: 'Value in milliseconds: the age of the oldest message this group has not read. No SQL — committed offsets and record timestamps.' });
    msgs.push({ level: 'warning', text: 'Each refresh reads one record per lagging partition (bounded to 64 partitions, 8 s). A partition that cannot be read is reported as unknown, never as zero.' });
  }
  return msgs;
}

// ── Inline validation hint list ───────────────────────────────────────────

const HINT_ICONS: Record<ValidationMsg['level'], string> = { error: 'error', warning: 'warning', info: 'info' };
const HINT_COLORS: Record<ValidationMsg['level'], string> = {
  error:   'text-error',
  warning: 'text-warning',
  info:    'text-on-surface-variant',
};

const ValidationHints: React.FC<{ messages: ValidationMsg[] }> = ({ messages }) => {
  if (messages.length === 0) return null;
  return (
    <div className="flex flex-col gap-1 px-4 py-2.5 border-t border-outline-variant/60 bg-background-dark/70">
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
    topic.replace(/[.-]/g, '_').replace(/[^a-zA-Z0-9_]/g, '').replace(/^_+|_+$/g, '') || 'my_table'
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
      color: 'text-success',
      items: [
        { label: 'Total events',    sql: `SELECT COUNT(*) AS metric_value\nFROM ${table}` },
        { label: 'Total per status',sql: `SELECT COUNT(*) AS metric_value, status\nFROM ${table}\nGROUP BY status` },
        { label: 'Total revenue',   sql: `SELECT SUM(amount) AS metric_value\nFROM ${table}` },
      ],
    },
    {
      group: 'HISTOGRAM — value distribution',
      color: 'text-secondary',
      items: [
        { label: 'Amount observations',  sql: `SELECT CAST(amount AS DOUBLE) AS metric_value\nFROM ${table}\n-- one row per event → engine auto-buckets the values` },
        { label: 'Payload size',         sql: `SELECT CAST(LENGTH(value) AS DOUBLE) AS metric_value\nFROM ${table}\n-- one row per event → engine auto-buckets the values` },
      ],
    },
    {
      group: 'SUMMARY — quantile observations',
      color: 'text-warning',
      items: [
        { label: 'Amount observations',  sql: `SELECT CAST(amount AS DOUBLE) AS metric_value\nFROM ${table}\n-- one row per event → Micrometer computes P50/P75/P90/P95/P99` },
        { label: 'Latency observations', sql: `SELECT CAST(latency_ms AS DOUBLE) AS metric_value\nFROM ${table}\n-- one row per event; replace latency_ms with your numeric column` },
      ],
    },
  ];
}

// ─────────────────────────────────────────────────────────────────────────────

/**
 * Ce qu'affiche le pied de carte quand il n'y a pas de SQL : le gabarit et ce qu'il compare.
 *
 * Une métrique de gabarit n'a pas de requête à montrer — dire « pas de SQL » n'apprendrait rien,
 * alors que nommer les deux topics dit exactement ce que la métrique mesure.
 */
function describeTemplate(metric: MetricConfig): string {
  const params = metric.templateParams ?? {};
  const at = (key: string) => (typeof params[key] === 'string' ? params[key] as string : null);
  const from = at('sourceTopic') ?? at('leftTopic') ?? at('topic');
  const to = at('targetTopic') ?? at('rightTopic') ?? at('group');
  const pair = from && to ? `${from} → ${to}` : from ?? '';
  return [metric.templateType ?? 'TEMPLATE', pair].filter(Boolean).join(' · ');
}

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
  ok:       { dot: 'bg-success', text: 'text-success', label: 'Healthy' },
  warning:  { dot: 'bg-warning',   text: 'text-warning',   label: 'Warning' },
  critical: { dot: 'bg-error',     text: 'text-error',     label: 'Critical' },
  error:    { dot: 'bg-error',     text: 'text-error',     label: 'Error' },
  pending:  { dot: 'bg-outline',   text: 'text-on-surface-variant',   label: 'Pending' },
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

  const strokeColor = status === 'critical' ? '#f58c8c' : status === 'warning' ? '#f5c264' : '#a3adff';

  return (
    <div className="flex flex-col rounded-xl bg-surface-container ring-1 ring-white/[0.045] overflow-hidden card-hover">
      {/* Header */}
      <div className="flex items-center justify-between px-4 pt-4 pb-2 gap-2">
        <div className="flex items-center gap-2 min-w-0">
          <span className={`w-2 h-2 rounded-full shrink-0 ${st.dot} ${status === 'ok' ? 'animate-pulse' : ''}`} />
          <h3 className="font-semibold text-on-surface truncate font-mono text-[13px]">{metric.name}</h3>
          <span className={`px-1.5 py-0.5 rounded text-[9px] font-bold uppercase shrink-0 ${tm.badge}`}>
            {metric.type}
          </span>
          {metric.createTableSql && (
            <InfoTooltip content="This metric carries a CREATE TABLE statement, run before its own SQL.">
              <span tabIndex={0}
                className="px-1.5 py-0.5 rounded text-[9px] font-bold uppercase shrink-0 bg-surface-container-high text-on-surface-variant">
                DDL
              </span>
            </InfoTooltip>
          )}
          {(metric.labelFields?.length ?? 0) > 0 && (
            <InfoTooltip content="Prometheus labels taken from the latest message, so the metric can be split by one of its fields.">
              <span tabIndex={0}
                className="px-1.5 py-0.5 rounded text-[9px] font-bold uppercase shrink-0 bg-primary/10 text-primary">
                {metric.labelFields!.length} labels
              </span>
            </InfoTooltip>
          )}
        </div>
        <div className="flex items-center gap-1 shrink-0">
          <button onClick={onRefresh} disabled={refreshing} title="Refresh now" aria-label="Refresh this metric now"
            className="p-1 text-on-surface-variant hover:text-primary transition-colors disabled:opacity-40">
            <span className={`material-symbols-outlined text-base ${refreshing ? 'animate-spin' : ''}`}>refresh</span>
          </button>
          <button onClick={onEdit} title="Edit" aria-label="Edit this metric"
            className="p-1 text-on-surface-variant hover:text-primary transition-colors">
            <span className="material-symbols-outlined text-base">edit</span>
          </button>
          <button onClick={onDelete} title="Delete" aria-label="Delete this metric"
            className="p-1 text-on-surface-variant hover:text-error transition-colors">
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
          {metric.description && <p className="text-xs text-on-surface-variant truncate">{metric.description}</p>}
          {metric.warningThreshold !== null && (
            <span className="flex items-center gap-0.5 text-[10px] text-warning font-mono shrink-0">
              <span className="material-symbols-outlined text-[11px]">warning</span>≥ {metric.warningThreshold.toLocaleString()}
            </span>
          )}
          {metric.criticalThreshold !== null && (
            <span className="flex items-center gap-0.5 text-[10px] text-error font-mono shrink-0">
              <span className="material-symbols-outlined text-[11px]">emergency</span>≥ {metric.criticalThreshold.toLocaleString()}
            </span>
          )}
        </div>
        {metric.errorMessage && (
          // Titre lisible (voir queryError.ts) ; le message brut du serveur, qui dit *quelle*
          // colonne ou quelle table pose problème, se lit dans l'infobulle — au survol comme au
          // focus, puisque c'est souvent la seule information exploitable.
          <InfoTooltip content={metric.errorMessage}>
            <p tabIndex={0} className="text-[10px] text-error mt-1 line-clamp-2 rounded">
              <span className="material-symbols-outlined text-[11px] align-middle">error</span>{' '}{describeQueryError(metric.errorMessage).title}
            </p>
          </InfoTooltip>
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
            <div className="rounded-lg overflow-hidden border border-outline-variant/60 bg-background-dark/60">
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
                    <ReferenceLine y={metric.warningThreshold} stroke="#f5c264" strokeDasharray="4 3" strokeWidth={1}
                      label={{ value: 'warn', position: 'insideTopRight', fontSize: 8, fill: '#f5c264', dy: -2 }} />
                  )}
                  {metric.criticalThreshold !== null && (
                    <ReferenceLine y={metric.criticalThreshold} stroke="#f58c8c" strokeDasharray="4 3" strokeWidth={1}
                      label={{ value: 'crit', position: 'insideTopRight', fontSize: 8, fill: '#f58c8c', dy: -2 }} />
                  )}
                  <Area type="monotone" dataKey="v" stroke={strokeColor} strokeWidth={2}
                    fill={`url(#grad-${metric.id})`} dot={false}
                    activeDot={{ r: 3, fill: strokeColor, strokeWidth: 0 }} isAnimationActive={false} />
                  <Tooltip cursor={{ stroke: strokeColor, strokeWidth: 1, strokeOpacity: 0.3 }}
                    content={({ active, payload }) =>
                      active && payload?.length ? (
                        <div className="bg-surface-container-low border border-outline-variant px-2 py-1 rounded-lg text-[10px] font-mono" style={{ color: strokeColor }}>
                          {Number(payload[0].value).toLocaleString(undefined, { maximumFractionDigits: 2 })}
                        </div>
                      ) : null
                    }
                  />
                </AreaChart>
              </ResponsiveContainer>
              <div className="flex items-center justify-between px-3 py-1.5 border-t border-primary/5 text-[10px] font-mono">
                <span className="text-outline">min <span className="text-on-surface-variant">{minV.toLocaleString(undefined, { maximumFractionDigits: 1 })}</span></span>
                <span className="text-outline">{chartData.length} pts</span>
                <span className="text-outline">max <span className="text-on-surface-variant">{maxV.toLocaleString(undefined, { maximumFractionDigits: 1 })}</span></span>
              </div>
            </div>
          );
        })() : (
          <div className="rounded-lg border border-outline-variant/60 bg-background-dark/60 h-24 flex flex-col items-center justify-center gap-1.5">
            <div className="flex items-end gap-0.5 h-6 opacity-20">
              {/* Ornement « en attente » : des hauteurs fixes plutôt qu'un tirage au sort à
                  chaque rendu, qui rendait le rendu impur et faisait frémir les barres. */}
              {PLACEHOLDER_BARS.map((height, i) => (
                <div key={i} className="w-1 bg-primary rounded-sm" style={{ height: `${height}%` }} />
              ))}
            </div>
            <span className="text-[10px] text-outline uppercase tracking-wider">Waiting for data…</span>
          </div>
        )}
      </div>

      {/* SQL footer — ou ce qui en tient lieu.

          Une métrique de gabarit n'a pas de SQL : ses paramètres SONT la requête, et le champ
          arrive à `null` du serveur. La carte appelait `metric.sql.replace(…)` dessus, ce qui
          faisait tomber toute la page — pas seulement la carte — dès qu'une métrique de gabarit
          était enregistrée. Le type l'annonçait `string`, ce qu'il n'a jamais été. */}
      <div className="group border-t border-outline-variant/60 bg-background-dark/40 px-4 py-2.5 flex items-start gap-2">
        <span className="material-symbols-outlined text-primary/40 text-base shrink-0 mt-0.5">code</span>
        <pre className="flex-1 text-[10px] font-mono text-on-surface-variant truncate leading-relaxed whitespace-nowrap overflow-hidden">
          {metric.sql
            ? metric.sql.replace(/\s+/g, ' ')
            : describeTemplate(metric)}
        </pre>
        {metric.sql && (
          <button onClick={() => void copyText(metric.sql ?? '').then(ok =>
              toast(ok ? 'SQL copied' : 'Could not copy to the clipboard', ok ? 'success' : 'error'))}
            title="Copy SQL" aria-label="Copy the metric SQL" className="shrink-0 text-outline hover:text-primary transition-colors opacity-0 group-hover:opacity-100">
            <span className="material-symbols-outlined text-base">content_copy</span>
          </button>
        )}
      </div>

      {/* Footer */}
      <div className="px-4 py-1.5 flex items-center justify-between border-t border-primary/5">
        <span className="text-[10px] text-outline flex items-center gap-1">
          <span className="material-symbols-outlined text-[11px]">schedule</span>
          {relativeTime(metric.lastUpdateTime)}
        </span>
        <span className={`text-[10px] font-bold ${st.text}`}>{st.label}</span>
      </div>
    </div>
  );
};

// ── Template parameter editor (shown in place of the raw SQL editor) ─────────
const ParamSql: React.FC<{
  label: string; hint?: string; value: string; placeholder: string;
  onChange: (v: string) => void;
}> = ({ label, hint, value, placeholder, onChange }) => (
  <Field label={label} description={hint}>
    {p => (
      <Textarea
        {...p}
        value={value}
        onChange={e => onChange(e.target.value)}
        placeholder={placeholder}
        rows={4}
        spellCheck={false}
        className="font-mono text-[12px]"
      />
    )}
  </Field>
);

/** Étiquette Prometheus libre : un nom de topic, donc suggéré depuis le catalogue. */
const ParamTopic: React.FC<{
  label: string; value: string; placeholder: string; onChange: (v: string) => void;
}> = ({ label, value, placeholder, onChange }) => (
  <Field label={label}>
    {p => <TopicInput {...p} value={value} onChange={onChange} placeholder={placeholder} />}
  </Field>
);

const TemplateParamsEditor: React.FC<{
  templateType: string;
  params: Record<string, unknown>;
  executionMode: string;
  table: string;
  setParam: (key: string, value: string) => void;
  setExecutionMode: (mode: string) => void;
}> = ({ templateType, params, executionMode, table, setParam, setExecutionMode }) => {
  const p = (k: string) => paramStr(params, k);
  return (
    <div className="h-full overflow-y-auto p-5 space-y-4">
      {templateType === 'CONSUMER_TIME_LAG' ? (
        <>
          <ParamTopic label="Topic" value={p('topic')} onChange={v => setParam('topic', v)} placeholder="demo.payments" />
          <Field
            label="Consumer group"
            description="Named, never resolved to “the worst one”: that choice would move between refreshes and the series would change subject without saying so."
          >
            {f => (
              <Input {...f} value={p('group')} onChange={e => setParam('group', e.target.value)}
                placeholder="payments-api" spellCheck={false} className="font-mono text-[12px]" />
            )}
          </Field>
          <Field
            label="Across partitions"
            description="The worst partition is what an alert is set on; a mean hides it behind the healthy ones."
          >
            {f => (
              <Select {...f} value={p('aggregation') || 'MAX'} onChange={e => setParam('aggregation', e.target.value)}>
                <option value="MAX" className="bg-[#12151a] text-on-surface">Worst partition (MAX)</option>
                <option value="AVG" className="bg-[#12151a] text-on-surface">Mean over partitions (AVG)</option>
              </Select>
            )}
          </Field>
        </>
      ) : templateType === 'TOPIC_COUNT_DELTA' ? (
        <>
          <ParamSql label="Left query — metric_value" value={p('leftSql')} onChange={v => setParam('leftSql', v)}
            hint="Bounded query returning a single metric_value."
            placeholder={`SELECT COUNT(*) AS metric_value\nFROM ${table}`} />
          <ParamSql label="Right query — metric_value" value={p('rightSql')} onChange={v => setParam('rightSql', v)}
            hint="Compared against the left query."
            placeholder={`SELECT COUNT(*) AS metric_value\nFROM other_table`} />
          <Field label="Operation">
            {f => (
              <Select {...f} value={p('operation') || 'LEFT_MINUS_RIGHT'} onChange={e => setParam('operation', e.target.value)}>
                {DELTA_OPERATIONS.map(o => (
                  <option key={o.value} value={o.value} className="bg-[#12151a] text-on-surface">{o.label}</option>
                ))}
              </Select>
            )}
          </Field>
          <div className="grid grid-cols-2 gap-3">
            <ParamTopic label="Left topic (label)"  value={p('leftTopic')}  onChange={v => setParam('leftTopic', v)}  placeholder="optional" />
            <ParamTopic label="Right topic (label)" value={p('rightTopic')} onChange={v => setParam('rightTopic', v)} placeholder="optional" />
          </div>
        </>
      ) : (
        <>
          <ParamSql label="Source query — match_key, event_time" value={p('sourceSql')} onChange={v => setParam('sourceSql', v)}
            hint="Emit one row per source event with a match_key and an event_time (ISO-8601 or epoch)."
            placeholder={`SELECT order_id AS match_key,\n       created_at AS event_time\nFROM ${table}`} />
          <ParamSql label="Target query — match_key, event_time" value={p('targetSql')} onChange={v => setParam('targetSql', v)}
            hint="Downstream events, matched on match_key; latency = target − source."
            placeholder={`SELECT order_id AS match_key,\n       processed_at AS event_time\nFROM target_table`} />
          <div className="grid grid-cols-2 gap-3">
            <ParamTopic label="Source topic (label)" value={p('sourceTopic')} onChange={v => setParam('sourceTopic', v)} placeholder="optional" />
            <ParamTopic label="Target topic (label)" value={p('targetTopic')} onChange={v => setParam('targetTopic', v)} placeholder="optional" />
          </div>
        </>
      )}

      <div className="border-t border-outline-variant/60 pt-4">
        <Field
          label="Execution Mode"
          description={EXECUTION_MODES.find(m => m.value === (executionMode || 'TEMPLATE_BOUNDED_SCAN'))?.note}
        >
          {f => (
            <Select {...f} value={executionMode || 'TEMPLATE_BOUNDED_SCAN'} onChange={e => setExecutionMode(e.target.value)}>
              {EXECUTION_MODES.map(m => (
                <option key={m.value} value={m.value} className="bg-[#12151a] text-on-surface">{m.label}</option>
              ))}
            </Select>
          )}
        </Field>
      </div>
    </div>
  );
};

// ─────────────────────────────────────────────────────────────────────────────

/** Clé du brouillon de l'éditeur — voir `draftStore.ts`. */
const EDITOR_DRAFT = 'metrics:editor';

/** Ce qu'il faut pour rouvrir le modal exactement là où il a été quitté. */
interface EditorDraft {
  metric: Partial<MetricConfig>;
  topic: string;
  tab: 'metric' | 'ddl';
  nameIsAuto: boolean;
}

const EMPTY_METRIC: Partial<MetricConfig> = {
  name: '', type: 'GAUGE',
  sql: 'SELECT COUNT(*) AS metric_value FROM my_table',
  description: '',
  warningThreshold: null, criticalThreshold: null,
  createTableSql: '',
  labelTopic: '',
  labelFields: [],
  templateType: RAW_SQL,
  templateParams: {},
  executionMode: 'SQL',
};

const Metrics: React.FC = () => {
  const { toast } = useToast();
  const confirm = useConfirm();
  const navigate = useNavigate();
  const [metrics, setMetrics]           = useState<MetricConfig[]>([]);
  const [metadata, setMetadata]         = useState<Record<string, string[]>>({});
  // Le catalogue partagé (alimenté par le sondage /api/dashboard de Layout) évite une
  // seconde requête vers le même endpoint et reste rafraîchi toutes les 30 s.
  const { topics } = useCatalog();
  const [bootstrapServers, setBootstrapServers] = useState<string>('localhost:9092');
  const [loading, setLoading]           = useState(true);
  /*
   * L'éditeur de métrique est du SQL écrit à la main, parfois long, et il vivait entièrement dans
   * l'état du modal : aller vérifier un nom de colonne dans l'explorateur de topics le perdait.
   * Le brouillon est relu au montage et rouvre le modal tel qu'il était.
   */
  const [restoredEditor] = useState(() => readDraft<EditorDraft | null>(EDITOR_DRAFT, null));
  const [isModalOpen, setIsModalOpen]   = useState(restoredEditor !== null);
  const [editingMetric, setEditingMetric] = useState<Partial<MetricConfig>>(restoredEditor?.metric ?? EMPTY_METRIC);
  const [selectedTopic, setSelectedTopic] = useState<string>(restoredEditor?.topic ?? '');
  const [editorTab, setEditorTab]       = useState<'metric' | 'ddl'>(restoredEditor?.tab ?? 'metric');
  const [saving, setSaving]             = useState(false);
  const [previewing, setPreviewing]     = useState(false);
  const [previewResult, setPreviewResult] = useState<{ value?: unknown; rows?: unknown[]; error?: string; summary?: Record<string, unknown> } | null>(null);
  // Erreur de prévisualisation classée (titre lisible + piste) — voir queryError.ts.
  const previewError = useMemo(
    () => (previewResult?.error ? describeQueryError(previewResult.error) : null),
    [previewResult],
  );
  const [templates, setTemplates]       = useState<MetricTemplateDescriptor[]>([]);
  const [refreshingId, setRefreshingId] = useState<string | null>(null);
  const [filterType, setFilterType]     = useState<string>('all');
  const [filterStatus, setFilterStatus] = useState<string>('all');
  const [nameIsAuto, setNameIsAuto]     = useState(restoredEditor?.nameIsAuto ?? false);
  const [labelPreview, setLabelPreview] = useState<MetricLabelPreview | null>(null);
  const [labelPreviewLoading, setLabelPreviewLoading] = useState(false);

  const monaco = useMonaco();
  /** Échec d'enregistrement, gardé sous les yeux plutôt que dans un toast fugace. */
  const [saveError, setSaveError] = useState<QueryErrorInfo | null>(null);
  const [templatesError, setTemplatesError] = useState<QueryErrorInfo | null>(null);
  /*
   * Les KPI proposés à partir de ce que le cluster a montré de lui-même : l'audit côté serveur,
   * les traces Stream Flow que ce navigateur a gardées. Les secondes voyagent dans le corps de la
   * requête — le serveur n'en a jamais vu une — pour qu'une seule dérivation réponde des deux.
   */
  const [suggestions, setSuggestions] = useState<MetricSuggestions | null>(null);
  const [suggestionsLoading, setSuggestionsLoading] = useState(true);
  const [suggestionsError, setSuggestionsError] = useState<QueryErrorInfo | null>(null);
  /*
   * L'historique d'audit, lu pour une seule question : le dernier run est-il celui dont ces
   * propositions sont issues ? Les résumés, pas les rapports — un rapport porte une entrée par
   * topic, et cette page n'a besoin que d'un identifiant et d'une date.
   */
  const [auditHistory, setAuditHistory] = useState<AuditHistory | null>(null);

  const fetchMetrics = useCallback(async () => {
    try {
      const res = await axios.get<MetricConfig[]>('/api/metrics');
      setMetrics(res.data);
    } catch {
      toast('Failed to fetch metrics', 'error');
    } finally {
      setLoading(false);
    }
  // eslint-disable-next-line react-hooks/exhaustive-deps -- toast is stable
  }, []);

  const fetchSuggestions = useCallback(async () => {
    setSuggestionsLoading(true);
    setSuggestionsError(null);
    try {
      const res = await axios.post<MetricSuggestions>('/api/metrics/suggestions', {
        flowChains: readFlowChains(),
        // Le mapping validé par Process Mining vit dans le brouillon de cette page-là ; c'est lui
        // qui connaît la vraie clé de corrélation et le champ de statut de chaque topic.
        fieldMappingId: readDraft<string | null>('pm:mapping', null),
      });
      setSuggestions(res.data);
    } catch (err) {
      // Un panneau vide se lirait « ce cluster n'appelle aucun KPI », qui est l'inverse de
      // « la dérivation a échoué ». La raison du serveur reste à l'écran.
      setSuggestionsError(describeApiError(err, 'Failed to derive suggested KPIs.'));
    } finally {
      setSuggestionsLoading(false);
    }
  }, []);

  const fetchAuditHistory = useCallback(async () => {
    try {
      const res = await axios.get<AuditHistory>('/api/audit/history');
      setAuditHistory(res.data);
    } catch {
      // Muet : ne pas pouvoir dire « un audit plus récent existe » n'est pas une panne de cette
      // page, et une erreur de plus ne dirait rien de ce que les cartes affichent.
      setAuditHistory(null);
    }
  }, []);

  useEffect(() => {
    // eslint-disable-next-line react-hooks/set-state-in-effect -- chargement au montage
    fetchMetrics();
    void fetchSuggestions();
    void fetchAuditHistory();
    axios.get<Record<string, string[]>>('/api/metrics/metadata').then(r => setMetadata(r.data)).catch(() => { toast('Failed to load table metadata', 'error'); });
    axios.get<{ bootstrapServers: string }>('/api/config').then(r => {
      if (r.data.bootstrapServers) setBootstrapServers(r.data.bootstrapServers);
    }).catch(() => {});
    // `.catch(() => {})` laissait la liste de gabarits vide sans que rien ne le dise.
    axios.get<MetricTemplateDescriptor[]>('/api/metrics/templates')
      .then(r => setTemplates(r.data))
      .catch(err => setTemplatesError(describeApiError(err, 'Failed to load metric templates.')));
    const iv = setInterval(fetchMetrics, 15000);
    return () => clearInterval(iv);
  // eslint-disable-next-line react-hooks/exhaustive-deps -- poll on mount; fetchMetrics/toast are stable
  }, []);

  useEffect(() => {
    if (!isModalOpen || !selectedTopic) {
      // eslint-disable-next-line react-hooks/set-state-in-effect -- prévisualisation demandée au serveur
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
  // eslint-disable-next-line react-hooks/exhaustive-deps -- toast is stable
  }, [isModalOpen, selectedTopic]);

  /*
   * Le brouillon ne vit qu'avec le modal : fermer, c'est renoncer, et un modal qui se rouvrirait
   * tout seul sur une métrique abandonnée serait une surprise, pas un service. L'enregistrement
   * ferme le modal, donc efface aussi.
   */
  useEffect(() => {
    if (isModalOpen) {
      writeDraft(EDITOR_DRAFT, {
        metric: editingMetric, topic: selectedTopic, tab: editorTab, nameIsAuto,
      } satisfies EditorDraft);
    } else {
      clearDraft(EDITOR_DRAFT);
    }
  }, [isModalOpen, editingMetric, selectedTopic, editorTab, nameIsAuto]);

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

  /**
   * Ouvre l'éditeur sur une proposition. Elle arrive complète — SQL ou paramètres de gabarit,
   * seuils, description — mais reste une proposition : rien n'est enregistré tant que le geste
   * n'est pas fait, et la prévisualisation est là pour vérifier la colonne de clé déduite.
   */
  /*
   * Relu au retour sur l'onglet, pas en boucle : l'audit se lance depuis une autre page, donc le
   * moment où la réponse peut avoir changé est exactement celui où l'on revient ici.
   */
  useEffect(() => {
    const onFocus = () => void fetchAuditHistory();
    window.addEventListener('focus', onFocus);
    return () => window.removeEventListener('focus', onFocus);
  }, [fetchAuditHistory]);

  const newerAudit = useMemo(() => newerAuditNote(suggestions, auditHistory), [suggestions, auditHistory]);

  const openSuggestion = (suggestion: MetricSuggestion) => {
    const draft = suggestionToDraft(suggestion);
    setEditingMetric({ ...EMPTY_METRIC, ...draft });
    setSelectedTopic(draft.labelTopic ?? '');
    setNameIsAuto(false);
    setEditorTab('metric');
    setPreviewResult(null);
    setSaveError(null);
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

  // ── Template mode ─────────────────────────────────────────────────────────
  const templateType = editingMetric.templateType ?? RAW_SQL;
  const isTemplate   = templateType !== RAW_SQL;
  const templateParams = editingMetric.templateParams ?? {};
  const currentDescriptor = templates.find(t => t.type === templateType);
  const allowedTypes = isTemplate
    ? (currentDescriptor?.supportedMetricTypes ?? ['GAUGE'])
    : ['GAUGE', 'COUNTER', 'HISTOGRAM', 'SUMMARY'];

  const setParam = (key: string, value: string) =>
    setEditingMetric(m => ({ ...m, templateParams: { ...(m.templateParams ?? {}), [key]: value } }));

  const setExecutionMode = (mode: string) =>
    setEditingMetric(m => ({ ...m, executionMode: mode }));

  const onTemplateTypeChange = (tt: string) => {
    setPreviewResult(null);
    setEditingMetric(m => {
      const desc = templates.find(t => t.type === tt);
      const supported = desc?.supportedMetricTypes ?? ['GAUGE'];
      const nextType = tt !== RAW_SQL && !supported.includes(m.type ?? 'GAUGE') ? supported[0] : (m.type ?? 'GAUGE');
      return {
        ...m,
        templateType: tt,
        type: nextType,
        name: nameIsAuto ? buildAutoName(nextType, selectedTopic) : m.name,
        executionMode: tt === RAW_SQL ? 'SQL'
          : (m.executionMode && m.executionMode !== 'SQL' ? m.executionMode : 'TEMPLATE_BOUNDED_SCAN'),
        templateParams: tt === RAW_SQL ? {} : (m.templateParams ?? {}),
      };
    });
    if (tt !== RAW_SQL) setEditorTab('metric');
  };

  // ── Live validation (derived, no state needed) ────────────────────────────
  const nameValidationBase  = validateMetricName(editingMetric.name ?? '');
  // U10 — warn (non-blocking) when the name collides with a different existing metric: the
  // metric_name Prometheus label would then be shared across two distinct series.
  const nameCollision = (editingMetric.name ?? '').trim().length > 0
    && metrics.some(m => m.name === editingMetric.name?.trim() && m.id !== editingMetric.id);
  const nameValidation: ValidationMsg[] = nameCollision
    ? [...nameValidationBase, { level: 'warning', text: 'Another metric already uses this name — the metric_name label will be shared across both series.' }]
    : nameValidationBase;
  const sqlValidation       = isTemplate ? [] : validateMetricSql(editingMetric.sql ?? '', editingMetric.type ?? 'GAUGE');
  const templateValidation  = isTemplate ? validateTemplate(templateType, editingMetric.type ?? 'GAUGE', templateParams) : [];
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
  const hasBlockingErrors = [...nameValidation, ...sqlValidation, ...templateValidation, ...ddlValidation, ...thresholdValidation]
    .some(m => m.level === 'error');

  // ── Save ──────────────────────────────────────────────────────────────────
  const handleSave = async () => {
    if (!editingMetric.name?.trim()) { toast('Name is required', 'error'); return; }
    if (!isTemplate && !editingMetric.sql?.trim()) { toast('SQL is required', 'error'); return; }
    if (hasBlockingErrors) {
      const first = [...nameValidation, ...sqlValidation, ...templateValidation, ...ddlValidation, ...thresholdValidation].find(m => m.level === 'error');
      toast(first!.text, 'error');
      return;
    }
    setSaving(true);
    setSaveError(null);
    try {
      await axios.post('/api/metrics', editingMetric);
      toast('Metric saved', 'success');
      setIsModalOpen(false);
      fetchMetrics();
      // Ce qui vient d'être enregistré couvre peut-être une proposition : sans ce rappel, la
      // carte resterait à proposer un KPI désormais en place.
      void fetchSuggestions();
    } catch (err) {
      // Un toast disparaît derrière le modal en trois secondes, et `catch {}` jetait la seule
      // chose utile : la raison du refus — quelle colonne SQL est inconnue, quelle DDL ne compile
      // pas. Elle reste affichée dans le modal, là où l'on peut corriger.
      setSaveError(describeApiError(err, 'Failed to save metric.'));
    } finally {
      setSaving(false);
    }
  };

  const handleDelete = async (id: string) => {
    const metric = metrics.find(m => m.id === id);
    const ok = await confirm({
      title: 'Delete this metric?',
      description: metric ? <>The metric <span className="font-mono text-on-surface">{metric.name}</span> will stop being scheduled and exported to Prometheus.</> : 'This metric will stop being scheduled and exported to Prometheus.',
      confirmLabel: 'Delete',
      tone: 'danger',
      icon: 'delete',
    });
    if (!ok) return;
    try {
      await axios.delete(`/api/metrics/${id}`);
      toast('Metric deleted', 'success');
      setMetrics(prev => prev.filter(m => m.id !== id));
    } catch {
      toast('Failed to delete metric', 'error');
    }
  };

  const handlePreview = async () => {
    if (!isTemplate && !editingMetric.sql?.trim()) return;
    setPreviewing(true);
    setPreviewResult(null);
    try {
      // Preview through the template endpoint so the attached CREATE TABLE DDL is executed first
      // (mirrors the scheduled refresh) and the value is computed with the metric's real type
      // (or template semantics — count delta, transit latency).
      const res = await axios.post<MetricTestResponse>(
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
      <PageHeader
        title="Business Metrics"
        description="Flink SQL queries scheduled continuously — values exported to Prometheus."
        actions={
          <>
            <Button variant="ghost" size="sm" icon="refresh" onClick={fetchMetrics} aria-label="Refresh all">Refresh</Button>
            <Button variant="ghost" size="sm" icon="help" onClick={() => navigate('/metrics/help')} aria-label="Help">Help</Button>
            <Button variant="primary" icon="add" onClick={() => openEdit()}>Add metric</Button>
          </>
        }
      />

      {/* Summary bar */}
      <div className="grid grid-cols-2 sm:grid-cols-4 gap-3">
        <Stat label="Total" value={metrics.length} />
        <Stat label="Healthy" value={counts.ok} tone={counts.ok > 0 ? 'success' : 'none'} />
        <Stat label="Warning" value={counts.warning} tone={counts.warning > 0 ? 'warning' : 'none'} />
        <Stat label="Critical" value={counts.critical + counts.error} tone={(counts.critical + counts.error) > 0 ? 'error' : 'none'} />
      </div>

      {/* Filters */}
      {!loading && metrics.length > 0 && (
        <div className="flex items-center gap-3 flex-wrap">
          <Select value={filterType} onChange={e => setFilterType(e.target.value)} className="w-auto" aria-label="Filter by type">
            <option value="all">All types</option>
            <option value="GAUGE">GAUGE</option>
            <option value="COUNTER">COUNTER</option>
            <option value="HISTOGRAM">HISTOGRAM</option>
            <option value="SUMMARY">SUMMARY</option>
          </Select>
          <Select value={filterStatus} onChange={e => setFilterStatus(e.target.value)} className="w-auto" aria-label="Filter by status">
            <option value="all">All statuses</option>
            <option value="error">Error</option>
            <option value="critical">Critical</option>
            <option value="warning">Warning</option>
            <option value="ok">Healthy</option>
            <option value="pending">Pending</option>
          </Select>
          {(filterType !== 'all' || filterStatus !== 'all') && (
            <button onClick={() => { setFilterType('all'); setFilterStatus('all'); }}
              className="text-[12px] text-on-surface-variant hover:text-on-surface transition-colors flex items-center gap-1">
              <span className="material-symbols-outlined text-[16px]">close</span>Clear filters
            </button>
          )}
          <span className="text-[12px] text-outline ml-auto tabular-nums">
            {filteredMetrics.length} / {metrics.length} metrics · sorted by severity
          </span>
        </div>
      )}

      {/* Content */}
      {loading ? (
        <div className="grid grid-cols-1 md:grid-cols-2 xl:grid-cols-3 gap-4" role="status" aria-label="Loading metrics">
          {Array.from({ length: 6 }).map((_, i) => <CardSkeleton key={i} lines={2} />)}
        </div>
      ) : metrics.length === 0 ? (
        <EmptyState
          icon="monitoring"
          title="No metrics yet"
          description={topics.length === 0
            ? 'No Kafka topics found — make sure the broker is reachable. Pick a template below once topics are available.'
            : 'Pick one of the quick-start templates below, or create your own.'}
          action={<Button variant="primary" icon="add" onClick={() => openEdit()}>Add metric</Button>}
        />
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
            <div className="col-span-full text-center py-12 text-on-surface-variant text-sm">
              No metrics match the current filters.
            </div>
          )}
        </div>
      )}

      {/* ── KPI proposés à partir de ce qui a été mesuré ────────────────────
          Au-dessus des gabarits génériques, parce qu'une proposition qui nomme un topic de ce
          cluster et la mesure dont elle sort vaut mieux qu'un COUNT(*) sur la première table
          trouvée — et en dessous des métriques existantes, qui restent le sujet de la page. */}
      <SuggestionsPanel
        response={suggestions}
        loading={suggestionsLoading}
        error={suggestionsError}
        newerAudit={newerAudit}
        onRefresh={() => void fetchSuggestions()}
        onAdopt={openSuggestion}
      />

      {/* ── Quick-start templates — always visible ─────────────────────────── */}
      {!loading && (
        <div>
          <p className="text-xs font-bold text-on-surface-variant uppercase tracking-widest mb-3">Quick-start templates</p>
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
                    <p className="font-bold text-on-surface text-sm">{ex.title}</p>
                    <p className="text-xs text-on-surface-variant mt-0.5 leading-relaxed">{ex.description}</p>
                  </div>
                  <pre className="text-[10px] font-mono text-on-surface-variant bg-black/20 rounded-lg p-2 overflow-hidden line-clamp-4 whitespace-pre-wrap">
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
        <div className="fixed inset-0 z-50 flex items-center justify-center p-4 glass-overlay"
          role="dialog" aria-modal="true" onClick={() => setIsModalOpen(false)}>
          {/* Un vrai <form> : Entrée depuis les champs du panneau de gauche enregistre.
              Tous les boutons internes déclarent type="button", seul « Save » soumet. */}
          <form
            noValidate
            onSubmit={e => { e.preventDefault(); if (!hasBlockingErrors) void handleSave(); }}
            className="bg-surface-container border border-outline-variant rounded-2xl w-full max-w-5xl max-h-[92vh] overflow-hidden flex flex-col shadow-2xl"
            onClick={e => e.stopPropagation()}>

            {/* Modal header */}
            <div className="flex items-center justify-between px-6 py-4 border-b border-outline-variant/60">
              <h2 className="text-[16px] font-semibold text-on-surface">
                {editingMetric.id ? 'Edit Metric' : 'New SQL Metric'}
              </h2>
              <button type="button" onClick={() => setIsModalOpen(false)} aria-label="Close"
                className="p-1.5 rounded-md text-on-surface-variant hover:text-on-surface hover:bg-surface-container-high transition-colors">
                <span className="material-symbols-outlined text-[20px]">close</span>
              </button>
            </div>

            {saveError && (
              <div className="px-6 pt-4">
                <ErrorPanel error={saveError} onDismiss={() => setSaveError(null)} />
              </div>
            )}

            {/* Modal body */}
            <div className="flex flex-1 overflow-hidden">

              {/* Left: form */}
              <div className="w-72 border-r border-outline-variant/60 flex flex-col shrink-0 overflow-y-auto p-5 space-y-4">

                {/* Name */}
                <div className="space-y-1.5">
                  <div className="flex items-center justify-between">
                    <label htmlFor="metric-name" className="block text-[12px] font-medium text-on-surface-variant">
                      Metric Name<span aria-hidden="true" className="text-error ml-0.5">*</span>
                    </label>
                    {nameIsAuto ? (
                      <span className="flex items-center gap-0.5 text-[9px] text-primary/70 font-bold uppercase tracking-wider">
                        <span className="material-symbols-outlined text-[11px]">auto_awesome</span>auto
                      </span>
                    ) : (
                      <button
                        type="button"
                        aria-label="Regenerate the name from the metric type and topic"
                        onClick={() => {
                          setNameIsAuto(true);
                          setEditingMetric(m => ({ ...m, name: buildAutoName(m.type ?? 'GAUGE', selectedTopic) }));
                        }}
                        className="flex items-center gap-0.5 text-[9px] text-outline hover:text-primary transition-colors uppercase tracking-wider"
                      >
                        <span className="material-symbols-outlined text-[11px]">refresh</span>regenerate
                      </button>
                    )}
                  </div>
                  <Input
                    id="metric-name"
                    value={editingMetric.name ?? ''}
                    onChange={e => { setNameIsAuto(false); setEditingMetric(m => ({ ...m, name: e.target.value })); }}
                    placeholder="e.g. gauge_orders_topic"
                    autoComplete="off"
                    spellCheck={false}
                    invalid={nameValidation.some(v => v.level === 'error')}
                    aria-describedby={nameValidation.length > 0 ? 'metric-name-hints' : undefined}
                    className="bg-primary/5 font-mono"
                  />
                  {nameValidation.length > 0 && (
                    <div id="metric-name-hints">
                      {nameValidation.map((m, i) => (
                        <p key={i}
                          role={m.level === 'error' ? 'alert' : undefined}
                          className={`text-[10px] flex items-start gap-1 ${HINT_COLORS[m.level]}`}>
                          <span aria-hidden="true" className="material-symbols-outlined text-[11px] shrink-0 mt-px">{HINT_ICONS[m.level]}</span>
                          {m.text}
                        </p>
                      ))}
                    </div>
                  )}
                </div>

                {/* Un sélecteur vide sans explication laisse croire qu'il n'y a pas de gabarit. */}
                {templates.length === 0 && templatesError && (
                  <ErrorPanel error={templatesError} onDismiss={() => setTemplatesError(null)} />
                )}

                {/* Metric source / template */}
                {templates.length > 0 && (
                  <Field
                    label="Metric Source"
                    description={isTemplate && currentDescriptor ? currentDescriptor.description : undefined}
                  >
                    {f => (
                      <Select {...f} value={templateType} onChange={e => onTemplateTypeChange(e.target.value)}>
                        <option value={RAW_SQL} className="bg-[#12151a] text-on-surface">Raw SQL</option>
                        {templates.map(t => (
                          <option key={t.type} value={t.type} className="bg-[#12151a] text-on-surface">{t.label}</option>
                        ))}
                      </Select>
                    )}
                  </Field>
                )}

                {/* Type */}
                <Field
                  label="Type"
                  description={{ GAUGE: '→ explorer_metric_gauge{…}', COUNTER: '→ explorer_metric_counter_total{…}',
                    HISTOGRAM: '→ explorer_metric_histogram_bucket{le=…} — auto-bucketed over metric_value',
                    SUMMARY: '→ explorer_metric_summary{quantile=0.95,…}',
                  }[editingMetric.type ?? 'GAUGE']}
                >
                  {f => (
                    <Select
                      {...f}
                      value={editingMetric.type ?? 'GAUGE'}
                      onChange={e => {
                        const newType = e.target.value;
                        setEditingMetric(m => ({
                          ...m,
                          type: newType,
                          name: nameIsAuto ? buildAutoName(newType, selectedTopic) : m.name,
                        }));
                      }}
                    >
                      {[
                        { value: 'GAUGE',     label: 'GAUGE — point-in-time value' },
                        { value: 'COUNTER',   label: 'COUNTER — cumulative total' },
                        { value: 'HISTOGRAM', label: 'HISTOGRAM — bucket distribution' },
                        { value: 'SUMMARY',   label: 'SUMMARY — quantile observations' },
                      ].filter(o => allowedTypes.includes(o.value)).map(o => (
                        <option key={o.value} value={o.value} className="bg-[#12151a] text-on-surface">{o.label}</option>
                      ))}
                    </Select>
                  )}
                </Field>

                {/* Topic selector — un combobox unique remplace le couple select/champ libre :
                    la liste locale peut être vide alors que le catalogue partagé est rempli, et
                    un <select> interdisait de saisir un topic créé à l'instant. */}
                <Field
                  label={<>Kafka Topic <span className="text-outline font-normal">— used in SQL templates &amp; DDL</span></>}
                  description={selectedTopic ? `Table: ${topicToTable(selectedTopic)}` : undefined}
                >
                  {f => (
                    <TopicInput
                      {...f}
                      value={selectedTopic}
                      onChange={onTopicChange}
                      placeholder="my_topic"
                    />
                  )}
                </Field>

                {/* Prometheus labels from latest Kafka message */}
                <div className="space-y-2 rounded-xl border border-outline-variant/60 bg-primary/5 p-3">
                  <div className="flex items-start justify-between gap-2">
                    <div>
                      <span className="block text-[12px] font-medium text-on-surface-variant">Prometheus Labels</span>
                      <p className="text-[10px] text-outline mt-1 leading-relaxed">
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
                        className="shrink-0 text-on-surface-variant hover:text-primary transition-colors"
                        title="Refresh latest message" aria-label="Refresh the latest message"
                      >
                        <span className={`material-symbols-outlined text-base ${labelPreviewLoading ? 'animate-spin' : ''}`}>refresh</span>
                      </button>
                    )}
                  </div>

                  {!selectedTopic ? (
                    <p className="text-[11px] text-on-surface-variant">Select a topic to inspect its latest message.</p>
                  ) : labelPreviewLoading ? (
                    <div className="flex items-center gap-2 text-[11px] text-on-surface-variant">
                      <span className="material-symbols-outlined text-sm animate-spin">progress_activity</span>
                      Loading latest message…
                    </div>
                  ) : !labelPreview?.message ? (
                    <p className="text-[11px] text-on-surface-variant">No recent message available on this topic.</p>
                  ) : (
                    <>
                      <div className="flex items-center justify-between gap-2 text-[10px] text-outline">
                        <span>Latest message at {formatPreviewTimestamp(labelPreview.timestamp)}</span>
                        <span>{selectedLabelFields.length} selected</span>
                      </div>

                      {availableLabelFields.length > 0 ? (
                        <div className="max-h-44 overflow-y-auto rounded-lg border border-outline-variant/60 bg-background-dark/40 divide-y divide-primary/5">
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
                                  <div className="font-mono text-[11px] text-on-surface break-all">{field}</div>
                                  <div className="font-mono text-[10px] text-on-surface-variant break-all">{value || '""'}</div>
                                </div>
                              </label>
                            );
                          })}
                        </div>
                      ) : (
                        <p className="text-[11px] text-on-surface-variant">
                          The latest message format does not expose selectable leaf fields.
                        </p>
                      )}

                      <div className="rounded-lg border border-outline-variant/60 bg-background-dark/40 p-2">
                        <p className="text-[10px] uppercase font-bold tracking-wider text-on-surface-variant mb-2">Latest Message</p>
                        <pre className="max-h-36 overflow-auto whitespace-pre-wrap break-all text-[10px] font-mono text-on-surface-variant">
                          {labelPreview.message}
                        </pre>
                      </div>
                    </>
                  )}
                </div>

                {/* Description */}
                <Field label="Description">
                  {f => (
                    <Textarea
                      {...f}
                      value={editingMetric.description ?? ''}
                      onChange={e => setEditingMetric(m => ({ ...m, description: e.target.value }))}
                      placeholder="What does this metric track?"
                      rows={2}
                      className="resize-none"
                    />
                  )}
                </Field>

                {/* Thresholds — vides par défaut : ce sont des seuils optionnels, pas des nombres
                    avec une valeur de repli, d'où l'Input natif plutôt que NumberInput. */}
                <div className="space-y-1.5">
                  <div className="grid grid-cols-2 gap-3">
                    <Field label="⚠ Warning">
                      {f => (
                        <Input {...f} type="number" inputMode="decimal"
                          value={editingMetric.warningThreshold ?? ''}
                          invalid={thresholdValidation.length > 0}
                          aria-describedby={thresholdHints.length > 0 ? 'metric-threshold-hints' : undefined}
                          onChange={e => setEditingMetric(m => ({ ...m, warningThreshold: e.target.value ? parseFloat(e.target.value) : null }))}
                          className="bg-warning/5" />
                      )}
                    </Field>
                    <Field label="🔴 Critical">
                      {f => (
                        <Input {...f} type="number" inputMode="decimal"
                          value={editingMetric.criticalThreshold ?? ''}
                          invalid={thresholdValidation.length > 0}
                          aria-describedby={thresholdHints.length > 0 ? 'metric-threshold-hints' : undefined}
                          onChange={e => setEditingMetric(m => ({ ...m, criticalThreshold: e.target.value ? parseFloat(e.target.value) : null }))}
                          className="bg-error/5" />
                      )}
                    </Field>
                  </div>
                  {thresholdHints.length > 0 && (
                    <div id="metric-threshold-hints">
                      {thresholdHints.map((m, i) => (
                        <p key={i}
                          role={m.level === 'error' ? 'alert' : undefined}
                          className={`text-[10px] flex items-start gap-1 ${HINT_COLORS[m.level]}`}>
                          <span aria-hidden="true" className="material-symbols-outlined text-[11px] shrink-0 mt-px">{HINT_ICONS[m.level]}</span>
                          {m.text}
                        </p>
                      ))}
                    </div>
                  )}
                </div>

                {/* SQL templates (raw SQL mode only) */}
                {!isTemplate && (
                <div className="border-t border-outline-variant/60 pt-4">
                  <p className="text-[10px] uppercase font-bold text-on-surface-variant tracking-wider mb-3">SQL Templates</p>
                  <div className="space-y-3">
                    {sqlTemplates.map(group => (
                      <div key={group.group}>
                        <p className={`text-[10px] font-bold uppercase tracking-wider mb-1.5 ${group.color}`}>{group.group}</p>
                        <div className="space-y-1">
                          {group.items.map(t => (
                            <button key={t.label} type="button"
                              onClick={() => { setEditingMetric(m => ({ ...m, sql: t.sql })); setPreviewResult(null); setEditorTab('metric'); }}
                              className="w-full text-left text-xs px-3 py-1.5 rounded-lg text-on-surface-variant hover:text-primary hover:bg-primary/10 transition-colors font-mono">
                              {t.label}
                            </button>
                          ))}
                        </div>
                      </div>
                    ))}
                  </div>
                </div>
                )}
              </div>

              {/* Right: tabbed editors */}
              <div className="flex-1 flex flex-col overflow-hidden">

                {/* Tabs */}
                <div className="flex items-center border-b border-outline-variant/60 bg-primary/5 px-4 gap-1">
                  {(['metric', 'ddl'] as const).map(tab => (
                    <button key={tab} type="button" onClick={() => setEditorTab(tab)}
                      className={`px-4 py-2.5 text-xs font-bold uppercase tracking-wider transition-colors border-b-2 ${
                        editorTab === tab
                          ? 'border-primary text-primary'
                          : 'border-transparent text-on-surface-variant hover:text-on-surface'
                      }`}>
                      {tab === 'metric' ? (
                        <span className="flex items-center gap-1.5">
                          <span className="material-symbols-outlined text-sm">code</span>
                          {isTemplate ? 'Parameters' : 'Metric SQL'}
                        </span>
                      ) : (
                        <span className="flex items-center gap-1.5">
                          <span className="material-symbols-outlined text-sm">table</span>
                          Table DDL
                          {editingMetric.createTableSql?.trim() && (
                            <span className="w-1.5 h-1.5 rounded-full bg-success" />
                          )}
                        </span>
                      )}
                    </button>
                  ))}

                  {editorTab === 'metric' && (
                    <div className="ml-auto">
                      <Button variant="secondary" size="sm" icon={previewing ? undefined : 'play_arrow'} loading={previewing}
                        onClick={handlePreview} disabled={previewing || (!isTemplate && !editingMetric.sql?.trim()) || hasBlockingErrors}>
                        {previewing ? 'Running…' : 'Preview'}
                      </Button>
                    </div>
                  )}

                  {editorTab === 'ddl' && (
                    <span className="ml-auto text-[10px] text-outline pr-1">
                      Executed before metric SQL — <code className="text-on-surface-variant">IF NOT EXISTS</code> is auto-added
                    </span>
                  )}
                </div>

                {/* Editor area */}
                <div className="flex-1 overflow-hidden">
                  {editorTab === 'metric' ? (
                    isTemplate ? (
                      <TemplateParamsEditor
                        templateType={templateType}
                        params={templateParams}
                        executionMode={editingMetric.executionMode ?? 'TEMPLATE_BOUNDED_SCAN'}
                        table={tableName}
                        setParam={(k, v) => { setParam(k, v); setPreviewResult(null); }}
                        setExecutionMode={setExecutionMode}
                      />
                    ) : (
                    <Editor height="100%" defaultLanguage="sql" theme="vs-dark"
                      value={editingMetric.sql ?? ''}
                      onChange={val => { setEditingMetric(m => ({ ...m, sql: val ?? '' })); setPreviewResult(null); }}
                      options={{ minimap: { enabled: false }, fontSize: 13, fontFamily: 'JetBrains Mono, monospace',
                        lineNumbers: 'on', scrollBeyondLastLine: false, padding: { top: 12, bottom: 12 },
                        wordWrap: 'on', suggest: { showKeywords: true } }}
                    />
                    )
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

                {/* SQL / template validation hints (metric tab only) */}
                {editorTab === 'metric' && <ValidationHints messages={isTemplate ? templateValidation : sqlValidation} />}

                {/* DDL validation hints */}
                {editorTab === 'ddl' && <ValidationHints messages={ddlValidation} />}

                {/* Preview result (metric tab only) */}
                {editorTab === 'metric' && previewResult && (
                  <div className={`border-t px-4 py-3 text-xs font-mono ${
                    previewResult.error
                      ? 'border-error/20 bg-error/5 text-error'
                      : 'border-success/20 bg-success/5 text-success'
                  }`}>
                    {previewError ? (
                      <InfoTooltip content={previewError.raw}>
                      <div tabIndex={0} className="flex items-start gap-2 rounded">
                        <span className="material-symbols-outlined text-sm mt-0.5 shrink-0">error</span>
                        <span className="min-w-0">
                          <span className="font-semibold">{previewError.title}</span>
                          {previewError.hint && (
                            <span className="block font-sans text-error/80 mt-0.5 leading-relaxed">{previewError.hint}</span>
                          )}
                        </span>
                      </div>
                      </InfoTooltip>
                    ) : (
                      <div className="space-y-1.5">
                        <span className="flex items-center gap-2">
                          <span className="material-symbols-outlined text-sm">check_circle</span>
                          {isTemplate ? 'value' : 'metric_value'} = <strong>{String(previewResult.value)}</strong>
                          {Array.isArray(previewResult.rows) && previewResult.rows.length > 1 && (
                            <span className="text-success ml-2">({previewResult.rows.length} rows total)</span>
                          )}
                        </span>
                        {previewResult.summary && Object.keys(previewResult.summary).length > 0 && (
                          <div className="flex flex-wrap gap-1.5 pt-0.5">
                            {Object.entries(previewResult.summary).map(([k, v]) => (
                              <span key={k} className="px-2 py-0.5 rounded bg-success/10 text-success text-[10px]">
                                {k}: <strong>{String(v)}</strong>
                              </span>
                            ))}
                          </div>
                        )}
                      </div>
                    )}
                  </div>
                )}

                {/* DDL hint panel */}
                {editorTab === 'ddl' && !editingMetric.createTableSql?.trim() && (
                  <div className="border-t border-outline-variant/60 px-4 py-3 text-xs text-on-surface-variant bg-primary/5 space-y-1">
                    <p className="font-bold text-on-surface-variant">No DDL defined</p>
                    <p>The metric SQL will run against tables already registered in Flink.</p>
                    <p>
                      Select a <span className="text-primary">Kafka Topic</span> on the left to auto-generate a{' '}
                      <code className="text-on-surface">CREATE TABLE IF NOT EXISTS</code> template.
                    </p>
                  </div>
                )}
              </div>
            </div>

            {/* Modal footer */}
            <div className="flex items-center justify-end gap-3 px-6 py-4 border-t border-outline-variant/60 bg-surface-container-low/60">
              {hasBlockingErrors && (
                <span className="flex items-center gap-1 text-[12px] text-error mr-auto">
                  <span className="material-symbols-outlined text-[16px]">error</span>
                  Fix errors before saving
                </span>
              )}
              <Button variant="ghost" onClick={() => setIsModalOpen(false)}>Cancel</Button>
              <Button type="submit" variant="primary" icon={saving ? undefined : 'save'} loading={saving}
                disabled={saving || hasBlockingErrors}>
                {saving ? 'Saving…' : 'Save & Activate'}
              </Button>
            </div>
          </form>
        </div>
      )}
    </div>
  );
};

export default Metrics;
