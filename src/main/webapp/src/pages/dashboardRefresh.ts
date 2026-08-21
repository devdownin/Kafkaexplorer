// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors

/**
 * La cadence de rafraîchissement du tableau de bord, et ce que la page en dit.
 *
 * Le sondage existait déjà — toutes les 5 s, et seulement quand l'onglet est visible — mais la
 * valeur était une constante du module : ni réglable, ni extinguible, ni même énoncée. Or elle
 * n'est pas neutre. Chaque tour appelle `/api/dashboard`, qui liste les topics, leurs tailles et
 * l'horodatage de leur dernier message ; les lectures sont cachées 30 s côté serveur
 * (`explorer.cache-expire-seconds`), donc six tours sur sept relisent la même entrée, et le
 * septième paie les allers-retours au broker. Sur un poste laissé ouvert sur cette page toute la
 * journée, personne ne pouvait ralentir ça, et sur un cluster que l'on regarde travailler,
 * personne ne pouvait l'accélérer.
 *
 * Trois règles tiennent le réglage.
 *
 * **Le défaut est le comportement d'aujourd'hui.** Rendre une cadence réglable ne doit pas
 * changer ce que fait une installation existante : `DEFAULT_REFRESH_CHOICE` vaut exactement les
 * 5 s qui étaient codées en dur.
 *
 * **« Off » est une valeur du même sélecteur**, comme pour la colonne d'activité et pour la même
 * raison : la mesure coûte des allers-retours au broker, donc elle doit pouvoir s'éteindre depuis
 * l'écran et pas seulement depuis un fichier de configuration.
 *
 * **Et une valeur inconnue retombe sur le défaut, jamais sur « off »** : un identifiant retiré
 * d'une version à l'autre ne doit pas se traduire par un tableau de bord qui a cessé de se mettre
 * à jour sans un mot.
 */

export interface RefreshOption {
  id: string;
  label: string;
  ms: number;
}

/** « Off » est une valeur du sélecteur, pas une case à part. */
export const REFRESH_OFF = 'off';
export type RefreshChoice = typeof REFRESH_OFF | string;

const SECOND = 1000;

/**
 * Les cadences offertes. `2s` existe parce que regarder un pipeline démarrer est un usage réel de
 * cette page ; `5m` parce que la laisser ouverte sur un second écran en est un autre, et que les
 * deux ne demandent pas la même chose au broker.
 */
export const REFRESH_OPTIONS: RefreshOption[] = [
  { id: '2s', label: 'Every 2 s', ms: 2 * SECOND },
  { id: '5s', label: 'Every 5 s', ms: 5 * SECOND },
  { id: '15s', label: 'Every 15 s', ms: 15 * SECOND },
  { id: '30s', label: 'Every 30 s', ms: 30 * SECOND },
  { id: '1m', label: 'Every minute', ms: 60 * SECOND },
  { id: '5m', label: 'Every 5 min', ms: 300 * SECOND },
];

/** Exactement la constante qui était codée en dur : le réglage ne change rien par défaut. */
export const DEFAULT_REFRESH_CHOICE = '5s';

const STORAGE_KEY = 'kse:dashboard-refresh';

export function refreshById(id: RefreshChoice): RefreshOption | null {
  return REFRESH_OPTIONS.find(o => o.id === id) ?? null;
}

export function readRefreshChoice(): RefreshChoice {
  try {
    const stored = localStorage.getItem(STORAGE_KEY);
    if (stored === REFRESH_OFF) return REFRESH_OFF;
    if (stored && refreshById(stored)) return stored;
  } catch {
    /* mode privé, quota, stockage désactivé : le défaut fait l'affaire */
  }
  return DEFAULT_REFRESH_CHOICE;
}

export function writeRefreshChoice(choice: RefreshChoice): void {
  try {
    localStorage.setItem(STORAGE_KEY, choice);
  } catch {
    /* l'écriture est un confort, jamais une condition */
  }
}

/** La période du sondage, ou `null` quand il est éteint — ce que l'effet lit pour ne rien armer. */
export function refreshIntervalMs(choice: RefreshChoice): number | null {
  return refreshById(choice)?.ms ?? null;
}

/**
 * Le plancher de la colonne d'activité : la cadence de son cache serveur. La redemander plus
 * souvent ne ferait que relire six fois la même entrée, puis payer six fois les allers-retours au
 * broker une fois celle-ci expirée — c'est l'arbitrage que `ACTIVITY_REFRESH_MS` portait déjà, et
 * il ne devient pas faux parce que le reste de la page est devenu réglable.
 */
export const ACTIVITY_FLOOR_MS = 30 * SECOND;

/**
 * La période de la colonne d'activité sous un choix donné. Le réglage la **ralentit** mais ne
 * l'accélère jamais : un tableau de bord à 2 s ne doit pas marteler un cache de 30 s, alors qu'un
 * tableau de bord à 5 min ne doit pas continuer à mesurer l'activité dix fois plus souvent que
 * tout le reste de l'écran. Et « off » éteint les deux : un seul interrupteur, sinon la page
 * continuerait de sonder après qu'on lui a dit d'arrêter.
 */
export function activityIntervalMs(choice: RefreshChoice): number | null {
  const chosen = refreshIntervalMs(choice);
  return chosen === null ? null : Math.max(chosen, ACTIVITY_FLOOR_MS);
}

/**
 * L'âge de ce qui est à l'écran, en clair.
 *
 * C'est la moitié du réglage qui ne se voit pas : `fetchedAt` existait et ne servait qu'à dater
 * les « dernier message » relatifs, donc rien ne disait quand les chiffres avaient été lus. Tant
 * que la cadence était de 5 s pour tout le monde, la question ne se posait pas ; dès qu'on peut
 * la porter à cinq minutes ou l'éteindre, un nombre que personne ne peut dater est un nombre sur
 * lequel personne ne devrait agir — la règle que ce dépôt applique déjà à ses jauges Prometheus.
 */
export function describeAge(fetchedAt: number, now: number): string {
  const seconds = Math.max(0, Math.round((now - fetchedAt) / SECOND));
  if (seconds < 5) return 'just now';
  if (seconds < 60) return `${seconds} s ago`;
  const minutes = Math.round(seconds / 60);
  if (minutes < 60) return `${minutes} min ago`;
  const hours = Math.round(minutes / 60);
  return `${hours} h ago`;
}

/**
 * Ce que la ligne sous l'en-tête annonce : quand les chiffres ont été lus, et ce que la page fera
 * ensuite. Les deux ensemble, parce que l'un sans l'autre ne répond pas — « il y a 4 min » sans la
 * cadence laisse croire à une panne, et « toutes les 5 min » sans l'âge ne dit pas où l'on en est
 * dans l'intervalle.
 */
export function describeRefreshStatus(
  choice: RefreshChoice,
  fetchedAt: number,
  now: number,
): string {
  const age = describeAge(fetchedAt, now);
  const option = refreshById(choice);
  return option
    ? `Updated ${age} · refreshing ${option.label.replace(/^Every /, 'every ')}`
    : `Updated ${age} · auto-refresh off`;
}
