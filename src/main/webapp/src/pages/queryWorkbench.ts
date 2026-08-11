/**
 * Logique pure de l'éditeur SQL (QueryWorkbench).
 *
 * Tout ce qui peut être décidé sans Monaco, sans React et sans réseau vit ici : le formatage du
 * SQL, le tri de la grille de résultats, le choix de l'onglet actif après une fermeture, le rendu
 * d'une cellule, l'écriture défensive dans `localStorage`. Même raison que `streamFlow.ts` et
 * `topicSearch.ts` — une page de 1 400 lignes n'est testable que par ce qu'on en a sorti.
 */

// ─────────────────────────────────────────────────────────────────────────────
// Formatage SQL
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Monaco n'embarque **aucun formateur SQL**. `monaco-editor` fournit pour ce langage une
 * coloration Monarch et rien d'autre : `editor.action.formatDocument` ne trouve alors aucun
 * fournisseur et se contente d'afficher un discret « There is no formatter for 'sql' files
 * installed » dans l'éditeur. Le bouton « Format » de la barre d'onglets appelait exactement cette
 * action — il n'a jamais rien reformaté, et rien ne le disait.
 *
 * D'où ce formateur, enregistré comme `DocumentFormattingEditProvider` : le bouton et
 * `Shift+Alt+F` passent tous deux par lui.
 */

/**
 * Mots-clés qui ouvrent une clause : ils commencent une ligne, à la colonne 0, quand ils
 * apparaissent hors parenthèses. Les formes composées sont listées avant leur préfixe, la
 * reconnaissance étant gourmande — sans quoi `UNION ALL` se couperait après `UNION` et
 * `LEFT OUTER JOIN` après `LEFT`.
 */
const CLAUSE_KEYWORDS: readonly string[] = [
  'INSERT INTO', 'CREATE TABLE', 'CREATE VIEW',
  'SELECT DISTINCT', 'SELECT',
  'FROM',
  'LEFT OUTER JOIN', 'RIGHT OUTER JOIN', 'FULL OUTER JOIN',
  'LEFT JOIN', 'RIGHT JOIN', 'FULL JOIN', 'INNER JOIN', 'CROSS JOIN', 'JOIN',
  'ON', 'USING',
  'WHERE', 'GROUP BY', 'HAVING', 'ORDER BY', 'LIMIT', 'OFFSET', 'FETCH',
  'WINDOW', 'PARTITIONED BY', 'WITH', 'VALUES',
  'UNION ALL', 'UNION', 'INTERSECT', 'EXCEPT',
  'EMIT CHANGES',
];

/** Clauses dont le corps est réparti une entrée par ligne, coupé sur les virgules de niveau 0. */
const MULTILINE_BODY = new Set(['SELECT', 'SELECT DISTINCT', 'GROUP BY', 'ORDER BY']);

/**
 * Mots-clés mis en majuscules quand ils apparaissent comme mot isolé. Volontairement limité aux
 * mots-clés : passer tout en majuscules abîmerait les identifiants, et un nom de topic est
 * sensible à la casse côté Kafka.
 */
const INLINE_KEYWORDS = new Set([
  'AND', 'OR', 'NOT', 'IN', 'IS', 'NULL', 'LIKE', 'BETWEEN', 'AS', 'ASC', 'DESC',
  'CASE', 'WHEN', 'THEN', 'ELSE', 'END', 'CAST', 'EXISTS', 'ALL', 'ANY', 'DISTINCT',
  'INTERVAL', 'TABLE', 'BY', 'OVER', 'PARTITION', 'ROWS', 'RANGE',
  'TRUE', 'FALSE', 'UNBOUNDED', 'PRECEDING', 'FOLLOWING', 'CURRENT', 'ROW',
  // Fonctions intégrées, mises en majuscules comme les mots-clés. La liste est **fermée** à
  // dessein : une fonction inconnue est très probablement une UDF, dont Flink ne garantit pas
  // la résolution insensible à la casse — recaser `XmlExtract` en `XMLEXTRACT` casserait la
  // requête que l'on prétendait embellir.
  'COUNT', 'SUM', 'AVG', 'MIN', 'MAX', 'COALESCE', 'NULLIF', 'ABS', 'ROUND', 'FLOOR', 'CEIL',
  'UPPER', 'LOWER', 'TRIM', 'SUBSTRING', 'CONCAT', 'LENGTH', 'REPLACE',
  'TUMBLE', 'HOP', 'SESSION', 'CUMULATE', 'DESCRIPTOR',
  'JSON_VALUE', 'JSON_QUERY', 'JSON_EXISTS', 'PROCTIME', 'ROWTIME',
]);

