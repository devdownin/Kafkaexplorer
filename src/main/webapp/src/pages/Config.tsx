// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors

import React, { useState, useEffect, useMemo, useRef, useCallback } from 'react';
import axios from 'axios';
import {
  PageHeader, Button, CardSkeleton, Checkbox, Combobox, ErrorPanel, Field, Input, NumberInput,
  PasswordInput, RadioCards, Switch, useConfirm, useUnsavedGuard,
} from '../components/ui';
import { clearDraft, readDraft, useDraftConflict, writeDraft } from '../draftStore';
import { draftableOnly, mergeDraft } from './configDraft';
import {
  describeForget, describeForgetOutcome, describePersistence, describeSaveOutcome,
  splitPersistence, storedFieldLabel,
  type ForgetOutcome, type SettingsPersistence,
} from './settingsPersistence';
import { describeDataPolicy, type LlmPolicyFacts } from './llmPolicy';
import { describeTestTimeout, testTimeoutMs } from './llmTimeout';
import type { LlmModelShortlist, LlmTestResponse } from '../api/types';
import {
  describeKeyStatus, describeModelCheck, describeModelIdentity, hasModelWarning,
} from './llmModelCheck';
import { describeApiError, type QueryErrorInfo } from './queryError';
import {
  PROJECTION_NOTE, describeOption, describeShortlist, optionSlugs, validateModelSlug,
} from './llmModelPicker';

interface ClusterConfig {
  bootstrapServers: string;
  mode: string;
  truststorePath?: string;
  truststorePassword?: string;
  keystorePath?: string;
  keystorePassword?: string;
  keyPassword?: string;
  confluentKey?: string;
  confluentSecret?: string;
  /*
   * Un mot de passe n'est jamais renvoyé — le serveur dit seulement s'il y en a un, comme pour
   * `llmApiKeyConfigured`. Sans ça, un champ vide ne distingue pas « aucun mot de passe » de
   * « mot de passe en vigueur, simplement pas affiché », et depuis que les réglages survivent à
   * un redémarrage c'est la seconde qui est la situation courante.
   */
  truststorePasswordConfigured?: boolean;
  keystorePasswordConfigured?: boolean;
  keyPasswordConfigured?: boolean;
  confluentSecretConfigured?: boolean;
  isConnected?: boolean;
  /**
   * Pourquoi le courtier n'a pas répondu, quand il n'a pas répondu.
   *
   * `isConnected` seul confond un courtier arrêté, une adresse qui ne pointe sur rien et un client
   * que le cluster refuse — trois problèmes qui envoient à trois endroits différents, sur l'écran
   * précisément fait pour corriger l'adresse. `null` quand la connexion est établie, et absent
   * d'une réponse plus ancienne.
   */
  connectionError?: string | null;
  llmProvider: 'ANTHROPIC' | 'OPENAI_COMPATIBLE' | 'OLLAMA' | 'OPENROUTER' | 'SPECTRA';
  llmProviderLabel?: string;
  llmApiKey?: string;
  llmApiKeyConfigured?: boolean;
  llmApiKeyRequired?: boolean;
  llmBaseUrl: string;
  llmModel: string;
  llmUseRag?: boolean;
  llmCollection?: string;
  llmRequestTimeoutSeconds?: number;
  llmMaxTokens: number;
  llmSnapshotWindowSize: number;
  llmSnapshotWindowTimeoutSeconds: number;
  llmLocalDeployment?: boolean;
  /**
   * Ce qui empêche ce déploiement d'appeler un modèle, avant même d'essayer : clé absente, adresse
   * absente, adresse qui n'en est pas une. Servi par le serveur et rendu par Process Mining depuis
   * toujours — mais c'est ici que se trouve le réglage à changer, et ici que rien ne le disait.
   */
  llmConfigurationProblem?: string | null;
  /**
   * Ce que `claude.structured-output` produit réellement pour le fournisseur en vigueur. `AUTO`
   * décline pour un point d'accès inconnu, et le réglage seul ne dit donc pas si un schéma part
   * avec la requête — ce qui est la question qu'on se pose. Servi depuis longtemps, lu par
   * personne.
   */
  llmStructuredOutputActive?: boolean;
  /**
   * Le contrat de décodage demandé au point d'accès. `AUTO` ne l'active que là où le support est
   * connu (Anthropic, Ollama, OpenRouter) : une passerelle inconnue peut répondre 400 à un
   * `response_format` qu'elle n'implémente pas, et transformer un déploiement qui marche en
   * déploiement qui échoue pour gagner une garantie dont il n'a peut-être pas besoin est le
   * mauvais défaut. `ON` est l'option de qui sait mieux. Le serveur l'acceptait et le validait
   * depuis toujours ; il n'y avait simplement aucun contrôle.
   */
  llmStructuredOutput?: 'AUTO' | 'ON' | 'OFF';
  /**
   * Réglages de routage OpenRouter. `llmOpenrouterDataCollection` est le seul endroit de cette
   * application où une affirmation de confidentialité cesse d'être un avertissement : OpenRouter
   * l'impose au routage, donc le bandeau peut l'énoncer. Le formulaire les renvoyait tels quels
   * sans jamais les montrer.
   */
  llmOpenrouterDataCollection?: 'ALLOW' | 'DENY';
  llmOpenrouterRequireParameters?: boolean;
  /** Vrai quand le routage a été restreint aux fournisseurs qui ne conservent rien. */
  llmDataRetentionRefused?: boolean;
}

const MODES = [
  { value: 'PLAIN', label: 'PLAIN', description: 'No authentication' },
  { value: 'SSL', label: 'SSL / mTLS', description: 'Certificate-based auth' },
  { value: 'CONFLUENT_CLOUD', label: 'Confluent Cloud', description: 'SASL/SSL with API keys' },
];

/** Champs pouvant porter une erreur de validation. */
type ValidatedField =
  | 'bootstrapServers' | 'truststorePath' | 'keystorePath'
  | 'confluentKey' | 'confluentSecret'
  | 'llmModel' | 'llmBaseUrl' | 'llmApiKey'
  | 'llmMaxTokens' | 'llmSnapshotWindowSize' | 'llmSnapshotWindowTimeoutSeconds';

type FieldErrors = Partial<Record<ValidatedField, string>>;

/** Ids DOM stables : la validation focalise le premier champ fautif. */
const fieldIds: Record<ValidatedField, string> = {
  bootstrapServers: 'cfg-bootstrap-servers',
  truststorePath: 'cfg-truststore-path',
  keystorePath: 'cfg-keystore-path',
  confluentKey: 'cfg-confluent-key',
  confluentSecret: 'cfg-confluent-secret',
  llmModel: 'cfg-llm-model',
  llmBaseUrl: 'cfg-llm-base-url',
  llmApiKey: 'cfg-llm-api-key',
  llmMaxTokens: 'cfg-llm-max-tokens',
  llmSnapshotWindowSize: 'cfg-llm-window-size',
  llmSnapshotWindowTimeoutSeconds: 'cfg-llm-window-timeout',
};

/** Ordre d'apparition à l'écran — détermine quel champ reçoit le focus en cas d'erreurs. */
const FIELD_ORDER: ValidatedField[] = [
  'bootstrapServers', 'truststorePath', 'keystorePath', 'confluentKey', 'confluentSecret',
  'llmModel', 'llmBaseUrl', 'llmApiKey',
  'llmMaxTokens', 'llmSnapshotWindowSize', 'llmSnapshotWindowTimeoutSeconds',
];

const STRUCTURED_OUTPUT = [
  { value: 'AUTO', label: 'Auto', description: 'On where support is known' },
  { value: 'ON', label: 'On', description: 'Always send a JSON schema' },
  { value: 'OFF', label: 'Off', description: 'Parse the answer leniently' },
] as const;

const DATA_COLLECTION = [
  { value: 'DENY', label: 'Refuse retention', description: 'Route only to providers that keep nothing' },
  { value: 'ALLOW', label: 'Allow any provider', description: 'Including those that retain or train' },
] as const;

const LLM_PROVIDERS = [
  { value: 'ANTHROPIC', label: 'Anthropic', description: 'Hosted Claude models' },
  { value: 'OPENROUTER', label: 'OpenRouter', description: 'One key, many hosted models (vendor/model)' },
  { value: 'OPENAI_COMPATIBLE', label: 'OpenAI-compatible', description: 'vLLM, LM Studio or compatible gateways' },
  { value: 'OLLAMA', label: 'Ollama', description: 'Lightweight local open-source models' },
  { value: 'SPECTRA', label: 'SpectraLLM', description: 'Local SpectraLLM instance (RAG + fine-tuned models)' },
] as const;

/**
 * Les fournisseurs pour lesquels une clé est indispensable, et non simplement acceptée.
 *
 * Miroir de `ClaudeConfig.isApiKeyRequired()`. Le serveur envoie bien `llmApiKeyRequired`, mais il
 * décrit le fournisseur *en vigueur*, pas celui qu'on est en train de choisir dans le formulaire :
 * la question posée ici porte sur la valeur non encore enregistrée.
 */
const API_KEY_REQUIRED: ReadonlySet<ClusterConfig['llmProvider']> = new Set(['ANTHROPIC', 'OPENROUTER']);

/**
 * Base URL et modèle par défaut de chaque fournisseur, **servis par `GET /api/config`**.
 *
 * Ils étaient écrits ici : une table recopiant `ClaudeConfig.defaultBaseUrl` et, deux fois,
 * `openai/gpt-4o-mini` en dur — dans l'état initial et dans le repli au changement de fournisseur.
 * C'est le motif de miroir que ce dépôt retire partout ailleurs, et il mord le jour où un défaut
 * livré bouge : le formulaire propose un modèle pendant que le moteur en fait tourner un autre.
 */
