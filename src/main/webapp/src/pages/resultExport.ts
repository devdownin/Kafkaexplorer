/**
 * Sérialisation des résultats de requête pour l'export.
 *
 * L'export CSV faisait `JSON.stringify(value)` sur chaque cellule : correct par accident pour
 * une chaîne (`"a,b"` ressort entre guillemets), cassé pour tout objet à plusieurs clés
 * (`{"a":1,"b":2}` part tel quel, sa virgule interne décale les colonnes) et pour toute valeur
 * contenant un guillemet (jamais doublé). Les en-têtes n'étaient pas échappés du tout.
 *
 * Fonctions pures → testables.
 */

/** Échappe une cellule selon la RFC 4180 : guillemets doublés, encadrement si nécessaire. */
export function csvCell(value: unknown): string {
  const text = value === null || value === undefined
    ? ''
    : typeof value === 'object'
      ? JSON.stringify(value)
      : String(value);
  return /[",\r\n]/.test(text) ? `"${text.replace(/"/g, '""')}"` : text;
}

/**
 * Document CSV complet. Les lignes sont séparées par CRLF, comme le veut la RFC — les tableurs
 * comme les parseurs en ligne de commande l'acceptent.
 */
export function toCsv(columns: string[], rows: Record<string, unknown>[]): string {
  const header = columns.map(csvCell).join(',');
  const body = rows.map(row => columns.map(col => csvCell(row[col])).join(','));
  return [header, ...body].join('\r\n');
}

export function toJson(rows: Record<string, unknown>[]): string {
  return JSON.stringify(rows, null, 2);
}
