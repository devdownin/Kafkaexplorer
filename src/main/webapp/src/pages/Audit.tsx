import React, { useState, useEffect, useRef, useMemo, useCallback } from 'react';
import { Link } from 'react-router-dom';
import axios from 'axios';
import {
  PageHeader, Button, Stat, Badge, EmptyState, Card, Input, Select,
  Table, TableHead, TableBody, TableRow, Th, Td, TopicInput, type BadgeTone,
} from '../components/ui';

type Severity = 'HEALTHY' | 'WARNING' | 'CRITICAL';

const HEALTH_TONE: Record<string, BadgeTone> = {
  HEALTHY: 'success', WARNING: 'warning', CRITICAL: 'error',
};

/** Chip styling per issue severity — every issue used to be painted error-red. */
const ISSUE_STYLE: Record<Severity, string> = {
  HEALTHY: 'bg-surface-container-high text-on-surface-variant border-outline-variant',
  WARNING: 'bg-warning/10 text-warning border-warning/25',
  CRITICAL: 'bg-error/10 text-error border-error/25',
};

interface TopicIssue {
  message: string;
  severity: Severity;
}

interface TopicAudit {
  name: string;
  messageCount: number;
  format: string;
  poisonMessageCount: number;
  duplicateCount: number;
  healthStatus: Severity;
  issues: TopicIssue[];
}

interface StepInfo {
  topicName: string;
  count: number;
  throughputPercentage: number;
  averageLatencyMs: number | null;
}

interface FlowAudit {
  flowName: string;
  steps: StepInfo[];
  /** Ratio 0..1 — pas un pourcentage (le backend normalise). */
  overallHealthScore: number;
}

interface LaggingFeature {
  feature: string;
  finalizedVersion: number | null;
  supportedMaxVersion: number;
}

interface GlobalStats {
  timestamp?: number;
  startedAt?: number;
  durationMs?: number;
  phase?: string;
  topicsCompleted?: number;
  topicsTotal?: number;
  error?: string;
  errorType?: string;
  /** Le run a été arrêté sur demande : le rapport est partiel. */
  cancelled?: boolean;
  /** Arrêt demandé, pas encore effectif (l'annulation est coopérative). */
  cancelling?: boolean;
  /** Topics qui étaient dans le périmètre avant l'annulation. */
  topicsInScope?: number;
  /** Ratio 0..1 : un topic CRITICAL coûte 1 point, un WARNING un demi-point. */
  healthScore?: number;
  scopeNotes?: string[];
  metadataVersionWarning?: string;
  laggingFeatures?: LaggingFeature[];
  options?: Partial<AuditOptions> & { topicPrefix?: string | null };
}

interface AuditReport {
  auditId: string;
  status: 'RUNNING' | 'COMPLETED' | 'CANCELLED' | 'FAILED';
  totalTopics: number;
  totalMessages: number;
  criticalTopicsCount: number;
  warningTopicsCount: number;
  topicAudits: TopicAudit[];
  flowAudits: FlowAudit[];
  globalStats: GlobalStats;
}

interface AuditOptions {
  checkSchema: boolean;
  checkPoisonMessages: boolean;
  checkDuplicates: boolean;
  checkFlows: boolean;
  checkExactCount: boolean;
}

const CHECK_LABELS: { key: keyof AuditOptions; label: string; description: string; icon: string; requires?: keyof AuditOptions }[] = [
  { key: 'checkSchema',         label: 'Schema inference',    description: 'Detect message format (JSON/XML/Avro) and infer field types', icon: 'schema' },
  { key: 'checkExactCount',     label: 'Exact message count', description: 'Run COUNT(*) via Flink SQL (slower than Kafka offset diff)',    icon: 'tag', requires: 'checkSchema' },
  { key: 'checkPoisonMessages', label: 'Poison messages',     description: 'Parse 10 recent messages per topic and flag unparseable payloads', icon: 'bug_report' },
  { key: 'checkDuplicates',     label: 'Duplicate detection', description: 'Group the first 10 000 messages by id field (or record key) and count repeats', icon: 'content_copy' },
  { key: 'checkFlows',          label: 'Flow analysis',       description: 'Group topics by naming convention and compute inter-step latency', icon: 'account_tree' },
];

