// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors
import { describe, expect, it } from 'vitest';
import type { MetricConfig } from '../api/types';
import {
  BUSIEST_LABELLED, COMPARED_VIA, FAMILY_HUES, INFERRED_VIA, LABEL_ALL_UP_TO, familyHue, familyLegend, familyShape, flowParticleCount, healthOf,
  inferNameLinks, isSideOutput, labelledTopics, laneLayout, linkHealth, metricLinks, spiralSlot, timeHeight, topicColor,
  topicFamily, topicStage,
} from './strataFlows';

const orders = ['demo.orders.1.received', 'demo.orders.2.validated', 'demo.orders.2.dlt',
  'demo.orders.2.retry.5m', 'demo.orders.3.enriched', 'demo.orders.4.transformed'];
const supply = ['demo.sc.01.order.placed.in', 'demo.sc.01.order.placed.out', 'demo.sc.01.order.placed.audit',
  'demo.sc.02.payment.pending.in', 'demo.sc.02.payment.pending.out'];

describe('families and stages', () => {
  it('names a pipeline by its first two segments and a short name by itself', () => {
    expect(topicFamily('demo.orders.1.received')).toBe('demo.orders');
    expect(topicFamily('demo.sc.01.order.placed.in')).toBe('demo.sc');
    expect(topicFamily('demo.customers')).toBe('demo.customers');
    expect(topicFamily('orders-v1-in')).toBe('orders.v1');
    expect(topicFamily('__consumer_offsets')).toBe('consumer.offsets');
  });

  it('reads the first numeric segment after the family as the stage', () => {
    expect(topicStage('demo.orders.2.validated')).toBe(2);
    expect(topicStage('demo.sc.01.order.placed.in')).toBe(1);
    expect(topicStage('demo.payments.authorized')).toBeNull();
    // A version or a retry delay is not a stage when it is not a segment of its own.
    expect(topicStage('demo.orders.2.retry.5m')).toBe(2);
  });

  it('tells a side output from a topic that feeds the next stage', () => {
    expect(isSideOutput('demo.orders.2.dlt')).toBe(true);
    expect(isSideOutput('demo.orders.2.retry.5m')).toBe(true);
    expect(isSideOutput('demo.sc.01.order.placed.audit')).toBe(true);
    expect(isSideOutput('demo.orders.2.validated')).toBe(false);
  });
});

describe('colour', () => {
  it('gives one hue to a family and keeps it whatever else is in view', () => {
    const hue = familyHue('demo.orders');
    for (const name of orders) expect(topicColor(name, false).h).toBe(hue);
    expect(familyHue('demo.orders')).toBe(hue);
  });

  it('spreads the demo families over several hues instead of one band', () => {
    const families = ['demo.orders', 'demo.sc', 'demo.payments', 'demo.iot', 'demo.live', 'demo.metrics'];
    expect(new Set(families.map(familyHue)).size).toBeGreaterThanOrEqual(4);
  });

  it('never takes a hue the scene spends on a meaning', () => {
    const reserved = [0, 36, 167, 185, 266];
    for (const hue of FAMILY_HUES) {
      for (const meaning of reserved) {
        const distance = Math.min(Math.abs(hue - meaning), 360 - Math.abs(hue - meaning));
        expect(distance).toBeGreaterThanOrEqual(20);
      }
    }
  });

  it('steps the lightness along the stages of a pipeline', () => {
    const lightness = [1, 2, 3].map(stage => topicColor(`demo.orders.${stage}.x`, false).l);
    expect(new Set(lightness).size).toBe(3);
    for (const name of orders) {
      const { l } = topicColor(name, true);
      expect(l).toBeGreaterThan(0.5);
      expect(l).toBeLessThan(0.8);
    }
  });

  it('gives every family a socle shape as a second channel', () => {
    expect([40, 4, 6, 8]).toContain(familyShape('demo.orders'));
  });

  it('lists the families present, largest first', () => {
    expect(familyLegend([...orders, ...supply, 'demo.customers'])).toEqual([
      { family: 'demo.orders', count: 6 }, { family: 'demo.sc', count: 5 }, { family: 'demo.customers', count: 1 },
    ]);
  });
});

