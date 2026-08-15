// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors

import React, { useState, useMemo, useRef, useEffect, useCallback } from 'react';
import { Link, useLocation, useNavigate } from 'react-router-dom';
import axios from 'axios';
import {
  Badge, Button, EmptyState, Field, Input, NumberInput, Select, TopicInput, Tooltip,
  Table, TableBody, TableHead, TableRow, Td, Th,
} from '../components/ui';
import { useToast } from '../components/Toast';
import { useCatalog } from '../catalogStore';
import { describeApiError, type QueryErrorInfo } from './queryError';
import { toCsv } from './resultExport';
import {
  analyzeChain, buildContinuation, buildLayout, buildTopicSearchQuery, buildTraceQuery, centerOn,
  clampScale, compareFlows, describeChainInsight, describeComparison, describeContinuation,
  describeCoverage, describeProgress, describeSearchScope, expandTopicPatterns, filterHits,
  fitTransform, formatAbsoluteTime, formatDwell, formatLatency, formatRelativeTime, hitsToRows,
  HIT_EXPORT_COLUMNS, isNodeVisible, parseFlowResponse, parseSseBuffer, parseTopicList,
  clampEvidencePct, MAX_EVIDENCE_PCT, MIN_EVIDENCE_PCT,
  isBlankTraceParams, parseTraceParams, progressRatio, pushTraceHistory, readEvidencePct,
  readPanelOpen, readTraceParamsDraft, saveTraceParamsDraft,
  readTraceHistory, sameCriterion, slowestDivergence, sortHits, suggestWidenings, traceToJson,
  validateSearchPath, writeEvidencePct, writePanelOpen, zoomAt,
  type FlowHit, type FormErrors, type HitSortKey, type ParsedFlow, type TraceContinuation,
  type TraceHistoryEntry, type TraceParams, type TraceProgress, type Transform,
} from './streamFlow';
import { copyText } from '../clipboard';

/** Filet de sécurité côté client : le backend borne déjà la trace (explorer.stream-flow-timeout-ms). */
const REQUEST_TIMEOUT_MS = 120_000;

const EMPTY_FLOW: ParsedFlow = { nodes: [], edges: [], hits: [], stats: null, warnings: [] };

/**
 * Interrupteur du panneau de critères — quatre options identiques, un seul rendu.
 *
 * `disabled` porte sa propre explication : « Use Regex » sous une recherche par clé exacte était
 * dessiné éteint mais restait cliquable, et le clic éteignait silencieusement la clé exacte.
 */
const Toggle: React.FC<{
  label: string; hint?: string; checked: boolean; disabled?: boolean;
  onChange: (v: boolean) => void;
}> = ({ label, hint, checked, disabled = false, onChange }) => {
  const control = (
    <button
      type="button"
      role="switch" aria-checked={checked} aria-label={label} disabled={disabled}
      onClick={() => onChange(!checked)}
      className={`relative inline-flex h-5 w-9 shrink-0 items-center rounded-full transition-colors disabled:cursor-not-allowed ${checked ? 'bg-primary' : 'bg-surface-container-highest'}`}
    >
      <span className={`inline-block h-3.5 w-3.5 transform rounded-full transition-transform ${checked ? 'translate-x-[18px] bg-on-primary' : 'translate-x-1 bg-on-surface-variant'}`} />
    </button>
  );
  return (
    <div className={`flex items-center justify-between gap-2 ${disabled ? 'opacity-50' : ''}`}>
      <span className="text-[12px] font-medium text-on-surface-variant">{label}</span>
      {/* L'explication vivait sur le conteneur, donc n'atteignait que la souris — alors qu'elle
          dit précisément ce que l'interrupteur va changer. */}
      {hint ? <Tooltip content={hint}>{control}</Tooltip> : control}
    </div>
  );
};

