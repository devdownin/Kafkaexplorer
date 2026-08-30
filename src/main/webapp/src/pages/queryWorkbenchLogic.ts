// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors

/**
 * Logique pure de l'éditeur SQL (QueryWorkbench).
 *
 * Tout ce qui peut être décidé sans Monaco, sans React et sans réseau vit ici : le formatage du
 * SQL, le tri de la grille de résultats, le choix de l'onglet actif après une fermeture, le rendu
 * d'une cellule, l'écriture défensive dans `localStorage`. Même raison que `streamFlow.ts` et
 * `topicSearch.ts` — une page de 1 400 lignes n'est testable que par ce qu'on en a sorti.
 */
import { toTableName } from './sqlScope';
import type { QueryResult } from '../api/types';
import type { QueryErrorInfo } from './queryError';
import { isInternalTopic, isInternalTable } from '../internalTopics';

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

/**
 * L'inverse de `positionAt` : le décalage d'une ligne/colonne (base 1).
 *
 * Monaco sait le faire (`model.getOffsetAt`), mais le calculer ici garde le plan d'exécution
 * décidable sans éditeur — donc testable, ce qui est toute la raison d'être de ce module.
 */
export function offsetAt(text: string, line: number, column: number): number {
  let offset = 0;
  for (let l = 1; l < line; l += 1) {
    const next = text.indexOf('\n', offset);
    if (next === -1) return text.length;
    offset = next + 1;
  }
  return Math.min(text.length, offset + Math.max(0, column - 1));
}

/**
 * Où se trouve, **dans le document tel qu'il est maintenant**, le SQL qui a été exécuté — ou `null`
 * quand il n'y est plus.
 *
 * Les positions que le moteur renvoie (ligne, colonne) sont relatives au fragment envoyé ; il faut
 * donc une origine pour les ramener dans le repère du document. Cette origine était *figée au
 * moment du run*, ce qui la rend fausse dès que le texte bouge : ajouter une ligne au-dessus
 * décalait le « Jump to line » d'autant, et rouvrir le résultat d'une instruction d'un lot après
 * une édition pointait une position qui avait glissé. La dériver du texte courant la corrige toute
 * seule dans le premier cas, et permet dans le second de dire honnêtement qu'on ne sait plus.
 *
 * `null` veut dire « le texte mesuré n'est plus là » : l'appelant retire alors la position au lieu
 * d'en désigner une au hasard — le message brut du moteur, lui, porte toujours sa ligne et sa
 * colonne, relatives à ce qui avait été envoyé.
 *
 * Deux passes, dans cet ordre : la recherche littérale d'abord, qui couvre le cas courant (rien n'a
 * bougé) et vaut aussi pour un fragment sélectionné, qui n'est pas une instruction ; puis la
 * comparaison normalisée aux instructions du document, qui rattrape un simple reformatage. Deux
 * instructions identiques dans un même onglet rendent la première : elles sont indiscernables, et
 * choisir la première est au moins déterministe.
 */
export function resolveOrigin(sql: string, ranSql: string | null): { line: number; column: number } | null {
  if (!ranSql || !ranSql.trim()) return null;
  const literal = sql.indexOf(ranSql);
  if (literal !== -1) return positionAt(sql, literal);
  const normalize = (s: string) => s.replace(/\s+/g, ' ').trim();
  const ran = normalize(ranSql);
  const match = splitStatements(sql).find(st => normalize(st.text) === ran);
  return match ? positionAt(sql, match.start) : null;
}

// ─────────────────────────────────────────────────────────────────────────────
// Ce qu'un geste « Run » va envoyer
// ─────────────────────────────────────────────────────────────────────────────

/** Une instruction prête à partir, avec son décalage **dans le document**. */
export interface PlannedStatement {
  text: string;
  /** Décalage du premier caractère dans le document, d'où viennent les positions d'erreur. */
  start: number;
}

