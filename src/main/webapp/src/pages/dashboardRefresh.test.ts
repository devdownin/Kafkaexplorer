// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors

import { describe, it, expect, beforeEach } from 'vitest';
import {
  REFRESH_OPTIONS, REFRESH_OFF, DEFAULT_REFRESH_CHOICE, ACTIVITY_FLOOR_MS,
  readRefreshChoice, writeRefreshChoice, refreshById,
  refreshIntervalMs, activityIntervalMs, describeAge, describeRefreshStatus,
} from './dashboardRefresh';

beforeEach(() => localStorage.clear());

describe('dashboardRefresh — le choix', () => {
  it('vaut par défaut la cadence qui était codée en dur : le réglage ne change rien tout seul', () => {
    expect(readRefreshChoice()).toBe(DEFAULT_REFRESH_CHOICE);
    expect(refreshIntervalMs(DEFAULT_REFRESH_CHOICE)).toBe(5000);
  });

  it('persiste, et se relit', () => {
    writeRefreshChoice('1m');
    expect(readRefreshChoice()).toBe('1m');
    writeRefreshChoice(REFRESH_OFF);
    expect(readRefreshChoice()).toBe(REFRESH_OFF);
  });

  /*
   * Un identifiant retiré d'une version à l'autre ne doit pas se traduire par un tableau de bord
   * qui a cessé de se mettre à jour sans un mot : on retombe sur le défaut, jamais sur « off ».
   */
  it('retombe sur le défaut devant une valeur inconnue, pas sur off', () => {
    localStorage.setItem('kse:dashboard-refresh', '3h');
    expect(readRefreshChoice()).toBe(DEFAULT_REFRESH_CHOICE);
  });

  it('survit à un stockage indisponible', () => {
    const original = Object.getOwnPropertyDescriptor(window, 'localStorage');
    Object.defineProperty(window, 'localStorage', {
      configurable: true,
      get() { throw new Error('SecurityError'); },
    });
    expect(() => writeRefreshChoice('1m')).not.toThrow();
    expect(readRefreshChoice()).toBe(DEFAULT_REFRESH_CHOICE);
    if (original) Object.defineProperty(window, 'localStorage', original);
  });

  it('chaque option porte une période exploitable, et off n en porte aucune', () => {
    REFRESH_OPTIONS.forEach(o => {
      expect(refreshById(o.id)).toBe(o);
      expect(refreshIntervalMs(o.id)).toBeGreaterThan(0);
    });
    expect(refreshIntervalMs(REFRESH_OFF)).toBeNull();
  });
});

describe('dashboardRefresh — la colonne d activité suit, avec son plancher', () => {
  /*
   * Son plancher est le cache serveur : la redemander plus souvent ne ferait que relire la même
   * entrée, puis payer plusieurs fois les allers-retours au broker une fois celle-ci expirée.
   */
  it('ne descend jamais sous le cache serveur, même sur la cadence la plus rapide', () => {
    expect(activityIntervalMs('2s')).toBe(ACTIVITY_FLOOR_MS);
    expect(activityIntervalMs('5s')).toBe(ACTIVITY_FLOOR_MS);
    expect(activityIntervalMs('30s')).toBe(ACTIVITY_FLOOR_MS);
  });

  it('ralentit avec le reste de la page quand la cadence est plus lente que le plancher', () => {
    expect(activityIntervalMs('1m')).toBe(60000);
    expect(activityIntervalMs('5m')).toBe(300000);
  });

  it('s éteint avec le reste : un seul interrupteur', () => {
    expect(activityIntervalMs(REFRESH_OFF)).toBeNull();
  });
});

describe('dashboardRefresh — ce que la page annonce', () => {
  const t = 1_700_000_000_000;

  it('date ce qui est à l écran', () => {
    expect(describeAge(t, t + 1000)).toBe('just now');
    expect(describeAge(t, t + 12_000)).toBe('12 s ago');
    expect(describeAge(t, t + 4 * 60_000)).toBe('4 min ago');
    expect(describeAge(t, t + 3 * 3_600_000)).toBe('3 h ago');
  });

  /** Une horloge qui recule ne doit pas produire un âge négatif. */
  it('ne rend pas un âge négatif si l horloge recule', () => {
    expect(describeAge(t, t - 5000)).toBe('just now');
  });

  /*
   * Les deux moitiés ensemble : l'âge seul se lit comme une panne, la cadence seule ne dit pas où
   * l'on en est dans l'intervalle.
   */
  it('dit l âge et la cadence, et nomme l extinction plutôt que de se taire', () => {
    expect(describeRefreshStatus('5s', t, t + 2000))
      .toBe('Updated just now · refreshing every 5 s');
    expect(describeRefreshStatus(REFRESH_OFF, t, t + 4 * 60_000))
      .toBe('Updated 4 min ago · auto-refresh off');
  });
});
