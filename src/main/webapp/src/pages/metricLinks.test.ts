// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors
import { describe, expect, it } from 'vitest';
import { forecastBreachRule, metricTopics } from './metricLinks';

describe('metricTopics', () => {
  it('names the topics a template reads, then the label topic, each once', () => {
    expect(metricTopics({ templateParams: { sourceTopic: 'a', targetTopic: 'b' }, labelTopic: 'a' })).toEqual(['a', 'b']);
    expect(metricTopics({ templateParams: { leftTopic: ' x ', rightTopic: '' }, labelTopic: null })).toEqual(['x']);
    expect(metricTopics({ templateParams: null, labelTopic: null })).toEqual([]);
  });
});

describe('forecastBreachRule', () => {
  it('alerts on the ETA series and requires the metric to still be collected', () => {
    const rule = forecastBreachRule({ id: 'm-1', name: 'latency' }, 'critical', 'expected');
    expect(rule).toContain('explorer_forecast_threshold_eta_seconds{metric_id="m-1",level="critical",likelihood="expected"} < 1800');
    expect(rule).toContain('explorer_metric_last_success_timestamp_seconds{metric_id="m-1"}');
    expect(rule.split('\n')[0]).toBe('# latency: critical threshold expected within 30 min (forecast)');
  });
});
