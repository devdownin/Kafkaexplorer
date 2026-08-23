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
  const cost = usage.costUsd == null ? '' : ` · ${formatCostUsd(usage.costUsd)}`;
  /*
   * Affiché seulement quand le cache a servi à quelque chose. Un « 0 cached » sur chaque fenêtre
   * serait du bruit — l'information « le cache n'a rien servi » vaut d'être cherchée dans le log,
   * pas répétée à l'écran toutes les trente secondes.
   */
  const cached = usage.cachedInputTokens != null && usage.cachedInputTokens > 0
    ? ` · ${usage.cachedInputTokens.toLocaleString()} cached`
    : '';
  /*
   * Même règle que le cache, pour une raison inverse : ici `0` est le cas courant — un modèle qui
   * ne raisonne pas — donc l'afficher partout noierait le seul cas intéressant. On ne le montre
   * que lorsqu'il y a effectivement eu délibération, c'est-à-dire quand il explique quelque chose.
   */
  const reasoning = usage.reasoningTokens != null && usage.reasoningTokens > 0
    ? ` · ${usage.reasoningTokens.toLocaleString()} reasoning`
    : '';
  return `${usage.model} · ${tokens}${reasoning}${cached}${cost} · ${describeDuration(usage.durationMs)}`;
};

/**
 * Rend un montant en dollars sans le réduire à zéro.
 *
 * Un appel coûte presque toujours une fraction de centime, donc un rendu à deux décimales
 * afficherait `$0.00` sur chaque analyse et rendrait le chiffre inutile : sous le centime on
 * descend à six décimales, au-dessus deux suffisent et se lisent mieux. `0` est une vraie mesure —
 * un modèle gratuit — et s'affiche donc `$0.00`, pas « non rapporté » : c'est l'absence de valeur,
 * traitée ailleurs, qui veut dire qu'on ne sait pas.
 */
export const formatCostUsd = (value: number): string =>
  value !== 0 && Math.abs(value) < 0.01 ? `$${value.toFixed(6)}` : `$${value.toFixed(2)}`;

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

/**
 * Somme des coûts d'une suite d'appels — la facture d'une session live.
 *
 * Même règle que `totalTokens`, et elle compte davantage ici : une session dont un appel n'a pas
 * été chiffré a une facture *supérieure* à ce qu'on sait additionner, et l'afficher quand même
 * sous-estimerait une dépense réelle avec l'aplomb d'un total exact. `null` dit « on ne peut pas
 * répondre », ce qui est la seule réponse honnête.
 */
export const totalCostUsd = (usages: readonly LlmUsage[]): number | null => {
  if (usages.length === 0) return null;
  let total = 0;
  for (const usage of usages) {
    if (usage.costUsd == null) return null;
    total += usage.costUsd;
  }
  return total;
};
