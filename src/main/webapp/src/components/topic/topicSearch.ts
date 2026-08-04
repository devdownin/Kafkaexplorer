/**
 * Logique pure de la recherche dans un topic : critère, requête, couverture, surlignage.
 *
 * Tout ce qui est testable sans DOM vit ici — `TopicSearchPanel` ne fait que rendre ce que ce
 * module décide, et `TopicExplorer` ne fait qu'enchaîner les passes.
 */

/** One Kafka record with its coordinates — what /api/topic returns and what a search hit is. */
export interface TopicMessage {
  partition: number;
  offset: number;
  timestamp: number;
  key: string | null;
  value: string | null;
  headers: Record<string, string | null>;
  valueBytes: number;
  truncated: boolean;
}

export type SearchMode = 'CONTAINS' | 'REGEX' | 'FIELD' | 'HEADER' | 'KEY';

/**
 * De quel bout du topic part le scan.
 *
 * `NEWEST` (défaut) lit les records les plus récents en remontant depuis la fin — c'est
 * `LAST_N` côté serveur. `OLDEST` part du début (ou du début de la fenêtre temporelle).
 * Le défaut n'est pas neutre : une recherche est presque toujours une enquête sur ce qui vient
 * de se passer, et partir du plus ancien dépensait tout le budget de scan sur l'historique le
 * plus vieux d'un topic qui en compte des millions.
 */
export type ScanDirection = 'NEWEST' | 'OLDEST';

export interface TopicSearchCriteria {
  mode: SearchMode;
  query: string;
  caseSensitive: boolean;
  searchKey: boolean;
  /**
   * Étend une recherche texte / regex aux valeurs des headers Kafka. Décoché par défaut :
   * l'activer d'office changerait ce que renvoie une recherche existante.
   */
  searchHeaders: boolean;
  /**
   * Mode KEY : ne lire que la partition que le partitionneur par défaut aurait choisie pour cette
   * clé. Divise le travail par le nombre de partitions, au prix de deux hypothèses invérifiables
   * (partitionneur par défaut, nombre de partitions inchangé) — d'où l'opt-in.
   */
  keyPartitioning: boolean;
  /** Chemin de champ en mode FIELD, nom du header en mode HEADER. */
  field: string;
  operator: string;
  value: string;
  /** Minutes to look back; 0 means "from the beginning of the topic". */
  sinceMinutes: number;
  direction: ScanDirection;
}

export interface TopicSearchResponse {
  hits: TopicMessage[];
  scanned: number;
  matched: number;
  elapsedMs: number;
  exhausted: boolean;
  stopReason: 'MAX_HITS' | 'MAX_SCAN' | 'TIMEOUT' | 'EXHAUSTED' | 'ERROR';
  nextCursor: Record<string, number>;
  warnings: string[];
}

export const OPERATORS: { value: string; label: string }[] = [
  { value: 'EQ', label: '=' },
  { value: 'NEQ', label: '≠' },
  { value: 'CONTAINS', label: 'contains' },
  { value: 'REGEX', label: 'matches regex' },
  { value: 'GT', label: '>' },
  { value: 'GTE', label: '≥' },
  { value: 'LT', label: '<' },
  { value: 'LTE', label: '≤' },
  { value: 'EXISTS', label: 'exists' },
];

export const SCOPES: { value: number; label: string }[] = [
  { value: 0, label: 'Whole topic' },
  { value: 15, label: 'Last 15 min' },
  { value: 60, label: 'Last hour' },
  { value: 1440, label: 'Last 24 h' },
];

export const DIRECTIONS: { value: ScanDirection; label: string }[] = [
  { value: 'NEWEST', label: 'Newest first' },
  { value: 'OLDEST', label: 'Oldest first' },
];

export const SEARCH_MODES: SearchMode[] = ['CONTAINS', 'REGEX', 'FIELD', 'HEADER', 'KEY'];

