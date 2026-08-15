// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors

/**
 * Logique pure de la page Stream Flow : validation du critère, lecture de la réponse
 * backend, mise en page du graphe et formatage.
 *
 * Isolée du composant pour être testable — la trace est une recherche bornée, et tout
 * ce qui décrit ses limites (fenêtre lue, topics ignorés, latences entre sauts) se
 * calcule ici plutôt que dans du JSX.
 */
import { clearDraft, readDraft, writeDraft } from '../draftStore';

export interface FlowNode {
  id: string;
  label: string;
  type?: string;
  /** Timestamp (ms) de la première occurrence dans ce topic. */
  timestamp?: number;
  /** Nombre d'occurrences trouvées dans la fenêtre scannée. */
  hits?: number;
}

export interface FlowEdge {
  source: string;
  target: string;
  label?: string;
}

export interface FlowHit {
  topic: string;
  occurrences: number;
  firstTimestamp: number;
  lastTimestamp: number;
  firstPartition: number;
  firstOffset: number;
  firstKey: string | null;
  preview: string | null;
  latencyFromPreviousMs: number | null;
  /** Le topic contient plus d'occurrences que le scan n'en a gardées : le compte est un plancher. */
  occurrencesCapped?: boolean;
}

export interface FlowStats {
  topicsInScope: number;
  topicsScanned: number;
  topicsSkipped: number;
  topicsFailed: number;
  /** Quels topics n'ont jamais été lus (borné côté serveur) — la portée exacte d'une reprise. */
  skippedTopics?: string[];
  failedTopics?: string[];
  messagesScanned: number;
  matches: number;
  durationMs: number;
  truncated: boolean;
  stopReason: string;
  maxMessagesPerTopic: number;
  timeLimitMinutes: number | null;
}

export interface ParsedFlow {
  nodes: FlowNode[];
  edges: FlowEdge[];
  hits: FlowHit[];
  stats: FlowStats | null;
  warnings: string[];
}

export type FormErrors = { messageKey?: string; searchPath?: string };

export const HEADER_PREFIX = 'header:';

/** Ce que le backend fera du chemin saisi — affiché sous le champ pour lever l'ambiguïté. */
export type SearchScope = 'ANY' | 'FIELD' | 'JSONPATH' | 'XPATH' | 'HEADER';

/**
 * Descente récursive et filtres dépassent le parcours en notation pointée : le backend bascule
 * alors sur le moteur JSONPath complet, qui exige un `$` en tête.
 */
export function needsFullJsonPath(path: string): boolean {
  return path.includes('..') || path.includes('[?');
}

export function searchScopeOf(path: string): SearchScope {
  const trimmed = path.trim();
  if (!trimmed) return 'ANY';
  if (trimmed.toLowerCase().startsWith(HEADER_PREFIX)) return 'HEADER';
  if (trimmed.startsWith('/')) return 'XPATH';
  return needsFullJsonPath(trimmed) ? 'JSONPATH' : 'FIELD';
}

export function describeSearchScope(path: string, searchHeaders: boolean, exactKey = false): string {
  if (exactKey) {
    return 'Compares the whole Kafka record key. Scans only that key\u2019s partition.';
  }
  switch (searchScopeOf(path)) {
    case 'HEADER':
      return `Compares the Kafka header "${path.trim().slice(HEADER_PREFIX.length).trim()}".`;
    case 'XPATH':
      return 'XPath over XML payloads. Only what the expression extracts is compared.';
    case 'JSONPATH':
      return 'Full JSONPath (recursive descent, filters) over JSON payloads.';
    case 'FIELD':
      return 'Dot-notation path over JSON or XML payloads. Only what the path extracts is compared.';
    default:
      return searchHeaders
        ? 'Matches the record key, the payload, and every header value.'
        : 'Matches the record key and the payload.';
  }
}

/**
 * Validation de surface du chemin de recherche, côté client.
 *
 * Un chemin invalide déclenchait un scan de tous les topics pour finir sur zéro résultat, sans
 * rien qui distingue « mauvaise syntaxe » de « clé absente ». Le backend rejette désormais un
 * chemin illisible avec son propre message ; cette passe évite l'aller-retour sur les fautes de
 * frappe manifestes. On ne réimplémente pas les grammaires.
 *
 * Un nom de champ nu (`orderId`, `order.items[].sku`) est **valide** : c'est la notation que
 * produit la liste de champs du Topic Explorer, et l'ancienne version la rejetait au motif
 * qu'elle n'était « ni un JSONPath ni un XPath ».
 */
export function validateSearchPath(path: string): string | undefined {
  const trimmed = path.trim();
  if (!trimmed) return undefined;
  const scope = searchScopeOf(trimmed);
  if (scope === 'HEADER') {
    return trimmed.slice(HEADER_PREFIX.length).trim() ? undefined : 'Missing header name after "header:".';
  }
  const brackets = [...trimmed].reduce((depth, char) => {
    if (char === '[') return depth + 1;
    if (char === ']') return depth - 1;
    return depth;
  }, 0);
  if (brackets !== 0) return 'Unbalanced brackets.';
  if (scope === 'XPATH') {
    return trimmed.includes('//') && !trimmed.startsWith('//')
      ? 'Empty path segment ("//").'
      : undefined;
  }
  // `..` (descente récursive) et `[?…]` (filtre) n'ont de sens qu'en JSONPath, qui commence par `$`.
  // Sans lui, le chemin serait lu comme une notation pointée et ne matcherait jamais rien.
  if (needsFullJsonPath(trimmed) && !trimmed.startsWith('$')) {
    return 'Recursive descent (..) and filters ([?…]) need a JSONPath — start the path with "$".';
  }
  return undefined;
}

function toNumber(value: unknown): number | undefined {
  if (typeof value === 'number' && Number.isFinite(value)) return value;
  if (typeof value === 'string' && value.trim()) {
    const parsed = Number(value);
    if (Number.isFinite(parsed)) return parsed;
  }
  return undefined;
}

/**
 * Lit la réponse de `/api/stream-flow`.
 *
 * Les nœuds et arêtes arrivent en maps de chaînes (`from` / `to` côté backend, `source` /
 * `target` côté rendu) : la conversion est centralisée ici, et un champ manquant donne un
 * nœud ignoré plutôt qu'un `undefined` qui casse la mise en page.
 */