describe('links inferred from names', () => {
  it('follows the numbered stages and lets side outputs receive but never feed', () => {
    const links = inferNameLinks(orders).map(link => link.from + ' -> ' + link.to);
    expect(links).toContain('demo.orders.1.received -> demo.orders.2.validated');
    expect(links).toContain('demo.orders.1.received -> demo.orders.2.dlt');
    expect(links).toContain('demo.orders.2.validated -> demo.orders.3.enriched');
    expect(links).toContain('demo.orders.3.enriched -> demo.orders.4.transformed');
    expect(links.some(link => link.startsWith('demo.orders.2.dlt'))).toBe(false);
    expect(links.some(link => link.startsWith('demo.orders.2.retry'))).toBe(false);
  });

  it('joins an in to its out inside a stage, and an out to the next in', () => {
    const links = inferNameLinks(supply).map(link => link.from + ' -> ' + link.to);
    expect(links).toEqual([
      'demo.sc.01.order.placed.in -> demo.sc.01.order.placed.out',
      'demo.sc.01.order.placed.out -> demo.sc.02.payment.pending.in',
      'demo.sc.02.payment.pending.in -> demo.sc.02.payment.pending.out',
    ]);
  });

  it('says what it is, leaves a declared pair to the lineage and stays bounded', () => {
    const declared = [{ from: 'demo.orders.1.received', to: 'demo.orders.2.validated', via: 'job' }];
    const links = inferNameLinks(orders, declared);
    expect(links.every(link => link.via === INFERRED_VIA)).toBe(true);
    expect(links.some(link => link.to === 'demo.orders.2.validated' && link.from === 'demo.orders.1.received')).toBe(false);
    expect(inferNameLinks(orders, [], 2)).toHaveLength(2);
  });

  it('infers nothing from names without stages', () => {
    expect(inferNameLinks(['demo.payments.authorized', 'demo.payments.captured'])).toEqual([]);
  });
});

describe('pipeline lanes', () => {
  it('puts a family in one lane, stages left to right, side outputs beside their stage', () => {
    const slots = laneLayout([...orders, ...supply, 'demo.customers', 'demo.iot']);
    const received = slots.get('demo.orders.1.received')!;
    const validated = slots.get('demo.orders.2.validated')!;
    const dlt = slots.get('demo.orders.2.dlt')!;
    const enriched = slots.get('demo.orders.3.enriched')!;
    expect(validated.x).toBeGreaterThan(received.x);
    expect(enriched.x).toBeGreaterThan(validated.x);
    expect(dlt.x).toBe(validated.x);
    expect(dlt.z).toBeGreaterThan(validated.z);
    // Another family is another lane, below.
    expect(slots.get('demo.sc.01.order.placed.in')!.z).toBeGreaterThan(dlt.z);
    // One-topic families share the last lane.
    expect(slots.get('demo.customers')!.z).toBe(slots.get('demo.iot')!.z);
  });

  it('gives every topic its own slot', () => {
    const slots = laneLayout([...orders, ...supply]);
    const places = [...slots.values()].map(slot => slot.x + ':' + slot.z);
    expect(new Set(places).size).toBe(places.length);
  });

  it('keeps the spiral slot the scene always used', () => {
    expect(spiralSlot(0)).toEqual({ x: 0, z: 0 });
    expect(Math.hypot(spiralSlot(4).x, spiralSlot(4).z)).toBeCloseTo(6.8);
  });
});

describe('time on the vertical axis', () => {
  const start = 1_000_000;
  const bucket = 150_000;

  it('puts an instant on the ring of its bucket', () => {
    expect(timeHeight(start + bucket * 0.5, start, bucket, 12)).toEqual({ y: 0.35, inWindow: true });
    expect(timeHeight(start + bucket * 11.5, start, bucket, 12).y).toBeCloseTo(0.35 + 11 * 0.43);
  });

  it('clamps an instant outside the window and says so', () => {
    expect(timeHeight(start - 3_600_000, start, bucket, 12)).toEqual({ y: 0.35, inWindow: false });
    const late = timeHeight(start + bucket * 40, start, bucket, 12);
    expect(late.inWindow).toBe(false);
    expect(late.y).toBeCloseTo(0.35 + 11 * 0.43);
  });
});

