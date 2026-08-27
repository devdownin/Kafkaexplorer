// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors

import React, { useState, useEffect, useMemo, useRef, useCallback } from 'react';
import axios from 'axios';
import {
  PageHeader, Button, CardSkeleton, Checkbox, Combobox, ErrorPanel, Field, Input, NumberInput,
  PasswordInput, useConfirm, useUnsavedGuard,
} from '../components/ui';
import { clearDraft, readDraft, useDraftConflict, writeDraft } from '../draftStore';
import { draftableOnly, mergeDraft } from './configDraft';
import {
  describePersistence, describeSaveOutcome, splitPersistence,
  type SettingsPersistence,
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
   * Réglages de routage OpenRouter, servis par le serveur et non éditables ici — comme
   * `llmStructuredOutput`, ils se posent dans la configuration du déploiement. Le formulaire les
   * renvoie tels quels, donc ils ne comptent jamais comme « saisis ».
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
const secretHint = (configured?: boolean, typed?: string): string | undefined => {
  if (!configured) return undefined;
  // Jamais touché : le champ n'est pas envoyé du tout, donc le mot de passe en vigueur reste.
  if (typed === undefined) return 'One is set. It is not shown — leave this field alone to keep it.';
  // Tapé puis effacé : la chaîne vide part et efface le mot de passe. Dire « laissez vide pour le
  // conserver » ici décrirait exactement l'inverse de ce que ferait l'enregistrement.
  if (typed === '') return 'Saving now clears the password that is set.';
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
  const persistenceNotice = useMemo(() => describePersistence(persistence), [persistence]);
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
  /** Le formulaire pointe ailleurs que ce qui tourne : le bandeau décrit encore l'ancien. */
  const policyIsStale = inForce != null && inForce.llmProvider !== config.llmProvider;

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
  const applyConfig = async (force: boolean) => {
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
  };

  const handleSave = async () => {
    if (!checkBeforeSubmit()) return;
    setSaving(true);
    setError(null);
    setSaveNote(null);
    setSaveSuccess(false);
    try {
      await applyConfig(false);
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
          await applyConfig(true);
        } catch (forced) {
          setError(refusal(forced, 400) ?? 'Failed to save configuration.');
        }
      }
    } finally {
      setSaving(false);
    }
  };

  const handleTestConnection = async () => {
    if (!checkBeforeSubmit()) return;
    setTesting(true);
    setTestResult(null);
    try {
      const res = await axios.post<ClusterConfig & SettingsPersistence>('/api/config', config);
      const { settings, persistence: kept } = splitPersistence(res.data);
      setConfig(prev => ({ ...prev, ...settings }));
      setPersistence(kept);
      setTestResult(settings.isConnected ?? false);
    } catch {
      setTestResult(false);
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
    if (!checkBeforeSubmit()) return;
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
  const checkBeforeSubmit = (): boolean => {
    const found = validateConfig();
    setErrors(found);
    const firstInvalid = FIELD_ORDER.find(key => found[key]);
    if (!firstInvalid) return true;
    // Le champ peut être dans une section masquée par le mode courant — d'où le garde-fou.
    document.getElementById(fieldIds[firstInvalid])?.focus();
    return false;
  };

  const set = (key: keyof ClusterConfig, value: string) => {
    setConfig(prev => ({ ...prev, [key]: value }));
    clearError(key);
  };
  const setNumber = (key: keyof ClusterConfig, value: number) => {
    setConfig(prev => ({ ...prev, [key]: value }));
    clearError(key);
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
        <div>
          <p className="font-medium">{persistenceNotice.text}</p>
          {persistenceNotice.detail && (
            <p className="text-on-surface-variant/80 mt-0.5">{persistenceNotice.detail}</p>
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
          <fieldset>
            <legend className="block text-[12px] font-medium text-on-surface-variant mb-1.5">Security Mode</legend>
            <div className="grid grid-cols-3 gap-3">
              {MODES.map(mode => (
                <button
                  key={mode.value}
                  type="button"
                  aria-pressed={config.mode === mode.value}
                  onClick={() => set('mode', mode.value)}
                  className={`p-3 rounded-lg border text-left transition-all ${
                    config.mode === mode.value
                      ? 'border-primary bg-primary/10 text-on-surface'
                      : 'border-outline-variant bg-surface-container-low text-on-surface-variant hover:border-outline'
                  }`}
                >
                  <p className="text-xs font-bold">{mode.label}</p>
                  <p className="text-[10px] text-on-surface-variant mt-0.5">{mode.description}</p>
                </button>
              ))}
            </div>
          </fieldset>
        </div>
      </div>

      {/* SSL Config */}
      {config.mode === 'SSL' && (
        <div className="rounded-xl bg-surface-container ring-1 ring-white/[0.045] overflow-hidden">
          <div className="p-4 border-b border-outline-variant/60 flex items-center gap-3">
            <span className="material-symbols-outlined text-primary">lock</span>
            <h2 className="font-bold text-on-surface">SSL / mTLS Settings</h2>
          </div>
          <div className="p-5 grid grid-cols-2 gap-4">
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
          <div className="p-5 grid grid-cols-2 gap-4">
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
          <fieldset>
            <legend className="block text-[12px] font-medium text-on-surface-variant mb-1.5">Provider</legend>
            <div className="grid grid-cols-2 sm:grid-cols-3 gap-3">
              {LLM_PROVIDERS.map(provider => (
                <button
                  key={provider.value}
                  type="button"
                  aria-pressed={config.llmProvider === provider.value}
                  onClick={() => { applyLlmProvider(provider.value); setErrors({}); }}
                  className={`p-3 rounded-lg border text-left transition-all ${
                    config.llmProvider === provider.value
                      ? 'border-primary bg-primary/10 text-on-surface'
                      : 'border-outline-variant bg-surface-container-low text-on-surface-variant hover:border-outline'
                  }`}
                >
                  <p className="text-xs font-bold">{provider.label}</p>
                  <p className="text-[10px] text-on-surface-variant mt-0.5">{provider.description}</p>
                </button>
              ))}
            </div>
          </fieldset>

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

          <div className="grid grid-cols-2 gap-4">
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
              description={config.llmApiKeyConfigured
                ? 'A key is currently configured in memory — leave blank to keep it.'
                : 'No key configured in memory.'}
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
        <Button type="button" variant="outline" icon={testing ? undefined : 'wifi_tethering'} loading={testing} onClick={handleTestConnection} disabled={testing || saving}>
          {testing ? 'Testing…' : 'Test connection'}
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