export function parseFlowResponse(data: unknown): ParsedFlow {
  const payload = (data ?? {}) as {
    nodes?: Record<string, string>[];
    edges?: Record<string, string>[];
    hits?: FlowHit[];
    stats?: FlowStats;
    warnings?: string[];
  };

  const nodes: FlowNode[] = (payload.nodes ?? []).flatMap(raw => {
    const id = raw.id ?? raw.label;
    if (!id) return [];
    return [{
      id,
      label: raw.label ?? id,
      type: raw.type,
      timestamp: toNumber(raw.timestamp),
      hits: toNumber(raw.hits),
    }];
  });

  const known = new Set(nodes.map(n => n.id));
  const edges: FlowEdge[] = (payload.edges ?? []).flatMap(raw => {
    const source = raw.source ?? raw.from;
    const target = raw.target ?? raw.to;
    if (!source || !target || !known.has(source) || !known.has(target)) return [];
    return [{ source, target, label: raw.label || undefined }];
  });

  return {
    nodes,
    edges,
    hits: Array.isArray(payload.hits) ? payload.hits : [],
    stats: payload.stats ?? null,
    warnings: Array.isArray(payload.warnings) ? payload.warnings : [],
  };
}

/* ──────────────────────────────────────────────────────────────────────────
 * Flux d'événements (SSE)
 * ────────────────────────────────────────────────────────────────────────── */

export interface TraceProgress {
  topicsCompleted: number;
  topicsInScope: number;
  messagesScanned: number;
  matches: number;
  elapsedMs: number;
  lastTopic: string | null;
}

export interface SseEvent {
  event: string;
  data: string;
}

/**
 * Découpe un tampon SSE en événements complets et rend le reliquat.
 *
 * `EventSource` ferait ce travail, mais il ne parle que GET et se reconnecte tout seul : sur une
 * coupure réseau au milieu d'un scan, il relancerait silencieusement une trace de tout le cluster.
 * On lit donc la réponse d'un POST à la main — même corps de requête que l'appel non streamé, et
 * l'`AbortController` du bouton « Cancel » reste la seule façon d'arrêter.
 *
 * Un événement se termine par une ligne vide ; un `data:` peut tenir sur plusieurs lignes, elles se
 * recollent avec des sauts de ligne (spec SSE). Un bloc sans `data:` est ignoré — c'est ainsi que
 * s'écrit un commentaire de maintien de connexion.
 */
export function parseSseBuffer(buffer: string): { events: SseEvent[]; rest: string } {
  const events: SseEvent[] = [];
  const normalized = buffer.replace(/\r\n/g, '\n');
  const blocks = normalized.split('\n\n');
  // Le dernier bloc n'est complet que si le tampon se terminait par un séparateur.
  const rest = blocks.pop() ?? '';

  for (const block of blocks) {
    let event = 'message';
    const data: string[] = [];
    for (const line of block.split('\n')) {
      if (line.startsWith(':') || !line.trim()) continue;
      const separator = line.indexOf(':');
      const field = separator < 0 ? line : line.slice(0, separator);
      const value = separator < 0 ? '' : line.slice(separator + 1).replace(/^ /, '');
      if (field === 'event') event = value;
      else if (field === 'data') data.push(value);
    }
    if (data.length > 0) events.push({ event, data: data.join('\n') });
  }
  return { events, rest };
}

/** Progression telle qu'elle s'affiche : « 42/250 topics · 12 800 messages · 3 matches ». */
export function describeProgress(progress: TraceProgress | null): string {
  if (!progress) return '';
  const parts = [
    `${progress.topicsCompleted}/${progress.topicsInScope} topics`,
    `${progress.messagesScanned.toLocaleString()} messages`,
    `${progress.matches} match${progress.matches === 1 ? '' : 'es'}`,
  ];
  if (progress.lastTopic) parts.push(progress.lastTopic);
  return parts.join(' · ');
}

/** Part scannée, bornée à [0,1] — une barre de progression n'a pas à se fier au serveur. */
export function progressRatio(progress: TraceProgress | null): number {
  if (!progress || progress.topicsInScope <= 0) return 0;
  return Math.min(1, Math.max(0, progress.topicsCompleted / progress.topicsInScope));
}

/* ──────────────────────────────────────────────────────────────────────────
 * Paramètres de trace : URL partageable et historique local
 * ────────────────────────────────────────────────────────────────────────── */

export interface TraceParams {
  messageKey: string;
  searchPath: string;
  topics: string[];
  windowMode: 'recent' | 'window';
  timeLimitMinutes: number;
  maxMessages: number;
  useRegex: boolean;
  /**
   * La valeur cherchée *est* la clé du record, comparée à l'identique. Le scan peut alors ne lire
   * que la partition choisie par le partitionneur par défaut — d'où l'exclusion mutuelle avec un
   * chemin de recherche (la clé n'est pas dans le payload) et avec une regex (on compare tout).
   */
  exactKey: boolean;
  caseSensitive: boolean;
  searchHeaders: boolean;
}

export const DEFAULT_TRACE_PARAMS: TraceParams = {
  messageKey: '',
  searchPath: '',
  topics: [],
  windowMode: 'recent',
  timeLimitMinutes: 5,
  maxMessages: 100,
  useRegex: false,
  exactKey: false,
  caseSensitive: false,
  searchHeaders: true,
};

function boolParam(raw: string | null, fallback: boolean): boolean {
  if (raw === null) return fallback;
  return raw === '1' || raw.toLowerCase() === 'true';
}

function intParam(raw: string | null, fallback: number, min: number, max: number): number {
  const parsed = Number(raw);
  if (raw === null || !Number.isFinite(parsed)) return fallback;
  return Math.min(max, Math.max(min, Math.round(parsed)));
}

/**
 * Lit une trace depuis la query string.
 *
 * Une trace est une pièce à conviction : elle se colle dans un ticket d'incident, et le
 * destinataire doit rejouer exactement la même — d'où tous les paramètres du formulaire dans
 * l'URL, pas seulement la clé.
 */
export function parseTraceParams(search: string): TraceParams {
  const params = new URLSearchParams(search.startsWith('?') ? search.slice(1) : search);
  const rawWindow = params.get('window');
  // Une valeur illisible retombe sur le défaut plutôt que d'activer une fenêtre fantôme.
  const hasWindow = rawWindow !== null && rawWindow !== 'recent' && Number.isFinite(Number(rawWindow));
  return {
    messageKey: params.get('key') ?? '',
    searchPath: params.get('path') ?? '',
    topics: (params.get('topics') ?? '').split(',').map(t => t.trim()).filter(Boolean),
    windowMode: hasWindow ? 'window' : 'recent',
    timeLimitMinutes: intParam(rawWindow, DEFAULT_TRACE_PARAMS.timeLimitMinutes, 1, 1440),
    maxMessages: intParam(params.get('max'), DEFAULT_TRACE_PARAMS.maxMessages, 10, 1000),
    useRegex: boolParam(params.get('regex'), false),
    exactKey: boolParam(params.get('exact'), false),
    caseSensitive: boolParam(params.get('case'), false),
    searchHeaders: boolParam(params.get('headers'), true),
  };
}

