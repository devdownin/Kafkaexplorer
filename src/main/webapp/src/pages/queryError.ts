// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors

/**
 * Traduction des erreurs SQL brutes (Flink / Calcite / validateur backend) en
 * un message lisible + une piste d'action, sans perdre le texte d'origine.
 *
 * Les messages renvoyés par le planner Flink/Calcite sont cryptiques pour un
 * utilisateur ("SQL parse failed. Encountered ... at line 3, column 5",
 * "Object 'FOO' not found", "Column 'bar' not found"). On les classe en
 * familles connues pour afficher un titre clair, une suggestion concrète, et —
 * quand c'est disponible — la position (ligne/colonne) pour pointer l'éditeur
 * dessus. Fonction pure → testable et réutilisable.
 */
export interface QueryErrorLocation {
  line: number;
  column: number;
}

export interface QueryErrorInfo {
  /** Titre court et lisible (ex. « Unknown table "orders" »). */
  title: string;
  /** Suggestion d'action, optionnelle. */
  hint?: string;
  /** Position dans le SQL si l'erreur en cite une. */
  location?: QueryErrorLocation;
  /** Message brut d'origine, toujours conservé pour les détails. */
  raw: string;
}

/** Extrait « line L, column C » (Calcite) — première occurrence. */
export function parseSqlLocation(message: string): QueryErrorLocation | undefined {
  const m = /line\s+(\d+),\s*column\s+(\d+)/i.exec(message);
  if (!m) return undefined;
  const line = Number(m[1]);
  const column = Number(m[2]);
  if (!Number.isFinite(line) || !Number.isFinite(column) || line < 1 || column < 1) return undefined;
  return { line, column };
}

/** Longueur max d'un titre : le backend chaîne désormais les causes, le brut peut être long. */
const MAX_TITLE_CHARS = 180;

/** Première ligne non vide, bornée — repli quand aucune famille ne matche. */
function firstLine(message: string): string {
  let candidate = message.trim();
  for (const line of message.split('\n')) {
    const t = line.trim();
    if (t) { candidate = t; break; }
  }
  return candidate.length > MAX_TITLE_CHARS ? `${candidate.slice(0, MAX_TITLE_CHARS)}…` : candidate;
}

/**
 * Reporte une position rapportée dans un fragment exécuté vers les coordonnées du document.
 *
 * Quand on n'exécute que la sélection, le moteur numérote les lignes depuis le début de ce
 * fragment. Sans ce décalage, « line 1, column 3 » soulignerait la première ligne du document
 * au lieu de la première ligne de la sélection. Seule la première ligne du fragment subit aussi
 * un décalage de colonne : les suivantes commencent bien en colonne 1.
 */
export function offsetLocation(
  location: QueryErrorLocation | undefined,
  origin: QueryErrorLocation | undefined,
): QueryErrorLocation | undefined {
  if (!location) return undefined;
  if (!origin) return location;
  return {
    line: origin.line + location.line - 1,
    column: location.line === 1 ? origin.column + location.column - 1 : location.column,
  };
}

