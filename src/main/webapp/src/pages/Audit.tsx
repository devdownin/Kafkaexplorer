import React, { useState, useEffect, useRef } from 'react';
import axios from 'axios';

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

const healthColor: Record<string, string> = {
  HEALTHY: 'text-success bg-success/10 border-success/20',
  WARNING: 'text-warning bg-warning/10 border-warning/20',
  CRITICAL: 'text-error bg-error/10 border-error/20',
  UNKNOWN: 'text-on-surface-variant bg-outline/10 border-outline-variant/20',
};

const healthDot: Record<string, string> = {
  HEALTHY: 'bg-success',
  WARNING: 'bg-warning',
  CRITICAL: 'bg-error',
  UNKNOWN: 'bg-outline',
};

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
      const res = await axios.post<string>('/api/audit/start', options);
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
    <div className="p-6 space-y-6">
      {/* Header */}
      <div className="flex items-center justify-between">
        <div>
          <h1 className="text-2xl font-bold">Cluster Audit</h1>
          <p className="text-on-surface0 dark:text-on-surface-variant text-sm mt-1">
            Deep health scan of topics, schemas, and stream flows.
          </p>
        </div>
        <button
          onClick={startAudit}
          disabled={loading || Object.values(options).every(v => !v)}
          className="flex items-center gap-2 bg-primary hover:brightness-110 disabled:opacity-50 text-background-dark px-5 py-2.5 rounded-lg font-bold transition-all text-sm"
        >
          {loading ? (
            <span className="material-symbols-outlined animate-spin text-lg">refresh</span>
          ) : (
            <span className="material-symbols-outlined text-lg">play_circle</span>
          )}
          {loading ? 'Running...' : 'Run New Audit'}
        </button>
      </div>

      {/* Check selection */}
      <div className="rounded-xl border border-primary/10 bg-primary/5 p-4">
        <div className="flex items-center justify-between mb-3">
          <span className="text-[10px] uppercase font-bold tracking-widest text-on-surface0">Checks to run</span>
          <div className="flex gap-3 text-xs">
            <button onClick={() => setOptions(ALL_CHECKED)} className="text-primary hover:underline">All</button>
            <button
              onClick={() => setOptions({ checkSchema: false, checkPoisonMessages: false, checkDuplicates: false, checkFlows: false, checkExactCount: false })}
              className="text-on-surface0 hover:underline"
            >None</button>
          </div>
        </div>
        <div className="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-3 gap-2">
          {CHECK_LABELS.map(({ key, label, description, icon }) => (
            <label
              key={key}
              className={`flex items-start gap-3 p-3 rounded-lg border cursor-pointer transition-colors ${
                options[key]
                  ? 'border-primary/30 bg-primary/10'
                  : 'border-primary/10 bg-transparent hover:border-primary/20'
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
                  <span className={`material-symbols-outlined text-sm ${options[key] ? 'text-primary' : 'text-on-surface0'}`}>{icon}</span>
                  <span className={`text-xs font-bold ${options[key] ? 'text-on-surface' : 'text-on-surface-variant'}`}>{label}</span>
                </div>
                <p className="text-[10px] text-on-surface0 mt-0.5 leading-snug">{description}</p>
              </div>
            </label>
          ))}
        </div>
      </div>

      {/* Running State */}
      {loading && (
        <div className="rounded-xl border border-primary/20 bg-primary/5 p-6 flex items-center gap-4">
          <span className="material-symbols-outlined text-primary text-3xl animate-pulse">radar</span>
          <div className="flex-1">
            <p className="text-sm font-bold text-primary">Scanning cluster...</p>
            <p className="text-xs text-on-surface-variant mt-1">Inspecting topics, schema formats, duplicates, and stream flows.</p>
            <div className="mt-3 h-1.5 bg-primary/10 rounded-full overflow-hidden">
              <div className="h-full bg-primary rounded-full animate-pulse" style={{ width: '60%' }} />
            </div>
          </div>
        </div>
      )}

      {/* Error */}
      {error && (
        <div className="rounded-xl border border-error/20 bg-error/5 p-4 flex items-center gap-3 text-error text-sm">
          <span className="material-symbols-outlined">warning</span>
          {error}
        </div>
      )}

      {/* Empty State */}
      {!loading && !report && !error && (
        <div className="rounded-xl border border-primary/10 bg-background-dark/30 p-16 flex flex-col items-center gap-4 text-center">
          <span className="material-symbols-outlined text-5xl text-outline">assignment</span>
          <div>
            <p className="font-bold text-on-surface">No audit report yet</p>
            <p className="text-sm text-on-surface0 mt-1">Click <b>Run New Audit</b> to start a full cluster health scan.</p>
          </div>
        </div>
      )}

      {/* Report */}
      {report && report.status !== 'RUNNING' && (
        <>
          {/* KPI Summary */}
          <div className="grid grid-cols-2 lg:grid-cols-4 gap-4">
            {[
              { label: 'Total Topics', value: report.totalTopics, icon: 'format_list_bulleted', color: 'text-primary' },
              { label: 'Total Messages', value: formatNum(report.totalMessages), icon: 'bolt', color: 'text-primary', raw: true },
              { label: 'Unhealthy Topics', value: report.unhealthyTopicsCount, icon: 'warning', color: report.unhealthyTopicsCount > 0 ? 'text-error' : 'text-success' },
              { label: 'Health Score', value: `${healthScore}%`, icon: 'health_metrics', color: healthScore! >= 80 ? 'text-success' : healthScore! >= 50 ? 'text-warning' : 'text-error', raw: true },
            ].map((kpi) => (
              <div key={kpi.label} className="rounded-xl border border-primary/10 bg-primary/5 p-5">
                <div className="flex items-center gap-2 mb-2">
                  <span className={`material-symbols-outlined text-xl ${kpi.color}`}>{kpi.icon}</span>
                  <span className="text-[10px] uppercase font-bold tracking-widest text-on-surface0">{kpi.label}</span>
                </div>
                <p className={`text-3xl font-bold ${kpi.color}`}>
                  {kpi.raw ? kpi.value : kpi.value}
                </p>
              </div>
            ))}
          </div>

          {/* Tabs */}
          <div className="flex gap-1 border-b border-primary/10">
            {(['topics', 'flows'] as const).map((tab) => (
              <button
                key={tab}
                onClick={() => setActiveTab(tab)}
                className={`px-4 py-2 text-sm font-bold uppercase tracking-wider transition-colors border-b-2 -mb-px ${
                  activeTab === tab
                    ? 'border-primary text-primary'
                    : 'border-transparent text-on-surface0 hover:text-on-surface'
                }`}
              >
                {tab === 'topics' ? `Topics (${report.topicAudits.length})` : `Flows (${report.flowAudits.length})`}
              </button>
            ))}
          </div>

          {/* Topics Table */}
          {activeTab === 'topics' && (
            <div className="rounded-xl border border-primary/10 overflow-hidden">
              <table className="w-full text-sm">
                <thead>
                  <tr className="bg-primary/5 border-b border-primary/10 text-[10px] uppercase tracking-widest text-on-surface0">
                    <th className="text-left px-4 py-3">Topic</th>
                    <th className="text-right px-4 py-3">Messages</th>
                    <th className="text-center px-4 py-3">Format</th>
                    <th className="text-right px-4 py-3">Poison</th>
                    <th className="text-right px-4 py-3">Duplicates</th>
                    <th className="text-center px-4 py-3">Health</th>
                    <th className="text-left px-4 py-3">Issues</th>
                  </tr>
                </thead>
                <tbody className="divide-y divide-primary/5">
                  {report.topicAudits.map((t) => (
                    <tr key={t.name} className="hover:bg-primary/5 transition-colors">
                      <td className="px-4 py-3 font-mono font-medium text-on-surface">{t.name}</td>
                      <td className="px-4 py-3 text-right font-mono text-on-surface">{formatNum(t.messageCount)}</td>
                      <td className="px-4 py-3 text-center">
                        <span className="px-2 py-0.5 rounded text-[10px] font-bold bg-surface-container-high text-on-surface">{t.format}</span>
                      </td>
                      <td className={`px-4 py-3 text-right font-mono ${t.poisonMessageCount > 0 ? 'text-error font-bold' : 'text-on-surface0'}`}>
                        {t.poisonMessageCount}
                      </td>
                      <td className={`px-4 py-3 text-right font-mono ${t.duplicateCount > 0 ? 'text-warning' : 'text-on-surface0'}`}>
                        {t.duplicateCount}
                      </td>
                      <td className="px-4 py-3 text-center">
                        <span className={`inline-flex items-center gap-1.5 px-2 py-0.5 rounded-full border text-[10px] font-bold ${healthColor[t.healthStatus] ?? healthColor.UNKNOWN}`}>
                          <span className={`w-1.5 h-1.5 rounded-full ${healthDot[t.healthStatus] ?? healthDot.UNKNOWN}`} />
                          {t.healthStatus}
                        </span>
                      </td>
                      <td className="px-4 py-3">
                        {t.issues.length > 0 ? (
                          <div className="flex flex-wrap gap-1">
                            {t.issues.map((issue, i) => (
                              <span key={i} className="text-[10px] bg-error/10 text-error px-1.5 py-0.5 rounded border border-error/20">{issue}</span>
                            ))}
                          </div>
                        ) : (
                          <span className="text-outline text-xs">—</span>
                        )}
                      </td>
                    </tr>
                  ))}
                  {report.topicAudits.length === 0 && (
                    <tr>
                      <td colSpan={7} className="px-4 py-8 text-center text-on-surface0">No topic audits available.</td>
                    </tr>
                  )}
                </tbody>
              </table>
            </div>
          )}

          {/* Flow Audits */}
          {activeTab === 'flows' && (
            <div className="space-y-4">
              {report.flowAudits.length === 0 && (
                <div className="rounded-xl border border-primary/10 p-10 text-center text-on-surface0">
                  No flow audits available.
                </div>
              )}
              {report.flowAudits.map((flow) => (
                <div key={flow.flowName} className="rounded-xl border border-primary/10 bg-primary/5 overflow-hidden">
                  <div className="p-4 border-b border-primary/10 flex items-center justify-between">
                    <div className="flex items-center gap-3">
                      <span className="material-symbols-outlined text-primary">account_tree</span>
                      <span className="font-bold text-on-surface">{flow.flowName}</span>
                    </div>
                    <div className="flex items-center gap-2">
                      <span className="text-[10px] uppercase font-bold text-on-surface0">Health Score</span>
                      <span className={`text-lg font-bold ${flow.overallHealthScore >= 0.8 ? 'text-success' : flow.overallHealthScore >= 0.5 ? 'text-warning' : 'text-error'}`}>
                        {Math.round(flow.overallHealthScore * 100)}%
                      </span>
                    </div>
                  </div>
                  <div className="p-4 flex items-start gap-2 overflow-x-auto">
                    {flow.steps.map((step, idx) => (
                      <React.Fragment key={step.topicName}>
                        <div className="flex-shrink-0 bg-background-dark/50 border border-primary/20 rounded-lg p-3 min-w-[140px]">
                          <p className="font-mono text-xs font-bold text-on-surface truncate">{step.topicName}</p>
                          <p className="text-[10px] text-on-surface0 mt-1">{formatNum(step.count)} msgs</p>
                          {step.averageLatencyMs !== null && (
                            <p className="text-[10px] text-on-surface0">{step.averageLatencyMs}ms avg</p>
                          )}
                          {idx > 0 && (
                            <div className={`mt-2 text-[10px] font-bold ${step.throughputPercentage >= 90 ? 'text-success' : step.throughputPercentage >= 70 ? 'text-warning' : 'text-error'}`}>
                              {step.throughputPercentage.toFixed(1)}% throughput
                            </div>
                          )}
                        </div>
                        {idx < flow.steps.length - 1 && (
                          <span className="material-symbols-outlined text-primary/40 self-center flex-shrink-0">arrow_forward</span>
                        )}
                      </React.Fragment>
                    ))}
                  </div>
                </div>
              ))}
            </div>
          )}
        </>
      )}
    </div>
  );
};

export default Audit;
