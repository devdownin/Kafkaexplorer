// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors

/**
 * Logique pure du panneau de validation du mapping — testée à part, comme le reste des écrans
 * d'investigation de cette application.
 */

/**
 * Reproduit `PayloadDigestService.normalizePath` : `$.order['items'][0].sku` → `order.items[].sku`.
 *
 * Le back est délibérément permissif (il retire le `$`, convertit les crochets, mange les points de
 * tête), donc la validation côté navigateur ne peut pas être plus stricte que lui sans refuser des
 * chemins qui marchent. Ce qu'elle doit attraper, c'est le chemin qui ne peut désigner *aucune*
 * feuille — la faute de frappe dont le seul symptôme, sinon, est un mapping vide en fin de pipeline.
 */
export const normalizePath = (rawPath: string): string => {
  let path = rawPath.trim();
  if (path.startsWith('$')) path = path.slice(1);
  path = path
    .replace(/\['([^']*)']/g, '.$1')
    .replace(/\["([^"]*)"]/g, '.$1')
    .replace(/\[\s*(\d+|\*)\s*]/g, '[]');
  while (path.startsWith('.')) path = path.slice(1);
  return path;
};

/**
 * Ce qui empêche un chemin de désigner quoi que ce soit, ou `null` s'il est utilisable.
 *
 * Volontairement peu de règles : trois cas seulement, tous des chemins qui ne peuvent rien matcher.
 * Refuser au-delà reviendrait à imposer un dialecte que le serveur, lui, accepte — et une validation
 * qui refuse du valide est pire que pas de validation, parce qu'on apprend à la contourner.
 */
export const pathProblem = (rawPath: string): string | null => {
  const raw = rawPath.trim();
  if (!raw) return null; // Vide = « pas de mapping pour ce topic », ce qui est un choix légitime.

  const opens = (raw.match(/\[/g) ?? []).length;
  const closes = (raw.match(/]/g) ?? []).length;
  if (opens !== closes) {
    return 'Unbalanced brackets.';
  }

  const normalized = normalizePath(raw);
  if (!normalized) {
    return 'This points at the document root, not at a field.';
  }
  // L'espace se juge sur le chemin *hors segments entre crochets quotés* : `$['a b'].c` est du
  // JSONPath valide que le back convertit en `a b.c`, et le refuser serait précisément la
  // validation plus stricte que le serveur que ce module s'interdit.
  const withoutQuotedKeys = raw.replace(/\['[^']*']/g, '').replace(/\["[^"]*"]/g, '');
  if (/\s/.test(withoutQuotedKeys)) {
    return 'A field path cannot contain a space.';
  }
  return null;
};

/** Tous les chemins fautifs d'un jeu de corrections, sous la forme `role → topic → message`. */
export const mappingProblems = (
  corrections: Record<string, Record<string, string>>,
): Record<string, Record<string, string>> => {
  const problems: Record<string, Record<string, string>> = {};
  for (const [role, byTopic] of Object.entries(corrections)) {
    for (const [topic, path] of Object.entries(byTopic)) {
      const problem = pathProblem(path);
      if (problem) {
        problems[role] = { ...(problems[role] ?? {}), [topic]: problem };
      }
    }
  }
  return problems;
};

/**
 * Ne garde que les chemins non vides, par rôle — la forme que le serveur attend.
 *
 * Un rôle dont plus aucun topic n'est renseigné disparaît, plutôt que d'être envoyé comme un objet
 * vide : côté serveur, `applyCorrections` ne fait qu'écraser des entrées, donc un rôle vide et un
 * rôle absent veulent dire la même chose et le second se lit mieux dans les logs.
 */
export const cleanCorrections = (
  corrections: Record<string, Record<string, string>>,
): Record<string, Record<string, string>> => {
  const cleaned: Record<string, Record<string, string>> = {};
  for (const [role, byTopic] of Object.entries(corrections)) {
    const nonEmpty = Object.fromEntries(
      Object.entries(byTopic).filter(([, path]) => path.trim().length > 0));
    if (Object.keys(nonEmpty).length > 0) {
      cleaned[role] = nonEmpty;
    }
  }
  return cleaned;
};
