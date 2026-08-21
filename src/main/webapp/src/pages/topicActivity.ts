// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors

/**
 * La courbe d'activité d'un topic : le choix de fenêtre, la géométrie de la sparkline, et ce
 * qu'elle dit d'elle-même.
 *
 * Tout ce qui décide vit ici plutôt que dans le composant, pour la raison habituelle : une courbe
 * est une affirmation sur un cluster, et une affirmation se teste. Deux d'entre elles comptent
 * plus que le dessin — une série que la rétention a amputée ne doit pas se lire comme une nuit
 * calme, et une mesure impossible ne doit pas se lire comme un zéro.
 */

import type { TopicActivity } from '../api/types';

/** Une fenêtre proposée dans le sélecteur, avec le découpage qui la rend lisible. */
export interface ActivityWindow {
  id: string;
  /** Libellé du sélecteur. */
  label: string;
  windowMs: number;
  /** Points de la série. Le serveur les borne à [4, 60] ; ces valeurs sont dedans. */
  buckets: number;
  /** Ce que couvre un point, pour l'infobulle — dérivé, jamais saisi deux fois. */
  bucketMs: number;
}

const MINUTE = 60_000;
const HOUR = 60 * MINUTE;

function windowOf(id: string, label: string, windowMs: number, buckets: number): ActivityWindow {
  return { id, label, windowMs, buckets, bucketMs: Math.round(windowMs / buckets) };
}

/**
 * Les fenêtres offertes. Trois, pas dix : le sélecteur est dans l'en-tête d'une colonne, et
 * chacune répond à une question différente — « est-ce que ça produit là maintenant », « à quoi
 * ressemble une journée », « est-ce que la semaine a une forme ».
 */
export const ACTIVITY_WINDOWS: ActivityWindow[] = [
  windowOf('1h', 'Last hour', HOUR, 12),
  windowOf('24h', 'Last 24 h', 24 * HOUR, 24),
  windowOf('7d', 'Last 7 days', 7 * 24 * HOUR, 28),
];

/** « Off » est une valeur du même sélecteur : la colonne coûte des allers-retours au broker. */
export const ACTIVITY_OFF = 'off';
export type ActivityChoice = typeof ACTIVITY_OFF | string;

export const DEFAULT_ACTIVITY_CHOICE = '24h';
const STORAGE_KEY = 'kse:dashboard-activity';

export function windowById(id: ActivityChoice): ActivityWindow | null {
  return ACTIVITY_WINDOWS.find(w => w.id === id) ?? null;
}

/**
 * Le choix persiste, et une valeur inconnue retombe sur le défaut plutôt que d'éteindre la
 * colonne : un identifiant retiré d'une version à l'autre ne doit pas se traduire par une
 * fonctionnalité disparue sans un mot.
 */
export function readActivityChoice(): ActivityChoice {
  try {
    const stored = localStorage.getItem(STORAGE_KEY);
    if (stored === ACTIVITY_OFF) return ACTIVITY_OFF;
    if (stored && windowById(stored)) return stored;
  } catch {
    /* private mode, quota, storage désactivé : le défaut fait l'affaire */
  }
  return DEFAULT_ACTIVITY_CHOICE;
}

export function writeActivityChoice(choice: ActivityChoice): void {
  try {
    localStorage.setItem(STORAGE_KEY, choice);
  } catch {
    /* l'écriture est un confort, jamais une condition */
  }
}

/** Géométrie d'une sparkline, en coordonnées du `viewBox`. */
export interface SparklineShape {
  /** `d` de la courbe. */
  line: string;
  /** `d` de l'aire sous la courbe, refermée sur la ligne de base. */
  area: string;
  points: Array<{ x: number; y: number }>;
  /** Plus haute valeur de la série — c'est l'échelle, et elle est propre à chaque ligne. */
  peak: number;
  peakIndex: number;
  /** Vrai quand aucun bucket n'a rien vu passer : une ligne plate, pas une absence de mesure. */
  flat: boolean;
}

const round = (n: number) => Math.round(n * 100) / 100;

/**
 * La série, mise à l'échelle **de sa propre pointe**.
 *
 * Une échelle commune à toutes les lignes du tableau écraserait tout topic qui n'est pas le plus
 * bavard du cluster, et la colonne ne dirait plus rien de quatre-vingt-dix-neuf lignes sur cent.
 * Le prix, c'est que deux courbes ne se comparent pas en hauteur — d'où la pointe dans l'infobulle
 * et dans le nom accessible, où elle est chiffrée.
 */
