// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors

/**
 * Ce qu'une métrique a réellement mesuré, en quelques mots sur sa carte.
 *
 * `MetricConfig.lastSummary` portait déjà le taux d'appariement, les décomptes non appariés, le
 * délai entre les deux lectures et la note de portée — calculés, persistés dans
 * `internal.metrics.config`, et affichés à personne : seul l'aperçu du modal les rendait, donc
 * uniquement avant l'enregistrement. Une métrique en service n'en montrait rien.
 *
 * Ça compte surtout pour la latence de transit : un événement source dont la cible n'est jamais
 * arrivée ne pèse pas dans la moyenne, donc la valeur *s'améliore* quand le pipeline casse. Le
 * taux d'appariement est ce qui empêche de lire la moyenne comme un verdict — voir
 * METRICS-TWO-QUERY-AUDIT.md, D6.
 */

export type ScopeTone = 'neutral' | 'warning';

export interface ScopeChip {
  /** Ce qui s'affiche sur la puce. */
  label: string;
  /** Ce que la puce veut dire, en une phrase, pour l'infobulle. */
  detail: string;
  tone: ScopeTone;
}

/** En dessous, la moyenne décrit une minorité des événements lus et le dit. */
export const LOW_MATCH_RATE = 0.9;

function num(summary: Record<string, unknown>, key: string): number | null {
  const value = summary[key];
  return typeof value === 'number' && Number.isFinite(value) ? value : null;
}

/** `4.2 s`, `320 ms` — l'unité suit l'ordre de grandeur, comme partout ailleurs. */
export function formatDurationMs(ms: number): string {
  if (ms < 1000) return `${Math.round(ms)} ms`;
  if (ms < 60_000) return `${(ms / 1000).toFixed(1)} s`;
  return `${Math.round(ms / 60_000)} min`;
}

/**
 * Les faits de portée d'une métrique, prêts à afficher — jamais un verdict.
 *
 * Ce qui vaut zéro et ce qui est absent ne sont pas la même chose : un décompte absent ne produit
 * aucune puce (la métrique ne mesure pas ça), un décompte à zéro non plus quand zéro est le cas
 * ordinaire et donc muet. Le taux d'appariement fait exception et s'affiche même à 100 % : une
 * indication qui n'apparaît que sur mauvaise nouvelle est une indication qu'on cesse de lire.
 */
export function describeMetricScope(lastSummary: Record<string, unknown> | null): ScopeChip[] {
  if (!lastSummary) return [];
  const chips: ScopeChip[] = [];

  const matchRate = num(lastSummary, 'matchRate');
  if (matchRate !== null) {
    const matched = num(lastSummary, 'matchedCount');
    const unmatched = num(lastSummary, 'unmatchedSourceCount');
    const of = matched !== null && unmatched !== null ? ` of ${matched + unmatched}` : '';
    chips.push({
      label: `${Math.round(matchRate * 100)}% paired`,
      detail:
        `${matched ?? '?'}${of} source event(s) found a target event. The value averages those alone, ` +
        'so it improves when a downstream stage stalls — read it against this rate, not on its own.',
      tone: matchRate < LOW_MATCH_RATE ? 'warning' : 'neutral',
    });
  }

  const unmatchedTargets = num(lastSummary, 'unmatchedTargetCount');
  if (unmatchedTargets !== null && unmatchedTargets > 0) {
    chips.push({
      label: `${unmatchedTargets} target${unmatchedTargets > 1 ? 's' : ''} unpaired`,
      detail: 'Target events no source event claimed: a replay, a duplicate, or a source outside the window read.',
      tone: 'neutral',
    });
  }

  const outOfOrder = num(lastSummary, 'outOfOrderCount');
  if (outOfOrder !== null && outOfOrder > 0) {
    chips.push({
      label: `${outOfOrder} before source`,
      detail:
        'Target events stamped before the source they match: two producers whose clocks disagree, ' +
        'or an event back-dated on the way. Dropped from the average — a negative latency is not a latency.',
      tone: 'warning',
    });
  }

  const readGapMs = num(lastSummary, 'readGapMs');
  if (readGapMs !== null) {
    chips.push({
      label: `${formatDurationMs(readGapMs)} apart`,
      detail:
        'The two sides were read this far apart, never at one instant. Traffic in between lands in ' +
        'one of the two counts and not the other.',
      tone: 'neutral',
    });
  }

  const warnings = lastSummary.warnings;
  if (Array.isArray(warnings) && warnings.length > 0) {
    chips.push({
      label: `${warnings.length} caveat${warnings.length > 1 ? 's' : ''}`,
      detail: warnings.filter(w => typeof w === 'string').join(' · '),
      tone: 'warning',
    });
  }

  return chips;
}

/** La phrase de portée du serveur, quand il en a écrit une. */
export function scopeNoteOf(lastSummary: Record<string, unknown> | null): string | null {
  const note = lastSummary?.scopeNote;
  return typeof note === 'string' && note.trim() !== '' ? note : null;
}
