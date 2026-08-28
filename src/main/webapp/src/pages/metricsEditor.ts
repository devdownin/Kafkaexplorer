// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors

/*
 * La logique pure de la page Métriques : validation du formulaire, gabarits de SQL, noms dérivés,
 * et les tables de constantes que la carte et l'éditeur lisent toutes les deux.
 *
 * Elle vivait dans `Metrics.tsx`, qui est la plus grosse page du dépôt. Le découpage suit le
 * précédent de `QueryWorkbench` — et surtout son ordre : le test de la page existait avant, parce
 * que refactorer ce qu'on vient de corriger sans filet est le plus sûr moyen de le décorriger.
 * Ce qui bouge ici est du déplacement, pas de la réécriture : les fonctions sont celles qui
 * étaient exportées de la page et déjà couvertes par `Metrics.test.tsx`, à l'identique.
 *
 * Un module `.ts` plutôt qu'un `.tsx` : c'est la convention du dépôt pour ce qui ne rend rien, et
 * c'est ce qui rend ces fonctions testables sans monter React.
 */

import type { MetricConfig } from '../api/types';
import { describeThresholdDirection, gradeMetric } from './metricAlert';

export const PLACEHOLDER_BARS = [35, 58, 24, 71, 46, 88, 30, 62, 41, 77, 19, 54, 66, 28, 83, 37, 49, 74, 22, 60];

export const RAW_SQL = 'RAW_SQL';

export const DELTA_OPERATIONS: Array<{ value: string; label: string }> = [
  { value: 'LEFT_MINUS_RIGHT', label: 'Left − Right' },
  { value: 'ABS_DIFF',         label: '|Left − Right|' },
  { value: 'RATIO',            label: 'Left ÷ Right' },
  { value: 'PERCENT_GAP',      label: '(Left − Right) ÷ Right × 100' },
];

export const EXECUTION_MODES: Array<{ value: string; label: string; note: string }> = [
  { value: 'TEMPLATE_BOUNDED_SCAN', label: 'Bounded scan (default)', note: 'Re-scans bounded data every refresh cycle.' },
  { value: 'FLINK_MANAGED_JOB',     label: 'Flink managed job (planned)', note: 'Continuous execution — planned; previews use a bounded scan for now.' },
];

// ── Type visual config ─────────────────────────────────────────────────────
export const TYPE_META: Record<string, { color: string; bg: string; border: string; icon: string; badge: string }> = {
  GAUGE:     { color: 'text-primary',     bg: 'bg-primary/5',      border: 'border-primary/20',     icon: 'speed',       badge: 'bg-primary/10 text-primary' },
  COUNTER:   { color: 'text-success', bg: 'bg-success/5',  border: 'border-success/20', icon: 'add_circle',  badge: 'bg-success/10 text-success' },
  HISTOGRAM: { color: 'text-secondary',  bg: 'bg-secondary/5',   border: 'border-secondary/20',  icon: 'bar_chart',   badge: 'bg-secondary/10 text-secondary' },
  SUMMARY:   { color: 'text-warning',   bg: 'bg-warning/5',    border: 'border-warning/20',   icon: 'query_stats', badge: 'bg-warning/10 text-warning' },
};

