// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors

import type { LlmUsage } from '../api/types';

/**
 * Rend ce qu'un appel au modèle a coûté.
 *
 * Un compte de tokens à `null` veut dire « le fournisseur n'a rien rapporté » — c'est le cas de
 * SpectraLLM, dont l'API de requête n'expose aucune comptabilité — et se lit donc comme tel plutôt
 * que comme un zéro, qui affirmerait que l'appel était gratuit. La durée, elle, est mesurée côté
 * serveur : elle est toujours réelle, même quand les tokens sont inconnus.
 */
export const describeUsage = (usage: LlmUsage): string => {
  const tokens = usage.inputTokens != null && usage.outputTokens != null
    ? `${usage.inputTokens.toLocaleString()} in / ${usage.outputTokens.toLocaleString()} out`
    : 'tokens not reported';
  return `${usage.model} · ${tokens} · ${describeDuration(usage.durationMs)}`;
};

/** Sous la seconde on lit mieux des millisecondes ; au-delà, une seconde à une décimale. */
export const describeDuration = (durationMs: number): string =>
  durationMs < 1000 ? `${durationMs} ms` : `${(durationMs / 1000).toFixed(1)} s`;

/**
 * Somme des tokens d'une suite d'appels — le total d'une session live, par exemple.
 *
 * Retourne `null` dès qu'un appel n'a pas rapporté ses comptes : additionner ce qui est connu en
 * ignorant le reste produirait un total plus bas que la réalité, présenté avec la même assurance
 * qu'un total exact. C'est la règle appliquée partout ailleurs ici — une mesure absente n'est pas
 * une mesure nulle.
 */
export const totalTokens = (usages: readonly LlmUsage[]): number | null => {
  if (usages.length === 0) return null;
  let total = 0;
  for (const usage of usages) {
    if (usage.inputTokens == null || usage.outputTokens == null) return null;
    total += usage.inputTokens + usage.outputTokens;
  }
  return total;
};
