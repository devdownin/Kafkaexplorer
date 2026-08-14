// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors

/**
 * Logique pure de la recherche dans un topic : critère, requête, couverture, surlignage.
 *
 * Tout ce qui est testable sans DOM vit ici — `TopicSearchPanel` ne fait que rendre ce que ce
 * module décide, et `TopicExplorer` ne fait qu'enchaîner les passes.
 */
import { clearDraft, readDraft, writeDraft } from '../../draftStore';

/** One Kafka record with its coordinates — what /api/topic returns and what a search hit is. */
/*
 * La forme d'un enregistrement vit dans `api/types.ts`, adossée au record Java et vérifiée en CI.
 * Elle était déclarée ici, et une seconde fois — différemment — dans la page Compare : c'est cette
 * duplication qui a permis à l'une des deux de se périmer sans que rien ne le dise.
 */
import type { TopicMessage } from '../../api/types';

export type { TopicMessage };

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

/**
 * D'où part le scan.
 *
 * `RANGE` est le cas courant (tout le topic, ou une fenêtre relative). `TIMESTAMP` et `OFFSET`
 * existaient déjà côté serveur sans que rien ne puisse les demander : « à partir de 14h02 » et
 * « à partir de l'offset 128 000 » sont pourtant les deux formes que prend une question d'incident,
 * la seconde arrivant très souvent d'une alerte de lag qui donne déjà le numéro.
 */
export type StartMode = 'RANGE' | 'TIMESTAMP' | 'OFFSET';

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
  /**
   * Partitions à lire ; vide = toutes. On arrive très souvent sur un topic avec un numéro de
   * partition déjà en main (une alerte de lag, un consumer bloqué), et lire une partition sur
   * vingt-quatre divise le travail par vingt-quatre sans rien supposer — contrairement au
   * partitionnement par clé, qui parie sur le partitionneur.
   */
  partitions: number[];
  /** Records que la passe a le droit de lire ; 0 = le budget par défaut du serveur. */
  maxScan: number;
  /** Hits que la passe a le droit de rapporter ; 0 = le plafond par défaut du serveur (100). */
  maxHits: number;
  startMode: StartMode;
  /** Instant de départ en mode TIMESTAMP, tel que le rend un `<input type="datetime-local">`. */
  fromTime: string;
  /** Offset de départ en mode OFFSET, gardé en chaîne pour ne pas coercer pendant la saisie. */
  fromOffset: string;
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

/** `>` `≥` `<` `≤` comparent des nombres : le serveur rejette une valeur non numérique. */
const NUMERIC_OPERATORS = ['GT', 'GTE', 'LT', 'LTE'];

/**
 * Ce que chaque mode compare réellement.
 *
 * Cinq boutons nommés « Text / Regex / Field / Header / Key » disent où l'on cherche, pas ce que
 * cela implique — qu'une recherche par champ ne s'applique qu'à un payload structuré, ou qu'une
 * recherche par clé est une comparaison entière et non une sous-chaîne. C'est exactement ce qui
 * fait choisir le mauvais mode et lire un zéro sans comprendre pourquoi.
 */
export const MODE_HELP: Record<SearchMode, string> = {
  CONTAINS: 'Looks for your text anywhere in the record — no parsing, so it works on any payload, '
    + 'structured or not.',
  REGEX: 'Matches the record against a regular expression. It is compiled here before the scan '
    + 'starts, so a broken pattern is reported instead of scanned for.',
  FIELD: 'Compares one field of a JSON or XML payload, by dot path. Records the path cannot be '
    + 'applied to are counted and reported — not silently treated as misses.',
  HEADER: 'Compares one named Kafka header. A correlation id very often travels there and nowhere '
    + 'else, so a payload-only search would never find it.',
  KEY: 'Compares the record key itself, not the payload. The whole key is compared, unlike a text '
    + 'search that would also match a record merely mentioning it.',
};

export const describeMode = (mode: SearchMode): string => MODE_HELP[mode];

/** Ce que chaque opérateur fait, y compris quand il ne matche jamais. */
export const OPERATOR_HELP: Record<string, string> = {
  EQ: 'The value is exactly what you type.',
  NEQ: 'The value differs from what you type. A record without the field does not match.',
  CONTAINS: 'The value contains what you type, anywhere inside it.',
  REGEX: 'The value matches the pattern.',
  GT: 'Numeric comparison: greater than. A value that is not a number never matches.',
  GTE: 'Numeric comparison: greater than or equal. A value that is not a number never matches.',
  LT: 'Numeric comparison: less than. A value that is not a number never matches.',
  LTE: 'Numeric comparison: less than or equal. A value that is not a number never matches.',
  EXISTS: 'The field is present, whatever it holds — nothing is compared.',
};

export const describeOperator = (operator: string): string =>
  OPERATOR_HELP[operator] ?? 'Compares the value.';

/**
 * Pourquoi le scan s'est arrêté, et ce que cela laisse faire. Le bandeau donne un état
 * (« hit limit reached ») ; ce qu'il faut savoir, c'est ce que le bouton d'à côté fera.
 */
export const STOP_REASON_HELP: Record<string, string> = {
  MAX_HITS: 'The pass stopped once it had gathered its cap of matches. Continuing reads on past '
    + 'the records it already scanned, so the matches it skipped there need a higher cap, not a '
    + 'longer scan.',
  MAX_SCAN: 'The pass read as many records as its budget allowed. There may be more matches '
    + 'beyond — continue, or raise the scan budget.',
  TIMEOUT: 'The pass ran out of time before running out of records. Continuing picks up exactly '
    + 'where it stopped.',
  EXHAUSTED: 'Every record in the range was read. Nothing was skipped inside it.',
  ERROR: 'The scan failed; the message above is the server’s own reason.',
};

export const describeStopReason = (stopReason: string): string =>
  STOP_REASON_HELP[stopReason] ?? 'The scan stopped.';

/**
 * Les opérateurs qu'un mode peut réellement porter.
 *
 * `EXISTS` disparaît en mode KEY : côté serveur il répond vrai sans rien comparer, donc une
 * recherche par clé « exists » ramène tous les records du scan. Un critère qui ne filtre rien
 * n'est pas une recherche.
 */
export function operatorsFor(mode: SearchMode): { value: string; label: string }[] {
  return mode === 'KEY' ? OPERATORS.filter(op => op.value !== 'EXISTS') : OPERATORS;
}

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

/**
 * Budgets de scan proposés. Décider une fois de dépenser 100 000 records vaut mieux que de
 * cliquer dix fois sur « continue » — et le serveur borne de toute façon à un million.
 */
export const SCAN_BUDGETS: { value: number; label: string }[] = [
  { value: 0, label: 'Default budget' },
  { value: 50_000, label: 'Scan 50 000' },
  { value: 100_000, label: 'Scan 100 000' },
  { value: 250_000, label: 'Scan 250 000' },
];

