// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors

/**
 * Ce que la page Settings peut promettre à qui la remplit.
 *
 * Le serveur a longtemps appliqué `POST /api/config` à deux singletons sans rien écrire : l'écran
 * dont c'est tout l'objet était le seul dont la saisie ne survivait pas à un redémarrage, et il
 * revenait à `application.yml` sans le dire. Le back-end la conserve maintenant ; ce module dit
 * laquelle des situations on est en train de regarder, parce qu'elles n'appellent pas la même
 * conduite : sur un déploiement où rien n'est conservé, ce qu'on tape ici vaut pour la session et
 * le vrai correctif est dans la configuration du déploiement. Le silence ferait lire le cas
 * rassurant.
 *
 * Aucune valeur ne transite ici — seulement des booléens et un chemin, comme
 * `llmApiKeyConfigured`.
 */

/** Ce que `GET /api/config` et `POST /api/config` disent de la conservation des réglages. */
export interface SettingsPersistence {
  /** Les réglages saisis ici sont conservés d'un arrêt à l'autre. */
  settingsPersisted?: boolean;
  /** Les identifiants (mots de passe SSL, secret Confluent, clé LLM) le sont aussi. */
  settingsStoreSecrets?: boolean;
  /** Le fichier qui les porte, `null` quand la conservation est désactivée. */
  settingsStorePath?: string | null;
  /** Date ISO du dernier enregistrement, `null` si rien n'a jamais été saisi. */
  settingsSavedAt?: string | null;
  /** Nombre de réglages actuellement repris du fichier. */
  settingsStoredFields?: number;
  /** L'enregistrement qui vient d'avoir lieu a-t-il été écrit. */
  settingsPersistedNow?: boolean;
  /** La raison quand il ne l'a pas été. */
  settingsPersistenceError?: string | null;
  /** Les champs volontairement laissés de côté, en clair (`keystorePassword`, …). */
  settingsNotStored?: string[];
}

/** Les clés ci-dessus, pour les retirer de l'état du formulaire — ce n'en sont pas des champs. */
export const PERSISTENCE_KEYS: (keyof SettingsPersistence)[] = [
  'settingsPersisted', 'settingsStoreSecrets', 'settingsStorePath', 'settingsSavedAt',
  'settingsStoredFields', 'settingsPersistedNow', 'settingsPersistenceError', 'settingsNotStored',
];

/**
 * Sépare la réponse du serveur en deux : les réglages, et ce qu'il dit de leur conservation.
 *
 * Sans ça, `settingsPersistedNow` entrerait dans l'état du formulaire, donc dans la comparaison
 * qui décide s'il est modifié et dans le brouillon écrit en `localStorage` — un champ qui n'existe
 * sur aucun formulaire ferait basculer les deux.
 */
export function splitPersistence<T extends object>(
  response: T & SettingsPersistence,
): { settings: T; persistence: SettingsPersistence } {
  const settings = { ...response } as T & Partial<SettingsPersistence>;
  const persistence: SettingsPersistence = {};
  for (const key of PERSISTENCE_KEYS) {
    if (key in settings) {
      (persistence as Record<string, unknown>)[key] = settings[key];
      delete settings[key];
    }
  }
  return { settings: settings as T, persistence };
}

export type PersistenceTone = 'kept' | 'partial' | 'not-kept' | 'unknown';

export interface PersistenceNotice {
  tone: PersistenceTone;
  /** La phrase principale, au présent : ce qui se passe quand on enregistre. */
  text: string;
  /** Le détail qu'on lit une fois, s'il y en a un. */
  detail?: string;
}

const dateOf = (iso?: string | null): string | null => {
  if (!iso) return null;
  const at = new Date(iso);
  return Number.isNaN(at.getTime()) ? null : at.toLocaleString();
};

/**
 * L'état de la conservation, en une phrase.
 *
 * Quatre réponses et non deux : « conservé », « conservé sauf les identifiants » — la moitié qui
 * manque est justement celle qui fait échouer une connexion sans rien dire —, « non conservé », et
 * « on ne sait pas encore », tant que la première réponse n'est pas arrivée. Le dernier cas est
 * neutre : afficher l'un des trois autres avant d'avoir demandé serait exactement l'affirmation
 * non vérifiée que la pastille de connexion a été réécrite pour ne plus faire.
 */
export function describePersistence(p: SettingsPersistence): PersistenceNotice {
  if (p.settingsPersisted === undefined) {
    return { tone: 'unknown', text: 'Checking whether these settings are kept…' };
  }
  if (!p.settingsPersisted) {
    return {
      tone: 'not-kept',
      text: 'These settings apply to this process only.',
      detail:
        'Nothing typed here is kept when the application stops — set explorer.settings-store-path '
        + 'to keep it, or configure the deployment itself (environment variables, application.yml).',
    };
  }
  const savedAt = dateOf(p.settingsSavedAt);
  const where = p.settingsStorePath ? ` in ${p.settingsStorePath}` : '';
  const since = savedAt ? ` Last saved ${savedAt}.` : '';
  if (p.settingsStoreSecrets === false) {
    return {
      tone: 'partial',
      text: `These settings are kept${where}, except the passwords and API keys.`,
      detail:
        'explorer.settings-store-secrets is off, so credentials are not written and have to be '
        + 'entered again after a restart — the rest of the form comes back.' + since,
    };
  }
  return {
    tone: 'kept',
    text: `These settings are kept${where} and restored on the next start.`,
    detail:
      'An environment variable naming the same setting still wins, so a deployment can override '
      + 'what is saved here.' + since,
  };
}

/**
 * Ce qu'un enregistrement vient d'obtenir, quand ce n'est pas ce qui était promis — `null` quand
 * il n'y a rien à signaler.
 *
 * Un magasin qui n'a pas pu être écrit laisse des réglages qui fonctionnent maintenant et
 * disparaissent au prochain démarrage : c'est précisément la surprise que tout ce mécanisme existe
 * pour supprimer, donc elle se dit là où on vient de cliquer, pas seulement dans un journal que
 * personne ne lit à cet instant.
 */
export function describeSaveOutcome(p: SettingsPersistence): string | null {
  if (p.settingsPersistedNow === false && p.settingsPersistenceError) {
    return `Applied, but not saved: ${p.settingsPersistenceError}. These settings work now and `
      + 'will be gone when the application restarts.';
  }
  const notStored = p.settingsNotStored ?? [];
  if (notStored.length > 0) {
    return `Applied and saved, except ${notStored.join(', ')} — credentials are not written to `
      + 'disk on this deployment, so they have to be entered again after a restart.';
  }
  return null;
}
