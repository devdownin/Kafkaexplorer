// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors

import { describe, it, expect } from 'vitest';
import { tablesInScope, resolveScope, resolveScopeAsTables, stripSqlNoise, toTableName } from './sqlScope';

describe('stripSqlNoise', () => {
  it('drops line and block comments', () => {
    expect(stripSqlNoise('SELECT 1 -- FROM secret\n/* FROM other */ FROM orders'))
      .not.toMatch(/secret|other/);
  });
  it('blanks string literals, escaped quotes included', () => {
    expect(stripSqlNoise("WHERE label = 'from customers' AND x = 'it''s'"))
      .not.toMatch(/customers/);
  });
});

describe('tablesInScope', () => {
  it('finds the FROM table', () => {
    expect(tablesInScope('SELECT * FROM orders')).toEqual(['orders']);
  });

  it('finds every joined table, in order and without duplicates', () => {
    const sql = 'SELECT * FROM orders o JOIN customers c ON o.cid = c.id JOIN orders x ON x.id = o.id';
    expect(tablesInScope(sql)).toEqual(['orders', 'customers']);
  });

  it('ignores a table named only inside a comment', () => {
    expect(tablesInScope('-- FROM secret_topic\nSELECT * FROM orders')).toEqual(['orders']);
  });

  it('ignores a FROM that only appears inside a string literal', () => {
    expect(tablesInScope("SELECT * FROM orders WHERE note = 'shipped from warehouse'"))
      .toEqual(['orders']);
  });

  it('does not mistake the TABLE keyword for a table name', () => {
    const sql = "SELECT window_start FROM TABLE(TUMBLE(TABLE clicks, DESCRIPTOR(ts), INTERVAL '5' MINUTE))";
    expect(tablesInScope(sql)).toEqual(['clicks']);
  });

  it('handles backticked and dotted identifiers', () => {
    expect(tablesInScope('SELECT * FROM `my.topic-name`')).toEqual(['my.topic-name']);
  });

  it('returns nothing while the FROM clause is still being typed', () => {
    expect(tablesInScope('SELECT id, name ')).toEqual([]);
  });

  it('tolerates empty and nullish input', () => {
    expect(tablesInScope('')).toEqual([]);
    expect(tablesInScope(undefined as unknown as string)).toEqual([]);
  });
});

describe('toTableName', () => {
  it('maps a Kafka topic to its Flink table name', () => {
    expect(toTableName('my.topic-name')).toBe('my_topic_name');
  });
});

describe('resolveScope', () => {
  it('matches a cited Flink table back to its Kafka topic key', () => {
    // La requête cite `auto_reg_topic`, le catalogue connaît `auto.reg.topic`.
    expect(resolveScope('SELECT * FROM auto_reg_topic', ['auto.reg.topic', 'other']))
      .toEqual(['auto.reg.topic']);
  });

  it('keeps an unknown name as typed, so it can still be looked up', () => {
    expect(resolveScope('SELECT * FROM not_in_catalog', ['orders']))
      .toEqual(['not_in_catalog']);
  });

  it('is case-insensitive on the catalog match', () => {
    expect(resolveScope('SELECT * FROM ORDERS', ['orders'])).toEqual(['orders']);
  });
});

describe('resolveScopeAsTables', () => {
  it('renvoie la forme table Flink là où resolveScope rend la clé du catalogue', () => {
    const known = ['demo.orders.1.received'];
    const sql = 'SELECT * FROM demo_orders_1_received';
    // La distinction est exactement celle qui a produit un bug : la première forme sert à aller
    // chercher un schéma par nom de topic, la seconde est la seule qu'on puisse écrire dans une
    // requête — Flink lirait les points comme des séparateurs d'identifiant.
    expect(resolveScope(sql, known)).toEqual(['demo.orders.1.received']);
    expect(resolveScopeAsTables(sql, known)).toEqual(['demo_orders_1_received']);
  });

  it('laisse passer un nom inconnu du catalogue, normalisé', () => {
    expect(resolveScopeAsTables('SELECT * FROM some.topic-name', [])).toEqual(['some_topic_name']);
  });

  it('rend une liste vide quand la requête ne cite rien', () => {
    expect(resolveScopeAsTables('SELECT 1 + 1', ['demo.orders.1.received'])).toEqual([]);
  });
});