/**
 * Mots-clés après lesquels une parenthèse ouvrante prend une espace (`x IN (1, 2)`).
 *
 * Le défaut est l'inverse — `COUNT(*)`, `TUMBLE(…)` — parce qu'un appel de fonction est de très
 * loin le cas majoritaire, et qu'un identifiant collé à sa parenthèse est ce que tout le monde
 * écrit à la main.
 */
const SPACE_BEFORE_PAREN = new Set([
  'IN', 'AND', 'OR', 'NOT', 'EXISTS', 'BETWEEN', 'ON', 'VALUES', 'BY', 'ALL', 'ANY',
  'WHEN', 'THEN', 'ELSE', 'IS', 'AS', 'FROM', 'WHERE', 'SELECT', 'UNION', 'RETURNS',
]);

type TokenKind = 'word' | 'string' | 'lineComment' | 'blockComment' | 'punct';
interface Token { kind: TokenKind; text: string }

/**
 * Découpe le SQL en unités que le formateur peut déplacer sans les abîmer.
 *
 * Les littéraux et les commentaires sortent d'un bloc : leur contenu n'est jamais réécrit, ni
 * recasé, ni recoupé. C'est la seule garantie qui rend un formateur acceptable — reformater
 * `WHERE label = 'group by'` ne doit pas produire une clause.
 */
export function tokenizeSql(sql: string): Token[] {
  const tokens: Token[] = [];
  let i = 0;
  while (i < sql.length) {
    const c = sql[i];

    if (/\s/.test(c)) { i += 1; continue; }

    if (c === '-' && sql[i + 1] === '-') {
      const end = sql.indexOf('\n', i);
      const stop = end === -1 ? sql.length : end;
      tokens.push({ kind: 'lineComment', text: sql.slice(i, stop) });
      i = stop;
      continue;
    }

    if (c === '/' && sql[i + 1] === '*') {
      const end = sql.indexOf('*/', i + 2);
      const stop = end === -1 ? sql.length : end + 2;
      tokens.push({ kind: 'blockComment', text: sql.slice(i, stop) });
      i = stop;
      continue;
    }

    // Chaîne SQL : le guillemet simple se double pour s'échapper.
    if (c === "'") {
      let j = i + 1;
      while (j < sql.length) {
        if (sql[j] === "'") {
          if (sql[j + 1] === "'") { j += 2; continue; }
          j += 1;
          break;
        }
        j += 1;
      }
      tokens.push({ kind: 'string', text: sql.slice(i, j) });
      i = j;
      continue;
    }

    // Identifiant protégé : backticks (Flink) ou guillemets doubles (SQL standard).
    if (c === '`' || c === '"') {
      const end = sql.indexOf(c, i + 1);
      const stop = end === -1 ? sql.length : end + 1;
      tokens.push({ kind: 'string', text: sql.slice(i, stop) });
      i = stop;
      continue;
    }

    if (/[A-Za-z_$]/.test(c)) {
      let j = i;
      while (j < sql.length && /[\w$]/.test(sql[j])) j += 1;
      tokens.push({ kind: 'word', text: sql.slice(i, j) });
      i = j;
      continue;
    }

    if (/[0-9]/.test(c)) {
      let j = i;
      while (j < sql.length && /[\d.]/.test(sql[j])) j += 1;
      tokens.push({ kind: 'word', text: sql.slice(i, j) });
      i = j;
      continue;
    }

    tokens.push({ kind: 'punct', text: c });
    i += 1;
  }
  return tokens;
}

/** Le mot-clé de clause qui commence à `index`, ou `null`. Reconnaissance gourmande. */
function clauseAt(tokens: Token[], index: number): string | null {
  const token = tokens[index];
  if (!token || token.kind !== 'word') return null;
  for (const keyword of CLAUSE_KEYWORDS) {
    const parts = keyword.split(' ');
    const matches = parts.every((part, k) => {
      const t = tokens[index + k];
      return t && t.kind === 'word' && t.text.toUpperCase() === part;
    });
    if (matches) return keyword;
  }
  return null;
}

/** Ni espace avant, ni espace après — la ponctuation qui se colle. */
const NO_SPACE_BEFORE = new Set([',', ')', ';', '.']);
const NO_SPACE_AFTER = new Set(['(', '.']);