/**
 * Plafonds de hits proposés. Le serveur borne à mille : au-delà, une réponse cesse d'être
 * quelque chose qu'on lit et devient quelque chose qu'on exporte.
 */
export const HIT_CAPS: { value: number; label: string }[] = [
  { value: 0, label: 'Up to 100 hits' },
  { value: 250, label: 'Up to 250 hits' },
  { value: 500, label: 'Up to 500 hits' },
  { value: 1000, label: 'Up to 1 000 hits' },
];

export const START_MODES: { value: StartMode; label: string }[] = [
  { value: 'RANGE', label: 'Time range' },
  { value: 'TIMESTAMP', label: 'From a date' },
  { value: 'OFFSET', label: 'From an offset' },
];

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
  partitions: [],
  maxScan: 0,
  maxHits: 0,
  startMode: 'RANGE',
  fromTime: '',
  fromOffset: '',
};

/** L'instant de départ, en millisecondes ; `null` quand le mode n'en demande pas ou qu'il est illisible. */
export function startTimestamp(criteria: TopicSearchCriteria): number | null {
  if (criteria.startMode !== 'TIMESTAMP' || !criteria.fromTime.trim()) return null;
  const parsed = Date.parse(criteria.fromTime);
  return Number.isFinite(parsed) ? parsed : null;
}

/** L'offset de départ ; `null` quand le mode n'en demande pas ou qu'il n'est pas un entier. */
export function startOffset(criteria: TopicSearchCriteria): number | null {
  if (criteria.startMode !== 'OFFSET') return null;
  const parsed = Number(criteria.fromOffset.trim());
  return criteria.fromOffset.trim() && Number.isInteger(parsed) && parsed >= 0 ? parsed : null;
}

/**
 * Un départ par offset est une lecture vers l'avant : le sens de scan ne s'y applique pas, et le
 * proposer quand même laisserait choisir une option que la requête n'emporte pas.
 */
export const directionApplies = (criteria: TopicSearchCriteria): boolean =>
  criteria.startMode !== 'OFFSET';

/** FIELD et HEADER portent leur cible dans `field` ; KEY compare `value` sans cible. */
export const isFieldScoped = (mode: SearchMode): boolean => mode === 'FIELD' || mode === 'HEADER';
export const isValueScoped = (mode: SearchMode): boolean => isFieldScoped(mode) || mode === 'KEY';

/** `EXISTS` ne compare rien : c'est le seul opérateur qui n'attend pas de valeur. */
const needsValue = (operator: string): boolean => operator !== 'EXISTS';

/** Ce qu'une URL doit au minimum décrire pour valoir recherche : une cible et de quoi comparer. */
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

/** Le partitionnement par clé n'a de sens que sur une comparaison exacte, et sans choix manuel. */
export function keyPartitioningApplies(criteria: TopicSearchCriteria): boolean {
  return criteria.mode === 'KEY'
    && criteria.operator === 'EQ'
    && criteria.partitions.length === 0;
}

export type SearchField = 'query' | 'field' | 'value' | 'fromTime' | 'fromOffset';
export type SearchErrors = Partial<Record<SearchField, string>>;

/** L'ordre de focalisation : le premier champ en faute reçoit le curseur après validation. */
export const SEARCH_FIELD_ORDER: SearchField[] =
  ['field', 'value', 'query', 'fromTime', 'fromOffset'];

function regexError(source: string): string | undefined {
  try {
    new RegExp(source);
    return undefined;
  } catch (e) {
    return `Invalid regular expression: ${e instanceof Error ? e.message : 'unparseable'}`;
  }
}

/**
 * Valide *tous* les champs d'un coup, pour que le formulaire puisse afficher chaque faute à sa
 * place et focaliser la première. Le bouton « Search » était simplement désactivé : en mode Field,
 * tant qu'aucun champ n'était choisi, l'écran restait gris et muet sur ce qu'il attendait.
 *
 * La regex est compilée ici, sur le fil de saisie : un motif invalide n'a pas à faire un
 * aller-retour serveur pour être signalé.
 */
export function validateCriteria(criteria: TopicSearchCriteria, now = Date.now()): SearchErrors {
  const errors: SearchErrors = {};
  const value = criteria.value.trim();

  if (criteria.startMode === 'TIMESTAMP') {
    const timestamp = startTimestamp(criteria);
    if (timestamp === null) {
      errors.fromTime = 'Enter the date and time to start from.';
    } else if (timestamp > now) {
      // Le broker ne rend aucun offset pour un instant futur : le plancher tomberait en silence
      // et le scan lirait les records les plus récents, en prétendant partir de cette date.
      errors.fromTime = 'That instant is in the future — nothing was written then.';
    }
  }
  if (criteria.startMode === 'OFFSET' && startOffset(criteria) === null) {
    errors.fromOffset = 'Enter the offset to start from — a whole number, 0 or more.';
  }

  if (criteria.mode === 'CONTAINS' || criteria.mode === 'REGEX') {
    if (!criteria.query.trim()) {
      errors.query = criteria.mode === 'REGEX'
        ? 'Enter a regular expression to search for.'
        : 'Enter the text to search for.';
    } else if (criteria.mode === 'REGEX') {
      errors.query = regexError(criteria.query);
    }
    return prune(errors);
  }

  if (isFieldScoped(criteria.mode) && !criteria.field.trim()) {
    errors.field = criteria.mode === 'HEADER'
      ? 'Enter the header name to compare.'
      : 'Choose the field to compare.';
  }
  if (needsValue(criteria.operator)) {
    if (!value) {
      errors.value = criteria.mode === 'KEY'
        ? 'Enter the record key to compare.'
        : 'Enter the value to compare.';
    } else if (criteria.operator === 'REGEX') {
      errors.value = regexError(value);
    } else if (NUMERIC_OPERATORS.includes(criteria.operator) && !Number.isFinite(Number(value))) {
      // Le serveur compare des nombres et rejette le reste : une valeur non numérique ne
      // matcherait jamais, en silence.
      errors.value = 'This operator compares numbers — enter a numeric value.';
    }
  }
  return prune(errors);
}

function prune(errors: SearchErrors): SearchErrors {
  for (const key of Object.keys(errors) as SearchField[]) {
    if (!errors[key]) delete errors[key];
  }
  return errors;
}

export const firstErrorField = (errors: SearchErrors): SearchField | null =>
  SEARCH_FIELD_ORDER.find(field => errors[field]) ?? null;

/** Un instant en valeur d'`<input type="datetime-local">`, c'est-à-dire en heure locale. */
export function toDateTimeLocal(ms: number): string {
  const local = new Date(ms - new Date(ms).getTimezoneOffset() * 60_000);
  return local.toISOString().slice(0, 16);
}