/** Ne sérialise que ce qui s'écarte du défaut : un lien lisible plutôt qu'exhaustif. */
export function buildTraceQuery(params: TraceParams): string {
  const query = new URLSearchParams();
  if (params.messageKey) query.set('key', params.messageKey);
  if (params.searchPath) query.set('path', params.searchPath);
  if (params.topics.length > 0) query.set('topics', params.topics.join(','));
  if (params.windowMode === 'window') query.set('window', String(params.timeLimitMinutes));
  if (params.maxMessages !== DEFAULT_TRACE_PARAMS.maxMessages) query.set('max', String(params.maxMessages));
  if (params.useRegex) query.set('regex', '1');
  if (params.exactKey) query.set('exact', '1');
  if (params.caseSensitive) query.set('case', '1');
  if (!params.searchHeaders) query.set('headers', '0');
  const encoded = query.toString();
  return encoded ? `?${encoded}` : '';
}

/**
 * Deux critères désignent-ils la même trace ?
 *
 * Comparé sur la signature URL, donc avec les mêmes règles de défaut que le lien partagé : un
 * champ modifié dans le formulaire après coup rend le graphe affiché caduc, et l'écran doit le
 * dire — sans quoi on lit le résultat d'une recherche pour celui d'une autre.
 */
export function sameCriterion(a: TraceParams, b: TraceParams): boolean {
  return buildTraceQuery(a) === buildTraceQuery(b);
}

export const PANEL_KEY = 'kse:flow-panel';

/**
 * Le panneau de critères occupe un tiers de la largeur, et une trace se lit dans le graphe : son
 * état de repli suit l'utilisateur d'une visite à l'autre plutôt que de se rouvrir à chaque fois.
 */
export function readPanelOpen(fallback: boolean): boolean {
  try {
    const raw = localStorage.getItem(PANEL_KEY);
    // Une valeur inconnue retombe sur le défaut : un stockage abîmé ne doit pas décider de la mise en page.
    return raw === '1' ? true : raw === '0' ? false : fallback;
  } catch {
    return fallback;
  }
}

export function writePanelOpen(open: boolean): void {
  try {
    localStorage.setItem(PANEL_KEY, open ? '1' : '0');
  } catch {
    // Quota ou mode privé : le repli reste un confort, jamais un blocage.
  }
}

export const HISTORY_KEY = 'kse:flow-history';
const HISTORY_MAX = 10;

export interface TraceHistoryEntry extends TraceParams {
  /** Date de l'exécution (ms) — l'entrée la plus récente est en tête. */
  ranAt: number;
  /** Nombre de topics trouvés, pour distinguer deux traces voisines dans la liste. */
  topicsFound: number;
}

/**
 * Le critère **non lancé**, conservé d'une page à l'autre.
 *
 * L'URL porte une trace *exécutée* — c'est ce qui la rend partageable et rejouable. Ce qu'elle ne
 * porte pas, c'est un critère en cours de saisie qu'on quitte pour aller regarder un topic dans
 * l'explorateur : celui-là mourait avec la page. Une URL qui décrit déjà une trace l'emporte
 * toujours, sinon un lien partagé ouvrirait le formulaire de son destinataire.
 */
const PARAMS_DRAFT = 'stream-flow';

/** `true` quand le formulaire ne décrit aucune recherche — rien à garder, rien à restituer. */
export function isBlankTraceParams(params: TraceParams): boolean {
  return !params.messageKey.trim() && !params.searchPath.trim() && params.topics.length === 0;
}

export function readTraceParamsDraft(): TraceParams | null {
  const draft = readDraft<Partial<TraceParams> | null>(PARAMS_DRAFT, null);
  if (!draft) return null;
  // Complété sur le défaut : un brouillon d'une version antérieure n'a pas tous les champs.
  const params = { ...DEFAULT_TRACE_PARAMS, ...draft, topics: draft.topics ?? [] };
  return isBlankTraceParams(params) ? null : params;
}

export function saveTraceParamsDraft(params: TraceParams): void {
  if (isBlankTraceParams(params)) clearDraft(PARAMS_DRAFT);
  else writeDraft(PARAMS_DRAFT, params);
}

export function readTraceHistory(): TraceHistoryEntry[] {
  try {
    const raw = localStorage.getItem(HISTORY_KEY);
    const parsed = raw ? JSON.parse(raw) : [];
    return Array.isArray(parsed) ? parsed.filter(e => e && typeof e.messageKey === 'string') : [];
  } catch {
    // Quota, mode privé, JSON corrompu : l'historique est un confort, jamais un blocage.
    return [];
  }
}

/** Empile une trace en tête, dédoublonnée sur le critère (pas sur le résultat). */
export function pushTraceHistory(entry: TraceHistoryEntry): TraceHistoryEntry[] {
  const signature = buildTraceQuery(entry);
  const next = [entry, ...readTraceHistory().filter(e => buildTraceQuery(e) !== signature)]
    .slice(0, HISTORY_MAX);
  try {
    localStorage.setItem(HISTORY_KEY, JSON.stringify(next));
  } catch {
    // idem : on renvoie la liste calculée même si le stockage refuse.
  }
  return next;
}

/* ──────────────────────────────────────────────────────────────────────────
 * Saisie des topics cibles
 * ────────────────────────────────────────────────────────────────────────── */

/**
 * Découpe une saisie ou un collage en noms de topics.
 *
 * Les topics se choisissaient un par un, alors qu'un pipeline se connaît par listes — celle d'un
 * runbook, d'un ticket, d'une commande `kafka-topics --list`. Virgules, points-virgules, espaces
 * et sauts de ligne séparent donc tous, et les doublons tombent.
 */
export function parseTopicList(text: string): string[] {
  const seen = new Set<string>();
  return text
    .split(/[\s,;]+/)
    .map(entry => entry.trim())
    .filter(entry => entry.length > 0 && !seen.has(entry) && seen.add(entry));
}

/** `orders.*` → toutes les correspondances du catalogue. `*` seul est le seul caractère spécial. */
function patternToRegExp(pattern: string): RegExp {
  const escaped = pattern.replace(/[.+?^${}()|[\]\\]/g, '\\$&').replace(/\*/g, '.*');
  return new RegExp(`^${escaped}$`);
}

/**
 * Étend les motifs (`orders.*`) sur le catalogue déjà chargé, et **rend compte** de ceux qui ne
 * correspondent à rien : envoyer `orders.*` tel quel au serveur ne ferait qu'un scan d'un topic
 * qui n'existe pas, avec un avertissement à l'autre bout du voyage. Un nom sans `*` passe tel
 * quel — un topic peut exister avant que le catalogue (30 s de cache) ne le montre.
 */
