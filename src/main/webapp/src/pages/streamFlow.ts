/**
 * Logique pure de la page Stream Flow : validation du critère, lecture de la réponse
 * backend, mise en page du graphe et formatage.
 *
 * Isolée du composant pour être testable — la trace est une recherche bornée, et tout
 * ce qui décrit ses limites (fenêtre lue, topics ignorés, latences entre sauts) se
 * calcule ici plutôt que dans du JSX.
 */

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

export function describeSearchScope(path: string, searchHeaders: boolean): string {
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
  if (params.caseSensitive) query.set('case', '1');
  if (!params.searchHeaders) query.set('headers', '0');
  const encoded = query.toString();
  return encoded ? `?${encoded}` : '';
}

export const HISTORY_KEY = 'kse:flow-history';
const HISTORY_MAX = 10;

export interface TraceHistoryEntry extends TraceParams {
  /** Date de l'exécution (ms) — l'entrée la plus récente est en tête. */
  ranAt: number;
  /** Nombre de topics trouvés, pour distinguer deux traces voisines dans la liste. */
  topicsFound: number;
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
export function formatLatency(ms: number | null | undefined): string {
  if (ms === null || ms === undefined) return '—';
  if (ms < 0) return '—';
  if (ms < 1000) return `+${ms} ms`;
  if (ms < 60_000) return `+${(ms / 1000).toFixed(1)} s`;
  if (ms < 3_600_000) return `+${Math.floor(ms / 60_000)} min`;
  return `+${(ms / 3_600_000).toFixed(1)} h`;
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