// ── Example template for each type shown in the empty state ───────────────
export const TYPE_EXAMPLES: Array<{
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

export interface ValidationMsg {
  level: 'error' | 'warning' | 'info';
  text: string;
}

export function validateMetricSql(sql: string, type: string): ValidationMsg[] {
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

export function validateDdlSql(ddl: string): ValidationMsg[] {
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

export function validateMetricName(name: string): ValidationMsg[] {
  if (!name.trim()) return [];
  if (!/^[a-zA-Z_][a-zA-Z0-9_]*$/.test(name.trim()))
    return [{ level: 'error', text: 'Metric name must start with a letter or underscore and contain only letters, digits, and underscores (Prometheus naming rules).' }];
  return [];
}

/**
 * Les deux seuils, et le sens qu'ils impliquent.
 *
 * `warn >= crit` était refusé tout court, ce qui rendait la lecture descendante **inatteignable** :
 * un `RATIO` sain à 1.0 casse en descendant, donc ses seuils sont 0.99 puis 0.95 — la paire que
 * cette règle interdisait. Seule l'égalité reste une erreur : deux seuils identiques n'expriment
 * aucun sens et se déclencheraient ensemble. Le reste est une direction, énoncée plutôt que
 * devinée — voir `describeThresholdDirection`.
 */
export function validateThresholds(warn: number | null, crit: number | null): ValidationMsg[] {
  if (warn !== null && crit !== null && warn === crit)
    return [{ level: 'error', text: 'The two thresholds are the same number, so they say nothing about which side the problem is on and would fire together.' }];
  const direction = describeThresholdDirection(warn, crit);
  return direction ? [{ level: 'info', text: direction }] : [];
}

export function paramStr(params: Record<string, unknown>, key: string): string {
  const v = params[key];
  return v == null ? '' : String(v);
}

/** Le mode de lecture par défaut d'un gabarit — miroir de MetricService.DEFAULT_*_READ_MODE. */
export const defaultReadMode = (templateType: string): string =>
  templateType === 'TOPIC_TRANSIT_LATENCY' ? 'latest-offset' : 'earliest-offset';

export const SCAN_MAX_ROWS_DEFAULT = 10_000;
export const SCAN_TIMEOUT_MS_DEFAULT = 30_000;

/**
 * Deux instructions qui disent la même chose.
 *
 * La comparaison est sur le texte, espaces réduits et casse ignorée : elle attrape le copier-coller,
 * qui est la façon dont ce défaut arrive, et ne prétend pas comprendre le SQL — deux requêtes
 * sémantiquement identiques écrites différemment passent, et le serveur ne peut pas mieux faire.
 * Deux chaînes vides ne sont pas identiques : le champ manquant a déjà son propre refus.
 */
export function sameStatement(a: string, b: string): boolean {
  const norm = (sql: string) => sql.replace(/\s+/g, ' ').trim().replace(/;$/, '').toLowerCase();
  return norm(a) !== '' && norm(a) === norm(b);
}

export const COUNT_MODES = ['AUTO', 'OFFSETS', 'RECORDS'];
export const COUNT_WINDOWS = ['TOTAL', 'SINCE_LAST_REFRESH'];

/**
 * Miroir de la validation serveur des deux réglages qui décident *ce que* la métrique compare.
 *
 * Un comptage par offsets n'exécute aucune requête : il demande au log ses propres bornes, donc il
 * lui faut les deux topics et pas les deux SQL — voir METRICS-TWO-QUERY-AUDIT.md.
 */
export function validateCountParams(params: Record<string, unknown>): ValidationMsg[] {
  const msgs: ValidationMsg[] = [];
  const countBy = paramStr(params, 'countBy').trim().toUpperCase();
  const window = paramStr(params, 'window').trim().toUpperCase();

  if (countBy && !COUNT_MODES.includes(countBy))
    msgs.push({ level: 'error', text: `Count by must be one of ${COUNT_MODES.join(', ')}.` });
  if (window && !COUNT_WINDOWS.includes(window))
    msgs.push({ level: 'error', text: `Compare must be one of ${COUNT_WINDOWS.join(', ')}.` });

  const hasTopics = !!paramStr(params, 'leftTopic').trim() && !!paramStr(params, 'rightTopic').trim();
  if (countBy === 'OFFSETS' && !hasTopics)
    msgs.push({ level: 'error', text: 'Counting by offsets needs both topics named: it asks the log rather than running a query.' });

  if (window === 'SINCE_LAST_REFRESH')
    msgs.push({ level: 'info', text: 'The first refresh establishes the baseline and publishes nothing; the next one reports the gap over the interval.' });

  /*
   * Deux côtés qui ne peuvent pas différer.
   *
   * Un écart entre une chose et elle-même vaut 0 pour toujours, et 0 sur cette métrique **se lit
   * comme « aucune perte »** — la seule valeur qu'elle ne doit jamais publier par accident. Deux
   * formes le produisent : les deux requêtes identiques, et, en comptage par offsets, les deux
   * topics identiques (le SQL n'est alors pas lu du tout, donc deux requêtes différentes sur le
   * même topic ne sauvent rien).
   */
  if (sameStatement(paramStr(params, 'leftSql'), paramStr(params, 'rightSql')))
    msgs.push({ level: 'error', text: 'The two queries are the same statement, so this metric compares a topic with itself and will report no gap for ever — which reads as “nothing is being lost”.' });
  else if (countBy === 'OFFSETS' && paramStr(params, 'leftTopic').trim() === paramStr(params, 'rightTopic').trim()
           && paramStr(params, 'leftTopic').trim() !== '')
    msgs.push({ level: 'error', text: 'Both sides name the same topic, and counting by offsets does not read the queries at all — so the two counts are the same number and the gap is always zero.' });

  const plainCount = (key: string) => {
    const sql = paramStr(params, key).trim();
    return sql === '' || /^select\s+count\s*\(\s*\*\s*\)\s*(?:as\s+`?\w+`?\s*)?from\s+`?\w[\w.]*`?\s*;?$/is.test(sql);
  };
  if ((countBy === '' || countBy === 'AUTO') && hasTopics && plainCount('leftSql') && plainCount('rightSql'))
    msgs.push({ level: 'info', text: 'Counted from the log\u2019s offsets: no record is read, no scan ceiling applies, and both sides describe the same instant.' });

  return msgs;
}

export const LATENCY_WINDOWS: { value: string; label: string }[] = [
  { value: '', label: 'Row count only (no window)' },
  { value: '300000', label: 'Same 5 min on both sides' },
  { value: '900000', label: 'Same 15 min on both sides' },
  { value: '3600000', label: 'Same hour on both sides' },
  { value: '21600000', label: 'Same 6 h on both sides' },
];

export const REFRESH_INTERVALS: { value: string; label: string }[] = [
  { value: '', label: 'Every cycle (default)' },
  { value: '60000', label: 'At most once a minute' },
  { value: '300000', label: 'At most every 5 min' },
  { value: '900000', label: 'At most every 15 min' },
  { value: '3600000', label: 'At most hourly' },
];

/**
 * Miroir de `MetricService.isSingleTableRead` : la forme que le lecteur direct sait répondre.
 *
 * Transcription littérale des quatre règles du serveur, pas une réécriture : ce miroir n'existe que
 * pour dire *pourquoi* une fenêtre est refusée, au moment où la requête peut encore être corrigée,
 * donc il doit refuser exactement ce que le serveur refuse — un miroir plus strict rejetterait dans
 * le formulaire une configuration que l'API accepte, ce qui est pire que de ne rien dire.
 */
export function isSingleTableRead(sql: string): boolean {
  const body = sql.trim();
  if (body === '') return false;
  if (!/^select/i.test(body)) return false;
  if (/\bjoin\b/i.test(body)) return false;
  if (body.replace(/\s+/g, '').toUpperCase().includes('(SELECT')) return false;
  const from = /\bfrom\b\s+(\w[\w.]*)/gi;
  const first = from.exec(body);
  if (!first) return false;
  // Une virgule juste après le nom de table est une liste de tables, soit une jointure à l'ancienne.
  if (body.slice(first.index + first[0].length).replace(/^\s+/, '').startsWith(',')) return false;
  // Un second FROM est une forme que ce lecteur ne sait pas honorer non plus.
  return from.exec(body) === null;
}

/** Miroir de MetricService.validateScanParams : refusé à l'enregistrement, pas au rafraîchissement. */
export function validateScanParams(templateType: string, params: Record<string, unknown>): ValidationMsg[] {
  const msgs: ValidationMsg[] = [];
  const raw = (key: string) => paramStr(params, key).trim();

  const rows = raw('maxRowsPerSide');
  if (rows && (!/^\d+$/.test(rows) || Number(rows) < 1 || Number(rows) > 1_000_000))
    msgs.push({ level: 'error', text: 'Max rows / side must be a whole number between 1 and 1,000,000.' });

  const timeout = raw('timeoutMs');
  if (timeout && (!/^\d+$/.test(timeout) || Number(timeout) < 1_000 || Number(timeout) > 600_000))
    msgs.push({ level: 'error', text: 'Timeout / side must be a whole number between 1,000 and 600,000 ms.' });

  const readMode = raw('readMode');
  if (readMode && !['earliest-offset', 'latest-offset'].includes(readMode))
    msgs.push({ level: 'error', text: 'Read from must be either the most recent records or the earliest offset.' });

  const interval = raw('refreshIntervalMs');
  if (interval && (!/^\d+$/.test(interval) || Number(interval) < 1_000 || Number(interval) > 86_400_000))
    msgs.push({ level: 'error', text: 'Refresh at most must be a whole number of milliseconds between 1,000 and 86,400,000, or blank to run on every cycle.' });

  const windowMs = raw('windowMs');
  if (templateType === 'TOPIC_TRANSIT_LATENCY' && windowMs) {
    if (!/^\d+$/.test(windowMs) || Number(windowMs) < 1_000 || Number(windowMs) > 604_800_000) {
      msgs.push({ level: 'error', text: 'Window must be a whole number of milliseconds between 1,000 and 604,800,000, or blank to bound the two reads by row count.' });
    } else {
      const sourceOk = isSingleTableRead(paramStr(params, 'sourceSql').trim());
      const targetOk = isSingleTableRead(paramStr(params, 'targetSql').trim());
      if (!sourceOk || !targetOk)
        msgs.push({ level: 'error', text: `A window bounds the read by time, which only the direct Kafka reader can do — and it answers a single-table SELECT. The ${sourceOk ? 'target' : 'source'} query joins or nests, so it would go to the Flink planner and read a different stretch of time.` });
      else
        msgs.push({ level: 'info', text: 'Both sides read from one instant computed once, so the match rate below is depressed by real losses rather than by two topics being read over two different stretches of time. A source near the end of the window has its target after it — the trailing edge understates by about one hop.' });
    }
  }

  // Une fenêtre décide où commencer, donc elle remplace le mode de lecture au lieu de coexister
  // avec lui : le dire vaut mieux que laisser deux réglages se contredire en silence.
  const effective = windowMs && templateType === 'TOPIC_TRANSIT_LATENCY' ? '' : readMode || defaultReadMode(templateType);
  if (templateType === 'TOPIC_TRANSIT_LATENCY' && effective === 'earliest-offset')
    msgs.push({ level: 'warning', text: 'Read from the earliest offset, this reports the latency of the oldest records the row cap allows — a figure that stops moving once the topic outgrows the cap.' });

  return msgs;
}

// Front-end mirror of the backend template validation (MetricService.validateMetric).
export function validateTemplate(templateType: string, metricType: string,
                          params: Record<string, unknown>): ValidationMsg[] {
  const msgs: ValidationMsg[] = [];
  if (templateType === 'TOPIC_COUNT_DELTA') {
    if (!paramStr(params, 'leftSql').trim())  msgs.push({ level: 'error', text: 'Left query (leftSql) is required.' });
    if (!paramStr(params, 'rightSql').trim()) msgs.push({ level: 'error', text: 'Right query (rightSql) is required.' });
    if (metricType !== 'GAUGE')               msgs.push({ level: 'error', text: 'Topic Count Delta supports GAUGE metrics only.' });
    // Le select ne peut proposer que les quatre, mais l'API accepte un corps écrit à la main :
    // le serveur refuse maintenant à l'enregistrement, et le formulaire dit la même chose.
    const operation = paramStr(params, 'operation').trim().toUpperCase();
    if (operation && !DELTA_OPERATIONS.some(o => o.value === operation))
      msgs.push({ level: 'error', text: `Operation must be one of ${DELTA_OPERATIONS.map(o => o.value).join(', ')}.` });
    msgs.push(...validateCountParams(params));
    msgs.push(...validateScanParams(templateType, params));
    msgs.push({ level: 'warning', text: 'The two counts are taken one after the other, not at one instant: whatever the pipeline produced in between is in the second and not in the first.' });
  } else if (templateType === 'TOPIC_TRANSIT_LATENCY') {
    if (!paramStr(params, 'sourceSql').trim()) msgs.push({ level: 'error', text: 'Source query (sourceSql) is required.' });
    if (!paramStr(params, 'targetSql').trim()) msgs.push({ level: 'error', text: 'Target query (targetSql) is required.' });
    if (!['GAUGE', 'HISTOGRAM', 'SUMMARY'].includes(metricType))
      msgs.push({ level: 'error', text: 'Topic Transit Latency supports GAUGE, HISTOGRAM or SUMMARY.' });
    msgs.push({ level: 'info', text: 'Both queries must return match_key and event_time columns (event_time as ISO-8601 or epoch).' });
    if (sameStatement(paramStr(params, 'sourceSql'), paramStr(params, 'targetSql')))
      msgs.push({ level: 'error', text: 'The two queries are the same statement, so every event would be correlated with itself and the latency would be zero.' });
    msgs.push(...validateScanParams(templateType, params));
    msgs.push({ level: 'info', text: 'A source event whose target the read did not cover is counted as unmatched, never averaged in — the summary reports how many.' });
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

export function buildAutoName(type: string, topic: string): string {
  const typePart  = (type || 'gauge').toLowerCase();
  const topicPart = topic ? topicToTable(topic) : 'my_table';
  return `${typePart}_${topicPart}`;
}

export function extractTopicFromDdl(ddl?: string | null): string {
  if (!ddl) return '';
  const match = ddl.match(/'topic'\s*=\s*'([^']+)'/i);
  return match?.[1] ?? '';
}

export function formatPreviewTimestamp(timestamp: number | null): string {
  if (!timestamp) return 'unknown';
  return new Date(timestamp).toLocaleString();
}

// ── Convert Kafka topic name to a valid Flink table identifier ─────────────
export function topicToTable(topic: string): string {
  return (
    topic.replace(/[.-]/g, '_').replace(/[^a-zA-Z0-9_]/g, '').replace(/^_+|_+$/g, '') || 'my_table'
  );
}

// ── Auto-generate a CREATE TABLE DDL for a topic ──────────────────────────
export function buildDdlTemplate(topic: string, bootstrapServers: string): string {
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
export function getSqlTemplates(table: string) {
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
export function describeTemplate(metric: MetricConfig): string {
  const params = metric.templateParams ?? {};
  const at = (key: string) => (typeof params[key] === 'string' ? params[key] as string : null);
  const from = at('sourceTopic') ?? at('leftTopic') ?? at('topic');
  const to = at('targetTopic') ?? at('rightTopic') ?? at('group');
  const pair = from && to ? `${from} → ${to}` : from ?? '';
  return [metric.templateType ?? 'TEMPLATE', pair].filter(Boolean).join(' · ');
}

export function relativeTime(ms: number | null): string {
  if (!ms) return 'Never';
  const diff = Date.now() - ms;
  if (diff < 5000) return 'Just now';
  if (diff < 60000) return `${Math.floor(diff / 1000)}s ago`;
  if (diff < 3600000) return `${Math.floor(diff / 60000)}m ago`;
  return new Date(ms).toLocaleTimeString();
}

/**
 * Le verdict d'une carte. La règle vit dans `metricAlert.ts` — elle a cessé d'être « au-dessus du
 * seuil » le jour où un ratio, sain à 1.0, a eu besoin d'être lu vers le bas.
 */
export const getStatus = gradeMetric;

export const STATUS_STYLES = {
  ok:       { dot: 'bg-success', text: 'text-success', label: 'Healthy' },
  warning:  { dot: 'bg-warning',   text: 'text-warning',   label: 'Warning' },
  critical: { dot: 'bg-error',     text: 'text-error',     label: 'Critical' },
  error:    { dot: 'bg-error',     text: 'text-error',     label: 'Error' },
  pending:  { dot: 'bg-outline',   text: 'text-on-surface-variant',   label: 'Pending' },
};
