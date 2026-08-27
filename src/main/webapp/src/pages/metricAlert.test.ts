// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors

import { describe, it, expect } from 'vitest';
import type { MetricConfig } from '../api/types';
import {
  buildAlertRule, describeThresholdDirection, gradeMetric, thresholdDirection,
} from './metricAlert';

const metric = (over: Partial<MetricConfig>): MetricConfig => ({
  id: 'm1', name: 'gap', type: 'GAUGE', sql: null, description: '',
  warningThreshold: null, criticalThreshold: null, lastValue: null, lastUpdateTime: null,
  errorMessage: null, history: [], lastSummary: null, createTableSql: null,
  templateType: null, templateParams: null, executionMode: null, labelTopic: null, labelFields: null, componentHistory: null,
  ...over,
});

describe('la direction du seuil', () => {
  it('se déduit de l’ordre des deux, sans champ de plus', () => {
    expect(thresholdDirection(100, 500)).toBe('above');
    // Un ratio sain à 1.0 casse en descendant : 0.99 puis 0.95.
    expect(thresholdDirection(0.99, 0.95)).toBe('below');
    // Un seuil seul ne dit rien de la direction, donc c'est la lecture ordinaire.
    expect(thresholdDirection(5, null)).toBe('above');
    expect(thresholdDirection(null, null)).toBe('above');
  });

  it('gradue dans cette direction', () => {
    const rising = metric({ lastValue: 600, warningThreshold: 100, criticalThreshold: 500 });
    expect(gradeMetric(rising)).toBe('critical');

    const falling = metric({ lastValue: 0.94, warningThreshold: 0.99, criticalThreshold: 0.95 });
    expect(gradeMetric(falling)).toBe('critical');
    expect(gradeMetric({ ...falling, lastValue: 0.97 })).toBe('warning');
    expect(gradeMetric({ ...falling, lastValue: 1.0 })).toBe('ok');
    // La lecture descendante ne doit pas rendre « critique » un ratio parfaitement sain.
    expect(gradeMetric({ ...falling, lastValue: 1.2 })).toBe('ok');
  });

  it('énonce la direction plutôt que de la laisser tacite', () => {
    expect(describeThresholdDirection(0.99, 0.95)).toContain('downwards');
    expect(describeThresholdDirection(100, 500)).toContain('rises');
    expect(describeThresholdDirection(null, null)).toBeNull();
  });

  it('reste ce qu’il était sur une métrique sans seuil ou en erreur', () => {
    expect(gradeMetric(metric({ lastValue: 3 }))).toBe('ok');
    expect(gradeMetric(metric({ lastValue: null }))).toBe('pending');
    expect(gradeMetric(metric({ lastValue: 3, errorMessage: 'boom' }))).toBe('error');
  });
});

describe('la règle Prometheus', () => {
  it('compare la série qui EST le nombre de la carte, et la date', () => {
    const advice = buildAlertRule(metric({ lastValue: 12, criticalThreshold: 500 }));
    expect(advice.unavailable).toBeNull();
    expect(advice.rules[0].promql).toContain('explorer_metric_gauge{metric_id="m1"} >= 500');
    // Un rafraîchissement en échec garde la valeur précédente : sans ce garde, l'alerte ne
    // distingue pas une vraie condition bloquée d'une condition que plus personne ne mesure.
    expect(advice.rules[0].promql).toContain('explorer_metric_last_success_timestamp_seconds');
  });

  it('suit la direction des seuils', () => {
    const advice = buildAlertRule(metric({ lastValue: 1, warningThreshold: 0.99, criticalThreshold: 0.95 }));
    expect(advice.rules[0].promql).toContain('<= 0.95');
  });

  it('dérive la fenêtre de fraîcheur de la cadence propre à la métrique', () => {
    const slow = buildAlertRule(metric({
      criticalThreshold: 5, templateParams: { refreshIntervalMs: 900_000 },
    }));
    expect(slow.rules[0].promql).toContain('< 3600');
    const dflt = buildAlertRule(metric({ criticalThreshold: 5 }));
    expect(dflt.rules[0].promql).toContain('< 120');
    expect(dflt.rules[0].note).toContain('assumes the default refresh cadence');
  });

  it('propose le p95 en second sur une latence, sans le substituer', () => {
    const advice = buildAlertRule(metric({
      criticalThreshold: 5_000,
      templateType: 'TOPIC_TRANSIT_LATENCY',
      lastSummary: { p95LatencyMs: 4_200 },
    }));
    expect(advice.rules).toHaveLength(2);
    expect(advice.rules[0].promql).toContain('explorer_metric_gauge');
    expect(advice.rules[1].promql).toContain('explorer_metric_correlation_latency_p95_ms');
  });

  it('refuse plutôt que d’inventer une règle sur une série qui n’est pas ce nombre', () => {
    // Un counter exporte un cumul de deltas ; poser dessus un seuil copié de la carte comparerait
    // deux grandeurs différentes.
    const counter = buildAlertRule(metric({ type: 'COUNTER', criticalThreshold: 5 }));
    expect(counter.rules).toHaveLength(0);
    expect(counter.unavailable).toContain('explorer_metric_counter_total');

    const summary = buildAlertRule(metric({ type: 'SUMMARY', criticalThreshold: 5 }));
    expect(summary.unavailable).toContain('quantile');

    // Et ce qui manque est nommé, jamais une liste vide sans raison.
    expect(buildAlertRule(metric({})).unavailable).toContain('threshold');
    expect(buildAlertRule(metric({ id: '', criticalThreshold: 5 })).unavailable).toContain('Save');
  });
});