/**
 * Change le point de départ du scan, en portant la fenêtre relative dans le champ absolu : passer
 * de « Last hour » à « From a date » sur un champ vide obligerait sinon à retrouver à la main
 * l'heure qu'il était il y a une heure.
 */
export function switchStart(
  criteria: TopicSearchCriteria,
  startMode: StartMode,
  now = Date.now(),
): TopicSearchCriteria {
  if (startMode === criteria.startMode) return criteria;
  const next: TopicSearchCriteria = { ...criteria, startMode };
  if (startMode === 'TIMESTAMP' && !next.fromTime.trim() && criteria.sinceMinutes > 0) {
    next.fromTime = toDateTimeLocal(now - criteria.sinceMinutes * 60_000);
  }
  return next;
}

/**
 * Reporte la saisie en cours dans le champ que le nouveau mode utilise réellement.
 *
 * On tape un identifiant dans la barre de texte, on comprend qu'il faut chercher dans un champ,
 * on bascule — et la saisie restait dans `query`, ignorée par la recherche qui allait partir.
 */
export function switchMode(criteria: TopicSearchCriteria, mode: SearchMode): TopicSearchCriteria {
  if (mode === criteria.mode) return criteria;
  const carried = (isValueScoped(criteria.mode) ? criteria.value : criteria.query).trim();
  const next: TopicSearchCriteria = { ...criteria, mode };
  if (isValueScoped(mode)) {
    if (!next.value.trim()) next.value = carried;
  } else if (!next.query.trim()) {
    next.query = carried;
  }
  // `EXISTS` n'existe pas en mode KEY : y basculer laisserait un opérateur que la liste ne
  // propose pas, donc un formulaire qui affiche autre chose que ce qu'il exécuterait.
  if (!operatorsFor(mode).some(op => op.value === next.operator)) {
    next.operator = emptyCriteria.operator;
  }
  return next;
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
  const start = (params.get('start') ?? '').toUpperCase() as StartMode;
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
    partitions: parsePartitionList(params.get('parts')),
    maxScan: SCAN_BUDGETS.some(b => b.value === Number(params.get('scan')))
      ? Number(params.get('scan'))
      : emptyCriteria.maxScan,
    maxHits: HIT_CAPS.some(c => c.value === Number(params.get('hits')))
      ? Number(params.get('hits'))
      : emptyCriteria.maxHits,
    startMode: START_MODES.some(m => m.value === start) ? start : emptyCriteria.startMode,
    fromTime: (params.get('at') ?? '').trim(),
    fromOffset: (params.get('off') ?? '').trim(),
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
  if (criteria.partitions.length > 0) params.set('parts', criteria.partitions.join(','));
  if (criteria.maxScan > 0) params.set('scan', String(criteria.maxScan));
  if (criteria.maxHits > 0) params.set('hits', String(criteria.maxHits));
  if (criteria.startMode !== emptyCriteria.startMode) {
    params.set('start', criteria.startMode);
    if (criteria.startMode === 'TIMESTAMP') params.set('at', criteria.fromTime.trim());
    if (criteria.startMode === 'OFFSET') params.set('off', criteria.fromOffset.trim());
  }
  return `?${params.toString()}`;
}

/** `parts=0,3,7` — les entiers, dédoublonnés et triés ; le reste est ignoré. */
function parsePartitionList(raw: string | null): number[] {
  if (!raw) return [];
  const seen = new Set<number>();
  for (const piece of raw.split(',')) {
    const partition = Number(piece.trim());
    if (Number.isInteger(partition) && partition >= 0) seen.add(partition);
  }
  return [...seen].sort((a, b) => a - b);
}

/** Une passe : soit la reprise du curseur précédent, soit un budget de scan élargi. */
export interface SearchPass {
  cursor?: Record<string, number> | null;
  maxScan?: number | null;
}

/**
 * Le budget réellement demandé au serveur : celui de l'élargissement s'il y en a un, sinon celui
 * du formulaire, sinon rien (le serveur applique le sien). La couverture enregistre ce même
 * chiffre, faute de quoi l'élargissement suivant doublerait à partir d'une autre base.
 */
export function effectiveScanBudget(
  criteria: TopicSearchCriteria,
  pass: SearchPass = {},
): number | undefined {
  if (pass.maxScan) return pass.maxScan;
  return criteria.maxScan > 0 ? criteria.maxScan : undefined;
}

