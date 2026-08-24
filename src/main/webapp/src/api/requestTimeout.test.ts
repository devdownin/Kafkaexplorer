// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors

import { describe, it, expect } from 'vitest';
import { installRequestTimeout, DEFAULT_REQUEST_TIMEOUT_MS } from './requestTimeout';

/*
 * Le défaut existe parce que trois écrans ont dû être corrigés un par un pour le même manque.
 * Ce qui se vérifie ici est mince mais exact : qu'il soit posé, et qu'il ne se substitue pas à
 * un budget explicite — c'est cette seconde propriété qui fait qu'on peut le poser large sans
 * raccourcir l'attente d'une inférence ou d'une analyse LLM.
 */
describe('installRequestTimeout', () => {
  it('pose le défaut sur l instance qu on lui donne', () => {
    const client = { defaults: {} as { timeout?: number } };
    installRequestTimeout(client);
    expect(client.defaults.timeout).toBe(DEFAULT_REQUEST_TIMEOUT_MS);
  });

  it('accepte une valeur explicite, ce qui le rend vérifiable sans toucher au singleton', () => {
    const client = { defaults: {} as { timeout?: number } };
    installRequestTimeout(client, 1234);
    expect(client.defaults.timeout).toBe(1234);
  });

  /*
   * Un filet, pas un budget : il doit être assez large pour ne jamais couper une requête
   * légitime, et fini pour qu'une requête morte finisse par l'être.
   */
  it('est un filet fini et large', () => {
    expect(DEFAULT_REQUEST_TIMEOUT_MS).toBeGreaterThanOrEqual(30_000);
    expect(Number.isFinite(DEFAULT_REQUEST_TIMEOUT_MS)).toBe(true);
  });
});
