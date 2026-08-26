// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors

/**
 * Le processus mesuré, gardé par le navigateur.
 *
 * Même chaînon que `flowChains.ts`, et pour la même raison : un `ProcessModel` n'existe nulle part
 * côté serveur — il est calculé pour une analyse, renvoyé, puis oublié. C'est pourtant l'observation
 * la plus solide que cette application produise sur un pipeline : un graphe de successions directes
 * avec des quantiles par transition, compté sur *tous* les enregistrements lus et groupé sur un id
 * de corrélation qu'un opérateur a validé à la main. La page Métriques n'avait aucun moyen de s'en
 * servir, et dérivait ses KPI de latence d'un audit qui groupe les topics par leurs noms et d'une
 * trace qui suit une clé.
 *
 * Ce qui est stocké est délibérément plus étroit que le modèle : les transitions et les reprises,
 * plus de quoi dater et situer la mesure. Les variantes, le tableau des activités et les cas
 * d'exemple sont la matière du prompt d'analyse — les garder ici, puis les renvoyer au serveur qui
 * ne les lit pas, serait du volume que personne n'utilise.
 *
 * Trois garde-fous, les mêmes qu'ailleurs : une enveloppe versionnée (une forme inconnue est
 * effacée, jamais devinée), une péremption de sept jours (une mesure d'il y a trois semaines décrit
 * un pipeline qui a pu changer), et un nombre borné d'entrées. L'écriture est au mieux : quota
 * plein ou mode privé, on renvoie la liste calculée sans lever.
 */

import type {
  MeasuredRepeat, MeasuredTransition, ProcessModel, ProcessModelEvidence,
} from '../api/types';

export const PROCESS_MODEL_KEY = 'kse:process-models';
/** Deux sélections de topics décrivent deux pipelines ; au-delà, ce sont des mesures périmées. */
export const MAX_PROCESS_MODELS = 3;
/** Même durée que les brouillons et les chaînes : sept jours. */
export const MAX_PROCESS_MODEL_AGE_MS = 7 * 24 * 60 * 60 * 1000;

const ENVELOPE_VERSION = 1;

interface ModelEnvelope {
  v: number;
  models: ProcessModelEvidence[];
}

function isTransition(value: unknown): value is MeasuredTransition {
  const t = value as MeasuredTransition;
  return !!t && typeof t === 'object' && typeof t.from === 'string' && typeof t.to === 'string';
}

function isEvidence(value: unknown): value is ProcessModelEvidence {
  const e = value as ProcessModelEvidence;
  return !!e && typeof e === 'object' && Array.isArray(e.transitions);
}

/**
 * Les mesures encore valides, plus récente en tête.
 *
 * Une enveloppe d'une autre version est effacée plutôt qu'interprétée : la relire au jugé, c'est
 * exactement ce qui produit un KPI fondé sur des données dont on ne sait plus la forme.
 */
export function readProcessModels(now: number = Date.now()): ProcessModelEvidence[] {
  let raw: string | null;
  try {
    raw = localStorage.getItem(PROCESS_MODEL_KEY);
  } catch {
    return [];
  }
  if (!raw) return [];

  let parsed: unknown;
  try {
    parsed = JSON.parse(raw);
  } catch {
    clearProcessModels();
    return [];
  }

  const envelope = parsed as ModelEnvelope;
  if (!envelope || typeof envelope !== 'object' || envelope.v !== ENVELOPE_VERSION
      || !Array.isArray(envelope.models)) {
    clearProcessModels();
    return [];
  }

  const fresh = envelope.models
    .filter(isEvidence)
    .map(model => ({ ...model, transitions: model.transitions.filter(isTransition) }))
    .filter(model => model.transitions.length > 0)
    .filter(model => model.measuredAt == null || now - model.measuredAt <= MAX_PROCESS_MODEL_AGE_MS);

  if (fresh.length !== envelope.models.length) writeProcessModels(fresh);
  return fresh;
}

export function writeProcessModels(models: ProcessModelEvidence[]): void {
  try {
    localStorage.setItem(PROCESS_MODEL_KEY,
      JSON.stringify({ v: ENVELOPE_VERSION, models } satisfies ModelEnvelope));
  } catch {
    // Le stockage est un confort : une proposition en moins, jamais une page cassée.
  }
}