export function expandTopicPatterns(
  entries: string[], known: string[],
): { topics: string[]; unmatched: string[] } {
  const topics: string[] = [];
  const unmatched: string[] = [];
  for (const entry of entries) {
    if (!entry.includes('*')) {
      topics.push(entry);
      continue;
    }
    const matcher = patternToRegExp(entry);
    const matches = known.filter(topic => matcher.test(topic));
    if (matches.length === 0) {
      unmatched.push(entry);
    } else {
      topics.push(...matches);
    }
  }
  const seen = new Set<string>();
  return { topics: topics.filter(t => !seen.has(t) && seen.add(t)), unmatched };
}

/* ──────────────────────────────────────────────────────────────────────────
 * Rebond vers le Topic Explorer
 * ────────────────────────────────────────────────────────────────────────── */

/**
 * Lien inverse : depuis un message du Topic Explorer, tracer sa clé à travers le cluster.
 *
 * La clé du record est connue exactement, donc la trace part en mode « clé exacte » — comparaison
 * entière et scan de la seule partition concernée, plutôt qu'une recherche de sous-chaîne qui
 * trouverait aussi les messages qui ne font que la mentionner.
 */
export function buildTraceLinkForKey(recordKey: string): string {
  return `/stream-flow${buildTraceQuery({
    ...DEFAULT_TRACE_PARAMS, messageKey: recordKey, exactKey: true,
  })}`;
}

/**
 * Query string ouvrant le Topic Explorer sur *la même* recherche.
 *
 * Depuis un saut, la question suivante est toujours « montre-moi le message » : sans ce lien il
 * fallait rouvrir le topic et ressaisir le critère à la main, dans un formulaire qui ne présente
 * pas les mêmes champs (mode, opérateur, cible) — une transcription à refaire à chaque saut, et
 * une occasion de chercher autre chose que ce que la trace a trouvé.
 *
 * Le Topic Explorer n'expose ni XPath ni JSONPath : dans ces deux cas le chemin est **abandonné**
 * au profit d'une recherche texte sur tout le payload. Elle ramène un sur-ensemble de ce que la
 * trace a vu, ce qui est honnête ; prétendre appliquer le chemin ne le serait pas.
 */
export function buildTopicSearchQuery(params: TraceParams): string {
  const query = new URLSearchParams();
  const key = params.messageKey.trim();
  const path = params.searchPath.trim();
  const operator = params.useRegex ? 'REGEX' : 'CONTAINS';
  const scope = searchScopeOf(path);

  if (params.exactKey) {
    query.set('mode', 'KEY');
    query.set('op', 'EQ');
    query.set('value', key);
    query.set('keyPartitioning', '1');
  } else if (scope === 'HEADER') {
    query.set('mode', 'HEADER');
    query.set('field', path.slice(HEADER_PREFIX.length).trim());
    query.set('op', operator);
    query.set('value', key);
  } else if (scope === 'FIELD') {
    query.set('mode', 'FIELD');
    query.set('field', path);
    query.set('op', operator);
    query.set('value', key);
  } else {
    query.set('mode', params.useRegex ? 'REGEX' : 'CONTAINS');
    query.set('q', key);
    if (params.searchHeaders) query.set('headers', '1');
  }
  if (params.caseSensitive) query.set('case', '1');
  // La fenêtre de la trace se transpose en « depuis N minutes » ; sans fenêtre, le topic entier.
  if (params.windowMode === 'window') query.set('since', String(params.timeLimitMinutes));
  return `?${query.toString()}`;
}

/* ──────────────────────────────────────────────────────────────────────────
 * Export
 * ────────────────────────────────────────────────────────────────────────── */

export const HIT_EXPORT_COLUMNS = [
  'hop', 'topic', 'occurrences', 'occurrencesCapped', 'firstSeen', 'lastSeen',
  'latencyFromPreviousMs', 'partition', 'offset', 'recordKey', 'preview',
];

/** Une ligne par saut, horodatages en ISO : lisible dans un tableur comme dans un ticket. */
export function hitsToRows(hits: FlowHit[]): Record<string, unknown>[] {
  return hits.map((hit, i) => ({
    hop: i + 1,
    topic: hit.topic,
    occurrences: hit.occurrences,
    occurrencesCapped: Boolean(hit.occurrencesCapped),
    firstSeen: formatAbsoluteTime(hit.firstTimestamp),
    lastSeen: formatAbsoluteTime(hit.lastTimestamp),
    latencyFromPreviousMs: hit.latencyFromPreviousMs ?? '',
    partition: hit.firstPartition,
    offset: hit.firstOffset,
    recordKey: hit.firstKey ?? '',
    preview: hit.preview ?? '',
  }));
}

/** Export JSON complet : le critère, la couverture et les avertissements, pas seulement les sauts. */
export function traceToJson(params: TraceParams, flow: ParsedFlow): string {
  return JSON.stringify({
    criterion: {
      messageKey: params.messageKey,
      searchPath: params.searchPath || null,
      useRegex: params.useRegex,
      exactKey: params.exactKey,
      caseSensitive: params.caseSensitive,
      searchHeaders: params.searchHeaders,
      targetTopics: params.topics,
    },
    stats: flow.stats,
    warnings: flow.warnings,
    hits: flow.hits,
  }, null, 2);
}

export interface FlowLayout {
  positions: Record<string, { x: number; y: number }>;
  nodeW: number;
  nodeH: number;
  /** Encombrement du graphe, utilisé pour cadrer la vue. */
  width: number;
  height: number;
}

export const NODE_W = 168;
export const NODE_H = 62;
const COL_GAP = 210;
const ROW_GAP = 84;

/**
 * Mise en page par couches (BFS depuis les nœuds sans entrant).
 *
 * Les nœuds jamais atteints — un topic isolé, sans arête — sont empilés dans une dernière
 * colonne au lieu d'occuper chacun la sienne : une trace où rien n'est chaîné produisait
 * autrefois un ruban horizontal de N colonnes vides.
 */
