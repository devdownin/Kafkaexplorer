// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors

import React, { useState, useEffect, useRef, useCallback, useMemo } from 'react';
import axios from 'axios';
import TopicSelectorPanel, { SnapshotConfig } from '../components/processmining/TopicSelectorPanel';
import SchemaValidationPanel, {
  FieldProfileResult,
} from '../components/processmining/SchemaValidationPanel';
import MermaidRenderer from '../components/processmining/MermaidRenderer';
import AnomalyTable from '../components/processmining/AnomalyTable';
import LiveStatusBar, { LiveWindowStats } from '../components/processmining/LiveStatusBar';
import AnomalyFeed, { LiveAnomaly } from '../components/processmining/AnomalyFeed';
import { PageHeader, Button, Field, Textarea } from '../components/ui';
import { clearDraft, readDraft, useDraftConflict, usePersistentState, writeDraft } from '../draftStore';
import { recordMeasuredProcess, recordMetricPriorities } from './processModelEvidence';
import { describeResume, resumableStep } from './processMiningDraft';
import { describeUsage, formatCostUsd, totalCostUsd, totalTokens } from './llmUsage';
import { describeDataPolicy } from './llmPolicy';
import { describeTestTimeout, testTimeoutMs } from './llmTimeout';
import { describeCoverage } from './processMiningCoverage';
import ProcessModelPanel from '../components/processmining/ProcessModelPanel';
import type { AnalysisMode, Step } from './processMiningDraft';
import type {
  AnomalyReport,
  FieldMappingValidation,
  LlmUsage,
  ProcessMiningCoverage,
  ProcessMiningResult,
  ProcessModel,
  RagSource,
} from '../api/types';

// ---- Types ----

