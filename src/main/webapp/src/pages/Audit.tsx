import React, { useState, useEffect, useRef } from 'react';
import axios from 'axios';
import {
  PageHeader, Button, Stat, Badge, EmptyState, Card, Input,
  Table, TableHead, TableBody, TableRow, Th, Td, type BadgeTone,
} from '../components/ui';

const HEALTH_TONE: Record<string, BadgeTone> = {
  HEALTHY: 'success', WARNING: 'warning', CRITICAL: 'error', UNKNOWN: 'neutral',
};

interface TopicAudit {
  name: string;
  messageCount: number;
  format: string;
  poisonMessageCount: number;
  duplicateCount: number;
  healthStatus: string;
  issues: string[];
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
  overallHealthScore: number;
}

interface AuditReport {
  auditId: string;
  status: 'RUNNING' | 'COMPLETED' | 'FAILED';
  totalTopics: number;
  totalMessages: number;
  unhealthyTopicsCount: number;
  topicAudits: TopicAudit[];
  flowAudits: FlowAudit[];
  globalStats: Record<string, unknown>;
}

interface AuditOptions {
  checkSchema: boolean;
  checkPoisonMessages: boolean;
  checkDuplicates: boolean;
  checkFlows: boolean;
  checkExactCount: boolean;
}

const CHECK_LABELS: { key: keyof AuditOptions; label: string; description: string; icon: string }[] = [
  { key: 'checkSchema',         label: 'Schema inference',    description: 'Detect message format (JSON/XML/Avro) and infer field types', icon: 'schema' },
  { key: 'checkExactCount',     label: 'Exact message count', description: 'Run COUNT(*) via Flink SQL (slower than Kafka offset diff)',    icon: 'tag' },
  { key: 'checkPoisonMessages', label: 'Poison messages',     description: 'Sample 10 messages per topic and flag malformed payloads',     icon: 'bug_report' },
  { key: 'checkDuplicates',     label: 'Duplicate detection', description: 'GROUP BY key field and count rows appearing more than once',   icon: 'content_copy' },
  { key: 'checkFlows',          label: 'Flow analysis',       description: 'Group topics by naming convention and compute inter-step latency', icon: 'account_tree' },
];

const ALL_CHECKED: AuditOptions = {
  checkSchema: true, checkPoisonMessages: true,
  checkDuplicates: true, checkFlows: true, checkExactCount: true,
};