export const emptyCriteria: TopicSearchCriteria = {
  mode: 'CONTAINS',
  query: '',
  caseSensitive: false,
  searchKey: true,
  searchHeaders: false,
  keyPartitioning: false,
  field: '',
  operator: 'EQ',
  value: '',
  sinceMinutes: 0,
  direction: 'NEWEST',
};

/** FIELD et HEADER portent leur cible dans `field` ; KEY compare `value` sans cible. */
export const isFieldScoped = (mode: SearchMode): boolean => mode === 'FIELD' || mode === 'HEADER';
export const isValueScoped = (mode: SearchMode): boolean => isFieldScoped(mode) || mode === 'KEY';

/** `EXISTS` ne compare rien : c'est le seul opérateur qui n'attend pas de valeur. */
const needsValue = (operator: string): boolean => operator !== 'EXISTS';

/** Ce que le bouton « Search » accepte de lancer — et donc ce qu'une URL peut décrire. */
export function canRun(criteria: TopicSearchCriteria): boolean {
  if (isFieldScoped(criteria.mode)) {
    return criteria.field.trim().length > 0
      && (!needsValue(criteria.operator) || criteria.value.trim().length > 0);
  }
  if (criteria.mode === 'KEY') {
    return !needsValue(criteria.operator) || criteria.value.trim().length > 0;
  }
  return criteria.query.trim().length > 0;
}

/**
 * Le sélecteur de portée ne propose que les valeurs de `SCOPES` : une fenêtre arbitraire venue
 * de l'URL est arrondie à la plus petite portée qui la couvre, sinon le champ afficherait une
 * durée que la liste ne contient pas et la recherche partirait sur autre chose que l'affiché.
 */
export function snapScope(minutes: number): number {
  if (!Number.isFinite(minutes) || minutes <= 0) return 0;
  return SCOPES.map(s => s.value).filter(v => v > 0).find(v => v >= minutes) ?? 0;
}

/**
 * Critère de recherche porté par la query string — c'est ainsi qu'un saut de la page Stream Flow
 * ouvre le topic sur *sa* recherche, et ainsi qu'une recherche faite à la main se partage.
 *
 * Rend `null` dès que l'URL ne décrit pas une recherche exécutable : une navigation ordinaire
 * vers un topic ne doit rien déclencher.
 */
export function criteriaFromQuery(search: string): TopicSearchCriteria | null {
  const params = new URLSearchParams(search.startsWith('?') ? search.slice(1) : search);
  const mode = (params.get('mode') ?? '').toUpperCase() as SearchMode;
  if (!SEARCH_MODES.includes(mode)) return null;

  const operator = params.get('op') ?? '';
  const direction = (params.get('dir') ?? '').toUpperCase();
  const criteria: TopicSearchCriteria = {
    ...emptyCriteria,
    mode,
    query: (params.get('q') ?? '').trim(),
    field: (params.get('field') ?? '').trim(),
    operator: OPERATORS.some(o => o.value === operator) ? operator : emptyCriteria.operator,
    value: (params.get('value') ?? '').trim(),
    caseSensitive: params.get('case') === '1',
    searchKey: params.get('keys') !== '0',
    searchHeaders: params.get('headers') === '1',
    keyPartitioning: params.get('keyPartitioning') === '1',
    sinceMinutes: snapScope(Number(params.get('since'))),
    direction: direction === 'OLDEST' ? 'OLDEST' : emptyCriteria.direction,
  };
  // Mêmes conditions que le bouton « Search » du panneau : une cible sans valeur, ou une
  // recherche texte sans texte, n'aurait rien à exécuter.
  return canRun(criteria) ? criteria : null;
}

/**
 * L'inverse de `criteriaFromQuery`. Écrire l'URL après chaque recherche est ce qui rend une
 * recherche partageable et rejouable : le lien collé dans un ticket rouvre la même passe, au lieu
 * de laisser retranscrire le critère de mémoire.
 */