type ProviderDefaults = Partial<Record<ClusterConfig['llmProvider'], { baseUrl: string; model: string }>>;

/**
 * Une base URL qu'aucun opérateur n'a choisie : c'est le défaut d'un autre fournisseur.
 *
 * Tant que le serveur n'a pas répondu, la réponse est « non » plutôt qu'un jugement porté sur une
 * table devinée : remplacer une URL saisie à la main serait pire que de la laisser en place.
 */
const isProviderDefaultUrl = (defaults: ProviderDefaults, url?: string): boolean =>
  !url || Object.values(defaults).some(known => known.baseUrl !== '' && known.baseUrl === url);

/**
 * Ce qu'on peut dire d'un mot de passe qu'on ne montre pas.
 *
 * Un champ vide ne distingue pas « aucun mot de passe » de « mot de passe en vigueur, simplement
 * jamais renvoyé » — et depuis que les réglages survivent à un redémarrage, la seconde est la
 * situation courante : on revient sur la page et tout paraît vide. Le serveur envoie le booléen,
 * jamais la valeur, comme pour `llmApiKeyConfigured`.
 *
 * Rien n'est dit dès qu'on tape : ce qui est à l'écran est alors ce qui partira, et une phrase
 * décrivant l'état précédent ne ferait que semer le doute.
 */
const secretHint = (
  configured?: boolean, typed?: string, noun = 'password',
): string | undefined => {
  if (!configured) return undefined;
  // Jamais touché : le champ n'est pas envoyé du tout, donc le mot de passe en vigueur reste.
  if (typed === undefined) return 'One is set. It is not shown — leave this field alone to keep it.';
  // Tapé puis effacé : la chaîne vide part et efface le mot de passe. Dire « laissez vide pour le
  // conserver » ici décrirait exactement l'inverse de ce que ferait l'enregistrement.
  if (typed === '') return `Saving now clears the ${noun} that is set.`;
  return undefined;
};

/** Clé du brouillon (voir `configDraft.ts` — les secrets n'y entrent pas). */
const DRAFT_KEY = 'config';
const DRAFT_KEYS = [DRAFT_KEY];

