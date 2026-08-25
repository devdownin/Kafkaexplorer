// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors

import { describe, it, expect } from 'vitest';
import { describeCoverage } from './processMiningCoverage';
import type { ProcessMiningCoverage, TopicCoverage } from '../api/types';

const topic = (
  name: string,
  read: number,
  analysed: number,
  readable = true,
): TopicCoverage => ({ topic: name, messagesRead: read, messagesAnalysed: analysed, readable });

const coverage = (
  topics: TopicCoverage[],
  extra: Partial<ProcessMiningCoverage> = {},
): ProcessMiningCoverage => ({
  topics,
  messagesRead: topics.reduce((n, t) => n + t.messagesRead, 0),
  messagesAnalysed: topics.reduce((n, t) => n + t.messagesAnalysed, 0),
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