export function describeQueryError(rawInput: string | null | undefined): QueryErrorInfo {
  const raw = (rawInput ?? '').trim();
  const location = parseSqlLocation(raw);

  if (!raw) {
    return { title: 'Query failed', hint: 'The engine returned no error detail. Try running the query again.', raw };
  }

  // Le validateur backend préfixe « SQL Validation Error: ». On déballe pour
  // classer le vrai motif dessous (souvent une erreur Calcite).
  const unwrapped = raw.replace(/^SQL Validation Error:\s*/i, '');

  // ── Broker injoignable ──────────────────────────────────────────────────
  if (/cannot reach kafka broker|connection to .* (?:failed|refused)|no resolvable bootstrap|failed to construct kafka|broker may not be available/i.test(raw)) {
    return {
      title: 'Kafka is unreachable',
      hint: 'The broker didn’t respond. Check it’s running and that the bootstrap servers in Settings are correct.',
      location, raw,
    };
  }

  // ── Timeout ─────────────────────────────────────────────────────────────
  if (/query timed out|timed out|timeoutexception/i.test(raw)) {
    return {
      title: 'Query timed out',
      hint: 'Add a LIMIT, tighten the WHERE clause, or scan fewer messages — the default timeout is 10s.',
      location, raw,
    };
  }

  // ── Statement non autorisé (whitelist) ──────────────────────────────────
  if (/cross joins are not allowed|access to system tables is restricted|not allowed in this environment|only (?:select|the following)/i.test(unwrapped)) {
    return {
      title: 'Statement not permitted',
      hint: 'This editor runs SELECT, EXPLAIN, SHOW, DESCRIBE and CREATE TABLE. Anything that writes — INSERT, UPDATE, DROP, ALTER — is refused here.',
      location, raw,
    };
  }

  // ── CREATE TABLE … AS SELECT ────────────────────────────────────────────
  // Refus propre à cette application : le CTAS crée la table *et* démarre le job qui l'alimente,
  // hors de tout registre. Le message du serveur nomme déjà la marche à suivre ; sans famille il
  // arrivait en titre brut de deux lignes, sans piste.
  if (/as select starts a job/i.test(unwrapped)) {
    return {
      title: 'CREATE TABLE … AS SELECT is not run here',
      hint: 'It would start a writing job that the dashboard cannot see and Stop cannot reach. Declare the table with CREATE TABLE, then run the INSERT INTO separately.',
      location, raw,
    };
  }

  // ── Ce que le planner *streaming* ne sait pas construire ────────────────
  // Du SQL valide qui n'a pas de sens sur un flux. Ces deux-là remontent désormais à l'appelant
  // au lieu de se replier sur le lecteur direct — et arrivaient donc en titre brut.
  if (/sort on a non-time-attribute field is not supported/i.test(unwrapped)) {
    return {
      title: 'ORDER BY needs a bound on a stream',
      hint: 'A stream has no last row, so it cannot be sorted whole. Add a LIMIT, or sort on the time attribute the table declares.',
      location, raw,
    };
  }
  // Une colonne horodatée n'est pas un attribut temporel tant qu'aucun watermark ne le déclare.
  // Le message brut de Flink arrive enveloppé dans la règle Calcite qui a échoué et son plan ;
  // sans famille, ce pavé passait pour titre.
  if (/requires the timecol is a time attribute|(?:must|should) be defined on a time attribute|is not a time attribute|must be a time attribute/i.test(unwrapped)) {
    return {
      title: 'That column is not a time attribute',
      hint: 'Event-time windows and OVER need a column carrying a watermark. Tables this explorer registers declare one on event_time — window over that, or declare the table yourself with WATERMARK FOR <col> AS <col> - INTERVAL \'5\' SECOND.',
      location, raw,
    };
  }
  if (/unexpected correlate variable|correlate variable \$cor/i.test(unwrapped)) {
    return {
      title: 'Correlated subquery not supported',
      hint: 'A subquery that refers to the outer row (EXISTS, IN with a correlated predicate) cannot be planned on a stream. Rewrite it as a JOIN on the correlating column.',
      location, raw,
    };
  }

  // ── Projection qui ne rentre pas dans la table cible ────────────────────
  // Une seule et même faute, que Flink formule de plusieurs façons — dont « Different number of
  // columns », la plus courante : `INSERT INTO sink SELECT * FROM source` ramène la colonne
  // calculée `proc_time` qu'aucun sink n'accepte. Placée avant la famille « types incompatibles »,
  // qui l'attraperait et dirait de caster une colonne.
  if (/different number of columns|column types of query result and sink|incompatible types for sink column/i.test(unwrapped)) {
    return {
      title: 'The query does not fit the target table',
      hint: 'List the sink’s columns explicitly instead of SELECT * — a generated table carries a computed proc_time column that no sink accepts.',
      location, raw,
    };
  }
  if (/insert overwrite requires/i.test(unwrapped)) {
    return {
      title: 'This table cannot be overwritten',
      hint: 'The connector behind it does not support INSERT OVERWRITE. Use a plain INSERT INTO, or write to a table whose connector does.',
      location, raw,
    };
  }
  if (/cannot be enriched with new options/i.test(unwrapped)) {
    return {
      title: 'Options hint applied to a view',
      hint: 'An /*+ OPTIONS(...) */ hint only applies to a table. Put it on the table the view reads, or query that table directly.',
      location, raw,
    };
  }

  // ── Table / objet introuvable ───────────────────────────────────────────
  const tableMatch = /(?:Object|Table)\s+'([^']+)'\s+not found/i.exec(unwrapped)
    ?? /(?:Object|Table)\s+"([^"]+)"\s+not found/i.exec(unwrapped)
    ?? /Table\s+\(or view\)\s+'([^']+)'\s+does not exist/i.exec(unwrapped);
  if (tableMatch) {
    return {
      title: `Unknown table “${tableMatch[1]}”`,
      hint: 'Check the exact name in the Schema Browser on the left. A raw topic is auto-registered on a plain SELECT — dots and dashes in topic names become underscores.',
      location, raw,
    };
  }

  // ── Colonne introuvable ─────────────────────────────────────────────────
  const columnMatch = /Column\s+'([^']+)'\s+not found/i.exec(unwrapped)
    ?? /Unknown\s+column\s+'?([^'\s.]+)'?/i.exec(unwrapped)
    ?? /Column\s+"([^"]+)"\s+not found/i.exec(unwrapped);
  if (columnMatch) {
    return {
      title: `Unknown column “${columnMatch[1]}”`,
      hint: 'Expand the table in the Schema Browser to see its columns, and check the spelling and case.',
      location, raw,
    };
  }

  // ── Erreur de syntaxe ───────────────────────────────────────────────────
  if (/sql parse failed|parseexception|encountered\s+["']|syntax error|non-query expression|was expecting/i.test(unwrapped)) {
    return {
      title: 'Syntax error',
      hint: location
        ? `Check for a typo, a missing keyword, comma or quote near line ${location.line}:${location.column}.`
        : 'Check for a typo, a missing keyword, comma or quote.',
      location, raw,
    };
  }

  // ── Fonction inconnue ───────────────────────────────────────────────────
  const functionMatch = /No match found for function signature\s+([A-Za-z_][\w]*)/i.exec(unwrapped);
  if (functionMatch) {
    return {
      title: `Unknown function “${functionMatch[1]}”`,
      hint: 'Check the spelling and the argument types — the query runs on Flink SQL, whose built-in functions differ from those of other SQL dialects.',
      location, raw,
    };
  }

  // ── Types incompatibles ─────────────────────────────────────────────────
  if (/cannot apply\s+'|incompatible types|argument type mismatch|cannot be cast to/i.test(unwrapped)) {
    return {
      title: 'Incompatible types',
      hint: 'An operator or function got a type it cannot handle. Cast the column explicitly, e.g. CAST(amount AS DOUBLE).',
      location, raw,
    };
  }

  // ── GROUP BY manquant ───────────────────────────────────────────────────
  const groupingMatch = /Expression\s+'([^']+)'\s+is not being grouped/i.exec(unwrapped);
  if (groupingMatch) {
    return {
      title: `“${groupingMatch[1]}” must be grouped or aggregated`,
      hint: 'Every selected column that is not aggregated has to appear in the GROUP BY clause.',
      location, raw,
    };
  }

  // ── Alias d'agrégat manquant (moteur direct) ────────────────────────────
  if (/metric_value|alias/i.test(unwrapped) && /aggregate|count|sum|avg/i.test(unwrapped)) {
    return {
      title: 'Aggregate needs an alias',
      hint: 'Alias the aggregate column, e.g. COUNT(*) AS metric_value.',
      location, raw,
    };
  }

  // ── Repli générique ─────────────────────────────────────────────────────
  return { title: firstLine(unwrapped), location, raw };
}

