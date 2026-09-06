// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors

/**
 * L'état de l'écran des files d'échec, tel qu'une URL le porte.
 *
 * Toutes les pages de cette application font circuler leur état par la query string — une
 * recherche de topic, une trace, un onglet SQL sont des liens partageables qui se rejouent à
 * l'ouverture, et `docs/screenshots/capture.mjs` s'appuie explicitement là-dessus pour atteindre
 * chaque écran sans cliquer. Celui-ci était la seule exception : fenêtre, filtre, tri et ligne
 * dépliée vivaient en état local.
 *
 * Le coût tombait au pire moment. Pendant un incident, « regarde `orders.DLQ` sur sept jours » ne
 * pouvait pas s'envoyer : il fallait décrire le chemin et espérer que l'autre reproduise le même
 * écran — sur une page dont le tri par défaut dépend de mesures qui changent d'une minute à
 * l'autre, donc où « la troisième ligne » ne désigne rien de stable.
 *
 * **Ce qui ne passe pas par l'URL** : l'échelle de la courbe et la cadence de rafraîchissement.
 * Ce sont des préférences de lecture propres à la personne, pas des propriétés de la situation
 * qu'on partage — les imposer à qui reçoit le lien changerait ses réglages sans le lui demander.
 * Elles restent dans le stockage local, où le reste de l'application les garde déjà.
 */

import { ACTIVITY_WINDOWS, type ActivityWindow } from './topicActivity';

export type SortKey = 'name' | 'volume' | 'share' | 'size';
export type SortDir = 'asc' | 'desc';

const SORT_KEYS: SortKey[] = ['name', 'volume', 'share', 'size'];

/** Les noms des paramètres, écrits une fois pour la lecture et pour l'écriture. */
export const PARAMS = {
  window: 'window',
  filter: 'q',
  sort: 'sort',
  dir: 'dir',
  opened: 'open',
} as const;

/** Ce qu'une URL dit de l'écran. Chaque champ absent veut dire « laisse le défaut ». */
export interface ScreenState {
  window?: ActivityWindow;
  filter?: string;
  sortKey?: SortKey;
  sortDir?: SortDir;
  opened?: string;
}

/**
 * Lit l'état porté par l'URL.
 *
 * **Une valeur inconnue est ignorée, jamais appliquée ni signalée.** Une URL est du texte qu'un
 * tiers a pu écrire ou tronquer, et un identifiant de fenêtre retiré d'une version à l'autre ne
 * doit pas se traduire par un écran vide ou par une erreur : le défaut fait l'affaire, c'est la
 * même règle que `readActivityChoice` applique au stockage local.
 */
export function readScreenState(params: URLSearchParams): ScreenState {
  const state: ScreenState = {};

  const windowId = params.get(PARAMS.window);
  const found = windowId ? ACTIVITY_WINDOWS.find(w => w.id === windowId) : null;
  if (found) state.window = found;

  const filter = params.get(PARAMS.filter);
  if (filter) state.filter = filter;

  const sort = params.get(PARAMS.sort);
  if (sort && (SORT_KEYS as string[]).includes(sort)) state.sortKey = sort as SortKey;

  const dir = params.get(PARAMS.dir);
  if (dir === 'asc' || dir === 'desc') state.sortDir = dir;

  const opened = params.get(PARAMS.opened);
  if (opened) state.opened = opened;

  return state;
}

/**
 * Réécrit les paramètres de cet écran dans une URL, en laissant intacts ceux qui ne sont pas à lui.
 *
 * Un champ à sa valeur par défaut est **retiré** plutôt qu'écrit : sans ça, la barre d'adresse se
 * remplit de `?sort=volume&dir=desc&q=` dès l'ouverture, ce qui donne un lien plus long à lire et
 * plus difficile à comparer à l'œil pour une information nulle. Ce qui est dans l'URL est donc
 * exactement ce qui s'écarte du défaut.
 */
export function writeScreenState(
  current: URLSearchParams, state: ScreenState, defaults: { window: string; sortKey: SortKey; sortDir: SortDir },
): URLSearchParams {
  const next = new URLSearchParams(current);
  const set = (name: string, value: string | undefined | null, fallback?: string) => {
    if (!value || value === fallback) next.delete(name);
    else next.set(name, value);
  };
  set(PARAMS.window, state.window?.id, defaults.window);
  set(PARAMS.filter, state.filter?.trim() ? state.filter : undefined);
  set(PARAMS.sort, state.sortKey, defaults.sortKey);
  set(PARAMS.dir, state.sortDir, defaults.sortDir);
  set(PARAMS.opened, state.opened ?? undefined);
  return next;
}