/** Corps envoyé à `POST /api/topic/{name}/search`. */
export function buildSearchBody(criteria: TopicSearchCriteria, pass: SearchPass = {}) {
  const offset = startOffset(criteria);
  const timestamp = startTimestamp(criteria);
  // Une fenêtre relative n'a de sens que dans le mode qui la porte, et le serveur préfère de
  // toute façon `fromTimestamp` à `sinceMinutes` quand les deux arrivent.
  const windowed = criteria.startMode === 'RANGE' && criteria.sinceMinutes > 0;
  // NEWEST → LAST_N : le serveur remonte de `maxScan` records depuis la fin, et un instant y
  // *relève le plancher* au lieu de le remplacer — qu'il vienne d'une fenêtre relative ou d'une
  // date choisie. Partir du début de la fenêtre et lire vers l'avant (ce que fait TIMESTAMP)
  // dépense le budget sur les records les plus anciens, et rate ce qui vient d'arriver. Un départ
  // par offset, lui, est une lecture vers l'avant : il l'emporte sur le sens de scan.
  const from = offset !== null
    ? 'OFFSET'
    : criteria.direction === 'NEWEST'
      ? 'LAST_N'
      : (windowed || timestamp !== null) ? 'TIMESTAMP' : 'EARLIEST';
  return {
    from,
    fromOffset: offset,
    fromTimestamp: timestamp,
    sinceMinutes: windowed ? criteria.sinceMinutes : null,
    maxHits: criteria.maxHits > 0 ? criteria.maxHits : null,
    mode: criteria.mode,
    query: criteria.query,
    caseSensitive: criteria.caseSensitive,
    searchKey: criteria.searchKey,
    searchHeaders: criteria.searchHeaders,
    keyPartitioning: criteria.keyPartitioning,
    field: isFieldScoped(criteria.mode) ? criteria.field : null,
    operator: isValueScoped(criteria.mode) ? criteria.operator : null,
    value: isValueScoped(criteria.mode) ? criteria.value : null,
    partitions: criteria.partitions.length > 0 ? criteria.partitions : null,
    cursor: pass.cursor ?? null,
    maxScan: effectiveScanBudget(criteria, pass) ?? null,
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

/**
 * Les avertissements de toutes les passes, dédoublonnés dans leur ordre d'apparition.
 *
 * N'afficher que ceux de la dernière réponse effaçait, à la reprise, un avertissement qui
 * décrivait toujours une partie des résultats affichés — « aucun des 50 records scannés n'est du
 * JSON » disparaissait dès que la passe suivante ne rencontrait pas le cas. Même règle que la
 * couverture : une reprise ajoute, une relecture remplace.
 */
export function mergeWarnings(
  previous: string[],
  incoming: string[],
  accumulate: boolean,
): string[] {
  const merged = accumulate ? [...previous, ...incoming] : [...incoming];
  return [...new Set(merged)];
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
  // Un départ choisi se lit toujours vers l'avant : « tout le topic » serait faux, ce qui précède
  // le point de départ n'a jamais été ouvert.
  if (criteria.startMode === 'OFFSET') {
    return `Read to the end from offset ${criteria.fromOffset.trim()}`;
  }
  if (criteria.startMode === 'TIMESTAMP') {
    return 'Read to the end from that date';
  }
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
  const readsBackFromTheEnd = directionApplies(criteria) && criteria.direction === 'NEWEST';
  if (!coverage.exhausted) {
    return {
      kind: 'RESUME',
      label: 'Continue scanning',
      hint: readsBackFromTheEnd
        ? 'Reads on from where this pass stopped, towards the newest record.'
        : 'Reads on from where this pass stopped, towards the end of the topic.',
    };
  }
  // Plus rien devant : en lisant vers l'avant, la plage entière a été lue. Élargir le budget ne
  // ferait que relire la même chose, puisque le point de départ, lui, ne bouge pas.
  if (!readsBackFromTheEnd) return null;
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

/** Le plafond de hits que le serveur borne à mille. */
export const MAX_HIT_CAP = 1_000;
/** Le plafond appliqué quand la requête n'en demande pas (`explorer.search-max-hits`). */
const DEFAULT_HIT_CAP = 100;

/**
 * Relever le plafond et relancer.
 *
 * Une passe arrêtée sur `MAX_HITS` affiche « 12 of 340 matches » : reprendre au curseur repart
 * *après* la zone déjà lue, donc les 328 restants de cette zone-là sont hors d'atteinte pour
 * toujours. C'est le seul geste qui les ramène, et il relit — donc il remplace.
 */
export function raiseHitCapAction(
  coverage: SearchCoverage,
  criteria: TopicSearchCriteria,
): { maxHits: number; label: string; hint: string } | null {
  if (coverage.stopReason !== 'MAX_HITS') return null;
  const current = criteria.maxHits > 0 ? criteria.maxHits : DEFAULT_HIT_CAP;
  if (current >= MAX_HIT_CAP) return null;
  const maxHits = Math.min(MAX_HIT_CAP, current * 4);
  return {
    maxHits,
    label: `Show up to ${maxHits.toLocaleString()} hits`,
    hint: `The pass stopped at ${current.toLocaleString()} hits, so the matches it skipped in the `
      + 'records it had already read cannot be reached by continuing — that reads on past them. '
      + 'This rereads from the same end with a higher cap, and replaces the results.',
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

/**
 * Le chemin comparé, en notation pointée, quand il désigne un nœud unique de l'arbre — de quoi
 * marquer le champ lui-même dans la vue structurée, et pas seulement sa valeur dans la vue brute.
 *
 * Un chemin qui traverse un tableau, un prédicat ou une descente récursive rend `null` : marquer
 * à côté serait pire que ne rien marquer.
 */
export function highlightedPath(criteria: TopicSearchCriteria): string | null {
  if (criteria.mode !== 'FIELD') return null;
  const path = criteria.field.trim().replace(/^\$\.?/, '');
  if (!path || /[[\]()*@?$]/.test(path)) return null;
  // Un segment vide, c'est `$..id` : une descente récursive, qui ne désigne pas un nœud.
  if (path.split('.').some(segment => segment.length === 0)) return null;
  return path;
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

// ── Portée d'une passe ────────────────────────────────────────────────────

/**
 * La part du topic que la passe a ouverte. « 20 000 scanned » sur un topic qui en compte deux
 * millions et sur un topic qui en compte trente mille ne veut pas dire la même chose, et la
 * différence décide de la confiance à accorder à un zéro.
 */
export function describeScanShare(scanned: number, topicSize: number): string | null {
  if (!Number.isFinite(topicSize) || topicSize <= 0 || scanned <= 0) return null;
  if (scanned >= topicSize) return null;
  const share = (scanned / topicSize) * 100;
  const rendered = share < 0.1
    ? '<0.1'
    : share < 10
      // `2.0%` se lit comme une précision qu'on n'a pas : la décimale ne sert que sous 10 %.
      ? share.toFixed(1).replace(/\.0$/, '')
      : String(Math.round(share));
  return `≈${rendered}% of the topic`;
}

/**
 * Un scan restreint à quelques partitions doit le dire : le serveur ne prévient que si *aucune*
 * des partitions demandées n'existe, donc un « 0 match » sur une partition sur vingt-quatre
 * ressemblerait sinon à un « 0 match » sur le topic.
 */
export function describePartitionScope(criteria: TopicSearchCriteria): string | null {
  const count = criteria.partitions.length;
  if (count === 0) return null;
  return count === 1
    ? `partition ${criteria.partitions[0]} only`
    : `partitions ${criteria.partitions.join(', ')} only`;
}

/** Résumé court d'un critère — pour l'historique, un export ou un libellé de bouton. */
export function describeCriterion(criteria: TopicSearchCriteria): string {
  const operator = OPERATORS.find(op => op.value === criteria.operator)?.label ?? criteria.operator;
  const value = criteria.value.trim();
  switch (criteria.mode) {
    case 'REGEX':
      return `regex ${criteria.query.trim()}`;
    case 'FIELD':
      return `${criteria.field.trim()} ${operator}${needsValue(criteria.operator) ? ` ${value}` : ''}`;
    case 'HEADER':
      return `header ${criteria.field.trim()} ${operator}${needsValue(criteria.operator) ? ` ${value}` : ''}`;
    case 'KEY':
      return `key ${operator} ${value}`;
    default:
      return criteria.query.trim();
  }
}

// ── Options avancées ──────────────────────────────────────────────────────

export const ADVANCED_KEY = 'kse:topic-search-advanced';

/**
 * Direction, budget et partitions se replient : chacun se justifie, et tous ensemble ils font une
 * barre de recherche que l'œil ne balaye plus. Repliés, ils restent annoncés par `describeAdvanced`
 * — une option active qu'on ne voit pas est exactement ce qu'il ne faut pas.
 */
export function readAdvancedOpen(): boolean {
  try {
    return localStorage.getItem(ADVANCED_KEY) === '1';
  } catch {
    return false;
  }
}

export function writeAdvancedOpen(open: boolean): void {
  try {
    localStorage.setItem(ADVANCED_KEY, open ? '1' : '0');
  } catch {
    // Mode privé ou quota : la préférence est un confort, pas une condition d'affichage.
  }
}

/** Ce que les options avancées changent par rapport au défaut ; vide quand elles n'y touchent pas. */
export function describeAdvanced(criteria: TopicSearchCriteria): string[] {
  const notes: string[] = [];
  if (criteria.startMode === 'TIMESTAMP' && criteria.fromTime.trim()) {
    notes.push(`from ${criteria.fromTime.trim().replace('T', ' ')}`);
  }
  if (criteria.startMode === 'OFFSET' && criteria.fromOffset.trim()) {
    notes.push(`from offset ${criteria.fromOffset.trim()}`);
  }
  if (criteria.maxHits > 0) {
    notes.push(HIT_CAPS.find(c => c.value === criteria.maxHits)?.label
      ?? `up to ${criteria.maxHits.toLocaleString()} hits`);
  }
  if (directionApplies(criteria) && criteria.direction !== emptyCriteria.direction) {
    notes.push(DIRECTIONS.find(d => d.value === criteria.direction)?.label ?? criteria.direction);
  }
  if (criteria.maxScan > 0) {
    notes.push(SCAN_BUDGETS.find(b => b.value === criteria.maxScan)?.label
      ?? `scan ${criteria.maxScan.toLocaleString()}`);
  }
  const scope = describePartitionScope(criteria);
  if (scope) notes.push(scope);
  return notes;
}

// ── Lecture des résultats ─────────────────────────────────────────────────

/**
 * Un hit garde son rang d'origine : c'est l'ordre dans lequel le scan l'a rencontré, et il doit
 * survivre au tri comme au filtre — demander « le plus ancien » et lire « #1 » en tête sur une
 * colonne triée par date ne dirait rien.
 */
export interface RankedHit extends TopicMessage {
  rank: number;
}

export const rankHits = (messages: TopicMessage[]): RankedHit[] =>
  messages.map((message, index) => ({ ...message, rank: index + 1 }));

/** Resserre sur les hits déjà ramenés — instantané, là où relancer une passe coûte dix secondes. */
export function filterHits(hits: RankedHit[], query: string): RankedHit[] {
  const needle = query.trim().toLowerCase();
  if (!needle) return hits;
  return hits.filter(hit =>
    (hit.key ?? '').toLowerCase().includes(needle)
    || (hit.value ?? '').toLowerCase().includes(needle)
    || `p${hit.partition}`.includes(needle)
    || String(hit.offset).includes(needle)
    || Object.entries(hit.headers ?? {}).some(([name, value]) =>
      name.toLowerCase().includes(needle) || (value ?? '').toLowerCase().includes(needle)));
}

export type HitSortKey = 'rank' | 'partition' | 'offset' | 'timestamp' | 'key';

export function sortHits(hits: RankedHit[], key: HitSortKey, desc: boolean): RankedHit[] {
  const direction = desc ? -1 : 1;
  return [...hits].sort((a, b) => {
    switch (key) {
      case 'partition':
        // À partition égale, l'offset est le seul ordre qui ait un sens.
        return (a.partition - b.partition) * direction || a.offset - b.offset;
      case 'offset':
        return (a.offset - b.offset) * direction;
      case 'timestamp':
        return (a.timestamp - b.timestamp) * direction || a.rank - b.rank;
      case 'key':
        return (a.key ?? '').localeCompare(b.key ?? '') * direction || a.rank - b.rank;
      default:
        return (a.rank - b.rank) * direction;
    }
  });
}

/** Ce que les hits disent d'eux-mêmes, une fois posés côte à côte. */
export interface HitInsight {
  total: number;
  /** Partitions représentées, la plus fournie en tête. */
  partitions: { partition: number; count: number }[];
  firstAt: number | null;
  lastAt: number | null;
  spanMs: number | null;
  distinctKeys: number;
  /** Clés vues plus d'une fois, la plus fréquente en tête. */
  repeatedKeys: { key: string; count: number }[];
}

export function analyzeHits(hits: TopicMessage[]): HitInsight {
  const byPartition = new Map<number, number>();
  const byKey = new Map<string, number>();
  let firstAt: number | null = null;
  let lastAt: number | null = null;

  for (const hit of hits) {
    byPartition.set(hit.partition, (byPartition.get(hit.partition) ?? 0) + 1);
    if (hit.key) byKey.set(hit.key, (byKey.get(hit.key) ?? 0) + 1);
    if (hit.timestamp > 0) {
      firstAt = firstAt === null ? hit.timestamp : Math.min(firstAt, hit.timestamp);
      lastAt = lastAt === null ? hit.timestamp : Math.max(lastAt, hit.timestamp);
    }
  }

  return {
    total: hits.length,
    partitions: [...byPartition.entries()]
      .map(([partition, count]) => ({ partition, count }))
      .sort((a, b) => b.count - a.count || a.partition - b.partition),
    firstAt,
    lastAt,
    spanMs: firstAt !== null && lastAt !== null ? lastAt - firstAt : null,
    distinctKeys: byKey.size,
    repeatedKeys: [...byKey.entries()]
      .filter(([, count]) => count > 1)
      .map(([key, count]) => ({ key, count }))
      .sort((a, b) => b.count - a.count || a.key.localeCompare(b.key)),
  };
}

function formatSpan(ms: number): string {
  if (ms < 1_000) return `${ms} ms`;
  const seconds = Math.round(ms / 1_000);
  if (seconds < 60) return `${seconds} s`;
  const minutes = Math.floor(seconds / 60);
  if (minutes < 60) return `${minutes} min ${seconds % 60} s`;
  const hours = Math.floor(minutes / 60);
  return hours < 24 ? `${hours} h ${minutes % 60} min` : `${Math.floor(hours / 24)} d ${hours % 24} h`;
}

/**
 * Lit les hits au lieu de laisser les lire. Un tableau de quarante lignes ne montre pas de lui-même
 * que trente-huit sont sur la même partition, ni qu'elles tiennent dans quatre minutes — ce sont
 * pourtant les deux premières choses qu'on cherche à savoir.
 *
 * @param partitionCount partitions du topic : « toutes sur p0 » sur un topic mono-partition n'est
 *   pas une observation.
 */
export function describeHitInsight(insight: HitInsight, partitionCount: number): string[] {
  const notes: string[] = [];
  if (insight.total === 0) return notes;

  const [busiest] = insight.partitions;
  if (partitionCount > 1 && busiest && insight.total > 1) {
    notes.push(insight.partitions.length === 1
      ? `all on partition ${busiest.partition}`
      : `${busiest.count} of ${insight.total} on partition ${busiest.partition}`
        + ` (${insight.partitions.length} partitions)`);
  }
  if (insight.spanMs !== null && insight.total > 1) {
    notes.push(insight.spanMs === 0
      ? 'all within the same millisecond'
      : `spanning ${formatSpan(insight.spanMs)}`);
  }
  if (insight.repeatedKeys.length > 0) {
    const [first] = insight.repeatedKeys;
    notes.push(insight.repeatedKeys.length === 1
      ? `key ${first.key} appears ${first.count} times`
      : `${insight.repeatedKeys.length} keys appear more than once (${first.key} ×${first.count})`);
  } else if (insight.distinctKeys > 0 && insight.total > 1) {
    notes.push(`${insight.distinctKeys} distinct keys`);
  }
  return notes;
}

// ── Affichage des messages ────────────────────────────────────────────────

export const VIEW_KEY = 'kse:topic-view';
export type MessageView = 'cards' | 'table';

/**
 * Une recherche peut ramener cent hits, et « continuer » les empile : cent cartes rendant chacune
 * un arbre JSON récursif saturent le rendu, et surtout se parcourent mal. La vue tableau donne une
 * ligne par record — coordonnées et aperçu — et le détail à la sélection.
 */
export function readViewMode(): MessageView {
  try {
    return localStorage.getItem(VIEW_KEY) === 'table' ? 'table' : 'cards';
  } catch {
    return 'cards';
  }
}

export function writeViewMode(view: MessageView): void {
  try {
    localStorage.setItem(VIEW_KEY, view);
  } catch {
    // Mode privé ou quota : la préférence est un confort, pas une condition d'affichage.
  }
}

/** Aperçu d'un payload sur une ligne — les lignes du tableau doivent rester de hauteur égale. */
export function previewOf(value: string | null, max = 240): string {
  const flat = (value ?? '').replace(/\s+/g, ' ').trim();
  return flat.length > max ? `${flat.slice(0, max)}…` : flat;
}

// ── Brouillon du critère ──────────────────────────────────────────────────

/**
 * Le critère **non lancé**, conservé d'une page à l'autre.
 *
 * L'URL porte déjà une recherche *exécutée* — c'est ce qui rend un lien partageable et ce qui
 * amène un saut depuis Stream Flow directement sur les messages. Ce qu'elle ne porte pas, c'est un
 * critère à moitié tapé qu'on quitte pour aller vérifier un nom de champ ailleurs : celui-là
 * disparaissait avec la page, exactement comme les formulaires que `draftStore` a rattrapés.
 *
 * **Un seul emplacement**, avec le topic auquel il appartient. Une clé par topic ferait grossir
 * `localStorage` sans borne pour un cas qui, en pratique, est toujours « je reviens sur le topic
 * que je viens de quitter » ; passer d'un topic à l'autre perd le brouillon du premier, ce que le
 * champ `topic` rend explicite au lieu de restituer un critère qui parlerait d'autre chose.
 */
const CRITERIA_DRAFT = 'topic-search';

interface CriteriaDraft {
  topic: string;
  criteria: TopicSearchCriteria;
}

/** Le critère gardé pour ce topic, ou `null` — un brouillon d'un autre topic n'est pas restitué. */
export function readCriteriaDraft(topic: string): TopicSearchCriteria | null {
  const draft = readDraft<CriteriaDraft | null>(CRITERIA_DRAFT, null);
  if (!draft || draft.topic !== topic || !draft.criteria) return null;
  // Un brouillon d'une version antérieure n'a pas tous les champs : le compléter vaut mieux que
  // le rejeter, et bien mieux que rouvrir un formulaire troué.
  return { ...emptyCriteria, ...draft.criteria };
}

/** Écrit le brouillon, ou l'efface quand le formulaire est revenu à vide — il n'y a rien à garder. */
export function saveCriteriaDraft(topic: string, criteria: TopicSearchCriteria): void {
  if (isEmptyCriteria(criteria)) clearDraft(CRITERIA_DRAFT);
  else writeDraft(CRITERIA_DRAFT, { topic, criteria } satisfies CriteriaDraft);
}

/** `true` quand le formulaire ne dit rien de plus que son état initial. */
export function isEmptyCriteria(criteria: TopicSearchCriteria): boolean {
  return (Object.keys(emptyCriteria) as (keyof TopicSearchCriteria)[]).every(key => {
    const a = criteria[key];
    const b = emptyCriteria[key];
    if (Array.isArray(a) && Array.isArray(b)) return a.length === b.length;
    return a === b;
  });
}

// ── Historique ────────────────────────────────────────────────────────────

export const SEARCH_HISTORY_KEY = 'kse:topic-search-history';
const HISTORY_PER_TOPIC = 8;
const HISTORY_MAX = 60;

export interface SearchHistoryEntry {
  topic: string;
  criteria: TopicSearchCriteria;
  /** Date d'exécution (ms) — la plus récente en tête. */
  ranAt: number;
  /** Nombre de hits ramenés, pour distinguer deux critères voisins dans la liste. */
  hits: number;
}

function readAllHistory(): SearchHistoryEntry[] {
  try {
    const raw = localStorage.getItem(SEARCH_HISTORY_KEY);
    const parsed = raw ? JSON.parse(raw) : [];
    return Array.isArray(parsed)
      ? parsed.filter((e): e is SearchHistoryEntry =>
          Boolean(e) && typeof e.topic === 'string' && Boolean(e.criteria))
        // Une entrée écrite par une version antérieure n'a pas tous les champs du critère :
        // la compléter vaut mieux que la rejeter ou que rejouer un critère troué.
        .map(e => ({ ...e, criteria: { ...emptyCriteria, ...e.criteria } }))
      : [];
  } catch {
    // Quota, mode privé, JSON corrompu : l'historique est un confort, jamais un blocage.
    return [];
  }
}

/** Les recherches passées sur ce topic, la plus récente en tête. */
export function readSearchHistory(topic: string): SearchHistoryEntry[] {
  return readAllHistory().filter(entry => entry.topic === topic).slice(0, HISTORY_PER_TOPIC);
}

/** Empile une recherche, dédoublonnée sur le critère (pas sur son résultat). */
export function pushSearchHistory(entry: SearchHistoryEntry): SearchHistoryEntry[] {
  const signature = buildSearchQuery(entry.criteria);
  const kept = readAllHistory().filter(other =>
    other.topic !== entry.topic || buildSearchQuery(other.criteria) !== signature);
  const next = [entry, ...kept].slice(0, HISTORY_MAX);
  try {
    localStorage.setItem(SEARCH_HISTORY_KEY, JSON.stringify(next));
  } catch {
    // idem : on renvoie la liste calculée même si le stockage refuse.
  }
  return next.filter(other => other.topic === entry.topic).slice(0, HISTORY_PER_TOPIC);
}

// ── Lien vers un message précis ───────────────────────────────────────────

/** Les coordonnées d'un record : ce qui l'identifie, et ce qu'un lien doit donc porter. */
export interface RecordCoordinates {
  partition: number;
  offset: number;
}

/**
 * `?record=2:88` — un lien vers *un message*, là où le reste de l'URL ne décrit qu'une recherche.
 *
 * C'est pourtant le message qu'on colle dans un ticket, et jusqu'ici il fallait décrire à la main
 * comment le retrouver. La lecture par coordonnées existant côté serveur, il ne restait que le lien.
 */
export function recordFromQuery(search: string): RecordCoordinates | null {
  const params = new URLSearchParams(search.startsWith('?') ? search.slice(1) : search);
  const raw = params.get('record');
  if (!raw) return null;
  const [left, right] = raw.split(':');
  const partition = Number(left);
  const offset = Number(right);
  return Number.isInteger(partition) && partition >= 0 && Number.isInteger(offset) && offset >= 0
    ? { partition, offset }
    : null;
}

export const recordParam = (coordinates: RecordCoordinates): string =>
  `${coordinates.partition}:${coordinates.offset}`;

/**
 * Pose ou retire le record d'une query string, en laissant le reste intact : l'URL doit décrire
 * à la fois la recherche affichée et le message ouvert, sans que l'une efface l'autre.
 */
export function withRecord(search: string, coordinates: RecordCoordinates | null): string {
  const params = new URLSearchParams(search.startsWith('?') ? search.slice(1) : search);
  if (coordinates) {
    params.set('record', recordParam(coordinates));
  } else {
    params.delete('record');
  }
  const rendered = params.toString();
  return rendered ? `?${rendered}` : '';
}

/** Le lien complet vers un message, tel qu'il se colle dans un ticket. */
export function buildRecordLink(origin: string, pathname: string, message: TopicMessage): string {
  return `${origin}${pathname}${withRecord('', message)}`;
}

// ── Suivre l'arrivée des messages ─────────────────────────────────────────

/** Intervalle entre deux reprises en mode « suivre ». */
export const FOLLOW_INTERVAL_MS = 5_000;

/**
 * Reprendre au curseur *est* un tail : le curseur pointe après le dernier record lu, donc relancer
 * la même passe ramène exactement les nouveaux arrivants. Le mode « suivre » ne fait que répéter
 * ce geste — ce qu'un opérateur faisait à la main, un clic toutes les quelques secondes.
 *
 * Il faut un curseur pour cela : sans passe précédente, il n'y a rien à reprendre.
 */
export function canFollow(cursor: Record<string, number> | null | undefined): boolean {
  return Boolean(cursor) && Object.keys(cursor ?? {}).length > 0;
}

/** Ce que le mode « suivre » annonce — jamais « en direct », qui promettrait plus que ça. */
export function describeFollow(following: boolean, coverage: SearchCoverage | null): string {
  if (!following) return 'Re-reads from where the scan stopped every few seconds, for new arrivals.';
  const passes = coverage ? ` · ${coverage.passes} passes` : '';
  return `Following: new records only, every ${FOLLOW_INTERVAL_MS / 1000}s${passes}`;
}

// ── Valeurs observées ─────────────────────────────────────────────────────

/**
 * Les valeurs qu'un chemin porte dans les messages déjà échantillonnés.
 *
 * La liste des chemins vient du schéma inféré, mais la valeur restait une saisie libre : on ne
 * sait pas si le statut s'écrit `SHIPPED`, `shipped` ou `Shipped`, et c'est la cause la plus
 * banale d'un zéro. Les échantillons sont déjà chargés — aucune requête de plus.
 *
 * JSON seulement, et chemins pointés simples : un chemin qui traverse un tableau ne désigne pas
 * une valeur, et proposer la mauvaise serait pire que de ne rien proposer.
 */
export function valuesAtPath(samples: TopicMessage[], path: string, max = 12): string[] {
  const segments = path.trim().replace(/^\$\.?/, '').split('.');
  if (!path.trim() || segments.some(segment => !segment || /[[\]()*@?$]/.test(segment))) return [];

  const seen = new Set<string>();
  for (const sample of samples) {
    let node: unknown;
    try {
      node = JSON.parse(sample.value ?? '');
    } catch {
      continue;
    }
    for (const segment of segments) {
      if (node === null || typeof node !== 'object' || Array.isArray(node)) {
        node = undefined;
        break;
      }
      node = (node as Record<string, unknown>)[segment];
    }
    if (node === null || node === undefined || typeof node === 'object') continue;
    seen.add(String(node));
    if (seen.size >= max) break;
  }
  return [...seen];
}

// ── Recherches épinglées ──────────────────────────────────────────────────

export const PINNED_KEY = 'kse:topic-search-pinned';
const PINNED_MAX = 40;

/**
 * Une recherche épinglée, par opposition à l'historique qui se dévide : le chemin du corrélatif
 * d'un pipeline se rejoue à chaque incident et n'a pas à survivre par chance aux huit dernières.
 */
export interface PinnedSearch {
  topic: string;
  criteria: TopicSearchCriteria;
  pinnedAt: number;
}

function readAllPinned(): PinnedSearch[] {
  try {
    const raw = localStorage.getItem(PINNED_KEY);
    const parsed = raw ? JSON.parse(raw) : [];
    return Array.isArray(parsed)
      ? parsed
        .filter((e): e is PinnedSearch =>
          Boolean(e) && typeof e.topic === 'string' && Boolean(e.criteria))
        .map(e => ({ ...e, criteria: { ...emptyCriteria, ...e.criteria } }))
      : [];
  } catch {
    return [];
  }
}

export const readPinned = (topic: string): PinnedSearch[] =>
  readAllPinned().filter(entry => entry.topic === topic);

export const isPinned = (pinned: PinnedSearch[], criteria: TopicSearchCriteria): boolean => {
  const signature = buildSearchQuery(criteria);
  return pinned.some(entry => buildSearchQuery(entry.criteria) === signature);
};

/** Épingle, ou désépingle si le critère y est déjà — un même bouton, deux sens. */
export function togglePinned(topic: string, criteria: TopicSearchCriteria): PinnedSearch[] {
  const signature = buildSearchQuery(criteria);
  const all = readAllPinned();
  const without = all.filter(entry =>
    entry.topic !== topic || buildSearchQuery(entry.criteria) !== signature);
  const next = without.length === all.length
    ? [{ topic, criteria, pinnedAt: Date.now() }, ...all].slice(0, PINNED_MAX)
    : without;
  try {
    localStorage.setItem(PINNED_KEY, JSON.stringify(next));
  } catch {
    // idem que l'historique : on renvoie la liste calculée même si le stockage refuse.
  }
  return next.filter(entry => entry.topic === topic);
}

// ── Clavier ───────────────────────────────────────────────────────────────

/**
 * Le hit suivant / précédent, par rang, dans l'ordre où la liste est *affichée* — tri et filtre
 * compris, sans quoi `j` sauterait à un record absent de l'écran.
 */
export function nextSelectedRank(
  displayed: RankedHit[],
  current: number | null,
  delta: number,
): number | null {
  if (displayed.length === 0) return null;
  const index = displayed.findIndex(hit => hit.rank === current);
  if (index < 0) return displayed[delta > 0 ? 0 : displayed.length - 1].rank;
  const next = Math.min(Math.max(index + delta, 0), displayed.length - 1);
  return displayed[next].rank;
}

/**
 * Vrai quand une frappe doit rester au champ qui a le focus. Un raccourci à une touche qui
 * s'appliquerait pendant la saisie transformerait « j » en commande au milieu d'un mot.
 */
export function isTypingTarget(target: EventTarget | null): boolean {
  const element = target as HTMLElement | null;
  if (!element || !element.tagName) return false;
  return ['INPUT', 'TEXTAREA', 'SELECT'].includes(element.tagName)
    || element.isContentEditable === true;
}

/**
 * Ce qu'une recherche terminée annonce à un lecteur d'écran : la disparition de « Searching… »
 * ne dit rien, et le bandeau de couverture n'est pas lu à sa mise à jour.
 */
export function announceResult(
  searching: boolean,
  coverage: SearchCoverage | null,
  criteria: TopicSearchCriteria | null,
  hits: number,
): string {
  if (searching) return 'Searching…';
  if (!coverage || !criteria) return '';
  return `${hits} match${hits === 1 ? '' : 'es'}, `
    + `${coverage.scanned.toLocaleString()} records scanned. `
    + `${describeCoverage(coverage, criteria)}.`;
}

// ── Export ────────────────────────────────────────────────────────────────

export const HIT_EXPORT_COLUMNS = [
  'partition', 'offset', 'timestamp', 'key', 'headers', 'value',
];

export function hitsToRows(hits: TopicMessage[]): Record<string, unknown>[] {
  return hits.map(hit => ({
    partition: hit.partition,
    offset: hit.offset,
    timestamp: hit.timestamp > 0 ? new Date(hit.timestamp).toISOString() : '',
    key: hit.key ?? '',
    headers: Object.entries(hit.headers ?? {}).map(([k, v]) => `${k}=${v ?? ''}`).join('; '),
    value: hit.value ?? '',
  }));
}

/**
 * Un export collé dans un ticket doit porter ce qu'il a couvert : les hits seuls ne disent ni
 * quel critère les a produits, ni combien de records ont été lus pour les trouver.
 */
export function searchToJson(
  topic: string,
  criteria: TopicSearchCriteria,
  coverage: SearchCoverage | null,
  hits: TopicMessage[],
  warnings: string[],
): string {
  return JSON.stringify({
    topic,
    exportedAt: new Date().toISOString(),
    criterion: describeCriterion(criteria),
    criteria,
    coverage: coverage && {
      ...coverage,
      scope: describePartitionScope(criteria) ?? 'every partition',
      summary: describeCoverage(coverage, criteria),
    },
    warnings,
    hits: hitsToRows(hits),
  }, null, 2);
}

/** Nom de fichier sûr pour un export de ce topic. */
export function exportFileName(topic: string, extension: string): string {
  return `topic-search-${topic.replace(/[^\w.-]+/g, '_') || 'topic'}.${extension}`;
}

// ── Relances quand rien n'a été trouvé ────────────────────────────────────

/** Une relance proposée sur un résultat vide : un critère élargi, et ce qu'il change. */
export interface SearchSuggestion {
  id: string;
  label: string;
  hint: string;
  criteria: TopicSearchCriteria;
}

/**
 * « Élargissez la portée, ou continuez le scan » était un conseil juste, écrit en texte, à
 * appliquer à la main dans un formulaire situé plus haut. Chaque piste devient un bouton qui
 * relance avec ce seul paramètre changé, de la plus probable à la plus coûteuse.
 */
export function suggestWidenings(criteria: TopicSearchCriteria): SearchSuggestion[] {
  const suggestions: SearchSuggestion[] = [];
  const textual = criteria.mode === 'CONTAINS' || criteria.mode === 'REGEX';

  if (criteria.mode === 'FIELD' && criteria.operator === 'EQ') {
    suggestions.push({
      id: 'contains',
      label: 'Match anywhere in the field',
      hint: `compares ${criteria.field.trim() || 'the field'} with "contains" instead of an exact "="`,
      criteria: { ...criteria, operator: 'CONTAINS' },
    });
  }
  if (isFieldScoped(criteria.mode) && criteria.value.trim()) {
    suggestions.push({
      id: 'raw-text',
      label: 'Search the raw payload',
      hint: `looks for ${criteria.value.trim()} anywhere in the record, ignoring the path`,
      criteria: { ...criteria, mode: 'CONTAINS', query: criteria.value },
    });
  }
  if (textual && !criteria.searchHeaders) {
    suggestions.push({
      id: 'headers',
      label: 'Search headers too',
      hint: 'correlation ids often travel only in a Kafka header',
      criteria: { ...criteria, searchHeaders: true },
    });
  }
  if (criteria.caseSensitive) {
    suggestions.push({
      id: 'ignore-case',
      label: 'Ignore case',
      hint: 'matches whatever the casing of the records',
      criteria: { ...criteria, caseSensitive: false },
    });
  }
  if (criteria.partitions.length > 0) {
    suggestions.push({
      id: 'all-partitions',
      label: 'Scan every partition',
      hint: `the scan only read ${describePartitionScope(criteria)}`,
      criteria: { ...criteria, partitions: [] },
    });
  }
  if (criteria.keyPartitioning && keyPartitioningApplies(criteria)) {
    suggestions.push({
      id: 'drop-key-partitioning',
      label: 'Scan every partition',
      hint: 'the key may have been written by a different partitioner',
      criteria: { ...criteria, keyPartitioning: false },
    });
  }
  if (criteria.sinceMinutes > 0) {
    suggestions.push({
      id: 'drop-window',
      label: 'Drop the time window',
      hint: `reads without the last ${criteria.sinceMinutes} min limit`,
      criteria: { ...criteria, sinceMinutes: 0 },
    });
  }
  if (criteria.maxScan < 100_000) {
    suggestions.push({
      id: 'bigger-budget',
      label: 'Scan 100 000 records',
      hint: 'reads deeper before giving up — slower',
      criteria: { ...criteria, maxScan: 100_000 },
    });
  }
  return suggestions.slice(0, 4);
}
