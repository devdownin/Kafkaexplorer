// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors

import { describe, it, expect } from 'vitest';
import { metricDraftLink, queueMetricDraft, readQueueDraft } from './metricFromQueue';

describe('metricDraftLink / readQueueDraft', () => {
  it('round-trips through the query string, encoding a topic name', () => {
    const link = metricDraftLink('demo.orders.2.dlt', 'demo.orders.2.validated');
    expect(readQueueDraft(link.slice(link.indexOf('?'))))
      .toEqual({ queue: 'demo.orders.2.dlt', source: 'demo.orders.2.validated' });
  });

  it('requires both halves — one alone says nothing', () => {
    expect(readQueueDraft('?fromQueue=orders.DLQ')).toBeNull();
    expect(readQueueDraft('?againstSource=orders')).toBeNull();
    expect(readQueueDraft('')).toBeNull();
  });
});

describe('queueMetricDraft', () => {
  const draft = queueMetricDraft({ queue: 'orders.DLQ', source: 'orders' });

  it('puts the queue on the left, because RATIO divides left by right', () => {
    // La part de la source qui échoue, pas l'inverse : intervertir les deux publierait le nombre
    // opposé sous le même nom.
    expect(draft.templateParams).toMatchObject({
      operation: 'RATIO', leftTopic: 'orders.DLQ', rightTopic: 'orders',
    });
  });

  it('counts from offsets and over the interval, like the suggestion panel does', () => {
    expect(draft.templateParams).toMatchObject({ countBy: 'OFFSETS', window: 'SINCE_LAST_REFRESH' });
  });

  it('proposes no threshold at all', () => {
    // Les cartes proposées ailleurs posent des seuils qui sont des multiples de quelque chose de
    // mesuré et le disent ; ici rien ne l'a été, donc un chiffre rond serait une invention.
    expect(draft.warningThreshold).toBeNull();
    expect(draft.criticalThreshold).toBeNull();
  });

  it('names the metric from the queue, in a form Prometheus accepts', () => {
    expect(draft.name).toBe('dlq_share_orders_dlq');
  });
});
