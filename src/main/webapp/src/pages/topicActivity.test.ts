// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors

/*
 * La logique de la colonne d'activité.
 *
 * Ce qui est vérifié ici n'est pas « le tracé est joli » : c'est que la courbe ne dise rien que la
 * mesure ne dise. Une série entièrement nulle se dessine sur la ligne de base et pas au milieu de
 * la boîte, une fenêtre amputée par la rétention se compte en buckets non mesurés plutôt qu'en
 * zéros, et l'énoncé accessible chiffre la pointe — sans quoi une échelle propre à chaque ligne
 * laisserait croire que deux courbes de même hauteur décrivent le même trafic.
 */

import { describe, it, expect, beforeEach } from 'vitest';
import type { TopicActivity } from '../api/types';
import {
  ACTIVITY_OFF, ACTIVITY_WINDOWS, DEFAULT_ACTIVITY_CHOICE, bucketLabel, bucketLink, compact,
  describeActivity, describeActivityScope, describeRate, describeScale, describeSilence,
  detectSilence, isFloor, readActivityChoice, readActivityScale, sparkline,
  unmeasuredLeadingBuckets, windowById, writeActivityChoice, writeActivityScale,
} from './topicActivity';
import { criteriaFromQuery, seedFromQuery, startTimestamp } from '../components/topic/topicSearch';

const HOUR = 3_600_000;

function activity(overrides: Partial<TopicActivity> = {}): TopicActivity {
  const counts = overrides.counts ?? [0, 5, 10, 5];
  return {
    topic: 'demo.orders.1.received',
    windowStartMs: 0,
    windowEndMs: counts.length * HOUR,
    bucketMs: HOUR,
    counts,
    total: counts.reduce((a, b) => a + b, 0),
    coveredFromMs: null,
    partitionsMeasured: 3,
    partitionsTotal: 3,
    available: true,
    note: null,
    ...overrides,
  };
}

describe('sparkline', () => {
  it('scales on the series own peak and lands on the box', () => {
    const shape = sparkline([0, 5, 10], 100, 20, 1);
    expect(shape.peak).toBe(10);
    expect(shape.peakIndex).toBe(2);
    expect(shape.flat).toBe(false);
    // Ligne de base à 19 (padding + hauteur utile), sommet à 1.
    expect(shape.points).toEqual([{ x: 1, y: 19 }, { x: 50, y: 10 }, { x: 99, y: 1 }]);
    expect(shape.line).toBe('M1 19 L50 10 L99 1');
  });

  it('closes the area on the baseline', () => {
    const shape = sparkline([1, 2], 100, 20, 1);
    expect(shape.area.endsWith('L99 19 L1 19 Z')).toBe(true);
  });

  it('draws a silent topic flat on the baseline, not through the middle', () => {
    const shape = sparkline([0, 0, 0], 100, 20, 1);
    expect(shape.flat).toBe(true);
    expect(shape.points.every(p => p.y === 19)).toBe(true);
  });

  it('survives an empty series rather than producing an unusable path', () => {
    const shape = sparkline([], 100, 20, 1);
    expect(shape.points).toHaveLength(1);
    expect(shape.line.startsWith('M')).toBe(true);
  });
});

describe('the log scale', () => {
  beforeEach(() => localStorage.clear());

  it('keeps the peak at the top and an empty bucket on the baseline', () => {
    const shape = sparkline([0, 1, 100], 100, 20, 1, 'log');
    expect(shape.points[0].y).toBe(19);   // vide : ligne de base, comme en linéaire
    expect(shape.points[2].y).toBe(1);    // pic : haut de la boîte, comme en linéaire
    // Et le pic reste la valeur *brute* : c'est lui qui est écrit à côté de la courbe.
    expect(shape.peak).toBe(100);
  });

  it('lifts what a burst would otherwise flatten onto the baseline', () => {
    // 1 face à un pic de 100 : en linéaire le point est à un centième de la hauteur, donc
    // indiscernable d'un bucket vide — ce que l'échelle log existe pour corriger.
    const linear = sparkline([0, 1, 100], 100, 20, 1);
    const log = sparkline([0, 1, 100], 100, 20, 1, 'log');
    expect(linear.points[1].y).toBeGreaterThan(18.5);
    expect(log.points[1].y).toBeLessThan(17);
  });

  it('leaves a silent topic flat, whichever scale is asked for', () => {
    expect(sparkline([0, 0, 0], 100, 20, 1, 'log').points.every(p => p.y === 19)).toBe(true);
  });

  it('round-trips the choice and says so only when it is not the assumed one', () => {
    expect(readActivityScale()).toBe('linear');
    expect(describeScale('linear')).toBe('');
    writeActivityScale('log');
    expect(readActivityScale()).toBe('log');
    expect(describeScale('log')).toBe('log scale');
  });
});

