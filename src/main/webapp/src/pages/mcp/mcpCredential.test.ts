// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors

import { afterEach, describe, expect, it, vi } from 'vitest';
import {
  MCP_CREDENTIAL_KEY, authHeaders, credentialHint, forgetCredential, readCredential,
  writeCredential,
} from './mcpCredential';

afterEach(() => {
  window.sessionStorage.clear();
  vi.restoreAllMocks();
});

describe('mcpCredential', () => {
  it('retient le jeton pour la durée de l’onglet et le rend', () => {
    expect(writeCredential('  secret-token  ')).toBe(true);

    expect(readCredential()).toBe('secret-token');
    // sessionStorage et pas localStorage : un jeton bearer ne survit pas à la fermeture de
    // l'onglet sur un poste partagé.
    expect(window.sessionStorage.getItem(MCP_CREDENTIAL_KEY)).toBe('secret-token');
    expect(window.localStorage.getItem(MCP_CREDENTIAL_KEY)).toBeNull();
  });

  it('traite un jeton vide comme un oubli plutôt que comme un secret vide', () => {
    writeCredential('secret-token');

    writeCredential('   ');

    expect(readCredential()).toBeNull();
  });

  it('oublie sur demande', () => {
    writeCredential('secret-token');

    forgetCredential();

    expect(readCredential()).toBeNull();
  });

  it('survit à un stockage qui lève, au lieu de casser l’écran', () => {
    // Navigation privée, politique d'entreprise, quota plein : l'accesseur lui-même lève, et c'est
    // le déploiement le plus prudent qui perdrait la page.
    vi.spyOn(Storage.prototype, 'getItem').mockImplementation(() => {
      throw new Error('storage disabled');
    });
    vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => {
      throw new Error('storage disabled');
    });

    expect(readCredential()).toBeNull();
    // Faux, pas silencieux : l'écran dit que rien n'a été retenu plutôt que d'afficher un jeton
    // enregistré qui ne l'est pas.
    expect(writeCredential('secret-token')).toBe(false);
  });

  it('ne joint un en-tête que lorsqu’il y a un jeton', () => {
    // « Bearer  » est une authentification ratée là où l'absence d'en-tête est une requête
    // anonyme, et le serveur ne répond pas la même chose aux deux.
    expect(authHeaders(null)).toEqual({});
    expect(authHeaders('  ')).toEqual({});
    expect(authHeaders('secret-token'))
      .toEqual({ headers: { Authorization: 'Bearer secret-token' } });
  });

  it('montre de quoi le jeton a l’air sans le rendre lisible', () => {
    expect(credentialHint('abcd-efgh-1234')).toBe('…1234');
    expect(credentialHint(null)).toBeNull();
    expect(credentialHint('ab')).toBeNull();
  });
});