export function buildSearchQuery(criteria: TopicSearchCriteria): string {
  const params = new URLSearchParams();
  params.set('mode', criteria.mode);
  if (isFieldScoped(criteria.mode)) params.set('field', criteria.field.trim());
  if (isValueScoped(criteria.mode)) {
    params.set('op', criteria.operator);
    if (needsValue(criteria.operator)) params.set('value', criteria.value.trim());
  } else {
    params.set('q', criteria.query.trim());
    if (!criteria.searchKey) params.set('keys', '0');
    if (criteria.searchHeaders) params.set('headers', '1');
  }
  if (criteria.caseSensitive) params.set('case', '1');
  if (criteria.keyPartitioning) params.set('keyPartitioning', '1');
  if (criteria.sinceMinutes > 0) params.set('since', String(criteria.sinceMinutes));
  if (criteria.direction !== emptyCriteria.direction) params.set('dir', criteria.direction);
  return `?${params.toString()}`;
}

/** Une passe : soit la reprise du curseur précédent, soit un budget de scan élargi. */
export interface SearchPass {
  cursor?: Record<string, number> | null;
  maxScan?: number | null;
}

/** Corps envoyé à `POST /api/topic/{name}/search`. */
export function buildSearchBody(criteria: TopicSearchCriteria, pass: SearchPass = {}) {
  const windowed = criteria.sinceMinutes > 0;
  return {
    mode: criteria.mode,
    query: criteria.query,
    caseSensitive: criteria.caseSensitive,
    searchKey: criteria.searchKey,
    searchHeaders: criteria.searchHeaders,
    keyPartitioning: criteria.keyPartitioning,
    field: isFieldScoped(criteria.mode) ? criteria.field : null,
    operator: isValueScoped(criteria.mode) ? criteria.operator : null,
    value: isValueScoped(criteria.mode) ? criteria.value : null,
    // NEWEST → LAST_N : le serveur remonte de `maxScan` records depuis la fin, et une fenêtre
    // temporelle y *relève le plancher* au lieu de le remplacer. Partir du début de la fenêtre et
    // lire vers l'avant (ce que fait TIMESTAMP) dépense le budget sur les records les plus anciens
    // de la fenêtre, et rate ce qui vient d'arriver.
    from: criteria.direction === 'NEWEST' ? 'LAST_N' : windowed ? 'TIMESTAMP' : 'EARLIEST',
    sinceMinutes: windowed ? criteria.sinceMinutes : null,
    cursor: pass.cursor ?? null,
    maxScan: pass.maxScan ?? null,
  };
}

// ── Couverture ────────────────────────────────────────────────────────────

/**
 * Ce qui a réellement été lu, *toutes passes confondues*.
 *
 * Une réponse ne décrit que sa propre passe : afficher `response.scanned` après trois « continue »
 * annonçait 20 000 records lus quand il y en avait 60 000, alors que tout l'intérêt de cet écran
 * est de dire exactement ce qui a été couvert.
 */
export interface SearchCoverage {
  passes: number;
  scanned: number;
  matched: number;
  elapsedMs: number;
  exhausted: boolean;
  stopReason: string;
  /** Budget de la dernière passe ; absent = le budget par défaut du serveur. */
  scanBudget?: number;
}

/**
 * @param accumulate vrai pour une reprise au curseur (la passe lit du terrain neuf), faux pour une
 *   première recherche ou un élargissement, qui relisent depuis le même bout et remplacent donc
 *   ce qui précède au lieu de s'y ajouter.
 */
export function coverageOf(
  response: TopicSearchResponse,
  previous: SearchCoverage | null,
  accumulate: boolean,
  scanBudget?: number,
): SearchCoverage {
  const base = accumulate ? previous : null;
  return {
    passes: (base?.passes ?? 0) + 1,
    scanned: (base?.scanned ?? 0) + response.scanned,
    matched: (base?.matched ?? 0) + response.matched,
    elapsedMs: (base?.elapsedMs ?? 0) + response.elapsedMs,
    exhausted: response.exhausted,
    stopReason: response.stopReason,
    scanBudget,
  };
}

