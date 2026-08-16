// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors

/**
 * La logique pure du panneau « KPI proposés » de la page Métriques.
 *
 * La page ne savait rien du cluster : ses quatre exemples posaient un `COUNT(*)` sur la première
 * table trouvée, identiques sur un cluster de trois topics et sur un pipeline de commandes. Ce que
 * l'application observe pourtant — l'audit (volumes, constats, flux reconstruits) et Stream Flow
 * (le chemin réel d'une clé, saut par saut) — n'alimentait aucune proposition.
 *
 * Deux règles gouvernent ce qui suit, et ce sont celles du reste de l'écran :
 *
 *  1. **Une proposition n'apparaît qu'adossée à une observation.** Le serveur ne renvoie une carte
 *     qu'avec ses `evidence` ; le panneau les affiche telles quelles. Sans audit ni trace, il n'y
 *     a pas « aucun KPI à proposer » mais « rien n'a encore été mesuré », et ce n'est pas la même
 *     phrase — `describeEvidence` produit celle qui correspond.
 *  2. **Rien ne se crée tout seul.** Une carte ouvre l'éditeur pré-rempli ; l'enregistrement reste
 *     un geste. Le SQL repose sur une colonne de clé déduite et sur un moteur qui sait ou non
 *     exécuter l'agrégat : les deux voyagent en `caveats`, et la prévisualisation tranche.
 *
 * Les rejets sont locaux (`localStorage`) : ils disent « pas sur ce poste », pas « jamais pour
 * personne », et les identifiants de proposition sont stables d'un audit à l'autre pour que le
 * rejet tienne quand le prochain audit redérive la même carte.
 */

import type { MetricConfig, MetricSuggestion, MetricSuggestions, MetricSuggestionSource } from '../api/types';

export const DISMISSED_KEY = 'kse:metric-suggestions-dismissed';
/** Au-delà, la liste de rejets décrit des propositions que plus aucun audit ne produit. */
export const MAX_DISMISSED = 100;

// ── Rejets ───────────────────────────────────────────────────────────────────

export function readDismissed(): string[] {
  try {
    const raw = localStorage.getItem(DISMISSED_KEY);
    const parsed = raw ? JSON.parse(raw) : [];
    return Array.isArray(parsed) ? parsed.filter((id): id is string => typeof id === 'string') : [];
  } catch {
    // Quota, mode privé, JSON corrompu : au pire les propositions rejetées réapparaissent.
    return [];
  }
}

export function writeDismissed(ids: string[]): void {
  try {
    localStorage.setItem(DISMISSED_KEY, JSON.stringify(ids.slice(0, MAX_DISMISSED)));
  } catch {
    /* voir readDismissed */
  }
}

export function dismiss(id: string, current: string[] = readDismissed()): string[] {
  if (current.includes(id)) return current;
  const next = [id, ...current].slice(0, MAX_DISMISSED);
  writeDismissed(next);
  return next;
}

export function restore(id: string, current: string[] = readDismissed()): string[] {
  const next = current.filter(entry => entry !== id);
  writeDismissed(next);
  return next;
}

// ── Tri et filtrage ──────────────────────────────────────────────────────────

/**
 * Ce que le panneau affiche : d'abord ce qui n'est pas encore mesuré, ensuite ce qu'une métrique
 * existante couvre déjà — cette dernière carte reste visible parce que « c'est déjà mesuré » est
 * une réponse à la question posée, pas une raison de faire disparaître le constat qui l'a produite.
 */
export function visibleSuggestions(
  suggestions: MetricSuggestion[],
  dismissed: string[],
): MetricSuggestion[] {
  const rejected = new Set(dismissed);
  return suggestions
    .filter(suggestion => !rejected.has(suggestion.id))
    .slice()
    .sort((a, b) => Number(a.alreadyConfigured) - Number(b.alreadyConfigured));
}

export function dismissedSuggestions(
  suggestions: MetricSuggestion[],
  dismissed: string[],
): MetricSuggestion[] {
  const rejected = new Set(dismissed);
  return suggestions.filter(suggestion => rejected.has(suggestion.id));
}

export function sourceLabel(source: MetricSuggestionSource): string {
  return source === 'AUDIT' ? 'Cluster audit' : 'Stream Flow trace';
}

// ── État des observations ────────────────────────────────────────────────────