const ALL_CHECKED: AuditOptions = {
  checkSchema: true, checkPoisonMessages: true,
  checkDuplicates: true, checkFlows: true, checkExactCount: true,
};

const NONE_CHECKED: AuditOptions = {
  checkSchema: false, checkPoisonMessages: false,
  checkDuplicates: false, checkFlows: false, checkExactCount: false,
};

type SortKey = 'name' | 'messageCount' | 'poisonMessageCount' | 'duplicateCount';
type HealthFilter = 'all' | 'critical' | 'warning' | 'attention' | 'healthy';

const HEALTH_FILTERS: { value: HealthFilter; label: string; match: (s: Severity) => boolean }[] = [
  { value: 'all',       label: 'All health states', match: () => true },
  { value: 'attention', label: 'Needs attention',   match: s => s !== 'HEALTHY' },
  { value: 'critical',  label: 'Critical only',     match: s => s === 'CRITICAL' },
  { value: 'warning',   label: 'Warning only',      match: s => s === 'WARNING' },
  { value: 'healthy',   label: 'Healthy only',      match: s => s === 'HEALTHY' },
];

const compactNum = (n: number) => {
  if (n >= 1_000_000) return (n / 1_000_000).toFixed(1) + 'M';
  if (n >= 1_000) return (n / 1_000).toFixed(1) + 'K';
  return n.toString();
};

const formatDuration = (ms: number) => {
  if (ms < 1000) return `${ms}ms`;
  if (ms < 60_000) return `${(ms / 1000).toFixed(1)}s`;
  const minutes = Math.floor(ms / 60_000);
  return `${minutes}m ${Math.round((ms % 60_000) / 1000)}s`;
};