/**
 * Les instructions qu'un geste d'exécution va envoyer, dans l'ordre.
 *
 * Un seul point d'entrée pour les trois gestes, parce qu'ils se distinguent uniquement par leur
 * portée et que les laisser diverger, c'est laisser diverger ce qui part au moteur :
 *
 * - **une sélection**, quand l'appelant en fournit une, l'emporte : c'est le geste explicite
 *   « exécute *ça* » (l'éditeur ne la transmet donc pas pour un « Run all », qui annonce l'onglet
 *   entier). Elle est
 *   découpée comme le document — une sélection qui enjambe deux instructions partait auparavant
 *   telle quelle en une seule requête, que le planner rejetait sur le `;`, alors que sélectionner
 *   deux requêtes et les lancer est un geste parfaitement ordinaire. Un fragment sans `;` reste
 *   envoyé tel quel : sélectionner une sous-expression pour l'essayer doit continuer de marcher.
 * - **`all`** prend toutes les instructions de l'onglet ;
 * - **`cursor`** prend celle où se trouve le curseur, et **rien** quand il n'y en a aucune.
 *
 * Un tableau vide n'est pas une erreur silencieuse : c'est ce qui permet à l'appelant de dire
 * « il n'y a rien à exécuter ici » au lieu d'envoyer une chaîne vide au moteur pour qu'il le dise
 * à sa place, dans ses termes à lui.
 */
export function planRun(
  sql: string,
  cursorOffset: number,
  selection: { start: number; text: string } | null,
  scope: 'cursor' | 'all',
): PlannedStatement[] {
  if (selection && selection.text.trim()) {
    const inner = splitStatements(selection.text);
    if (inner.length <= 1) {
      // Un fragment : envoyé tel quel, aux espaces de bord près, pour que le décalage d'erreur
      // désigne son premier caractère utile et non le blanc qui le précède.
      const leading = selection.text.length - selection.text.trimStart().length;
      const text = selection.text.trim();
      return text ? [{ text, start: selection.start + leading }] : [];
    }
    return inner.map(s => ({ text: s.text, start: selection.start + s.start }));
  }

  const statements = splitStatements(sql);
  if (scope === 'all') return statements.map(s => ({ text: s.text, start: s.start }));
  const index = statementIndexAt(statements, cursorOffset);
  const statement = index >= 0 ? statements[index] : undefined;
  return statement ? [{ text: statement.text, start: statement.start }] : [];
}

/**
 * Aperçu d'une instruction sur une ligne, pour la nommer dans la liste d'un lot.
 *
 * Les commentaires sont retirés d'abord : le SQL que la barre latérale pose commence par deux
 * lignes de commentaire, dont un aperçu brut ne montrerait que celles-là — donc la même étiquette
 * pour toutes les instructions du lot.
 */
