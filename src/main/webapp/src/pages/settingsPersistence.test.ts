// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors

import { describe, expect, it } from 'vitest';
import {
  describePersistence, describeSaveOutcome, splitPersistence,
} from './settingsPersistence';

describe('splitPersistence', () => {
  it('keeps the settings and takes the persistence keys out of them', () => {
    const { settings, persistence } = splitPersistence({
      bootstrapServers: 'broker:9092',
      settingsPersisted: true,
      settingsStorePath: '/app/data/settings.json',
    });

    expect(settings).toEqual({ bootstrapServers: 'broker:9092' });
    expect(persistence.settingsPersisted).toBe(true);
    expect(persistence.settingsStorePath).toBe('/app/data/settings.json');
  });

  /*
   * C'est la raison d'être de la fonction : laissées dans l'état du formulaire, ces clés entrent
   * dans la comparaison qui décide s'il est modifié et dans le brouillon écrit en `localStorage`.
   * Un champ qui n'existe sur aucun formulaire ferait basculer les deux.
   */
  it('leaves nothing behind that a form comparison would see', () => {
    const { settings } = splitPersistence({
      mode: 'SSL',
      settingsPersistedNow: false,
      settingsPersistenceError: 'read-only file system',
      settingsNotStored: ['keystorePassword'],
    });

    expect(Object.keys(settings)).toEqual(['mode']);
  });

  it('does not invent keys the server did not send', () => {
    const { persistence } = splitPersistence({ mode: 'PLAIN' });
    expect(persistence).toEqual({});
  });
});

describe('describePersistence', () => {
  /* Avant la première réponse, on ne sait pas — et on ne l'affirme donc pas. */
  it('is neutral until the server has answered', () => {
    expect(describePersistence({}).tone).toBe('unknown');
  });

  it('says where settings are kept', () => {
    const notice = describePersistence({
      settingsPersisted: true,
      settingsStoreSecrets: true,
      settingsStorePath: '/app/data/settings.json',
    });

    expect(notice.tone).toBe('kept');
    expect(notice.text).toContain('/app/data/settings.json');
    expect(notice.detail).toContain('environment variable');
  });

  it('warns when nothing typed here outlives the process', () => {
    const notice = describePersistence({ settingsPersisted: false });

    expect(notice.tone).toBe('not-kept');
    expect(notice.text).toContain('this process only');
    expect(notice.detail).toContain('explorer.settings-store-path');
  });

  /*
   * La moitié qui manque est celle qui fait échouer une connexion sans rien dire : le mode et le
   * chemin du keystore reviennent, et la connexion échoue pour un mot de passe dont rien n'a
   * signalé l'abandon.
   */
  it('distinguishes credentials that are not kept from settings that are not kept', () => {
    const notice = describePersistence({
      settingsPersisted: true,
      settingsStoreSecrets: false,
      settingsStorePath: '/app/data/settings.json',
    });

    expect(notice.tone).toBe('partial');
    expect(notice.text).toContain('except the passwords');
    expect(notice.detail).toContain('entered again');
  });

  it('mentions the last save when there was one, and stays silent otherwise', () => {
    const withDate = describePersistence({
      settingsPersisted: true, settingsStoreSecrets: true,
      settingsSavedAt: '2026-08-19T10:00:00Z',
    });
    expect(withDate.detail).toContain('Last saved');

    const never = describePersistence({ settingsPersisted: true, settingsStoreSecrets: true });
    expect(never.detail).not.toContain('Last saved');
  });

  /* Une date que l'on ne sait pas lire ne devient pas « Invalid Date » à l'écran. */
  it('ignores a date it cannot read', () => {
    const notice = describePersistence({
      settingsPersisted: true, settingsStoreSecrets: true, settingsSavedAt: 'not a date',
    });
    expect(notice.detail).not.toContain('Last saved');
  });
});

describe('describeSaveOutcome', () => {
  it('says nothing when the save did what it promised', () => {
    expect(describeSaveOutcome({ settingsPersistedNow: true, settingsNotStored: [] })).toBeNull();
  });

  /*
   * Des réglages qui marchent maintenant et disparaissent au redémarrage : la surprise que tout ce
   * mécanisme existe pour supprimer, donc elle ne peut pas rester sous un « Saved! ».
   */
  it('reports a store that could not be written, with the reason', () => {
    const note = describeSaveOutcome({
      settingsPersistedNow: false,
      settingsPersistenceError: 'Read-only file system',
    });

    expect(note).toContain('Read-only file system');
    expect(note).toContain('will be gone');
  });

  it('names the credentials that were deliberately left out', () => {
    const note = describeSaveOutcome({
      settingsPersistedNow: true,
      settingsNotStored: ['keystorePassword', 'llmApiKey'],
    });

    expect(note).toContain('keystorePassword');
    expect(note).toContain('llmApiKey');
  });

  /* Un échec d'écriture prime : il vaut pour tout l'enregistrement, pas pour deux champs. */
  it('reports the failure rather than the omissions when both are true', () => {
    const note = describeSaveOutcome({
      settingsPersistedNow: false,
      settingsPersistenceError: 'No space left on device',
      settingsNotStored: ['keystorePassword'],
    });

    expect(note).toContain('No space left on device');
  });
});
