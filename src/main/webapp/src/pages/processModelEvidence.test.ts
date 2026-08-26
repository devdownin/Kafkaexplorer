// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors

import { beforeEach, describe, expect, it } from 'vitest';
import {
  clearProcessModels, evidenceFromModel, latestProcessModel, MAX_PROCESS_MODEL_AGE_MS,
  MAX_PROCESS_MODELS, modelRoute, PROCESS_MODEL_KEY, pushProcessModel, readProcessModels,
  recordMeasuredProcess, topicOfActivity, writeProcessModels,
} from './processModelEvidence';
import type { ProcessModel, ProcessModelEvidence } from '../api/types';

const NOW = 1_700_000_000_000;

function model(extra: Partial<ProcessModel> = {}): ProcessModel {
  return {
    available: true,
    unavailableReason: null,
    cases: 120,
    events: 300,
    eventsWithoutCase: 0,
    windowStartMs: NOW,
    windowEndMs: NOW + 3_600_000,
    eventTimeSource: 'MAPPED_FIELD',
    activities: [],
    edges: [
      { from: 'orders.received', to: 'orders.enriched', occurrences: 300, cases: 120,
        p50Ms: 900, p95Ms: 4_500, maxMs: 41_000, outOfOrderCount: 2 },
    ],
    variants: [{ path: ['orders.received', 'orders.enriched'], cases: 120, example: 'ORD-1' }],
    starts: [],
    ends: [],
    repeats: [{ activity: 'orders.received', casesAffected: 7, maxOccurrencesInOneCase: 3 }],
    spotlightCases: ['ORD-1'],
    variantsOmitted: 0,
    edgesOmitted: 0,
    notes: [],
    ...extra,
  };
}

function evidence(topics: string[], measuredAt = NOW): ProcessModelEvidence {
  return {
    measuredAt,
    cases: 10,
    windowStartMs: NOW,
    windowEndMs: NOW + 1000,
    eventTimeSource: 'MAPPED_FIELD',
    transitions: topics.slice(1).map((to, i) => ({
      from: topics[i], to, occurrences: 10, cases: 10, p50Ms: 1, p95Ms: 2, maxMs: 3,
    })),
    repeats: [],
  };
}

beforeEach(() => {
  localStorage.clear();
});

describe('evidenceFromModel', () => {
  /*
   * Ce qui est gardé est délibérément plus étroit que le modèle : les variantes et les cas
   * d'exemple sont la matière du prompt, et les renvoyer au serveur qui ne les lit pas serait du
   * volume que personne n'utilise.
   */
  it('keeps the transitions and the repeats, and nothing the server does not read', () => {
    const built = evidenceFromModel(model(), NOW);

    expect(built?.transitions).toHaveLength(1);
    expect(built?.transitions[0].p95Ms).toBe(4_500);
    expect(built?.repeats[0].casesAffected).toBe(7);
    expect(built?.measuredAt).toBe(NOW);
    expect(built).not.toHaveProperty('variants');
    expect(built).not.toHaveProperty('spotlightCases');
  });

  /* Pas de log d'événements n'est pas un processus vide, et une activité isolée n'est pas un
   * pipeline : ni l'un ni l'autre ne doit produire une observation à laquelle un KPI se fierait. */
  it('refuses a model with no log, and one with no transition', () => {
    expect(evidenceFromModel(model({ available: false }), NOW)).toBeNull();
    expect(evidenceFromModel(model({ edges: [] }), NOW)).toBeNull();
    expect(evidenceFromModel(null, NOW)).toBeNull();
  });
});

describe('topicOfActivity', () => {
  it('reads the topic out of a label a mapped status refined', () => {
    expect(topicOfActivity('orders.events · SHIPPED')).toBe('orders.events');
    expect(topicOfActivity('orders.received')).toBe('orders.received');
  });
});

describe('readProcessModels', () => {
  it('is empty when nothing was stored', () => {
    expect(readProcessModels(NOW)).toEqual([]);
  });

  /* Relire une enveloppe inconnue au jugé, c'est dériver un KPI de données dont on ne sait plus
   * la forme. Elle est effacée. */
  it('erases an envelope of another version rather than guessing at it', () => {
    localStorage.setItem(PROCESS_MODEL_KEY, JSON.stringify({ v: 99, models: [evidence(['a', 'b'])] }));
    expect(readProcessModels(NOW)).toEqual([]);
    expect(localStorage.getItem(PROCESS_MODEL_KEY)).toBeNull();
  });

  it('erases what will not parse at all', () => {
    localStorage.setItem(PROCESS_MODEL_KEY, 'not json');
    expect(readProcessModels(NOW)).toEqual([]);
    expect(localStorage.getItem(PROCESS_MODEL_KEY)).toBeNull();
  });

  /* Une mesure d'il y a trois semaines décrit un pipeline qui a pu changer. */
  it('drops a measurement past the expiry, and rewrites what is left', () => {
    writeProcessModels([evidence(['a', 'b'], NOW - MAX_PROCESS_MODEL_AGE_MS - 1), evidence(['c', 'd'])]);
    const fresh = readProcessModels(NOW);
    expect(fresh).toHaveLength(1);
    expect(modelRoute(fresh[0])).toBe('c,d');
    expect(readProcessModels(NOW)).toHaveLength(1);
  });
});

describe('pushProcessModel', () => {
  /*
   * Remesurer le même pipeline une heure plus tard décrit le même pipeline : garder les deux ne
   * donnerait pas deux propositions mais deux fois la même. La plus récente gagne.
   */
  it('deduplicates on the route, newest first', () => {
    pushProcessModel(evidence(['a', 'b'], NOW - 1000), NOW);
    const kept = pushProcessModel(evidence(['b', 'a'], NOW), NOW);

    expect(kept).toHaveLength(1);
    expect(kept[0].measuredAt).toBe(NOW);
  });

  it('keeps a bounded number of distinct pipelines', () => {
    for (let i = 0; i < MAX_PROCESS_MODELS + 2; i++) {
      pushProcessModel(evidence([`t${i}.in`, `t${i}.out`], NOW), NOW);
    }
    expect(readProcessModels(NOW)).toHaveLength(MAX_PROCESS_MODELS);
  });

  /* Deux fenêtres du même pipeline sont la même route même si les statuts observés diffèrent. */
  it('routes on topics, not on the activity labels a status refines', () => {
    expect(modelRoute(evidence(['orders.events · A', 'orders.events · B'])))
      .toBe('orders.events');
  });
});

describe('recordMeasuredProcess / latestProcessModel', () => {
  it('stores what an analysis measured and hands the freshest one back', () => {
    expect(recordMeasuredProcess(model(), NOW)).not.toBeNull();
    expect(latestProcessModel(NOW)?.transitions[0].from).toBe('orders.received');
  });

  it('writes nothing when there was nothing to keep', () => {
    expect(recordMeasuredProcess(model({ edges: [] }), NOW)).toBeNull();
    expect(localStorage.getItem(PROCESS_MODEL_KEY)).toBeNull();
    expect(latestProcessModel(NOW)).toBeNull();
  });

  it('clears on request', () => {
    recordMeasuredProcess(model(), NOW);
    clearProcessModels();
    expect(readProcessModels(NOW)).toEqual([]);
  });
});