export function previewStatement(sql: string, max = 64): string {
  const bare = (sql ?? '')
    .replace(/\/\*[\s\S]*?\*\//g, ' ')
    .replace(/--[^\n]*/g, ' ')
    .replace(/\s+/g, ' ')
    .trim() || (sql ?? '').replace(/\s+/g, ' ').trim();
  return bare.length > max ? `${bare.slice(0, max - 1)}…` : bare;
}

/**
 * Type d'instruction, classé après une éventuelle chaîne CTE de tête — comme le backend
 * (`SqlStatements.withoutLeadingCte`) : `WITH … INSERT INTO` est un INSERT et `WITH … SELECT` une
 * lecture. Sans cela, la garde de mode d'exécution voyait « WITH » et se trompait de camp.
 */
export function detectStatementType(sql: string): string {
  const stripped = withoutLeadingCte(
    (sql ?? '').replace(/\/\*[\s\S]*?\*\//g, ' ').replace(/--[^\n]*/g, '').trim(),
  ).toUpperCase();
  // Un STATEMENT SET est la façon dont Flink écrit un fan-out : plusieurs INSERT depuis une même
  // source dans un seul job. Classé avant l'INSERT, puisqu'il en contient — et reconnu ici parce
  // que le garde du mode d'exécution refuse côté navigateur ce qu'il ne sait pas nommer, donc sans
  // ce cas la forme partait à l'erreur avant même d'atteindre le serveur qui l'accepte.
  if (stripped.startsWith('EXECUTE STATEMENT SET') || stripped.startsWith('STATEMENT SET')) {
    return 'STATEMENT_SET';
  }
  if (stripped.startsWith('INSERT INTO')) return 'INSERT';
  if (stripped.startsWith('CREATE TABLE')) return 'CREATE_TABLE';
  if (stripped.startsWith('SELECT')) return 'SELECT';
  if (stripped.startsWith('EXPLAIN')) return 'EXPLAIN';
  return stripped.split(/\s+/, 1)[0] || 'UNKNOWN';
}

/**
 * Ce que le mode Job soumet : un INSERT continu, ou un STATEMENT SET qui en réunit plusieurs.
 *
 * Miroir de `FlinkSqlService.isJobModeStatement`, et il doit le rester : ce garde-ci refuse avant
 * d'appeler le serveur, donc une forme qu'il ignore n'atteint jamais celui qui l'accepte.
 */
export function isJobModeStatement(type: string): boolean {
  return type === 'INSERT' || type === 'STATEMENT_SET';
}

/**
 * La cible et la source d'un `INSERT INTO <cible> … FROM <source>`.
 *
 * En mode Job la cible doit exister — un nom inventé ne peut qu'échouer — et rien n'aidait à la
 * créer : sans table déclarée il fallait repasser en mode lecture et écrire le `CREATE TABLE` à la
 * main. Ce couple est ce dont `/api/query/sink-ddl` a besoin pour le générer à partir des colonnes
 * de la source.
 *
 * Volontairement lexical et volontairement étroit : une forme qui n'est pas reconnue rend `null`,
 * et le bouton ne s'affiche pas — proposer de créer une table dont on a mal lu le nom serait pire
 * que de ne rien proposer.
 */
export function insertTargetAndSource(sql: string): { target: string; source: string | null } | null {
  const stripped = (sql ?? '').replace(/\/\*[\s\S]*?\*\//g, ' ').replace(/--[^\n]*/g, ' ');
  const target = /\bINSERT\s+(?:INTO|OVERWRITE)\s+[`"]?([\w.-]+)[`"]?/i.exec(stripped);
  if (!target) return null;
  const source = /\bFROM\s+[`"]?([\w.-]+)[`"]?/i.exec(stripped);
  const sourceName = source && source[1].toUpperCase() !== 'TABLE' ? source[1] : null;
  return { target: target[1], source: sourceName };
}

// ─────────────────────────────────────────────────────────────────────────────
// Complétion : les propositions, sans Monaco
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Une proposition de complétion, **sans sa plage** : celle-ci dépend du mot sous le curseur, donc
 * de l'appel, tandis que tout le reste ne dépend que du catalogue et de la portée. C'est cette
 * séparation qui permet de ne plus tout reconstruire à chaque frappe.
 */
export interface CompletionEntry {
  label: string;
  /** Traduit en `CompletionItemKind` par l'appelant, seul à connaître Monaco. */
  kind: 'table' | 'column' | 'keyword';
  insertText: string;
  detail?: string;
  documentation?: string;
  sortText?: string;
}

/** Mots-clés Flink proposés en plus du catalogue. */
export const SQL_KEYWORD_SUGGESTIONS: readonly string[] = [
  'TUMBLE', 'HOP', 'SESSION', 'CUMULATE', 'DESCRIPTOR', 'PROCTIME', 'ROWTIME',
  'WATERMARK', 'EMIT CHANGES', 'INTERVAL', 'OVER', 'PARTITION BY',
  'JSON_VALUE', 'JSON_QUERY', 'JSON_EXISTS',
];

/**
 * Les propositions pour un catalogue et une portée donnés.
 *
 * Le fournisseur Monaco est invoqué **à chaque caractère** (`quickSuggestions`), et il reconstruisait
 * tout à chaque fois : un objet par table, un `toTableName()` et un `sortText` interpolé par topic,
 * un `Set` des tables enregistrées, un `Object.entries` par table chargée. Sur un cluster de trois
 * cents topics, cela fait quelques centaines d'objets et autant de chaînes fabriquées par frappe,
 * pour un résultat identique tant que ni le catalogue ni la portée n'ont bougé — la page mémoïse
 * donc cette liste sur ces deux seules entrées, et n'attache plus que la plage à chaque appel.
 *
 * Les topics Kafka figurent dans la liste au même titre que les tables : la barre latérale les
 * écrit, le backend les auto-enregistre au premier SELECT, et la complétion n'en proposait aucun —
 * la moitié du catalogue sur lequel l'éditeur est bâti était invisible à la saisie. Le nom proposé
 * est celui de la table (points et tirets en underscores), et un topic déjà enregistré n'est pas
 * proposé deux fois.
 */
export function buildCompletionEntries(
  catalogue: CatalogLike | null | undefined,
  loadedSchemas: Readonly<Record<string, Record<string, string>>>,
  scope: readonly string[],
): CompletionEntry[] {
  const entries: CompletionEntry[] = [];
  const registered = new Set(catalogue?.tables ?? []);

  (catalogue?.tables ?? []).forEach(table => entries.push({
    label: table, kind: 'table', insertText: table, detail: 'Flink Table',
  }));

  (catalogue?.topics ?? []).forEach(topic => {
    const asTable = toTableName(topic);
    if (registered.has(asTable)) return;
    entries.push({
      label: asTable,
      kind: 'table',
      insertText: asTable,
      detail: 'Kafka topic — registered on first use',
      documentation: topic === asTable ? undefined : `Topic ${topic}`,
      // Derrière les tables déjà enregistrées, qui sont un choix explicite de l'utilisateur.
      sortText: `2_${asTable}`,
    });
  });

  // Colonnes : limitées aux tables que la requête cite réellement. Proposer celles de toutes les
  // tables chargées noyait les bonnes au milieu de dizaines d'inutiles. Tant qu'aucune table n'est
  // citée (curseur dans le SELECT avant le FROM), on retombe sur tout ce qui est connu plutôt que
  // de ne rien proposer.
  const scoped = scope.filter(t => loadedSchemas[t]);
  const columnSources = scoped.length ? scoped : Object.keys(loadedSchemas);
  columnSources.forEach(tableName =>
    Object.entries(loadedSchemas[tableName] ?? {}).forEach(([col, type]) => entries.push({
      label: col, kind: 'column', insertText: col, detail: type,
      documentation: `${tableName}.${col}`,
      // Les colonnes en portée passent devant les mots-clés, triés par défaut sur le label.
      sortText: scoped.length ? `0_${col}` : `1_${col}`,
    })));

  SQL_KEYWORD_SUGGESTIONS.forEach(kw => entries.push({ label: kw, kind: 'keyword', insertText: kw }));
  return entries;
}

// ─────────────────────────────────────────────────────────────────────────────
// Le lot : ce que chaque instruction a donné, et pas seulement la dernière
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Issue d'une instruction dans un lot.
 *
 * `skipped` est distinct de `pending` et le reste : une instruction que le lot n'a **jamais**
 * atteinte — parce qu'une précédente a échoué, ou parce qu'on a arrêté — n'a pas « réussi sans
 * lignes », et c'est exactement le genre de zéro que cet écran ne doit pas afficher.
 */
export type StatementStatus = 'pending' | 'running' | 'ok' | 'failed' | 'cancelled' | 'skipped';

/**
 * Ce qu'une instruction d'un lot a donné, conservé pour qu'on puisse y revenir.
 *
 * « Run all » n'affichait que le résultat de la **dernière** instruction : lancer cinq requêtes
 * laissait une grille et aucune trace des quatre autres — ni lignes, ni durée, ni moteur, ni même
 * la preuve qu'elles avaient tourné. Le lot garde donc une entrée par instruction, et la grille
 * montre celle qu'on sélectionne.
 */
export interface StatementRun {
  /** Rang dans le lot, base 1 — c'est le numéro que l'utilisateur lit. */
  index: number;
  sql: string;
  /** SELECT, INSERT, CREATE_TABLE… — voir `detectStatementType`. */
  kind: string;
  status: StatementStatus;
  /** Durée mesurée côté client, absente tant que l'instruction n'a pas fini. */
  ms?: number;
  /** Lignes rendues. Absent — jamais 0 — quand la question n'a pas été posée. */
  rows?: number;
  engine?: string;
  /**
   * Plafond de lignes sous lequel elle a tourné. Conservé avec elle, sinon le badge « limit
   * reached » jugerait un résultat ancien à l'aune du sélecteur tel qu'il est réglé maintenant.
   */
  limit?: number;
  /** Le résultat conservé, ou `null` s'il n'y en a pas (échec, ou lignes libérées). */
  result?: QueryResult | null;
  error?: QueryErrorInfo | null;
  /**
   * Les lignes ont été libérées pour borner ce que le lot retient. Le compte (`rows`) reste, lui :
   * « 4 000 lignes, plus affichables » est une réponse, « 0 ligne » en serait une fausse.
   */
  forgotten?: boolean;
}

/**
 * Plafond de lignes que l'ensemble d'un lot conserve en mémoire.
 *
 * Une instruction peut rendre jusqu'à 5 000 lignes (`ROW_LIMITS`) et un lot n'a pas de longueur
 * maximale : tout garder, c'est laisser un onglet de vingt requêtes retenir cent mille lignes
 * pour la seule éventualité qu'on y revienne.
 */
export const MAX_RETAINED_BATCH_ROWS = 10_000;

/**
 * Libère les lignes des plus **anciennes** instructions jusqu'à rentrer dans le budget.
 *
 * Les plus anciennes, parce qu'on remonte un lot depuis sa fin ; et jamais la dernière, qui est
 * celle que la grille affiche. Ce qui est libéré est **marqué** (`forgotten`) au lieu d'être
 * silencieusement vidé : une grille vide et un résultat qu'on ne garde plus sont deux réponses
 * différentes, et l'écran doit pouvoir les distinguer.
 */
export function forgetOldestResults(
  runs: readonly StatementRun[],
  budget = MAX_RETAINED_BATCH_ROWS,
): StatementRun[] {
  const out = runs.map(r => ({ ...r }));
  let retained = out.reduce((n, r) => n + (r.forgotten ? 0 : r.result?.rows.length ?? 0), 0);
  for (let i = 0; i < out.length - 1 && retained > budget; i += 1) {
    const run = out[i];
    const held = run.forgotten ? 0 : run.result?.rows.length ?? 0;
    if (!held) continue;
    retained -= held;
    run.result = null;
    run.forgotten = true;
  }
  return out;
}

/** Compte des issues d'un lot, dans l'ordre où on veut les lire. */
export function summariseBatch(runs: readonly StatementRun[]): string {
  const count = (s: StatementStatus) => runs.filter(r => r.status === s).length;
  const parts = [`${runs.length} statement${runs.length === 1 ? '' : 's'}`];
  const ok = count('ok');
  if (ok) parts.push(`${ok} ok`);
  const failed = count('failed');
  if (failed) parts.push(`${failed} failed`);
  const cancelled = count('cancelled');
  if (cancelled) parts.push(`${cancelled} cancelled`);
  const skipped = count('skipped');
  if (skipped) parts.push(`${skipped} never run`);
  return parts.join(' · ');
}

/** Résumé d'une entrée pour sa puce : durée, lignes, moteur — rien d'inventé. */
export function describeStatementRun(run: StatementRun): string {
  const parts: string[] = [];
  if (typeof run.ms === 'number') parts.push(formatDuration(run.ms));
  if (typeof run.rows === 'number') {
    parts.push(`${run.rows.toLocaleString()} ${run.rows === 1 ? 'row' : 'rows'}`);
  }
  if (run.engine) parts.push(run.engine === 'KAFKA_DIRECT' ? 'Kafka Direct' : run.engine);
  if (run.status === 'skipped') parts.push('never run');
  if (run.status === 'cancelled') parts.push('stopped');
  if (run.forgotten) parts.push('rows released');
  return parts.join(' · ');
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
export function isResultStale(
  ranSql: string | null,
  currentSql: string,
  statements?: readonly SqlStatement[],
): boolean {
  if (ranSql === null) return false;
  const normalize = (s: string) => s.replace(/\s+/g, ' ').trim();
  const ran = normalize(ranSql);
  // Une instruction parmi plusieurs : le texte exécuté n'est *jamais* égal au contenu de
  // l'onglet, donc la comparaison brute déclarait périmé tout résultat d'un onglet
  // multi-instructions — un avertissement permanent, qui ne veut plus rien dire dès qu'il est
  // toujours là. Le résultat reste à jour tant que ce qui a tourné est encore, mot pour mot,
  // l'une des instructions du document ; éditer *une autre* instruction ne périme pas celle-ci.
  if (statements?.some(s => normalize(s.text) === ran)) return false;
  return ran !== normalize(currentSql);
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
export interface StarterQuery {
  label: string;
  sql: string;
  hint: string;
}

export interface CatalogLike { tables: string[]; topics: string[] }

/**
 * Table sur laquelle bâtir les propositions : une table Flink si le catalogue en a une, sinon le
 * premier topic. Les topics `internal.*` sont ceux que l'application s'écrit à elle-même (historique
 * d'audit, configuration des métriques) — une première impression bâtie dessus n'apprend rien du
 * cluster que l'on vient explorer.
 */
export function starterTable(
  catalog: CatalogLike | null | undefined, internalPrefix = '',
): string | null {
  if (!catalog) return null;
  const table = catalog.tables.find(t => !isInternalTable(t, internalPrefix));
  if (table) return table;
  const topic = catalog.topics.find(t => !isInternalTopic(t, internalPrefix));
  return topic ? toTableName(topic) : null;
}

export function starterQueries(
  catalog: CatalogLike | null | undefined, internalPrefix = '',
): StarterQuery[] {
  const table = starterTable(catalog, internalPrefix);
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

/**
 * Le SQL qu'un clic sur une table ou un topic de la barre latérale pose dans l'éditeur.
 *
 * L'éditeur n'a plus qu'un mode d'exécution : il lit. Le mode « Flink job », qui posait ici un
 * squelette d'`INSERT INTO` et le soumettait à `POST /api/query/jobs`, a été retiré — il ne
 * fonctionnait pas, et un geste dont le seul résultat possible est un échec vaut moins qu'un geste
 * absent. Ce qui en dépendait a été retiré avec lui plutôt que laissé sans appelant : le choix de
 * la table cible, la liste des colonnes inscriptibles et la sélection posée sur le nom de sink
 * n'avaient de sens que pour cet INSERT.
 */
export function sidebarSqlFor(table: string, maxRows: number): string {
  return `SELECT * FROM ${table} LIMIT ${maxRows}`;
}

/** Ce que le clic va faire, pour l'intitulé accessible du bouton. */
export function sidebarActionLabel(target: string): string {
  return `SELECT from ${target}`;
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

// ── Fenêtre trop étroite ─────────────────────────────────────────────────────

/**
 * En dessous de cette largeur, cette page ne propose pas une surface d'écriture.
 *
 * Le chiffre n'est pas choisi : `MOBILE-LAYOUT-SCOPE.md` a mesuré Monaco contre la largeur de
 * fenêtre, et l'éditeur ne dépasse 200 px de rendu qu'à partir de 1024 — 5 px à 390, 5 px à 768,
 * 192 px à 1024. C'est aussi le seuil auquel le rail de navigation entre dans le flux
 * (`DESKTOP_QUERY` dans `Layout.tsx`) : les deux décrivent le même « il y a la place ».
 */
export const WIDE_WINDOW_MIN_PX = 1024;

export const NARROW_NOTICE_KEY = 'kse:query-narrow-notice';

export interface NarrowAlternative {
  label: string;
  to: string;
  /** Ce que l'écran répond, pour que le choix ne se fasse pas au nom seul. */
  answers: string;
}

/**
 * Les écrans qui, eux, fonctionnent dans une fenêtre étroite.
 *
 * Mesurés, pas supposés : à 390 px le document ne déborde sur aucun d'eux et rien n'y est tronqué
 * (tableau « Nothing scrolls sideways » du document de cadrage). Les nommer est la moitié utile du
 * bandeau — « cet écran demande une fenêtre plus large » tout court renvoie l'opérateur nulle part,
 * au moment précis où il cherche une réponse. Leur seul défaut mesuré est la taille des cibles
 * tactiles, qui est app-wide et n'a rien à voir avec la largeur.
 */
export const NARROW_ALTERNATIVES: NarrowAlternative[] = [
  // Le Topic Explorer est mesuré bon à 390 px lui aussi, mais il n'a pas de route sans nom de
  // topic (`/topic/:name`) : y renvoyer sans en nommer un tomberait sur le 404. C'est le Dashboard
  // qui y mène, et c'est ce que dit la ligne.
  { label: 'Dashboard', to: '/', answers: 'topics, sizes, and the way into a topic’s messages' },
  { label: 'Audit', to: '/audit', answers: 'the last cluster health run' },
  { label: 'Cluster', to: '/cluster', answers: 'brokers, quorum and client groups' },
];

export function readNarrowNoticeDismissed(): boolean {
  return readStored<boolean>(NARROW_NOTICE_KEY, false) === true;
}

export function dismissNarrowNotice(): boolean {
  return writeStored(NARROW_NOTICE_KEY, true);
}
