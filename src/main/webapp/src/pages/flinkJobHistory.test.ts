// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors

/*
 * La lecture de l'historique d'un job.
 *
 * Ce que ces cas fixent n'est pas la mise en forme mais les trois refus : une entrée dont
 * l'horodatage est inutilisable n'est pas rendue à l'époque Unix, une durée négative n'est pas
 * ramenée à zéro, et `UNAVAILABLE` — « on n'a pas su lire le statut » — ne se raconte pas comme
 * une fin. C'est la même distinction que le magasin porte côté serveur, et elle ne sert à rien si
 * l'écran la reperd.
 */

import { describe, it, expect } from 'vitest';
import type { FlinkManagedJobDetails } from '../api/types';
import {
  describeJobOutcome, formatDuration, historyCount, historyLines, jobDurationMs,
} from './flinkJobHistory';

const T = 1_700_000_000_000;

function details(over: Partial<FlinkManagedJobDetails> = {}): FlinkManagedJobDetails {
  return {
    queryId: 'q-1',
    flinkJobId: 'f-1',
    statementType: 'INSERT',
    executionMode: 'ASYNC_JOB',
    status: 'RUNNING',
    statusDetail: 'Submitted via Flink Job mode',
    sql: 'INSERT INTO sink SELECT * FROM src',
    startedAt: T,
    endedAt: null,
    cancelRequested: false,
    cancelRequestedAt: null,
    errorMessage: null,
    lastUpdatedAt: T,
    history: [],
    ...over,
  };
}


describe('historyLines', () => {
  it('orders the transitions and says how long each state lasted', () => {
    const lines = historyLines(details({
      history: [
        { timestamp: T + 5_000, status: 'FINISHED', detail: 'done' },
        { timestamp: T, status: 'RUNNING', detail: 'Submitted via Flink Job mode' },
      ],
    }));

    expect(lines.map(l => l.status)).toEqual(['RUNNING', 'FINISHED']);
    // Rien avant la première : « combien de temps depuis rien » n'est pas une durée, et un 0
    // laisserait croire à une transition instantanée.
    expect(lines[0].sincePreviousMs).toBeNull();
    expect(lines[1].sincePreviousMs).toBe(5_000);
  });

  it('drops an entry whose timestamp is unusable rather than dating it to 1970', () => {
    const lines = historyLines(details({
      history: [
        { timestamp: T, status: 'RUNNING', detail: null },
        { timestamp: Number.NaN, status: 'FINISHED', detail: 'done' },
      ],
    }));

    expect(lines).toHaveLength(1);
    expect(lines[0].status).toBe('RUNNING');
  });

  it('reads back a record written before the history existed', () => {
    expect(historyLines(details({ history: null }))).toEqual([]);
    expect(historyCount(details({ history: null }))).toBe(0);
    expect(historyLines(null)).toEqual([]);
  });

  it('treats a blank detail as no detail', () => {
    const [line] = historyLines(details({ history: [{ timestamp: T, status: 'RUNNING', detail: '   ' }] }));
    expect(line.detail).toBeNull();
  });
});

describe('jobDurationMs', () => {
  it('measures a finished job against its own end, and a running one against now', () => {
    expect(jobDurationMs(details({ endedAt: T + 2_310 }), T + 60_000)).toBe(2_310);
    expect(jobDurationMs(details(), T + 4_000)).toBe(4_000);
  });

  it('refuses to answer rather than reporting a negative run as instantaneous', () => {
    // Deux horloges : un enregistrement rapatrié d'un fichier peut porter une fin antérieure au
    // début. Zéro serait une mesure ; `null` dit qu'il n'y en a pas.
    expect(jobDurationMs(details({ endedAt: T - 1_000 }), T)).toBeNull();
    expect(jobDurationMs(null, T)).toBeNull();
  });
});

describe('formatDuration', () => {
  it('follows the order of magnitude', () => {
    expect(formatDuration(840)).toBe('840 ms');
    expect(formatDuration(2_310)).toBe('2.3 s');
    expect(formatDuration(250_000)).toBe('4 min 10 s');
    expect(formatDuration(7_200_000)).toBe('2 h');
  });

  it('shows an absent duration as absent', () => {
    expect(formatDuration(null)).toBe('—');
    expect(formatDuration(-1)).toBe('—');
  });
});

describe('describeJobOutcome', () => {
  it('distinguishes a job that ended from one still running', () => {
    expect(describeJobOutcome(details({ endedAt: T + 2_310, status: 'FINISHED' }), T + 60_000))
      .toBe('Ended after 2.3 s.');
    expect(describeJobOutcome(details(), T + 4_000)).toBe('Running for 4.0 s.');
  });

  it('says that an unreadable status is not an ending', () => {
    const said = describeJobOutcome(details({ status: 'UNAVAILABLE' }), T + 4_000);

    expect(said).toContain('could not be read');
    expect(said).toContain('not the same as having ended');
  });
});