export function buildLayout(nodes: FlowNode[], edges: FlowEdge[]): FlowLayout {
  if (nodes.length === 0) {
    return { positions: {}, nodeW: NODE_W, nodeH: NODE_H, width: 0, height: 0 };
  }

  const outgoing = new Map<string, string[]>();
  const inDegree = new Map<string, number>();
  nodes.forEach(n => inDegree.set(n.id, 0));
  edges.forEach(e => {
    outgoing.set(e.source, [...(outgoing.get(e.source) ?? []), e.target]);
    inDegree.set(e.target, (inDegree.get(e.target) ?? 0) + 1);
  });

  const layers: string[][] = [];
  const visited = new Set<string>();
  let frontier = nodes.filter(n => (inDegree.get(n.id) ?? 0) === 0).map(n => n.id);
  if (frontier.length === 0) frontier = [nodes[0].id];

  while (frontier.length > 0) {
    const layer: string[] = [];
    const next: string[] = [];
    for (const id of frontier) {
      if (visited.has(id)) continue;
      visited.add(id);
      layer.push(id);
      for (const target of outgoing.get(id) ?? []) {
        if (!visited.has(target) && !next.includes(target)) next.push(target);
      }
    }
    if (layer.length > 0) layers.push(layer);
    frontier = next;
  }

  const orphans = nodes.filter(n => !visited.has(n.id)).map(n => n.id);
  if (orphans.length > 0) layers.push(orphans);

  const positions: Record<string, { x: number; y: number }> = {};
  const maxRows = Math.max(...layers.map(l => l.length), 1);
  const canvasH = maxRows * NODE_H + (maxRows - 1) * ROW_GAP;
  layers.forEach((layer, col) => {
    const totalH = layer.length * NODE_H + (layer.length - 1) * ROW_GAP;
    const startY = (canvasH - totalH) / 2;
    layer.forEach((id, row) => {
      positions[id] = { x: col * (NODE_W + COL_GAP), y: startY + row * (NODE_H + ROW_GAP) };
    });
  });

  return {
    positions,
    nodeW: NODE_W,
    nodeH: NODE_H,
    width: layers.length * NODE_W + Math.max(layers.length - 1, 0) * COL_GAP,
    height: canvasH,
  };
}

export interface Viewport {
  width: number;
  height: number;
}

export interface Transform {
  x: number;
  y: number;
  scale: number;
}

export const MIN_SCALE = 0.15;
export const MAX_SCALE = 4;

export function clampScale(scale: number): number {
  return Math.max(MIN_SCALE, Math.min(MAX_SCALE, scale));
}

/**
 * Cadre le graphe dans la vue. Le « reset » revenait à un `translate(40, 40) scale(1)` fixe :
 * sur une chaîne de sept topics, la moitié du graphe restait hors écran et le bouton
 * « recentrer » ne recentrait rien.
 */
export function fitTransform(layout: FlowLayout, viewport: Viewport, padding = 48): Transform {
  if (layout.width === 0 || layout.height === 0 || viewport.width === 0 || viewport.height === 0) {
    return { x: padding, y: padding, scale: 1 };
  }
  const scale = clampScale(Math.min(
    1,
    (viewport.width - padding * 2) / layout.width,
    (viewport.height - padding * 2) / layout.height,
  ));
  return {
    x: (viewport.width - layout.width * scale) / 2,
    y: (viewport.height - layout.height * scale) / 2,
    scale,
  };
}

/** Zoom ancré sur le pointeur : le point survolé reste sous le curseur. */
export function zoomAt(current: Transform, factor: number, px: number, py: number): Transform {
  const scale = clampScale(current.scale * factor);
  const ratio = scale / current.scale;
  return {
    scale,
    x: px - ratio * (px - current.x),
    y: py - ratio * (py - current.y),
  };
}

/** Un nœud est-il entièrement dans la vue au transform courant ? */
export function isNodeVisible(
  layout: FlowLayout, viewport: Viewport, transform: Transform, nodeId: string,
): boolean {
  const pos = layout.positions[nodeId];
  if (!pos) return false;
  const x = pos.x * transform.scale + transform.x;
  const y = pos.y * transform.scale + transform.y;
  return x >= 0 && y >= 0
    && x + layout.nodeW * transform.scale <= viewport.width
    && y + layout.nodeH * transform.scale <= viewport.height;
}

/**
 * Amène un nœud au centre de la vue **à échelle constante**.
 *
 * Sélectionner une ligne du tableau mettait le nœud en surbrillance là où il était — hors écran
 * sur une chaîne un peu longue, la sélection n'avait alors aucun effet visible. Recadrer tout le
 * graphe (`fitTransform`) répondrait à une autre question : on veut voir *ce* saut, pas l'ensemble.
 */
export function centerOn(
  layout: FlowLayout, viewport: Viewport, nodeId: string, scale: number,
): Transform | null {
  const pos = layout.positions[nodeId];
  if (!pos) return null;
  return {
    scale,
    x: viewport.width / 2 - (pos.x + layout.nodeW / 2) * scale,
    y: viewport.height / 2 - (pos.y + layout.nodeH / 2) * scale,
  };
}

/** Âge relatif d'un timestamp Kafka, ou heure absolue au-delà d'une journée. */
export function formatRelativeTime(ts: number | undefined, now = Date.now()): string {
  if (!ts || ts <= 0) return '';
  const diff = now - ts;
  if (diff < 0) return new Date(ts).toLocaleTimeString();
  if (diff < 60_000) return '< 1 min ago';
  if (diff < 3_600_000) return `${Math.floor(diff / 60_000)} min ago`;
  if (diff < 86_400_000) return `${Math.floor(diff / 3_600_000)}h ago`;
  return new Date(ts).toLocaleString(undefined, {
    month: 'short', day: '2-digit', hour: '2-digit', minute: '2-digit',
  });
}

/** Horodatage absolu à la milliseconde — mis en `title` des dates relatives. */
export function formatAbsoluteTime(ts: number | undefined): string {
  if (!ts || ts <= 0) return 'no broker timestamp';
  return new Date(ts).toISOString();
}

/** Latence d'un saut. `null` = premier topic de la chaîne. */
/**
 * Latence d'un saut. `null` = premier topic de la chaîne.
 *
 * Une valeur **négative** est rendue telle quelle : elle signale que les horloges des
 * producteurs divergent (les timestamps Kafka sont posés à la production). L'afficher en `—`
 * revenait à masquer la seule information qui explique un ordre de chaîne douteux.
 */
export function formatLatency(ms: number | null | undefined): string {
  if (ms === null || ms === undefined) return '—';
  const sign = ms < 0 ? '-' : '+';
  const abs = Math.abs(ms);
  if (abs < 1000) return `${sign}${abs} ms`;
  if (abs < 60_000) return `${sign}${(abs / 1000).toFixed(1)} s`;
  if (abs < 3_600_000) return `${sign}${Math.floor(abs / 60_000)} min`;
  return `${sign}${(abs / 3_600_000).toFixed(1)} h`;
}

export function formatDuration(ms: number): string {
  if (ms < 1000) return `${ms} ms`;
  if (ms < 60_000) return `${(ms / 1000).toFixed(1)} s`;
  return `${Math.floor(ms / 60_000)} min ${Math.round((ms % 60_000) / 1000)} s`;
}