const Audit: React.FC = () => {
  const [report, setReport] = useState<AuditReport | null>(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [activeTab, setActiveTab] = useState<'topics' | 'flows'>('topics');
  const [options, setOptions] = useState<AuditOptions>(ALL_CHECKED);
  // Restreint l'audit aux topics dont le nom commence par ce préfixe (vide = tous).
  const [topicPrefix, setTopicPrefix] = useState('');
  const pollRef = useRef<ReturnType<typeof setInterval> | null>(null);

  const stopPolling = () => {
    if (pollRef.current) {
      clearInterval(pollRef.current);
      pollRef.current = null;
    }
  };

  const pollStatus = async (auditId: string) => {
    try {
      const res = await axios.get<AuditReport>(`/api/audit/status/${auditId}`);
      setReport(res.data);
      if (res.data.status !== 'RUNNING') {
        stopPolling();
        setLoading(false);
      }
    } catch {
      stopPolling();
      setLoading(false);
      setError('Failed to fetch audit status.');
    }
  };

  const startAudit = async () => {
    setLoading(true);
    setError(null);
    setReport(null);
    try {
      const res = await axios.post<string>('/api/audit/start', { ...options, topicPrefix: topicPrefix.trim() || null });
      const auditId = res.data;
      pollRef.current = setInterval(() => pollStatus(auditId), 2000);
    } catch {
      setLoading(false);
      setError('Failed to start audit.');
    }
  };

  useEffect(() => () => stopPolling(), []);

  const healthScore = report
    ? Math.round(((report.totalTopics - report.unhealthyTopicsCount) / Math.max(report.totalTopics, 1)) * 100)
    : null;

  const formatNum = (n: number) => {
    if (n >= 1_000_000) return (n / 1_000_000).toFixed(1) + 'M';
    if (n >= 1_000) return (n / 1_000).toFixed(1) + 'K';
    return n.toString();
  };

  return (
    <div className="p-4 md:p-6 space-y-6">
      <PageHeader
        title="Cluster Audit"
        description="Deep health scan of topics, schemas, and stream flows."
        actions={
          <Button
            variant="primary"
            icon={loading ? undefined : 'play_circle'}
            loading={loading}
            onClick={startAudit}
            disabled={loading || Object.values(options).every(v => !v)}
          >
            {loading ? 'Running…' : 'Run new audit'}
          </Button>
        }
      />

      {/* Check selection */}
      <Card padding="md">
        <div className="flex items-center justify-between mb-3">
          <span className="text-[11px] uppercase font-medium tracking-[0.05em] text-on-surface-variant">Checks to run</span>
          <div className="flex gap-3 text-[12px]">
            <button onClick={() => setOptions(ALL_CHECKED)} className="text-primary hover:underline">All</button>
            <button
              onClick={() => setOptions({ checkSchema: false, checkPoisonMessages: false, checkDuplicates: false, checkFlows: false, checkExactCount: false })}
              className="text-on-surface-variant hover:text-on-surface"
            >None</button>
          </div>
        </div>
        {/* Topic prefix filter */}
        <div className="mb-4">
          <label htmlFor="audit-topic-prefix" className="text-[11px] uppercase font-medium tracking-[0.05em] text-on-surface-variant">
            Topic prefix <span className="normal-case tracking-normal text-outline">(optional)</span>
          </label>
          <div className="relative mt-1.5 max-w-sm">
            <span aria-hidden="true" className="material-symbols-outlined text-on-surface-variant text-[18px] absolute left-2.5 top-1/2 -translate-y-1/2 pointer-events-none">filter_list</span>
            <Input
              id="audit-topic-prefix"
              className="pl-9 pr-8 h-9 font-mono"
              placeholder="e.g. orders."
              value={topicPrefix}
              onChange={e => setTopicPrefix(e.target.value)}
              onKeyDown={e => { if (e.key === 'Enter' && !loading) startAudit(); }}
            />
            {topicPrefix && (
              <button onClick={() => setTopicPrefix('')} aria-label="Clear prefix" className="absolute right-2 top-1/2 -translate-y-1/2 text-outline hover:text-on-surface">
                <span className="material-symbols-outlined text-[16px]">close</span>
              </button>
            )}
          </div>
          <p className="text-[11px] text-on-surface-variant mt-1">
            {topicPrefix.trim()
              ? <>Only topics starting with <span className="font-mono text-on-surface">{topicPrefix.trim()}</span> will be audited.</>
              : 'Leave empty to audit every topic in the cluster.'}
          </p>
        </div>

        <div className="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-3 gap-2">
          {CHECK_LABELS.map(({ key, label, description, icon }) => (
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
              </div>
            </label>
          ))}
        </div>
      </Card>

      {/* Running State */}
      {loading && (
        <Card padding="md" className="flex items-center gap-4">
          <span className="material-symbols-outlined text-primary text-[30px] animate-pulse">radar</span>
          <div className="flex-1">
            <p className="text-[13px] font-semibold text-primary">Scanning cluster…</p>
            <p className="text-[12px] text-on-surface-variant mt-1">Inspecting topics, schema formats, duplicates, and stream flows.</p>
            <div className="mt-3 h-1.5 bg-primary/15 rounded-full overflow-hidden">
              <div className="h-full bg-primary rounded-full animate-pulse" style={{ width: '60%' }} />
            </div>
          </div>
        </Card>
      )}

      {/* Error */}
      {error && (
        <div className="rounded-xl border border-error/25 bg-error/10 p-4 flex items-center gap-3 text-error text-[13px]" role="alert">
          <span className="material-symbols-outlined text-[20px]">error</span>
          {error}
        </div>
      )}

      {/* Empty State */}
      {!loading && !report && !error && (
        <Card padding="none">
          <EmptyState
            icon="fact_check"
            title="No audit report yet"
            description="Run a full cluster health scan to inspect topics, schemas, duplicates and stream flows."
            action={<Button variant="primary" icon="play_circle" onClick={startAudit} disabled={Object.values(options).every(v => !v)}>Run new audit</Button>}
          />
        </Card>
      )}

      {/* Report */}
      {report && report.status !== 'RUNNING' && (
        <>
          {/* KPI Summary */}
          <div className="grid grid-cols-2 lg:grid-cols-4 gap-4">
            <Stat label="Total Topics" icon="format_list_bulleted" value={report.totalTopics.toLocaleString()} />
            <Stat label="Total Messages" icon="bolt" tone="primary" value={formatNum(report.totalMessages)} />
            <Stat label="Unhealthy Topics" icon="warning"
              tone={report.unhealthyTopicsCount > 0 ? 'error' : 'success'}
              value={report.unhealthyTopicsCount.toLocaleString()} />
            <Stat label="Health Score" icon="health_metrics"
              tone={healthScore! >= 80 ? 'success' : healthScore! >= 50 ? 'warning' : 'error'}
              value={`${healthScore}%`} />
          </div>

          {/* KRaft upgrade completeness — set when metadata.version lags broker support */}
          {typeof report.globalStats.metadataVersionWarning === 'string' && (
            <div className="rounded-xl border border-warning/25 bg-warning/10 p-4 flex items-start gap-3 text-[13px]" role="alert">
              <span className="material-symbols-outlined text-[20px] text-warning shrink-0">update</span>
              <div>
                <span className="text-[11px] font-bold text-warning uppercase tracking-widest">Incomplete KRaft upgrade</span>
                <p className="text-on-surface mt-1 leading-relaxed">{report.globalStats.metadataVersionWarning}</p>
              </div>
            </div>
          )}

          {/* Tabs */}
          <div className="flex gap-1 border-b border-outline-variant/60">
            {(['topics', 'flows'] as const).map((tab) => (
              <button
                key={tab}
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
            <Table rowCount={report.topicAudits.length}>
              <TableHead>
                <tr>
                  <Th>Topic</Th>
                  <Th className="text-right">Messages</Th>
                  <Th className="text-center">Format</Th>
                  <Th className="text-right">Poison</Th>
                  <Th className="text-right">Duplicates</Th>
                  <Th className="text-center">Health</Th>
                  <Th>Issues</Th>
                </tr>
              </TableHead>
              <TableBody>
                {report.topicAudits.map((t) => (
                  <TableRow key={t.name}>
                    <Td className="font-mono font-medium text-on-surface">{t.name}</Td>
                    <Td className="text-right font-mono tabular-nums">{formatNum(t.messageCount)}</Td>
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
                            <span key={i} className="text-[11px] bg-error/10 text-error px-1.5 py-0.5 rounded border border-error/25">{issue}</span>
                          ))}
                        </div>
                      ) : (
                        <span className="text-outline">—</span>
                      )}
                    </Td>
                  </TableRow>
                ))}
                {report.topicAudits.length === 0 && (
                  <tr><td colSpan={7}><EmptyState icon="inbox" title="No topic audits available" /></td></tr>
                )}
              </TableBody>
            </Table>
          )}

          {/* Flow Audits */}
          {activeTab === 'flows' && (
            <div className="space-y-4">
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
                      <span className="text-[11px] uppercase font-medium tracking-[0.05em] text-on-surface-variant">Health Score</span>
                      <span className={`text-lg font-semibold tabular-nums ${flow.overallHealthScore >= 0.8 ? 'text-success' : flow.overallHealthScore >= 0.5 ? 'text-warning' : 'text-error'}`}>
                        {Math.round(flow.overallHealthScore * 100)}%
                      </span>
                    </div>
                  </div>
                  <div className="p-4 flex items-start gap-2 overflow-x-auto">
                    {flow.steps.map((step, idx) => (
                      <React.Fragment key={step.topicName}>
                        <div className="flex-shrink-0 bg-surface-container-high border border-outline-variant rounded-lg p-3 min-w-[140px]">
                          <p className="font-mono text-[12px] font-medium text-on-surface truncate">{step.topicName}</p>
                          <p className="text-[11px] text-on-surface-variant mt-1 tabular-nums">{formatNum(step.count)} msgs</p>
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
