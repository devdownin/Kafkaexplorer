// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors

/**
 * Le processus mesuré, mis en phrases et en pourcentages.
 *
 * Il était calculé et seul le modèle le voyait : l'opérateur lisait un *récit* à propos d'un graphe
 * de successions directes qu'il ne pouvait pas consulter. C'est l'inverse de la règle que cette
 * application suit partout ailleurs — le tableau de preuves de Stream Flow, la note de couverture,
 * l'évidence des KPI proposés : ce qui est mesuré s'affiche, pour qu'un récit puisse être vérifié
 * plutôt que cru.
 *
 * Les verdicts viennent du serveur (`ProcessModel`) ; ici on ne fait que les tourner en phrases.
 * Même règle qu'ailleurs : une règle d'appréciation écrite des deux côtés est une règle qui dérive.
 */

import type { ProcessEdge, ProcessModel, ProcessModelTimeSource } from '../api/types';

/** Combien d'arêtes il faut avant que « la plus lente » veuille dire quelque chose. */
export const SLOWEST_EDGE_MIN = 2;

/**
 * Une durée qu'on peut soupeser : « 812 ms », « 3,2 s », « 4,1 min ».
 *
 * Jamais un décompte de millisecondes brut — `247 000 ms` est un nombre que personne ne lit.
 */
export function formatDuration(millis: number): string {
  if (!Number.isFinite(millis)) return '—';
  const magnitude = Math.abs(millis);
  const rendered =
    magnitude < 1_000 ? `${Math.round(magnitude)} ms`
      : magnitude < 60_000 ? `${(magnitude / 1_000).toFixed(1)} s`
        : magnitude < 3_600_000 ? `${(magnitude / 60_000).toFixed(1)} min`
          : `${(magnitude / 3_600_000).toFixed(1)} h`;
  return millis < 0 ? `-${rendered}` : rendered;
}

/** Un pourcentage de cas, ou `—` quand il n'y a pas de dénominateur à diviser. */
export function share(part: number, total: number): string {
  return total > 0 ? `${((100 * part) / total).toFixed(1)}%` : '—';
}

/**
 * Quelle horloge a ordonné le log — à énoncer, jamais à supposer.
 *
 * Un horodatage métier dit quand le processus s'est passé ; l'horodatage Kafka dit quand le message
 * a été produit, et les deux diffèrent exactement de ce qui rend un constat de latence intéressant
 * ou vide de sens.
 */
export function describeTimeSource(source: ProcessModelTimeSource): string {
  switch (source) {
    case 'MAPPED_FIELD':
      return 'business timestamps from the field mapping';
    case 'MIXED':
      return 'business timestamps, with a partial fallback to the Kafka record timestamp';
    case 'RECORD_TIMESTAMP':
      return 'Kafka record timestamps — produce time, not event time';
  }
}

/** La durée couverte par la fenêtre mesurée. */
export function windowSpan(model: ProcessModel): string {
  return formatDuration(Math.max(0, model.windowEndMs - model.windowStartMs));
}

/** Une ligne d'en-tête : ce qui a été mesuré, et sur quelle horloge. */
export function describeScope(model: ProcessModel): string {
  if (!model.available) return 'No event log could be built';
  const cases = `${model.cases.toLocaleString()} ${model.cases === 1 ? 'case' : 'cases'}`;
  const events = `${model.events.toLocaleString()} ${model.events === 1 ? 'event' : 'events'}`;
  return `${cases} · ${events} over ${windowSpan(model)} · ${describeTimeSource(model.eventTimeSource)}`;
}

/**
 * L'arête la plus lente au p95, ou `null`.
 *
 * `null` sous deux arêtes, comme le fait déjà la chaîne de Stream Flow : « la plus lente » ne dit
 * rien quand il n'y en a qu'une, et l'annoncer donnerait à une transition unique l'air d'un
 * constat.
 */
export function slowestEdge(model: ProcessModel): ProcessEdge | null {
  if (!model.available || model.edges.length < SLOWEST_EDGE_MIN) return null;
  return model.edges.reduce((worst, edge) => (edge.p95Ms > worst.p95Ms ? edge : worst));
}

/** Les arêtes dont les deux horloges se contredisent — producteur désynchronisé, ou antidatage. */
export function skewedEdges(model: ProcessModel): ProcessEdge[] {
  return model.available ? model.edges.filter(e => e.outOfOrderCount > 0) : [];
}

/**
 * Les fins de cas minoritaires : celles qui ne sont pas la plus fréquente.
 *
 * Rien n'est appelé orphelin ici — quelle activité *doit* terminer un processus est un fait métier
 * que cette application n'a pas, et le déduire des données est circulaire. Ce qui est rendu est la
 * distribution ; la lecture reste à l'opérateur.
 */
export function minorityEnds(model: ProcessModel): { activity: string; cases: number }[] {
  if (!model.available || model.ends.length < 2) return [];
  return model.ends.slice(1);
}

/** Ce qu'il faut afficher quand il n'y a pas de log d'événements : la raison, telle quelle. */
export function describeUnavailable(model: ProcessModel): string {
  return model.unavailableReason
    ?? 'No event log could be built, and no reason was given.';
}