/**
 * Phrase de couverture affichée sous le graphe : ce qui a été lu, et ce qui ne l'a pas été.
 * Une trace vide sans cette ligne se lit « la clé n'existe pas », alors qu'elle peut
 * simplement être hors de la fenêtre scannée.
 */
export function describeCoverage(stats: FlowStats | null): string {
  if (!stats) return '';
  const parts = [
    `${stats.topicsScanned}/${stats.topicsInScope} topic${stats.topicsInScope > 1 ? 's' : ''} scanned`,
    `${stats.messagesScanned.toLocaleString()} message${stats.messagesScanned > 1 ? 's' : ''} read`,
    stats.timeLimitMinutes
      ? `last ${stats.timeLimitMinutes} min window, up to ${stats.maxMessagesPerTopic}/topic`
      : `up to ${stats.maxMessagesPerTopic} most recent per topic`,
    formatDuration(stats.durationMs),
  ];
  if (stats.topicsSkipped > 0) parts.push(`${stats.topicsSkipped} skipped (time budget)`);
  if (stats.topicsFailed > 0) parts.push(`${stats.topicsFailed} unreadable`);
  return parts.join(' · ');
}

/**
 * Filtre du tableau de preuves : nom du topic, clé du record ou aperçu du payload.
 *
 * Une trace sur tout un cluster peut ramener quarante sauts ; sans filtre, retrouver celui qu'on
 * cherche se fait à la molette.
 */
export function filterHits(hits: FlowHit[], query: string): FlowHit[] {
  const needle = query.trim().toLowerCase();
  if (!needle) return hits;
  return hits.filter(hit =>
    hit.topic.toLowerCase().includes(needle)
    || (hit.firstKey ?? '').toLowerCase().includes(needle)
    || (hit.preview ?? '').toLowerCase().includes(needle));
}

/* ──────────────────────────────────────────────────────────────────────────
 * Reprise d'une trace interrompue
 * ────────────────────────────────────────────────────────────────────────── */

export interface TraceCoverage {
  topicsScanned: number;
  messagesScanned: number;
  matches: number;
  durationMs: number;
}

/** De quoi reprendre une trace là où son budget l'a arrêtée, sans repartir de zéro. */
export interface TraceContinuation {
  /** Les topics jamais lus — la portée de la seconde passe. */
  topics: string[];
  /** Ce que la première passe a trouvé : renvoyé au serveur, qui rend **un** graphe fusionné. */
  priorHits: FlowHit[];
  priorCoverage: TraceCoverage;
  /** Nombre total de topics jamais lus, dont `topics` n'est que la part nommée. */
  pending: number;
}

/**
 * Une trace arrêtée par le budget laissait comme seule issue de tout relancer, plus large : les
 * topics déjà lus l'étaient une seconde fois, et le graphe précédent était jeté. Le serveur nomme
 * désormais les topics jamais lus, ce qui suffit à ne rescanner que ceux-là et à fusionner.
 *
 * Rend `null` quand il n'y a rien à reprendre — une trace complète, ou une interruption si précoce
 * qu'aucun nom n'a été rapporté.
 */
export function buildContinuation(flow: ParsedFlow): TraceContinuation | null {
  const stats = flow.stats;
  if (!stats) return null;
  if (stats.stopReason !== 'TIME_BUDGET' && stats.stopReason !== 'CANCELLED') return null;
  const topics = (stats.skippedTopics ?? []).filter(Boolean);
  if (topics.length === 0) return null;
  return {
    topics,
    priorHits: flow.hits,
    priorCoverage: {
      topicsScanned: stats.topicsScanned,
      messagesScanned: stats.messagesScanned,
      matches: stats.matches,
      durationMs: stats.durationMs,
    },
    pending: Math.max(stats.topicsSkipped, topics.length),
  };
}

/**
 * Le libellé dit la portée réelle : le serveur borne la liste des noms, donc une reprise peut ne
 * couvrir qu'une partie des topics manquants — l'annoncer « les 412 topics » serait faux.
 */
export function describeContinuation(continuation: TraceContinuation): string {
  const { topics, pending } = continuation;
  return topics.length < pending
    ? `Continue on ${topics.length} of the ${pending} topics never read`
    : `Continue on the ${pending} topic${pending > 1 ? 's' : ''} never read`;
}

/* ──────────────────────────────────────────────────────────────────────────
 * Tri du tableau de preuves
 * ────────────────────────────────────────────────────────────────────────── */

export type HitSortKey = 'chain' | 'topic' | 'occurrences' | 'firstTimestamp' | 'latency';

/**
 * Trie les sauts sans jamais perdre leur rang dans la chaîne — celui-ci vient de `hopNumber`,
 * pas de la position dans le tableau. `chain` est l'ordre naturel (par première apparition) et
 * reste le défaut : c'est le sens même de la trace, et on n'y renonce que sur demande.
 *
 * Un saut sans latence (le premier de la chaîne) se range **toujours en dernier**, dans les deux
 * sens : demander « le saut le plus lent » et voir un tiret en tête ne répondrait pas à la question.
 */
export function sortHits(hits: FlowHit[], key: HitSortKey, desc: boolean): FlowHit[] {
  if (key === 'chain') return desc ? [...hits].reverse() : hits;
  const direction = desc ? -1 : 1;

  return [...hits].sort((a, b) => {
    if (key === 'topic') return a.topic.localeCompare(b.topic) * direction;

    const left = key === 'occurrences' ? a.occurrences
      : key === 'firstTimestamp' ? a.firstTimestamp
        : a.latencyFromPreviousMs;
    const right = key === 'occurrences' ? b.occurrences
      : key === 'firstTimestamp' ? b.firstTimestamp
        : b.latencyFromPreviousMs;

    const leftMissing = left === null || left === undefined;
    const rightMissing = right === null || right === undefined;
    if (leftMissing || rightMissing) {
      if (leftMissing && rightMissing) return a.topic.localeCompare(b.topic);
      return leftMissing ? 1 : -1;
    }
    // Égalité départagée par le nom : deux exécutions sur les mêmes données trient pareil.
    return left === right ? a.topic.localeCompare(b.topic) : (left - right) * direction;
  });
}

/* ──────────────────────────────────────────────────────────────────────────
 * Hauteur du panneau de preuves
 * ────────────────────────────────────────────────────────────────────────── */

export const EVIDENCE_KEY = 'kse:flow-evidence';
export const MIN_EVIDENCE_PCT = 15;
export const MAX_EVIDENCE_PCT = 75;
export const DEFAULT_EVIDENCE_PCT = 45;

