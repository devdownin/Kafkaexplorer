// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors
import type { MetricConfig } from '../api/types';

/**
 * The Kafka topics a metric reads, by name — where an operator goes once a threshold is predicted.
 * A template names them in its parameters; a raw SQL metric names none (its table is the planner's),
 * except the topic its labels are read from.
 */
export function metricTopics(metric: Pick<MetricConfig, 'templateParams' | 'labelTopic'>): string[] {
  const params = metric.templateParams ?? {};
  const named = ['sourceTopic', 'leftTopic', 'topic', 'targetTopic', 'rightTopic']
    .map(key => params[key])
    .filter((value): value is string => typeof value === 'string' && value.trim() !== '')
    .map(value => value.trim());
  return [...new Set([...named, ...(metric.labelTopic?.trim() ? [metric.labelTopic.trim()] : [])])];
}

/**
 * The Alertmanager rule for a predicted threshold, on `explorer_forecast_threshold_eta_seconds`.
 *
 * The page sends no alert; this is what lets one be raised where alerts belong. The series floors at
 * zero and a forecast that stops being refreshed keeps saying zero, so the rule also requires the
 * metric to still be collected — the same pairing the card's own alert rule makes.
 */
export function forecastBreachRule(metric: Pick<MetricConfig, 'id' | 'name'>, level: 'warning' | 'critical',
  likelihood: 'expected' | 'possible', withinSeconds = 1800): string {
  const id = metric.id.replace(/["\\]/g, '\\$&');
  return [
    `# ${metric.name || metric.id}: ${level} threshold ${likelihood} within ${Math.round(withinSeconds / 60)} min (forecast)`,
    '# Paired with collection freshness: a forecast that stopped being refreshed would otherwise keep firing.',
    `explorer_forecast_threshold_eta_seconds{metric_id="${id}",level="${level}",likelihood="${likelihood}"} < ${withinSeconds}`,
    `  and on(metric_id) (time() - explorer_metric_last_success_timestamp_seconds{metric_id="${id}"}) < 600`,
  ].join('\n');
}