/**
 * Extrait le message le plus parlant d'une erreur d'appel API (axios ou autre),
 * sans importer axios (duck-typing → module pur et testable). Ordre de
 * préférence : corps `message`/`error` renvoyé par le backend → statut HTTP →
 * message d'exception → repli.
 */
export function extractApiErrorMessage(error: unknown, fallback = 'Request failed'): string {
  if (typeof error === 'string' && error.trim()) return error;
  const e = error as {
    response?: { data?: unknown; status?: number; statusText?: string };
    code?: string;
    message?: string;
  } | null | undefined;

  const data = e?.response?.data;
  if (typeof data === 'string' && data.trim()) return data;
  if (data && typeof data === 'object') {
    const rec = data as Record<string, unknown>;
    if (typeof rec.message === 'string' && rec.message.trim()) return rec.message;
    if (typeof rec.error === 'string' && rec.error.trim()) return rec.error;
  }

  const status = e?.response?.status;
  if (status) return `Server responded ${status}${e?.response?.statusText ? ` ${e.response.statusText}` : ''}`;

  if (typeof e?.message === 'string' && e.message.trim()) return e.message;
  return fallback;
}

/**
 * Décrit une erreur d'appel API pour l'affichage : gère d'abord les pannes de
 * transport (serveur injoignable, requête interrompue) qui n'ont pas de corps
 * de réponse, puis délègue le reste à `describeQueryError` — les familles
 * SQL-spécifiques ne se déclenchent que sur des sous-chaînes SQL, donc une
 * erreur non-SQL n'est jamais mal étiquetée « Syntax error ».
 */
export function describeApiError(error: unknown, fallback = 'Request failed'): QueryErrorInfo {
  const e = error as { response?: unknown; code?: string; message?: string } | null | undefined;
  if (e && typeof e === 'object' && !e.response) {
    if (e.code === 'ECONNABORTED' || /timeout/i.test(e.message ?? '')) {
      return {
        title: 'Request timed out',
        hint: 'The server took too long to respond. Try again, or narrow the request.',
        raw: e.message ?? fallback,
      };
    }
    if (e.code === 'ERR_NETWORK' || e.message === 'Network Error') {
      return {
        title: 'Cannot reach the server',
        hint: 'The backend may be offline or unreachable. Check that the app server is running.',
        raw: e.message ?? fallback,
      };
    }
  }
  return describeQueryError(extractApiErrorMessage(error, fallback));
}
