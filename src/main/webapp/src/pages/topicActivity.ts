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
// Le format d'instant du formulaire de recherche appartient à ce module-là : en réécrire un ici
// donnerait deux façons d'écrire la même valeur, dont une qui dérive.
import { toDateTimeLocal } from '../components/topic/topicSearch';

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
  const silence = detectSilence(activity);
  // Le silence passe avant la note du serveur : c'est ce qui a le plus de chances de faire agir.
  const quiet = silence
    ? ` Nothing produced for at least ${formatWindow(silence.atLeastMs)}, after producing earlier in the window.`
    : '';
  return `${head} ${peak}${quiet}${activity.note ? ` ${activity.note}` : ''}`;
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

// ── Ce que la série dit d'elle-même ──────────────────────────────────────────

/** Le pic rapporté à la largeur d'un bucket : « 620/h », « 43/5 min ». */
export function describeRate(count: number, bucketMs: number): string {
  return `${compact(count)}/${unitOf(bucketMs)}`;
}

/** `1.2K` — la valeur exacte reste dans l'énoncé accessible, qui n'a pas de contrainte de place. */
export function compact(count: number): string {
  if (count >= 1_000_000) return `${(count / 1_000_000).toFixed(1)}M`;
  if (count >= 1_000) return `${(count / 1_000).toFixed(1)}K`;
  return String(count);
}

function unitOf(bucketMs: number): string {
  if (bucketMs % HOUR === 0) {
    const hours = bucketMs / HOUR;
    return hours === 1 ? 'h' : `${hours} h`;
  }
  if (bucketMs % MINUTE === 0) {
    const minutes = bucketMs / MINUTE;
    return minutes === 1 ? 'min' : `${minutes} min`;
  }
  return `${Math.max(1, Math.round(bucketMs / 1000))} s`;
}

/** Un topic qui produisait et qui s'est tu. */
export interface Silence {
  /** Buckets vides à la fin de la fenêtre. */
  buckets: number;
  /** Durée du silence — un **plancher** : le dernier message est quelque part dans son bucket. */
  atLeastMs: number;
}

/**
 * Le topic produisait, et ne produit plus.
 *
 * C'est le signal le plus actionnable d'une série, et le seul que sa *forme* ne donne pas : une
 * courbe qui retombe à zéro et une courbe basse se ressemblent à cette taille. La colonne « Last
 * Message » en dit la moitié — elle donne l'instant du dernier message, pas le fait qu'il y avait
 * un régime avant.
 *
 * Trois garde-fous, parce qu'un badge qui se trompe est un badge qu'on apprend à ignorer : il faut
 * un régime préalable (au moins deux buckets non vides avant le silence), un silence qui ne soit
 * pas un simple creux (au moins deux buckets, et au moins 15 % de la fenêtre) et une fenêtre qui
 * ne soit pas entièrement vide — ce dernier cas n'est pas un topic qui s'est tu, c'est un topic
 * qu'on n'a pas vu produire. Ce qui est affirmé reste un fait daté, jamais un verdict : « silent
 * 4 h+ » est vrai d'un topic qui dort la nuit, et c'est à l'opérateur de savoir si ça l'inquiète.
 */
export function detectSilence(activity: TopicActivity | null | undefined): Silence | null {
  if (!activity || !activity.available || activity.total <= 0) return null;
  const counts = activity.counts;
  if (counts.length === 0) return null;

  let trailing = 0;
  while (trailing < counts.length && counts[counts.length - 1 - trailing] === 0) trailing++;
  if (trailing === 0 || trailing === counts.length) return null;
  if (trailing < Math.max(2, Math.ceil(counts.length * 0.15))) return null;

  const active = counts.slice(0, counts.length - trailing).filter(c => c > 0).length;
  if (active < 2) return null;

  return { buckets: trailing, atLeastMs: trailing * activity.bucketMs };
}

/** « silent 4 h+ » — le `+` porte le plancher, qui est ce que les buckets permettent d'affirmer. */
export function describeSilence(silence: Silence): string {
  return `silent ${formatWindow(silence.atLeastMs)}+`;
}

// ── Du pic aux messages ──────────────────────────────────────────────────────

/**
 * Le lien qui mène du pic aux messages : l'explorateur de ce topic, recherche **amorcée** au début
 * du bucket.
 *
 * Amorcée et pas lancée, et c'est le contrat de l'autre page plutôt qu'un demi-effort : la
 * recherche de topic n'a pas de critère « tout » — `EXISTS` en mode KEY a précisément été retiré
 * de l'interface parce qu'il ramenait tout ce qui était scanné. Le lien porte donc ce qu'il sait
 * (l'instant) et laisse l'opérateur dire ce qu'il cherche ; `seedFromQuery` pose le formulaire à
 * l'arrivée. Passer par l'URL plutôt que par le brouillon `localStorage` de l'explorateur est
 * délibéré : c'est la convention de toute l'application, et ça rend le lien partageable au lieu
 * d'écraser en silence un critère non lancé sur un autre topic.
 */
export function bucketLink(topic: string, activity: TopicActivity, index: number): string {
  const at = toDateTimeLocal(activity.windowStartMs + index * activity.bucketMs);
  return `/topic/${encodeURIComponent(topic)}?start=TIMESTAMP&at=${encodeURIComponent(at)}`;
}

/** Ce que le clic va faire, dit avant qu'il ait lieu. */
export function describeBucketLink(activity: TopicActivity, index: number): string {
  return `Opens ${activity.topic} with the search primed at ${bucketLabel(activity, index)}.`;
}
