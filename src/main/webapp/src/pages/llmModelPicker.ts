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

/**
 * Ce qui cloche dans un slug, avant même de l'enregistrer — `null` quand rien ne cloche.
 *
 * Le bouton Test nomme déjà un slug sans `/`, et le chemin du catalogue refuse un slug qui
 * traverserait. Enregistrer, lui, acceptait n'importe quoi : la faute n'était découverte qu'à la
 * première fenêtre analysée, sous la forme d'une 404 que l'opérateur lit comme un refus de routage.
 *
 * Volontairement **syntaxique et seulement pour OpenRouter**. On ne vérifie pas qu'un modèle
 * existe — ça, c'est le rôle du bouton Test, qui interroge le catalogue — mais qu'il a la forme
 * d'une adresse que la passerelle peut résoudre. Ailleurs, un nom de modèle est ce que le point
 * d'accès veut bien accepter, et cette application n'a pas d'avis à donner dessus.
 */
export const validateModelSlug = (
  provider: string,
  model: string | undefined,
): string | null => {
  if (provider !== 'OPENROUTER') return null;
  const slug = (model ?? '').trim();
  if (!slug) return null; // « un modèle est requis » est déjà dit ailleurs ; ne pas le dire deux fois.
  if (!slug.includes('/')) {
    return `OpenRouter names models vendor/model — "${slug}" has no vendor. `
      + 'For example openai/gpt-4o-mini.';
  }
  /*
   * Même règle que le serveur (`OpenRouterModelCatalog.SLUG`) : chaque segment commence par une
   * lettre ou un chiffre, ce qui écarte un segment `.` ou `..` d'un chemin d'URL. Écrite ici aussi
   * parce qu'un formulaire qui laisse passer ce que le serveur refusera n'aide personne — mais
   * c'est le serveur qui décide, celle-ci ne fait qu'avancer le moment où on l'apprend.
   */
  if (!/^[A-Za-z0-9][\w.:-]*(\/[A-Za-z0-9][\w.:-]*)+$/.test(slug)) {
    return `"${slug}" is not a usable OpenRouter slug — each part has to start with a letter or `
      + 'a digit, as in meta-llama/llama-3.1-8b-instruct:free.';
  }
  return null;
};

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