const Audit: React.FC = () => {
  const [report, setReport] = useState<AuditReport | null>(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  /** Message informatif (pas une erreur) — ex. rattachement à un audit déjà en cours. */
  const [notice, setNotice] = useState<string | null>(null);
  const [cancelling, setCancelling] = useState(false);
  const [activeTab, setActiveTab] = useState<'topics' | 'flows'>('topics');
  const [options, setOptions] = useState<AuditOptions>(ALL_CHECKED);
  // Restreint l'audit aux topics dont le nom commence par ce préfixe (vide = tous).
  const [topicPrefix, setTopicPrefix] = useState('');
  const [topicFilter, setTopicFilter] = useState('');
  const [healthFilter, setHealthFilter] = useState<HealthFilter>('all');
  const [sortKey, setSortKey] = useState<SortKey>('name');
  const [sortDesc, setSortDesc] = useState(false);
  const pollRef = useRef<ReturnType<typeof setInterval> | null>(null);

  const stopPolling = useCallback(() => {
    if (pollRef.current) {
      clearInterval(pollRef.current);
      pollRef.current = null;
    }
  }, []);

  const pollStatus = useCallback(async (auditId: string) => {
    try {
      const res = await axios.get<AuditReport>(`/api/audit/status/${auditId}`);
      setReport(res.data);
      if (res.data.status !== 'RUNNING') {
        stopPolling();
        setLoading(false);
      }
    } catch (e) {
      stopPolling();
      setLoading(false);
      // 404 = the run was evicted from the server's retained history (or the app restarted);
      // say so instead of leaving a blank page behind a stopped poller.
      setError(axios.isAxiosError(e) && e.response?.status === 404
        ? 'This audit run is no longer available on the server. Run a new audit.'
        : 'Failed to fetch audit status.');
    }
  }, [stopPolling]);

  const attachTo = useCallback((auditId: string) => {
    stopPolling();
    void pollStatus(auditId);
    pollRef.current = setInterval(() => pollStatus(auditId), 2000);
  }, [pollStatus, stopPolling]);

  const startAudit = async () => {
    setLoading(true);
    setError(null);
    setNotice(null);
    setReport(null);
    try {
      const res = await axios.post<string>('/api/audit/start', { ...options, topicPrefix: topicPrefix.trim() || null });
      attachTo(res.data);
    } catch (e) {
      // 409 = a run is already in flight and the body carries its id. Following it beats both
      // queueing a second full cluster scan and showing the operator an error.
      if (axios.isAxiosError(e) && e.response?.status === 409 && typeof e.response.data === 'string') {
        setNotice('An audit was already running — following that one instead of queueing a second scan.');
        attachTo(e.response.data);
        return;
      }
      setLoading(false);
      setError('Failed to start audit.');
    }
  };

  /**
   * Demande l'arrêt du run en cours. L'annulation est coopérative côté serveur : on ne coupe pas
   * le polling ici, c'est lui qui verra passer le statut CANCELLED et le rapport partiel.
   */
  const cancelAudit = async () => {
    if (!report || report.status !== 'RUNNING') return;
    setCancelling(true);
    try {
      await axios.post(`/api/audit/${report.auditId}/cancel`);
      setNotice('Stop requested — the audit finishes the topics already in flight.');
    } catch (e) {
      // 409 = le run vient de se terminer entre l'affichage du bouton et le clic.
      if (axios.isAxiosError(e) && e.response?.status === 409) {
        setNotice('The audit had already finished.');
      } else {
        setError('Failed to cancel the audit.');
      }
    } finally {
      setCancelling(false);
    }
  };

  // Restaure le dernier rapport du serveur : un rafraîchissement de page ne doit pas
  // obliger à relancer un scan complet du cluster.
  useEffect(() => {
    let cancelled = false;
    axios.get<AuditReport | ''>('/api/audit/last')
      .then(res => {
        if (cancelled || !res.data || res.status === 204) return;
        setReport(res.data);
        if (res.data.status === 'RUNNING') {
          setLoading(true);
          const auditId = res.data.auditId;
          pollRef.current = setInterval(() => pollStatus(auditId), 2000);
        }
      })
      .catch(() => { /* pas de rapport antérieur : l'état vide fait le travail */ });
    return () => { cancelled = true; };
  }, [pollStatus]);

  useEffect(() => () => stopPolling(), [stopPolling]);

  const stats = report?.globalStats ?? {};
  const failed = report?.status === 'FAILED';
  const wasCancelled = report?.status === 'CANCELLED';
  // Un run annulé a produit de vrais résultats sur les topics déjà traités : on les affiche,
  // avec un bandeau qui dit que le rapport est partiel.
  const hasReport = report?.status === 'COMPLETED' || wasCancelled;

  // Pondéré côté serveur (CRITICAL = 1 point, WARNING = 0,5) et persisté avec le rapport.
  const healthScore = report && hasReport && report.totalTopics > 0
      && typeof stats.healthScore === 'number'
    ? Math.round(stats.healthScore * 100)
    : null;

  const progressPct = stats.topicsTotal
    ? Math.round(((stats.topicsCompleted ?? 0) / stats.topicsTotal) * 100)
    : null;

  const visibleTopics = useMemo(() => {
    if (!report) return [];
    const needle = topicFilter.trim().toLowerCase();
    const matchHealth = HEALTH_FILTERS.find(f => f.value === healthFilter)?.match ?? (() => true);
    const rows = report.topicAudits.filter(t => {
      if (needle && !t.name.toLowerCase().includes(needle)) return false;
      return matchHealth(t.healthStatus);
    });
    const direction = sortDesc ? -1 : 1;
    return [...rows].sort((a, b) => {
      if (sortKey === 'name') return a.name.localeCompare(b.name) * direction;
      return (a[sortKey] - b[sortKey]) * direction;
    });
  }, [report, topicFilter, healthFilter, sortKey, sortDesc]);

  const toggleSort = (key: SortKey) => {
    if (key === sortKey) setSortDesc(d => !d);
    else { setSortKey(key); setSortDesc(key !== 'name'); }
  };

  const sortIcon = (key: SortKey) =>
    key !== sortKey ? 'unfold_more' : sortDesc ? 'arrow_downward' : 'arrow_upward';

  const exportReport = () => {
    if (!report) return;
    const blob = new Blob([JSON.stringify(report, null, 2)], { type: 'application/json' });
    const url = URL.createObjectURL(blob);
    const link = document.createElement('a');
    link.href = url;
    link.download = `audit-${report.auditId}.json`;
    link.click();
    URL.revokeObjectURL(url);
  };

  const activeChecks = CHECK_LABELS.filter(c => stats.options?.[c.key]).map(c => c.label);
  const noneSelected = Object.values(options).every(v => !v);

  return (
    <div className="p-4 md:p-6 space-y-6">
      <PageHeader
        title="Cluster Audit"
        description="Deep health scan of topics, schemas, and stream flows."
        actions={
          <div className="flex items-center gap-2">
            {report && hasReport && (
              <Button variant="secondary" icon="download" onClick={exportReport}>Export JSON</Button>
            )}
            <Button
              variant="primary"
              icon={loading ? undefined : 'play_circle'}
              loading={loading}
              onClick={startAudit}
              disabled={loading || noneSelected}
            >
              {loading ? 'Running…' : 'Run new audit'}
            </Button>
          </div>
        }
      />

      {/* Check selection */}
      <Card padding="md">
        <div className="flex items-center justify-between mb-3">
          <span className="text-[11px] uppercase font-medium tracking-[0.05em] text-on-surface-variant">Checks to run</span>
          <div className="flex gap-3 text-[12px]">
            <button onClick={() => setOptions(ALL_CHECKED)} className="text-primary hover:underline">All</button>
            <button
              onClick={() => setOptions(NONE_CHECKED)}
              className="text-on-surface-variant hover:text-on-surface"
            >None</button>
          </div>
        </div>
        {/* Topic prefix filter */}
        <div className="mb-4">
          <label htmlFor="audit-topic-prefix" className="text-[11px] uppercase font-medium tracking-[0.05em] text-on-surface-variant">
            Topic prefix <span className="normal-case tracking-normal text-outline">(optional)</span>
          </label>
          {/* Suggère les topics existants : un préfixe qui ne correspond à rien produisait
              un rapport vide sans expliquer pourquoi. */}
          <TopicInput
            id="audit-topic-prefix"
            className="mt-1.5 max-w-sm"
            placeholder="e.g. orders."
            value={topicPrefix}
            onChange={setTopicPrefix}
            onEnter={() => { if (!loading && !noneSelected) startAudit(); }}
          />
          <p className="text-[11px] text-on-surface-variant mt-1">
            {topicPrefix.trim()
              ? <>Only topics starting with <span className="font-mono text-on-surface">{topicPrefix.trim()}</span> will be audited.</>
              : 'Leave empty to audit every topic in the cluster.'}
          </p>
        </div>

        <div className="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-3 gap-2">
          {CHECK_LABELS.map(({ key, label, description, icon, requires }) => {
            const missingPrerequisite = requires != null && options[key] && !options[requires];
            return (
              <label
                key={key}
                className={`flex items-start gap-3 p-3 rounded-lg border cursor-pointer transition-colors ${
                  options[key]
                    ? 'border-primary/40 bg-primary/10'
                    : 'border-outline-variant bg-transparent hover:border-outline'
                }`}
              >
                <input
                  type="checkbox"
                  checked={options[key]}
                  onChange={e => setOptions(o => ({ ...o, [key]: e.target.checked }))}
                  className="mt-0.5 accent-[var(--color-primary)] shrink-0"
                />
                <div className="min-w-0">
                  <div className="flex items-center gap-1.5">
                    <span className={`material-symbols-outlined text-[16px] ${options[key] ? 'text-primary' : 'text-on-surface-variant'}`}>{icon}</span>
                    <span className={`text-[13px] font-medium ${options[key] ? 'text-on-surface' : 'text-on-surface-variant'}`}>{label}</span>
                  </div>
                  <p className="text-[11px] text-on-surface-variant mt-0.5 leading-snug">{description}</p>
                  {/* Sans inférence de schéma, aucune table Flink n'est enregistrée : le COUNT(*)
                      retombe silencieusement sur l'estimation par offsets. */}
                  {missingPrerequisite && (
                    <p className="text-[11px] text-warning mt-1 leading-snug">
                      Needs “Schema inference” to run — otherwise it falls back to the offset estimate.
                    </p>
                  )}
                </div>
              </label>
            );
          })}
        </div>
      </Card>

      {/* Running State */}
      {loading && (
        <Card padding="md" className="flex items-center gap-4">
          <span className="material-symbols-outlined text-primary text-[30px] animate-pulse">radar</span>
          <div className="flex-1">
            <p className="text-[13px] font-semibold text-primary">
              {stats.cancelling ? 'Stopping…'
                : stats.phase === 'flows' ? 'Correlating stream flows…'
                : 'Scanning cluster…'}
            </p>
            <p className="text-[12px] text-on-surface-variant mt-1">
              {stats.cancelling
                ? 'Finishing the topics already in flight; the partial report will be kept.'
                : progressPct !== null
                  ? `${stats.topicsCompleted} of ${stats.topicsTotal} topics audited`
                  : 'Listing topics and reading offsets…'}
            </p>
            <div
              className="mt-3 h-1.5 bg-primary/15 rounded-full overflow-hidden"
              role="progressbar"
              aria-label="Audit progress"
              aria-valuenow={progressPct ?? undefined}
              aria-valuemin={0}
              aria-valuemax={100}
            >
              <div
                className={`h-full bg-primary rounded-full transition-[width] duration-500 ${progressPct === null ? 'animate-pulse' : ''}`}
                style={{ width: progressPct !== null ? `${Math.max(progressPct, 2)}%` : '15%' }}
              />
            </div>
          </div>
          {report?.status === 'RUNNING' && (
            <Button
              variant="outline"
              icon="stop_circle"
              onClick={cancelAudit}
              loading={cancelling}
              disabled={cancelling || stats.cancelling}
            >
              {stats.cancelling ? 'Stopping…' : 'Stop'}
            </Button>
          )}
        </Card>
      )}

      {/* Error */}
      {error && (
        <div className="rounded-xl border border-error/25 bg-error/10 p-4 flex items-center gap-3 text-error text-[13px]" role="alert">
          <span className="material-symbols-outlined text-[20px]">error</span>
          {error}
        </div>
      )}

      {/* Notice — informatif, surtout pas rendu en rouge comme une erreur */}
      {notice && (
        <div className="rounded-xl border border-outline-variant bg-surface-container p-4 flex items-center gap-3 text-on-surface-variant text-[13px]" role="status">
          <span className="material-symbols-outlined text-[20px]">info</span>
          {notice}
        </div>
      )}

      {/* Failed run — never render the KPI grid for one: an aborted audit used to show
          "0 topics / 100% health", i.e. a perfect cluster. */}
      {failed && (
        <div className="rounded-xl border border-error/25 bg-error/10 p-4 flex items-start gap-3 text-[13px]" role="alert">
          <span className="material-symbols-outlined text-[20px] text-error shrink-0">report</span>
          <div className="min-w-0">
            <span className="text-[11px] font-bold text-error uppercase tracking-widest">Audit failed</span>
            <p className="text-on-surface mt-1 leading-relaxed break-words">
              {stats.error ?? 'The audit stopped before producing a report.'}
            </p>
            {stats.errorType && (
              <p className="text-[11px] text-on-surface-variant mt-1 font-mono">{stats.errorType}</p>
            )}
          </div>
        </div>
      )}

      {/* Empty State */}
      {!loading && !report && !error && (
        <Card padding="none">
          <EmptyState
            icon="fact_check"
            title="No audit report yet"
            description="Run a full cluster health scan to inspect topics, schemas, duplicates and stream flows."
            action={<Button variant="primary" icon="play_circle" onClick={startAudit} disabled={noneSelected}>Run new audit</Button>}
          />
        </Card>
      )}

      {/* Report */}
      {report && hasReport && (
        <>
          {/* Rapport partiel : rien ici ne doit se lire comme un verdict sur tout le cluster. */}
          {wasCancelled && (
            <div className="rounded-xl border border-warning/25 bg-warning/10 p-4 flex items-start gap-3 text-[13px]" role="status">
              <span aria-hidden="true" className="material-symbols-outlined text-[20px] text-warning shrink-0">stop_circle</span>
              <div>
                <span className="text-[11px] font-bold text-warning uppercase tracking-widest">Partial report — run stopped</span>
                <p className="text-on-surface mt-1 leading-relaxed">
                  {stats.topicsInScope != null
                    ? <>Audited {report.totalTopics.toLocaleString()} of {stats.topicsInScope.toLocaleString()} topic(s) in scope. The figures below cover only those.</>
                    : <>The figures below cover only the topics audited before the stop.</>}
                </p>
              </div>
            </div>
          )}

          {/* Run recap — what was scanned, when, and for how long */}
          <div className="flex flex-wrap items-center gap-x-4 gap-y-1 text-[11px] text-on-surface-variant">
            {stats.timestamp && (
              <span className="inline-flex items-center gap-1">
                <span aria-hidden="true" className="material-symbols-outlined text-[14px]">schedule</span>
                {new Date(stats.timestamp).toLocaleString()}
              </span>
            )}
            {stats.durationMs != null && (
              <span className="inline-flex items-center gap-1">
                <span aria-hidden="true" className="material-symbols-outlined text-[14px]">timer</span>
                {formatDuration(stats.durationMs)}
              </span>
            )}
            {stats.options?.topicPrefix && (
              <span className="inline-flex items-center gap-1">
                <span aria-hidden="true" className="material-symbols-outlined text-[14px]">filter_list</span>
                prefix <span className="font-mono text-on-surface">{stats.options.topicPrefix}</span>
              </span>
            )}
            {activeChecks.length > 0 && (
              <span className="inline-flex items-center gap-1">
                <span aria-hidden="true" className="material-symbols-outlined text-[14px]">checklist</span>
                {activeChecks.join(' · ')}
              </span>
            )}
          </div>

          {/* KPI Summary */}
          <div className="grid grid-cols-2 lg:grid-cols-4 gap-4">
            <Stat label="Total Topics" icon="format_list_bulleted" value={report.totalTopics.toLocaleString()} />
            <Stat label="Total Messages" icon="bolt" tone="primary"
              value={<span title={report.totalMessages.toLocaleString()}>{compactNum(report.totalMessages)}</span>} />
            <Stat label="Needs Attention" icon="warning"
              tone={report.criticalTopicsCount > 0 ? 'error' : report.warningTopicsCount > 0 ? 'warning' : 'success'}
              value={(report.criticalTopicsCount + report.warningTopicsCount).toLocaleString()}
              hint={
                report.criticalTopicsCount + report.warningTopicsCount > 0
                  ? <span className="inline-flex gap-2">
                      <span className="text-error">{report.criticalTopicsCount} critical</span>
                      <span className="text-warning">{report.warningTopicsCount} warning</span>
                    </span>
                  : 'No finding'
              } />
            <Stat label="Health Score" icon="health_metrics"
              tone={healthScore == null ? 'none' : healthScore >= 80 ? 'success' : healthScore >= 50 ? 'warning' : 'error'}
              value={healthScore == null ? '—' : `${healthScore}%`}
              hint={healthScore == null ? 'No topic in scope' : 'Critical −1, warning −½'} />
          </div>

          {/* KRaft upgrade completeness — set when metadata.version lags broker support */}
          {stats.metadataVersionWarning && (
            <div className="rounded-xl border border-warning/25 bg-warning/10 p-4 flex items-start gap-3 text-[13px]" role="alert">
              <span className="material-symbols-outlined text-[20px] text-warning shrink-0">update</span>
              <div>
                <span className="text-[11px] font-bold text-warning uppercase tracking-widest">Incomplete KRaft upgrade</span>
                <p className="text-on-surface mt-1 leading-relaxed">{stats.metadataVersionWarning}</p>
                {stats.laggingFeatures && stats.laggingFeatures.length > 0 && (
                  <ul className="mt-2 space-y-0.5">
                    {stats.laggingFeatures.map(f => (
                      <li key={f.feature} className="font-mono text-[11px] text-on-surface-variant">
                        {f.feature}: finalized {f.finalizedVersion ?? '—'} / supported {f.supportedMaxVersion}
                      </li>
                    ))}
                  </ul>
                )}
              </div>
            </div>
          )}

          {/* What the run did NOT cover — a bounded scan reporting 0 findings is not "clean" */}
          {stats.scopeNotes && stats.scopeNotes.length > 0 && (
            <Card padding="md">
              <div className="flex items-start gap-3">
                <span aria-hidden="true" className="material-symbols-outlined text-[18px] text-on-surface-variant shrink-0 mt-0.5">info</span>
                <div>
                  <span className="text-[11px] uppercase font-medium tracking-[0.05em] text-on-surface-variant">Scope of this run</span>
                  <ul className="mt-1.5 space-y-1">
                    {stats.scopeNotes.map(note => (
                      <li key={note} className="text-[12px] text-on-surface-variant leading-snug">• {note}</li>
                    ))}
                  </ul>
                </div>
              </div>
            </Card>
          )}

          {/* Tabs */}
          <div className="flex gap-1 border-b border-outline-variant/60" role="tablist" aria-label="Audit results">
            {(['topics', 'flows'] as const).map((tab) => (
              <button
                key={tab}
                role="tab"
                id={`audit-tab-${tab}`}
                aria-selected={activeTab === tab}
                aria-controls={`audit-panel-${tab}`}
                onClick={() => setActiveTab(tab)}
                className={`px-4 py-2 text-[13px] font-medium transition-colors border-b-2 -mb-px ${
                  activeTab === tab
                    ? 'border-primary text-on-surface'
                    : 'border-transparent text-on-surface-variant hover:text-on-surface'
                }`}
              >
                {tab === 'topics' ? `Topics (${report.topicAudits.length})` : `Flows (${report.flowAudits.length})`}
              </button>
            ))}
          </div>

          {/* Topics Table */}
          {activeTab === 'topics' && (
            <div id="audit-panel-topics" role="tabpanel" aria-labelledby="audit-tab-topics" className="space-y-3">
              {/* Un cluster de démo compte déjà 70+ topics : sans filtre, la seule façon de
                  trouver les topics en défaut est de faire défiler la table entière. */}
              <div className="flex flex-wrap items-center gap-3">
                <div className="relative flex-1 min-w-[200px] max-w-sm">
                  <span aria-hidden="true" className="material-symbols-outlined text-on-surface-variant text-[18px] absolute left-2.5 top-1/2 -translate-y-1/2 pointer-events-none">search</span>
                  <Input
                    className="pl-9 h-9 font-mono"
                    placeholder="Filter topics…"
                    aria-label="Filter audited topics"
                    value={topicFilter}
                    onChange={e => setTopicFilter(e.target.value)}
                  />
                </div>
                <Select
                  className="h-9 w-auto"
                  aria-label="Filter by health"
                  value={healthFilter}
                  onChange={e => setHealthFilter(e.target.value as HealthFilter)}
                >
                  {HEALTH_FILTERS.map(f => (
                    <option key={f.value} value={f.value}>{f.label}</option>
                  ))}
                </Select>
                <span className="text-[11px] text-on-surface-variant tabular-nums">
                  {visibleTopics.length} of {report.topicAudits.length} topics
                </span>
              </div>

              <Table rowCount={visibleTopics.length}>
                <TableHead>
                  <tr>
                    {([
                      ['name', 'Topic', ''],
                      ['messageCount', 'Messages', 'text-right'],
                    ] as const).map(([key, label, align]) => (
                      <Th key={key} className={align} aria-sort={sortKey === key ? (sortDesc ? 'descending' : 'ascending') : 'none'}>
                        <button onClick={() => toggleSort(key)} className="inline-flex items-center gap-1 hover:text-on-surface uppercase tracking-[0.05em]">
                          {label}
                          <span aria-hidden="true" className="material-symbols-outlined text-[14px]">{sortIcon(key)}</span>
                        </button>
                      </Th>
                    ))}
                    <Th className="text-center">Format</Th>
                    {([
                      ['poisonMessageCount', 'Poison'],
                      ['duplicateCount', 'Duplicates'],
                    ] as const).map(([key, label]) => (
                      <Th key={key} className="text-right" aria-sort={sortKey === key ? (sortDesc ? 'descending' : 'ascending') : 'none'}>
                        <button onClick={() => toggleSort(key)} className="inline-flex items-center gap-1 hover:text-on-surface uppercase tracking-[0.05em]">
                          {label}
                          <span aria-hidden="true" className="material-symbols-outlined text-[14px]">{sortIcon(key)}</span>
                        </button>
                      </Th>
                    ))}
                    <Th className="text-center">Health</Th>
                    <Th>Issues</Th>
                  </tr>
                </TableHead>
                <TableBody>
                  {visibleTopics.map((t) => (
                    <TableRow key={t.name}>
                      <Td className="font-mono font-medium">
                        <Link to={`/topic/${encodeURIComponent(t.name)}`} className="text-on-surface hover:text-primary hover:underline">
                          {t.name}
                        </Link>
                      </Td>
                      {/* Titre = valeur exacte : "1.2K" dans une colonne intitulée "exact count" ment. */}
                      <Td className="text-right font-mono tabular-nums" title={t.messageCount.toLocaleString()}>{compactNum(t.messageCount)}</Td>
                      <Td className="text-center"><Badge tone="neutral">{t.format}</Badge></Td>
                      <Td className={`text-right font-mono tabular-nums ${t.poisonMessageCount > 0 ? 'text-error font-semibold' : 'text-on-surface-variant'}`}>{t.poisonMessageCount}</Td>
                      <Td className={`text-right font-mono tabular-nums ${t.duplicateCount > 0 ? 'text-warning' : 'text-on-surface-variant'}`}>{t.duplicateCount}</Td>
                      <Td className="text-center">
                        <Badge tone={HEALTH_TONE[t.healthStatus] ?? 'neutral'} dot>{t.healthStatus}</Badge>
                      </Td>
                      <Td>
                        {t.issues.length > 0 ? (
                          <div className="flex flex-wrap gap-1">
                            {t.issues.map((issue, i) => (
                              <span
                                key={i}
                                title={issue.severity}
                                className={`text-[11px] px-1.5 py-0.5 rounded border ${ISSUE_STYLE[issue.severity]}`}
                              >
                                {issue.message}
                              </span>
                            ))}
                          </div>
                        ) : (
                          <span className="text-outline">—</span>
                        )}
                      </Td>
                    </TableRow>
                  ))}
                  {visibleTopics.length === 0 && (
                    <tr><td colSpan={7}>
                      <EmptyState
                        icon="inbox"
                        title={report.topicAudits.length === 0 ? 'No topic audits available' : 'No topic matches the filter'}
                      />
                    </td></tr>
                  )}
                </TableBody>
              </Table>
            </div>
          )}

          {/* Flow Audits */}
          {activeTab === 'flows' && (
            <div id="audit-panel-flows" role="tabpanel" aria-labelledby="audit-tab-flows" className="space-y-4">
              {report.flowAudits.length === 0 && (
                <Card padding="none"><EmptyState icon="account_tree" title="No flow audits available" description="Flows are grouped from topic naming conventions." /></Card>
              )}
              {report.flowAudits.map((flow) => (
                <Card key={flow.flowName} padding="none" className="overflow-hidden">
                  <div className="p-4 border-b border-outline-variant/60 flex items-center justify-between">
                    <div className="flex items-center gap-3">
                      <span className="material-symbols-outlined text-primary text-[20px]">account_tree</span>
                      <span className="font-semibold text-on-surface">{flow.flowName}</span>
                    </div>
                    <div className="flex items-center gap-2">
                      <span className="text-[11px] uppercase font-medium tracking-[0.05em] text-on-surface-variant" title="Share of the first step's volume still reaching the last one">
                        End-to-end retention
                      </span>
                      <span className={`text-lg font-semibold tabular-nums ${flow.overallHealthScore >= 0.8 ? 'text-success' : flow.overallHealthScore >= 0.5 ? 'text-warning' : 'text-error'}`}>
                        {Math.round(flow.overallHealthScore * 100)}%
                      </span>
                    </div>
                  </div>
                  <div className="p-4 flex items-start gap-2 overflow-x-auto">
                    {flow.steps.map((step, idx) => (
                      <React.Fragment key={step.topicName}>
                        <div className="flex-shrink-0 bg-surface-container-high border border-outline-variant rounded-lg p-3 min-w-[140px]">
                          <Link to={`/topic/${encodeURIComponent(step.topicName)}`} className="font-mono text-[12px] font-medium text-on-surface hover:text-primary hover:underline block truncate" title={step.topicName}>
                            {step.topicName}
                          </Link>
                          <p className="text-[11px] text-on-surface-variant mt-1 tabular-nums" title={`${step.count.toLocaleString()} messages`}>{compactNum(step.count)} msgs</p>
                          {step.averageLatencyMs !== null && (
                            <p className="text-[11px] text-on-surface-variant tabular-nums">{step.averageLatencyMs}ms avg</p>
                          )}
                          {idx > 0 && (
                            <div className={`mt-2 text-[11px] font-semibold tabular-nums ${step.throughputPercentage >= 90 ? 'text-success' : step.throughputPercentage >= 70 ? 'text-warning' : 'text-error'}`}>
                              {step.throughputPercentage.toFixed(1)}% throughput
                            </div>
                          )}
                        </div>
                        {idx < flow.steps.length - 1 && (
                          <span className="material-symbols-outlined text-outline self-center flex-shrink-0">arrow_forward</span>
                        )}
                      </React.Fragment>
                    ))}
                  </div>
                </Card>
              ))}
            </div>
          )}
        </>
      )}
    </div>
  );
};

export default Audit;