/**
 * Reformate le SQL : une clause par ligne, la liste du SELECT une entrée par ligne, les mots-clés
 * en majuscules, les littéraux et les commentaires inchangés.
 *
 * Une entrée vide ou uniquement composée d'espaces ressort telle quelle — reformater le vide en
 * autre chose que du vide serait une modification que personne n'a demandée.
 */
export function formatSql(sql: string): string {
  if (!sql.trim()) return sql;

  const tokens = tokenizeSql(sql);
  const lines: string[] = [];
  let current = '';
  let indent = 0;
  let depth = 0;
  /** La clause en cours, pour savoir si ses virgules de niveau 0 coupent la ligne. */
  let clause: string | null = null;

  const flush = () => {
    if (current.trim()) lines.push(' '.repeat(indent) + current.trim());
    current = '';
  };

  const append = (text: string, spaceBefore: boolean) => {
    if (current && spaceBefore) current += ' ';
    current += text;
  };

  for (let i = 0; i < tokens.length; i += 1) {
    const token = tokens[i];
    const previous = tokens[i - 1];

    // ── Clause de niveau 0 : nouvelle ligne, sans indentation ────────────────
    const keyword = depth === 0 ? clauseAt(tokens, i) : null;
    if (keyword) {
      flush();
      indent = 0;
      clause = keyword;
      current = keyword;
      i += keyword.split(' ').length - 1;
      // Le corps multi-lignes commence sur la ligne suivante, indenté.
      if (MULTILINE_BODY.has(keyword)) { flush(); indent = 2; }
      continue;
    }

    // ── Commentaires ────────────────────────────────────────────────────────
    if (token.kind === 'lineComment') {
      // Un commentaire de fin de ligne reste sur sa ligne ; seul, il en occupe une.
      append(token.text, true);
      flush();
      continue;
    }
    if (token.kind === 'blockComment') {
      append(token.text, true);
      continue;
    }

    // ── Ponctuation ─────────────────────────────────────────────────────────
    if (token.kind === 'punct') {
      if (token.text === '(') {
        depth += 1;
        const afterKeyword = !!previous && previous.kind === 'word'
          && SPACE_BEFORE_PAREN.has(previous.text.toUpperCase());
        append('(', afterKeyword);
        continue;
      }
      if (token.text === ')') { depth = Math.max(0, depth - 1); append(')', false); continue; }
      if (token.text === ';') {
        append(';', false);
        flush();
        indent = 0;
        clause = null;
        // Une ligne vide sépare deux instructions, sauf en fin de document.
        if (i < tokens.length - 1) lines.push('');
        continue;
      }
      if (token.text === ',') {
        append(',', false);
        // Seules les virgules de niveau 0 d'une clause à corps multi-lignes coupent.
        if (depth === 0 && clause && MULTILINE_BODY.has(clause)) flush();
        continue;
      }
      append(token.text, !NO_SPACE_BEFORE.has(token.text) && !(previous && NO_SPACE_AFTER.has(previous.text)));
      continue;
    }

    // ── Chaînes et identifiants protégés : jamais réécrits ───────────────────
    if (token.kind === 'string') {
      append(token.text, !previous || !NO_SPACE_AFTER.has(previous.text));
      continue;
    }

    // ── Mots ────────────────────────────────────────────────────────────────
    const upper = token.text.toUpperCase();
    const text = INLINE_KEYWORDS.has(upper) ? upper : token.text;
    const spaceBefore = !previous || (!NO_SPACE_AFTER.has(previous.text) && previous.text !== '(');
    append(text, spaceBefore);
  }

  flush();
  // Une instruction terminée par `;` laisse une ligne vide en queue.
  while (lines.length && !lines[lines.length - 1]) lines.pop();
  return lines.join('\n');
}

// ─────────────────────────────────────────────────────────────────────────────
// Tri de la grille de résultats
// ─────────────────────────────────────────────────────────────────────────────

export type SortDir = 'asc' | 'desc';

/**
 * Clé de tri précalculée pour une cellule.
 *
 * Le tri passait par `String(a[col] ?? '').localeCompare(…, { numeric: true })` : deux allocations
 * de chaîne et un appel au collateur Unicode **par comparaison**, soit de l'ordre de 60 000 appels
 * pour 5 000 lignes. Le collateur est aussi ce qui décidait qu'une colonne numérique se trie « à
 * peu près » — `numeric: true` compare des chiffres dans du texte, pas des nombres : `1e3` y passe
 * après `2`, et `-1` avant `-2`. On extrait donc la clé une fois par ligne, et un nombre est
 * comparé comme un nombre.
 */