const Config: React.FC = () => {
  const confirm = useConfirm();
  const [config, setConfig] = useState<ClusterConfig>({
    bootstrapServers: 'localhost:9092',
    mode: 'PLAIN',
    /*
     * Le formulaire est derrière une garde `loading`, donc rien de tout ceci ne s'affiche avant
     * que `GET /api/config` ait répondu — et la réponse porte le fournisseur, l'URL et le modèle
     * réellement en vigueur. Une base URL et un modèle vides sont donc la valeur juste : ils
     * disent « le serveur ne l'a pas encore dit », là où une copie des défauts livrés était une
     * affirmation qui dérive. Le fournisseur, lui, doit valoir quelque chose — c'est une énumération
     * qui pilote un groupe de boutons radio.
     */
    llmProvider: 'OPENROUTER',
    llmBaseUrl: '',
    llmModel: '',
    llmMaxTokens: 4096,
    llmSnapshotWindowSize: 100,
    llmSnapshotWindowTimeoutSeconds: 30,
  });
  const [loading, setLoading] = useState(true);
  /*
   * Pourquoi `GET /api/config` n'a pas répondu, quand il n'a pas répondu.
   *
   * L'effet de chargement avalait l'échec dans un `catch` vide et posait `loading` à faux, donc la
   * page dessinait un formulaire complet sans avoir reçu la moindre valeur : l'affirmation non
   * vérifiée que ce dépôt retire partout ailleurs — la pastille de connexion, `inForce`, le
   * quatrième état de `describePersistence`. Un formulaire de réglages qui prétend montrer la
   * configuration en vigueur alors qu'il ne l'a jamais reçue est le pire endroit pour ça.
   */
  const [loadError, setLoadError] = useState<QueryErrorInfo | null>(null);
  const [saving, setSaving] = useState(false);
  const [testing, setTesting] = useState(false);
  const [saveSuccess, setSaveSuccess] = useState(false);
  const [testResult, setTestResult] = useState<boolean | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [errors, setErrors] = useState<FieldErrors>({});
  const [llmTesting, setLlmTesting] = useState(false);
  /*
   * Soit la réponse du serveur, soit un échec local — la requête qui n'est jamais partie, ou dont
   * la réponse n'est pas arrivée. Les deux n'ont pas la même forme et ne doivent pas la partager :
   * `provider` et `model` disent ce qui a été *sondé*, et un appel qui a échoué avant d'atteindre
   * le serveur n'a rien sondé du tout. Les remplir de valeurs plausibles serait une affirmation
   * inventée, ce que le typage vient précisément d'empêcher.
   */
  type LlmTestOutcome = LlmTestResponse | { ok: false; message: string; probeFailed: true };
  const [llmTestResult, setLlmTestResult] = useState<LlmTestOutcome | null>(null);
  const [providerDefaults, setProviderDefaults] = useState<ProviderDefaults>({});
  const [models, setModels] = useState<LlmModelShortlist | null>(null);
  const [modelsLoading, setModelsLoading] = useState(false);
  const [modelsOpen, setModelsOpen] = useState(false);
  const [includeUnconstrained, setIncludeUnconstrained] = useState(false);
  /** Dernier état persisté, pour savoir si le formulaire a été modifié. */
  const savedRef = useRef<string>('');
  const [dirty, setDirty] = useState(false);
  const draftConflict = useDraftConflict(DRAFT_KEYS);
  /*
   * Ce que le serveur dit de la conservation des réglages — tenu à part de `config` : ce ne sont
   * pas des champs du formulaire, et les y laisser entrer les ferait compter dans la comparaison
   * « modifié » et dans le brouillon écrit en `localStorage`.
   */
  const [persistence, setPersistence] = useState<SettingsPersistence>({});
  /*
   * Ce que le serveur dit être *en vigueur* pour le contenu des messages, tenu à part de `config`
   * pour la même raison que `persistence` — et parce que le bandeau doit décrire ce qui tourne, pas
   * ce qui est en train d'être tapé : `llmDataRetentionRefused` est calculé côté serveur et ne suit
   * pas un fournisseur changé dans le formulaire. Les mélanger afficherait la politique d'un
   * fournisseur sous le nom d'un autre.
   */
  const [inForce, setInForce] = useState<LlmPolicyFacts | null>(null);
  const [saveNote, setSaveNote] = useState<string | null>(null);
  /** Un oubli en cours, par nom de champ — ou `''` pour la totalité du magasin. */
  const [forgetting, setForgetting] = useState<string | null>(null);
  const persistenceNotice = useMemo(() => describePersistence(persistence), [persistence]);
  /*
   * Ce que le fichier porte, servi par nom depuis que le compte a été remplacé — et vide tant que
   * la première réponse n'est pas arrivée, ce qui est la valeur juste : rien n'est affirmé sur un
   * magasin dont on n'a pas encore lu l'état.
   */
  const storedFields = useMemo(
    () => persistence.settingsStoredFields ?? [], [persistence.settingsStoredFields]);
  const policy = useMemo(() => describeDataPolicy(inForce), [inForce]);
  /*
   * Ce que la passerelle a dit du modèle au dernier test. Dérivé du résultat et non d'un état à
   * part : il décrit ce qui a été testé, donc il doit disparaître avec lui plutôt que rester sous
   * un formulaire qu'on a modifié depuis.
   */
  /* Un échec local n'a pas de `modelCheck` : rien n'a été sondé, donc rien n'a été décrit. */
  const modelCheck = llmTestResult && 'modelCheck' in llmTestResult
    ? llmTestResult.modelCheck : undefined;
  /* Idem pour la clé : un échec local n'en dit rien non plus. */
  const keyStatus = llmTestResult && 'keyStatus' in llmTestResult
    ? llmTestResult.keyStatus : undefined;
  const modelNotes = useMemo(() => {
    const notes = describeModelCheck(modelCheck);
    // En dernier, et c'est l'ordre voulu : ce bloc va du plus bloquant au plus rassurant côté
    // *modèle*, et le crédit ne parle pas du modèle. Une clé épuisée reste un avertissement, donc
    // elle teinte le bloc comme les autres.
    const key = describeKeyStatus(keyStatus);
    return key ? [...notes, key] : notes;
  }, [modelCheck, keyStatus]);
  const modelIdentity = modelCheck ? describeModelIdentity(modelCheck) : null;
  /*
   * La sonde et la liste n'essaient que le *modèle* : le point d'accès reste celui en vigueur, et
   * délibérément — voir `probeBody`. Il faut donc le dire quand le formulaire a pris de l'avance
   * dessus, sinon « joignable » se lit comme un verdict sur le fournisseur qu'on vient de choisir.
   * Même source que la politique de confidentialité (`inForce`), pour la même raison : ce qui
   * tourne n'est pas ce qui est saisi.
   */
  const connectionIsStale = inForce != null && inForce.llmProvider !== config.llmProvider;
  const modelSlugs = useMemo(() => optionSlugs(models), [models]);
  const shortlistState = useMemo(() => describeShortlist(models), [models]);
  /**
   * Le formulaire pointe ailleurs que ce qui tourne : le bandeau décrit encore l'ancien.
   *
   * La politique de collecte compte autant que le fournisseur depuis qu'elle est modifiable ici :
   * `llmDataRetentionRefused` est calculé côté serveur, donc passer à `ALLOW` dans le formulaire
   * laisse le bandeau annoncer « aucune rétention » jusqu'à l'enregistrement — exactement le
   * mensonge que ce repère existe pour empêcher, sur la seule phrase de cette page qui engage
   * quelque chose.
   */
  const policyIsStale = inForce != null
    && (inForce.llmProvider !== config.llmProvider
      || inForce.llmOpenrouterDataCollection !== config.llmOpenrouterDataCollection);

  /*
   * Le serveur donne la base — c'est lui qui dit ce qui est réellement en vigueur, et lui seul
   * connaît les secrets — puis la saisie non enregistrée repasse par-dessus. `savedRef` reste
   * calé sur la réponse du serveur : c'est ce qui fait que le formulaire restauré s'affiche
   * modifié, avec ses boutons actifs, plutôt que de se croire à jour.
   */
  const fetchConfig = useCallback(async () => {
      let saved: ClusterConfig | null = null;
      setLoadError(null);
      try {
        const res = await axios.get<ClusterConfig & SettingsPersistence
          & { llmProviderDefaults?: ProviderDefaults }>('/api/config');
        const split = splitPersistence(res.data);
        setPersistence(split.persistence);
        /*
         * Les défauts par fournisseur, servis plutôt que recopiés ici. Sortis de l'objet avant
         * qu'il ne devienne l'état du formulaire, pour la raison exacte que `splitPersistence`
         * documente : un champ qui n'existe sur aucun formulaire entrerait dans la comparaison qui
         * décide s'il est modifié, et dans le brouillon écrit en `localStorage`. Absents d'une
         * réponse plus ancienne, auquel cas le changement de fournisseur ne propose rien — ce qui
         * vaut mieux que proposer une valeur devinée.
         */
        const { llmProviderDefaults, ...settings } = split.settings;
        if (llmProviderDefaults) setProviderDefaults(llmProviderDefaults);
        saved = settings;
      } catch (err: unknown) {
        /*
         * Le formulaire ne se dessine pas par-dessus une réponse qu'on n'a pas eue. Le
         * commentaire d'origine disait « le backend n'expose peut-être pas encore la config », ce
         * qui a cessé d'être vrai il y a longtemps ; ce qui restait, c'était une page affirmant
         * montrer ce qui tourne sans l'avoir demandé avec succès.
         */
        setLoadError(describeApiError(err, 'The configuration could not be read.'));
        setLoading(false);
        return;
      }
      setConfig(prev => {
        const server = { ...prev, ...(saved ?? {}) };
        savedRef.current = saved ? JSON.stringify(server) : '';
        return mergeDraft(server, readDraft<Partial<ClusterConfig> | null>(DRAFT_KEY, null));
      });
      // Rien à dire si le serveur n'a pas répondu : sans réponse, la politique est inconnue, et
      // une phrase rassurante posée par défaut serait exactement l'affirmation invérifiable que
      // cette page a été réécrite pour retirer.
      setInForce(saved ? {
          llmProvider: saved.llmProvider,
          llmLocalDeployment: saved.llmLocalDeployment,
          llmDataRetentionRefused: saved.llmDataRetentionRefused,
          llmOpenrouterDataCollection: saved.llmOpenrouterDataCollection,
        } : null);
      setLoading(false);
  }, []);

  useEffect(() => {
    // La lecture est ce qui produit l'état de la page, et seul un Suspense la sortirait de l'effet.
    // eslint-disable-next-line react-hooks/set-state-in-effect -- chargement au montage
    void fetchConfig();
  }, [fetchConfig]);

  useEffect(() => {
    setDirty(savedRef.current !== '' && JSON.stringify(config) !== savedRef.current);
  }, [config]);

  /*
   * Le brouillon n'est écrit que lorsqu'il y a quelque chose à garder : sans cette condition, un
   * simple passage sur la page recopierait la configuration du serveur dans `localStorage`, et
   * une modification faite ailleurs se retrouverait masquée par ce faux brouillon.
   */
  useEffect(() => {
    if (loading) return;
    if (dirty) writeDraft(DRAFT_KEY, draftableOnly(config));
    else clearDraft(DRAFT_KEY);
  }, [dirty, config, loading]);

  // Deux sorties, deux gardes : `beforeunload` pour le rechargement et la fermeture d'onglet,
  // `useBlocker` pour la navigation interne, que le navigateur ne voit jamais passer.
  useUnsavedGuard(dirty, {
    title: 'Leave the settings without saving?',
    description:
      'These settings have not been applied — the cluster stays on its current connection. Most '
      + 'of the form is kept as a draft, but passwords and API keys are never stored and will '
      + 'have to be typed again.',
  });

  /**
   * Le message du serveur quand il refuse, ou `null` quand il n'en a pas donné.
   *
   * Un 400 nomme le champ et dit pourquoi — `kafka.mode` qui se connecterait en clair, des
   * identifiants Confluent Cloud absents, un provider LLM mal tapé. Le remplacer par « Failed to
   * save configuration » jette la seule partie utile, exactement ce que le 409 avait déjà été
   * corrigé pour ne plus faire.
   */
  const refusal = (err: unknown, status: number): string | null =>
    axios.isAxiosError(err) && err.response?.status === status
      ? ((err.response.data as { message?: string } | undefined)?.message ?? null)
      : null;

  /**
   * Le serveur refuse de repointer le cluster tant qu'un audit, un job Flink ou une session
   * Process Mining tourne encore dessus (409) : il dit lequel, et on propose de forcer plutôt que
   * d'afficher « Failed to save configuration » sur un refus parfaitement délibéré.
   */
  const applyConfig = async (force: boolean): Promise<ClusterConfig> => {
    const res = await axios.post<ClusterConfig & SettingsPersistence>(
      '/api/config', force ? { ...config, force } : config);
    const { settings, persistence: kept } = splitPersistence(res.data);
    /*
     * Ce que l'enregistrement a retiré. Une clé ne suit pas le point d'accès vers un autre hôte —
     * le serveur l'efface plutôt que de l'y envoyer — et le champ du formulaire est vide dans les
     * deux cas, donc sans cette phrase le prochain appel échouerait sur un identifiant manquant
     * avec rien qui relie les deux.
     */
    const cleared = (res.data as { credentialsCleared?: string[] }).credentialsCleared ?? [];
    setConfig(prev => {
      const next = { ...prev, ...settings };
      savedRef.current = JSON.stringify(next);
      return next;
    });
    setPersistence(kept);
    setInForce({
      llmProvider: settings.llmProvider,
      llmLocalDeployment: settings.llmLocalDeployment,
      llmDataRetentionRefused: settings.llmDataRetentionRefused,
      llmOpenrouterDataCollection: settings.llmOpenrouterDataCollection,
    });
    // Ce que l'enregistrement a réellement obtenu quand ce n'est pas ce qui était promis : un
    // magasin qu'on n'a pas pu écrire laisse des réglages qui marchent maintenant et disparaissent
    // au redémarrage. Ça ne peut pas rester sous un simple « Saved! ».
    setSaveNote(cleared.length > 0
      ? 'The endpoint now points at a different host, so the stored API key was not carried over '
        + 'to it. Enter the key for the new endpoint and save again.'
      : describeSaveOutcome(kept));
    setSaveSuccess(true);
    setTimeout(() => setSaveSuccess(false), 3000);
    return settings;
  };

  /**
   * Enregistre, et rend compte du refus quand il y en a un.
   *
   * Partagé entre les deux boutons parce que **les deux enregistrent** : ce point d'accès est le
   * seul chemin qui repointe le cluster, et il n'y a pas de sonde sans lui — une adresse de
   * courtier prise dans le corps d'une requête serait la contrefaçon de requête côté serveur que
   * `test-llm` refuse par construction. Ce qui est corrigé n'est donc pas le geste mais ce qu'il
   * raconte : un 409 (« un audit tourne encore ») et un 400 (« ce mode n'existe pas ») arrivaient
   * ici sous la forme « Connection failed », c'est-à-dire un verdict sur le courtier à propos d'un
   * appel qui ne l'avait jamais atteint.
   *
   * @returns les réglages appliqués, ou `null` si le serveur a refusé
   */
  const submitConfig = async (): Promise<ClusterConfig | null> => {
    try {
      return await applyConfig(false);
    } catch (err) {
      const conflict = refusal(err, 409);
      const rejected = refusal(err, 400);
      if (rejected) {
        setError(rejected);
      } else if (!conflict) {
        setError('Failed to save configuration.');
      } else if (await confirm({
        title: 'Work is still running on this cluster',
        description: conflict,
        confirmLabel: 'Repoint anyway',
        tone: 'danger',
        icon: 'warning',
      })) {
        try {
          return await applyConfig(true);
        } catch (forced) {
          setError(refusal(forced, 400) ?? 'Failed to save configuration.');
        }
      }
      return null;
    }
  };

  const handleSave = async () => {
    if (!checkBeforeSubmit()) return;
    setSaving(true);
    setError(null);
    setSaveNote(null);
    setSaveSuccess(false);
    try {
      await submitConfig();
    } finally {
      setSaving(false);
    }
  };

  /**
   * Applique le formulaire, puis dit si le courtier répond.
   *
   * Trois choses passaient à côté. Le refus du serveur était rendu comme un échec de connexion
   * (voir `submitConfig`). L'enregistrement réussi ne mettait pas `savedRef` à jour, donc la page
   * affichait « Unsaved changes » et sa garde de sortie annonçait « ces réglages n'ont pas été
   * appliqués » à propos de réglages qu'elle venait d'appliquer *et* d'écrire sur disque. Et
   * `inForce` restait en arrière, donc le bandeau de confidentialité continuait de décrire le
   * fournisseur précédent. Tout cela vient de `applyConfig`, qu'il suffisait d'emprunter.
   */
  const handleTestConnection = async () => {
    if (!checkBeforeSubmit()) return;
    setTesting(true);
    setTestResult(null);
    setError(null);
    setSaveNote(null);
    try {
      // Le verdict ne se pose que si la configuration est passée : un refus n'a rien sondé, et
      // l'erreur du serveur est déjà à l'écran.
      const applied = await submitConfig();
      if (applied) setTestResult(applied.isConnected ?? false);
    } finally {
      setTesting(false);
    }
  };

  /**
   * Ce que la sonde envoie : le modèle saisi, et rien d'autre.
   *
   * Ni le point d'accès ni la clé ne voyagent — le serveur les refuserait. Une URL prise dans une
   * requête est une contrefaçon de requête côté serveur, et une clé qui retomberait sur celle du
   * déploiement en ferait une exfiltration en un appel. Changer d'endpoint reste le rôle
   * d'Enregistrer, qui est un geste délibéré et persisté.
   */
  const probeBody = () => ({ llmModel: config.llmModel ?? '' });

  /**
   * Teste ce qui est dans le formulaire — sans l'enregistrer.
   *
   * Cette fonction commençait par `POST /api/config`, donc *essayer* un modèle repointait le
   * déploiement en cours et, quand la persistance est active, l'écrivait sur disque. Explorer et
   * s'engager étaient le même geste, ce qui est précisément pourquoi personne ne comparait deux
   * modèles. Le serveur construit maintenant un client jetable à partir de ce corps et ne touche à
   * rien.
   */
  const handleTestLlm = async () => {
    // Seul le modèle voyage (voir `probeBody`), donc seul le modèle est validé.
    if (!checkBeforeSubmit(['llmModel'])) return;
    // `axios` ne pose aucun délai par défaut, donc ce bouton pouvait tourner indéfiniment face à un
    // serveur qui ne répond jamais — la règle que l'éditeur SQL a servi à écrire, restée non
    // appliquée ici. L'attente se déduit du budget du serveur : c'est le champ juste au-dessus, et
    // une UI qui fixe elle-même un délai pour un appel dont elle connaît le budget se trompe le
    // jour où ce budget bouge.
    const budget = config.llmRequestTimeoutSeconds;
    const waitMs = testTimeoutMs(budget);
    setLlmTesting(true);
    setLlmTestResult(null);
    setError(null);
    try {
      const res = await axios.post<LlmTestResponse>('/api/config/test-llm', probeBody(),
        { timeout: waitMs });
      setLlmTestResult(res.data);
    } catch (err: unknown) {
      const gaveUp = axios.isAxiosError(err)
        && (err.code === 'ECONNABORTED' || err.code === 'ETIMEDOUT');
      const msg = gaveUp
        ? describeTestTimeout(waitMs, budget)
        : err instanceof Error ? err.message : 'LLM test failed';
      setLlmTestResult({ ok: false, message: msg, probeFailed: true });
    } finally {
      setLlmTesting(false);
    }
  };

  /**
   * Va chercher la liste restreinte des modèles.
   *
   * Paresseux, et déclenché par un geste : la liste n'est utile qu'à qui choisit un modèle, et
   * rien dont le seul produit est un confort de formulaire ne doit peser sur le chargement de la
   * page. Lue contre le point d'accès *en vigueur*, jamais contre un que la requête nommerait —
   * même règle que la sonde. La conséquence est dite à l'écran : changer de fournisseur dans le
   * formulaire demande de l'enregistrer avant que la liste décrive le nouveau point d'accès.
   */
  const loadModels = async (unconstrained: boolean) => {
    setModelsLoading(true);
    try {
      const res = await axios.get<LlmModelShortlist>('/api/config/llm-models', {
        params: { includeUnconstrained: unconstrained },
      });
      setModels(res.data);
    } catch (err: unknown) {
      const msg = err instanceof Error ? err.message : 'The model list could not be read.';
      setModels({ available: false, models: [], criteria: [], error: msg });
    } finally {
      setModelsLoading(false);
    }
  };

  const toggleModelPicker = () => {
    const opening = !modelsOpen;
    setModelsOpen(opening);
    if (opening && !models && !modelsLoading) void loadModels(includeUnconstrained);
  };

  const applyUnconstrained = (value: boolean) => {
    setIncludeUnconstrained(value);
    if (modelsOpen) void loadModels(value);
  };

  /**
   * Cesse de conserver un réglage — ou tous.
   *
   * L'appartenance au magasin était **collante et à sens unique** : un champ qui y était entré y
   * restait, réécrit à chaque enregistrement, sans qu'aucun geste de cette page ne puisse l'en
   * sortir. Une adresse de courtier saisie par erreur, ou nommant un cluster démantelé depuis, ne
   * se défaisait qu'en éditant un fichier sur le disque du déploiement, ou en ajoutant la variable
   * d'environnement qui la surclasse — deux modifications du déploiement pour une valeur saisie
   * dans un formulaire.
   *
   * Le geste est confirmé parce qu'il n'est pas intuitif dans un sens précis, que le dialogue dit :
   * il ne change rien au processus en cours.
   */
  const handleForget = async (field?: string) => {
    const targets = field ? [field] : (persistence.settingsStoredFields ?? []);
    if (targets.length === 0) return;
    const ok = await confirm({
      title: field
        ? `Stop keeping “${storedFieldLabel(field)}”?`
        : `Stop keeping ${targets.length === 1 ? 'this setting' : `these ${targets.length} settings`}?`,
      description: describeForget(targets),
      confirmLabel: 'Forget',
      tone: 'danger',
      icon: 'warning',
    });
    if (!ok) return;
    setForgetting(field ?? '');
    setError(null);
    try {
      const res = await axios.delete<ForgetOutcome & SettingsPersistence>(
        '/api/config/stored', field ? { params: { field } } : undefined);
      const { persistence: kept } = splitPersistence(res.data);
      setPersistence(kept);
      setSaveNote(describeForgetOutcome(res.data));
    } catch (err) {
      setError(refusal(err, 400) ?? 'The saved settings could not be released.');
    } finally {
      setForgetting(null);
    }
  };

  /**
   * Toutes les erreurs d'un coup, indexées par champ.
   *
   * La version précédente renvoyait la **première** erreur sous forme de chaîne, affichée dans un
   * bandeau en bas d'une page qui défile : on en corrigeait une, on cliquait Save, on obtenait la
   * suivante, et le champ fautif n'était ni signalé ni focalisé.
   */
  const validateConfig = (): FieldErrors => {
    const errors: FieldErrors = {};
    const servers = config.bootstrapServers?.trim() ?? '';
    if (!servers) {
      errors.bootstrapServers = 'Bootstrap servers are required.';
    } else {
      const invalid = servers.split(',').map(s => s.trim()).filter(Boolean)
        .find(part => !/^[^\s:]+:\d{1,5}$/.test(part));
      if (invalid) {
        errors.bootstrapServers = `Invalid entry "${invalid}". Expected host:port (e.g. localhost:9092).`;
      }
    }
    if (config.mode === 'CONFLUENT_CLOUD') {
      if (!config.confluentKey?.trim()) errors.confluentKey = 'API Key is required for Confluent Cloud.';
      if (!config.confluentSecret?.trim()) errors.confluentSecret = 'API Secret is required for Confluent Cloud.';
    }
    if (config.mode === 'SSL') {
      if (!config.truststorePath?.trim()) errors.truststorePath = 'Truststore path is required for SSL.';
      if (!config.keystorePath?.trim()) errors.keystorePath = 'Keystore path is required for SSL.';
    }
    // SpectraLLM picks its own served model, so the model field is optional for it.
    if (config.llmProvider !== 'SPECTRA' && !config.llmModel?.trim()) {
      errors.llmModel = 'A model is required for process mining.';
    } else {
      // La forme du slug, refusée ici plutôt qu'à la première fenêtre analysée — où la passerelle
      // répond la même 404 que pour un modèle inexistant, et l'opérateur va vérifier un nom.
      const slugProblem = validateModelSlug(config.llmProvider, config.llmModel);
      if (slugProblem) errors.llmModel = slugProblem;
    }
    if (config.llmProvider !== 'OLLAMA' && !config.llmBaseUrl?.trim()) {
      errors.llmBaseUrl = 'A base URL is required for every provider but Ollama, which defaults to the local one.';
    }
    if (API_KEY_REQUIRED.has(config.llmProvider)
      && !config.llmApiKeyConfigured
      && !config.llmApiKey?.trim()) {
      const label = LLM_PROVIDERS.find(p => p.value === config.llmProvider)?.label ?? config.llmProvider;
      errors.llmApiKey = `An API key is required when the provider is ${label}.`;
    }
    if (!Number.isFinite(config.llmMaxTokens) || config.llmMaxTokens < 256) {
      errors.llmMaxTokens = 'Must be at least 256.';
    }
    if (!Number.isFinite(config.llmSnapshotWindowSize) || config.llmSnapshotWindowSize < 10) {
      errors.llmSnapshotWindowSize = 'Must be at least 10 messages.';
    }
    if (!Number.isFinite(config.llmSnapshotWindowTimeoutSeconds) || config.llmSnapshotWindowTimeoutSeconds < 5) {
      errors.llmSnapshotWindowTimeoutSeconds = 'Must be at least 5 seconds.';
    }
    return errors;
  };

  /**
   * Valide, publie les erreurs et focalise le premier champ fautif.
   * @returns true quand le formulaire peut partir
   */
  const checkBeforeSubmit = (only?: readonly ValidatedField[]): boolean => {
    const all = validateConfig();
    /*
     * Une sonde ne valide que ce qu'elle envoie. `checkBeforeSubmit()` refusait « Test LLM » sur un
     * chemin de keystore manquant — une erreur sans rapport avec le geste — et déplaçait le focus
     * vers un champ Kafka. Depuis que la sonde n'applique plus rien et n'emporte que le modèle, le
     * reste du formulaire ne la concerne pas.
     */
    const found: FieldErrors = only
      ? Object.fromEntries(only.filter(k => all[k]).map(k => [k, all[k]]))
      : all;
    /*
     * Une validation restreinte ne *remplace* pas ce qui est affiché : elle rafraîchit ses propres
     * champs et laisse les autres. Sinon une sonde effacerait les erreurs qu'un Enregistrer venait
     * de poser sur l'autre moitié du formulaire — elles tiennent toujours.
     */
    setErrors(prev => (only
      ? { ...prev, ...Object.fromEntries(only.map(k => [k, all[k]])) }
      : all));
    const firstInvalid = FIELD_ORDER.find(key => found[key]);
    if (!firstInvalid) return true;
    // Le champ peut être dans une section masquée par le mode courant — d'où le garde-fou.
    document.getElementById(fieldIds[firstInvalid])?.focus();
    return false;
  };

  const set = (key: keyof ClusterConfig, value: string) => {
    setConfig(prev => ({ ...prev, [key]: value }));
    clearError(key);
    forgetVerdicts(key);
  };
  const setNumber = (key: keyof ClusterConfig, value: number) => {
    setConfig(prev => ({ ...prev, [key]: value }));
    clearError(key);
    forgetVerdicts(key);
  };

  /**
   * Un verdict décrit ce qui a été essayé, pas ce qui est à l'écran.
   *
   * « Connection successful » restait affiché après qu'on eut changé l'adresse du courtier, et le
   * résultat du test LLM après qu'on eut changé de modèle : deux affirmations portant sur une
   * configuration qui n'existe plus. Même règle que `isResultStale` dans l'éditeur SQL — le
   * résultat périmé disparaît plutôt que de passer pour celui du formulaire courant.
   */
  const forgetVerdicts = (key: keyof ClusterConfig) => {
    if (String(key).startsWith('llm')) setLlmTestResult(null);
    else setTestResult(null);
  };

  /** Une erreur disparaît dès qu'on retouche le champ — la revalidation a lieu au submit. */
  const clearError = (key: keyof ClusterConfig) => {
    setErrors(prev => (prev[key as ValidatedField] ? { ...prev, [key]: undefined } : prev));
  };

  const applyLlmProvider = (provider: ClusterConfig['llmProvider']) => {
    setConfig(prev => {
      const next: ClusterConfig = { ...prev, llmProvider: provider };
      // Une base URL saisie à la main est conservée ; celle d'un autre fournisseur ne l'est pas —
      // c'est un défaut, pas un choix, et la laisser en place pointe le nouveau fournisseur vers
      // l'ancien endpoint. La règle était écrite fournisseur par fournisseur, chacun énumérant les
      // défauts des autres : le cinquième aurait demandé de retoucher les quatre.
      const suggested = providerDefaults[provider];
      if (suggested?.baseUrl && isProviderDefaultUrl(providerDefaults, prev.llmBaseUrl)) {
        next.llmBaseUrl = suggested.baseUrl;
      }
      /*
       * Le modèle ne suit que lorsque celui en place ne peut pas convenir au nouveau fournisseur :
       * les slugs OpenRouter s'appellent `vendor/model`, donc un `qwen3:4b` hérité d'Ollama n'y
       * résout rien et l'erreur arriverait à la première fenêtre analysée. La valeur proposée vient
       * du serveur, jamais d'une constante écrite ici.
       */
      const staleModel =
        !prev.llmModel
        || (provider === 'OPENROUTER' && !prev.llmModel.includes('/'))
        || (provider === 'OLLAMA'
            && (prev.llmModel.startsWith('claude-') || prev.llmModel.includes('/')));
      if (staleModel && suggested?.model) {
        next.llmModel = suggested.model;
      }
      if (provider === 'OLLAMA') {
        next.llmApiKey = '';
      }
      if (provider === 'SPECTRA') {
        // SpectraLLM serves its own configured model; no per-request model to send.
        next.llmApiKey = '';
      }
      return next;
    });
  };

  const errorCount = useMemo(() => Object.values(errors).filter(Boolean).length, [errors]);

  /*
   * Rien du formulaire tant qu'on ne sait pas ce qui tourne. Un panneau d'erreur avec un bouton
   * Réessayer plutôt qu'une page qui prétend montrer la configuration en vigueur : le geste
   * qu'offre cette page est la saisie, et laisser saisir par-dessus une base inconnue produirait un
   * enregistrement dont personne ne sait ce qu'il écrase.
   */
  if (loadError) return (
    <div className="p-4 md:p-6 max-w-3xl space-y-6">
      <PageHeader title="Configuration" description="Manage Kafka cluster connection, security and process-mining LLM settings." />
      <ErrorPanel error={loadError} onRetry={() => { setLoading(true); void fetchConfig(); }} />
    </div>
  );

  if (loading) return (
    <div className="p-4 md:p-6 max-w-3xl space-y-6">
      <PageHeader title="Configuration" description="Manage Kafka cluster connection, security and process-mining LLM settings." />
      <div className="skeleton-shimmer h-16 w-full rounded-xl" />
      <CardSkeleton lines={4} />
      <CardSkeleton lines={5} />
    </div>
  );

  return (
    <form
      className="p-4 md:p-6 max-w-3xl space-y-6"
      noValidate
      // Un vrai <form> : Entrée depuis n'importe quel champ enregistre, au lieu de ne rien faire.
      onSubmit={e => { e.preventDefault(); void handleSave(); }}
    >
      <PageHeader
        title="Configuration"
        description="Manage Kafka cluster connection, security and process-mining LLM settings."
      />

      {/* Connection Status Banner */}
      <div className={`rounded-xl border p-4 flex items-center gap-3 ${
        config.isConnected
          ? 'bg-success/10 border-success/25'
          : 'bg-surface-container border-outline-variant'
      }`}>
        <span className="relative flex h-3 w-3">
          <span className={`animate-ping absolute inline-flex h-full w-full rounded-full opacity-75 ${config.isConnected ? 'bg-success' : 'bg-outline'}`} />
          <span className={`relative inline-flex rounded-full h-3 w-3 ${config.isConnected ? 'bg-success' : 'bg-outline'}`} />
        </span>
        <div>
          <p className={`text-sm font-bold ${config.isConnected ? 'text-success' : 'text-on-surface-variant'}`}>
            {config.isConnected ? 'Connected' : 'Not connected'}
          </p>
          <p className="text-xs text-on-surface-variant">{config.bootstrapServers}</p>
          {/*
            Pourquoi, et pas seulement que. « Not connected » recouvre un courtier arrêté, une
            adresse qui ne pointe sur rien et un client que le cluster refuse — trois causes, trois
            corrections, et c'est ici qu'on vient corriger l'adresse. Le serveur porte la raison
            depuis `pingDetail`, cette page lisait le booléen.
          */}
          {!config.isConnected && config.connectionError && (
            <p className="text-xs text-on-surface-variant/80 mt-0.5 break-words">
              {config.connectionError}
            </p>
          )}
        </div>
        {testResult !== null && (
          <div className={`ml-auto flex items-center gap-1.5 text-xs font-bold ${testResult ? 'text-success' : 'text-error'}`}>
            <span className="material-symbols-outlined text-sm">{testResult ? 'check_circle' : 'cancel'}</span>
            {testResult ? 'Connection successful' : 'Connection failed'}
          </div>
        )}
      </div>

      {/*
        Ce que cette page peut promettre. Elle applique des réglages à un processus ; savoir s'ils
        lui survivent change ce qu'on vient y faire — sur un déploiement qui n'en garde rien, le
        vrai correctif est dans sa configuration, pas dans ce formulaire. Le silence ferait lire le
        cas rassurant, qui a été le mauvais pendant longtemps.
      */}
      <div
        className={`rounded-lg border p-3 flex items-start gap-2 text-[13px] ${
          persistenceNotice.tone === 'not-kept'
            ? 'border-warning/25 bg-warning/10 text-warning'
            : persistenceNotice.tone === 'partial'
              ? 'border-outline-variant bg-surface-container text-on-surface-variant'
              : 'border-outline-variant/60 bg-surface-container/60 text-on-surface-variant'
        }`}
      >
        <span className="material-symbols-outlined text-[18px] shrink-0">
          {persistenceNotice.tone === 'not-kept' ? 'warning'
            : persistenceNotice.tone === 'unknown' ? 'hourglass_empty' : 'save'}
        </span>
        <div className="min-w-0">
          <p className="font-medium">{persistenceNotice.text}</p>
          {persistenceNotice.detail && (
            <p className="text-on-surface-variant/80 mt-0.5">{persistenceNotice.detail}</p>
          )}
          {/*
            Lesquels, et le moyen de les reprendre. Le serveur envoyait un *nombre*, que personne
            ne lisait et que personne ne pouvait lire : « 5 réglages sont conservés » ne se corrige
            pas. Et l'appartenance au magasin était à sens unique — un champ entré là y restait,
            réécrit à chaque enregistrement, et seule l'édition d'un fichier sur le disque du
            déploiement l'en sortait.
          */}
          {storedFields.length > 0 && (
            <div className="mt-2">
              <p className="text-on-surface-variant/80">
                Taken from the file rather than from this deployment’s own configuration:
              </p>
              <ul className="mt-1 flex flex-wrap gap-1.5">
                {storedFields.map(field => (
                  <li key={field}>
                    <button
                      type="button"
                      onClick={() => void handleForget(field)}
                      disabled={forgetting !== null}
                      title={`Stop keeping ${storedFieldLabel(field)} — the next start reads it from this deployment’s configuration`}
                      className="inline-flex items-center gap-1 rounded-md border border-outline-variant
                        bg-surface-container-low pl-2 pr-1.5 py-1 text-[12px] text-on-surface-variant
                        hover:border-outline disabled:opacity-50"
                    >
                      {storedFieldLabel(field)}
                      <span aria-hidden="true" className="material-symbols-outlined text-[14px]">close</span>
                      <span className="sr-only">— stop keeping this setting</span>
                    </button>
                  </li>
                ))}
              </ul>
              <Button
                type="button" variant="ghost" size="sm" className="mt-1.5"
                icon={forgetting === '' ? undefined : 'delete_sweep'}
                loading={forgetting === ''}
                disabled={forgetting !== null}
                onClick={() => void handleForget()}
              >
                Forget all saved settings
              </Button>
            </div>
          )}
        </div>
      </div>

      {/* Cluster Connection */}
      <div className="rounded-xl bg-surface-container ring-1 ring-white/[0.045] overflow-hidden">
        <div className="p-4 border-b border-outline-variant/60 flex items-center gap-3">
          <span className="material-symbols-outlined text-primary">lan</span>
          <h2 className="font-bold text-on-surface">Cluster Connection</h2>
        </div>
        <div className="p-5 space-y-5">
          {/* Bootstrap Servers */}
          <Field
            label="Bootstrap Servers"
            required
            id={fieldIds.bootstrapServers}
            error={errors.bootstrapServers}
            description="Comma-separated list of host:port pairs."
          >
            {p => (
              <Input
                {...p}
                className="font-mono"
                value={config.bootstrapServers}
                onChange={e => set('bootstrapServers', e.target.value)}
                placeholder="localhost:9092"
                autoComplete="off"
                spellCheck={false}
              />
            )}
          </Field>

          {/* Security Mode */}
          <RadioCards
            legend="Security Mode"
            name="cfg-mode"
            value={config.mode}
            onChange={value => set('mode', value)}
            options={MODES}
            columns="grid-cols-1 sm:grid-cols-3"
          />
        </div>
      </div>

      {/* SSL Config */}
      {config.mode === 'SSL' && (
        <div className="rounded-xl bg-surface-container ring-1 ring-white/[0.045] overflow-hidden">
          <div className="p-4 border-b border-outline-variant/60 flex items-center gap-3">
            <span className="material-symbols-outlined text-primary">lock</span>
            <h2 className="font-bold text-on-surface">SSL / mTLS Settings</h2>
          </div>
          <div className="p-5 grid grid-cols-1 sm:grid-cols-2 gap-4">
            <Field label="Truststore Path" required id={fieldIds.truststorePath} error={errors.truststorePath}>
              {p => (
                <Input {...p} className="font-mono" value={config.truststorePath ?? ''}
                  onChange={e => set('truststorePath', e.target.value)}
                  placeholder="/path/to/truststore.jks" autoComplete="off" spellCheck={false} />
              )}
            </Field>
            <Field label="Truststore Password"
              description={secretHint(config.truststorePasswordConfigured, config.truststorePassword)}>
              {p => (
                <PasswordInput {...p} value={config.truststorePassword ?? ''}
                  onChange={e => set('truststorePassword', e.target.value)} />
              )}
            </Field>
            <Field label="Keystore Path" required id={fieldIds.keystorePath} error={errors.keystorePath}>
              {p => (
                <Input {...p} className="font-mono" value={config.keystorePath ?? ''}
                  onChange={e => set('keystorePath', e.target.value)}
                  placeholder="/path/to/keystore.jks" autoComplete="off" spellCheck={false} />
              )}
            </Field>
            <Field label="Keystore Password"
              description={secretHint(config.keystorePasswordConfigured, config.keystorePassword)}>
              {p => (
                <PasswordInput {...p} value={config.keystorePassword ?? ''}
                  onChange={e => set('keystorePassword', e.target.value)} />
              )}
            </Field>
            <Field label="Key Password"
              description={secretHint(config.keyPasswordConfigured, config.keyPassword)}>
              {p => (
                <PasswordInput {...p} value={config.keyPassword ?? ''}
                  onChange={e => set('keyPassword', e.target.value)} />
              )}
            </Field>
          </div>
        </div>
      )}

      {/* Confluent Cloud Config */}
      {config.mode === 'CONFLUENT_CLOUD' && (
        <div className="rounded-xl bg-surface-container ring-1 ring-white/[0.045] overflow-hidden">
          <div className="p-4 border-b border-outline-variant/60 flex items-center gap-3">
            <span className="material-symbols-outlined text-primary">cloud</span>
            <h2 className="font-bold text-on-surface">Confluent Cloud Settings</h2>
          </div>
          <div className="p-5 grid grid-cols-1 sm:grid-cols-2 gap-4">
            <Field label="API Key" required id={fieldIds.confluentKey} error={errors.confluentKey}>
              {p => (
                <Input {...p} className="font-mono" value={config.confluentKey ?? ''}
                  onChange={e => set('confluentKey', e.target.value)}
                  placeholder="YOUR_API_KEY" autoComplete="off" spellCheck={false} />
              )}
            </Field>
            <Field label="API Secret" required id={fieldIds.confluentSecret} error={errors.confluentSecret}
              description={secretHint(config.confluentSecretConfigured, config.confluentSecret)}>
              {p => (
                <PasswordInput {...p} value={config.confluentSecret ?? ''}
                  onChange={e => set('confluentSecret', e.target.value)}
                  placeholder="YOUR_API_SECRET" />
              )}
            </Field>
          </div>
        </div>
      )}

      <div className="rounded-xl bg-surface-container ring-1 ring-white/[0.045] overflow-hidden">
        <div className="p-4 border-b border-outline-variant/60 flex items-center gap-3">
          <span className="material-symbols-outlined text-primary">neurology</span>
          <div>
            <h2 className="font-bold text-on-surface">Process Mining LLM</h2>
            <p className="text-xs text-on-surface-variant mt-0.5">
              Applied at runtime. Use environment variables or `application.yml` for persistent configuration.
            </p>
          </div>
        </div>
        <div className="p-5 space-y-5">
          <RadioCards
            legend="Provider"
            name="cfg-llm-provider"
            value={config.llmProvider}
            onChange={provider => {
              applyLlmProvider(provider);
              setErrors({});
              // Le verdict précédent portait sur un autre fournisseur.
              setLlmTestResult(null);
            }}
            options={LLM_PROVIDERS}
            columns="grid-cols-1 sm:grid-cols-2 lg:grid-cols-3"
          />

          {/* Ce que devient le contenu envoyé au modèle, dans les quatre cas où la réponse
              diffère — voir `llmPolicy.ts`. Lu sur l'adresse résolue et sur le réglage de routage,
              jamais sur le nom du fournisseur : un Ollama pointé sur une autre machine est
              distant, et « aucune rétention » ne se dit que là où le routage peut l'imposer. */}
          {policy && (
            <div className={`rounded-lg border px-4 py-3 text-xs ${
              policy.tone === 'local' ? 'border-success/20 bg-success/5 text-success'
              : policy.tone === 'restricted' ? 'border-success/20 bg-success/5 text-success'
              : policy.tone === 'open' ? 'border-warning/25 bg-warning/5 text-warning'
              : 'border-outline-variant/60 bg-surface-container-low text-on-surface-variant'
            }`}>
              <p className="font-semibold">Message content: {policy.label}</p>
              <p className="mt-1 opacity-90">{policy.detail}</p>
              {policy.tone !== 'local' && (
                <p className="mt-1 opacity-90">
                  Switch to Ollama or SpectraLLM to keep everything on your own network.
                </p>
              )}
              {/* Le bandeau décrit ce qui tourne, pas ce qui est tapé — le dire vaut mieux que de
                  laisser lire la politique d'un fournisseur sous le nom d'un autre. */}
              {policyIsStale && (
                <p className="mt-1 font-medium">
                  This describes the configuration in force. Save to apply the provider selected above.
                </p>
              )}
            </div>
          )}

          {/*
            Deux faits sur la configuration *en vigueur*, servis depuis longtemps et rendus nulle
            part ici. Le premier est celui qui coûte : `llmConfigurationProblem` dit ce qui empêche
            d'appeler un modèle avant même d'essayer — une clé absente, une adresse absente, une
            adresse qui n'en est pas une — et Process Mining l'affichait pendant que la page qui
            porte le réglage à changer se taisait. Le second répond à la question que le réglage
            seul ne tranche pas : `AUTO` décline pour un point d'accès inconnu, donc « AUTO » ne dit
            pas si un schéma part avec la requête.

            Ils décrivent ce qui tourne, jamais ce qui est tapé — d'où la formulation, et d'où le
            même repère `policyIsStale` que le bandeau au-dessus.
          */}
          {(config.llmConfigurationProblem || config.llmStructuredOutputActive !== undefined) && (
            <div className={`rounded-lg border px-4 py-3 text-[12px] space-y-1.5 ${
              config.llmConfigurationProblem
                ? 'border-warning/25 bg-warning/5' : 'border-outline-variant/60 bg-surface-container-low'
            }`} data-testid="llm-in-force">
              {config.llmConfigurationProblem && (
                <p className="flex items-start gap-2 text-warning">
                  <span aria-hidden="true" className="material-symbols-outlined text-[16px] mt-px">warning</span>
                  <span className="break-words">
                    The configuration in force cannot call a model: {config.llmConfigurationProblem}
                  </span>
                </p>
              )}
              {config.llmStructuredOutputActive !== undefined && (
                <p className="flex items-start gap-2 text-on-surface-variant">
                  <span aria-hidden="true" className="material-symbols-outlined text-[16px] mt-px">
                    {config.llmStructuredOutputActive ? 'data_object' : 'help'}
                  </span>
                  <span className="break-words">
                    {config.llmStructuredOutputActive
                      ? 'Answers are constrained by a JSON schema on this provider.'
                      : 'Answers are not constrained by a JSON schema here — the reply is parsed '
                        + 'leniently instead. Set claude.structured-output to ON for a gateway '
                        + 'known to support it.'}
                  </span>
                </p>
              )}
              {policyIsStale && (
                <p className="text-on-surface-variant/80">
                  This describes the configuration in force, not the provider selected above.
                </p>
              )}
            </div>
          )}

          {/*
            Trois réglages que le formulaire renvoyait au serveur sans jamais les montrer. Celui qui
            compte est la politique de collecte : c'est le seul endroit de cette application où une
            affirmation de confidentialité cesse d'être un avertissement, parce qu'OpenRouter
            l'impose au routage — et le bandeau au-dessus l'énonce. Le laisser invisible revenait à
            faire dépendre cette phrase d'une valeur que la page ne permettait pas de lire.
          */}
          <details className="rounded-lg border border-outline-variant/60 bg-surface-container-low">
            <summary className="cursor-pointer select-none px-4 py-2.5 text-[12px] font-medium text-on-surface-variant">
              Decoding and routing
            </summary>
            <div className="px-4 pb-4 pt-1 space-y-4">
              <RadioCards
                legend="Structured output"
                name="cfg-llm-structured-output"
                value={config.llmStructuredOutput ?? 'AUTO'}
                onChange={value => set('llmStructuredOutput', value)}
                options={STRUCTURED_OUTPUT}
                columns="grid-cols-1 sm:grid-cols-3"
              />
              {config.llmProvider === 'OPENROUTER' && (
                <>
                  <div>
                    <RadioCards
                      legend="Data collection"
                      name="cfg-llm-data-collection"
                      value={config.llmOpenrouterDataCollection ?? 'DENY'}
                      onChange={value => set('llmOpenrouterDataCollection', value)}
                      options={DATA_COLLECTION}
                      columns="grid-cols-1 sm:grid-cols-2"
                    />
                    {/* Ce que la restriction coûte, dit là où on la choisit : un modèle que seuls
                        des fournisseurs collecteurs servent cesse d'être routable, et la
                        passerelle le signale avec la même 404 qu'un slug mal tapé. */}
                    <p className="mt-1.5 text-[11px] text-on-surface-variant">
                      {(config.llmOpenrouterDataCollection ?? 'DENY') === 'DENY'
                        ? 'A model served only by providers that retain data stops being routable, '
                          + 'and OpenRouter reports that with the same 404 it uses for a mistyped '
                          + 'slug. Free models are usually in that case.'
                        : 'Message digests may be routed to providers that retain them, and may be '
                          + 'used for training. Nothing here can observe or undo that.'}
                    </p>
                  </div>
                  <label className="flex items-start gap-3">
                    <Switch
                      checked={config.llmOpenrouterRequireParameters ?? false}
                      onChange={value => setConfig(prev => ({
                        ...prev, llmOpenrouterRequireParameters: value,
                      }))}
                      aria-label="Route only to providers that implement every parameter sent"
                      className="mt-0.5"
                    />
                    <span>
                      <span className="block text-xs font-bold text-on-surface">
                        Require full parameter support
                      </span>
                      <span className="block text-[11px] text-on-surface-variant mt-0.5">
                        Off by default: it makes schema support a routing guarantee, but a model
                        whose providers lack it becomes unroutable rather than degrading — and that
                        arrives as “no endpoints found”, not as the 400 the per-model fallback
                        keys on.
                      </span>
                    </span>
                  </label>
                </>
              )}
            </div>
          </details>

          <div className="grid grid-cols-1 sm:grid-cols-2 gap-4">
            <Field
              label="Model"
              required={config.llmProvider !== 'SPECTRA'}
              id={fieldIds.llmModel}
              error={errors.llmModel}
              description={
                config.llmProvider === 'SPECTRA' ? 'Served by SpectraLLM — not sent per request.'
                : config.llmProvider === 'OPENROUTER' ? 'OpenRouter model slug, in the form vendor/model.'
                : undefined}
            >
              {p => (
                /*
                 * Un Combobox sur OpenRouter, un Input ailleurs : la liste n'existe que là où une
                 * passerelle publie un catalogue. Non contraignant dans les deux cas — un slug tout
                 * neuf doit rester saisissable, la liste ne fait qu'éviter de le retaper de mémoire.
                 */
                config.llmProvider === 'OPENROUTER' ? (
                  <Combobox
                    {...p}
                    className="font-mono"
                    value={config.llmModel}
                    onChange={value => set('llmModel', value)}
                    options={modelSlugs}
                    placeholder={providerDefaults.OPENROUTER?.model ?? 'vendor/model'}
                  />
                ) : (
                  <Input
                    {...p}
                    className="font-mono"
                    value={config.llmModel}
                    onChange={e => set('llmModel', e.target.value)}
                    placeholder={
                      config.llmProvider === 'SPECTRA' ? 'Served by SpectraLLM (ignored)'
                      : providerDefaults[config.llmProvider]?.model || 'model name'}
                    disabled={config.llmProvider === 'SPECTRA'}
                    autoComplete="off"
                    spellCheck={false}
                  />
                )
              )}
            </Field>
            <Field
              label="Base URL"
              required={config.llmProvider !== 'OLLAMA'}
              id={fieldIds.llmBaseUrl}
              error={errors.llmBaseUrl}
            >
              {p => (
                <Input
                  {...p}
                  className="font-mono"
                  value={config.llmBaseUrl}
                  onChange={e => set('llmBaseUrl', e.target.value)}
                  placeholder={
                    config.llmProvider === 'OLLAMA' ? 'http://localhost:11434/v1'
                    : config.llmProvider === 'SPECTRA' ? 'http://localhost:8080'
                    : config.llmProvider === 'OPENROUTER' ? 'https://openrouter.ai/api/v1'
                    : 'https://...'}
                  autoComplete="off"
                  spellCheck={false}
                />
              )}
            </Field>
            <Field
              label="API Key"
              required={API_KEY_REQUIRED.has(config.llmProvider) && !config.llmApiKeyConfigured}
              id={fieldIds.llmApiKey}
              error={errors.llmApiKey}
              /*
               * Même règle que les mots de passe SSL et le secret Confluent, dont ce champ se
               * distinguait sans raison. « in memory » n'était plus vrai depuis que les réglages
               * survivent à un redémarrage, et il manquait le second état : un champ tapé puis
               * effacé *efface* la clé enregistrée — ce que faisait déjà, en silence, le passage à
               * Ollama ou SpectraLLM, qui vide ce champ.
               */
              description={secretHint(config.llmApiKeyConfigured, config.llmApiKey, 'API key')
                ?? (config.llmApiKeyConfigured ? undefined : 'No key is configured.')}
            >
              {p => (
                <PasswordInput
                  {...p}
                  value={config.llmApiKey ?? ''}
                  onChange={e => set('llmApiKey', e.target.value)}
                  placeholder={
                    config.llmProvider === 'OLLAMA' || config.llmProvider === 'SPECTRA'
                      ? 'Optional for local deployments'
                      : config.llmProvider === 'OPENROUTER' ? 'sk-or-v1-…' : 'sk-…'}
                />
              )}
            </Field>
            <Field label="Max Tokens" id={fieldIds.llmMaxTokens} error={errors.llmMaxTokens} description="256 – 32768">
              {p => (
                <NumberInput {...p} min={256} max={32768} fallback={4096}
                  value={config.llmMaxTokens}
                  onChange={v => setNumber('llmMaxTokens', v)} />
              )}
            </Field>
            <Field label="Live Window Size" id={fieldIds.llmSnapshotWindowSize} error={errors.llmSnapshotWindowSize} description="Messages per analysis window (10 – 5000)">
              {p => (
                <NumberInput {...p} min={10} max={5000} fallback={100}
                  value={config.llmSnapshotWindowSize}
                  onChange={v => setNumber('llmSnapshotWindowSize', v)} />
              )}
            </Field>
            <Field label="Live Window Timeout (s)" id={fieldIds.llmSnapshotWindowTimeoutSeconds} error={errors.llmSnapshotWindowTimeoutSeconds} description="Flush the window after this delay (5 – 600)">
              {p => (
                <NumberInput {...p} min={5} max={600} fallback={30}
                  value={config.llmSnapshotWindowTimeoutSeconds}
                  onChange={v => setNumber('llmSnapshotWindowTimeoutSeconds', v)} />
              )}
            </Field>
          </div>

          {config.llmProvider === 'SPECTRA' && (
            <label className="mt-4 flex items-start gap-3 rounded-lg border border-outline-variant/60 bg-background-dark/20 px-4 py-3 cursor-pointer">
              <Checkbox
                checked={config.llmUseRag ?? false}
                onChange={value => setConfig(prev => ({ ...prev, llmUseRag: value }))}
                className="mt-0.5"
              />
              <span>
                <span className="block text-xs font-bold text-on-surface">Enrich audit with SpectraLLM RAG</span>
                <span className="block text-[10px] text-on-surface-variant mt-0.5">
                  When enabled, the audit prompt is answered with hybrid retrieval over SpectraLLM's
                  ingested corpus. Leave off to ground the audit solely on the sampled Kafka messages.
                </span>
              </span>
            </label>
          )}

          {config.llmProvider === 'SPECTRA' && config.llmUseRag && (
            <Field
              label="SpectraLLM Collection"
              className="mt-3"
              description="Optional — the ChromaDB collection to retrieve from. Leave blank for SpectraLLM's default."
            >
              {p => (
                <Input {...p} className="font-mono" value={config.llmCollection ?? ''}
                  onChange={e => set('llmCollection', e.target.value)}
                  placeholder="Default collection" autoComplete="off" spellCheck={false} />
              )}
            </Field>
          )}

          <div className="mt-4 flex flex-wrap items-end gap-4">
            <Field label="Request Timeout (s)" className="w-40">
              {p => (
                <NumberInput {...p} min={5} max={600} fallback={60}
                  value={config.llmRequestTimeoutSeconds ?? 60}
                  onChange={v => setNumber('llmRequestTimeoutSeconds', v)} />
              )}
            </Field>
            <Button type="button" variant="outline" icon={llmTesting ? undefined : 'network_check'} loading={llmTesting} onClick={handleTestLlm} disabled={llmTesting}>
              {llmTesting ? 'Testing LLM…' : 'Test LLM'}
            </Button>
          </div>

          {/*
            * La liste restreinte : les modèles qui savent faire ce travail, le moins cher d'abord.
            * Paresseuse et derrière un geste — elle ne sert qu'à qui choisit un modèle. OpenRouter
            * seul, parce que c'est le seul fournisseur ici qui publie un catalogue.
            */}
          {config.llmProvider === 'OPENROUTER' && (
            <div className="mt-3">
              <div className="flex flex-wrap items-center gap-3">
                <Button type="button" variant="outline" icon="format_list_bulleted"
                  loading={modelsLoading} onClick={toggleModelPicker}
                  aria-expanded={modelsOpen} aria-controls="llm-model-picker">
                  {modelsOpen ? 'Hide models' : 'Browse models'}
                </Button>
                {modelsOpen && (
                  <label className="flex items-center gap-2 text-[12px] text-on-surface-variant">
                    <Checkbox
                      checked={includeUnconstrained}
                      onChange={applyUnconstrained}
                      aria-label="Include models without schema support"
                    />
                    Include models without schema support
                  </label>
                )}
              </div>

              {modelsOpen && (
                <div id="llm-model-picker" className="mt-2 rounded-lg border border-outline-variant
                  bg-surface-container-low px-4 py-3 text-[12px]">
                  <p className="text-on-surface-variant">{shortlistState.text}</p>
                  {shortlistState.tone === 'ready' && (
                    <>
                      <ul className="mt-2 space-y-1 max-h-72 overflow-y-auto">
                        {models?.models.map(option => (
                          <li key={option.id}>
                            <button
                              type="button"
                              onClick={() => set('llmModel', option.id)}
                              aria-current={config.llmModel === option.id}
                              className={`w-full text-left rounded-md px-2 py-1.5 transition-colors
                                hover:bg-surface-container ${
                                config.llmModel === option.id ? 'bg-surface-container' : ''}`}
                            >
                              <span className="font-mono text-on-surface">{option.id}</span>
                              {option.name && (
                                <span className="ml-2 text-on-surface-variant">{option.name}</span>
                              )}
                              <span className="block text-on-surface-variant">
                                {/* La politique lue est celle qui *tourne* (`inForce`), jamais
                                    celle du formulaire : la liste décrit le point d'accès
                                    enregistré, et mélanger les deux mettrait l'avertissement d'une
                                    configuration sous les modèles d'une autre. */}
                                {describeOption(option,
                                  inForce?.llmDataRetentionRefused === true).join(' · ')}
                              </span>
                            </button>
                          </li>
                        ))}
                      </ul>
                      {/*
                        * L'étiquette est obligatoire, pas décorative : partout ailleurs ici un
                        * montant affiché est un montant qu'un fournisseur a assumé, et celui-ci
                        * n'en est pas un.
                        */}
                      <p className="mt-2 text-[11px] text-on-surface-variant">{PROJECTION_NOTE}</p>
                    </>
                  )}
                </div>
              )}
            </div>
          )}

          {connectionIsStale && (
            <p className="mt-2 text-[12px] text-on-surface-variant">
              Test tries the model against the connection currently in force
              ({inForce?.llmProvider}). Save to test a different provider or endpoint.
            </p>
          )}

          {llmTestResult && (
            <div className={`mt-3 rounded-lg border px-4 py-3 text-[12px] flex items-start gap-2 ${
              llmTestResult.ok
                ? 'border-success/30 bg-success/10 text-success'
                : 'border-error/30 bg-error/10 text-error'
            }`}>
              <span className="material-symbols-outlined text-[16px] mt-0.5">
                {llmTestResult.ok ? 'check_circle' : 'error'}
              </span>
              <span className="break-words">{llmTestResult.message}</span>
            </div>
          )}

          {/*
            * Ce que la passerelle dit du modèle, sous le verdict de joignabilité et distinct de
            * lui : « quelque chose répond » et « ce modèle sait faire ce travail » sont deux
            * questions, et c'est la seconde qu'on se pose quand Process Mining se comporte mal.
            * Affiché aussi quand l'appel a échoué — une 404 sur un modèle qui n'émet que des
            * embeddings s'explique ici et nulle part ailleurs.
            */}
          {modelNotes.length > 0 && (
            <div className={`mt-2 rounded-lg border px-4 py-3 text-[12px] ${
              hasModelWarning(modelNotes)
                ? 'border-warning/30 bg-warning/10'
                : 'border-outline-variant bg-surface-container-low'
            }`} data-testid="llm-model-check">
              {modelIdentity && (
                <div className="font-medium text-on-surface mb-1.5">{modelIdentity}</div>
              )}
              <ul className="space-y-1.5">
                {modelNotes.map((note, index) => (
                  <li key={index} className="flex items-start gap-2">
                    <span className={`material-symbols-outlined text-[16px] mt-px ${
                      note.tone === 'warning' ? 'text-warning'
                        : note.tone === 'ok' ? 'text-success' : 'text-on-surface-variant'
                    }`}>
                      {note.tone === 'warning' ? 'warning'
                        : note.tone === 'ok' ? 'check_circle' : 'help'}
                    </span>
                    <span className="break-words text-on-surface-variant">{note.text}</span>
                  </li>
                ))}
              </ul>
            </div>
          )}
        </div>
      </div>

      {/* Error */}
      {error && (
        <div className="rounded-lg border border-error/25 bg-error/10 p-3 flex items-center gap-2 text-error text-[13px]" role="alert">
          <span className="material-symbols-outlined text-[18px]">error</span>
          {error}
        </div>
      )}

      {/* Récapitulatif : les détails sont sur les champs, ceci ne fait que compter et orienter. */}
      {errorCount > 0 && (
        <div className="rounded-lg border border-error/25 bg-error/10 p-3 flex items-center gap-2 text-error text-[13px]" role="alert">
          <span className="material-symbols-outlined text-[18px]">error</span>
          {errorCount === 1 ? '1 field needs attention.' : `${errorCount} fields need attention.`}
        </div>
      )}

      {/*
        Reste à l'écran, contrairement au « Saved! » de trois secondes sur le bouton : ce qui est
        dit ici, c'est que l'enregistrement n'a pas obtenu ce qu'il promettait.
      */}
      {saveNote && (
        <div className="rounded-lg border border-warning/25 bg-warning/10 p-3 flex items-start gap-2 text-warning text-[13px]" role="status">
          <span className="material-symbols-outlined text-[18px] shrink-0">info</span>
          <span>{saveNote}</span>
        </div>
      )}

      {/* Actions */}
      <div className="flex items-center justify-between pt-2">
        {/*
          Le nom dit ce que le bouton fait. Il enregistre — c'est `POST /api/config` qui repointe le
          cluster, et il n'existe pas de sonde sans lui : une adresse de courtier prise dans le
          corps d'une requête serait la contrefaçon de requête côté serveur que `test-llm` refuse
          par construction. Le geste est donc légitime ; ce qui ne l'était pas, c'est de l'appeler
          « Test connection » sur une page où l'autre bouton s'appelle « Save configuration ».
        */}
        <Button type="button" variant="outline" icon={testing ? undefined : 'wifi_tethering'} loading={testing} onClick={handleTestConnection} disabled={testing || saving}
          title="Applies the form, then reports whether the broker answers.">
          {testing ? 'Applying…' : 'Apply & test connection'}
        </Button>
        <div className="flex items-center gap-3">
          {draftConflict && (
            <span className="text-[12px] text-warning">
              Also open in another tab — the last one to type owns the saved draft
            </span>
          )}
          {dirty && !saving && (
            <span className="text-[12px] text-on-surface-variant">Unsaved changes</span>
          )}
          <Button
            type="submit"
            variant="primary"
            icon={saving ? undefined : saveSuccess ? 'check_circle' : 'save'}
            loading={saving}
            disabled={saving || testing}
          >
            {saving ? 'Saving…' : saveSuccess ? 'Saved!' : 'Save configuration'}
          </Button>
        </div>
      </div>
    </form>
  );
};

export default Config;
