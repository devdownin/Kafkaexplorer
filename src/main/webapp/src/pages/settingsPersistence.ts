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
  /**
   * Les réglages actuellement repris du fichier, par leur nom d'API.
   *
   * C'était un *nombre*, servi et lu par personne — et il ne pouvait pas l'être : « 5 réglages sont
   * conservés » ne se corrige pas, là où « l'adresse du courtier et le modèle sont conservés » se
   * corrige. C'est aussi la question que pose la règle de précédence, un champ conservé prenant sa
   * valeur ici plutôt que dans la configuration du déploiement à chaque démarrage.
   */
  settingsStoredFields?: string[];
  /** L'enregistrement qui vient d'avoir lieu a-t-il été écrit. */
  settingsPersistedNow?: boolean;
  /** La raison quand il ne l'a pas été. */
  settingsPersistenceError?: string | null;
  /** Les champs volontairement laissés de côté, en clair (`keystorePassword`, …). */
  settingsNotStored?: string[];
}

/** Ce que le serveur a réellement relâché sur un `DELETE /api/config/stored`. */
export interface ForgetOutcome {
  /** Les champs qui étaient conservés et ne le sont plus — vide si aucun ne l'était. */
  forgotten?: string[];
  /** La raison quand le fichier n'a pas pu être réécrit. */
  forgetError?: string | null;
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

/**
 * Le nom lisible d'un réglage conservé.
 *
 * Une table plutôt qu'une dérivation, parce que ces noms sont ceux de l'API et pas ceux de
 * l'écran : `llmSnapshotWindowTimeoutSeconds` n'a pas de découpage automatique acceptable. Un nom
 * inconnu — un champ qu'une version plus récente du serveur a ajouté — est *affiché tel quel*
 * plutôt qu'omis : une liste qui dit ce qu'elle conserve doit rester exhaustive, et un nom d'API
 * reste plus utile qu'un silence.
 */
export const STORED_FIELD_LABELS: Record<string, string> = {
  bootstrapServers: 'Bootstrap servers',
  mode: 'Security mode',
  truststorePath: 'Truststore path',
  truststorePassword: 'Truststore password',
  keystorePath: 'Keystore path',
  keystorePassword: 'Keystore password',
  keyPassword: 'Key password',
  confluentKey: 'Confluent API key',
  confluentSecret: 'Confluent API secret',
  llmProvider: 'LLM provider',
  llmApiKey: 'LLM API key',
  llmBaseUrl: 'LLM base URL',
  llmModel: 'LLM model',
  llmUseRag: 'SpectraLLM RAG',
  llmCollection: 'SpectraLLM collection',
  llmStructuredOutput: 'Structured output',
  llmOpenrouterDataCollection: 'OpenRouter data collection',
  llmOpenrouterRequireParameters: 'OpenRouter parameter routing',
  llmRequestTimeoutSeconds: 'Request timeout',
  llmMaxTokens: 'Max tokens',
  llmSnapshotWindowSize: 'Live window size',
  llmSnapshotWindowTimeoutSeconds: 'Live window timeout',
};

export const storedFieldLabel = (field: string): string =>
  STORED_FIELD_LABELS[field] ?? field;

/**
 * Ce que « oublier » fait, et surtout ce qu'il ne fait pas.
 *
 * La phrase existe parce que le geste est contre-intuitif dans un sens précis : il ne change **rien**
 * au processus en cours. La valeur conservée a été appliquée au démarrage et reste celle sur
 * laquelle l'application est connectée ; ce qui change, c'est l'endroit où le *prochain* démarrage
 * ira la lire. Laisser un 200 le sous-entendre ferait croire à un retour en arrière immédiat, et
 * repointer un cluster vivant sur un « oublier » serait une surprise pire que celle qu'on corrige.
 */
export function describeForget(fields: string[]): string {
  const what = fields.length === 1
    ? `“${storedFieldLabel(fields[0])}”`
    : `these ${fields.length} settings`;
  return `Forgetting ${what} does not change what this application is connected to right now — `
    + 'the value in force stays until it is stopped. What changes is where the next start reads '
    + 'it from: this deployment\'s own configuration (environment variables, application.yml) '
    + 'instead of the saved file.';
}

/**
 * Le résultat d'un oubli, en une phrase — `null` quand il n'y a rien à signaler.
 *
 * Trois réponses, parce qu'un fichier qu'on n'a pas pu réécrire et un champ qui n'était pas
 * conservé sont deux non-événements très différents, et qu'aucun des deux n'est « c'est fait ».
 */
export function describeForgetOutcome(outcome: ForgetOutcome): string | null {
  if (outcome.forgetError) {
    return `Still stored: ${outcome.forgetError}. These settings will be restored at the next `
      + 'start, as before.';
  }
  const forgotten = outcome.forgotten ?? [];
  if (forgotten.length === 0) {
    return 'Nothing was stored, so nothing changed.';
  }
  const names = forgotten.map(storedFieldLabel).join(', ');
  return `No longer stored: ${names}. The next start takes ${forgotten.length === 1 ? 'it' : 'them'} `
    + 'from this deployment\'s own configuration. Nothing changed in the running application.';
}
