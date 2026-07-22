import React, { useState, useEffect, useRef, useCallback, useMemo } from 'react';
import axios from 'axios';
import TopicSelectorPanel, { SnapshotConfig } from '../components/processmining/TopicSelectorPanel';
import SchemaValidationPanel, {
  FieldProfileResult,
} from '../components/processmining/SchemaValidationPanel';
import MermaidRenderer from '../components/processmining/MermaidRenderer';
import AnomalyTable, { AnomalyReport } from '../components/processmining/AnomalyTable';
import LiveStatusBar from '../components/processmining/LiveStatusBar';
import AnomalyFeed, { LiveAnomaly } from '../components/processmining/AnomalyFeed';
import { PageHeader, Button } from '../components/ui';

// ---- Types ----

interface RagSource {
  text: string;
  sourceFile: string | null;
  score: number | null;
}

interface ProcessMiningResult {
  flowchart: string | null;
  comments: string | null;
  hypotheses: string[];
  blindSpots: string[];
  anomalies: AnomalyReport[];
  ragSources?: RagSource[];
}

interface RuntimeLlmInfo {
  llmProvider?: string;
  llmProviderLabel?: string;
  llmModel?: string;
  llmBaseUrl?: string;
  llmLocalDeployment?: boolean;
}

interface AuditTemplate {
  id: string;
  name: string;
  category: string;
  description: string;
  prompt: string;
  requiredRoles?: string[];
}

// Semantic field roles detected by profiling — used to grey out audits that
// cannot run (e.g. amount outliers when no AMOUNT field was found).
const ROLE_LABELS: Record<string, string> = {
  CORRELATION_ID: 'correlation id',
  TIMESTAMP: 'timestamp',
  STATUS: 'status',
  AMOUNT: 'amount',
};

type Step = 'SELECT' | 'PROFILING' | 'VALIDATE' | 'ANALYZE' | 'RESULTS';
type AnalysisMode = 'SNAPSHOT' | 'LIVE';

// ---- Step indicator ----

const STEPS: { key: Step; label: string; icon: string }[] = [
  { key: 'SELECT', label: 'Select Topics', icon: 'topic' },
  { key: 'PROFILING', label: 'Profiling', icon: 'search' },
  { key: 'VALIDATE', label: 'Validate Schema', icon: 'verified' },
  { key: 'ANALYZE', label: 'Choose Mode', icon: 'settings' },
  { key: 'RESULTS', label: 'Results', icon: 'analytics' },
];

const stepIndex = (s: Step) => STEPS.findIndex(x => x.key === s);

// Prefer the backend's error payload over axios' generic
// "Request failed with status code 500" message.
const errorMessage = (err: unknown, fallback: string): string => {
  if (axios.isAxiosError(err)) {
    const data: unknown = err.response?.data;
    if (typeof data === 'string' && data) return data;
    if (data && typeof data === 'object') {
      const d = data as Record<string, unknown>;
      if (typeof d.message === 'string' && d.message) return d.message;
      if (typeof d.error === 'string' && d.error) return d.error;
    }
    return err.message;
  }
  return err instanceof Error ? err.message : fallback;
};

const StepIndicator: React.FC<{ current: Step }> = ({ current }) => (
  <div className="flex items-center gap-0 mb-8">
    {STEPS.map((step, i) => {
      const ci = stepIndex(current);
      const isPast = i < ci;
      const isActive = i === ci;
      return (
        <React.Fragment key={step.key}>
          <div className="flex items-center gap-2">
            <div className={`flex items-center justify-center w-8 h-8 rounded-full border-2 transition-colors ${
              isActive
                ? 'border-primary bg-primary text-on-primary'
                : isPast
                ? 'border-primary/60 bg-primary/20 text-primary'
                : 'border-outline-variant bg-transparent text-outline'
            }`}>
              {isPast ? (
                <span className="material-symbols-outlined text-sm">check</span>
              ) : (
                <span className="material-symbols-outlined text-sm">{step.icon}</span>
              )}
            </div>
            <span className={`text-xs font-medium hidden sm:block ${
              isActive ? 'text-primary' : isPast ? 'text-on-surface-variant' : 'text-outline'
            }`}>
              {step.label}
            </span>
          </div>
          {i < STEPS.length - 1 && (
            <div className={`flex-1 h-0.5 mx-2 ${i < ci ? 'bg-primary/60' : 'bg-surface-container-high'}`} />
          )}
        </React.Fragment>
      );
    })}
  </div>
);

