// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors

import { describe, it, expect } from 'vitest';
import { describeCoverage } from './processMiningCoverage';
import type { ProcessMiningCoverage, TopicCoverage } from '../api/types';

/**
 * Un topic du chemin *sans* log d'événements : `messagesMeasured` y vaut zéro par construction, ce
 * qui est exactement ce que le serveur produit quand aucun mapping ne permet de grouper en cas.
 */
const topic = (
  name: string,
  read: number,
  detailed: number,
  readable = true,
): TopicCoverage => ({
  topic: name, messagesRead: read, messagesMeasured: 0, messagesDetailed: detailed, readable,
});

/** Un topic du chemin mesuré : l'agrégat porte sur `measured`, les exemples sur `detailed`. */
const measuredTopic = (
  name: string,
  read: number,
  measured: number,
  detailed: number,
  readable = true,
): TopicCoverage => ({
  topic: name, messagesRead: read, messagesMeasured: measured, messagesDetailed: detailed, readable,
});

const coverage = (
  topics: TopicCoverage[],
  extra: Partial<ProcessMiningCoverage> = {},
): ProcessMiningCoverage => ({
  topics,
  messagesRead: topics.reduce((n, t) => n + t.messagesRead, 0),
  messagesMeasured: topics.reduce((n, t) => n + t.messagesMeasured, 0),
  messagesDetailed: topics.reduce((n, t) => n + t.messagesDetailed, 0),
  promptChars: 5_000,
  promptCharBudget: 120_000,
  readTruncated: false,
  readError: null,
  warnings: [],
  ...extra,
});

describe('describeCoverage', () => {
  it('says nothing when there is no coverage — the live path carries none', () => {
    expect(describeCoverage(null)).toBeNull();
    expect(describeCoverage(undefined)).toBeNull();
  });

  it('reports a run where everything asked for reached the model', () => {
    const summary = describeCoverage(coverage([topic('orders', 200, 40), topic('payments', 120, 30)]));

    expect(summary?.tone).toBe('complete');
    expect(summary?.headline).toContain('2/2 topics analysed');
    expect(summary?.notes).toHaveLength(0);
  });

  /*
   * Le cas qui a motivé tout ceci : un topic lu en entier dont pas un message n'a atteint le
   * modèle. Rien à l'écran ne le disait, et le silence du modèle à son sujet se lisait comme un
   * constat sur ce topic.
   */
  it('names the topics the prompt budget left out', () => {
    const summary = describeCoverage(coverage([topic('orders', 200, 40), topic('audit', 500, 0)]));

    expect(summary?.tone).toBe('partial');
    expect(summary?.omitted).toEqual(['audit']);
    expect(summary?.notes.join(' ')).toContain('did not fit the prompt budget');
    expect(summary?.notes.join(' ')).toContain('audit');
  });

  /*
   * Le défaut que le processus mesuré a introduit, et la raison de `messagesMeasured`.
   *
   * Le prompt s'ouvre désormais sur un agrégat calculé sur *tous* les enregistrements lus et
   * n'inline qu'une poignée de traces témoins. Compter les seules traces disait « 6 sur 3 000 »
   * d'une exécution qui avait tout mesuré, et renvoyait vers un budget consommé à 6 %.
   */
  it('counts what the measured process covers, not just the worked examples', () => {
    const summary = describeCoverage(coverage([
      measuredTopic('orders', 1_500, 1_500, 3),
      measuredTopic('payments', 1_500, 1_488, 3),
    ]));

    expect(summary?.tone).toBe('complete');
    expect(summary?.headline).toContain('2/2 topics measured');
    expect(summary?.headline).toContain('2,988 of 3,000 messages read entered the measured process');
    expect(summary?.headline).toContain('6 shown as worked examples');
    expect(summary?.notes).toHaveLength(0);
  });

  /*
   * Un topic mesuré qu'aucun cas témoin ne traverse n'est pas écarté : il pèse sur chaque
   * transition et chaque latence. L'annoncer comme perdu était l'effondrement de périmètre que
   * cette page existe pour ne pas inventer.
   */
  it('does not call a measured topic omitted just because no worked example crosses it', () => {
    const summary = describeCoverage(coverage([
      measuredTopic('orders', 500, 500, 6),
      measuredTopic('shipments', 500, 500, 0),
    ]));

    expect(summary?.omitted).toEqual([]);
    expect(summary?.tone).toBe('complete');
    expect(summary?.notes.join(' ')).not.toContain('prompt budget');
  });

  /*
   * Sur le chemin mesuré, un topic qui n'a rien apporté a une autre cause — et une autre
   * réparation. Envoyer l'opérateur augmenter le budget ne changerait rien : ce qui manque est
   * l'id de corrélation.
   */
  it('blames the field mapping, not the budget, when a measured run leaves a topic out', () => {
    const summary = describeCoverage(coverage([
      measuredTopic('orders', 500, 500, 6),
      measuredTopic('legacy', 300, 0, 0),
    ]));

    expect(summary?.omitted).toEqual(['legacy']);
    expect(summary?.notes.join(' ')).toContain('carried the mapped correlation id');
    expect(summary?.notes.join(' ')).not.toContain('prompt-char-budget');
  });

  /* Un topic absent du cluster et un topic vide ne s'adressent pas au même problème. */
  it('separates a topic that does not resolve from one that is merely empty', () => {
    const summary = describeCoverage(coverage([
      topic('orders', 10, 10),
      topic('typo-topic', 0, 0, false),
      topic('quiet', 0, 0),
    ]));

    expect(summary?.unreadable).toEqual(['typo-topic']);
    expect(summary?.empty).toEqual(['quiet']);
    const notes = summary?.notes.join(' ') ?? '';
    expect(notes).toContain('could not be resolved');
    expect(notes).toContain('held no message');
  });

  it('states that a read stopped on its budget, so the counts are floors', () => {
    const summary = describeCoverage(coverage([topic('orders', 500, 40)], { readTruncated: true }));

    expect(summary?.notes.join(' ')).toContain('floors');
  });

  /* Une lecture cassée n'est pas une lecture vide : le ton passe à l'échec et la raison est citée. */
  it('leads with the read failure and carries the reason', () => {
    const summary = describeCoverage(coverage([topic('orders', 3, 3)], {
      readError: 'Broker not available',
    }));

    expect(summary?.tone).toBe('failed');
    expect(summary?.notes[0]).toContain('Broker not available');
  });

  it('grades a run where no topic reached the model at all as failed', () => {
    const summary = describeCoverage(coverage([topic('orders', 0, 0), topic('payments', 0, 0)]));

    expect(summary?.tone).toBe('failed');
  });

  /* Les notes du serveur — le mapping perdu, par exemple — voyagent telles quelles. */
  it('passes the server-side scope warnings through', () => {
    const summary = describeCoverage(coverage([topic('orders', 10, 10)], {
      warnings: ['The validated field mapping is no longer held by this server.'],
    }));

    expect(summary?.tone).toBe('partial');
    expect(summary?.notes).toContain('The validated field mapping is no longer held by this server.');
  });

  it('does not name every topic when a run leaves many out', () => {
    const many = Array.from({ length: 9 }, (_, i) => topic(`t${i}`, 5, 0));
    const summary = describeCoverage(coverage([topic('orders', 5, 5), ...many]));

    expect(summary?.omitted).toHaveLength(9);
    expect(summary?.notes.join(' ')).toContain('+5');
  });
});
