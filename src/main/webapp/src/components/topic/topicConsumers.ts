// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors

/**
 * Lecture du retard des groupes de consommateurs sur un topic.
 *
 * Logique pure : le panneau ne fait que rendre ce que ce module décide. Même découpage que
 * `topicSearch.ts` — ce qui se teste sans navigateur est testé.
 */

/*
 * Les formes de réponse vivent dans `api/types.ts`, où `docs/check-api-types.py` les résout contre
 * leurs records Java. Elles étaient déclarées ici, à la main et hors de toute vérification — le
 * défaut même que ce script existe pour empêcher, et qui a déjà coûté la page Compare une fois.
 */
import type { ConsumerGroupLag, PartitionLag, TopicConsumers } from '../../api/types';

export type { ConsumerGroupLag, PartitionLag, TopicConsumers };

export type GroupSort = 'lag' | 'groupId' | 'state';

/**
 * Diagnostic d'un groupe, indépendamment du chiffre.
 *
 * Le retard seul ne dit pas grand-chose : zéro sur un groupe sans membre veut dire que personne ne
 * lit et que le topic ne bouge pas, ce qui n'est pas « à jour ». Trois cas méritent donc d'être
 * nommés à part, et c'est ce que la pastille du panneau affiche.
 */
export type GroupHealth = 'CAUGHT_UP' | 'BEHIND' | 'STALLED' | 'PARTIAL' | 'AHEAD' | 'UNKNOWN';

export function healthOf(group: ConsumerGroupLag): GroupHealth {
  if (group.error) return 'UNKNOWN';
  if (group.partitions.some(p => p.lag !== null && p.lag < 0)) return 'AHEAD';
  // Aucun membre vivant : le retard ne se résorbera pas tout seul, quel qu'il soit. Mais il faut
  // avoir pu le constater : un groupe que `describeConsumerGroups` n'a pas su décrire arrive avec
  // zéro membre faute de réponse, pas faute de membre — le déclarer bloqué serait une conclusion
  // tirée d'un appel qui n'a rien dit. Même règle que `ConsumerGroupLag.health()` côté serveur.
  if (group.membersKnown && group.assignedMembers === 0 && group.totalLag > 0) return 'STALLED';
  if (group.partitionsWithoutCommit > 0) return 'PARTIAL';
  return group.totalLag > 0 ? 'BEHIND' : 'CAUGHT_UP';
}

export const HEALTH_TONE: Record<GroupHealth, 'success' | 'warning' | 'error' | 'neutral'> = {
  CAUGHT_UP: 'success',
  BEHIND: 'neutral',
  STALLED: 'error',
  PARTIAL: 'warning',
  AHEAD: 'error',
  UNKNOWN: 'neutral',
};

export const HEALTH_LABEL: Record<GroupHealth, string> = {
  CAUGHT_UP: 'Caught up',
  BEHIND: 'Behind',
  STALLED: 'Stalled',
  PARTIAL: 'Partial',
  AHEAD: 'Ahead of the log',
  UNKNOWN: 'Unreadable',
};

export const HEALTH_HELP: Record<GroupHealth, string> = {
  CAUGHT_UP: 'Committed at the end of every partition of this topic.',
  BEHIND: 'Reading, but not at the end. Normal on a live topic — watch whether it grows.',
  STALLED: 'Records are waiting and no member of the group is assigned to this topic. '
    + 'Nothing will consume them until one joins.',
  PARTIAL: 'The group has never committed on some partitions of this topic. Those are not '
    + 'counted in the lag, and nothing is reading them.',
  AHEAD: 'A committed offset sits past the end of its partition — an offset reset to a future '
    + 'position, or a topic recreated under the same name.',
  UNKNOWN: 'This group could not be read.',
};

/** Somme des retards connus, à travers tous les groupes affichés. */
export function totalLag(groups: ConsumerGroupLag[]): number {
  return groups.reduce((sum, g) => sum + g.totalLag, 0);
}

export function sortGroups(groups: ConsumerGroupLag[], sort: GroupSort, desc: boolean): ConsumerGroupLag[] {
  const factor = desc ? -1 : 1;
  return [...groups].sort((a, b) => {
    if (sort === 'groupId') return factor * a.groupId.localeCompare(b.groupId);
    if (sort === 'state') return factor * (a.state.localeCompare(b.state) || a.groupId.localeCompare(b.groupId));
    return factor * (a.totalLag - b.totalLag) || a.groupId.localeCompare(b.groupId);
  });
}

