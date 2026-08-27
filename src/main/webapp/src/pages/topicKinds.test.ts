// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors

/*
 * Les deux règles de nommage, désormais écrites une fois. Ce que ces cas défendent, c'est surtout
 * ce que les règles doivent *refuser* : une reconnaissance trop large étiquette un topic ordinaire
 * comme une file d'attente morte, et une reconnaissance trop étroite en laisse passer une vraie
 * sans rien dire — le second défaut étant celui qui a vécu ici, `.dlq` n'étant reconnu nulle part.
 */

import { describe, it, expect } from 'vitest';
import { isRetryTopic, isDeadLetterTopic, deadLetterLabel } from './topicKinds';

describe('isRetryTopic', () => {
  it('finds the word wherever it sits in the name, not only at the end', () => {
    expect(isRetryTopic('demo.orders.2.retry.5m')).toBe(true);
    expect(isRetryTopic('retry-orders')).toBe(true);
    expect(isRetryTopic('orders.retry')).toBe(true);
  });

  it('ignores case, a convention nobody applies uniformly', () => {
    expect(isRetryTopic('demo.RETRY-payments')).toBe(true);
    expect(isRetryTopic('demo.Orders.Retry')).toBe(true);
  });

  it('says no to a topic that carries no such word', () => {
    expect(isRetryTopic('demo.orders.1.received')).toBe(false);
    expect(isRetryTopic('demo.customers')).toBe(false);
  });
});

describe('isDeadLetterTopic', () => {
  /*
   * Les deux orthographes : `.DLT` est celle de DeadLetterPublishingRecoverer (Spring Kafka),
   * `.DLQ` celle de Spring Cloud Stream et du reste de l'écosystème. N'en connaître qu'une laisse
   * passer l'autre moitié en silence, ce qui est le défaut que ce module corrige.
   */
  it('recognises both spellings', () => {
    expect(isDeadLetterTopic('demo.orders.2.dlt')).toBe(true);
    expect(isDeadLetterTopic('demo.payments.dlq')).toBe(true);
  });

  it('recognises the three separators the ecosystem actually uses', () => {
    expect(isDeadLetterTopic('orders.dlq')).toBe(true);
    expect(isDeadLetterTopic('orders-dlq')).toBe(true);
    expect(isDeadLetterTopic('orders_dlq')).toBe(true);
  });

  it('ignores case', () => {
    expect(isDeadLetterTopic('demo.orders.2.DLT')).toBe(true);
    expect(isDeadLetterTopic('demo.Payments.Dlq')).toBe(true);
  });

  /*
   * Elle reste un *suffixe*, ce qu'elle a toujours été. Reconnaître le marqueur n'importe où dans
   * le nom étiquetterait comme morte une file qui ne l'est pas, et un badge « file morte » sur un
   * topic vivant est une affirmation fausse, pas une approximation.
   */
  it('stays a suffix, so a marker sitting elsewhere in the name does not match', () => {
    expect(isDeadLetterTopic('demo.dlq.reprocessor')).toBe(false);
    expect(isDeadLetterTopic('demo.orders.dlt.replay')).toBe(false);
  });

  it('requires the separator, so a word merely ending in those letters does not match', () => {
    expect(isDeadLetterTopic('demo.ordersdlq')).toBe(false);
    expect(isDeadLetterTopic('demo.orders.1.received')).toBe(false);
  });
});

describe('deadLetterLabel', () => {
  /*
   * Le badge disait `DLT` pour tout le monde. Une fois les deux orthographes reconnues, ça devient
   * une contre-vérité sur la moitié des cas : le badge nomme le suffixe mesuré, il n'en invente pas.
   */
  it('names the spelling the topic actually carries', () => {
    expect(deadLetterLabel('demo.orders.2.dlt')).toBe('DLT');
    expect(deadLetterLabel('demo.payments.dlq')).toBe('DLQ');
  });

  it('normalises the case, the badge being a label and not a quotation', () => {
    expect(deadLetterLabel('demo.orders.2.DLT')).toBe('DLT');
    expect(deadLetterLabel('demo.payments.Dlq')).toBe('DLQ');
  });

  it('answers null rather than a label for a topic that is not one', () => {
    expect(deadLetterLabel('demo.orders.1.received')).toBeNull();
  });
});