interface RuntimeLlmInfo {
  llmProvider?: string;
  llmProviderLabel?: string;
  llmModel?: string;
  llmBaseUrl?: string;
  llmLocalDeployment?: boolean;
  /** Le budget du serveur pour un appel au modèle : ce dont la sonde déduit son attente. */
  llmRequestTimeoutSeconds?: number;
  /**
   * Pourquoi un appel échouerait avant même d'être fait — clé manquante, adresse manquante, adresse
   * qui n'en est pas une — ou absent quand rien ne peut être dit sans interroger le point d'accès.
   * Calculé par le serveur (`ClaudeConfig.configurationProblem`), pas déduit ici : c'est lui qui
   * sait quel fournisseur exige quoi.
   */
  llmConfigurationProblem?: string | null;
  /** Vrai seulement là où le routage a pu imposer la non-rétention — voir `llmPolicy.ts`. */
  llmDataRetentionRefused?: boolean;
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

/**
 * Clés des brouillons. Seuls les acquis du pipeline sont conservés ; l'état d'une session live
 * (flux SSE, fenêtre glissante, anomalies reçues) n'est jamais écrit — un flux fermé ne se reprend
 * pas, et le restaurer donnerait à un instantané mort l'apparence d'un direct.
 */
const DRAFT = {
  step: 'pm:step',
  topics: 'pm:topics',
  depth: 'pm:depth',
  profile: 'pm:profile',
  mapping: 'pm:mapping',
  mode: 'pm:mode',
  snapshot: 'pm:snapshot',
  audits: 'pm:audits',
  prompt: 'pm:prompt',
} as const;

const DRAFT_KEYS = Object.values(DRAFT);

// ---- Step indicator ----

const STEPS: { key: Step; label: string; icon: string }[] = [
  { key: 'SELECT', label: 'Select Topics', icon: 'topic' },
  { key: 'PROFILING', label: 'Profiling', icon: 'search' },
  { key: 'VALIDATE', label: 'Validate Schema', icon: 'verified' },
  { key: 'ANALYZE', label: 'Choose Mode', icon: 'settings' },
  { key: 'RESULTS', label: 'Results', icon: 'analytics' },
];

const stepIndex = (s: Step) => STEPS.findIndex(x => x.key === s);

/*
 * axios has no default timeout, so a server that never answers left the spinner turning for ever —
 * the same defect the SQL editor was fixed for. Both calls drive a model, so the ceilings are
 * generous rather than tight: what matters is that they exist and that hitting one says so.
 */
const PROFILING_TIMEOUT_MS = 5 * 60_000;
const SNAPSHOT_TIMEOUT_MS = 10 * 60_000;
// A one-word health check: if this does not answer, nothing else on the page will either. What it
// waits is *derived* from the budget the server publishes rather than fixed here — see
// `llmTimeout.ts`: 90 s in hard code aborted at less than a third of the 300 s both bundled local
// stacks configure, so the button reported a failure about an endpoint that was answering.

// Prefer the backend's error payload over axios' generic
// "Request failed with status code 500" message.
const errorMessage = (err: unknown, fallback: string): string => {
  if (axios.isAxiosError(err)) {
    if (err.code === 'ECONNABORTED' || err.code === 'ETIMEDOUT') {
      return 'The server did not answer in time. The model may still be working — check the '
        + 'backend logs, then retry with fewer topics, a smaller sample, or a faster model.';
    }
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

/**
 * Ce qui fait qu'une anomalie est « la même » d'une fenêtre à l'autre.
 *
 * L'`id` seul ne suffit pas : il est requis par le schéma, mais tous les chemins non contraints
 * existent encore (SpectraLLM, passerelle OpenAI quelconque, `structured-output: OFF`), et un modèle
 * qui l'omet donnait `undefined === undefined` — donc *toutes* les anomalies se confondaient, et le
 * flux live se réduisait à une seule ligne éternellement « RECURRENT ». À défaut d'id, le topic, le
 * type et la description décrivent l'observation aussi bien.
 */
const anomalyKey = (a: AnomalyReport): string =>
  a.id?.trim() || `${a.topic ?? ''}|${a.type ?? ''}|${a.description ?? ''}`;

/**
 * Ce que l'analyse a pu regarder, dit au-dessus de ce qu'elle a produit.
 *
 * Un diagramme ne dit pas de quoi il est tiré : sans ce bandeau, une analyse de huit topics dont
 * deux n'ont jamais atteint le modèle se lit comme une analyse des huit, et le silence du modèle
 * sur les deux passe pour un constat à leur sujet. Rendu même quand tout s'est bien passé — c'est
 * ce qui fait qu'une portée réduite se remarque, plutôt qu'un bandeau qui n'apparaît que pour les
 * mauvaises nouvelles et qu'on finit par ne plus lire.
 */
const CoverageNotice: React.FC<{ coverage: ProcessMiningCoverage | null | undefined }> = ({ coverage }) => {
  const summary = describeCoverage(coverage);
  if (!summary) return null;
  const tone = summary.tone;
  return (
    <div className={`rounded-xl border p-3 flex items-start gap-3 ${
      tone === 'failed'
        ? 'border-error/30 bg-error/5'
        : tone === 'partial'
        ? 'border-warning/30 bg-warning/5'
        : 'border-outline-variant/60 bg-surface-container/40'
    }`}>
      <span aria-hidden="true" className={`material-symbols-outlined text-lg flex-shrink-0 ${
        tone === 'failed' ? 'text-error' : tone === 'partial' ? 'text-warning' : 'text-on-surface-variant'
      }`}>
        {tone === 'complete' ? 'fact_check' : 'rule'}
      </span>
      <div className="min-w-0">
        <p className="text-xs font-semibold text-on-surface">Analysis scope</p>
        <p className="text-xs text-on-surface-variant mt-0.5">{summary.headline}</p>
        {summary.notes.length > 0 && (
          <ul className="mt-1.5 space-y-1">
            {summary.notes.map((note, i) => (
              <li key={i} className="text-[11px] text-on-surface-variant leading-snug flex items-start gap-1.5">
                <span aria-hidden="true" className="material-symbols-outlined text-[13px] mt-0.5 flex-shrink-0">
                  arrow_right
                </span>
                <span className="break-words">{note}</span>
              </li>
            ))}
          </ul>
        )}
      </div>
    </div>
  );
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
  /*
   * L'étape reprise n'est pas forcément celle qui a été quittée : `resumableStep` ramène en
   * arrière celles qui dépendent d'une opération morte avec la page (la requête de profilage,
   * la session SSE) ou d'une donnée absente du brouillon.
   */
  const [restored] = useState(() => {
    const asked = readDraft<Step>(DRAFT.step, 'SELECT');
    const step = resumableStep({
      step: asked,
      analysisMode: readDraft<AnalysisMode>(DRAFT.mode, 'SNAPSHOT'),
      hasProfile: readDraft<FieldProfileResult | null>(DRAFT.profile, null) !== null,
      hasMapping: readDraft<string | null>(DRAFT.mapping, null) !== null,
      hasSnapshot: readDraft<ProcessMiningResult | null>(DRAFT.snapshot, null) !== null,
    });
    // Réécrit tout de suite quand l'étape a dû être ramenée en arrière : `usePersistentState`
    // relit le brouillon, et l'assainissement resterait sans effet si l'ancienne valeur y était
    // encore. Rien à écrire dans le cas courant — une simple visite ne sème pas de brouillon.
    if (step !== asked) writeDraft(DRAFT.step, step);
    return { step, notice: describeResume(step, asked) };
  });

  const [step, setStep] = usePersistentState<Step>(DRAFT.step, restored.step);
  const [selectedTopics, setSelectedTopics] = usePersistentState<string[]>(DRAFT.topics, []);
  const [depth, setDepth] = usePersistentState<SnapshotConfig>(DRAFT.depth, { mode: 'LATEST_N', maxMessages: 500 });
  const [profileResult, setProfileResult] = usePersistentState<FieldProfileResult | null>(DRAFT.profile, null);
  const [fieldMappingId, setFieldMappingId] = usePersistentState<string | null>(DRAFT.mapping, null);
  const [analysisMode, setAnalysisMode] = usePersistentState<AnalysisMode>(DRAFT.mode, 'SNAPSHOT');
  const [snapshotResult, setSnapshotResult] = usePersistentState<ProcessMiningResult | null>(DRAFT.snapshot, null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  /*
   * La portée d'une analyse qui a échoué. Elle voyage sur la réponse comme sur un succès — un run
   * qui a lu quatre cents messages avant de perdre le modèle sait ce qu'il a lu — et c'est là
   * qu'elle sert le plus : elle dit si la prochaine tentative doit changer de modèle ou de
   * sélection. Hors brouillon, à la différence de `snapshotResult` : un échec ne se rouvre pas.
   */
  const [failedCoverage, setFailedCoverage] = useState<ProcessMiningCoverage | null>(null);
  /**
   * Ce qui a été mesuré alors même que l'analyse a échoué.
   *
   * Hors brouillon comme `failedCoverage`, et pour la même raison : un échec ne se rouvre pas. Mais
   * il se lit — c'est le cas « aucun LLM configuré », où la mesure est la totalité de ce que
   * l'exécution a produit, et où la refuser avec le reste retirerait la moitié qui était gratuite.
   */
  const [failedProcessModel, setFailedProcessModel] = useState<ProcessModel | null>(null);
  const [llmInfo, setLlmInfo] = useState<RuntimeLlmInfo | null>(null);
  const [auditTemplates, setAuditTemplates] = useState<AuditTemplate[]>([]);
  const [selectedAuditIds, setSelectedAuditIds] = usePersistentState<string[]>(DRAFT.audits, []);
  const [customAuditPrompt, setCustomAuditPrompt] = usePersistentState(DRAFT.prompt, '');
  const [resumeNotice, setResumeNotice] = useState<string | null>(restored.notice);
  const [llmTest, setLlmTest] = useState<{ ok: boolean; message: string } | null>(null);
  const [llmTesting, setLlmTesting] = useState(false);
  const draftConflict = useDraftConflict(DRAFT_KEYS);

  const testLlmConnection = async () => {
    const budget = llmInfo?.llmRequestTimeoutSeconds;
    const waitMs = testTimeoutMs(budget);
    setLlmTesting(true);
    setLlmTest(null);
    try {
      const res = await axios.post<{ ok: boolean; message: string }>(
        '/api/config/test-llm', {}, { timeout: waitMs });
      setLlmTest({ ok: !!res.data.ok, message: res.data.message ?? '' });
    } catch (err: unknown) {
      // Ce que le navigateur a abandonné ne dit rien du point d'accès, qui peut être en train de
      // répondre — et le message générique conseille de réduire le nombre de topics, sur un appel
      // qui n'en porte aucun.
      const gaveUp = axios.isAxiosError(err)
        && (err.code === 'ECONNABORTED' || err.code === 'ETIMEDOUT');
      setLlmTest({
        ok: false,
        message: gaveUp
          ? describeTestTimeout(waitMs, budget)
          : errorMessage(err, 'The LLM could not be reached.'),
      });
    } finally {
      setLlmTesting(false);
    }
  };

  // Live mode state
  const [liveConnected, setLiveConnected] = useState(false);
  const [liveFlowchart, setLiveFlowchart] = useState<string | null>(null);
  const [liveAnomalies, setLiveAnomalies] = useState<LiveAnomaly[]>([]);
  const [liveWindowSize, setLiveWindowSize] = useState(0);
  const [liveStats, setLiveStats] = useState<LiveWindowStats | null>(null);
  const [liveLastUpdate, setLiveLastUpdate] = useState<number | null>(null);
  const [liveComments, setLiveComments] = useState<string | null>(null);
  const [liveSources, setLiveSources] = useState<RagSource[]>([]);
  // The last window whose analysis failed. Kept beside the results rather than replacing them:
  // earlier windows produced a real flowchart, and erasing it would lose more than it explains.
  const [liveError, setLiveError] = useState<string | null>(null);
  const [liveUsage, setLiveUsage] = useState<LlmUsage | null>(null);
  /*
   * Ce que le budget de session a à dire : il s'est arrêté en atteignant son plafond, ou le
   * plafond ne peut pas s'appliquer. Tenu à part de `liveError` — un budget qui fait son travail
   * n'est pas une panne, et le peindre en rouge d'erreur enverrait chercher un problème.
   */
  const [liveBudgetNotice, setLiveBudgetNotice] = useState<string | null>(null);
  /*
   * Le serveur a-t-il retrouvé le mapping validé à l'étape 3 ? Le magasin est borné et restauré au
   * mieux au démarrage, donc une session peut légitimement commencer sans lui — et cela change ce
   * que « corrélé entre topics » veut dire pour chaque fenêtre qu'elle produira. `null` tant que
   * `CONNECTED` n'a rien dit : un serveur plus ancien n'envoie pas le champ, et supposer « oui »
   * serait affirmer ce qu'on n'a pas demandé.
   */
  const [liveMappingApplied, setLiveMappingApplied] = useState<boolean | null>(null);
  /*
   * Every window's cost, not just the last one. A live session calls the model on a timer, so what
   * decides whether a configuration is affordable is the running total — the last window's 900
   * tokens say nothing about the four hours the tab has been open. `totalTokens` already existed
   * and was unit-tested; nothing had ever asked it a question.
   */
  const [liveUsageHistory, setLiveUsageHistory] = useState<LlmUsage[]>([]);
  const [liveStarted, setLiveStarted] = useState(false);
  const eventSourceRef = useRef<EventSource | null>(null);
  const liveSessionIdRef = useRef<string | null>(null);

  // Cleanup on unmount. Navigating away has to end the session too, not just drop the stream:
  // routes unmount this page, and a live session left running keeps a consumer and a model busy.
  useEffect(() => {
    return () => {
      if (eventSourceRef.current) {
        eventSourceRef.current.close();
        eventSourceRef.current = null;
      }
      const id = liveSessionIdRef.current;
      liveSessionIdRef.current = null;
      if (id) {
        void axios.delete(`/api/process-mining/live/${encodeURIComponent(id)}`).catch(() => {});
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

  const policy = useMemo(() => describeDataPolicy(llmInfo), [llmInfo]);
  /*
   * Tous les appels au modèle de l'exécution en cours, profilage compris. Le pipeline en fait deux
   * et seul le second était compté : le chiffre affiché sous-estimait donc la facture, ce qui est
   * exactement la règle appliquée entre les fenêtres et ignorée entre les deux étapes.
   */
  const runUsages = useMemo(() => {
    const all: LlmUsage[] = [];
    if (profileResult?.usage) all.push(profileResult.usage);
    if (analysisMode === 'LIVE') all.push(...liveUsageHistory);
    else if (snapshotResult?.usage) all.push(snapshotResult.usage);
    return all;
  }, [profileResult, analysisMode, liveUsageHistory, snapshotResult]);
  const runTokens = useMemo(() => totalTokens(runUsages), [runUsages]);
  const runCost = useMemo(() => totalCostUsd(runUsages), [runUsages]);

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
      }, { timeout: PROFILING_TIMEOUT_MS });
      // `error` renseigné : le profilage n'a pas eu lieu. C'est une autre réponse que « il a
      // tourné et n'a rien trouvé », et elle envoie ailleurs — vers l'endpoint, le modèle ou la
      // clé plutôt que vers le cluster. Le serveur ne les distinguait pas avant, et trois pannes de
      // modèle ont été lues comme des lectures Kafka en échec.
      if (res.data.error) {
        setError(res.data.error);
        setStep('SELECT');
        return;
      }
      // Aucun topic profilé sans erreur : le profilage a bien eu lieu et n'a rien trouvé.
      if ((res.data.topics?.length ?? 0) === 0) {
        setError(res.data.warnings?.join(' · ')
          || 'Profiling returned no topics. Check that the selected topics hold messages.');
        setStep('SELECT');
        return;
      }
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
      const res = await axios.post<FieldMappingValidation>('/api/process-mining/profiling/validate', {
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
      setFailedCoverage(null);
      setFailedProcessModel(null);
      try {
        const res = await axios.post<ProcessMiningResult>('/api/process-mining/snapshot', {
          topics: selectedTopics,
          depth,
          fieldMappingId,
          auditPromptIds: selectedAuditIds,
          customAuditPrompt: customAuditPrompt.trim() || null,
        }, { timeout: SNAPSHOT_TIMEOUT_MS });
        /*
         * Gardé avant même de regarder si l'analyse a abouti, et c'est délibéré : le processus est
         * *mesuré* ici, pas raconté par un modèle, donc un modèle perdu ne l'invalide pas. C'est ce
         * que la page Métriques relira pour proposer un KPI de latence fondé sur une distribution
         * plutôt que sur une moyenne déduite des noms de topics.
         */
        const measured = recordMeasuredProcess(res.data.processModel);
        /*
         * Le choix du modèle voyage à côté de la mesure qu'il porte, jamais seul : le bandeau de
         * la page Métriques désigne des cartes construites à partir de cette mesure-là, donc un
         * choix sans elle n'aurait rien à désigner. Écrit après, parce qu'il en dépend.
         */
        recordMetricPriorities(measured, res.data.metricPriorities);
        // A failure answers 200 with `error` set. Staying on this step is the point: the operator
        // is one click from re-running with a different model or a narrower selection, where
        // landing on an empty Results page offers nothing to act on.
        if (res.data.error) {
          setError(res.data.error);
          // Ce que la lecture avait tout de même ramené : c'est ce qui dit si l'échec vient du
          // modèle ou d'une sélection qui ne contenait rien à analyser.
          setFailedCoverage(res.data.coverage ?? null);
          setFailedProcessModel(res.data.processModel ?? null);
          return;
        }
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
    setLiveStarted(true);
    setLiveError(null);
    setLiveBudgetNotice(null);

    es.onopen = () => {
      setLiveConnected(true);
    };

    es.addEventListener('CONNECTED', (e) => {
      setLiveConnected(true);
      // Keep the server-assigned id: it is what lets Stop end the session server-side.
      try {
        const payload = JSON.parse(e.data);
        if (payload && typeof payload.sessionId === 'string') {
          liveSessionIdRef.current = payload.sessionId;
        }
        if (payload && typeof payload.fieldMappingApplied === 'boolean') {
          setLiveMappingApplied(payload.fieldMappingApplied);
        }
      } catch {
        // The stream is usable without it; Stop then falls back to closing the connection only.
      }
    });

    es.addEventListener('HEARTBEAT', () => {
      // Keep alive — no action needed
    });

    es.addEventListener('FLOWCHART_UPDATE', (e) => {
      // A window that analysed successfully clears the previous window's failure — otherwise a
      // one-off timeout would sit on screen contradicting a diagram that has since been refreshed.
      setLiveError(null);
      try {
        const chart = JSON.parse(e.data);
        setLiveFlowchart(typeof chart === 'string' ? chart : JSON.stringify(chart));
      } catch {
        setLiveFlowchart(e.data);
      }
    });

    es.addEventListener('ANALYSIS_COMMENTS', (e) => {
      setLiveError(null);
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
        const key = anomalyKey(anomaly);
        setLiveAnomalies(prev => {
          const existing = prev.some(a => anomalyKey(a) === key);
          if (existing) {
            return prev.map(a => anomalyKey(a) === key
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
        const stats: LiveWindowStats = JSON.parse(e.data);
        setLiveWindowSize(stats.windowSize ?? 0);
        setLiveStats(stats);
        setLiveLastUpdate(Date.now());
      } catch {
        // ignore
      }
    });

    /*
     * The server has always emitted this and nothing listened for it, so a window whose analysis
     * failed left the page looking healthy — connected, ingestion counters ticking — with a
     * flowchart that simply never arrived and no reason given anywhere.
     */
    es.addEventListener('ANALYSIS_ERROR', (e) => {
      let message = 'The analysis of the last window failed.';
      try {
        const payload = JSON.parse(e.data);
        if (typeof payload === 'string') message = payload;
        else if (payload && typeof payload.message === 'string') message = payload.message;
      } catch {
        if (e.data) message = e.data;
      }
      setLiveError(message);
    });

    /*
     * La session s'est arrêtée d'elle-même en atteignant son plafond de dépense, ou le plafond ne
     * peut pas s'appliquer faute de coût rapporté. Événement distinct d'`ANALYSIS_ERROR` : un
     * budget qui fait son travail n'est pas une analyse cassée, et l'afficher en rouge d'erreur
     * ferait chercher une panne là où il n'y en a pas. Sans écouteur ici, le serveur parlerait
     * dans le vide — c'est exactement ce qui était arrivé à `ANALYSIS_ERROR`.
     */
    es.addEventListener('SESSION_BUDGET', (e) => {
      try {
        const payload = JSON.parse(e.data) as { message?: string; stopped?: boolean };
        if (payload?.message) setLiveBudgetNotice(payload.message);
        if (payload?.stopped) {
          es.close();
          if (eventSourceRef.current === es) eventSourceRef.current = null;
          setLiveConnected(false);
        }
      } catch {
        // Une ligne de budget illisible ne justifie pas de casser le flux.
      }
    });

    // What the last window cost. A live session calls the model on every window, so this is
    // where an unaffordable configuration shows up first.
    es.addEventListener('ANALYSIS_USAGE', (e) => {
      try {
        const usage = JSON.parse(e.data) as LlmUsage;
        setLiveUsage(usage);
        // Deliberately uncapped: a cap would make the running total cover the last N windows while
        // presenting itself as the session's, which is the kind of quietly-wrong number this page
        // exists to replace. One small object per window — a window every 30 s for eight hours is
        // under a thousand of them.
        setLiveUsageHistory(prev => [...prev, usage]);
      } catch {
        // A missing cost line is not worth breaking the stream over.
      }
    });

    /*
     * EventSource reconnects on its own, and this endpoint mints a *new* session — a new Kafka
     * consumer, a new group member, a flowchart with no history — on every GET. So the stream
     * ending (the server finishing the session, or the 5-minute emitter timeout) started a fresh
     * session behind the operator's back, and a server that stays down became an unbounded loop of
     * them. Closing here makes the stream end visible and puts resuming under the operator's
     * control, which is also the only way "Live monitoring" can be said to have stopped.
     */
    es.onerror = () => {
      es.close();
      if (eventSourceRef.current === es) {
        eventSourceRef.current = null;
      }
      setLiveConnected(false);
    };
  }, [selectedTopics, fieldMappingId, selectedAuditIds, customAuditPrompt]);

  /**
   * Closing the stream is what the browser can do; telling the server is what actually stops the
   * work. Without the second half a Kafka consumer kept polling — and could still spend one more
   * analysis on the model — until the next heartbeat noticed the emitter was gone.
   */
  const stopLiveSession = () => {
    if (eventSourceRef.current) {
      eventSourceRef.current.close();
      eventSourceRef.current = null;
    }
    setLiveConnected(false);
    const id = liveSessionIdRef.current;
    liveSessionIdRef.current = null;
    if (id) {
      // Best effort: the session also ends on its own once the emitter is gone.
      void axios.delete(`/api/process-mining/live/${encodeURIComponent(id)}`).catch(() => {});
    }
  };

  const resetLiveState = () => {
    setLiveFlowchart(null);
    setLiveAnomalies([]);
    setLiveWindowSize(0);
    setLiveStats(null);
    setLiveLastUpdate(null);
    setLiveComments(null);
    setLiveSources([]);
    setLiveError(null);
    setLiveUsage(null);
    setLiveUsageHistory([]);
    setLiveStarted(false);
    setLiveMappingApplied(null);
  };

  const resetAll = () => {
    stopLiveSession();
    // « Start over » veut dire ce qu'il dit : les brouillons partent avec l'état, sinon le
    // pipeline reviendrait au rechargement suivant.
    DRAFT_KEYS.forEach(clearDraft);
    setResumeNotice(null);
    setStep('SELECT');
    setSelectedTopics([]);
    setDepth({ mode: 'LATEST_N', maxMessages: 500 });
    setAnalysisMode('SNAPSHOT');
    setProfileResult(null);
    setFieldMappingId(null);
    setSnapshotResult(null);
    resetLiveState();
    setSelectedAuditIds([]);
    setCustomAuditPrompt('');
    setError(null);
    setFailedCoverage(null);
    setFailedProcessModel(null);
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

      {/* Deux onglets partagent les mêmes clés et rien ne les arbitre : le dernier qui écrit
          gagne. Synchroniser écraserait une saisie en cours — le dire laisse le choix. */}
      {draftConflict && (
        <div className="bg-warning/5 border border-warning/30 rounded-xl p-3 flex items-start gap-3">
          <span aria-hidden="true" className="material-symbols-outlined text-warning text-lg flex-shrink-0">tab_duplicate</span>
          <p className="text-xs text-on-surface-variant">
            This pipeline is also open in another tab, which has just written its own draft.
            Whichever tab writes last is the one that will be restored — this one keeps what is on
            screen until you reload.
          </p>
        </div>
      )}

      {/* Un pipeline rouvert à mi-parcours doit dire qu'il vient d'un brouillon : sans cela il
          passe pour l'état courant, alors qu'il date de la visite précédente. */}
      {resumeNotice && (
        <div className="bg-primary/5 border border-primary/20 rounded-xl p-3 flex items-start gap-3">
          <span aria-hidden="true" className="material-symbols-outlined text-primary text-lg flex-shrink-0">history</span>
          <p className="text-xs text-on-surface-variant">{resumeNotice}</p>
          <button
            type="button"
            aria-label="Dismiss"
            onClick={() => setResumeNotice(null)}
            className="ml-auto text-on-surface-variant hover:text-on-surface"
          >
            <span aria-hidden="true" className="material-symbols-outlined text-base">close</span>
          </button>
        </div>
      )}

      {/*
        Every step of this page costs a model call, so what the banner owes the operator is the
        configuration it will actually use and a way to check it answers — not an adjective. It
        used to read "Local lightweight open-source inference is active", concluded from the base
        URL containing "localhost": a claim about a running model, asserted without ever asking
        one, on the page whose failures are hardest to attribute. Reachability is now checked on
        demand, against the same client the analyses use.
      */}
      {llmInfo && (
        <div className="rounded-xl border border-outline-variant/60 bg-surface-container p-4">
          <div className="flex items-start gap-3">
            <span aria-hidden="true" className="material-symbols-outlined text-lg text-primary">smart_toy</span>
            <div className="min-w-0 flex-1">
              <p className="text-sm font-semibold text-on-surface">
                LLM runtime: {llmInfo.llmProviderLabel ?? llmInfo.llmProvider ?? 'Unknown'}
                {llmInfo.llmModel ? ` · ${llmInfo.llmModel}` : ''}
              </p>
              <p className="text-xs text-on-surface-variant mt-1">
                Profiling and analysis run against this endpoint — message digests, never raw payloads.
              </p>
              {/* C'est ici que le contenu part réellement, et la page n'en disait rien : ni la
                  restriction quand elle est imposée, ni son absence quand elle ne l'est pas. La
                  phrase vient du même module que celle des Réglages, pour qu'une politique ne
                  puisse pas se lire différemment selon l'écran. */}
              {policy && (
                <p className={`text-xs mt-1.5 flex items-start gap-1.5 ${
                  policy.tone === 'open' ? 'text-warning' : 'text-on-surface-variant'
                }`}>
                  <span aria-hidden="true" className="material-symbols-outlined text-sm flex-shrink-0">
                    {policy.tone === 'local' || policy.tone === 'restricted' ? 'lock' : 'policy'}
                  </span>
                  <span><span className="font-medium">{policy.label}.</span> {policy.detail}</span>
                </p>
              )}
              {/* Une adresse absente ne se lit pas comme absente : la ligne disparaissait, sous une
                  bannière par ailleurs complète, et l'échec arrivait bien plus tard — depuis le
                  client HTTP, après la lecture des topics, sous la forme « URI with undefined
                  scheme ». Ce que le serveur ne peut pas atteindre, il le dit ici. */}
              {llmInfo.llmBaseUrl ? (
                <p className="text-[11px] font-mono text-on-surface-variant mt-2 break-all">{llmInfo.llmBaseUrl}</p>
              ) : (
                <p className="text-[11px] text-warning mt-2">No endpoint configured.</p>
              )}
              {llmInfo.llmConfigurationProblem && (
                <p className="text-xs mt-2 flex items-start gap-1.5 text-warning">
                  <span aria-hidden="true" className="material-symbols-outlined text-sm flex-shrink-0">
                    warning
                  </span>
                  <span className="break-words">{llmInfo.llmConfigurationProblem}</span>
                </p>
              )}
              {llmTest && (
                <p className={`text-xs mt-2 flex items-start gap-1.5 ${llmTest.ok ? 'text-success' : 'text-error'}`}>
                  <span aria-hidden="true" className="material-symbols-outlined text-sm flex-shrink-0">
                    {llmTest.ok ? 'check_circle' : 'error'}
                  </span>
                  <span className="break-words">{llmTest.message}</span>
                </p>
              )}
            </div>
            <Button
              variant="outline" size="sm" icon="network_check"
              loading={llmTesting}
              onClick={testLlmConnection}
            >
              Test
            </Button>
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

      {/* Une analyse qui a échoué sait quand même ce qu'elle avait lu — et c'est souvent la
          réponse : « aucun des topics choisis n'a livré de message » n'envoie pas au même endroit
          que « le modèle n'a pas répondu ». */}
      {error && failedCoverage && <CoverageNotice coverage={failedCoverage} />}

      {/* Et ce qu'elle a mesuré. Sans LLM configuré c'est la totalité de ce que l'exécution a
          produit : les transitions, les variantes et les latences sont du comptage, seule leur
          lecture demandait un modèle. */}
      {error && failedProcessModel && (
        <div className="mt-3">
          <ProcessModelPanel model={failedProcessModel} />
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
            providerLabel={llmInfo?.llmProviderLabel ?? llmInfo?.llmProvider}
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

                <Field label="Custom audit instruction">
                  {p => (
                    <Textarea
                      {...p}
                      value={customAuditPrompt}
                      onChange={e => setCustomAuditPrompt(e.target.value)}
                      rows={2}
                      placeholder="e.g. Flag any order whose amount changes between the received and validated topics."
                    />
                  )}
                </Field>
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
            {/* Ce sur quoi le diagramme ci-dessous repose. En mode live, la portée d'une fenêtre
                est déjà rapportée fenêtre par fenêtre par la barre d'état. */}
            {analysisMode === 'SNAPSHOT' && <CoverageNotice coverage={snapshotResult?.coverage} />}

            {/* Le processus mesuré, à côté du récit qu'on en a tiré — pour qu'il soit vérifiable
                plutôt que cru. Même règle que le tableau de preuves de Stream Flow. */}
            {analysisMode === 'SNAPSHOT' && (
              <ProcessModelPanel model={snapshotResult?.processModel} />
            )}

            {/* Live status bar (only in live mode) */}
            {analysisMode === 'LIVE' && (
              <>
                {/* Le mapping validé à l'étape 3 n'a pas été retrouvé : la corrélation entre topics
                    n'est plus que ce que le modèle en déduit. Dit ici, sinon la session tourne avec
                    l'apparence de celle qui a été configurée. */}
                {liveMappingApplied === false && (
                  <div className="bg-warning/10 border border-warning/30 rounded-xl p-3 flex items-start gap-3">
                    <span aria-hidden="true" className="material-symbols-outlined text-warning text-lg flex-shrink-0">link_off</span>
                    <div className="min-w-0">
                      <p className="text-xs font-semibold text-warning">Field mapping not applied</p>
                      <p className="text-xs text-warning/90 mt-0.5">
                        This server no longer holds the mapping validated at step 3, so correlation
                        across topics is the model's own inference. Re-run the profiling step to
                        rebuild it.
                      </p>
                    </div>
                  </div>
                )}

                <div className="flex items-center gap-3">
                  <div className="flex-1">
                    <LiveStatusBar
                      connected={liveConnected}
                      windowSize={liveWindowSize}
                      stats={liveStats}
                      lastUpdate={liveLastUpdate}
                    />
                  </div>
                  {liveConnected ? (
                    <button
                      onClick={stopLiveSession}
                      className="flex items-center gap-1.5 px-3 py-2 text-xs text-error border border-error/30 hover:bg-error/10 rounded-lg transition-colors"
                    >
                      <span aria-hidden="true" className="material-symbols-outlined text-sm">stop_circle</span>
                      Stop
                    </button>
                  ) : (
                    <button
                      onClick={startLiveSession}
                      className="flex items-center gap-1.5 px-3 py-2 text-xs text-primary border border-primary/30 hover:bg-primary/10 rounded-lg transition-colors"
                    >
                      <span aria-hidden="true" className="material-symbols-outlined text-sm">play_arrow</span>
                      Resume
                    </button>
                  )}
                </div>

                {/* The stream no longer restarts itself, so its ending has to be said out loud —
                    the counters simply stopping is not a message. */}
                {liveStarted && !liveConnected && (
                  <div className="bg-surface-container border border-outline-variant rounded-xl p-3 flex items-start gap-3">
                    <span aria-hidden="true" className="material-symbols-outlined text-on-surface-variant text-lg flex-shrink-0">pause_circle</span>
                    <p className="text-xs text-on-surface-variant">
                      Live monitoring is stopped — what is shown below is the last window analysed.
                      Resume starts a new session, which begins reading from the end of each topic.
                    </p>
                  </div>
                )}

                {/* Un plafond de dépense atteint est une décision qui s'applique, pas une panne :
                    il se lit en ambre, à côté de l'erreur et jamais à sa place. */}
                {liveBudgetNotice && (
                  <div className="bg-warning/10 border border-warning/30 rounded-xl p-3 flex items-start gap-3">
                    <span aria-hidden="true" className="material-symbols-outlined text-warning text-lg flex-shrink-0">savings</span>
                    <div className="min-w-0">
                      <p className="text-xs font-semibold text-warning">Session spend limit</p>
                      <p className="text-xs text-warning/90 mt-0.5 break-words">{liveBudgetNotice}</p>
                    </div>
                    <button
                      type="button"
                      aria-label="Dismiss"
                      onClick={() => setLiveBudgetNotice(null)}
                      className="ml-auto text-warning hover:text-warning/80 flex-shrink-0"
                    >
                      <span aria-hidden="true" className="material-symbols-outlined text-base">close</span>
                    </button>
                  </div>
                )}
                {liveError && (
                  <div className="bg-error/10 border border-error/30 rounded-xl p-3 flex items-start gap-3">
                    <span aria-hidden="true" className="material-symbols-outlined text-error text-lg flex-shrink-0">error</span>
                    <div className="min-w-0">
                      <p className="text-xs font-semibold text-error">Last window could not be analysed</p>
                      <p className="text-xs text-error/90 mt-0.5 break-words">{liveError}</p>
                      <p className="text-[11px] text-on-surface-variant mt-1">
                        Ingestion continues; the next window will be analysed as usual.
                      </p>
                    </div>
                    <button
                      type="button"
                      aria-label="Dismiss"
                      onClick={() => setLiveError(null)}
                      className="ml-auto text-error hover:text-error/80 flex-shrink-0"
                    >
                      <span aria-hidden="true" className="material-symbols-outlined text-base">close</span>
                    </button>
                  </div>
                )}
              </>
            )}

            {/* Ce que l'analyse a coûté. Jusqu'ici rien ne le mesurait : chaque réglage du
                pipeline (budget de prompt, échantillonnage, plafonds des digests) reposait sur un
                raisonnement et non sur un nombre, et personne ne pouvait dire ce que coûtait un
                run. */}
            {(() => {
              const usage = analysisMode === 'LIVE' ? liveUsage : snapshotResult?.usage ?? null;
              if (!usage && runUsages.length === 0) return null;
              return (
                <div className="flex flex-wrap items-center gap-x-3 gap-y-1 text-xs text-on-surface-variant">
                  {usage && (
                    <span className="flex items-center gap-2">
                      <span aria-hidden="true" className="material-symbols-outlined text-sm">speed</span>
                      <span>
                        {analysisMode === 'LIVE' ? 'Last window: ' : 'This analysis: '}
                        {describeUsage(usage)}
                      </span>
                    </span>
                  )}
                  {/* Le profilage est un appel au modèle comme un autre, et il se paie : l'omettre
                      faisait passer une exécution pour moins chère qu'elle n'est. */}
                  {profileResult?.usage && (
                    <span className="flex items-center gap-2">
                      <span aria-hidden="true" className="material-symbols-outlined text-sm">psychology</span>
                      <span>Profiling: {describeUsage(profileResult.usage)}</span>
                    </span>
                  )}
                  {/* Ce que l'exécution entière a coûté — profilage plus analyses — et non la seule
                      dernière fenêtre : c'est le cumul qui dit si la configuration est tenable sur
                      la durée. Le total est absent, jamais partiel, dès qu'un appel n'a pas été
                      chiffré : une mesure manquante n'est pas une mesure nulle. */}
                  {runUsages.length > 1 && (
                    <span className="flex items-center gap-2">
                      <span aria-hidden="true" className="material-symbols-outlined text-sm">functions</span>
                      <span>
                        {analysisMode === 'LIVE' ? `${liveUsageHistory.length} windows · ` : ''}
                        {runTokens == null
                          ? 'tokens not reported'
                          : `${runTokens.toLocaleString()} tokens`}
                        {runCost != null && ` · ${formatCostUsd(runCost)}`} this run
                      </span>
                    </span>
                  )}
                </div>
              );
            })()}

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