/** Le graphe et le tableau ne doivent jamais pouvoir se réduire à rien l'un l'autre. */
export function clampEvidencePct(pct: number): number {
  if (!Number.isFinite(pct)) return DEFAULT_EVIDENCE_PCT;
  return Math.min(MAX_EVIDENCE_PCT, Math.max(MIN_EVIDENCE_PCT, Math.round(pct)));
}

/**
 * Le partage entre graphe et preuves était figé à 45 % : sur une chaîne de quinze sauts, le
 * tableau défilait dans une lucarne pendant que le graphe gardait la moitié de l'écran pour
 * quatre nœuds. Il se règle, et le réglage suit l'utilisateur.
 */
export function readEvidencePct(): number {
  try {
    const raw = localStorage.getItem(EVIDENCE_KEY);
    return raw === null ? DEFAULT_EVIDENCE_PCT : clampEvidencePct(Number(raw));
  } catch {
    return DEFAULT_EVIDENCE_PCT;
  }
}

export function writeEvidencePct(pct: number): void {
  try {
    localStorage.setItem(EVIDENCE_KEY, String(clampEvidencePct(pct)));
  } catch {
    // Confort d'affichage : un stockage indisponible ne bloque rien.
  }
}

/** Une relance proposée quand la trace n'a rien trouvé : un critère élargi, et ce qu'il change. */
export interface TraceSuggestion {
  id: string;
  label: string;
  /** Ce que la relance change, en clair : un bouton qui va rescanner le cluster doit s'annoncer. */
  hint: string;
  params: TraceParams;
}

/**
 * « Élargissez la fenêtre, montez le plafond, vérifiez le chemin » : le conseil était juste et
 * demandait quand même de remonter dans le formulaire, de retrouver le bon champ et de relancer.
 * Chaque piste devient un bouton qui relance la trace avec ce seul paramètre changé, de la plus
 * probable (le chemin ne s'applique pas au payload) à la plus coûteuse (relire tout le cluster).
 */
export function suggestWidenings(params: TraceParams): TraceSuggestion[] {
  const suggestions: TraceSuggestion[] = [];

  if (params.searchPath.trim() && !params.exactKey) {
    suggestions.push({
      id: 'drop-path',
      label: 'Search the whole payload',
      hint: `ignores the path ${params.searchPath.trim()}, which may not exist in these messages`,
      params: { ...params, searchPath: '' },
    });
  }
  if (!params.searchHeaders && !params.exactKey && !params.searchPath.trim()) {
    suggestions.push({
      id: 'headers',
      label: 'Search headers too',
      hint: 'correlation ids often travel only in a Kafka header',
      params: { ...params, searchHeaders: true },
    });
  }
  if (params.windowMode === 'window') {
    suggestions.push({
      id: 'drop-window',
      label: 'Drop the time window',
      hint: `reads the newest messages instead of the last ${params.timeLimitMinutes} min`,
      params: { ...params, windowMode: 'recent' },
    });
  }
  if (params.maxMessages < 1000) {
    suggestions.push({
      id: 'raise-cap',
      label: 'Read 1000 per topic',
      hint: `up from ${params.maxMessages} — a longer scan, deeper into each topic`,
      params: { ...params, maxMessages: 1000 },
    });
  }
  if (params.caseSensitive) {
    suggestions.push({
      id: 'ignore-case',
      label: 'Ignore case',
      hint: 'matches ORD-42 against ord-42',
      params: { ...params, caseSensitive: false },
    });
  }
  if (params.topics.length > 0) {
    suggestions.push({
      id: 'whole-cluster',
      label: 'Scan every topic',
      hint: `the key may travel outside the ${params.topics.length} topic${params.topics.length > 1 ? 's' : ''} named — a much longer scan`,
      params: { ...params, topics: [] },
    });
  }
  return suggestions.slice(0, 4);
}

/* ──────────────────────────────────────────────────────────────────────────
 * Comparaison de deux traces
 * ────────────────────────────────────────────────────────────────────────── */

export type ComparisonStatus = 'BOTH' | 'ONLY_A' | 'ONLY_B';

export interface FlowComparisonRow {
  topic: string;
  status: ComparisonStatus;
  /** Rang du saut dans chaque chaîne (1-based), null quand le topic manque de ce côté. */
  hopA: number | null;
  hopB: number | null;
  /** Latence depuis le saut précédent, dans chaque chaîne. */
  latencyA: number | null;
  latencyB: number | null;
  /** `latencyB - latencyA`, seulement quand les deux existent. */
  deltaMs: number | null;
}

export interface FlowComparison {
  rows: FlowComparisonRow[];
  shared: number;
  onlyA: number;
  onlyB: number;
  /** Les deux clés ont-elles emprunté exactement les mêmes topics, dans le même ordre ? */
  sameRoute: boolean;
  /** Durée bout en bout de chaque chaîne, quand elle en a une. */
  totalA: number | null;
  totalB: number | null;
}

/**
 * Compare deux traces : par où l'une est passée et pas l'autre, et où le temps est parti.
 *
 * « ORD-42 est arrivé, ORD-43 s'est perdu — où ? » est la question d'incident, et la seule façon
 * d'y répondre était jusqu'ici de garder deux onglets ouverts et de lire deux tableaux en
 * vis-à-vis. Ce sont les **latences de saut** qui sont comparées, jamais les horodatages absolus :
 * deux clés traitées à une heure d'écart n'ont rien à se dire dans l'absolu, alors que « ce saut a
 * pris 200 ms là et 4 minutes ici » est exactement le constat recherché.
 *
 * L'ordre des lignes suit la chaîne A, puis les topics que seule B a vus — pour lire la référence
 * d'abord et les écarts ensuite.
 */