export function filterGroups(groups: ConsumerGroupLag[], text: string): ConsumerGroupLag[] {
  const needle = text.trim().toLowerCase();
  if (!needle) return groups;
  return groups.filter(g => g.groupId.toLowerCase().includes(needle));
}

/**
 * Ce que la lecture a couvert.
 *
 * Une liste vide veut dire deux choses très différentes selon le nombre de groupes examinés, et
 * c'est exactement la confusion que cette ligne existe pour empêcher.
 */
export function describeScope(consumers: TopicConsumers | null): string {
  if (!consumers) return '';
  const { groups, groupsExamined, groupsEligible, groupsInCluster, truncated, available } = consumers;
  // Une lecture qui a échoué revient à zéro groupe sur zéro examiné, exactement comme un cluster
  // qui n'en a aucun. Sans ce drapeau, la ligne affirmait « le cluster n'a aucun groupe » alors
  // que la question n'avait pas pu être posée — la seule réponse qu'elle ne doit jamais donner.
  if (!available) {
    return 'The consumer groups could not be read — this says nothing about who consumes this topic.';
  }
  if (groupsExamined === 0) {
    if (groupsInCluster === 0) return 'The cluster has no client group at all.';
    // Zéro examiné avec des groupes au compteur : tous ont été écartés (share groups, groupes de
    // l'application). Les avertissements disent lesquels ; ce n'est pas un échec de lecture.
    return `None of the cluster's ${groupsInCluster} groups could be measured on this topic.`;
  }
  // Le dénominateur est le nombre de groupes éligibles, pas celui du cluster : mélanger un
  // numérateur d'après exclusions avec un total d'avant faisait lire « 200 sur 3000 » là où 800
  // des 3000 appartenaient à l'application elle-même.
  const read = truncated
    ? `${groupsExamined} of ${groupsEligible} eligible groups read`
    : `all ${groupsExamined} eligible group${groupsExamined === 1 ? '' : 's'} read`;
  const scope = groupsEligible < groupsInCluster
    ? `${read} of the cluster's ${groupsInCluster}`
    : read;
  if (groups.length === 0) {
    return `No group holds a committed offset on this topic — ${scope}.`;
  }
  const plural = groups.length === 1 ? '' : 's';
  return `${groups.length} group${plural} consume${groups.length === 1 ? 's' : ''} this topic — ${scope}.`;
}

/** Résumé chiffré au-dessus de la liste, ou `null` quand il n'y a rien à résumer. */
export function describeSummary(groups: ConsumerGroupLag[]): string | null {
  if (groups.length === 0) return null;
  const parts: string[] = [`${formatCount(totalLag(groups))} messages behind in total`];
  const stalled = groups.filter(g => healthOf(g) === 'STALLED');
  if (stalled.length > 0) {
    parts.push(`${stalled.length} group${stalled.length === 1 ? '' : 's'} stalled with no assigned member`);
  }
  const partial = groups.filter(g => healthOf(g) === 'PARTIAL');
  if (partial.length > 0) {
    parts.push(`${partial.length} reading only part of the partitions`);
  }
  // Un groupe illisible pèse zéro dans le total : le dire, sans quoi la somme se lit comme
  // exhaustive alors qu'elle ignore précisément les lignes dont on ne sait rien.
  const unreadable = groups.filter(g => healthOf(g) === 'UNKNOWN');
  if (unreadable.length > 0) {
    parts.push(`${unreadable.length} could not be read and count for nothing in that total`);
  }
  return parts.join(' · ');
}

/** 1 234 567 → « 1.2M ». La valeur exacte reste dans un `title`. */
export function formatCount(value: number): string {
  const abs = Math.abs(value);
  if (abs < 1000) return String(value);
  if (abs < 1_000_000) return `${trimZero((value / 1000).toFixed(1))}K`;
  if (abs < 1_000_000_000) return `${trimZero((value / 1_000_000).toFixed(1))}M`;
  return `${trimZero((value / 1_000_000_000).toFixed(1))}B`;
}

function trimZero(text: string): string {
  return text.endsWith('.0') ? text.slice(0, -2) : text;
}

/**
 * Part de la partition déjà consommée, entre 0 et 1 — de quoi dessiner une barre.
 *
 * `null` sans offset committé (rien à dessiner) et sur une partition vide (une barre pleine
 * dirait « tout consommé » là où il n'y a rien à consommer).
 */
export function progressOf(partition: PartitionLag): number | null {
  if (partition.committedOffset === null || partition.endOffset <= 0) return null;
  const ratio = partition.committedOffset / partition.endOffset;
  return Math.max(0, Math.min(1, ratio));
}
