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
  HEALTHY: 'text-emerald-400 bg-emerald-500/10 border-emerald-500/20',
  WARNING: 'text-amber-400 bg-amber-500/10 border-amber-500/20',
  CRITICAL: 'text-red-400 bg-red-500/10 border-red-500/20',
  UNKNOWN: 'text-slate-400 bg-slate-500/10 border-slate-500/20',
};

const healthDot: Record<string, string> = {
  HEALTHY: 'bg-emerald-500',
  WARNING: 'bg-amber-500',
  CRITICAL: 'bg-red-500',
  UNKNOWN: 'bg-slate-500',
};

const Audit: React.FC = () => {
  const [report, setReport] = useState<AuditReport | null>(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [activeTab, setActiveTab] = useState<'topics' | 'flows'>('topics');
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
      const res = await axios.post<string>('/api/audit/start');
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
          <p className="text-slate-500 dark:text-slate-400 text-sm mt-1">
            Deep health scan of topics, schemas, and stream flows.
          </p>
        </div>
        <button
          onClick={startAudit}
          disabled={loading}
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

      {/* Running State */}
      {loading && (
        <div className="rounded-xl border border-primary/20 bg-primary/5 p-6 flex items-center gap-4">
          <span className="material-symbols-outlined text-primary text-3xl animate-pulse">radar</span>
          <div className="flex-1">
            <p className="text-sm font-bold text-primary">Scanning cluster...</p>
            <p className="text-xs text-slate-400 mt-1">Inspecting topics, schema formats, duplicates, and stream flows.</p>
            <div className="mt-3 h-1.5 bg-primary/10 rounded-full overflow-hidden">
              <div className="h-full bg-primary rounded-full animate-pulse" style={{ width: '60%' }} />
            </div>
          </div>
        </div>
      )}

      {/* Error */}
      {error && (
        <div className="rounded-xl border border-red-500/20 bg-red-500/5 p-4 flex items-center gap-3 text-red-400 text-sm">
          <span className="material-symbols-outlined">warning</span>
          {error}
        </div>
      )}

      {/* Empty State */}
      {!loading && !report && !error && (
        <div className="rounded-xl border border-primary/10 bg-background-dark/30 p-16 flex flex-col items-center gap-4 text-center">
          <span className="material-symbols-outlined text-5xl text-slate-600">assignment</span>
          <div>
            <p className="font-bold text-slate-300">No audit report yet</p>
            <p className="text-sm text-slate-500 mt-1">Click <b>Run New Audit</b> to start a full cluster health scan.</p>
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
              { label: 'Unhealthy Topics', value: report.unhealthyTopicsCount, icon: 'warning', color: report.unhealthyTopicsCount > 0 ? 'text-red-400' : 'text-emerald-400' },
              { label: 'Health Score', value: `${healthScore}%`, icon: 'health_metrics', color: healthScore! >= 80 ? 'text-emerald-400' : healthScore! >= 50 ? 'text-amber-400' : 'text-red-400', raw: true },
            ].map((kpi) => (
              <div key={kpi.label} className="rounded-xl border border-primary/10 bg-primary/5 p-5">
                <div className="flex items-center gap-2 mb-2">
                  <span className={`material-symbols-outlined text-xl ${kpi.color}`}>{kpi.icon}</span>
                  <span className="text-[10px] uppercase font-bold tracking-widest text-slate-500">{kpi.label}</span>
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
                    : 'border-transparent text-slate-500 hover:text-slate-300'
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
                  <tr className="bg-primary/5 border-b border-primary/10 text-[10px] uppercase tracking-widest text-slate-500">
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
                      <td className="px-4 py-3 font-mono font-medium text-slate-200">{t.name}</td>
                      <td className="px-4 py-3 text-right font-mono text-slate-300">{formatNum(t.messageCount)}</td>
                      <td className="px-4 py-3 text-center">
                        <span className="px-2 py-0.5 rounded text-[10px] font-bold bg-slate-700 text-slate-300">{t.format}</span>
                      </td>
                      <td className={`px-4 py-3 text-right font-mono ${t.poisonMessageCount > 0 ? 'text-red-400 font-bold' : 'text-slate-500'}`}>
                        {t.poisonMessageCount}
                      </td>
                      <td className={`px-4 py-3 text-right font-mono ${t.duplicateCount > 0 ? 'text-amber-400' : 'text-slate-500'}`}>
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
                              <span key={i} className="text-[10px] bg-red-500/10 text-red-400 px-1.5 py-0.5 rounded border border-red-500/20">{issue}</span>
                            ))}
                          </div>
                        ) : (
                          <span className="text-slate-600 text-xs">—</span>
                        )}
                      </td>
                    </tr>
                  ))}
                  {report.topicAudits.length === 0 && (
                    <tr>
                      <td colSpan={7} className="px-4 py-8 text-center text-slate-500">No topic audits available.</td>
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
                <div className="rounded-xl border border-primary/10 p-10 text-center text-slate-500">
                  No flow audits available.
                </div>
              )}
              {report.flowAudits.map((flow) => (
                <div key={flow.flowName} className="rounded-xl border border-primary/10 bg-primary/5 overflow-hidden">
                  <div className="p-4 border-b border-primary/10 flex items-center justify-between">
                    <div className="flex items-center gap-3">
                      <span className="material-symbols-outlined text-primary">account_tree</span>
                      <span className="font-bold text-slate-100">{flow.flowName}</span>
                    </div>
                    <div className="flex items-center gap-2">
                      <span className="text-[10px] uppercase font-bold text-slate-500">Health Score</span>
                      <span className={`text-lg font-bold ${flow.overallHealthScore >= 0.8 ? 'text-emerald-400' : flow.overallHealthScore >= 0.5 ? 'text-amber-400' : 'text-red-400'}`}>
                        {Math.round(flow.overallHealthScore * 100)}%
                      </span>
                    </div>
                  </div>
                  <div className="p-4 flex items-start gap-2 overflow-x-auto">
                    {flow.steps.map((step, idx) => (
                      <React.Fragment key={step.topicName}>
                        <div className="flex-shrink-0 bg-background-dark/50 border border-primary/20 rounded-lg p-3 min-w-[140px]">
                          <p className="font-mono text-xs font-bold text-slate-200 truncate">{step.topicName}</p>
                          <p className="text-[10px] text-slate-500 mt-1">{formatNum(step.count)} msgs</p>
                          {step.averageLatencyMs !== null && (
                            <p className="text-[10px] text-slate-500">{step.averageLatencyMs}ms avg</p>
                          )}
                          {idx > 0 && (
                            <div className={`mt-2 text-[10px] font-bold ${step.throughputPercentage >= 90 ? 'text-emerald-400' : step.throughputPercentage >= 70 ? 'text-amber-400' : 'text-red-400'}`}>
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
