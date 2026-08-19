// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors

import { expandTopicPatterns, isTopicPattern, parseTopicList } from './pages/streamFlow';

/**
 * Choisir des topics : un nom, une liste collée, ou un motif.
 *
 * Trois écrans posent la même question — Stream Flow (quels topics tracer), Data Model (quels
 * topics modéliser), Process Mining (quels topics profiler) — et la réponse doit être la même
 * partout, sans quoi un geste appris sur l'un ne fonctionne pas sur l'autre. `Layout` porte déjà
 * cette règle pour le seuil desktop (`breakpoints.ts`) ; c'est le même raisonnement ici.
 *
 * Les deux primitives restent dans `pages/streamFlow.ts`, où elles sont définies et testées :
 * `parseTopicList` sépare sur les virgules, les espaces et les sauts de ligne — une liste sortie
 * d'un runbook ou d'un `kafka-topics --list` entre d'un coup — et `expandTopicPatterns` étend les
 * motifs (`orders.*`, `*` seul joker, point littéral) sur un catalogue déjà chargé, sans requête
 * supplémentaire. Ce module ajoute ce que les écrans ont en commun *au-dessus* d'elles : la
 * fusion dans une sélection existante, un plafond facultatif, et le compte rendu de ce qui n'est
 * pas entré.
 */

/**
 * Ce qu'une saisie a produit. Les trois dernières listes existent pour être dites — un ajout
 * partiel silencieux laisserait croire que tout est entré.
 */
export interface TopicEntry {
  selection: string[];
  added: string[];
  /** Motifs qui ne correspondent à aucun topic du catalogue. */
  unmatched: string[];
  /**
   * Pour un motif sans correspondance, la version élargie qui en aurait — `orders*` → `*orders*`.
   * Absent quand élargir ne donnerait rien non plus : proposer une syntaxe qui échoue aussi
   * serait pire que se taire.
   */
  suggestions: Record<string, string>;
  /** Topics laissés dehors par le plafond, vide quand il n'y en a pas. */
  overflow: string[];
  /** Le plafond appliqué, ou `null` — c'est lui qui permet de le nommer dans le compte rendu. */
  cap: number | null;
}

/**
 * Le motif élargi qui aurait trouvé quelque chose, ou `null`.
 *
 * Les motifs sont **ancrés** : `demo.orders.*` désigne ce qui est *sous* `demo.orders`, et c'est
 * ce qui les rend précis — `demo.*` ne ramène pas `internal.demo.x`. La contrepartie est que
 * `orders*` ne correspond à rien sur un catalogue en `demo.orders.…`, ce qui se lit comme « ce
 * topic n'existe pas » plutôt que comme « ce motif est ancré ».
 *
 * L'échappatoire existe déjà — `*orders*` fonctionne — donc ce qui manquait n'était pas la
 * syntaxe mais le fait de la connaître. Élargir automatiquement aurait été le mauvais correctif :
 * ça aurait dilué la précision de tous les autres motifs pour offrir ce que la syntaxe donne
 * déjà. On propose, on n'applique pas.
 *
 * `null` quand le motif est déjà entouré d'étoiles, ou quand l'élargir ne donnerait rien non
 * plus : suggérer une syntaxe qui échoue aussi est pire que se taire.
 */
export function suggestBroaderPattern(pattern: string, catalog: string[]): string | null {
  if (!isTopicPattern(pattern)) return null;
  if (pattern.startsWith('*') && pattern.endsWith('*')) return null;
  const broader = `*${pattern.replace(/^\*+/, '').replace(/\*+$/, '')}*`;
  if (broader === pattern) return null;
  return expandTopicPatterns([broader], catalog).topics.length > 0 ? broader : null;
}

/**
 * Ajoute une saisie à la sélection.
 *
 * Un motif sans correspondance est **rendu**, pas envoyé comme nom de topic : il n'en est pas
 * un, et l'envoyer ne produirait qu'un avertissement à l'autre bout du voyage. Un nom sans `*`
 * que le catalogue ne connaît pas passe en revanche tel quel — un topic peut exister avant que
 * le cache de 30 s ne le montre.
 *
 * `cap` est le plafond de la requête quand l'écran en a un (Data Model en a un, côté serveur ;
 * Process Mining et Stream Flow n'en ont pas). Ce qu'il refuse est rendu plutôt que tronqué en
 * silence.
 *
 * Les topics `internal.*` ne sont **pas** écartés : un motif tapé à la main est une demande
 * explicite. C'est un geste différent d'un « tout cocher » sur une liste filtrée, qui a ses
 * propres règles là où il est proposé.
 */
export function addTopicEntries(
  selection: string[],
  text: string,
  catalog: string[],
  cap: number | null = null,
): TopicEntry {
  const entries = parseTopicList(text);
  if (entries.length === 0) {
    return { selection, added: [], unmatched: [], overflow: [], suggestions: {}, cap };
  }

  const { topics, unmatched } = expandTopicPatterns(entries, catalog);
  const next = [...selection];
  const added: string[] = [];
  const overflow: string[] = [];
  for (const topic of topics) {
    if (next.includes(topic)) continue;
    if (cap !== null && next.length >= cap) {
      overflow.push(topic);
      continue;
    }
    next.push(topic);
    added.push(topic);
  }
  const suggestions: Record<string, string> = {};
  for (const miss of unmatched) {
    const broader = suggestBroaderPattern(miss, catalog);
    if (broader) suggestions[miss] = broader;
  }
  return { selection: next, added, unmatched, overflow, suggestions, cap };
}

/**
 * Le résultat d'une saisie, en une phrase — ou `null` quand il n'y a rien à dire. Ce qui n'a pas
 * été ajouté doit s'énoncer : un motif qui ne correspond à rien et un plafond atteint sont deux
 * façons différentes de repartir avec moins que ce qu'on a demandé.
 */
export function describeTopicEntry(entry: TopicEntry): string | null {
  const parts: string[] = [];
  if (entry.added.length > 0) {
    parts.push(`${entry.added.length} topic${entry.added.length > 1 ? 's' : ''} added`);
  }
  if (entry.unmatched.length > 0) {
    // Le motif ancré n'a rien trouvé : dire lequel, et — quand il y en a une — la forme élargie
    // qui aurait trouvé. « aucun topic » et « motif ancré » sont deux réponses différentes.
    const missed = entry.unmatched
      .map(m => (entry.suggestions[m] ? `${m} (try ${entry.suggestions[m]})` : m))
      .join(', ');
    parts.push(`no topic matches ${missed}`);
  }
  if (entry.overflow.length > 0) {
    parts.push(`${entry.overflow.length} left out — the request is capped at ${entry.cap} topics`);
  }
  if (parts.length === 0) {
    // Tout était déjà là : le dire, sinon la saisie semble n'avoir rien fait.
    return entry.selection.length > 0 ? 'Already selected' : null;
  }
  return parts.join(' · ');
}
