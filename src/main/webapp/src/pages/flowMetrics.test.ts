// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors
import { describe, expect, it } from 'vitest';
import type { MetricSuggestion } from '../api/types';
import type { FlowHit } from './streamFlowLogic';
import { flowMetricOptions, hopLatencyId, metricName, neighboursOf, withStaticLabels } from './flowMetrics';

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
      'latency:payments>shipped', 'loss:payments>shipped', 'lag:payments', 'produced:payments']);
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
