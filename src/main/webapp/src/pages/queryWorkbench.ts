/**
 * Logique pure de l'éditeur SQL (QueryWorkbench).
 *
 * Tout ce qui peut être décidé sans Monaco, sans React et sans réseau vit ici : le formatage du
 * SQL, le tri de la grille de résultats, le choix de l'onglet actif après une fermeture, le rendu
 * d'une cellule, l'écriture défensive dans `localStorage`. Même raison que `streamFlow.ts` et
 * `topicSearch.ts` — une page de 1 400 lignes n'est testable que par ce qu'on en a sorti.
 */
import { toTableName } from './sqlScope';

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
// Découpage en instructions
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Un onglet peut contenir plusieurs instructions séparées par `;` — le formateur les met en forme,
 * l'éditeur les accepte — mais Run envoyait le texte entier et le backend classait sur le premier
 * mot. Sélectionner à la main était le contournement. L'affordance standard d'un éditeur SQL, c'est
 * que `⌘↵` exécute l'instruction **où se trouve le curseur**, la sélection ne servant qu'à forcer
 * autre chose.
 */
export interface SqlStatement {
  /** Décalage du premier caractère utile, dans le document. */
  start: number;
  /** Décalage du dernier caractère utile (inclus). */
  end: number;
  /** Le texte de l'instruction, sans le `;` terminal ni les espaces de bord. */
  text: string;
}