// ---- Main component ----

const ProcessMining: React.FC = () => {
  const [step, setStep] = useState<Step>('SELECT');
  const [selectedTopics, setSelectedTopics] = useState<string[]>([]);
  const [depth, setDepth] = useState<SnapshotConfig>({ mode: 'LATEST_N', maxMessages: 500 });
  const [profileResult, setProfileResult] = useState<FieldProfileResult | null>(null);
  const [fieldMappingId, setFieldMappingId] = useState<string | null>(null);
  const [analysisMode, setAnalysisMode] = useState<AnalysisMode>('SNAPSHOT');
  const [snapshotResult, setSnapshotResult] = useState<ProcessMiningResult | null>(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [llmInfo, setLlmInfo] = useState<RuntimeLlmInfo | null>(null);
  const [auditTemplates, setAuditTemplates] = useState<AuditTemplate[]>([]);
  const [selectedAuditIds, setSelectedAuditIds] = useState<string[]>([]);
  const [customAuditPrompt, setCustomAuditPrompt] = useState('');

  // Live mode state
  const [liveConnected, setLiveConnected] = useState(false);
  const [liveFlowchart, setLiveFlowchart] = useState<string | null>(null);
  const [liveAnomalies, setLiveAnomalies] = useState<LiveAnomaly[]>([]);
  const [liveWindowSize, setLiveWindowSize] = useState(0);
  const [liveLastUpdate, setLiveLastUpdate] = useState<number | null>(null);
  const [liveComments, setLiveComments] = useState<string | null>(null);
  const [liveSources, setLiveSources] = useState<RagSource[]>([]);
  const eventSourceRef = useRef<EventSource | null>(null);

  // Cleanup on unmount
  useEffect(() => {
    return () => {
      if (eventSourceRef.current) {
        eventSourceRef.current.close();
      }
    };
  }, []);

  useEffect(() => {
    const fetchRuntimeConfig = async () => {
      try {
        const res = await axios.get<RuntimeLlmInfo>('/api/config');
        setLlmInfo(res.data);
      } catch {
        setLlmInfo(null);
      }
    };
    fetchRuntimeConfig();
  }, []);

  useEffect(() => {
    const fetchAuditTemplates = async () => {
      try {
        const res = await axios.get<AuditTemplate[]>('/api/process-mining/audit-templates');
        setAuditTemplates(res.data);
      } catch {
        setAuditTemplates([]);
      }
    };
    fetchAuditTemplates();
  }, []);

  const toggleAudit = (id: string) => {
    setSelectedAuditIds(prev =>
      prev.includes(id) ? prev.filter(x => x !== id) : [...prev, id]);
  };

  // Semantic roles the profiling step actually detected, from the unification proposal
  // and the per-field semantic roles. Audits requiring an absent role are greyed out.
  const availableRoles = useMemo(() => {
    const roles = new Set<string>();
    const p = profileResult?.unificationProposal;
    const hasMappings = (e: { mappings?: Record<string, string> } | null | undefined) =>
      !!e && !!e.mappings && Object.keys(e.mappings).length > 0;
    if (hasMappings(p?.correlationId)) roles.add('CORRELATION_ID');
    if (hasMappings(p?.timestamp)) roles.add('TIMESTAMP');
    if (hasMappings(p?.status)) roles.add('STATUS');
    if (hasMappings(p?.amount)) roles.add('AMOUNT');
    profileResult?.topics?.forEach(t =>
      t.fields?.forEach(f => {
        if (f.semanticRole) roles.add(f.semanticRole.toUpperCase());
      }));
    return roles;
  }, [profileResult]);

  const missingRolesFor = (t: AuditTemplate): string[] =>
    (t.requiredRoles ?? []).filter(r => !availableRoles.has(r));

  // ---- Handlers ----

  const handleStartProfiling = async (topics: string[], snapshotDepth: SnapshotConfig) => {
    setSelectedTopics(topics);
    setDepth(snapshotDepth);
    setStep('PROFILING');
    setLoading(true);
    setError(null);

    try {
      const res = await axios.post<FieldProfileResult>('/api/process-mining/profiling/start', {
        topics,
        depth: snapshotDepth,
      });
      setProfileResult(res.data);
      setStep('VALIDATE');
    } catch (err: unknown) {
      setError(errorMessage(err, 'Profiling failed'));
      setStep('SELECT');
    } finally {
      setLoading(false);
    }
  };

  const handleValidateSchema = async (corrections: Record<string, Record<string, string>>) => {
    if (!profileResult) return;
    setLoading(true);
    setError(null);

    try {
      const res = await axios.post<{ fieldMappingId: string }>('/api/process-mining/profiling/validate', {
        proposal: profileResult.unificationProposal,
        userCorrections: corrections,
      });
      setFieldMappingId(res.data.fieldMappingId);
      setStep('ANALYZE');
    } catch (err: unknown) {
      setError(errorMessage(err, 'Validation failed'));
    } finally {
      setLoading(false);
    }
  };

  const handleLaunchAnalysis = async () => {
    if (!fieldMappingId) return;

    if (analysisMode === 'SNAPSHOT') {
      setLoading(true);
      setError(null);
      try {
        const res = await axios.post<ProcessMiningResult>('/api/process-mining/snapshot', {
          topics: selectedTopics,
          depth,
          fieldMappingId,
          auditPromptIds: selectedAuditIds,
          customAuditPrompt: customAuditPrompt.trim() || null,
        });
        setSnapshotResult(res.data);
        setStep('RESULTS');
      } catch (err: unknown) {
        setError(errorMessage(err, 'Analysis failed'));
      } finally {
        setLoading(false);
      }
    } else {
      // Live mode
      startLiveSession();
      setStep('RESULTS');
    }
  };

  const startLiveSession = useCallback(() => {
    if (eventSourceRef.current) {
      eventSourceRef.current.close();
    }

    const topicsParam = selectedTopics.map(t => `topics=${encodeURIComponent(t)}`).join('&');
    const auditParam = selectedAuditIds.map(id => `&auditPromptIds=${encodeURIComponent(id)}`).join('');
    const customParam = customAuditPrompt.trim()
      ? `&customAuditPrompt=${encodeURIComponent(customAuditPrompt.trim())}`
      : '';
    const url = `/api/process-mining/live?${topicsParam}&fieldMappingId=${fieldMappingId}${auditParam}${customParam}`;

    const es = new EventSource(url);
    eventSourceRef.current = es;

    es.onopen = () => {
      setLiveConnected(true);
    };

    es.addEventListener('CONNECTED', () => {
      setLiveConnected(true);
    });

    es.addEventListener('HEARTBEAT', () => {
      // Keep alive — no action needed
    });

    es.addEventListener('FLOWCHART_UPDATE', (e) => {
      try {
        const chart = JSON.parse(e.data);
        setLiveFlowchart(typeof chart === 'string' ? chart : JSON.stringify(chart));
      } catch {
        setLiveFlowchart(e.data);
      }
    });

    es.addEventListener('ANALYSIS_COMMENTS', (e) => {
      try {
        const comments = JSON.parse(e.data);
        setLiveComments(typeof comments === 'string' ? comments : JSON.stringify(comments));
      } catch {
        setLiveComments(e.data);
      }
    });

    es.addEventListener('ANOMALY_DETECTED', (e) => {
      try {
        const anomaly: AnomalyReport = JSON.parse(e.data);
        setLiveAnomalies(prev => {
          const existing = prev.find(a => a.id === anomaly.id);
          if (existing) {
            return prev.map(a => a.id === anomaly.id
              ? { ...anomaly, status: 'RECURRENT' as const, detectedAt: Date.now() }
              : a
            );
          }
          return [{ ...anomaly, status: 'NEW' as const, detectedAt: Date.now() }, ...prev].slice(0, 100);
        });
      } catch {
        // ignore parse errors
      }
    });

    es.addEventListener('RAG_SOURCES', (e) => {
      try {
        const sources: RagSource[] = JSON.parse(e.data);
        if (Array.isArray(sources)) setLiveSources(sources);
      } catch {
        // ignore
      }
    });

    es.addEventListener('WINDOW_STATS', (e) => {
      try {
        const stats = JSON.parse(e.data);
        setLiveWindowSize(stats.windowSize ?? 0);
        setLiveLastUpdate(Date.now());
      } catch {
        // ignore
      }
    });

    es.onerror = () => {
      setLiveConnected(false);
    };
  }, [selectedTopics, fieldMappingId, selectedAuditIds, customAuditPrompt]);

  const stopLiveSession = () => {
    if (eventSourceRef.current) {
      eventSourceRef.current.close();
      eventSourceRef.current = null;
    }
    setLiveConnected(false);
  };

  const resetAll = () => {
    stopLiveSession();
    setStep('SELECT');
    setSelectedTopics([]);
    setProfileResult(null);
    setFieldMappingId(null);
    setSnapshotResult(null);
    setLiveFlowchart(null);
    setLiveAnomalies([]);
    setLiveWindowSize(0);
    setLiveLastUpdate(null);
    setLiveComments(null);
    setLiveSources([]);
    setSelectedAuditIds([]);
    setCustomAuditPrompt('');
    setError(null);
  };

  // ---- Render ----

  return (
    <div className="p-4 md:p-6 max-w-5xl mx-auto space-y-6">
      <PageHeader
        title="Process Mining"
        description="Discover business process flows and detect anomalies across Kafka topics using AI."
        actions={step !== 'SELECT' && (
          <Button variant="outline" size="sm" icon="restart_alt" onClick={resetAll}>Start over</Button>
        )}
      />

      {/* Step indicator */}
      <StepIndicator current={step} />

      {llmInfo && (
        <div className={`rounded-xl border p-4 ${
          llmInfo.llmLocalDeployment
            ? 'border-success/20 bg-success/5'
            : 'border-outline-variant/60 bg-surface-container'
        }`}>
          <div className="flex items-start gap-3">
            <span className={`material-symbols-outlined text-lg ${
              llmInfo.llmLocalDeployment ? 'text-success' : 'text-primary'
            }`}>
              smart_toy
            </span>
            <div>
              <p className="text-sm font-semibold text-on-surface">
                LLM Runtime: {llmInfo.llmProviderLabel ?? llmInfo.llmProvider ?? 'Unknown'}
                {llmInfo.llmModel ? ` · ${llmInfo.llmModel}` : ''}
              </p>
              <p className="text-xs text-on-surface-variant mt-1">
                {llmInfo.llmLocalDeployment
                  ? 'Local lightweight open-source inference is active for process mining.'
                  : 'This page also supports lightweight open-source models through Ollama or any OpenAI-compatible endpoint.'}
              </p>
              {llmInfo.llmBaseUrl && (
                <p className="text-[11px] font-mono text-on-surface-variant mt-2">{llmInfo.llmBaseUrl}</p>
              )}
            </div>
          </div>
        </div>
      )}

      {/* Error banner */}
      {error && (
        <div className="bg-error/10 border border-error/30 rounded-xl p-4 flex items-start gap-3">
          <span className="material-symbols-outlined text-error text-lg flex-shrink-0">error</span>
          <div>
            <p className="text-sm font-semibold text-error">Error</p>
            <p className="text-xs text-error mt-0.5">{error}</p>
          </div>
          <button onClick={() => setError(null)} className="ml-auto text-error hover:text-error">
            <span className="material-symbols-outlined text-base">close</span>
          </button>
        </div>
      )}

      {/* Step content */}
      <div className="bg-white/3 dark:bg-surface-container/30 border border-outline-variant/60 rounded-2xl p-6">

        {/* STEP 1: SELECT */}
        {step === 'SELECT' && (
          <TopicSelectorPanel onStart={handleStartProfiling} loading={loading} />
        )}

        {/* STEP 2: PROFILING IN PROGRESS */}
        {step === 'PROFILING' && (
          <div className="flex flex-col items-center justify-center py-16 gap-4">
            <div className="relative w-16 h-16">
              <span className="material-symbols-outlined text-6xl text-primary animate-pulse">
                psychology
              </span>
            </div>
            <p className="text-lg font-semibold text-on-surface">
              Profiling topics with {llmInfo?.llmProviderLabel ?? 'the configured LLM'}...
            </p>
            <p className="text-sm text-on-surface-variant text-center max-w-md">
              Sampling messages from {selectedTopics.length} topic{selectedTopics.length > 1 ? 's' : ''},
              detecting field semantics and proposing schema unification.
            </p>
            <div className="flex flex-wrap gap-2 mt-2">
              {selectedTopics.map(t => (
                <span key={t} className="text-xs font-mono px-2.5 py-1 bg-primary/10 text-primary/80 rounded-lg">
                  {t}
                </span>
              ))}
            </div>
          </div>
        )}

        {/* STEP 3: VALIDATE */}
        {step === 'VALIDATE' && profileResult && (
          <SchemaValidationPanel
            result={profileResult}
            onValidate={handleValidateSchema}
            loading={loading}
          />
        )}

        {/* STEP 4: CHOOSE ANALYSIS MODE */}
        {step === 'ANALYZE' && (
          <div className="space-y-6">
            <div>
              <h2 className="text-lg font-semibold text-on-surface mb-1">Choose Analysis Mode</h2>
              <p className="text-sm text-on-surface-variant">
                Select how you want to analyse the Kafka message flows.
              </p>
            </div>

            <div className="grid grid-cols-2 gap-4">
              <button
                onClick={() => setAnalysisMode('SNAPSHOT')}
                className={`p-5 rounded-xl border-2 text-left transition-all ${
                  analysisMode === 'SNAPSHOT'
                    ? 'border-primary bg-primary/10'
                    : 'border-outline-variant hover:border-outline hover:bg-surface-container-high/40'
                }`}
              >
                <span className="material-symbols-outlined text-3xl text-primary mb-3 block">
                  camera
                </span>
                <p className="font-semibold text-on-surface">Snapshot Analysis</p>
                <p className="text-xs text-on-surface-variant mt-1">
                  Analyse a fixed sample of historical messages. Produces a complete flowchart
                  and anomaly report in one shot.
                </p>
              </button>

              <button
                onClick={() => setAnalysisMode('LIVE')}
                className={`p-5 rounded-xl border-2 text-left transition-all ${
                  analysisMode === 'LIVE'
                    ? 'border-primary bg-primary/10'
                    : 'border-outline-variant hover:border-outline hover:bg-surface-container-high/40'
                }`}
              >
                <span className="material-symbols-outlined text-3xl text-success mb-3 block">
                  stream
                </span>
                <p className="font-semibold text-on-surface">Live Monitoring</p>
                <p className="text-xs text-on-surface-variant mt-1">
                  Continuously consume new messages and re-analyse each window.
                  Detects evolving anomalies in real time.
                </p>
              </button>
            </div>

            {/* Audit checklist */}
            {auditTemplates.length > 0 && (
              <div className="border border-outline-variant rounded-xl p-4 space-y-3">
                <div className="flex items-center justify-between">
                  <div>
                    <h3 className="text-sm font-semibold text-on-surface flex items-center gap-2">
                      <span className="material-symbols-outlined text-base text-primary">fact_check</span>
                      Audit checklist
                      <span className="text-xs font-normal text-on-surface-variant">(optional)</span>
                    </h3>
                    <p className="text-xs text-on-surface-variant mt-0.5">
                      Pick the checks to focus the LLM on. None selected = general analysis.
                    </p>
                  </div>
                  {selectedAuditIds.length > 0 && (
                    <button
                      onClick={() => setSelectedAuditIds([])}
                      className="text-xs text-on-surface-variant hover:text-on-surface"
                    >
                      Clear ({selectedAuditIds.length})
                    </button>
                  )}
                </div>

                <div className="grid grid-cols-1 sm:grid-cols-2 gap-2">
                  {auditTemplates.map(t => {
                    const active = selectedAuditIds.includes(t.id);
                    const missing = missingRolesFor(t);
                    const disabled = missing.length > 0;
                    return (
                      <button
                        key={t.id}
                        onClick={() => !disabled && toggleAudit(t.id)}
                        disabled={disabled}
                        title={disabled
                          ? `Not applicable — needs ${missing.map(r => ROLE_LABELS[r] ?? r).join(', ')}`
                          : undefined}
                        className={`text-left p-3 rounded-lg border transition-colors ${
                          disabled
                            ? 'border-outline-variant bg-surface-container/20 opacity-50 cursor-not-allowed'
                            : active
                            ? 'border-primary bg-primary/10'
                            : 'border-outline-variant hover:border-outline hover:bg-surface-container-high/40'
                        }`}
                      >
                        <div className="flex items-start gap-2">
                          <span className={`material-symbols-outlined text-base mt-0.5 ${
                            disabled ? 'text-outline' : active ? 'text-primary' : 'text-outline'
                          }`}>
                            {disabled ? 'block' : active ? 'check_box' : 'check_box_outline_blank'}
                          </span>
                          <div className="min-w-0">
                            <p className="text-xs font-semibold text-on-surface truncate">{t.name}</p>
                            <p className="text-[11px] text-on-surface-variant leading-snug mt-0.5">{t.description}</p>
                            <span className="inline-block mt-1.5 text-[9px] uppercase tracking-wider font-bold text-on-surface-variant">
                              {t.category.replace('_', ' ')}
                            </span>
                            {disabled && (
                              <span className="block mt-1 text-[10px] text-warning/80">
                                needs {missing.map(r => ROLE_LABELS[r] ?? r).join(', ')}
                              </span>
                            )}
                          </div>
                        </div>
                      </button>
                    );
                  })}
                </div>

                <div>
                  <label className="block text-[10px] uppercase font-bold tracking-wider text-on-surface-variant mb-1.5">
                    Custom audit instruction
                  </label>
                  <textarea
                    value={customAuditPrompt}
                    onChange={e => setCustomAuditPrompt(e.target.value)}
                    rows={2}
                    placeholder="e.g. Flag any order whose amount changes between the received and validated topics."
                    className="w-full bg-surface-container-low border border-outline-variant rounded-md px-3 py-2 text-sm text-on-surface placeholder:text-outline focus:border-primary/60 outline-none resize-y"
                  />
                </div>
              </div>
            )}

            <Button
              variant="primary" size="lg" className="w-full"
              loading={loading}
              icon={loading ? undefined : (analysisMode === 'LIVE' ? 'stream' : 'analytics')}
              onClick={handleLaunchAnalysis}
              disabled={loading}
            >
              {loading ? 'Analysing…' : analysisMode === 'LIVE' ? 'Start Live Monitoring' : 'Run Snapshot Analysis'}
            </Button>
          </div>
        )}

        {/* STEP 5: RESULTS */}
        {step === 'RESULTS' && (
          <div className="space-y-6">
            {/* Live status bar (only in live mode) */}
            {analysisMode === 'LIVE' && (
              <div className="flex items-center gap-3">
                <div className="flex-1">
                  <LiveStatusBar
                    connected={liveConnected}
                    windowSize={liveWindowSize}
                    lastUpdate={liveLastUpdate}
                  />
                </div>
                <button
                  onClick={stopLiveSession}
                  disabled={!liveConnected}
                  className="flex items-center gap-1.5 px-3 py-2 text-xs text-error border border-error/30 hover:bg-error/10 rounded-lg transition-colors disabled:opacity-40"
                >
                  <span className="material-symbols-outlined text-sm">stop_circle</span>
                  Stop
                </button>
              </div>
            )}

            {/* Flowchart */}
            <div className="border border-outline-variant rounded-xl overflow-hidden">
              <div className="px-4 py-2.5 border-b border-outline-variant/60 bg-surface-container-high/60 flex items-center justify-between">
                <h3 className="text-sm font-semibold text-on-surface">Process Flowchart</h3>
                <span className="text-xs text-on-surface-variant">Mermaid diagram</span>
              </div>
              <div className="p-4">
                <MermaidRenderer
                  chart={
                    analysisMode === 'LIVE'
                      ? liveFlowchart ?? ''
                      : snapshotResult?.flowchart ?? ''
                  }
                />
              </div>
            </div>

            {/* Comments / Analysis narrative */}
            {(analysisMode === 'LIVE' ? liveComments : snapshotResult?.comments) && (
              <div className="border border-outline-variant rounded-xl p-4 bg-primary/5">
                <h3 className="text-xs font-bold text-on-surface-variant uppercase tracking-wider mb-2">
                  Analysis Commentary
                </h3>
                <p className="text-sm text-on-surface leading-relaxed">
                  {analysisMode === 'LIVE' ? liveComments : snapshotResult?.comments}
                </p>
              </div>
            )}

            {/* RAG evidence / cited sources (SpectraLLM with use-rag) */}
            {(() => {
              const sources = analysisMode === 'LIVE' ? liveSources : (snapshotResult?.ragSources ?? []);
              if (!sources || sources.length === 0) return null;
              return (
                <div className="border border-outline-variant rounded-xl overflow-hidden">
                  <div className="px-4 py-2.5 border-b border-outline-variant/60 bg-surface-container-high/60 flex items-center gap-2">
                    <span className="material-symbols-outlined text-base text-primary">menu_book</span>
                    <h3 className="text-sm font-semibold text-on-surface">Evidence — cited sources</h3>
                    <span className="text-xs px-2 py-0.5 rounded-full bg-primary/10 text-primary">
                      {sources.length}
                    </span>
                    <span className="ml-auto text-[11px] text-on-surface-variant">SpectraLLM RAG</span>
                  </div>
                  <div className="p-4 space-y-2">
                    {sources.map((s, i) => (
                      <div key={i} className="rounded-lg border border-outline-variant/60 bg-surface-container-low p-3">
                        <div className="flex items-center justify-between gap-2 mb-1">
                          <span className="text-xs font-mono text-primary/80 truncate">
                            {s.sourceFile ?? 'unknown source'}
                          </span>
                          {s.score != null && (
                            <span className="text-[10px] text-on-surface-variant flex-shrink-0">
                              score {s.score.toFixed(3)}
                            </span>
                          )}
                        </div>
                        <p className="text-xs text-on-surface leading-relaxed whitespace-pre-wrap">{s.text}</p>
                      </div>
                    ))}
                  </div>
                </div>
              );
            })()}

            {/* Snapshot extra sections */}
            {analysisMode === 'SNAPSHOT' && snapshotResult && (
              <>
                {snapshotResult.hypotheses && snapshotResult.hypotheses.length > 0 && (
                  <div className="border border-outline-variant rounded-xl p-4">
                    <h3 className="text-xs font-bold text-on-surface-variant uppercase tracking-wider mb-3">
                      Hypotheses
                    </h3>
                    <ul className="space-y-1.5">
                      {snapshotResult.hypotheses.map((h, i) => (
                        <li key={i} className="flex items-start gap-2 text-sm text-on-surface">
                          <span className="material-symbols-outlined text-primary text-sm mt-0.5 flex-shrink-0">
                            lightbulb
                          </span>
                          {h}
                        </li>
                      ))}
                    </ul>
                  </div>
                )}

                {snapshotResult.blindSpots && snapshotResult.blindSpots.length > 0 && (
                  <div className="border border-warning/20 rounded-xl p-4 bg-warning/5">
                    <h3 className="text-xs font-bold text-warning uppercase tracking-wider mb-3">
                      Blind Spots
                    </h3>
                    <ul className="space-y-1.5">
                      {snapshotResult.blindSpots.map((bs, i) => (
                        <li key={i} className="flex items-start gap-2 text-sm text-on-surface">
                          <span className="material-symbols-outlined text-warning text-sm mt-0.5 flex-shrink-0">
                            visibility_off
                          </span>
                          {bs}
                        </li>
                      ))}
                    </ul>
                  </div>
                )}
              </>
            )}

            {/* Anomalies */}
            <div className="border border-outline-variant rounded-xl overflow-hidden">
              <div className="px-4 py-2.5 border-b border-outline-variant/60 bg-surface-container-high/60 flex items-center gap-2">
                <h3 className="text-sm font-semibold text-on-surface">Anomalies</h3>
                {analysisMode === 'SNAPSHOT' && snapshotResult?.anomalies && (
                  <span className="text-xs px-2 py-0.5 rounded-full bg-primary/10 text-primary">
                    {snapshotResult.anomalies.length}
                  </span>
                )}
                {analysisMode === 'LIVE' && (
                  <span className="text-xs px-2 py-0.5 rounded-full bg-primary/10 text-primary">
                    {liveAnomalies.length}
                  </span>
                )}
              </div>
              <div className="p-4">
                {analysisMode === 'LIVE' ? (
                  <AnomalyFeed anomalies={liveAnomalies} />
                ) : (
                  <AnomalyTable anomalies={snapshotResult?.anomalies ?? []} />
                )}
              </div>
            </div>
          </div>
        )}
      </div>
    </div>
  );
};

export default ProcessMining;