export function compareFlows(a: FlowHit[], b: FlowHit[]): FlowComparison {
  const indexA = new Map(a.map((hit, i) => [hit.topic, { hit, hop: i + 1 }]));
  const indexB = new Map(b.map((hit, i) => [hit.topic, { hit, hop: i + 1 }]));

  const rows: FlowComparisonRow[] = a.map((hit, i) => {
    const other = indexB.get(hit.topic);
    const latencyA = hit.latencyFromPreviousMs ?? null;
    const latencyB = other ? other.hit.latencyFromPreviousMs ?? null : null;
    return {
      topic: hit.topic,
      status: other ? 'BOTH' : 'ONLY_A',
      hopA: i + 1,
      hopB: other ? other.hop : null,
      latencyA,
      latencyB,
      deltaMs: latencyA !== null && latencyB !== null ? latencyB - latencyA : null,
    };
  });

  for (const [i, hit] of b.entries()) {
    if (indexA.has(hit.topic)) continue;
    rows.push({
      topic: hit.topic,
      status: 'ONLY_B',
      hopA: null,
      hopB: i + 1,
      latencyA: null,
      latencyB: hit.latencyFromPreviousMs ?? null,
      deltaMs: null,
    });
  }

  const endToEnd = (hits: FlowHit[]) => (hits.length >= 2
    ? hits[hits.length - 1].firstTimestamp - hits[0].firstTimestamp
    : null);

  return {
    rows,
    shared: rows.filter(r => r.status === 'BOTH').length,
    onlyA: rows.filter(r => r.status === 'ONLY_A').length,
    onlyB: rows.filter(r => r.status === 'ONLY_B').length,
    // Même route = mêmes topics dans le même ordre. Deux chaînes vides ne se ressemblent pas :
    // elles ne disent rien, et l'annoncer « identiques » serait une conclusion sans données.
    sameRoute: a.length > 0 && a.length === b.length
      && a.every((hit, i) => hit.topic === b[i].topic),
    totalA: endToEnd(a),
    totalB: endToEnd(b),
  };
}

/** Le constat en une phrase, au-dessus du tableau de comparaison. */
export function describeComparison(comparison: FlowComparison): string {
  const { rows, shared, onlyA, onlyB, sameRoute, totalA, totalB } = comparison;
  if (rows.length === 0) return 'Neither trace found anything to compare.';

  const parts: string[] = [];
  parts.push(sameRoute
    ? `Same route through ${shared} topic${shared > 1 ? 's' : ''}`
    : `${shared} shared topic${shared === 1 ? '' : 's'}`);
  if (!sameRoute && onlyA > 0) parts.push(`${onlyA} only in A`);
  if (!sameRoute && onlyB > 0) parts.push(`${onlyB} only in B`);
  if (totalA !== null && totalB !== null) {
    const delta = totalB - totalA;
    parts.push(`end to end ${formatLatency(totalA).replace(/^\+/, '')} vs `
      + `${formatLatency(totalB).replace(/^\+/, '')} (${formatLatency(delta)})`);
  }
  return parts.join(' · ');
}

/**
 * Le saut partagé dont la latence s'écarte le plus entre les deux traces — là où le temps est
 * parti quand la route est la même. Rendu `null` en dessous d'un écart mesurable.
 */
export function slowestDivergence(comparison: FlowComparison): FlowComparisonRow | null {
  const measured = comparison.rows.filter(r => r.deltaMs !== null && r.deltaMs !== 0);
  if (measured.length === 0) return null;
  return measured.reduce((worst, row) =>
    (Math.abs(row.deltaMs ?? 0) > Math.abs(worst.deltaMs ?? 0) ? row : worst));
}

/* ──────────────────────────────────────────────────────────────────────────
 * Lecture de la chaîne : ce qui mérite le regard
 * ────────────────────────────────────────────────────────────────────────── */

/** Durée pendant laquelle la clé a continué d'apparaître dans un même topic. */
export function formatDwell(ms: number): string {
  if (ms <= 0) return '';
  return formatDuration(ms);
}

export interface ChainInsight {
  /** Du premier au dernier topic de la chaîne ; null en dessous de deux sauts. */
  totalElapsedMs: number | null;
  /** Topic d'arrivée du saut le plus long, et sa durée. Null s'il n'y a qu'un saut. */
  slowestHopTopic: string | null;
  slowestHopMs: number | null;
  /** Topics où la clé apparaît plusieurs fois — reprise, mise à jour compactée, doublon. */
  repeatedTopics: string[];
  /** Topics dont le saut entrant remonte le temps : horloges producteurs désaccordées. */
  clockSkewTopics: string[];
  /** Dernier topic de la chaîne : là où la trace s'arrête, dans la fenêtre scannée. */
  lastTopic: string | null;
}

/**
 * Ce que les sauts disent déjà mais que personne ne lit dans un tableau : le saut le plus lent,
 * les topics qui ont vu la clé plusieurs fois, ceux dont l'horloge diverge.
 *
 * Rien n'est déduit au-delà des données — en particulier, le dernier topic n'est pas présenté
 * comme un « cul-de-sac » : dans une chaîne complète, c'est simplement la destination.
 */
export function analyzeChain(hits: FlowHit[]): ChainInsight {
  const empty: ChainInsight = {
    totalElapsedMs: null, slowestHopTopic: null, slowestHopMs: null,
    repeatedTopics: [], clockSkewTopics: [], lastTopic: null,
  };
  if (hits.length === 0) return empty;

  const hops = hits.filter(h => h.latencyFromPreviousMs !== null && h.latencyFromPreviousMs !== undefined);
  // Le saut le plus lent n'a de sens qu'à partir de deux sauts : sur une chaîne à un seul saut,
  // « le plus lent » est aussi le seul, et le signaler ne dit rien.
  const positive = hops.filter(h => (h.latencyFromPreviousMs ?? 0) > 0);
  const slowest = hops.length >= 2 && positive.length > 0
    ? positive.reduce((max, h) => ((h.latencyFromPreviousMs ?? 0) > (max.latencyFromPreviousMs ?? 0) ? h : max))
    : null;

  return {
    totalElapsedMs: hits.length >= 2
      ? hits[hits.length - 1].firstTimestamp - hits[0].firstTimestamp
      : null,
    slowestHopTopic: slowest ? slowest.topic : null,
    slowestHopMs: slowest ? slowest.latencyFromPreviousMs ?? null : null,
    repeatedTopics: hits.filter(h => h.occurrences > 1 || h.occurrencesCapped).map(h => h.topic),
    clockSkewTopics: hits.filter(h => (h.latencyFromPreviousMs ?? 0) < 0).map(h => h.topic),
    lastTopic: hits[hits.length - 1].topic,
  };
}

/** Les mêmes constats, en phrases courtes destinées au bandeau au-dessus du tableau. */
export function describeChainInsight(insight: ChainInsight): string[] {
  const notes: string[] = [];
  if (insight.totalElapsedMs !== null) {
    notes.push(`End to end ${formatLatency(insight.totalElapsedMs).replace(/^\+/, '')}`);
  }
  if (insight.slowestHopTopic && insight.slowestHopMs !== null) {
    notes.push(`slowest hop into ${insight.slowestHopTopic} (${formatLatency(insight.slowestHopMs)})`);
  }
  if (insight.repeatedTopics.length > 0) {
    notes.push(`${insight.repeatedTopics.length} topic${insight.repeatedTopics.length > 1 ? 's' : ''} saw the key more than once`);
  }
  if (insight.clockSkewTopics.length > 0) {
    notes.push('clock skew between producers — the order is not reliable to the millisecond');
  }
  return notes;
}