interface SortKey { nullish: boolean; num: number | null; text: string }

export function sortKeyOf(value: unknown): SortKey {
  if (value === null || value === undefined) return { nullish: true, num: null, text: '' };
  if (typeof value === 'number') {
    return Number.isFinite(value)
      ? { nullish: false, num: value, text: String(value) }
      : { nullish: true, num: null, text: String(value) };
  }
  if (typeof value === 'boolean') return { nullish: false, num: value ? 1 : 0, text: String(value) };
  if (typeof value === 'object') return { nullish: false, num: null, text: JSON.stringify(value) ?? '' };
  const text = String(value);
  const trimmed = text.trim();
  // Une chaîne qui *est* un nombre se trie comme un nombre : les moteurs renvoient volontiers
  // des entiers 64 bits en texte, et les voir s'ordonner « 1, 10, 2 » est le grief classique.
  if (trimmed !== '' && Number.isFinite(Number(trimmed))) {
    return { nullish: false, num: Number(trimmed), text };
  }
  return { nullish: false, num: null, text };
}

/**
 * Trie une copie des lignes sur une colonne.
 *
 * Les valeurs absentes vont **toujours en fin de liste**, quel que soit le sens — même règle que
 * le premier saut d'une trace Stream Flow : trier pour trouver la plus grande valeur et lire une
 * colonne de vides en tête ne répond à rien. Le tri est stable : à clé égale, l'ordre du moteur
 * est conservé, et c'est lui qui porte le sens sur une lecture Kafka (l'ordre des offsets).
 */
export function sortRows<T extends Record<string, unknown>>(
  rows: readonly T[],
  column: string | null,
  direction: SortDir,
): T[] {
  if (!column) return [...rows];
  const decorated = rows.map((row, index) => ({ row, index, key: sortKeyOf(row[column]) }));
  const sign = direction === 'asc' ? 1 : -1;
  decorated.sort((a, b) => {
    if (a.key.nullish !== b.key.nullish) return a.key.nullish ? 1 : -1;
    if (a.key.nullish) return a.index - b.index;
    if (a.key.num !== null && b.key.num !== null) {
      if (a.key.num !== b.key.num) return (a.key.num - b.key.num) * sign;
      return a.index - b.index;
    }
    const cmp = a.key.text.localeCompare(b.key.text);
    return cmp !== 0 ? cmp * sign : a.index - b.index;
  });
  return decorated.map(d => d.row);
}

// ─────────────────────────────────────────────────────────────────────────────
// Rendu d'une cellule
// ─────────────────────────────────────────────────────────────────────────────

export interface CellText { text: string; isNull: boolean }

/**
 * Texte d'une cellule, en distinguant l'absence de valeur.
 *
 * `String(row[col] ?? '')` rendait `null` et la chaîne vide exactement pareil : deux cellules
 * vides côte à côte, dont l'une est un NULL SQL et l'autre une chaîne de longueur zéro. Sur une
 * grille de résultats, c'est la distinction qui décide si un LEFT JOIN a trouvé sa ligne.
 */
export function cellText(value: unknown): CellText {
  if (value === null || value === undefined) return { text: 'NULL', isNull: true };
  if (typeof value === 'object') return { text: JSON.stringify(value) ?? '', isNull: false };
  return { text: String(value), isNull: false };
}

// ─────────────────────────────────────────────────────────────────────────────
// Onglets
// ─────────────────────────────────────────────────────────────────────────────

export interface TabLike { id: string }

/**
 * Onglet à activer après en avoir fermé un : le voisin de gauche, sinon celui de droite.
 *
 * Le calcul vivait *à l'intérieur* de l'updater passé à `setTabs`, qui appelait `setActiveTabId`
 * au passage. Un updater doit être pur — React 19 l'invoque deux fois en mode strict, et rien ne
 * garantit qu'il ne le rejouera pas ailleurs. Fermer un onglet déclenchait donc deux mises à jour
 * d'état imbriquées dont la seconde travaillait sur une liste déjà filtrée.
 */
