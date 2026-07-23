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

/** Première ligne non vide — repli quand aucune famille ne matche. */
function firstLine(message: string): string {
  for (const line of message.split('\n')) {
    const t = line.trim();
    if (t) return t;
  }
  return message.trim();
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
    ?? /(?:Object|Table)\s+"([^"]+)"\s+not found/i.exec(unwrapped);
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