export interface EvidenceState {
  /** Une phrase disant sur quoi reposent les propositions, ou pourquoi il n'y en a pas. */
  summary: string;
  /** Ce qui manque et se lance depuis une autre page — vide quand tout a déjà tourné. */
  unlocks: Array<{ label: string; to: string }>;
  /** `true` quand rien n'a jamais été mesuré : le panneau attend, il ne conclut pas. */
  waiting: boolean;
}

/**
 * Le panneau doit distinguer trois situations qu'une liste vide confond : rien n'a été mesuré,
 * quelque chose l'a été mais n'appelle aucun KPI, et tout ce qui était proposé est déjà en place.
 */
export function describeEvidence(response: MetricSuggestions | null): EvidenceState {
  if (!response) {
    return { summary: 'Proposals have not been loaded yet.', unlocks: [], waiting: true };
  }

  const unlocks: Array<{ label: string; to: string }> = [];
  if (!response.auditAvailable) unlocks.push({ label: 'Run a cluster audit', to: '/audit' });
  if (response.flowChainsSubmitted === 0) unlocks.push({ label: 'Trace a message key', to: '/stream-flow' });

  if (!response.auditAvailable && response.flowChainsSubmitted === 0) {
    return {
      summary: 'Nothing has been measured on this cluster yet, so there is nothing to derive a KPI '
        + 'from. Run an audit, or trace a key across topics, and the proposals appear here.',
      unlocks,
      waiting: true,
    };
  }

  const parts: string[] = [];
  if (response.auditAvailable) {
    const when = response.auditTimestamp ? ` of ${formatDate(response.auditTimestamp)}` : '';
    const where = response.auditSource === 'HISTORY' ? ', read back from the history topic' : '';
    parts.push(`the audit${when}${where} (${response.auditTopics} topic${response.auditTopics === 1 ? '' : 's'})`);
  }
  if (response.flowChainsSubmitted > 0) {
    parts.push(`${response.flowChainsSubmitted} Stream Flow trace${response.flowChainsSubmitted === 1 ? '' : 's'} recorded in this browser`);
  }

  const derived = response.suggestions.length === 0
    ? 'No KPI could be derived from it'
    : `${response.suggestions.length} KPI${response.suggestions.length === 1 ? '' : 's'} derived from it`;

  return { summary: `${derived}: ${parts.join(' and ')}.`, unlocks, waiting: false };
}

/** L'âge de l'observation, quand il commence à compter — un audit d'il y a trois semaines. */
export function stalenessNote(
  response: MetricSuggestions | null,
  now: number = Date.now(),
  thresholdMs: number = 7 * 24 * 60 * 60 * 1000,
): string | null {
  if (!response?.auditTimestamp) return null;
  const age = now - response.auditTimestamp;
  if (age < thresholdMs) return null;
  const days = Math.floor(age / (24 * 60 * 60 * 1000));
  return `The audit these proposals rest on is ${days} day${days === 1 ? '' : 's'} old — topics may `
    + 'have changed since. Re-run it before trusting the thresholds it suggested.';
}

// ── Vers l'éditeur ───────────────────────────────────────────────────────────

/**
 * La configuration pré-remplie telle que l'éditeur l'attend.
 *
 * L'`id` du serveur est retiré : une proposition n'est pas une métrique existante, et la garder
 * ferait écraser la métrique portant cet identifiant au premier enregistrement. Les champs
 * d'exécution (valeur, historique, erreur) sont remis à zéro pour la même raison — ils décrivent
 * une métrique qui a tourné, ce que celle-ci n'a pas encore fait.
 */
export function suggestionToDraft(suggestion: MetricSuggestion): Partial<MetricConfig> {
  const metric = suggestion.metric;
  return {
    ...metric,
    id: undefined,
    lastValue: null,
    lastUpdateTime: null,
    errorMessage: null,
    history: [],
    lastSummary: null,
    templateParams: metric.templateParams ?? {},
    labelFields: metric.labelFields ?? [],
  };
}

/** Les topics qu'une proposition mesure, pour les puces de la carte. */
export function suggestionTopics(suggestion: MetricSuggestion): string[] {
  const params = suggestion.metric.templateParams ?? {};
  const named = ['sourceTopic', 'targetTopic', 'leftTopic', 'rightTopic']
    .map(key => params[key])
    .filter((value): value is string => typeof value === 'string' && value.length > 0);
  if (named.length > 0) return Array.from(new Set(named));
  return suggestion.metric.labelTopic ? [suggestion.metric.labelTopic] : [];
}

function formatDate(epochMillis: number): string {
  const date = new Date(epochMillis);
  return Number.isNaN(date.getTime()) ? 'an unknown date' : date.toLocaleString();
}