describe('what the curve does not measure', () => {
  it('counts the buckets retention emptied, the partial one included', () => {
    // Le log ne remonte qu'à 2,5 h après le début de la fenêtre : les deux premiers buckets sont
    // vides pour de bon, et le troisième n'est que partiellement couvert.
    const a = activity({ counts: [0, 0, 3, 9], coveredFromMs: 2.5 * HOUR });
    expect(unmeasuredLeadingBuckets(a)).toBe(3);
    expect(isFloor(a)).toBe(true);
  });

  it('marks nothing when the window is fully covered', () => {
    expect(unmeasuredLeadingBuckets(activity())).toBe(0);
    expect(isFloor(activity())).toBe(false);
  });

  it('treats a partition that could not be read as a floor', () => {
    expect(isFloor(activity({ partitionsMeasured: 2, partitionsTotal: 3 }))).toBe(true);
  });

  it('claims nothing at all about a series that is not available', () => {
    const missing = activity({ available: false, counts: [], total: 0 });
    expect(unmeasuredLeadingBuckets(missing)).toBe(0);
    expect(isFloor(missing)).toBe(false);
  });
});

describe('describeActivity', () => {
  it('states the total and the peak, since the scale is per row', () => {
    const text = describeActivity(activity(), 'demo.orders.1.received');
    expect(text).toContain('20 messages');
    expect(text).toContain('demo.orders.1.received');
    expect(text).toMatch(/Peak 10/);
  });

  it('says a quiet topic is quiet, and an unmeasured one unmeasured', () => {
    expect(describeActivity(activity({ counts: [0, 0], total: 0 }), 'orders'))
      .toMatch(/No message produced in orders/);
    expect(describeActivity(activity({ available: false, note: 'Connection refused' }), 'orders'))
      .toMatch(/could not be measured: Connection refused/);
    // Pas encore chargé n'est pas « rien à signaler ».
    expect(describeActivity(undefined, 'orders')).toMatch(/not measured yet/);
  });

  it('carries the server note, which is where a floor is explained', () => {
    const text = describeActivity(activity({ note: '1 of 3 partitions could not be read.' }), 'orders');
    expect(text).toContain('1 of 3 partitions could not be read.');
  });
});

describe('the window selector', () => {
  beforeEach(() => localStorage.clear());

  it('offers windows the server will accept', () => {
    for (const w of ACTIVITY_WINDOWS) {
      expect(w.buckets).toBeGreaterThanOrEqual(4);
      expect(w.buckets).toBeLessThanOrEqual(60);
      expect(w.bucketMs).toBe(Math.round(w.windowMs / w.buckets));
    }
  });

  /**
   * Sur une semaine, la question est « est-ce que la forme se répète ? », et une journée réduite à
   * quatre points ne montre aucun cycle : la nuit et la matinée tombent dans le même.
   */
  it('resolves a multi-day window finely enough to show a daily cycle', () => {
    const week = windowById('7d')!;
    const pointsPerDay = (24 * 3_600_000) / week.bucketMs;
    expect(pointsPerDay).toBeGreaterThanOrEqual(6);
  });

  it('round-trips a choice, off included', () => {
    writeActivityChoice(ACTIVITY_OFF);
    expect(readActivityChoice()).toBe(ACTIVITY_OFF);
    writeActivityChoice('1h');
    expect(readActivityChoice()).toBe('1h');
  });

  it('falls back to the default rather than switching the column off', () => {
    localStorage.setItem('kse:dashboard-activity', '3-fortnights');
    expect(readActivityChoice()).toBe(DEFAULT_ACTIVITY_CHOICE);
    expect(windowById(DEFAULT_ACTIVITY_CHOICE)).not.toBeNull();
    expect(windowById(ACTIVITY_OFF)).toBeNull();
  });
});