export function nextActiveTabId<T extends TabLike>(
  tabs: readonly T[],
  closingId: string,
  activeId: string,
): string {
  if (tabs.length <= 1) return activeId;
  if (closingId !== activeId) return activeId;
  const index = tabs.findIndex(t => t.id === closingId);
  if (index === -1) return activeId;
  const neighbour = tabs[index - 1] ?? tabs[index + 1];
  return neighbour ? neighbour.id : activeId;
}

// ─────────────────────────────────────────────────────────────────────────────
// Fraîcheur du résultat affiché
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Le SQL affiché a-t-il changé depuis la requête qui a produit ces résultats ?
 *
 * La grille gardait les lignes de la requête précédente pendant qu'on éditait la suivante, sans
 * rien dire : on lit un résultat en croyant qu'il répond au texte sous les yeux. Stream Flow marque
 * son graphe « stale » exactement pour cette raison (`sameCriterion`) — la comparaison ignore
 * l'espacement, une indentation retouchée ne périme pas un résultat.
 */
export function isResultStale(ranSql: string | null, currentSql: string): boolean {
  if (ranSql === null) return false;
  const normalize = (s: string) => s.replace(/\s+/g, ' ').trim();
  return normalize(ranSql) !== normalize(currentSql);
}

// ─────────────────────────────────────────────────────────────────────────────
// Persistance
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Écriture `localStorage` qui ne peut pas faire tomber l'appelant.
 *
 * `localStorage.setItem` lève sur quota dépassé et en navigation privée sur certains navigateurs.
 * Les onglets étaient protégés, les requêtes sauvegardées et l'historique ne l'étaient pas : la
 * même panne de stockage se traduisait par une exception non rattrapée au milieu d'un gestionnaire
 * de clic — donc, depuis que les erreurs non rattrapées remontent à l'écran, par un bandeau
 * d'erreur générique au lieu du message qui dit ce qui n'a pas pu être conservé.
 */
export function writeStored(key: string, value: unknown): boolean {
  try {
    localStorage.setItem(key, JSON.stringify(value));
    return true;
  } catch {
    return false;
  }
}

/** Lecture symétrique : un contenu illisible vaut l'absence, jamais une exception. */
export function readStored<T>(key: string, fallback: T): T {
  try {
    const raw = localStorage.getItem(key);
    if (raw === null) return fallback;
    return JSON.parse(raw) as T;
  } catch {
    return fallback;
  }
}

/** Suppression, même contrat. */
export function removeStored(key: string): boolean {
  try {
    localStorage.removeItem(key);
    return true;
  } catch {
    return false;
  }
}

// ─────────────────────────────────────────────────────────────────────────────
// Disposition du plan de travail
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Position des deux séparateurs, conservée d'une visite à l'autre.
 *
 * Les pages sont démontées à chaque navigation : un éditeur agrandi pour écrire une longue requête
 * revenait à 55 % au retour de la Dashboard, comme la barre latérale à 288 px. Stream Flow persiste
 * déjà son split (`kse:flow-evidence`) exactement pour cette raison.
 */
export interface WorkbenchLayout { splitPercent: number; sidebarWidth: number }

export const LAYOUT_STORAGE_KEY = 'kse:query-layout';
export const DEFAULT_LAYOUT: WorkbenchLayout = { splitPercent: 55, sidebarWidth: 288 };
export const SPLIT_MIN = 20, SPLIT_MAX = 80;
export const SIDEBAR_MIN = 200, SIDEBAR_MAX = 480;

export const clamp = (v: number, min: number, max: number) => Math.max(min, Math.min(max, v));

/**
 * Relit la disposition. Une valeur hors bornes est ramenée dans les bornes plutôt qu'ignorée — un
 * `localStorage` édité à la main, ou écrit par une version dont les bornes étaient différentes, ne
 * doit pas pouvoir laisser un panneau à 2 % de hauteur, donc inutilisable et non redimensionnable.
 */
export function readLayout(): WorkbenchLayout {
  const stored = readStored<Partial<WorkbenchLayout> | null>(LAYOUT_STORAGE_KEY, null);
  return {
    splitPercent: Number.isFinite(stored?.splitPercent)
      ? clamp(stored!.splitPercent as number, SPLIT_MIN, SPLIT_MAX) : DEFAULT_LAYOUT.splitPercent,
    sidebarWidth: Number.isFinite(stored?.sidebarWidth)
      ? clamp(stored!.sidebarWidth as number, SIDEBAR_MIN, SIDEBAR_MAX) : DEFAULT_LAYOUT.sidebarWidth,
  };
}
