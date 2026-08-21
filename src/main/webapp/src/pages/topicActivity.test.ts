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
  ACTIVITY_OFF, ACTIVITY_WINDOWS, DEFAULT_ACTIVITY_CHOICE, bucketLabel, describeActivity,
  describeActivityScope, isFloor, readActivityChoice, sparkline, unmeasuredLeadingBuckets,
  windowById, writeActivityChoice,
} from './topicActivity';

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