/** Le `;` d'un littéral ou d'un commentaire ne sépare rien : le découpage doit les traverser. */
function isBlankSql(text: string): boolean {
  return !text
    .replace(/\/\*[\s\S]*?\*\//g, ' ')
    .replace(/--[^\n]*/g, ' ')
    .trim();
}

export function splitStatements(sql: string): SqlStatement[] {
  const statements: SqlStatement[] = [];
  let chunkStart = 0;
  let i = 0;

  const pushChunk = (endExclusive: number) => {
    const raw = sql.slice(chunkStart, endExclusive);
    if (isBlankSql(raw)) return;
    // Les bornes désignent le texte utile, pas le fragment brut : c'est `start` qui reporte les
    // positions d'erreur du moteur dans le repère du document.
    const leading = raw.length - raw.trimStart().length;
    const trailing = raw.length - raw.trimEnd().length;
    statements.push({
      start: chunkStart + leading,
      end: endExclusive - trailing - 1,
      text: raw.trim(),
    });
  };

  while (i < sql.length) {
    const c = sql[i];
    if (c === '-' && sql[i + 1] === '-') {
      const end = sql.indexOf('\n', i);
      i = end === -1 ? sql.length : end;
      continue;
    }
    if (c === '/' && sql[i + 1] === '*') {
      const end = sql.indexOf('*/', i + 2);
      i = end === -1 ? sql.length : end + 2;
      continue;
    }
    if (c === "'") {
      i += 1;
      while (i < sql.length) {
        if (sql[i] === "'") {
          if (sql[i + 1] === "'") { i += 2; continue; }
          i += 1;
          break;
        }
        i += 1;
      }
      continue;
    }
    if (c === '`' || c === '"') {
      const end = sql.indexOf(c, i + 1);
      i = end === -1 ? sql.length : end + 1;
      continue;
    }
    if (c === ';') {
      pushChunk(i);
      chunkStart = i + 1;
      i += 1;
      continue;
    }
    i += 1;
  }
  pushChunk(sql.length);
  return statements;
}

/**
 * Index de l'instruction visée par un curseur, ou `-1` s'il n'y en a aucune.
 *
 * Le curseur posé *après* le `;` d'une instruction désigne celle-ci, pas la suivante : venir de
 * taper le point-virgule puis lancer, c'est vouloir exécuter ce que l'on vient de finir. C'est
 * aussi le comportement de DataGrip et de DBeaver.
 */
export function statementIndexAt(statements: readonly SqlStatement[], offset: number): number {
  if (!statements.length) return -1;
  for (let i = 0; i < statements.length; i += 1) {
    const s = statements[i];
    if (offset >= s.start && offset <= s.end) return i;
    // Le curseur est tombé dans l'espace qui précède cette instruction : il appartient à celle
    // d'avant, dont il suit le point-virgule. Avant la toute première, il n'y a rien d'antérieur.
    if (offset < s.start) return Math.max(0, i - 1);
  }
  return statements.length - 1;
}

/**
 * L'instruction débarrassée d'une chaîne `WITH … AS ( … )` de tête.
 *
 * Le backend classe désormais les instructions de la même façon (`SqlStatements.withoutLeadingCte`),
 * parce qu'une CTE commence par `WITH` et se faisait refuser par tous les `startsWith` du chemin de
 * requête. Côté UI, c'est ce qui permet à la garde de mode d'exécution de voir qu'un
 * `WITH … INSERT INTO` est un INSERT et qu'un `WITH … SELECT` est une lecture.
 *
 * **Échoue fermé**, comme son homologue Java : une forme non reconnue ressort telle quelle et reste
 * classée comme avant.
 */
export function withoutLeadingCte(sql: string): string {
  const s = (sql ?? '').trim();
  if (!/^with\b/i.test(s)) return s;

  let i = 4;
  const skipSpace = () => { while (i < s.length && /\s/.test(s[i])) i += 1; };
  /** Passe un bloc parenthésé équilibré, en traversant littéraux et identifiants protégés. */
  const skipParens = (): boolean => {
    if (s[i] !== '(') return false;
    let depth = 0;
    while (i < s.length) {
      const c = s[i];
      if (c === "'") {
        i += 1;
        while (i < s.length) {
          if (s[i] === "'") {
            if (s[i + 1] === "'") { i += 2; continue; }
            i += 1;
            break;
          }
          i += 1;
        }
        continue;
      }
      if (c === '`' || c === '"') {
        const end = s.indexOf(c, i + 1);
        if (end === -1) return false;
        i = end + 1;
        continue;
      }
      if (c === '(') depth += 1;
      if (c === ')') {
        depth -= 1;
        if (depth === 0) { i += 1; return true; }
      }
      i += 1;
    }
    return false;
  };
  const skipIdentifier = (): boolean => {
    const c = s[i];
    if (c === '`' || c === '"') {
      const end = s.indexOf(c, i + 1);
      if (end === -1) return false;
      i = end + 1;
      return true;
    }
    if (!/[A-Za-z_]/.test(c ?? '')) return false;
    while (i < s.length && /[\w$]/.test(s[i])) i += 1;
    return true;
  };

  skipSpace();
  if (/^recursive\b/i.test(s.slice(i))) { i += 9; skipSpace(); }

  let consumed = false;
  while (i < s.length) {
    if (!skipIdentifier()) return s;
    skipSpace();
    if (s[i] === '(' && !skipParens()) return s;   // liste de colonnes optionnelle
    skipSpace();
    if (!/^as\b/i.test(s.slice(i))) return s;
    i += 2;
    skipSpace();
    if (!skipParens()) return s;
    skipSpace();
    consumed = true;
    if (s[i] === ',') { i += 1; skipSpace(); continue; }
    break;
  }
  const body = consumed ? s.slice(i).trim() : s;
  return body || s;
}

/** Ligne et colonne (base 1) d'un décalage — le repère que Monaco et le planner emploient. */
export function positionAt(text: string, offset: number): { line: number; column: number } {
  const clamped = Math.max(0, Math.min(offset, text.length));
  let line = 1;
  let lineStart = 0;
  for (let i = 0; i < clamped; i += 1) {
    if (text[i] === '\n') { line += 1; lineStart = i + 1; }
  }
  return { line, column: clamped - lineStart + 1 };
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

/**
 * Valeur d'une cellule telle que le panneau de détail la montre : mise en forme quand elle est
 * structurée, brute sinon.
 *
 * Dans la grille, une valeur longue est tronquée sur une ligne et un JSON imbriqué est une chaîne
 * de texte — un `title` au survol était le seul recours, et il ne met rien en forme. Un objet est
 * donc indenté ici, y compris quand il arrive sous forme de **chaîne** contenant du JSON, ce qui
 * est le cas courant : le moteur direct renvoie un sous-document comme du texte.
 */
export interface DetailValue { text: string; json: boolean; isNull: boolean }

export function detailValue(value: unknown): DetailValue {
  if (value === null || value === undefined) return { text: 'NULL', json: false, isNull: true };
  if (typeof value === 'object') {
    return { text: JSON.stringify(value, null, 2) ?? '', json: true, isNull: false };
  }
  const text = String(value);
  const trimmed = text.trim();
  if (trimmed.startsWith('{') || trimmed.startsWith('[')) {
    try {
      // Reformaté seulement si cela parse réellement : un texte qui commence par `{` sans être du
      // JSON doit rester tel quel plutôt que d'être signalé comme structuré.
      return { text: JSON.stringify(JSON.parse(trimmed), null, 2), json: true, isNull: false };
    } catch { /* pas du JSON — on garde le texte brut */ }
  }
  return { text, json: false, isNull: false };
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
// Requêtes de départ
// ─────────────────────────────────────────────────────────────────────────────

/**
 * L'onglet initial était semé d'une requête écrite en dur qui **ne pouvait pas s'exécuter** :
 *
 * ```sql
 * SELECT window_start, … FROM orders_stream
 * WINDOW TUMBLING (SIZE 5 MINUTES) … EMIT CHANGES;
 * ```
 *
 * `WINDOW TUMBLING (…) … EMIT CHANGES` est de la syntaxe ksqlDB, pas du Flink SQL (qui écrit
 * `TABLE(TUMBLE(TABLE t, DESCRIPTOR(ts), INTERVAL '5' MINUTE))`), et `orders_stream` n'existe nulle
 * part — le jeu de démo sème `demo.orders.1.received` et compagnie. Le geste le plus naturel du
 * nouvel arrivant, ouvrir l'éditeur et appuyer sur Run, échouait donc ; et depuis que les erreurs
 * utilisateur ne retombent plus sur le lecteur direct, il échoue sur une erreur de parser.
 *
 * Les propositions sont désormais **dérivées du catalogue réellement chargé**, donc elles ne
 * peuvent pas citer une table absente. Quand le catalogue est vide, il n'y en a aucune : mieux
 * vaut un écran qui n'offre rien qu'un exemple qui ment.
 */
export interface StarterQuery { label: string; sql: string; hint: string }

export interface CatalogLike { tables: string[]; topics: string[] }

/**
 * Table sur laquelle bâtir les propositions : une table Flink si le catalogue en a une, sinon le
 * premier topic. Les topics `internal.*` sont ceux que l'application s'écrit à elle-même (historique
 * d'audit, configuration des métriques) — une première impression bâtie dessus n'apprend rien du
 * cluster que l'on vient explorer.
 */
export function starterTable(catalog: CatalogLike | null | undefined): string | null {
  if (!catalog) return null;
  const table = catalog.tables.find(t => !t.startsWith('internal'));
  if (table) return table;
  const topic = catalog.topics.find(t => !t.startsWith('internal.'));
  return topic ? toTableName(topic) : null;
}

export function starterQueries(catalog: CatalogLike | null | undefined): StarterQuery[] {
  const table = starterTable(catalog);
  if (!table) return [];
  return [
    {
      label: 'Read the latest rows',
      sql: `SELECT * FROM ${table} LIMIT 50`,
      hint: `Reads ${table} through the direct Kafka reader.`,
    },
    {
      label: 'Count the rows',
      sql: `SELECT COUNT(*) AS metric_value FROM ${table}`,
      // L'alias n'est pas cosmétique : le moteur direct lit la première valeur numérique d'une
      // ligne aliasée, et un agrégat sans alias ne remonte rien.
      hint: 'An aggregate has to be aliased — the direct engine reads the aliased column.',
    },
  ];
}

// ─────────────────────────────────────────────────────────────────────────────
// Raccourci de la barre latérale
// ─────────────────────────────────────────────────────────────────────────────

/** Mode d'exécution de l'éditeur. Déclaré ici parce que le SQL posé en dépend. */
export type ExecutionMode = 'SYNC_READ' | 'ASYNC_JOB';

/**
 * Suffixe du nom de sink dans le squelette d'INSERT. C'est un **emplacement à remplir**, pas une
 * table qu'on suppose exister : le mode Job soumet un job continu, dont la cible doit avoir été
 * déclarée avant. Le commentaire de tête le dit, parce qu'un nom plausible se lit comme une
 * promesse et que l'échec, sinon, arriverait sous la forme d'un « Object not found » sur une table
 * que l'éditeur a écrite lui-même.
 */
export const JOB_SINK_SUFFIX = '_out';

/**
 * Le SQL qu'un clic sur une table ou un topic de la barre latérale pose dans l'éditeur.
 *
 * Il suit le mode d'exécution, faute de quoi il produit une requête que le bouton Run refuse :
 * en mode Job, seul un INSERT INTO part au moteur, donc le `SELECT * FROM …` du mode lecture y
 * était rejeté par la garde de mode — un clic dont le seul résultat possible était un panneau
 * d'erreur.
 *
 * Pas de `LIMIT` sur la branche Job : un INSERT y est un job continu, que rien ne borne.
 */
export function sidebarSqlFor(table: string, mode: ExecutionMode, maxRows: number): string {
  if (mode === 'ASYNC_JOB') {
    return [
      `-- Job mode submits a continuous INSERT. ${table}${JOB_SINK_SUFFIX} is a placeholder:`,
      '-- point it at a table that already exists (CREATE TABLE declares one over a topic).',
      `INSERT INTO ${table}${JOB_SINK_SUFFIX}`,
      `SELECT * FROM ${table}`,
    ].join('\n');
  }
  return `SELECT * FROM ${table} LIMIT ${maxRows}`;
}

/** Ce que le clic va faire, pour l'intitulé accessible du bouton — il annonçait « SELECT » partout. */
export function sidebarActionLabel(target: string, mode: ExecutionMode): string {
  return mode === 'ASYNC_JOB' ? `INSERT INTO from ${target}` : `SELECT from ${target}`;
}

// ─────────────────────────────────────────────────────────────────────────────
// Historique
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Une entrée d'historique dit maintenant ce que la requête a **donné**.
 *
 * Elle ne portait que `{sql, ts}` : pour retrouver « celle qui avait marché », il fallait relire
 * vingt requêtes et deviner. Les échecs n'y entraient même pas — or c'est souvent celle qui a
 * échoué que l'on veut rouvrir pour la corriger.
 *
 * Tous les champs de résultat sont optionnels : une entrée écrite par une version antérieure se
 * relit telle quelle et n'affiche simplement rien de plus.
 */
export interface HistoryEntry {
  sql: string;
  ts: number;
  /** Durée mesurée côté client, en millisecondes. */
  ms?: number;
  /** Lignes rendues (lecture synchrone seulement). */
  rows?: number;
  /** `FLINK` ou `KAFKA_DIRECT`, tel que rapporté par le moteur. */
  engine?: string;
  /** Faux quand la requête a échoué. Absent sur une entrée d'une version antérieure. */
  ok?: boolean;
}

export const HISTORY_CAP = 20;

/**
 * Ajoute une entrée en tête, en dédupliquant sur le SQL : relancer la même requête met à jour son
 * résultat plutôt que d'accumuler des lignes identiques dont seule l'heure diffère.
 */
export function pushHistory(
  history: readonly HistoryEntry[],
  entry: HistoryEntry,
  cap = HISTORY_CAP,
): HistoryEntry[] {
  const sql = entry.sql.trim();
  if (!sql) return [...history];
  return [{ ...entry, sql }, ...history.filter(h => h.sql !== sql)].slice(0, cap);
}

/** `1.2s` sous la seconde près, `340ms` en deçà. */
export function formatDuration(ms: number): string {
  return ms < 1000 ? `${Math.round(ms)}ms` : `${(ms / 1000).toFixed(1)}s`;
}

/**
 * Résumé compact d'une entrée, pour la ligne sous le SQL. Chaîne vide quand l'entrée ne porte
 * aucune information de résultat — ne rien afficher vaut mieux qu'afficher des zéros inventés.
 */
export function describeHistoryEntry(entry: HistoryEntry): string {
  const parts: string[] = [];
  if (entry.ok === false) parts.push('failed');
  if (typeof entry.ms === 'number') parts.push(formatDuration(entry.ms));
  if (typeof entry.rows === 'number') {
    parts.push(`${entry.rows.toLocaleString()} ${entry.rows === 1 ? 'row' : 'rows'}`);
  }
  if (entry.engine) parts.push(entry.engine === 'KAFKA_DIRECT' ? 'Kafka Direct' : entry.engine);
  return parts.join(' · ');
}

// ─────────────────────────────────────────────────────────────────────────────
// Partage par lien
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Le SQL porté par une query string, ou `null`.
 *
 * **`URLSearchParams.get()` décode déjà.** L'appelant enchaînait un `decodeURIComponent` par-dessus,
 * et cette seconde passe *lève* dès que le SQL contient un `%` : `LIKE '%foo%'` ressort de `get()`
 * en `%foo%`, que `decodeURIComponent` rejette avec `URIError: URI malformed`. Comme la lecture a
 * lieu dans un initialiseur `useState`, l'exception partait pendant le rendu et **la page entière ne
 * montait pas** — un écran d'erreur à la place de l'éditeur. Le prédicat le plus banal d'un outil
 * d'exploration était donc précisément celui qu'un lien ne pouvait pas transporter.
 */
export function readSqlParam(search: string): string | null {
  try {
    const value = new URLSearchParams(search).get('sql');
    return value && value.trim() ? value : null;
  } catch {
    return null;
  }
}

/**
 * Lien rejouable vers une requête. Vide si le SQL l'est — un lien qui n'ouvre rien ne se partage pas.
 *
 * L'éditeur *acceptait* un `?sql=` sans jamais en produire, à rebours de la convention du dépôt
 * (Stream Flow et le Topic Explorer font l'aller-retour complet de leur état et portent un bouton
 * « Link »). Les requêtes sauvegardées vivant dans le `localStorage` d'un seul navigateur, montrer
 * une requête à quelqu'un passait par un copier-coller.
 */
export function buildQueryLink(baseUrl: string, sql: string): string {
  const trimmed = (sql ?? '').trim();
  if (!trimmed) return '';
  const params = new URLSearchParams();
  params.set('sql', trimmed);
  return `${baseUrl}?${params.toString()}`;
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
export interface WorkbenchLayout {
  splitPercent: number;
  sidebarWidth: number;
  /**
   * L'assistant de fenêtrage prenait 288 px à l'éditeur en permanence, pour un outil occasionnel,
   * et rien ne permettait de le replier. Replié il laisse un rail, et l'état voyage avec le reste
   * de la disposition. Ouvert par défaut : c'est ainsi qu'il a toujours été, et le replier doit
   * rester une décision, pas une surprise à la mise à jour.
   */
  assistantOpen: boolean;
}

export const LAYOUT_STORAGE_KEY = 'kse:query-layout';
export const DEFAULT_LAYOUT: WorkbenchLayout = { splitPercent: 55, sidebarWidth: 288, assistantOpen: true };
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
    // Une disposition écrite avant que ce champ existe n'en porte pas : elle vaut « ouvert ».
    assistantOpen: typeof stored?.assistantOpen === 'boolean'
      ? stored.assistantOpen : DEFAULT_LAYOUT.assistantOpen,
  };
}
