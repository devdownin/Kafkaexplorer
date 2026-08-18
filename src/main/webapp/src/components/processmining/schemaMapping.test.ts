// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors

import { describe, expect, it } from 'vitest';
import { cleanCorrections, mappingProblems, normalizePath, pathProblem } from './schemaMapping';

describe('normalizePath', () => {
  it('reproduit la normalisation du back', () => {
    // Les cas de la javadoc de PayloadDigestService.normalizePath.
    expect(normalizePath("$.order['items'][0].sku")).toBe('order.items[].sku');
    expect(normalizePath('$.orderId')).toBe('orderId');
    expect(normalizePath('orderId')).toBe('orderId');
    expect(normalizePath('$["a"].b')).toBe('a.b');
    expect(normalizePath('$.items[*].id')).toBe('items[].id');
  });
});

describe('pathProblem', () => {
  it('accepte ce que le back accepte', () => {
    // La validation ne doit pas être plus stricte que le serveur : une règle de plus ici, et un
    // chemin qui marche est refusé — après quoi on apprend à contourner la validation.
    for (const path of ['$.orderId', 'orderId', '$.order.items[0].sku', "$['a b'].c", '$.a[*].b']) {
      expect(pathProblem(path), path).toBeNull();
    }
  });

  it('laisse passer le vide — « pas de mapping » est un choix', () => {
    expect(pathProblem('')).toBeNull();
    expect(pathProblem('   ')).toBeNull();
  });

  it('refuse les crochets déséquilibrés', () => {
    expect(pathProblem('$.items[0.sku')).toMatch(/bracket/i);
    expect(pathProblem('$.items0].sku')).toMatch(/bracket/i);
  });

  it('refuse un chemin qui ne désigne que la racine', () => {
    // Normalisé, il ne reste rien : le mapping ne pourrait matcher aucune feuille, et le seul
    // symptôme serait une corrélation vide à l'autre bout du pipeline.
    expect(pathProblem('$')).toMatch(/root/i);
    expect(pathProblem('$.')).toMatch(/root/i);
    expect(pathProblem('.')).toMatch(/root/i);
  });

  it('refuse un espace au milieu du chemin', () => {
    expect(pathProblem('$.order id')).toMatch(/space/i);
  });
});

describe('mappingProblems', () => {
  it('rend chaque chemin fautif sous son rôle et son topic', () => {
    const problems = mappingProblems({
      correlationId: { 'orders': '$.id', 'payments': '$.ref[0' },
      timestamp: { 'orders': '$' },
    });
    expect(problems.correlationId).toEqual({ payments: expect.stringMatching(/bracket/i) });
    expect(problems.timestamp).toEqual({ orders: expect.stringMatching(/root/i) });
  });

  it('ne rapporte rien quand tout est valide', () => {
    expect(mappingProblems({ correlationId: { orders: '$.id', payments: '' } })).toEqual({});
  });
});

describe('cleanCorrections', () => {
  it('retire les chemins vides et les rôles qui n’en gardent aucun', () => {
    expect(cleanCorrections({
      correlationId: { orders: '$.id', payments: '  ' },
      amount: { orders: '' },
    })).toEqual({ correlationId: { orders: '$.id' } });
  });
});
