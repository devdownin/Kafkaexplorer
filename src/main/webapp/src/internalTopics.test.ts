// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors

import { describe, it, expect } from 'vitest';
import { isInternalTopic, isInternalTable } from './internalTopics';

/*
 * Ces prédicats existent parce que quatre points d'appel répondaient à la même question avec un
 * littéral `'internal.'`. C'était juste tant que le préfixe était une constante ; il est devenu
 * un réglage, et un déploiement qui en pose un se voyait reproposer ses propres topics.
 */
describe('internalTopics', () => {
  it('sans préfixe, répond exactement comme les littéraux qu il remplace', () => {
    expect(isInternalTopic('internal.audit.history')).toBe(true);
    expect(isInternalTopic('internal.demo.seeded')).toBe(true);
    expect(isInternalTopic('demo.orders.1.received')).toBe(false);
    expect(isInternalTable('internal_audit_history')).toBe(true);
    expect(isInternalTable('demo_orders_1_received')).toBe(false);
  });

  it('suit le préfixe configuré', () => {
    expect(isInternalTopic('acme.internal.audit.history', 'acme.')).toBe(true);
    expect(isInternalTable('acme_internal_audit_history', 'acme.')).toBe(true);
  });

  /*
   * Sans le préfixe, ce n'est plus un topic de cette instance : c'est peut-être celui d'une autre
   * qui partage le cluster, ce que le préfixe sert précisément à séparer. Même règle que
   * `ExplorerConfig.isInternalTopic` côté serveur.
   */
  it('sous un préfixe, un nom non préfixé n est plus des nôtres', () => {
    expect(isInternalTopic('internal.audit.history', 'acme.')).toBe(false);
    expect(isInternalTable('internal_audit_history', 'acme.')).toBe(false);
  });

  /*
   * Le préfixe passe par `toTableName` pour la forme table : concaténé tel quel il donnerait
   * `acme.internal_`, qui ne correspond à aucune table Flink.
   */
  it('normalise le préfixe pour la forme table, points compris', () => {
    expect(isInternalTable('acme_corp_internal_metrics_config', 'acme.corp.')).toBe(true);
    expect(isInternalTable('acme.corp.internal_metrics_config', 'acme.corp.')).toBe(false);
  });

  /** La comparaison des tables est insensible à la casse, comme le littéral qu'elle remplace. */
  it('ignore la casse sur les noms de tables', () => {
    expect(isInternalTable('INTERNAL_AUDIT_HISTORY')).toBe(true);
  });

  /** Un topic qui ne fait que *contenir* le mot n'est pas des nôtres. */
  it('ancre au début du nom', () => {
    expect(isInternalTopic('acme.internal.x')).toBe(false);
    expect(isInternalTopic('my.internal.stuff')).toBe(false);
  });
});
