// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors

import type { LlmModelOption, LlmModelShortlist } from '../api/types';

/**
 * Met en phrases la liste restreinte des modèles.
 *
 * Choisir un modèle OpenRouter consistait à se rappeler un slug et à le taper dans un champ libre.
 * La passerelle sait filtrer et trier son propre catalogue, donc « quels modèles conviennent ici »
 * a une réponse en une requête — et chaque filtre est un fait que l'application connaît déjà d'elle
 * -même. Ce module ne décide rien : le serveur produit les lignes et leurs verdicts, on les rend.
 *
 * Une règle traverse tout le fichier : **le coût projeté n'est pas une mesure**. Partout ailleurs
 * ici, un montant affiché est un montant qu'un fournisseur a assumé ; celui-ci est un prix publié
 * multiplié par une estimation plancher, donc il peut sous-estimer. Il vaut d'être montré — sans
 * lui on choisit à l'aveugle — à condition d'être étiqueté, ce que fait `PROJECTION_NOTE`.
 */

/** L'étiquette qui accompagne obligatoirement un coût projeté. */
export const PROJECTION_NOTE =
  'Projected from published prices and the same optimistic token estimate used elsewhere — '
  + 'it can understate. What an analysis actually cost is read from the provider afterwards.';

/**
 * Un montant par fenêtre analysée. Sous le centime on descend à six décimales, sinon `$0.00`
 * s'afficherait sur chaque ligne et la colonne ne dirait plus rien — même règle que `formatCostUsd`
 * pour les coûts réels, gardée identique pour que les deux se comparent à l'œil.
 */
export const formatProjectedCost = (value: number): string =>
  value !== 0 && Math.abs(value) < 0.01 ? `$${value.toFixed(6)}` : `$${value.toFixed(2)}`;

/** Un prix au million de tokens : `$0.15/M`. `null` reste `null`, jamais « gratuit ». */
export const formatPricePerMillion = (value: number | null): string | null =>
  value == null ? null : `$${value < 1 ? value.toFixed(3) : value.toFixed(2)}/M`;

/** `128k` plutôt que `128 000` : dans une ligne dense c'est l'ordre de grandeur qui compte. */
export const formatContext = (tokens: number | null): string | null => {
  if (tokens == null) return null;
  if (tokens >= 1000) return `${Math.round(tokens / 1000)}k ctx`;
  return `${tokens} ctx`;
};

/**
 * La ligne secondaire d'une option : fenêtre, prix, coût projeté, avertissements.
 *
 * Ce qui est absent ne produit rien — un modèle sans prix publié n'affiche pas « gratuit », et un
 * modèle dont la fenêtre n'est pas publiée n'affiche pas « 0 ». C'est la même règle que le reste de
 * l'intégration : une mesure qu'on n'a pas prise ne se rend pas comme une mesure nulle.
 */
export const describeOption = (option: LlmModelOption): string[] => {
  const parts: string[] = [];
  const context = formatContext(option.contextLength);
  if (context) parts.push(context);

  const prompt = formatPricePerMillion(option.promptPriceUsdPerMillion);
  const completion = formatPricePerMillion(option.completionPriceUsdPerMillion);
  if (prompt && completion) parts.push(`${prompt} in · ${completion} out`);

  if (option.projectedCostUsd != null) {
    parts.push(`≈ ${formatProjectedCost(option.projectedCostUsd)} / window`);
  }
  /*
   * Le seul défaut qui vaut d'être crié dans une liste : un modèle qui accepte le champ de schéma
   * et l'ignore ne lèvera aucune erreur, donc rien d'autre ne le dira jamais. Un modèle qui le
   * refuse franchement se répare tout seul et n'encombre pas la ligne.
   */
  if (option.schemaSupport === 'ACCEPTED_UNCONSTRAINED') {
    parts.push('schema accepted but not enforced');
  } else if (option.schemaSupport === 'UNSUPPORTED') {
    parts.push('no schema support');
  }
  if (option.reasoningMandatory === true) parts.push('always reasons');
  return parts;
};

/** Les slugs, pour la saisie assistée du champ — la liste reste non contraignante. */
export const optionSlugs = (shortlist: LlmModelShortlist | null): string[] =>
  shortlist?.available ? shortlist.models.map(option => option.id) : [];

export type ShortlistTone = 'ready' | 'empty' | 'unavailable' | 'idle';

export interface ShortlistState {
  tone: ShortlistTone;
  text: string;
}

/**
 * Ce que la liste dit d'elle-même.
 *
 * Trois vides à ne pas confondre : rien n'a été demandé, on a demandé et le catalogue n'a pas
 * répondu, on a demandé et rien ne correspond. Seul le troisième dit quelque chose des modèles, et
 * il doit alors rappeler *ce qui a été exigé* — sans quoi « aucun modèle » se lit comme une panne
 * plutôt que comme un filtre trop serré.
 */
export const describeShortlist = (shortlist: LlmModelShortlist | null): ShortlistState => {
  if (!shortlist) {
    return { tone: 'idle', text: 'No model list requested yet.' };
  }
  if (!shortlist.available) {
    return { tone: 'unavailable', text: shortlist.error ?? 'The model list could not be read.' };
  }
  if (shortlist.models.length === 0) {
    return {
      tone: 'empty',
      text: `No model matches: ${shortlist.criteria.join(', ')}. Widen the criteria to see more.`,
    };
  }
  const count = shortlist.models.length;
  return {
    tone: 'ready',
    text: `${count} model${count === 1 ? '' : 's'} — ${shortlist.criteria.join(', ')}.`,
  };
};
