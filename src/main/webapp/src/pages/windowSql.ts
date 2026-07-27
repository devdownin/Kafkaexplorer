/**
 * Génération du SQL de fenêtrage pour l'assistant du Query Workbench.
 *
 * L'assistant émettait un `TUMBLE` quel que soit le type choisi : « Hopping » et « Session »
 * ne changeaient qu'une ligne de commentaire. Les trois fonctions ont pourtant des signatures
 * distinctes, toutes acceptées par le planner Flink.
 *
 * Fonction pure → testable sans Monaco.
 */
export type WindowKind = 'TUMBLE' | 'HOP' | 'SESSION';
export type WindowUnit = 'SECOND' | 'MINUTE' | 'HOUR';

export interface WindowSpec {
  kind: WindowKind;
  table: string;
  timeColumn: string;
  /** Taille de la fenêtre (TUMBLE, HOP) ou durée d'inactivité (SESSION). */
  size: number;
  unit: WindowUnit;
  /** Pas d'avancement — HOP uniquement. Doit être inférieur à `size` pour un vrai recouvrement. */
  slide?: number;
  /** Clé de partitionnement — SESSION uniquement, requise par Flink. */
  partitionBy?: string;
}

const interval = (n: number, unit: WindowUnit) => `INTERVAL '${Math.max(1, Math.trunc(n))}' ${unit}`;

/** Identifiant SQL sûr tel quel, sinon protégé par des backticks (topics à points/tirets). */
function quoteIdentifier(name: string): string {
  return /^[A-Za-z_][A-Za-z0-9_]*$/.test(name) ? name : `\`${name.replace(/`/g, '``')}\``;
}

/**
 * Rend l'appel de fonction de fenêtrage seul (sans le SELECT autour).
 * - TUMBLE  : une taille
 * - HOP     : (pas, taille) — Flink attend le pas en premier
 * - SESSION : une durée d'inactivité, et un PARTITION BY exigé par Flink
 */
export function windowFunctionCall(spec: WindowSpec): string {
  const table = quoteIdentifier(spec.table);
  const time = `DESCRIPTOR(${quoteIdentifier(spec.timeColumn)})`;
  const size = interval(spec.size, spec.unit);
  switch (spec.kind) {
    case 'HOP': {
      // Un pas >= taille ne recouvre rien ; on retombe alors sur la moitié de la taille,
      // valeur qui donne le comportement attendu d'une fenêtre glissante.
      const slide = spec.slide && spec.slide < spec.size ? spec.slide : Math.max(1, Math.floor(spec.size / 2));
      return `HOP(TABLE ${table}, ${time}, ${interval(slide, spec.unit)}, ${size})`;
    }
    case 'SESSION': {
      const key = (spec.partitionBy ?? '').trim();
      const partition = key ? ` PARTITION BY ${key}` : '';
      return `SESSION(TABLE ${table}${partition}, ${time}, ${size})`;
    }
    default:
      return `TUMBLE(TABLE ${table}, ${time}, ${size})`;
  }
}

/** Requête complète prête à insérer dans l'éditeur. */
export function buildWindowSql(spec: WindowSpec): string {
  return [
    'SELECT',
    '  window_start,',
    '  window_end,',
    '  COUNT(*) AS event_count',
    `FROM TABLE(${windowFunctionCall(spec)})`,
    'GROUP BY window_start, window_end',
  ].join('\n');
}

/**
 * Ce que l'utilisateur doit savoir avant d'exécuter, ou chaîne vide.
 *
 * SESSION sans clé produit du SQL que Flink refuse : mieux vaut le dire dans l'assistant que
 * de laisser l'erreur du planner l'apprendre. HOP et SESSION, eux, ne sont qu'approximés si
 * la requête retombe sur le lecteur Kafka direct.
 */
export function windowCaveat(spec: WindowSpec): string {
  if (spec.kind === 'SESSION' && !(spec.partitionBy ?? '').trim()) {
    return 'Flink requires a PARTITION BY key for SESSION windows — fill it in before running.';
  }
  if (spec.kind !== 'TUMBLE') {
    return `${spec.kind} runs on the Flink engine. If the query falls back to the direct Kafka reader, it is approximated as a tumbling window.`;
  }
  return '';
}

/**
 * Colonne temporelle à proposer par défaut : la première colonne du schéma qui ressemble à un
 * temps. L'assistant écrivait `event_time` en dur, un nom que la plupart des topics n'ont pas.
 */
export function guessTimeColumn(schema: Record<string, string> | undefined): string | null {
  if (!schema) return null;
  const entries = Object.entries(schema);
  const byType = entries.find(([, type]) => /TIMESTAMP|TIME|DATE/i.test(type));
  if (byType) return byType[0];
  const byName = entries.find(([col]) => /(^|_)(ts|time|timestamp|date|datetime|created|updated|event_time)($|_)/i.test(col));
  return byName ? byName[0] : null;
}
