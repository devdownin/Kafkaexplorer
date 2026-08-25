// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors

/**
 * Ce sur quoi une analyse Process Mining repose réellement, mis en phrases.
 *
 * Un diagramme et une liste d'anomalies ne peuvent pas dire ce qu'ils ont regardé : une analyse de
 * huit topics dont trois étaient vides, un mal orthographié et deux tombés au-delà du budget de
 * prompt se dessinait exactement comme une analyse complète des huit. Le silence du modèle sur un
 * topic se lit alors comme un constat *sur* ce topic, et non comme l'absence de question — c'est la
 * façon la plus forte dont cette page peut induire en erreur.
 *
 * Les verdicts viennent du serveur (`ProcessMiningCoverage`) ; ici on ne fait que les tourner en
 * phrases. Même règle que partout ailleurs : une règle d'appréciation écrite des deux côtés est une
 * règle qui dérive.
 */

import type { ProcessMiningCoverage, TopicCoverage } from '../api/types';

/** Ce qu'il faut retenir de la couverture, avant même de lire le détail. */
export type CoverageTone = 'complete' | 'partial' | 'failed';

export interface CoverageSummary {
  tone: CoverageTone;
  /** Une ligne : ce qui a été lu, et ce qui en est parvenu au modèle. */
  headline: string;
  /** Ce qui manque, nommé. Vide quand tout ce qui a été demandé a été analysé. */
  notes: string[];
  /** Topics lus mais absents du prompt : ils n'ont pas pu peser sur la réponse. */
  omitted: string[];
  /** Topics dont aucune partition n'a été décrite : un nom qui ne désigne rien ici. */
  unreadable: string[];
  /** Topics résolus qui n'avaient aucun message dans la fenêtre lue. */
  empty: string[];
}

const plural = (n: number, one: string, many = `${one}s`) => (n === 1 ? one : many);

const list = (topics: string[], max = 4): string =>
  topics.length <= max
    ? topics.join(', ')
    : `${topics.slice(0, max).join(', ')} +${topics.length - max}`;

/** Topics lus mais dont aucun message n'a été inliné : le budget de prompt les a écartés. */
export function omittedTopics(topics: TopicCoverage[]): string[] {
  return topics
    .filter(t => t.readable && t.messagesRead > 0 && t.messagesAnalysed === 0)
    .map(t => t.topic);
}

export function unreadableTopics(topics: TopicCoverage[]): string[] {
  return topics.filter(t => !t.readable).map(t => t.topic);
}

export function emptyTopics(topics: TopicCoverage[]): string[] {
  return topics.filter(t => t.readable && t.messagesRead === 0).map(t => t.topic);
}

/**
 * Résume une couverture, ou rend `null` quand il n'y a rien à dire — c'est-à-dire jamais pour un
 * snapshot, et toujours pour le mode live, où la portée d'une fenêtre est déjà rapportée par
 * `WINDOW_STATS`.
 */
export function describeCoverage(coverage: ProcessMiningCoverage | null | undefined): CoverageSummary | null {
  if (!coverage) return null;

  const topics = coverage.topics ?? [];
  const omitted = omittedTopics(topics);
  const unreadable = unreadableTopics(topics);
  const empty = emptyTopics(topics);
  const analysedTopics = topics.filter(t => t.messagesAnalysed > 0).length;

  const headline = topics.length === 0
    ? `${coverage.messagesRead.toLocaleString()} ${plural(coverage.messagesRead, 'message')} read`
    : `${analysedTopics}/${topics.length} ${plural(topics.length, 'topic')} analysed · `
      + `${coverage.messagesAnalysed.toLocaleString()} of ${coverage.messagesRead.toLocaleString()} `
      + `${plural(coverage.messagesRead, 'message')} read reached the model`;

  const notes: string[] = [];

  // Une lecture qui a échoué se dit avant tout le reste : ce qui suit décrit ce qui est arrivé
  // avant la panne, pas ce qui avait été demandé.
  if (coverage.readError) {
    notes.push(`The read failed and the analysis ran on what had already arrived — ${coverage.readError}`);
  }
  if (unreadable.length > 0) {
    notes.push(`${unreadable.length} ${plural(unreadable.length, 'topic')} could not be resolved on `
      + `the cluster (${list(unreadable)}) — check the ${plural(unreadable.length, 'name')}.`);
  }
  if (empty.length > 0) {
    notes.push(`${empty.length} ${plural(empty.length, 'topic')} held no message in the window read `
      + `(${list(empty)}). The analysis says nothing about ${empty.length === 1 ? 'it' : 'them'} `
      + `because nothing was shown of ${empty.length === 1 ? 'it' : 'them'}.`);
  }
  if (omitted.length > 0) {
    notes.push(`${omitted.length} ${plural(omitted.length, 'topic')} were read but did not fit the `
      + `prompt budget (${list(omitted)}) — raise process-mining.prompt-char-budget, or analyse `
      + `fewer topics at once.`);
  }
  // Une lecture arrêtée par son propre budget rend des comptes qui sont des planchers : le dire,
  // sinon « 500 messages lus » se lit comme « il n'y en avait que 500 ».
  if (coverage.readTruncated) {
    notes.push('The read stopped on its own time budget, so these counts are floors — the topics '
      + 'may hold more in the window asked for.');
  }
  for (const warning of coverage.warnings ?? []) {
    notes.push(warning);
  }

  const tone: CoverageTone = coverage.readError || (topics.length > 0 && analysedTopics === 0)
    ? 'failed'
    : notes.length > 0 ? 'partial' : 'complete';

  return { tone, headline, notes, omitted, unreadable, empty };
}
