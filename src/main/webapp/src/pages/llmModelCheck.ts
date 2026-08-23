// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors

import type { LlmModelCheck } from '../api/types';

/**
 * Met en phrases ce que la passerelle a dit du modèle configuré.
 *
 * Le bouton Test prouvait que *quelque chose répond*. Ça vaut d'être su et ce n'est pas la question
 * qu'on se pose quand Process Mining se comporte mal, qui est de savoir si le slug saisi sait faire
 * ce travail. OpenRouter publie la réponse par modèle, donc sur ce fournisseur on peut la dire.
 *
 * Ce module ne juge rien : `schemaSupport` et `promptBudgetFits` sont calculés côté serveur, et ce
 * qui suit ne fait que les rendre. Une règle de notation écrite des deux côtés est une règle qui
 * dérive — c'est le défaut que `keyBase` a coûté au diagramme de données, corrigé en rapatriant la
 * décision côté serveur.
 *
 * Ce qui est propre à ce module, et qui ne s'automatise pas : ce qu'un fait *absent* affiche.
 * Rien. Un catalogue muet sur les modalités ne produit pas la ligne « ce modèle n'émet pas de
 * texte » — c'est un refus inventé à partir d'un champ manquant.
 */

export type ModelNoteTone = 'ok' | 'warning' | 'unknown';

export interface ModelNote {
  tone: ModelNoteTone;
  /** Le constat, en une phrase. */
  text: string;
}

/** Rend un nombre de tokens lisible : `128 000` plutôt que `128000`. */
const tokens = (value: number): string => `${value.toLocaleString()} tokens`;

/**
 * Le nom sous lequel afficher le modèle, en préférant celui du catalogue.
 *
 * OpenRouter résout les alias, donc le slug qui a répondu n'est pas forcément celui demandé — le
 * dire évite la question « pourquoi ce n'est pas ce que j'ai tapé ».
 */
export const describeModelIdentity = (check: LlmModelCheck): string | null => {
  if (check.error != null || check.id == null) return null;
  return check.name == null ? check.id : `${check.name} (${check.id})`;
};

/**
 * Les constats à afficher, dans l'ordre où ils comptent : ce qui empêche d'analyser d'abord, ce qui
 * dégrade ensuite, ce qui rassure en dernier.
 *
 * Une liste vide veut dire que le catalogue n'a rien dit d'exploitable — ce qui est un résultat, et
 * s'affiche comme tel par l'appelant, pas comme un modèle sans défaut.
 */
export const describeModelCheck = (check: LlmModelCheck | null | undefined): ModelNote[] => {
  if (!check) return [];
  if (check.error != null) {
    const notes: ModelNote[] = [{ tone: 'unknown', text: check.error }];
    /*
     * Le seul cas que la liste par clé départage, et la raison d'y avoir recours : une clé
     * d'organisation restreinte à une partie du catalogue reçoit exactement la même 404 qu'un slug
     * mal orthographié. `null` veut dire qu'on n'a pas pu trancher et ne produit donc rien — ce
     * serait affirmer une restriction qu'on n'a pas constatée.
     */
    if (check.availableToKey === false) {
      notes.push({
        tone: 'warning',
        text: 'This model is not in the list your API key can reach, so the refusal is an '
          + 'entitlement rather than a wrong name. Check your OpenRouter account’s allowed models.',
      });
    }
    return notes;
  }

  const notes: ModelNote[] = [];

  /*
   * Le seul constat bloquant : un modèle d'embeddings, de rerank ou de synthèse vocale ne répondra
   * jamais à un prompt Process Mining, quoi que dise le reste de la configuration. La passerelle
   * signale ça par la même 404 qu'un slug mal orthographié, donc sans cette ligne l'opérateur
   * vérifie un nom de modèle qui était correct.
   */
  if (check.emitsText === false) {
    notes.push({
      tone: 'warning',
      text: 'This model does not emit text, so it cannot answer a Process Mining prompt. '
        + 'Pick a chat model.',
    });
  }

  switch (check.schemaSupport) {
    case 'CONSTRAINED':
      notes.push({ tone: 'ok', text: 'Answers are constrained to the JSON schema.' });
      break;
    case 'ACCEPTED_UNCONSTRAINED':
      /*
       * Le constat que le code en fonctionnement ne peut pas voir : le champ est accepté, donc
       * aucune 4xx, donc le verrou par modèle ne se déclenche pas — et le schéma est ignoré. Une
       * garantie qui n'en est silencieusement pas une est pire qu'un refus franc.
       */
      notes.push({
        tone: 'warning',
        text: 'This model accepts the schema field but does not enforce it, so answers come back '
          + 'unconstrained and the JSON has to be recovered from prose. No error is raised, so '
          + 'nothing else would tell you.',
      });
      break;
    case 'UNSUPPORTED':
      notes.push({
        tone: 'warning',
        text: 'This model does not support schemas. The first call will be refused, retried '
          + 'without the constraint, and remembered — costing one extra request, once.',
      });
      break;
    case 'UNKNOWN':
      break;
  }

  if (check.reasoningMandatory === true) {
    notes.push({
      tone: 'warning',
      text: 'Reasoning cannot be turned off on this model, so part of every answer’s token '
        + 'budget is spent deliberating. Watch the reasoning count, and raise claude.max-tokens '
        + 'if answers arrive truncated.',
    });
  }

  const budget = describeBudget(check);
  if (budget) notes.push(budget);

  return notes;
};

/**
 * La comparaison entre le budget de prompt et la fenêtre du modèle.
 *
 * Le mot **plancher** est dans la phrase et il y est délibérément : le ratio de quatre caractères
 * par token est optimiste, donc un budget qui passe peut malgré tout ne pas tenir. Le dire est ce
 * qui distingue une vérification d'un étalonnage — et sans ce mot la phrase promettrait ce que
 * seule la tokenisation du modèle peut décider.
 */
const describeBudget = (check: LlmModelCheck): ModelNote | null => {
  if (check.promptBudgetTokens == null) return null;
  if (check.contextLength == null || check.promptBudgetFits == null) {
    return {
      tone: 'unknown',
      text: `A Process Mining prompt claims about ${tokens(check.promptBudgetTokens)} including the `
        + 'answer; this model’s context window is not published, so nothing here can say '
        + 'whether it fits.',
    };
  }
  if (check.promptBudgetFits) {
    return {
      tone: 'ok',
      text: `A Process Mining prompt claims about ${tokens(check.promptBudgetTokens)} including the `
        + `answer, within this model’s ${tokens(check.contextLength)} window — a floor, since `
        + 'the estimate is deliberately optimistic.',
    };
  }
  return {
    tone: 'warning',
    text: `A Process Mining prompt claims about ${tokens(check.promptBudgetTokens)} including the `
      + `answer, more than this model’s ${tokens(check.contextLength)} window. Lower `
      + 'process-mining.prompt-char-budget or pick a model with more room — an over-long prompt is '
      + 'usually truncated in silence, not refused.',
  };
};

/** Y a-t-il quelque chose à corriger ? Sert à choisir le ton du bloc, pas à décider à la place. */
export const hasModelWarning = (notes: readonly ModelNote[]): boolean =>
  notes.some((note) => note.tone === 'warning');