describe('particles and link health', () => {
  it('sends no particle down an unmeasured or silent link, then one to five', () => {
    expect(flowParticleCount(null, 100)).toBe(0);
    expect(flowParticleCount(0, 100)).toBe(0);
    expect(flowParticleCount(1, 100)).toBeGreaterThanOrEqual(1);
    expect(flowParticleCount(100, 100)).toBe(5);
  });

  const metric = (over: Partial<MetricConfig>): MetricConfig => ({
    id: 'm', name: 'gap', type: 'GAUGE', sql: null, description: '', warningThreshold: 5,
    criticalThreshold: 20, lastValue: 0, lastUpdateTime: 1, errorMessage: null, history: [],
    lastSummary: null, createTableSql: null, templateType: 'TOPIC_COUNT_DELTA',
    templateParams: { leftTopic: 'a', rightTopic: 'b' }, executionMode: null, labelTopic: null,
    labelFields: null, componentHistory: null, ...over,
  });

  it('grades a link with the count-comparison metric over its two topics, either way round', () => {
    const health = linkHealth([metric({ lastValue: 25 })]);
    expect(healthOf(health, { from: 'a', to: 'b', via: '' })).toBe('critical');
    expect(healthOf(health, { from: 'b', to: 'a', via: '' })).toBe('critical');
    expect(healthOf(health, { from: 'a', to: 'c', via: '' })).toBeNull();
  });

  it('keeps the worst verdict, and says nothing for a metric that is pending or failing', () => {
    expect(healthOf(linkHealth([metric({ lastValue: 0 }), metric({ lastValue: 8 })]),
      { from: 'a', to: 'b', via: '' })).toBe('warning');
    expect(linkHealth([metric({ lastValue: null })]).size).toBe(0);
    expect(linkHealth([metric({ errorMessage: 'broker down' })]).size).toBe(0);
    expect(linkHealth([metric({ templateType: 'CONSUMER_LAG' })]).size).toBe(0);
  });
});

describe('links a count comparison declares', () => {
  const compare = (left: string, right: string, over: Partial<MetricConfig> = {}): MetricConfig => ({
    id: left + right, name: 'gap', type: 'GAUGE', sql: null, description: '', warningThreshold: 5,
    criticalThreshold: 20, lastValue: null, lastUpdateTime: null, errorMessage: null, history: [],
    lastSummary: null, createTableSql: null, templateType: 'TOPIC_COUNT_DELTA',
    templateParams: { leftTopic: left, rightTopic: right }, executionMode: null, labelTopic: null,
    labelFields: null, componentHistory: null, ...over,
  });
  const live = ['demo.live.orders', 'demo.live.shipped', 'demo.live.requests', 'demo.live.processed'];

  it('links the two topics a metric compares, upstream to downstream, even before it has a value', () => {
    expect(metricLinks([compare('demo.live.orders', 'demo.live.shipped')], live)).toEqual([
      { from: 'demo.live.orders', to: 'demo.live.shipped', via: COMPARED_VIA },
    ]);
  });

  it('draws a pair once, leaves a declared one to the lineage, and ignores what is out of view', () => {
    const metrics = [
      compare('demo.live.orders', 'demo.live.shipped'), compare('demo.live.shipped', 'demo.live.orders'),
      compare('demo.live.requests', 'demo.live.processed'), compare('demo.live.imports', 'demo.live.indexed'),
      compare('demo.live.orders', 'demo.live.orders'), compare('a', 'b', { templateType: 'CONSUMER_LAG' }),
    ];
    const declared = [{ from: 'demo.live.processed', to: 'demo.live.requests', via: 'job' }];
    expect(metricLinks(metrics, live, declared).map(link => link.from + ' -> ' + link.to))
      .toEqual(['demo.live.orders -> demo.live.shipped']);
  });
});

describe('which names stay written', () => {
  const view = (count: number, latest: (i: number) => number | null = i => i) =>
    Array.from({ length: count }, (_, i) => ({ name: `t${String(i).padStart(2, '0')}`, latest: latest(i) }));

  it('writes every name while the view is small', () => {
    expect(labelledTopics(view(LABEL_ALL_UP_TO), []).size).toBe(LABEL_ALL_UP_TO);
  });

  it('keeps the busiest and whatever is being pointed at once it is not', () => {
    const labelled = labelledTopics(view(30), ['t00', null, 't03', undefined]);
    expect(labelled.has('t29')).toBe(true);
    expect(labelled.has(`t${30 - BUSIEST_LABELLED}`)).toBe(true);
    expect(labelled.has(`t${29 - BUSIEST_LABELLED}`)).toBe(false);
    expect(labelled.has('t00')).toBe(true);
    expect(labelled.has('t03')).toBe(true);
    expect(labelled.size).toBe(BUSIEST_LABELLED + 2);
  });

  it('never ranks an unmeasured or silent topic as busy', () => {
    expect(labelledTopics(view(20, () => null), []).size).toBe(0);
    expect(labelledTopics(view(20, i => (i === 5 ? 3 : 0)), [])).toEqual(new Set(['t05']));
  });
});
