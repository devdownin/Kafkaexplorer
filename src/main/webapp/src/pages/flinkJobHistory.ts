// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors

import type { FlinkManagedJobDetails } from '../api/types';

/**
 * Ce qu'une carte de job sait dire au-delà de sa pastille de statut.
 *
 * Le magasin garde, par job, un `statusDetail`, un `errorMessage` et un historique daté des
 * transitions — c'est-à-dire la réponse à « qu'est-il arrivé à mon INSERT » — et rien ne les
 * affichait : le résumé que le tableau de bord reçoit laisse tomber les trois. La logique de
 * lecture vit ici plutôt que dans la page, sur la règle du dépôt : ce qui n'affiche rien se teste
 * sans monter React.
 */

/** Une ligne de l'historique, avec le temps passé dans l'état précédent. */
export interface JobHistoryLine {
  timestamp: number;
  status: string;
  detail: string | null;
  /**
   * Depuis l'entrée précédente. `null` sur la première : « combien de temps depuis rien » n'est
   * pas une durée, et afficher 0 laisserait croire à une transition instantanée.
   */
  sincePreviousMs: number | null;
}

/**
 * L'historique en lignes affichables, dans l'ordre où les choses se sont produites.
 *
 * Tolérant par construction : `history` est absent d'un enregistrement écrit par une version
 * antérieure, et un magasin est précisément ce qu'on relit après une montée de version. Une
 * entrée sans horodatage utilisable est écartée plutôt que rendue à l'époque Unix — 1970 est une
 * date, et une fausse date est pire qu'une ligne en moins.
 */
export function historyLines(details: FlinkManagedJobDetails | null | undefined): JobHistoryLine[] {
  const entries = details?.history ?? [];
  const usable = entries.filter(e => e && Number.isFinite(e.timestamp));
  const ordered = [...usable].sort((a, b) => a.timestamp - b.timestamp);
  return ordered.map((entry, i) => ({
    timestamp: entry.timestamp,
    status: entry.status,
    detail: entry.detail && entry.detail.trim() !== '' ? entry.detail : null,
    sincePreviousMs: i === 0 ? null : entry.timestamp - ordered[i - 1].timestamp,
  }));
}

/**
 * Combien de temps le job a duré, ou dure.
 *
 * `null` quand la question n'a pas de réponse mesurée : un `startedAt` inutilisable, ou une fin
 * antérieure au début (deux horloges, sur un enregistrement rapatrié d'un fichier). On ne rend
 * pas une durée négative en la mettant à zéro : ce serait affirmer l'instantanéité.
 */
export function jobDurationMs(
  details: FlinkManagedJobDetails | null | undefined,
  nowMs: number,
): number | null {
  if (!details || !Number.isFinite(details.startedAt)) return null;
  const end = details.endedAt ?? nowMs;
  const elapsed = end - details.startedAt;
  return elapsed >= 0 ? elapsed : null;
}

/** `2,3 s`, `4 min 10 s`, `840 ms` — la précision suit l'ordre de grandeur. */
export function formatDuration(ms: number | null): string {
  if (ms === null || !Number.isFinite(ms) || ms < 0) return '—';
  if (ms < 1000) return `${Math.round(ms)} ms`;
  if (ms < 60_000) return `${(ms / 1000).toFixed(1)} s`;
  const minutes = Math.floor(ms / 60_000);
  const seconds = Math.round((ms % 60_000) / 1000);
  if (minutes < 60) return seconds === 0 ? `${minutes} min` : `${minutes} min ${seconds} s`;
  const hours = Math.floor(minutes / 60);
  const restMinutes = minutes % 60;
  return restMinutes === 0 ? `${hours} h` : `${hours} h ${restMinutes} min`;
}

/**
 * La phrase que la carte pose au-dessus de l'historique.
 *
 * Elle dit ce que la pastille ne dit pas — depuis quand, et pendant combien de temps — et elle
 * distingue les trois états que ce sous-système confondait : un job terminé, un job encore tenu
 * par le runtime, et un job dont le statut n'a pas pu être lu (`UNAVAILABLE`, qui n'est pas un
 * état terminal : c'est l'aveu qu'on n'a pas su demander).
 */
export function describeJobOutcome(
  details: FlinkManagedJobDetails | null | undefined,
  nowMs: number,
): string {
  if (!details) return 'No detail was returned for this job.';
  const duration = formatDuration(jobDurationMs(details, nowMs));
  if (details.status.toUpperCase() === 'UNAVAILABLE') {
    return `Running for ${duration} — its status could not be read from the Flink runtime, `
      + 'which is not the same as having ended.';
  }
  if (details.endedAt === null) {
    return `Running for ${duration}.`;
  }
  return `Ended after ${duration}.`;
}

/**
 * Le nombre de lignes que le bouton d'ouverture annonce.
 *
 * Un compte plutôt qu'un simple chevron : « Historique (4) » dit qu'il y a quelque chose à lire,
 * là où un job qui n'a jamais changé d'état n'en a qu'une et ne mérite pas le détour.
 */
export function historyCount(details: FlinkManagedJobDetails | null | undefined): number {
  return historyLines(details).length;
}
