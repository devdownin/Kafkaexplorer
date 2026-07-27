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
      hint: 'Only SELECT, EXPLAIN and CREATE TABLE statements are allowed here.',
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