const STOP_REASONS: Record<string, string> = {
  MAX_HITS: 'hit limit reached',
  MAX_SCAN: 'scan budget reached',
  TIMEOUT: 'time budget reached',
  EXHAUSTED: 'whole range scanned',
  ERROR: 'search failed',
};

/**
 * Dit où le scan s'est arrêté. `exhausted` ne veut pas dire la même chose selon le bout par lequel
 * on est entré : en partant du plus ancien, plus rien ne reste ; en partant du plus récent, on a
 * lu jusqu'au dernier record mais tout ce qui précède la fenêtre lue n'a jamais été ouvert.
 */
export function describeCoverage(
  coverage: SearchCoverage,
  criteria: TopicSearchCriteria,
): string {
  if (coverage.stopReason === 'ERROR') return STOP_REASONS.ERROR;
  if (!coverage.exhausted) return STOP_REASONS[coverage.stopReason] ?? coverage.stopReason;
  if (criteria.direction === 'OLDEST') {
    return criteria.sinceMinutes > 0 ? 'Whole time range scanned' : 'Whole topic scanned';
  }
  const window = `Newest ${coverage.scanned.toLocaleString()} record${coverage.scanned === 1 ? '' : 's'} scanned`;
  return criteria.sinceMinutes > 0 ? `${window} in the time range` : window;
}

/** Le serveur borne un `maxScan` demandé à un million de records. */
export const MAX_SCAN_BUDGET = 1_000_000;

/**
 * Ce que « en lire plus » veut dire ici — deux gestes différents, jamais le même bouton :
 *
 * - `RESUME` reprend au curseur, donc *vers l'avant*. C'est le seul geste utile tant que le scan
 *   n'a pas atteint la fin de sa plage.
 * - `DEEPEN` relit depuis la fin avec un budget doublé, donc *plus loin en arrière*. Un curseur ne
 *   sait pas faire ça : il pointe après le dernier record lu, jamais avant le premier.
 */
export type ScanAction =
  | { kind: 'RESUME'; label: string; hint: string }
  | { kind: 'DEEPEN'; maxScan: number; label: string; hint: string };

export function nextScanAction(
  coverage: SearchCoverage,
  criteria: TopicSearchCriteria,
): ScanAction | null {
  if (coverage.stopReason === 'ERROR') return null;
  if (!coverage.exhausted) {
    return {
      kind: 'RESUME',
      label: 'Continue scanning',
      hint: criteria.direction === 'NEWEST'
        ? 'Reads on from where this pass stopped, towards the newest record.'
        : 'Reads on from where this pass stopped, towards the end of the topic.',
    };
  }
  // Plus rien devant : en partant du plus ancien, la plage entière a été lue.
  if (criteria.direction === 'OLDEST') return null;
  const current = Math.max(coverage.scanned, coverage.scanBudget ?? 0);
  if (current <= 0 || current >= MAX_SCAN_BUDGET) return null;
  const maxScan = Math.min(MAX_SCAN_BUDGET, current * 2);
  return {
    kind: 'DEEPEN',
    maxScan,
    label: `Scan further back (${maxScan.toLocaleString()})`,
    hint: `Re-reads the newest ${maxScan.toLocaleString()} records instead of `
      + `${current.toLocaleString()} — the older ones were never opened. Results are replaced.`,
  };
}

// ── Surlignage ────────────────────────────────────────────────────────────

/**
 * Ce qu'il faut marquer dans un hit pour montrer *pourquoi* il correspond. Le surlignage n'était
 * câblé qu'à la recherche texte : une recherche par champ, par header ou par clé rendait dix murs
 * de JSON sans rien y désigner.
 */
export type SearchHighlight =
  | { kind: 'NONE' }
  | { kind: 'TEXT'; needle: string; caseSensitive: boolean }
  | { kind: 'REGEX'; source: string; caseSensitive: boolean };

export const NO_HIGHLIGHT: SearchHighlight = { kind: 'NONE' };

