/**
 * Portée d'une requête SQL en cours d'édition : quelles tables elle cite réellement.
 *
 * L'autocomplétion proposait auparavant *toutes* les colonnes de *toutes* les tables déjà
 * chargées, à n'importe quelle position — donc surtout des colonnes appartenant à des tables
 * absentes de la requête. Restreindre aux tables du FROM / JOIN rend la liste utilisable.
 *
 * Fonctions pures → testables sans Monaco.
 */

/** `FROM`/`JOIN` suivi d'un identifiant, éventuellement entre backticks ou guillemets. */
const FROM_OR_JOIN = /\b(?:FROM|JOIN)\s+[`"]?([A-Za-z_][\w.$-]*)[`"]?/gi;

/** Mots-clés qui peuvent suivre FROM sans être une table (`FROM TABLE(TUMBLE(...))`). */
const NOT_A_TABLE = new Set(['TABLE', 'LATERAL', 'UNNEST', 'SELECT']);

/**
 * Retire commentaires et littéraux avant toute analyse : un `-- FROM orders` en commentaire,
 * ou `WHERE label = 'from customers'`, ne doit pas faire entrer une table dans la portée.
 */
export function stripSqlNoise(sql: string): string {
  return sql
    .replace(/\/\*[\s\S]*?\*\//g, ' ')
    .replace(/--[^\n]*/g, ' ')
    .replace(/'(?:[^']|'')*'/g, "''");
}

/**
 * Noms de tables cités dans les clauses FROM / JOIN, dans l'ordre d'apparition et sans doublon.
 * `FROM TABLE(TUMBLE(TABLE orders, ...))` donne bien `orders` : le mot-clé `TABLE` est ignoré,
 * et l'identifiant réel est capté par l'occurrence suivante.
 */
export function tablesInScope(sql: string): string[] {
  const cleaned = stripSqlNoise(sql ?? '');
  const found = new Set<string>();
  for (const match of cleaned.matchAll(FROM_OR_JOIN)) {
    const name = match[1];
    if (!NOT_A_TABLE.has(name.toUpperCase())) found.add(name);
  }
  // `TABLE(TUMBLE(TABLE orders, ...))` : l'identifiant suit un `TABLE` que la regex ci-dessus
  // ne couvre pas, puisqu'il n'est précédé ni de FROM ni de JOIN.
  for (const match of cleaned.matchAll(/\bTABLE\s*\(\s*(?:TUMBLE|HOP|SESSION|CUMULATE)?\s*\(?\s*TABLE\s+[`"]?([A-Za-z_][\w.$-]*)[`"]?/gi)) {
    found.add(match[1]);
  }
  return [...found];
}

/**
 * Un topic Kafka et sa table Flink ne portent pas le même nom (points et tirets deviennent des
 * underscores). Les schémas sont mémorisés sous l'un ou l'autre selon la provenance, donc on
 * compare sur la forme normalisée.
 */
export function toTableName(raw: string): string {
  return raw.replace(/[.-]/g, '_');
}

/**
 * Résout les noms cités vers les clés effectivement présentes dans le catalogue connu.
 * Un nom cité qui ne correspond à rien de connu est conservé tel quel : c'est peut-être un
 * topic dont le schéma n'a pas encore été chargé, et c'est justement ce qu'il faut aller chercher.
 */
export function resolveScope(sql: string, known: string[]): string[] {
  const index = new Map(known.map(k => [toTableName(k).toLowerCase(), k]));
  return tablesInScope(sql).map(name => index.get(toTableName(name).toLowerCase()) ?? name);
}