describe('the coverage line', () => {
  it('states the resolution, and what it could not measure', () => {
    const window = windowById('24h')!;
    expect(describeActivityScope(25, 25, window)).toMatch(/One point per 1 h over the last 24 h/);
    expect(describeActivityScope(25, 22, window)).toMatch(/3 of the 25 topics on this page/);
  });
});

describe('bucketLabel', () => {
  it('names the interval a point covers, and two points differ', () => {
    const a = activity();
    expect(bucketLabel(a, 0)).not.toBe(bucketLabel(a, 1));
    expect(bucketLabel(a, 0).length).toBeGreaterThan(0);
  });
});

describe('the peak, written rather than hovered', () => {
  it('rates the peak against the bucket it was counted in', () => {
    expect(describeRate(620, 3_600_000)).toBe('620/h');
    expect(describeRate(43, 5 * 60_000)).toBe('43/5 min');
    expect(describeRate(1_240, 6 * 3_600_000)).toBe('1.2K/6 h');
  });

  it('compacts a large count and leaves a small one alone', () => {
    expect(compact(940)).toBe('940');
    expect(compact(1_240)).toBe('1.2K');
    expect(compact(3_400_000)).toBe('3.4M');
  });
});

describe('detectSilence', () => {
  /** 24 buckets d'une heure : un régime, puis plus rien. */
  const withCounts = (counts: number[]) => activity({ counts, windowEndMs: counts.length * HOUR });

  it('reports a topic that produced and then stopped', () => {
    const silence = detectSilence(withCounts([...Array(18).fill(10), ...Array(6).fill(0)]));
    expect(silence).toEqual({ buckets: 6, atLeastMs: 6 * HOUR });
    expect(describeSilence(silence!)).toBe('silent 6 h+');
  });

  it('says nothing about a dip, which is not a stop', () => {
    // Deux heures creuses sur vingt-quatre : c'est une nuit calme, pas un topic qui s'est tu.
    expect(detectSilence(withCounts([...Array(22).fill(10), 0, 0]))).toBeNull();
  });

  it('says nothing when there was no regime to lose', () => {
    // Un seul bucket non vide, puis rien : il n'y a pas de régime dont s'écarter.
    expect(detectSilence(withCounts([5, ...Array(23).fill(0)]))).toBeNull();
    // Et une fenêtre entièrement vide n'est pas un topic qui s'est tu : c'est un topic qu'on n'a
    // pas vu produire, ce que la courbe plate dit déjà.
    expect(detectSilence(withCounts(Array(24).fill(0)))).toBeNull();
  });

  it('claims nothing about a series that is not available', () => {
    expect(detectSilence(activity({ available: false, counts: [], total: 0 }))).toBeNull();
    expect(detectSilence(undefined)).toBeNull();
  });

  it('travels into the accessible name, where it is worth more than the peak', () => {
    const text = describeActivity(withCounts([...Array(18).fill(10), ...Array(6).fill(0)]), 'orders');
    expect(text).toMatch(/Nothing produced for at least 6 h/);
  });
});

describe('from the peak to the messages', () => {
  it('links to the topic with the search primed at the bucket start', () => {
    const a = activity({ windowStartMs: new Date(2026, 5, 15, 10, 0, 0).getTime() });
    const link = bucketLink('demo.orders.1.received', a, 2);

    expect(link.startsWith('/topic/demo.orders.1.received?')).toBe(true);
    const search = link.slice(link.indexOf('?'));
    // L'autre page doit lire ce lien comme une amorce — pas comme une recherche, il n'y a pas de
    // critère à exécuter, et pas comme du bruit, sinon l'instant serait perdu à l'arrivée.
    expect(criteriaFromQuery(search)).toBeNull();
    const seeded = seedFromQuery(search);
    expect(seeded).not.toBeNull();
    expect(seeded!.startMode).toBe('TIMESTAMP');
    expect(startTimestamp(seeded!)).toBe(a.windowStartMs + 2 * HOUR);
  });

  it('escapes a topic name that needs it', () => {
    expect(bucketLink('demo/orders', activity(), 0)).toContain('/topic/demo%2Forders?');
  });
});