export function sparkline(counts: number[], width: number, height: number, padding = 1): SparklineShape {
  const usableW = Math.max(1, width - padding * 2);
  const usableH = Math.max(1, height - padding * 2);
  const values = counts.length > 0 ? counts : [0];
  let peak = 0;
  let peakIndex = 0;
  values.forEach((value, i) => {
    if (value > peak) {
      peak = value;
      peakIndex = i;
    }
  });

  const step = values.length > 1 ? usableW / (values.length - 1) : 0;
  const baseline = padding + usableH;
  const points = values.map((value, i) => ({
    x: round(padding + i * step),
    // Une série entièrement nulle se dessine sur la ligne de base : plate, et pas au milieu de la
    // boîte, où elle se lirait comme une valeur moyenne.
    y: round(peak === 0 ? baseline : baseline - (value / peak) * usableH),
  }));

  const line = points.map((p, i) => `${i === 0 ? 'M' : 'L'}${p.x} ${p.y}`).join(' ');
  const last = points[points.length - 1];
  const first = points[0];
  const area = `${line} L${last.x} ${round(baseline)} L${first.x} ${round(baseline)} Z`;

  return { line, area, points, peak, peakIndex, flat: peak === 0 };
}

/**
 * Combien de buckets de tête la rétention a vidés.
 *
 * `coveredFromMs` est l'instant à partir duquel la série est complète ; ce qui précède a été
 * supprimé du log, et compté comme zéro se lirait « rien n'est passé ». Le bucket qui contient
 * cet instant est compté comme non mesuré lui aussi : il est partiel, donc c'est un plancher.
 */
export function unmeasuredLeadingBuckets(activity: TopicActivity): number {
  if (!activity.available || activity.coveredFromMs === null) return 0;
  if (activity.bucketMs <= 0 || activity.counts.length === 0) return 0;
  const offset = activity.coveredFromMs - activity.windowStartMs;
  if (offset <= 0) return 0;
  return Math.min(activity.counts.length, Math.ceil(offset / activity.bucketMs));
}

/** Vrai quand la série est un plancher plutôt qu'une mesure — pour l'infobulle et le style. */
export function isFloor(activity: TopicActivity): boolean {
  return activity.available
    && (activity.coveredFromMs !== null || activity.partitionsMeasured < activity.partitionsTotal);
}

function formatWindow(ms: number): string {
  if (ms >= 24 * HOUR) {
    const days = Math.round(ms / (24 * HOUR));
    return days === 1 ? '24 h' : `${days} days`;
  }
  if (ms >= HOUR) {
    const hours = Math.round(ms / HOUR);
    return `${hours} h`;
  }
  return `${Math.max(1, Math.round(ms / MINUTE))} min`;
}

/** L'intervalle qu'un point couvre, tel qu'on l'écrit dans une infobulle. */
export function bucketLabel(activity: TopicActivity, index: number): string {
  const start = new Date(activity.windowStartMs + index * activity.bucketMs);
  const end = new Date(activity.windowStartMs + (index + 1) * activity.bucketMs);
  const time = (d: Date) => d.toLocaleTimeString(undefined, { hour: '2-digit', minute: '2-digit' });
  const sameDay = start.toDateString() === end.toDateString();
  const day = activity.windowEndMs - activity.windowStartMs > 24 * HOUR
    ? `${start.toLocaleDateString(undefined, { day: '2-digit', month: 'short' })} `
    : '';
  return sameDay ? `${day}${time(start)}–${time(end)}` : `${day}${time(start)} – ${end.toLocaleDateString(undefined, { day: '2-digit', month: 'short' })} ${time(end)}`;
}

/**
 * Ce que la courbe affirme, en une phrase — c'est le nom accessible de la sparkline et son
 * infobulle. Une image sans texte n'est pas une information pour qui ne la voit pas, et le tableau
 * en compte vingt-cinq.
 */
export function describeActivity(activity: TopicActivity | null | undefined, topic: string): string {
  if (!activity) return `Activity for ${topic}: not measured yet.`;
  if (!activity.available) {
    return `Activity for ${topic} could not be measured: ${activity.note ?? 'the broker did not answer.'}`;
  }
  const span = formatWindow(activity.windowEndMs - activity.windowStartMs);
  if (activity.total === 0) {
    const quiet = `No message produced in ${topic} over the last ${span}.`;
    return activity.note ? `${quiet} ${activity.note}` : quiet;
  }
  const shape = sparkline(activity.counts, 100, 20);
  const peak = `Peak ${shape.peak.toLocaleString()} at ${bucketLabel(activity, shape.peakIndex)}.`;
  const head = `${activity.total.toLocaleString()} message${activity.total === 1 ? '' : 's'} produced in ${topic} over the last ${span}.`;
  return activity.note ? `${head} ${peak} ${activity.note}` : `${head} ${peak}`;
}

/**
 * La phrase courte sous la colonne, quand la lecture a été bornée ou dégradée. Les warnings du
 * serveur sont rendus tels quels ; celle-ci ne parle que de ce que le navigateur sait.
 */
export function describeActivityScope(
  requested: number, measured: number, window: ActivityWindow,
): string {
  const per = formatWindow(window.bucketMs);
  const base = `One point per ${per} over the last ${formatWindow(window.windowMs)}, counted from offsets.`;
  if (measured >= requested) return base;
  return `${base} ${requested - measured} of the ${requested} topics on this page could not be measured.`;
}