export function clearProcessModels(): void {
  try {
    localStorage.removeItem(PROCESS_MODEL_KEY);
  } catch {
    /* idem */
  }
}

/**
 * Le pipeline qu'une mesure décrit, et ce sur quoi deux mesures se dédoublonnent.
 *
 * Les topics plutôt que les activités : deux analyses de la même sélection décrivent le même
 * pipeline même si les statuts observés diffèrent d'une fenêtre à l'autre, et garder les deux ne
 * donnerait pas deux propositions mais deux fois la même.
 */
export function modelRoute(model: ProcessModelEvidence): string {
  const topics = new Set<string>();
  for (const t of model.transitions) {
    topics.add(topicOfActivity(t.from));
    topics.add(topicOfActivity(t.to));
  }
  return [...topics].sort().join(',');
}

/**
 * Le topic que nomme un libellé d'activité.
 *
 * Le serveur applique la même règle (`ProcessModelBuilder.topicOf`) et c'est lui qui décide : ici
 * elle ne sert qu'à dédoublonner deux mesures, jamais à construire une requête. Un nom de topic ne
 * peut pas contenir le séparateur, donc la coupe est sans ambiguïté.
 */
export function topicOfActivity(activity: string): string {
  const at = activity.indexOf(' · ');
  return at < 0 ? activity : activity.slice(0, at);
}

/**
 * Convertit le résultat d'une analyse en observation stockable, ou `null`.
 *
 * `null` quand aucun log d'événements n'a pu être construit (sans id de corrélation il n'y a pas de
 * cas, donc pas de transition) ou quand la fenêtre n'a produit aucune succession : une activité
 * isolée n'apprend rien sur un pipeline, exactement comme une trace qui n'a touché qu'un topic.
 */
export function evidenceFromModel(
  model: ProcessModel | null | undefined,
  now: number = Date.now(),
): ProcessModelEvidence | null {
  if (!model || !model.available || model.edges.length === 0) return null;

  const transitions: MeasuredTransition[] = model.edges.map(edge => ({
    from: edge.from,
    to: edge.to,
    occurrences: edge.occurrences,
    cases: edge.cases,
    p50Ms: edge.p50Ms,
    p95Ms: edge.p95Ms,
    maxMs: edge.maxMs,
  }));
  const repeats: MeasuredRepeat[] = model.repeats.map(repeat => ({
    activity: repeat.activity,
    casesAffected: repeat.casesAffected,
    maxOccurrencesInOneCase: repeat.maxOccurrencesInOneCase,
  }));

  return {
    measuredAt: now,
    cases: model.cases,
    windowStartMs: model.windowStartMs,
    windowEndMs: model.windowEndMs,
    eventTimeSource: model.eventTimeSource,
    transitions,
    repeats,
  };
}

/**
 * Empile une mesure en tête, dédoublonnée sur la route : remesurer le même pipeline une heure plus
 * tard décrit le même pipeline. La plus récente gagne — ses quantiles sont les plus frais.
 */
export function pushProcessModel(
  model: ProcessModelEvidence,
  now: number = Date.now(),
): ProcessModelEvidence[] {
  const route = modelRoute(model);
  const next = [model, ...readProcessModels(now).filter(existing => modelRoute(existing) !== route)]
    .slice(0, MAX_PROCESS_MODELS);
  writeProcessModels(next);
  return next;
}

/**
 * Enregistre le processus qu'une analyse a mesuré, s'il décrit des transitions. Renvoie la mesure
 * retenue, ou `null` quand il n'y avait rien à garder — auquel cas rien n'est écrit.
 */
export function recordMeasuredProcess(
  model: ProcessModel | null | undefined,
  now: number = Date.now(),
): ProcessModelEvidence | null {
  const evidence = evidenceFromModel(model, now);
  if (!evidence) return null;
  pushProcessModel(evidence, now);
  return evidence;
}

/**
 * La mesure à renvoyer au serveur : la plus récente, ou `null`.
 *
 * Une seule, délibérément — le serveur dérive des KPI d'un graphe de successions, et deux graphes
 * de deux fenêtres différentes produiraient deux cartes pour le même saut, que la déduplication
 * trancherait sur l'ordre d'arrivée plutôt que sur la qualité de la mesure.
 */
export function latestProcessModel(now: number = Date.now()): ProcessModelEvidence | null {
  return readProcessModels(now)[0] ?? null;
}
