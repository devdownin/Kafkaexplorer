// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors
import { describe, expect, it } from 'vitest';
import type { MetricSuggestion } from '../api/types';
import type { FlowHit } from './streamFlowLogic';
import { flowMetricOptions, hopLatencyId, hopMetricOptions, payloadLabelRisk, withPayloadLabels, metricName, neighboursOf, withStaticLabels } from './flowMetrics';

const hit = (topic: string): FlowHit => ({ topic, occurrences: 1, firstTimestamp: 1, lastTimestamp: 1, firstPartition: 0,
  firstOffset: 0, firstKey: null, preview: null, latencyFromPreviousMs: 10 });
const hits = ['orders', 'payments', 'shipped'].map(hit);
const latency = (from: string, to: string, alreadyConfigured = false) => ({
  id: hopLatencyId(from, to), alreadyConfigured, existingMetricName: alreadyConfigured ? 'old' : null,
  thresholdBasis: 'Warning at 2× the measured hop', metric: { id: 'server', name: `latency_${from}_${to}`,
    templateType: 'TOPIC_TRANSIT_LATENCY', templateParams: { sourceSql: 'a', targetSql: 'b' } },
}) as unknown as MetricSuggestion;

describe('flowMetrics', () => {
  it('finds the hop into and out of a topic, in trace order', () => {
    expect(neighboursOf(hits, 'payments')).toEqual({ topic: 'payments', previous: 'orders', next: 'shipped' });
    expect(neighboursOf(hits, 'orders')).toEqual({ topic: 'orders', previous: null, next: 'payments' });
  });

  it('proposes both hops, ticks the latency into the topic, and labels each metric with what it measures', () => {
    const options = flowMetricOptions(neighboursOf(hits, 'payments'),
      [latency('orders', 'payments'), latency('payments', 'shipped')], null, ['billing'], null);
    expect(options.map(o => o.id)).toEqual(['latency:orders>payments', 'loss:orders>payments',
      'latency:payments>shipped', 'loss:payments>shipped', 'lag:payments', 'produced:payments', 'silence:payments']);
    expect(options.filter(o => o.defaultOn).map(o => o.id)).toEqual(['latency:orders>payments']);
    expect(options[0].labels).toEqual({ source_topic: 'orders', target_topic: 'payments' });
    expect(options[0].draft.id).toBeUndefined();
    expect(options[4].labels).toEqual({ topic: 'payments', consumer_group: 'billing' });
    expect(options[1].draft.templateParams).toMatchObject({ operation: 'PERCENT_GAP', countBy: 'OFFSETS', window: 'SINCE_LAST_REFRESH' });
  });

  it('says why a metric cannot be created, and does not tick one that already exists', () => {
    const options = flowMetricOptions(neighboursOf(hits, 'payments'), [latency('orders', 'payments', true)], null, [], null);
    expect(options[0].existing).toBe('old');
    expect(options[0].defaultOn).toBe(false);
    expect(options[2].unavailable).toMatch(/proposes no latency/);
    expect(options[4].unavailable).toBe('No consumer group reads this topic.');
  });

  it('writes the ticked labels as fixed labels, and none when all are unticked', () => {
    const draft = { templateParams: { topic: 't' } };
    expect(withStaticLabels(draft, { topic: 't' }).templateParams).toEqual({ topic: 't', staticLabels: { topic: 't' } });
    expect(withStaticLabels(draft, {}).templateParams).toEqual({ topic: 't' });
    expect(metricName('hop_loss', 'demo.orders-v1', 'x')).toBe('hop_loss_demo_orders_v1_x');
  });
});

describe('flowMetricOptions without a latency proposal', () => {
  it('ticks the loss on the first hop, once the proposals are read', () => {
    const n = neighboursOf(hits, 'payments');
    expect(flowMetricOptions(n, [], null, [], null).filter(o => o.defaultOn).map(o => o.id)).toEqual(['loss:orders>payments']);
    expect(flowMetricOptions(n, null, null, [], null).filter(o => o.defaultOn)).toEqual([]);
  });
});

describe('hopMetricOptions', () => {
  it('proposes the latency and the loss of one hop, latency ticked, loss ticked instead when no latency is proposed', () => {
    const withLatency = hopMetricOptions('orders', 'payments', [latency('orders', 'payments')], null);
    expect(withLatency.map(o => [o.id, o.defaultOn])).toEqual([['latency:orders>payments', true], ['loss:orders>payments', false]]);
    expect(hopMetricOptions('orders', 'payments', [], null).map(o => o.defaultOn)).toEqual([false, true]);
    expect(withLatency[1].labels).toEqual({ source_topic: 'orders', target_topic: 'payments' });
  });
});

describe('payload labels', () => {
  it('rules out what visibly changes with every record, and keeps a category', () => {
    expect(payloadLabelRisk('region', 'eu-west')).toBeNull();
    expect(payloadLabelRisk('customer.tier', 'gold')).toBeNull();
    for (const [field, value] of [['orderId', 'ORD-1'], ['order_id', 'x'], ['ref', '3f2b8c1e-1d2a-4b3c-9d8e-0a1b2c3d4e5f'],
      ['at', '2026-11-21T06:00:00Z'], ['amount', '1250.40'], ['note', 'x'.repeat(41)], ['status', '']])
      expect(payloadLabelRisk(field, value), field).not.toBeNull();
  });
  it('points the labels at the topic they are read from, and leaves a draft alone without any', () => {
    expect(withPayloadLabels({ labelTopic: null }, 'orders', ['region'])).toEqual({ labelTopic: 'orders', labelFields: ['region'] });
    expect(withPayloadLabels({ labelTopic: 'a' }, 'orders', [])).toEqual({ labelTopic: 'a' });
  });
});