export function highlightFor(criteria: TopicSearchCriteria): SearchHighlight {
  const caseSensitive = criteria.caseSensitive;
  if (criteria.mode === 'CONTAINS') {
    return criteria.query ? { kind: 'TEXT', needle: criteria.query, caseSensitive } : NO_HIGHLIGHT;
  }
  if (criteria.mode === 'REGEX') {
    return criteria.query ? { kind: 'REGEX', source: criteria.query, caseSensitive } : NO_HIGHLIGHT;
  }
  if (criteria.operator === 'REGEX') {
    return criteria.value ? { kind: 'REGEX', source: criteria.value, caseSensitive } : NO_HIGHLIGHT;
  }
  // NEQ a matché *parce que* la valeur diffère, GT/LT/EXISTS ne comparent aucun littéral :
  // marquer la valeur cherchée désignerait alors précisément ce qui n'est pas là.
  if (criteria.operator !== 'EQ' && criteria.operator !== 'CONTAINS') return NO_HIGHLIGHT;
  return criteria.value ? { kind: 'TEXT', needle: criteria.value, caseSensitive } : NO_HIGHLIGHT;
}

/** Le header dont la valeur a décidé du match, quand la recherche en vise un seul. */
export function highlightedHeader(criteria: TopicSearchCriteria): string | null {
  return criteria.mode === 'HEADER' && criteria.field.trim() ? criteria.field.trim() : null;
}

/** Vrai quand les headers font partie de la réponse et doivent donc être visibles sur le hit. */
export function revealsHeaders(criteria: TopicSearchCriteria): boolean {
  return criteria.mode === 'HEADER'
    || (criteria.searchHeaders && (criteria.mode === 'CONTAINS' || criteria.mode === 'REGEX'));
}

/**
 * Splits text around every occurrence of `needle`, so a caller can mark the matches: even
 * indexes are the text between matches, odd indexes are the matches themselves.
 */
export const splitOnMatches = (text: string, needle: string, caseSensitive: boolean): string[] => {
  if (!needle) return [text];
  const haystack = caseSensitive ? text : text.toLowerCase();
  const target = caseSensitive ? needle : needle.toLowerCase();
  const parts: string[] = [];
  let cursor = 0;
  for (;;) {
    const found = haystack.indexOf(target, cursor);
    if (found < 0) break;
    parts.push(text.slice(cursor, found), text.slice(found, found + target.length));
    cursor = found + target.length;
  }
  parts.push(text.slice(cursor));
  return parts;
};

/** Même découpage, pour un motif. Une expression invalide ne marque rien plutôt que de jeter. */
export const splitOnRegex = (text: string, source: string, caseSensitive: boolean): string[] => {
  if (!source) return [text];
  let pattern: RegExp;
  try {
    pattern = new RegExp(source, caseSensitive ? 'g' : 'gi');
  } catch {
    return [text];
  }
  const parts: string[] = [];
  let cursor = 0;
  for (let match = pattern.exec(text); match !== null; match = pattern.exec(text)) {
    if (match[0] === '') {
      // Une correspondance de largeur nulle laisse `lastIndex` sur place : la faire avancer à la
      // main est la seule façon de ne pas boucler indéfiniment sur un motif comme `a*`.
      pattern.lastIndex = match.index + 1;
      if (pattern.lastIndex > text.length) break;
      continue;
    }
    parts.push(text.slice(cursor, match.index), match[0]);
    cursor = match.index + match[0].length;
  }
  parts.push(text.slice(cursor));
  return parts;
};

/** Découpe un texte selon le surlignage courant : indices impairs = correspondances. */
export function splitForHighlight(text: string, highlight: SearchHighlight): string[] {
  switch (highlight.kind) {
    case 'TEXT':
      return splitOnMatches(text, highlight.needle, highlight.caseSensitive);
    case 'REGEX':
      return splitOnRegex(text, highlight.source, highlight.caseSensitive);
    default:
      return [text];
  }
}
