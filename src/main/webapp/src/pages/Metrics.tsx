// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors

import React, { useState, useEffect, useCallback, useMemo } from 'react';
import { useNavigate, useSearchParams } from 'react-router-dom';
import axios from 'axios';
import Editor, { useMonaco } from '@monaco-editor/react';
import '../monaco-setup';
import { useToast } from '../components/Toast';
import { useCatalog } from '../catalogStore';
import { describeApiError, type QueryErrorInfo } from './queryError';
import {
  // Recharts exporte lui aussi un `Tooltip` (celui des graphes) : le nôtre est aliasé pour que
  // le fichier dise lequel des deux il utilise à chaque endroit.
  Tooltip as InfoTooltip,
  PageHeader, Button, Checkbox, Stat, Select, EmptyState, CardSkeleton, TopicInput,
  Field, Input, Textarea, useConfirm,
  ErrorPanel,
} from '../components/ui';
import { describeQueryError } from './queryError';
import { clearDraft, readDraft, writeDraft } from '../draftStore';
// La forme vit dans api/types.ts, où check-api-types.py la résout contre le record Java —
// une interface écrite dans la page est exactement ce qui a divergé sans bruit ailleurs.
import type { AuditHistory, MetricConfig, MetricSuggestion, MetricSuggestions, MetricTestResponse, TableMetadata } from '../api/types';
import { hasRunningMetric } from './metricsHealth';
import { MetricsPulseBackdrop } from '../components/metrics/MetricsPulseBackdrop';
import { SuggestionsPanel } from '../components/metrics/SuggestionsPanel';
import { MetricCard } from '../components/metrics/MetricCard';
import { TemplateParamsEditor } from '../components/metrics/TemplateParamsEditor';
import { readFlowChains } from './flowChains';
import { latestProcessModel, modelRoute, readMetricPriorities } from './processModelEvidence';
import { highlightPriorities, newerAuditNote, suggestionToDraft } from './metricSuggestions';
import { queueMetricDraft, readQueueDraft } from './metricFromQueue';
import { describeMeasurement, describeMetricScope, describeRefreshCost, scopeNoteOf } from './metricScope';
// La logique pure de cette page vit dans `metricsEditor.ts` — déplacée, pas réécrite.
import {
  RAW_SQL,
  TYPE_EXAMPLES,
  TYPE_META,
  buildAutoName,
  buildDdlTemplate,
  extractTopicFromDdl,
  formatPreviewTimestamp,
  getSqlTemplates,
  getStatus,
  topicToTable,
  validateDdlSql,
  validateMetricName,
  validateMetricSql,
  validateTemplate,
  validateThresholds,
} from './metricsEditor';
import type { ValidationMsg } from './metricsEditor';

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
  const [searchParams, setSearchParams] = useSearchParams();
  const [metrics, setMetrics]           = useState<MetricConfig[]>([]);
  const [metadata, setMetadata]         = useState<TableMetadata>({});
  // Le catalogue partagé (alimenté par le sondage /api/dashboard de Layout) évite une
  // seconde requête vers le même endpoint et reste rafraîchi toutes les 30 s.
  const { topics, bootstrapServers: catalogBootstrap } = useCatalog();
  /*
   * L'adresse vient du catalogue que `Layout` alimente depuis son sondage de `/api/dashboard`,
   * et non d'un `GET /api/config` posé au montage pour ce seul champ — une requête de plus, et
   * une forme de réponse déclarée à la main au point d'appel, c'est-à-dire invisible pour
   * `check-api-types.py`. `localhost:9092` reste le repli tant que le premier sondage n'a pas
   * répondu : c'est un gabarit de DDL, pas une affirmation sur le cluster.
   */
  const bootstrapServers = catalogBootstrap || 'localhost:9092';
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
  const previewSummary = previewResult?.summary ?? null;
  const previewChips = useMemo(() => describeMetricScope(previewSummary), [previewSummary]);
  const previewMeasurement = useMemo(() => describeMeasurement(previewSummary), [previewSummary]);
  const previewNote = useMemo(() => scopeNoteOf(previewSummary), [previewSummary]);
  const [templates, setTemplates]       = useState<MetricTemplateDescriptor[]>([]);
  const [refreshingId, setRefreshingId] = useState<string | null>(null);
  /** La route de la mesure dont viennent les cartes affichées, pour situer le choix du modèle. */
  const [priorityRoute, setPriorityRoute] = useState<string | null>(null);
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
      const model = latestProcessModel();
      const res = await axios.post<MetricSuggestions>('/api/metrics/suggestions', {
        flowChains: readFlowChains(),
        // Le mapping validé par Process Mining vit dans le brouillon de cette page-là ; c'est lui
        // qui connaît la vraie clé de corrélation et le champ de statut de chaque topic.
        fieldMappingId: readDraft<string | null>('pm:mapping', null),
        // La mesure la plus fine dont dispose cette application sur un pipeline : un graphe de
        // successions avec des quantiles par transition, compté sur tous les enregistrements lus.
        // Une seule — deux fenêtres décriraient deux fois le même saut, et la déduplication
        // trancherait sur l'ordre d'arrivée plutôt que sur la qualité de la mesure.
        processModel: model,
      });
      setSuggestions(res.data);
      // Le choix du modèle n'a de sens qu'au-dessus des cartes issues de *cette* mesure : deux
      // pipelines produisent deux routes, et un avis sur l'un ne dit rien de l'autre.
      setPriorityRoute(model ? modelRoute(model) : null);
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
    axios.get<TableMetadata>('/api/metrics/metadata').then(r => setMetadata(r.data)).catch(() => { toast('Failed to load table metadata', 'error'); });
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

  /*
   * Une file d'échec ouvre l'éditeur pré-rempli, depuis `/dead-letter`.
   *
   * Par la query string, comme tout état partageable de cette application, et **une seule fois** :
   * les paramètres sont retirés de l'URL dès qu'ils ont été lus, sans quoi fermer la modale puis
   * recharger la page la rouvrirait, et l'écran serait impossible à quitter sans éditer l'adresse.
   * `replace` plutôt qu'un push, pour que « Précédent » remonte à la file et non à la même page
   * sans ses paramètres.
   *
   * Rien n'est créé : c'est la règle du panneau de propositions, et pour la même raison.
   */
  useEffect(() => {
    const draft = readQueueDraft(searchParams);
    if (!draft) return;
    const openFromUrl = () => {
      setEditingMetric({ ...EMPTY_METRIC, ...queueMetricDraft(draft) });
      setSelectedTopic(draft.queue);
      setNameIsAuto(false);
      setEditorTab('metric');
      setPreviewResult(null);
      setSaveError(null);
      setIsModalOpen(true);
      setSearchParams(params => {
        const next = new URLSearchParams(params);
        next.delete('fromQueue');
        next.delete('againstSource');
        return next;
      }, { replace: true });
    };
    /* Les affectations d'état vivent dans `openFromUrl` plutôt qu'au corps de l'effet : c'est
       ce que `react-hooks/set-state-in-effect` demande, et ça évite une exception à la règle. */
    openFromUrl();
  }, [searchParams, setSearchParams]);

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
  // Ce que cette configuration coûtera au broker, dit avant de l'enregistrer plutôt qu'après, par
  // une jauge de durée de cycle.
  const refreshCost = isTemplate ? describeRefreshCost(templateType, templateParams) : null;
  // Recalculé quand les cartes changent : un bandeau qui survivrait à une nouvelle dérivation
  // désignerait des cartes qui ne sont plus là.
  const priorityHighlight = useMemo(
    () => highlightPriorities(suggestions, readMetricPriorities(), priorityRoute),
    [suggestions, priorityRoute]);
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

  // L'ornement de fond ne s'allume que si la page a quelque chose à orner : au moins une
  // métrique qui a produit une valeur au dernier passage. `hasRunningMetric` porte la règle —
  // `counts.ok` juste au-dessus répond à une autre question (le verdict par seuil), et une
  // métrique peut être `critical` tout en tournant parfaitement.
  const anyMetricRunning = useMemo(() => hasRunningMetric(metrics), [metrics]);

  const SEVERITY_ORDER: Record<string, number> = { error: 0, critical: 1, warning: 2, ok: 3, pending: 4 };
  const filteredMetrics = metrics
    .filter(m => filterType === 'all' || m.type === filterType)
    .filter(m => filterStatus === 'all' || getStatus(m) === filterStatus)
    .sort((a, b) => SEVERITY_ORDER[getStatus(a)] - SEVERITY_ORDER[getStatus(b)]);

  return (
    // `relative` ancre le fond animé de la page, et rien de plus : surtout pas `isolate`, qui
    // ferait de cette page un contexte d'empilement et y enfermerait le modal « Add metric » —
    // il est `fixed z-50`, la Sidebar aussi, et la Sidebar est en dehors de cette page. Le modal
    // passerait sous la barre latérale. Le calque passe donc derrière le contenu par l'ordre du
    // DOM entre deux éléments positionnés `z-auto`, pas par un `-z-10` qui exigerait ce contexte.
    <div className="relative p-6 overflow-y-auto h-full">

      <MetricsPulseBackdrop active={anyMetricRunning} />

      {/* `space-y-6` vit ici plutôt que sur la racine : sur la racine, il donnerait une marge
          haute au calque et décalerait son `inset-0`. */}
      <div className="relative space-y-6">

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
        highlight={priorityHighlight}
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
          onClick={() => setIsModalOpen(false)}>
          {/* Le dialogue est la carte, pas le voile : `role` sur le conteneur `fixed inset-0`
              annonçait un dialogue de la taille du viewport, et rangeait le clic-pour-fermer
              *dans* le dialogue au lieu de l'extérieur. Le voile ne garde que ce clic.

              Le rôle est porté par cette enveloppe et non par le <form> : ARIA n'admet pas
              `dialog` sur un formulaire, et le formulaire garde le sien à l'intérieur. */}
          <div
            role="dialog" aria-modal="true" aria-labelledby="metric-editor-title"
            className="bg-surface-container border border-outline-variant rounded-2xl w-full max-w-5xl max-h-[92vh] overflow-hidden flex flex-col shadow-2xl"
            onClick={e => e.stopPropagation()}>
            {/* Un vrai <form> : Entrée depuis les champs du panneau de gauche enregistre.
                Tous les boutons internes déclarent type="button", seul « Save » soumet. */}
            <form
              noValidate
              onSubmit={e => { e.preventDefault(); if (!hasBlockingErrors) void handleSave(); }}
              className="flex flex-col flex-1 min-h-0">

              {/* Modal header */}
              <div className="flex items-center justify-between px-6 py-4 border-b border-outline-variant/60">
                <h2 id="metric-editor-title" className="text-[16px] font-semibold text-on-surface">
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
                          className="shrink-0 inline-flex items-center justify-center w-6 h-6 text-on-surface-variant hover:text-primary transition-colors"
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
                                  <Checkbox
                                    checked={checked}
                                    onChange={() => toggleLabelField(field)}
                                    className="mt-0.5"
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
                            invalid={thresholdValidation.some(v => v.level === 'error')}
                            aria-describedby={thresholdHints.length > 0 ? 'metric-threshold-hints' : undefined}
                            onChange={e => setEditingMetric(m => ({ ...m, warningThreshold: e.target.value ? parseFloat(e.target.value) : null }))}
                            className="bg-warning/5" />
                        )}
                      </Field>
                      <Field label="🔴 Critical">
                        {f => (
                          <Input {...f} type="number" inputMode="decimal"
                            value={editingMetric.criticalThreshold ?? ''}
                            invalid={thresholdValidation.some(v => v.level === 'error')}
                            aria-describedby={thresholdHints.length > 0 ? 'metric-threshold-hints' : undefined}
                            onChange={e => setEditingMetric(m => ({ ...m, criticalThreshold: e.target.value ? parseFloat(e.target.value) : null }))}
                            className="bg-error/5" />
                        )}
                      </Field>
                    </div>
                    {refreshCost && (
                      <p className="text-[10px] text-on-surface-variant flex items-start gap-1 pt-0.5">
                        <span aria-hidden="true" className="material-symbols-outlined text-[11px] shrink-0 mt-px">bolt</span>
                        {refreshCost}
                      </p>
                    )}
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
                        /* L'aperçu rendait `Object.entries(summary)` tel quel : la phrase entière
                           de `scopeNote` dans une puce de 10 px, et `warnings` par `String(v)`,
                           soit « a,b ». C'était aussi un *second* rendu de ce que
                           `describeMetricScope` fait déjà — deux réponses à une question, à un
                           écran de distance de la carte. */
                        <div className="space-y-1.5">
                          <span className="flex items-center gap-2">
                            <span className="material-symbols-outlined text-sm">check_circle</span>
                            {isTemplate ? 'value' : 'metric_value'} = <strong>{String(previewResult.value)}</strong>
                            {Array.isArray(previewResult.rows) && previewResult.rows.length > 1 && (
                              <span className="text-success ml-2">({previewResult.rows.length} rows total)</span>
                            )}
                          </span>
                          {previewMeasurement.length > 0 && (
                            <div className="flex flex-wrap gap-3 pt-0.5">
                              {previewMeasurement.map(part => (
                                <span key={part.label} title={part.detail} className="flex items-baseline gap-1">
                                  <span className="opacity-60">{part.label}</span>
                                  <strong>{part.value}</strong>
                                </span>
                              ))}
                            </div>
                          )}
                          {previewChips.length > 0 && (
                            <div className="flex flex-wrap gap-1.5 pt-0.5">
                              {previewChips.map(chip => (
                                <span key={chip.label} title={chip.detail}
                                  className={`px-2 py-0.5 rounded text-[10px] ${
                                    chip.tone === 'warning' ? 'bg-warning/10 text-warning' : 'bg-success/10 text-success'
                                  }`}>
                                  {chip.label}
                                </span>
                              ))}
                            </div>
                          )}
                          {previewNote && (
                            <p className="font-sans text-success/80 leading-relaxed pt-0.5">{previewNote}</p>
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
        </div>
      )}

      </div>
    </div>
  );
};

export default Metrics;