const StreamFlow: React.FC = () => {
  const location = useLocation();
  const navigate = useNavigate();
  const { toast } = useToast();
  /** Déjà alimenté par le sondage de `Layout` : étendre `orders.*` ne coûte aucune requête. */
  const catalog = useCatalog();

  /*
   * Le formulaire s'initialise depuis l'URL : une trace se partage telle quelle. À défaut d'URL
   * qui décrive une recherche, le critère non lancé de la visite précédente reprend sa place —
   * l'URL l'emporte toujours, sinon un lien partagé écraserait le formulaire de son destinataire.
   */
  const initial = useMemo(() => {
    const fromUrl = parseTraceParams(location.search);
    return isBlankTraceParams(fromUrl) ? (readTraceParamsDraft() ?? fromUrl) : fromUrl;
  }, []); // eslint-disable-line react-hooks/exhaustive-deps

  const [messageKey, setMessageKey]         = useState(initial.messageKey);
  const [searchPath, setSearchPath]         = useState(initial.searchPath);
  /** Topics cibles saisis un par un, au lieu d'un textarea libre « un par ligne ». */
  const [selectedTopics, setSelectedTopics] = useState<string[]>(initial.topics);
  const [topicDraft, setTopicDraft]         = useState('');
  const [fieldErrors, setFieldErrors]       = useState<FormErrors>({});
  const [maxMessages, setMaxMessages]       = useState(initial.maxMessages);
  /** « recent » = derniers messages de chaque topic ; « window » = fenêtre temporelle. */
  const [windowMode, setWindowMode]         = useState(initial.windowMode);
  const [timeLimitMinutes, setTimeLimitMinutes] = useState(initial.timeLimitMinutes);
  const [useRegex, setUseRegex]             = useState(initial.useRegex);
  const [exactKey, setExactKey]             = useState(initial.exactKey);
  const [caseSensitive, setCaseSensitive]   = useState(initial.caseSensitive);
  const [searchHeaders, setSearchHeaders]   = useState(initial.searchHeaders);

  const [loading, setLoading]               = useState(false);
  const [error, setError]                   = useState<QueryErrorInfo | null>(null);
  const [notice, setNotice]                 = useState<string | null>(null);
  /** Annonce vocale d'une trace terminée — la barre de progression disparaît sans rien dire. */
  const [completionMessage, setCompletionMessage] = useState('');
  const [flow, setFlow]                     = useState<ParsedFlow>(EMPTY_FLOW);
  const [hasResult, setHasResult]           = useState(false);
  const [selectedTopic, setSelectedTopic]   = useState<string | null>(null);
  const [detailsOpen, setDetailsOpen]       = useState(true);
  const [hitFilter, setHitFilter]           = useState('');
  /** Tri du tableau de preuves. `chain` — l'ordre des sauts — est le sens même de la trace. */
  const [sortKey, setSortKey]               = useState<HitSortKey>('chain');
  const [sortDesc, setSortDesc]             = useState(false);
  /** Partage graphe / preuves, en pourcentage de hauteur, réglable et mémorisé. */
  const [evidencePct, setEvidencePct]       = useState(() => readEvidencePct());
  const [history, setHistory]               = useState<TraceHistoryEntry[]>(() => readTraceHistory());
  const [historyOpen, setHistoryOpen]       = useState(false);
  const [progress, setProgress]             = useState<TraceProgress | null>(null);
  /**
   * Critère du graphe affiché — distinct du formulaire, qui continue de s'éditer.
   * Sans lui, un champ modifié après la trace présentait l'ancien résultat comme le sien.
   */
  const [ranParams, setRanParams]           = useState<TraceParams | null>(null);
  /** Le panneau de critères se replie ; sur un écran étroit il part replié, le graphe passe avant. */
  const [panelOpen, setPanelOpen]           = useState(() =>
    readPanelOpen(typeof window === 'undefined' || window.innerWidth >= 1024));
  /**
   * Seconde trace mise en regard de celle qui est affichée. « ORD-42 est passé, ORD-43 s'est
   * perdu — où ? » se répondait jusqu'ici avec deux onglets et deux tableaux lus en vis-à-vis.
   */
  const [comparison, setComparison]         = useState<{ params: TraceParams; flow: ParsedFlow } | null>(null);
  const [comparing, setComparing]           = useState(false);
  const [comparePickerOpen, setComparePickerOpen] = useState(false);

  const messageKeyRef = useRef<HTMLInputElement>(null);
  const searchPathRef = useRef<HTMLInputElement>(null);
  /** Zone graphe + preuves : sa hauteur sert de référence au glissé du séparateur. */
  const mainRef       = useRef<HTMLElement>(null);
  /** Requête en vol — annulable, une trace peut légitimement durer une minute. */
  const abortRef      = useRef<AbortController | null>(null);
  /** Numéro de la trace en cours : ce qui arrive d'une passe abandonnée est ignoré. */
  const runIdRef      = useRef(0);

  // Pan & zoom
  const [transform, setTransform] = useState<Transform>({ x: 48, y: 48, scale: 1 });
  const svgRef                    = useRef<SVGSVGElement>(null);
  const isPanning                 = useRef(false);
  const lastPos                   = useRef({ x: 0, y: 0 });
  /** Un glissé ne doit pas se terminer en clic sur le nœud relâché. */
  const dragged                   = useRef(false);

  const { nodes, edges, hits, stats, warnings } = flow;
  const layout = useMemo(() => buildLayout(nodes, edges), [nodes, edges]);

  const togglePanel = useCallback(() => setPanelOpen(open => {
    writePanelOpen(!open);
    return !open;
  }), []);

  /** Taille de la zone de graphe, pour recadrer ou recentrer sans relire le DOM deux fois. */
  const viewport = useCallback(() => {
    const box = svgRef.current?.getBoundingClientRect();
    return box ? { width: box.width, height: box.height } : null;
  }, []);

  const currentParams = useCallback((): TraceParams => ({
    messageKey: messageKey.trim(),
    searchPath: searchPath.trim(),
    topics: selectedTopics,
    windowMode,
    timeLimitMinutes,
    maxMessages,
    useRegex,
    exactKey,
    caseSensitive,
    searchHeaders,
  }), [messageKey, searchPath, selectedTopics, windowMode, timeLimitMinutes, maxMessages,
    useRegex, exactKey, caseSensitive, searchHeaders]);

  // Le brouillon suit la saisie, et s'efface de lui-même quand le formulaire revient à vide.
  useEffect(() => {
    saveTraceParamsDraft(currentParams());
  }, [currentParams]);

  const fitView = useCallback(() => {
    const box = viewport();
    if (box) setTransform(fitTransform(layout, box));
  }, [layout, viewport]);

  // Le graphe est cadré dès son arrivée : un « reset » à translate(40,40) scale(1) laissait
  // la moitié d'une chaîne de sept topics hors écran. Replier le panneau de critères élargit
  // la vue de 18 rem : sans recadrage, le graphe resterait collé à gauche.
  useEffect(() => {
    // eslint-disable-next-line react-hooks/set-state-in-effect -- recadrage : dépend de la géométrie rendue
    if (nodes.length > 0) fitView();
  }, [nodes, panelOpen, fitView]);

  useEffect(() => {
    const el = svgRef.current;
    if (!el) return;
    const handler = (e: WheelEvent) => {
      e.preventDefault();
      const box = el.getBoundingClientRect();
      const factor = e.deltaY > 0 ? 0.9 : 1.1;
      setTransform(t => zoomAt(t, factor, e.clientX - box.left, e.clientY - box.top));
    };
    el.addEventListener('wheel', handler, { passive: false });
    return () => el.removeEventListener('wheel', handler);
  }, [hasResult, nodes.length]); // re-attach after the SVG mounts

  useEffect(() => () => abortRef.current?.abort(), []);

  // Événements *pointeur* et non souris : le même code fait glisser le graphe au doigt sur une
  // tablette, où le pan était jusqu'ici impossible (et `touch-action: none` empêche la page de
  // défiler sous le geste).
  const onPointerDown = useCallback((e: React.PointerEvent<SVGSVGElement>) => {
    isPanning.current = true;
    dragged.current = false;
    lastPos.current = { x: e.clientX, y: e.clientY };
    e.currentTarget.style.cursor = 'grabbing';
  }, []);

  const onPointerMove = useCallback((e: React.PointerEvent<SVGSVGElement>) => {
    if (!isPanning.current) return;
    const dx = e.clientX - lastPos.current.x;
    const dy = e.clientY - lastPos.current.y;
    if (Math.abs(dx) + Math.abs(dy) > 2) dragged.current = true;
    lastPos.current = { x: e.clientX, y: e.clientY };
    setTransform(t => ({ ...t, x: t.x + dx, y: t.y + dy }));
  }, []);

  const onPointerUp = useCallback((e: React.PointerEvent<SVGSVGElement>) => {
    isPanning.current = false;
    e.currentTarget.style.cursor = 'grab';
  }, []);

  /**
   * Le graphe se pilote au clavier : flèches pour se déplacer, +/− pour zoomer, 0 pour recadrer,
   * Échap pour désélectionner. Sans cela, pan et zoom n'existaient qu'à la souris — un opérateur
   * sans souris ne pouvait tout simplement pas atteindre la moitié d'une chaîne un peu longue.
   */
  const onGraphKeyDown = useCallback((e: React.KeyboardEvent<SVGSVGElement>) => {
    const step = e.shiftKey ? 160 : 48;
    switch (e.key) {
      case 'ArrowLeft':  setTransform(t => ({ ...t, x: t.x + step })); break;
      case 'ArrowRight': setTransform(t => ({ ...t, x: t.x - step })); break;
      case 'ArrowUp':    setTransform(t => ({ ...t, y: t.y + step })); break;
      case 'ArrowDown':  setTransform(t => ({ ...t, y: t.y - step })); break;
      case '+': case '=': setTransform(t => ({ ...t, scale: clampScale(t.scale * 1.25) })); break;
      case '-': case '_': setTransform(t => ({ ...t, scale: clampScale(t.scale * 0.8) })); break;
      case '0': fitView(); break;
      case 'Escape': setSelectedTopic(null); break;
      default: return;
    }
    e.preventDefault();
  }, [fitView]);

  /** Sélection depuis le graphe : la ligne correspondante doit être visible dans le tableau. */
  const selectFromGraph = useCallback((topic: string) => {
    setSelectedTopic(topic);
    setDetailsOpen(true);
  }, []);

  /**
   * Sélection depuis le tableau : on amène le nœud sous les yeux s'il est hors cadre. Recadrer
   * systématiquement déplacerait le graphe sous le curseur à chaque clic de ligne.
   */
  const selectFromTable = useCallback((topic: string) => {
    setSelectedTopic(topic);
    const box = viewport();
    if (!box || isNodeVisible(layout, box, transform, topic)) return;
    const next = centerOn(layout, box, topic, transform.scale);
    if (next) setTransform(next);
  }, [layout, transform, viewport]);

  // La ligne sélectionnée depuis le graphe est amenée dans le tableau, qui défile de son côté.
  useEffect(() => {
    if (!selectedTopic || !detailsOpen) return;
    document.getElementById(`sf-hit-${selectedTopic}`)?.scrollIntoView({ block: 'nearest' });
  }, [selectedTopic, detailsOpen]);

  /**
   * Glissé du séparateur : la hauteur suit le pointeur, et n'est écrite qu'au relâché — un
   * `localStorage.setItem` par image de glissement serait du gaspillage pur.
   */
  const evidencePctRef = useRef(evidencePct);
  const applyEvidencePct = useCallback((pct: number) => {
    const next = clampEvidencePct(pct);
    evidencePctRef.current = next;
    setEvidencePct(next);
  }, []);

  const onDividerPointerDown = useCallback((e: React.PointerEvent) => {
    e.preventDefault();
    const box = mainRef.current?.getBoundingClientRect();
    if (!box || box.height === 0) return;
    const move = (event: PointerEvent) =>
      applyEvidencePct(((box.bottom - event.clientY) / box.height) * 100);
    const up = () => {
      window.removeEventListener('pointermove', move);
      window.removeEventListener('pointerup', up);
      writeEvidencePct(evidencePctRef.current);
    };
    window.addEventListener('pointermove', move);
    window.addEventListener('pointerup', up);
  }, [applyEvidencePct]);

  /** Le séparateur se règle aussi au clavier : c'est un `separator` ARIA, pas une poignée souris. */
  const onDividerKeyDown = (e: React.KeyboardEvent) => {
    const step = e.key === 'ArrowUp' ? 5 : e.key === 'ArrowDown' ? -5 : 0;
    if (step === 0) return;
    e.preventDefault();
    applyEvidencePct(evidencePctRef.current + step);
    writeEvidencePct(evidencePctRef.current);
  };

  const clearFieldError = (key: keyof FormErrors) =>
    setFieldErrors(prev => (prev[key] ? { ...prev, [key]: undefined } : prev));

  /**
   * Ajoute le brouillon à la liste. Il peut valoir plusieurs topics — une liste collée depuis un
   * runbook, ou un motif `orders.*` étendu sur le catalogue déjà chargé. Un motif sans
   * correspondance est signalé plutôt qu'envoyé comme nom de topic : il n'en est pas un.
   */
  const addTopic = () => {
    const entries = parseTopicList(topicDraft);
    if (entries.length === 0) return;
    const { topics, unmatched } = expandTopicPatterns(entries, catalog.topics);
    if (topics.length > 0) {
      setSelectedTopics(list => [...list, ...topics.filter(t => !list.includes(t))]);
    }
    if (unmatched.length > 0) {
      toast(`No topic matches ${unmatched.join(', ')}`, 'error');
    }
    setTopicDraft('');
  };

  const cancelRun = () => {
    abortRef.current?.abort();
    abortRef.current = null;
  };

  /**
   * @param continuation seconde passe d'une trace interrompue : seuls les topics jamais lus sont
   *        rescannés, et le serveur rend un graphe fusionné. Le critère affiché (URL, historique,
   *        pastille) reste celui de la trace entière — la liste réduite n'est qu'un moyen.
   */
  const runTrace = useCallback(async (params: TraceParams, continuation?: TraceContinuation) => {
    const errors: FormErrors = {};
    if (!params.messageKey) errors.messageKey = 'A message key is required.';
    const pathError = validateSearchPath(params.searchPath);
    if (pathError) errors.searchPath = pathError;
    setFieldErrors(errors);
    if (errors.messageKey) { messageKeyRef.current?.focus(); return; }
    if (errors.searchPath) { searchPathRef.current?.focus(); return; }

    const controller = new AbortController();
    abortRef.current = controller;
    // Numéro de passe : une trace abandonnée continue de résoudre ses promesses, et ses derniers
    // effets (« trace annulée », `loading` à false, progression effacée) tombaient sur la trace
    // suivante — le bandeau d'annulation s'affichait au-dessus d'un scan qui venait de partir.
    const run = ++runIdRef.current;
    const current = () => runIdRef.current === run;
    setLoading(true);
    setError(null);
    setNotice(null);
    setCompletionMessage('');
    setProgress(null);
    setSelectedTopic(null);
    setHitFilter('');
    // La comparaison portait sur la trace précédente ; la garder en regard d'une nouvelle
    // reviendrait à comparer deux critères sans rapport.
    setComparison(null);
    setComparePickerOpen(false);
    setFlow(EMPTY_FLOW);
    setHasResult(false);
    // Le graphe qui va s'afficher appartient à ce critère-là, y compris s'il est partiel :
    // une trace arrêtée en cours de route garde de quoi dire ce qu'elle cherchait.
    setRanParams(params);

    const body = {
      messageKey: params.messageKey,
      maxMessagesPerTopic: params.maxMessages,
      searchPath: params.searchPath || null,
      timeLimitMinutes: params.windowMode === 'window' ? params.timeLimitMinutes : null,
      useRegex: params.useRegex,
      exactKey: params.exactKey,
      caseSensitive: params.caseSensitive,
      searchHeaders: params.searchHeaders,
      targetTopics: continuation ? continuation.topics : params.topics,
      priorHits: continuation?.priorHits ?? null,
      priorCoverage: continuation?.priorCoverage ?? null,
    };

    // Ce que la trace a trouvé au moment où on la quitte : gardé en ref pour que l'annulation
    // conserve le graphe partiel, alors que l'état React n'est pas lisible depuis ce callback.
    let lastFlow: ParsedFlow | null = null;
    let lastProgress: TraceProgress | null = null;

    const finish = (parsed: ParsedFlow, params2: TraceParams) => {
      if (!current()) return;
      setFlow(parsed);
      setDetailsOpen(true);
      setHasResult(true);
      // L'URL suit la trace affichée : elle est copiable à tout moment, pas seulement
      // depuis le bouton de partage.
      navigate({ search: buildTraceQuery(params2) }, { replace: true });
      setHistory(pushTraceHistory({ ...params2, ranAt: Date.now(), topicsFound: parsed.nodes.length }));
      setCompletionMessage(parsed.nodes.length === 0
        ? `Trace complete: ${params2.messageKey} was not found in what was scanned.`
        : `Trace complete: ${parsed.nodes.length} topic${parsed.nodes.length > 1 ? 's' : ''}, `
          + `${parsed.stats?.matches ?? parsed.hits.length} match(es).`);
    };

    try {
      const res = await fetch('/api/stream-flow/stream', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(body),
        signal: controller.signal,
      });

      if (!res.ok) {
        const detail = await res.json().catch(() => null);
        throw { response: { status: res.status, data: detail } };
      }
      if (!res.body) {
        // Pas de flux lisible (navigateur ancien, environnement de test) : l'appel non streamé
        // reste la même trace, sans les résultats intermédiaires.
        const fallback = await axios.post('/api/stream-flow', body,
          { signal: controller.signal, timeout: REQUEST_TIMEOUT_MS });
        finish(parseFlowResponse(fallback.data), params);
        return;
      }

      const reader = res.body.getReader();
      const decoder = new TextDecoder();
      let buffer = '';
      for (;;) {
        const { done, value } = await reader.read();
        if (done) break;
        buffer += decoder.decode(value, { stream: true });
        const { events, rest } = parseSseBuffer(buffer);
        buffer = rest;
        for (const event of events) {
          if (event.event === 'progress') {
            lastProgress = JSON.parse(event.data) as TraceProgress;
            if (current()) setProgress(lastProgress);
          } else if (event.event === 'flow') {
            lastFlow = parseFlowResponse(JSON.parse(event.data));
            if (current()) {
              setFlow(lastFlow);
              setHasResult(true);
            }
          } else if (event.event === 'result') {
            finish(parseFlowResponse(JSON.parse(event.data)), params);
            lastFlow = null;
          } else if (event.event === 'failed') {
            const message = (JSON.parse(event.data) as { message?: string }).message;
            throw { response: { status: 500, data: { message } } };
          }
        }
      }
    } catch (err) {
      if (!current()) return;
      if (axios.isCancel(err) || (err as { name?: string })?.name === 'AbortError'
        || (err as { code?: string })?.code === 'ERR_CANCELED') {
        // Arrêter tôt est le but de la trace streamée : on garde ce qui a été trouvé.
        const stopped = lastProgress
          ? `Stopped after ${lastProgress.topicsCompleted} of ${lastProgress.topicsInScope} topics — showing what was found by then.`
          : 'Trace cancelled — nothing was changed on the cluster.';
        setNotice(stopped);
        setCompletionMessage(stopped);
        if (lastFlow) setHasResult(true);
      } else {
        // Surface the real backend cause when there is one (invalid regex, malformed
        // search path, unreachable broker); otherwise fall back to a generic hint.
        const failure = describeApiError(err, 'Failed to trace stream flow.');
        setError(failure);
        setCompletionMessage(`Trace failed: ${failure.title}`);
        // The previous graph described a different search: keeping it on screen next to a
        // fresh error would present stale coverage as the result of this run.
        setFlow(EMPTY_FLOW);
        setHasResult(false);
        setRanParams(null);
      }
    } finally {
      if (current()) {
        abortRef.current = null;
        setProgress(null);
        setLoading(false);
      }
    }
  }, [navigate]);

  const handleSubmit = () => {
    // Un topic encore dans le champ de saisie compte : on ne le perd pas parce que
    // l'utilisateur a cliqué « Trace » sans valider par Entrée.
    const params = currentParams();
    const { topics: pending } = expandTopicPatterns(parseTopicList(topicDraft), catalog.topics);
    const added = pending.filter(topic => !params.topics.includes(topic));
    if (added.length > 0) {
      params.topics = [...params.topics, ...added];
      setSelectedTopics(params.topics);
      setTopicDraft('');
    }
    void runTrace(params);
  };

  /** Une trace ouverte depuis un lien partagé s'exécute d'elle-même : c'est ce qu'on partage. */
  const autoRan = useRef(false);
  useEffect(() => {
    if (autoRan.current) return;
    autoRan.current = true;
    // eslint-disable-next-line react-hooks/set-state-in-effect -- trace relancée depuis l'URL au montage
    if (initial.messageKey.trim()) void runTrace(initial);
    // Le garde se relève au démontage. Sans cela, le double montage de StrictMode annulait la
    // requête (le nettoyage `abortRef`) puis refusait de la relancer : un lien partagé ouvrait
    // une page vide en développement. `initial` et `runTrace` étant stables, la trace ne part
    // qu'une fois par montage réel.
    return () => { autoRan.current = false; };
  }, [initial, runTrace]);

  /**
   * Rejoue un critère : le formulaire se remet dessus **avant** de relancer. Un scan élargi
   * depuis le panneau « rien trouvé » doit laisser le formulaire dans l'état qu'il vient de
   * décrire, sinon le champ affiché et la trace affichée ne parlent plus de la même chose.
   */
  const rerun = (params: TraceParams) => {
    setMessageKey(params.messageKey);
    setSearchPath(params.searchPath);
    setSelectedTopics(params.topics);
    setTopicDraft('');
    setWindowMode(params.windowMode);
    setTimeLimitMinutes(params.timeLimitMinutes);
    setMaxMessages(params.maxMessages);
    setUseRegex(params.useRegex);
    setExactKey(params.exactKey);
    setCaseSensitive(params.caseSensitive);
    setSearchHeaders(params.searchHeaders);
    void runTrace(params);
  };

  const replay = (entry: TraceHistoryEntry) => {
    setHistoryOpen(false);
    rerun(entry);
  };

  /**
   * Le lien décrit la trace **affichée**, pas le formulaire : les trois boutons (Link, CSV, JSON)
   * portent sur le même résultat, et un critère édité depuis a son propre bandeau pour se relancer.
   */
  /**
   * Rejoue un critère de l'historique **à côté** de la trace affichée, sans la remplacer.
   *
   * Passe par l'endpoint non streamé : une comparaison n'a rien à montrer avant d'être complète,
   * et le flux SSE de la trace principale n'a pas à être partagé avec une seconde exécution.
   */
  const compareWith = async (params: TraceParams) => {
    setComparePickerOpen(false);
    setComparing(true);
    try {
      const response = await axios.post('/api/stream-flow', {
        messageKey: params.messageKey,
        maxMessagesPerTopic: params.maxMessages,
        searchPath: params.searchPath || null,
        timeLimitMinutes: params.windowMode === 'window' ? params.timeLimitMinutes : null,
        useRegex: params.useRegex,
        exactKey: params.exactKey,
        caseSensitive: params.caseSensitive,
        searchHeaders: params.searchHeaders,
        targetTopics: params.topics,
      }, { timeout: REQUEST_TIMEOUT_MS });
      setComparison({ params, flow: parseFlowResponse(response.data) });
    } catch (err) {
      toast(describeApiError(err, 'Failed to run the comparison trace.').title, 'error');
    } finally {
      setComparing(false);
    }
  };

  const copyLink = async () => {
    const url = `${window.location.origin}${window.location.pathname}${buildTraceQuery(ranParams ?? currentParams())}`;
    try {
      const ok = await copyText(url);
      toast(ok ? 'Trace link copied' : 'Could not copy — the browser refused clipboard access',
        ok ? 'success' : 'error');
    } catch {
      toast('Could not copy — the browser refused clipboard access', 'error');
    }
  };

  const download = (content: string, mime: string, ext: string) => {
    const blob = new Blob([content], { type: mime });
    const url = URL.createObjectURL(blob);
    const a = document.createElement('a');
    a.href = url;
    const traced = (ranParams?.messageKey ?? messageKey).trim();
    a.download = `stream-flow-${traced.replace(/[^\w.-]+/g, '_') || 'trace'}.${ext}`;
    a.click();
    URL.revokeObjectURL(url);
    toast(`Exported as ${ext.toUpperCase()}`, 'success');
  };

  const coverage = describeCoverage(stats);
  const hitByTopic = useMemo(() => new Map(hits.map(h => [h.topic, h])), [hits]);
  const insight = useMemo(() => analyzeChain(hits), [hits]);
  const insightNotes = useMemo(() => describeChainInsight(insight), [insight]);
  const scopeHint = describeSearchScope(searchPath, searchHeaders, exactKey);
  /** Rang du saut dans la chaîne — conservé quel que soit le filtre ou le tri du tableau. */
  const hopNumber = useMemo(() => new Map(hits.map((h, i) => [h.topic, i + 1])), [hits]);
  const shownHits = useMemo(
    () => sortHits(filterHits(hits, hitFilter), sortKey, sortDesc),
    [hits, hitFilter, sortKey, sortDesc]);

  const toggleSort = (key: HitSortKey) => {
    if (key === sortKey) {
      setSortDesc(desc => !desc);
    } else {
      setSortKey(key);
      // Un tri numérique s'ouvre décroissant : « le plus lent », « le plus vu » d'abord.
      setSortDesc(key !== 'chain' && key !== 'topic');
    }
  };

  const sortIcon = (key: HitSortKey) =>
    (key !== sortKey ? 'unfold_more' : sortDesc ? 'arrow_downward' : 'arrow_upward');
  /** Le formulaire a bougé depuis la trace affichée : le graphe ne répond plus à ce qu'on lit. */
  const stale = hasResult && !loading && ranParams !== null
    && !sameCriterion(ranParams, currentParams());
  const suggestions = useMemo(
    () => (ranParams ? suggestWidenings(ranParams) : []), [ranParams]);
  /** Trace arrêtée par son budget : les topics jamais lus sont nommés, donc reprenables. */
  const continuation = useMemo(() => buildContinuation(flow), [flow]);
  const diff = useMemo(
    () => (comparison ? compareFlows(hits, comparison.flow.hits) : null), [comparison, hits]);
  const divergence = useMemo(() => (diff ? slowestDivergence(diff) : null), [diff]);
  /** Topics que la trace comparée n'a pas vus — soulignés sur le graphe, qui montre toujours A. */
  const missingInB = useMemo(
    () => new Set(diff ? diff.rows.filter(r => r.status === 'ONLY_A').map(r => r.topic) : []),
    [diff]);
  /** Critères rejouables : l'historique, moins celui qui est déjà affiché. */
  const comparable = useMemo(
    () => (ranParams ? history.filter(entry => !sameCriterion(entry, ranParams)) : []),
    [history, ranParams]);
  const continueTrace = () => {
    if (continuation && ranParams) void runTrace(ranParams, continuation);
  };
  const graphLegend = insight.slowestHopTopic !== null || insight.clockSkewTopics.length > 0;

  return (
    <div className="flex h-full overflow-hidden">

      {/* Rail de réouverture — le panneau replié laisse toute la largeur au graphe. */}
      {!panelOpen && (
        <div className="w-10 shrink-0 border-r border-outline-variant/60 bg-surface-container-low flex flex-col items-center gap-3 py-4">
          <button
            type="button"
            onClick={togglePanel}
            aria-expanded={false}
            aria-label="Show the trace criteria"
            title="Show the trace criteria"
            className="text-on-surface-variant hover:text-on-surface"
          >
            <span aria-hidden="true" className="material-symbols-outlined text-[20px]">chevron_right</span>
          </button>
          <span
            className="text-[10px] font-semibold uppercase tracking-widest text-outline whitespace-nowrap"
            style={{ writingMode: 'vertical-rl' }}
          >
            Criteria
          </span>
        </div>
      )}

      {/* ── Config Panel ── */}
      {/* Un vrai <form> : Entrée depuis n'importe quel champ lance la trace, au lieu d'un
          onKeyDown bricolé sur le seul champ « Message Key ». */}
      {panelOpen && (
      <form
        noValidate
        id="sf-criteria"
        onSubmit={e => { e.preventDefault(); handleSubmit(); }}
        aria-label="Stream flow tracer"
        className="w-72 border-r border-outline-variant/60 bg-surface-container-low flex flex-col shrink-0 overflow-y-auto"
      >
        <div className="p-5 pb-0">
          <div className="flex items-start justify-between gap-2 mb-4">
            <h3 className="text-xs font-bold text-on-surface-variant uppercase tracking-widest">Stream Flow Tracer</h3>
            <div className="flex items-center gap-1.5 shrink-0">
              {history.length > 0 && (
                <button
                  type="button"
                  onClick={() => setHistoryOpen(o => !o)}
                  aria-expanded={historyOpen}
                  className="text-on-surface-variant hover:text-on-surface"
                  title="Recent traces"
                >
                  <span aria-hidden="true" className="material-symbols-outlined text-[18px]">history</span>
                </button>
              )}
              <button
                type="button"
                onClick={togglePanel}
                aria-expanded
                aria-controls="sf-criteria"
                aria-label="Hide the trace criteria"
                className="text-on-surface-variant hover:text-on-surface"
                title="Hide the criteria and widen the graph"
              >
                <span aria-hidden="true" className="material-symbols-outlined text-[18px]">chevron_left</span>
              </button>
            </div>
          </div>
          <p className="text-xs text-on-surface-variant">Trace a message key across Kafka topics to visualize the data flow pipeline.</p>
        </div>

        {historyOpen && history.length > 0 && (
          <ul className="space-y-1 border border-outline-variant/60 rounded-md p-1.5 mx-5 mt-5" aria-label="Recent traces">
            {history.map(entry => (
              <li key={`${entry.ranAt}`}>
                <button
                  type="button"
                  onClick={() => replay(entry)}
                  className="w-full text-left px-1.5 py-1 rounded hover:bg-surface-container-high"
                >
                  <span className="block font-mono text-[11px] text-on-surface truncate">{entry.messageKey}</span>
                  <span className="block text-[10px] text-outline">
                    {entry.searchPath ? `${entry.searchPath} · ` : ''}
                    {entry.topicsFound} topic{entry.topicsFound === 1 ? '' : 's'} · {formatRelativeTime(entry.ranAt)}
                  </span>
                </button>
              </li>
            ))}
          </ul>
        )}

        <div className="space-y-4 p-5">
          {/* Message Key */}
          <Field
            label={exactKey ? 'Record key (exact)' : useRegex ? 'Message Key (regex)' : 'Message Key'}
            required
            error={fieldErrors.messageKey}
          >
            {p => (
              <Input
                {...p}
                ref={messageKeyRef}
                className="font-mono"
                value={messageKey}
                onChange={e => { setMessageKey(e.target.value); clearFieldError('messageKey'); }}
                placeholder={useRegex ? 'e.g. order_\\d+' : 'e.g. order_88219'}
                autoComplete="off"
                spellCheck={false}
              />
            )}
          </Field>

          {/* Search Path */}
          <Field
            label="Search Path"
            error={fieldErrors.searchPath}
            description={exactKey
              ? 'Not used: the record key is not inside the payload.'
              : scopeHint}
          >
            {p => (
              <Input
                {...p}
                ref={searchPathRef}
                className="font-mono"
                value={searchPath}
                disabled={exactKey}
                onChange={e => { setSearchPath(e.target.value); clearFieldError('searchPath'); }}
                placeholder="order.id · $.orderId · /order/id · header:correlation-id"
                autoComplete="off"
                spellCheck={false}
              />
            )}
          </Field>

          {/* Target Topics — suggérés depuis le catalogue, ajoutés un par un */}
          <div className="space-y-1.5">
            <span className="block text-[12px] font-medium text-on-surface-variant">Target Topics</span>
            <TopicInput
              aria-label="Add a target topic"
              value={topicDraft}
              onChange={setTopicDraft}
              onEnter={addTopic}
              placeholder="Topic, orders.*, or a pasted list…"
            />
            {selectedTopics.length > 0 && (
              <ul className="flex flex-wrap gap-1 pt-1">
                {selectedTopics.map(topic => (
                  <li key={topic}>
                    <span className="inline-flex items-center gap-1 rounded-md border border-outline-variant bg-surface-container-high px-1.5 py-0.5 text-[11px] font-mono text-on-surface">
                      {topic}
                      <button
                        type="button"
                        onClick={() => setSelectedTopics(list => list.filter(t => t !== topic))}
                        aria-label={`Remove ${topic}`}
                        className="text-outline hover:text-error"
                      >
                        <span aria-hidden="true" className="material-symbols-outlined text-[13px] align-middle">close</span>
                      </button>
                    </span>
                  </li>
                ))}
              </ul>
            )}
            <p className="text-[10px] text-on-surface-variant">
              {selectedTopics.length === 0
                ? 'None selected — the whole cluster is scanned, one consumer per topic. Naming the topics is much faster: paste a list, or use a pattern like orders.*'
                : `${selectedTopics.length} topic(s) will be scanned.`}
            </p>
            {selectedTopics.length > 1 && (
              <button
                type="button"
                onClick={() => setSelectedTopics([])}
                className="text-[10px] text-outline hover:text-error"
              >
                Clear all
              </button>
            )}
          </div>

          {/* Fenêtre lue */}
          <Field
            label="Scan window"
            description={windowMode === 'window'
              ? 'Reads the newest messages of each topic that fall inside the window.'
              : 'Reads the newest messages of each topic.'}
          >
            {p => (
              <Select
                {...p}
                value={windowMode}
                onChange={e => setWindowMode(e.target.value as 'recent' | 'window')}
              >
                <option value="recent">Most recent messages</option>
                <option value="window">Time window</option>
              </Select>
            )}
          </Field>

          {windowMode === 'window' && (
            <Field label="Window length (minutes)">
              {p => (
                <NumberInput {...p} min={1} max={1440} fallback={5}
                  value={timeLimitMinutes} onChange={setTimeLimitMinutes} />
              )}
            </Field>
          )}

          {/* Max Messages — le curseur pour balayer, le champ pour viser : 750 se tapait
              autrement en visant un pixel sur une piste de dix pas. */}
          <div className="space-y-1.5">
            <div className="flex items-end justify-between gap-2">
              <label htmlFor="sf-max-messages" className="text-[12px] font-medium text-on-surface-variant">
                Max Messages / Topic
              </label>
              <NumberInput
                id="sf-max-messages-value"
                aria-label="Max messages per topic"
                className="h-7 w-20 text-[12px]"
                min={10} max={1000} fallback={100}
                value={maxMessages} onChange={setMaxMessages}
              />
            </div>
            <input
              id="sf-max-messages"
              aria-label="Max messages per topic (slider)"
              type="range" min={10} max={1000} step={10} value={maxMessages}
              onChange={e => setMaxMessages(Number(e.target.value))}
              className="w-full accent-primary"
            />
          </div>

          <div className="space-y-2.5 pt-1">
            <Toggle
              label="Exact record key"
              checked={exactKey}
              onChange={next => { setExactKey(next); if (next) setUseRegex(false); }}
              hint="The value is the Kafka record key, compared whole. Scans only that key's partition — much faster, but it assumes the default partitioner." />
            <Toggle label="Use Regex" checked={useRegex && !exactKey} disabled={exactKey}
              onChange={setUseRegex}
              hint={exactKey
                ? 'Unavailable under an exact record key: the key is compared whole, not matched.'
                : 'Treat the message key as a regular expression.'} />
            <Toggle label="Case sensitive" checked={caseSensitive} onChange={setCaseSensitive}
              hint="Off by default, like the topic search." />
            <Toggle label="Search headers" checked={searchHeaders} onChange={setSearchHeaders}
              hint="Also match Kafka header values — correlation ids often travel only there." />
          </div>
        </div>

        {/* Barre d'action collante : avec une dizaine de topics en pastilles, « Trace Flow »
            passait sous la ligne de flottaison et il fallait faire défiler pour lancer. */}
        <div className="mt-auto sticky bottom-0 space-y-2 border-t border-outline-variant/60 bg-surface-container-low p-4">
          <Button
            type="submit"
            variant="primary"
            className="w-full"
            icon={loading ? undefined : 'route'}
            loading={loading}
            disabled={loading || !messageKey.trim()}
          >
            {loading ? 'Tracing…' : stale ? 'Trace Flow (updated)' : 'Trace Flow'}
          </Button>
          {loading && (
            <Button type="button" variant="outline" className="w-full" icon="close" onClick={cancelRun}>
              Cancel
            </Button>
          )}
        </div>
      </form>
      )}

      {/* ── Graph Area ── */}
      <main ref={mainRef} className="flex-1 relative bg-background-dark overflow-hidden flex flex-col min-w-0">

        {/* Ce qu'une trace terminée a donné, annoncé une fois : la barre de progression est un
            `status` tant qu'elle tourne, mais sa disparition ne dit rien à qui ne voit pas l'écran. */}
        <p className="sr-only" role="status" aria-live="polite">{completionMessage}</p>

        <div className="relative flex-1 min-h-0">

          {/* Un seul bandeau supérieur : pastille, alerte et exports étaient trois surfaces
              flottantes indépendantes, et le message d'annulation recouvrait le résumé du scan. */}
          <div className="absolute top-4 left-4 right-4 z-20 flex items-start gap-3 pointer-events-none">

            <div className="flex flex-col items-start gap-2 min-w-0 pointer-events-auto">
              {/* Ce que montre le graphe : la clé tracée d'abord — panneau replié, c'est le seul
                  endroit qui rappelle ce qui a été cherché. */}
              {hasResult && nodes.length > 0 && (
                <div className="flex items-center gap-2 bg-surface-container/90 border border-outline-variant px-3 py-1.5 rounded-full text-xs max-w-full">
                  <span aria-hidden="true" className="material-symbols-outlined text-sm text-primary">route</span>
                  {ranParams && (
                    <>
                      <span className="font-mono text-on-surface truncate max-w-[14rem]" title={ranParams.messageKey}>
                        {ranParams.messageKey}
                      </span>
                      <span className="text-outline">·</span>
                    </>
                  )}
                  <span className="text-on-surface-variant">{nodes.length} topics</span>
                  <span className="text-outline">·</span>
                  <span className="text-on-surface-variant">{stats?.matches ?? 0} matches</span>
                  {stats?.truncated && (
                    <>
                      <span className="text-outline">·</span>
                      <Tooltip content="At least one topic was not scanned to the end — older matches may exist beyond what the budget allowed.">
                        <span tabIndex={0} className="text-warning rounded">partial scan</span>
                      </Tooltip>
                    </>
                  )}
                </div>
              )}

              {/* Le formulaire a changé depuis ce graphe : le dire, et offrir la relance sur place. */}
              {stale && (
                <Tooltip content="The criteria were edited after this run — this graph answers the previous ones.">
                <button
                  type="button"
                  onClick={handleSubmit}
                  className="flex items-center gap-1.5 rounded-full border border-warning/40 bg-warning/10 px-3 py-1.5 text-[11px] text-warning hover:bg-warning/20"
                >
                  <span aria-hidden="true" className="material-symbols-outlined text-[14px]">refresh</span>
                  Criteria changed — rerun
                </button>
                </Tooltip>
              )}
            </div>

            {/* Progression, erreur, annulation — au centre, dans le même flux que le reste. */}
            <div className="flex-1 flex justify-center min-w-0 pointer-events-auto">
              {loading ? (
                <div
                  className="w-[28rem] max-w-full bg-surface-container/95 border border-outline-variant rounded-lg px-3 py-2 shadow-xl"
                  role="status" aria-live="polite"
                >
                  <div className="flex items-center gap-2 text-[11px] text-on-surface-variant">
                    <span aria-hidden="true" className="material-symbols-outlined animate-spin text-[14px] text-primary">progress_activity</span>
                    <span className="truncate">{describeProgress(progress) || 'Starting the trace…'}</span>
                    <button
                      type="button"
                      onClick={cancelRun}
                      className="ml-auto shrink-0 text-[11px] font-medium text-on-surface-variant hover:text-error"
                    >
                      Stop
                    </button>
                  </div>
                  <div className="mt-1.5 h-1 rounded-full bg-primary/15 overflow-hidden">
                    <div
                      className="h-full bg-primary transition-[width] duration-300"
                      style={{ width: `${Math.max(progressRatio(progress) * 100, 2)}%` }}
                    />
                  </div>
                </div>
              ) : error ? (
                <div className="max-w-md bg-error/10 border border-error/20 text-error text-xs px-4 py-2 rounded-lg flex items-start gap-2" role="alert" title={error.raw}>
                  <span aria-hidden="true" className="material-symbols-outlined text-sm mt-0.5 shrink-0">warning</span>
                  <span className="min-w-0">
                    <span className="font-semibold">{error.title}</span>
                    {error.hint && <span className="block text-error/80 mt-0.5 leading-relaxed">{error.hint}</span>}
                  </span>
                </div>
              ) : notice ? (
                <div className="max-w-md bg-surface-container border border-outline-variant text-on-surface-variant text-xs px-4 py-2 rounded-lg" role="status">
                  {notice}
                </div>
              ) : null}
            </div>

            {/* Partage & export — sur un résultat figé : exporter une cible mouvante n'a pas de sens */}
            {hasResult && !loading && (
              <div className="shrink-0 flex items-center gap-1 pointer-events-auto">
                {/* Comparer deux traces : la question d'incident est « l'autre clé, elle, est
                    passée par où ? », et l'historique tient déjà les critères à rejouer. */}
                {(comparable.length > 0 || comparison) && (
                  <div className="relative">
                    <Button
                      size="sm" variant={comparison ? 'secondary' : 'ghost'} icon="compare_arrows"
                      loading={comparing}
                      onClick={() => (comparison ? setComparison(null) : setComparePickerOpen(o => !o))}
                      aria-expanded={comparePickerOpen}
                      title={comparison
                        ? 'Stop comparing and show this trace alone'
                        : 'Compare this trace with a recent one'}
                    >
                      {comparison ? 'Exit compare' : 'Compare'}
                    </Button>
                    {comparePickerOpen && !comparison && (
                      <ul
                        className="absolute right-0 top-full mt-1 z-30 w-72 max-h-64 overflow-y-auto rounded-lg border border-outline-variant bg-surface-container shadow-xl p-1"
                        aria-label="Compare with a recent trace"
                      >
                        {comparable.map(entry => (
                          <li key={entry.ranAt}>
                            <button
                              type="button"
                              onClick={() => void compareWith(entry)}
                              className="w-full text-left px-2 py-1.5 rounded hover:bg-surface-container-high"
                            >
                              <span className="block font-mono text-[11px] text-on-surface truncate">
                                {entry.messageKey}
                              </span>
                              <span className="block text-[10px] text-outline">
                                {entry.searchPath ? `${entry.searchPath} · ` : ''}
                                {entry.topicsFound} topic{entry.topicsFound === 1 ? '' : 's'} · {formatRelativeTime(entry.ranAt)}
                              </span>
                            </button>
                          </li>
                        ))}
                      </ul>
                    )}
                  </div>
                )}
                <Button size="sm" variant="ghost" icon="link" onClick={() => void copyLink()} aria-label="Copy a link that reruns this exact trace">
                  Link
                </Button>
                {hits.length > 0 && (
                  <>
                    <Button size="sm" variant="ghost" icon="download"
                      onClick={() => download(toCsv(HIT_EXPORT_COLUMNS, hitsToRows(hits)), 'text/csv', 'csv')}>
                      CSV
                    </Button>
                    <Button size="sm" variant="ghost" icon="download"
                      onClick={() => download(traceToJson(ranParams ?? currentParams(), flow), 'application/json', 'json')}
                      aria-label="Export as JSON: criterion, coverage, warnings and hops">
                      JSON
                    </Button>
                  </>
                )}
              </div>
            )}
          </div>

          {/* Zoom controls */}
          {hasResult && nodes.length > 0 && (
            <div className="absolute bottom-6 left-4 z-10 flex flex-col bg-background-dark border border-outline-variant rounded-xl overflow-hidden shadow-xl">
              <button type="button" aria-label="Zoom in"
                onClick={() => setTransform(t => ({ ...t, scale: clampScale(t.scale * 1.25) }))}
                className="p-2 hover:bg-surface-container-high text-on-surface-variant hover:text-on-surface border-b border-outline-variant/60 transition-colors">
                <span aria-hidden="true" className="material-symbols-outlined text-lg">add</span>
              </button>
              <button type="button" aria-label="Zoom out"
                onClick={() => setTransform(t => ({ ...t, scale: clampScale(t.scale * 0.8) }))}
                className="p-2 hover:bg-surface-container-high text-on-surface-variant hover:text-on-surface border-b border-outline-variant/60 transition-colors">
                <span aria-hidden="true" className="material-symbols-outlined text-lg">remove</span>
              </button>
              {/* Le raccourci clavier n'était annoncé que dans une infobulle de souris. */}
              <Tooltip content="Fits the graph to the view. Once the graph has focus: arrows pan, + and − zoom, 0 fits.">
                <button type="button" aria-label="Fit graph to view" onClick={fitView}
                  className="p-2 hover:bg-surface-container-high text-on-surface-variant hover:text-on-surface transition-colors">
                  <span aria-hidden="true" className="material-symbols-outlined text-lg">center_focus_weak</span>
                </button>
              </Tooltip>
            </div>
          )}

          {/* Légende — l'ambre et le rouge tiraient l'œil sans jamais dire ce qu'ils marquaient. */}
          {hasResult && nodes.length > 0 && (graphLegend || missingInB.size > 0) && (
            <div className="absolute bottom-6 left-20 z-10 flex flex-col gap-1 rounded-lg border border-outline-variant bg-surface-container/90 px-3 py-2 text-[10px] text-on-surface-variant">
              {missingInB.size > 0 && (
                <span className="flex items-center gap-1.5">
                  <span aria-hidden="true" className="inline-block h-3 w-5 rounded border border-dashed" style={{ borderColor: '#ffd479' }} />
                  not seen by {comparison?.params.messageKey}
                </span>
              )}
              {insight.slowestHopTopic && (
                <span className="flex items-center gap-1.5">
                  <span aria-hidden="true" className="inline-block h-[3px] w-5 rounded" style={{ background: '#ffd479' }} />
                  slowest hop
                </span>
              )}
              {insight.clockSkewTopics.length > 0 && (
                <span className="flex items-center gap-1.5">
                  <span aria-hidden="true" className="inline-block w-5 border-t-[3px] border-dashed" style={{ borderColor: '#f2b8b5' }} />
                  hop goes backwards (clock skew)
                </span>
              )}
            </div>
          )}

          {/* Empty state */}
          {!hasResult && !loading && (
            <div className="h-full flex items-center justify-center">
              <EmptyState
                icon="waves"
                title="No flow traced yet"
                description="Enter a message key and click Trace Flow to visualize the stream pipeline."
              />
            </div>
          )}

          {/* Loading — seulement tant qu'il n'y a rien à montrer ; sinon le graphe partiel prend le relais */}
          {loading && nodes.length === 0 && (
            <div className="h-full flex flex-col items-center justify-center gap-3">
              <p className="text-[13px] text-on-surface-variant">Tracing message across topics…</p>
              <p className="text-[11px] text-outline">
                Scanning up to {maxMessages} messages per topic. Hops appear as they are found.
              </p>
            </div>
          )}

          {/* SVG Graph with pan/zoom — visible dès le premier saut trouvé, trace en cours comprise */}
          {hasResult && nodes.length > 0 && (
            <svg
              ref={svgRef}
              className="w-full h-full select-none focus:outline-none focus-visible:ring-1 focus-visible:ring-primary/60"
              style={{ cursor: 'grab', touchAction: 'none' }}
              role="application"
              tabIndex={0}
              aria-label={`Message flow through ${nodes.map(n => n.label).join(', then ')}. Arrow keys pan, plus and minus zoom, 0 fits the graph.`}
              onPointerDown={onPointerDown}
              onPointerMove={onPointerMove}
              onPointerUp={onPointerUp}
              onPointerLeave={onPointerUp}
              onKeyDown={onGraphKeyDown}
              // Cliquer le fond désélectionne : sans cela, une ligne mise en avant le restait
              // jusqu'au clic sur un autre nœud.
              onClick={e => { if (e.target === e.currentTarget && !dragged.current) setSelectedTopic(null); }}
            >
              <defs>
                <marker id="sf-arrow" markerWidth="10" markerHeight="7" refX="9" refY="3.5" orient="auto">
                  <polygon points="0 0, 10 3.5, 0 7" fill="#a3adff" opacity="0.6" />
                </marker>
              </defs>

              <g transform={`translate(${transform.x}, ${transform.y}) scale(${transform.scale})`}>

                {/* Edges */}
                {edges.map((edge, i) => {
                  const s = layout.positions[edge.source];
                  const t = layout.positions[edge.target];
                  if (!s || !t) return null;
                  const { nodeW, nodeH } = layout;
                  const x1 = s.x + nodeW, y1 = s.y + nodeH / 2;
                  const x2 = t.x,          y2 = t.y + nodeH / 2;
                  const mx = (x1 + x2) / 2;
                  // Le saut le plus lent et un saut qui remonte le temps se lisent sur le graphe,
                  // pas seulement dans le tableau : c'est là que l'œil se pose en premier.
                  const slowest = insight.slowestHopTopic === edge.target;
                  const skewed = insight.clockSkewTopics.includes(edge.target);
                  const stroke = skewed ? '#f2b8b5' : slowest ? '#ffd479' : '#a3adff';
                  return (
                    <g key={`${edge.source}->${edge.target}-${i}`}>
                      <path
                        d={`M${x1},${y1} C${mx},${y1} ${mx},${y2} ${x2},${y2}`}
                        fill="none" stroke={stroke} strokeWidth={slowest || skewed ? 3 : 2}
                        strokeDasharray={skewed ? '6 4' : undefined}
                        opacity={slowest || skewed ? 0.9 : 0.5}
                        markerEnd="url(#sf-arrow)"
                      />
                      {edge.label && (
                        <text x={mx} y={Math.min(y1, y2) - 8} textAnchor="middle"
                          fill={slowest || skewed ? stroke : '#9aa3b2'} fontSize="10"
                          fontWeight={slowest || skewed ? 'bold' : undefined}>
                          {edge.label}{skewed ? ' ⚠' : ''}
                        </text>
                      )}
                    </g>
                  );
                })}

                {/* Nodes */}
                {nodes.map(node => {
                  const pos = layout.positions[node.id];
                  if (!pos) return null;
                  const { nodeW, nodeH } = layout;
                  const cx = nodeW / 2;
                  const ts = formatRelativeTime(node.timestamp);
                  const selected = selectedTopic === node.id;
                  const capped = hitByTopic.get(node.id)?.occurrencesCapped;
                  return (
                    <g
                      key={node.id}
                      transform={`translate(${pos.x}, ${pos.y})`}
                      style={{ cursor: 'pointer' }}
                      // Chaque saut est atteignable au clavier : la sélection ouvrait le détail
                      // d'un topic et n'existait qu'à la souris.
                      tabIndex={0}
                      role="button"
                      aria-pressed={selected}
                      aria-label={`${node.label}, ${node.hits ?? 0} match${node.hits === 1 ? '' : 'es'}`}
                      onClick={() => { if (!dragged.current) selectFromGraph(node.id); }}
                      onKeyDown={e => {
                        if (e.key !== 'Enter' && e.key !== ' ') return;
                        e.preventDefault();
                        e.stopPropagation();
                        selectFromGraph(node.id);
                      }}
                    >
                      <title>{`${node.label} — ${node.hits ?? 0}${capped ? '+' : ''} match(es), first seen ${formatAbsoluteTime(node.timestamp)}`
                        + (missingInB.has(node.id) ? ' — not seen by the compared trace' : '')}</title>
                      {/* En comparaison, un topic que l'autre clé n'a pas traversé est la
                          divergence elle-même : il se voit sur le graphe, pas seulement en table. */}
                      <rect width={nodeW} height={nodeH} rx="8"
                        fill={selected ? '#1b2030' : '#12151a'}
                        stroke={missingInB.has(node.id) ? '#ffd479' : '#a3adff'}
                        strokeDasharray={missingInB.has(node.id) ? '5 3' : undefined}
                        strokeWidth={selected ? 2.5 : 1.5} strokeOpacity={selected ? 1 : 0.6} />
                      <text x={cx} y={ts ? nodeH / 2 - 8 : nodeH / 2 + 4}
                        textAnchor="middle" fill="white" fontSize="11"
                        fontFamily="JetBrains Mono, monospace" fontWeight="bold">
                        {node.label.length > 20 ? node.label.slice(0, 19) + '…' : node.label}
                      </text>
                      {ts && (
                        <text x={cx} y={nodeH / 2 + 10} textAnchor="middle"
                          fill="#a3adff" fontSize="9" fontFamily="Inter, sans-serif" opacity="0.7">
                          {ts}{node.hits ? ` · ${node.hits}${capped ? '+' : ''} match${node.hits > 1 || capped ? 'es' : ''}` : ''}
                        </text>
                      )}
                    </g>
                  );
                })}
              </g>
            </svg>
          )}

          {/* No result — dit ce qui a été lu, sinon « introuvable » se confond avec « hors fenêtre » */}
          {hasResult && !loading && nodes.length === 0 && (
            <div className="h-full flex flex-col items-center justify-center gap-3 text-center p-12">
              <span aria-hidden="true" className="material-symbols-outlined text-5xl text-outline">search_off</span>
              <div className="max-w-lg">
                <p className="font-bold text-on-surface-variant">No flow found</p>
                <p className="text-sm text-outline mt-1">
                  The key was not in what was scanned{coverage ? ` — ${coverage}.` : '.'}
                </p>
                {/* Rien trouvé alors que des topics n'ont jamais été lus : la première réponse
                    n'est pas d'élargir le critère, c'est de finir le scan. */}
                {continuation && (
                  <div className="mt-4">
                    <Button variant="primary" icon="playlist_add" onClick={continueTrace}>
                      {describeContinuation(continuation)}
                    </Button>
                  </div>
                )}

                {/* Chaque piste est un bouton qui relance : le conseil « élargissez la fenêtre »
                    obligeait à remonter dans le formulaire pour l'appliquer. */}
                {suggestions.length > 0 && (
                  <div className="mt-4">
                    <p className="text-xs text-outline mb-2">Try again, wider:</p>
                    <div className="flex flex-wrap justify-center gap-2">
                      {suggestions.map(suggestion => (
                        <Button
                          key={suggestion.id}
                          size="sm"
                          variant="outline"
                          aria-label={`${suggestion.label}: ${suggestion.hint}`}
                          onClick={() => rerun(suggestion.params)}
                        >
                          {suggestion.label}
                        </Button>
                      ))}
                    </div>
                  </div>
                )}
              </div>
            </div>
          )}
        </div>

        {/* ── Coverage, warnings & evidence ── */}
        {hasResult && (
          <>
          {/* Séparateur réglable : le partage était figé à 45 %, si bien qu'une chaîne de quinze
              sauts défilait dans une lucarne pendant que quatre nœuds gardaient la moitié haute. */}
          {detailsOpen && (
            <div
              role="separator"
              aria-orientation="horizontal"
              aria-label="Resize the evidence panel"
              aria-valuenow={evidencePct}
              aria-valuemin={MIN_EVIDENCE_PCT}
              aria-valuemax={MAX_EVIDENCE_PCT}
              tabIndex={0}
              onPointerDown={onDividerPointerDown}
              onKeyDown={onDividerKeyDown}
              className="h-1.5 shrink-0 cursor-row-resize bg-outline-variant/40 hover:bg-primary/50 focus-visible:bg-primary/60 transition-colors"
              style={{ touchAction: 'none' }}
            />
          )}
          <section
            className="border-t border-outline-variant/60 bg-surface-container-low shrink-0 overflow-y-auto"
            style={detailsOpen ? { height: `${evidencePct}%` } : undefined}
          >
            <div className="flex items-center gap-3 px-4 py-2 sticky top-0 bg-surface-container-low z-10 border-b border-outline-variant/40">
              <button
                type="button"
                onClick={() => setDetailsOpen(o => !o)}
                aria-expanded={detailsOpen}
                className="flex items-center gap-1.5 text-[12px] font-semibold text-on-surface-variant hover:text-on-surface"
              >
                <span aria-hidden="true" className="material-symbols-outlined text-base">
                  {detailsOpen ? 'expand_more' : 'chevron_right'}
                </span>
                Matches ({hitFilter ? `${shownHits.length}/${hits.length}` : hits.length})
              </button>
              <span className="text-[11px] text-outline truncate" title={coverage}>{coverage}</span>
              <div className="ml-auto flex items-center gap-2 shrink-0">
                {/* Reprendre plutôt que tout relancer : les topics déjà lus ne le sont pas deux
                    fois, et le graphe précédent n'est pas jeté. */}
                {continuation && !loading && (
                  <Button size="sm" variant="outline" icon="playlist_add" onClick={continueTrace}
                    aria-label="Continue the trace: scans only the topics the budget never reached and merges the result into this graph">
                    {describeContinuation(continuation)}
                  </Button>
                )}
                {/* Un filtre dès qu'une trace ramène plus d'une poignée de sauts : sur un scan
                    de cluster, retrouver un topic se faisait à la molette. */}
                {detailsOpen && hits.length > 6 && (
                  <Input
                    value={hitFilter}
                    onChange={e => setHitFilter(e.target.value)}
                    placeholder="Filter topics…"
                    aria-label="Filter matched topics"
                    className="h-7 w-40 text-[11px]"
                  />
                )}
                {warnings.length > 0 && (
                  <Badge tone="warning">
                    {warnings.length} note{warnings.length > 1 ? 's' : ''}
                  </Badge>
                )}
              </div>
            </div>

            {detailsOpen && (
              <div className="p-4 space-y-4">
                {insightNotes.length > 0 && (
                  <p className="text-[11px] text-on-surface-variant flex flex-wrap items-center gap-x-1.5 gap-y-1">
                    <span aria-hidden="true" className="material-symbols-outlined text-[14px] text-primary">insights</span>
                    {insightNotes.map((note, i) => (
                      <React.Fragment key={note}>
                        {i > 0 && <span className="text-outline">·</span>}
                        <span>{note}</span>
                      </React.Fragment>
                    ))}
                  </p>
                )}

                {warnings.length > 0 && (
                  <ul className="space-y-1.5" aria-label="Scan warnings">
                    {warnings.map((warning, i) => (
                      <li key={i} className="flex items-start gap-2 text-[11px] text-warning leading-relaxed">
                        <span aria-hidden="true" className="material-symbols-outlined text-[14px] mt-0.5 shrink-0">info</span>
                        <span>{warning}</span>
                      </li>
                    ))}
                  </ul>
                )}

                {/* ── Comparaison de deux traces ── */}
                {diff && comparison && (
                  <div className="space-y-3">
                    <div className="flex flex-wrap items-center gap-x-3 gap-y-1 text-[11px]">
                      <span className="inline-flex items-center gap-1.5 text-on-surface-variant">
                        <span aria-hidden="true" className="material-symbols-outlined text-[14px] text-primary">compare_arrows</span>
                        <span className="font-mono text-on-surface">{ranParams?.messageKey}</span>
                        <span className="text-outline">(A) vs</span>
                        <span className="font-mono text-on-surface">{comparison.params.messageKey}</span>
                        <span className="text-outline">(B)</span>
                      </span>
                      <span className="text-outline">·</span>
                      <span className="text-on-surface-variant">{describeComparison(diff)}</span>
                    </div>

                    {/* Là où le temps est parti, quand les deux clés ont pris la même route. */}
                    {divergence && (
                      <p className="text-[11px] text-warning">
                        Biggest difference into <span className="font-mono">{divergence.topic}</span>:{' '}
                        {formatLatency(divergence.latencyA)} in A vs {formatLatency(divergence.latencyB)} in B
                        {' '}({formatLatency(divergence.deltaMs)}).
                      </p>
                    )}

                    <Table rowCount={diff.rows.length} scrollThreshold={12} maxBodyHeight="18rem">
                      <TableHead>
                        <tr>
                          <Th>Topic</Th>
                          <Th>In A</Th>
                          <Th>In B</Th>
                          <Th>Δ hop A</Th>
                          <Th>Δ hop B</Th>
                          <Th>Difference</Th>
                        </tr>
                      </TableHead>
                      <TableBody>
                        {diff.rows.map(row => (
                          <TableRow key={row.topic}>
                            <Td className="font-mono text-[12px]">{row.topic}</Td>
                            <Td>{row.hopA !== null ? `hop ${row.hopA}` : (
                              <Badge tone="warning">not seen</Badge>
                            )}</Td>
                            <Td>{row.hopB !== null ? `hop ${row.hopB}` : (
                              <Badge tone="warning">not seen</Badge>
                            )}</Td>
                            <Td className="whitespace-nowrap">{formatLatency(row.latencyA)}</Td>
                            <Td className="whitespace-nowrap">{formatLatency(row.latencyB)}</Td>
                            <Td className={`whitespace-nowrap ${row.deltaMs !== null && Math.abs(row.deltaMs) > 0 ? 'text-warning' : 'text-outline'}`}>
                              {row.deltaMs !== null ? formatLatency(row.deltaMs) : '—'}
                            </Td>
                          </TableRow>
                        ))}
                      </TableBody>
                    </Table>
                  </div>
                )}

                {hits.length > 0 && shownHits.length === 0 && (
                  <p className="text-[11px] text-outline">
                    No matched topic contains “{hitFilter}”.{' '}
                    <button type="button" className="text-primary hover:underline" onClick={() => setHitFilter('')}>
                      Clear the filter
                    </button>
                  </p>
                )}

                {shownHits.length > 0 && (
                  <Table rowCount={shownHits.length} scrollThreshold={12} maxBodyHeight="18rem">
                    <TableHead>
                      <tr>
                        {/* Colonnes triables : sur une chaîne longue, « le saut le plus lent » se
                            cherchait à l'œil. Le rang dans la chaîne, lui, ne bouge jamais. */}
                        {([
                          ['chain', '#'],
                          ['topic', 'Topic'],
                          ['occurrences', 'Matches'],
                          ['firstTimestamp', 'First seen'],
                          ['latency', 'Δ from previous'],
                        ] as [HitSortKey, string][]).map(([key, label]) => (
                          <Th key={key} aria-sort={sortKey === key ? (sortDesc ? 'descending' : 'ascending') : 'none'}>
                            <button
                              type="button"
                              onClick={() => toggleSort(key)}
                              className="inline-flex items-center gap-1 hover:text-on-surface uppercase tracking-[0.05em]"
                            >
                              {label}
                              <span aria-hidden="true" className="material-symbols-outlined text-[13px]">
                                {sortIcon(key)}
                              </span>
                            </button>
                          </Th>
                        ))}
                        <Th>Partition / Offset</Th>
                        <Th>Preview</Th>
                      </tr>
                    </TableHead>
                    <TableBody>
                      {shownHits.map((hit: FlowHit) => (
                        <TableRow
                          key={hit.topic}
                          id={`sf-hit-${hit.topic}`}
                          onClick={() => selectFromTable(hit.topic)}
                          className={selectedTopic === hit.topic ? 'bg-primary/10 cursor-pointer' : 'cursor-pointer'}
                        >
                          <Td className="text-outline">{hopNumber.get(hit.topic)}</Td>
                          <Td>
                            {/* Le lien emporte le critère : le Topic Explorer s'ouvre sur la même
                                recherche, au lieu de la faire ressaisir dans un autre formulaire. */}
                            <Link
                              to={{
                                pathname: `/topic/${encodeURIComponent(hit.topic)}`,
                                search: buildTopicSearchQuery(ranParams ?? currentParams()),
                              }}
                              className="font-mono text-[12px] text-primary hover:underline"
                              title="Open this topic with the same search prefilled"
                              onClick={e => e.stopPropagation()}
                            >
                              {hit.topic}
                            </Link>
                          </Td>
                          <Td>
                            {hit.occurrencesCapped ? (
                              <Tooltip content="More matches exist than the scan kept for this topic — the count is a floor, not a total.">
                                <span tabIndex={0} className="text-warning rounded">
                                  {hit.occurrences}+
                                </span>
                              </Tooltip>
                            ) : (
                              <span className={hit.occurrences > 1 ? 'text-warning' : ''}>
                                {hit.occurrences}
                              </span>
                            )}
                            {/* Une clé vue plusieurs fois peut être une reprise, une mise à jour
                                compactée ou un doublon : on montre l'étalement, on ne conclut pas. */}
                            {formatDwell(hit.lastTimestamp - hit.firstTimestamp) && (
                              <span className="text-outline"> over {formatDwell(hit.lastTimestamp - hit.firstTimestamp)}</span>
                            )}
                          </Td>
                          <Td title={formatAbsoluteTime(hit.firstTimestamp)}>
                            {formatRelativeTime(hit.firstTimestamp) || '—'}
                          </Td>
                          <Td className={`whitespace-nowrap ${hit.latencyFromPreviousMs === null ? 'text-outline' : ''}`}>
                            <span className="inline-flex items-center gap-1.5">
                              <span className={
                                insight.clockSkewTopics.includes(hit.topic) ? 'text-error'
                                  : insight.slowestHopTopic === hit.topic ? 'text-warning' : ''
                              }>
                                {formatLatency(hit.latencyFromPreviousMs)}
                              </span>
                              {insight.clockSkewTopics.includes(hit.topic) && (
                                <Tooltip content="This hop goes backwards in time: the producers' clocks disagree, so the chain order here is not evidence of anything.">
                                  <Badge tone="error" tabIndex={0}>clock skew</Badge>
                                </Tooltip>
                              )}
                              {insight.slowestHopTopic === hit.topic && (
                                <Tooltip content="The longest delay between two sightings in this chain.">
                                  <Badge tone="warning" tabIndex={0}>slowest hop</Badge>
                                </Tooltip>
                              )}
                            </span>
                          </Td>
                          <Td className="font-mono text-[11px] text-on-surface-variant">
                            {hit.firstPartition} / {hit.firstOffset}
                          </Td>
                          <Td className="font-mono text-[11px] text-on-surface-variant max-w-md truncate" title={hit.preview ?? ''}>
                            {hit.preview ?? '—'}
                          </Td>
                        </TableRow>
                      ))}
                    </TableBody>
                  </Table>
                )}

                {selectedTopic && hitByTopic.get(selectedTopic)?.firstKey && (
                  <p className="text-[11px] text-on-surface-variant">
                    Record key in <span className="font-mono">{selectedTopic}</span>:{' '}
                    <span className="font-mono text-on-surface">{hitByTopic.get(selectedTopic)?.firstKey}</span>
                  </p>
                )}
              </div>
            )}
          </section>
          </>
        )}
      </main>
    </div>
  );
};

export default StreamFlow;
