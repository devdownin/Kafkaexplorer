// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors

/**
 * Les formes de réponse de l'API, en un seul endroit, adossées aux records Java.
 *
 * Le front affirmait ses types au point d'appel : `axios.get<{ samples: string[] }>(…)`. Une
 * annotation posée à la main n'est pas une vérification, c'est un vœu — TypeScript la croit sur
 * parole. Le jour où `GET /api/topic/{name}` a cessé de renvoyer des chaînes pour renvoyer des
 * records, rien n'a échoué à la compilation : la page Compare a simplement rendu un objet comme
 * enfant React et s'est effondrée sur l'erreur #31, en production, longtemps après.
 *
 * Deux règles pour que ça ne se reproduise pas :
 *
 *  1. **Un seul endroit.** Une forme de réponse se déclare ici, pas dans la page qui la consomme.
 *     Trois pages qui décrivent chacune la même réponse, ce sont trois occasions de dériver.
 *  2. **Vérifié contre la source de vérité.** `docs/check-api-types.py` résout chaque interface de
 *     ce fichier contre son record Java — mêmes champs, types correspondants — et échoue en CI
 *     sinon. C'est le même principe que `check-config-table.py` pour les variables d'environnement :
 *     la documentation d'un contrat se périme en silence, donc on la fait vérifier par une machine.
 *
 * Le marqueur `@java` de chaque interface est ce que lit le script. Ajouter une interface sans lui
 * la laisse non vérifiée — ce qui reste possible, mais devient une décision visible.
 */

/** @java MessageFormat */
export type MessageFormat = 'JSON' | 'XML' | 'AVRO' | 'AUTO';

/**
 * Un enregistrement Kafka tel que l'UI le voit.
 *
 * @java TopicMessage
 */
export interface TopicMessage {
  partition: number;
  offset: number;
  timestamp: number;
  key: string | null;
  value: string | null;
  headers: Record<string, string | null>;
  /** Taille de la valeur avant troncature — c'est elle qui permet de dire « tronqué ». */
  valueBytes: number;
  truncated: boolean;
}

/** @java TopicDescriptor */
export interface TopicDescriptor {
  name: string;
  partitions: number;
  minOffsets: Record<number, number>;
  maxOffsets: Record<number, number>;
  detectedFormat: MessageFormat | null;
  estimatedSize: number;
}

/**
 * `GET /api/topic/{name}`.
 *
 * `samples` a été un `string[]`. Il ne l'est plus depuis que la recherche de topic existe, et
 * c'est ce changement-là qui a tué la page Compare pendant des mois.
 *
 * @java TopicDetailResponse
 */
export interface TopicDetailResponse {
  topic: TopicDescriptor;
  format: MessageFormat | null;
  schema: Record<string, string>;
  ddl: string | null;
  samples: TopicMessage[];
}

/** @java FlinkJobSummary */
export interface FlinkJobSummary {
  queryId: string;
  flinkJobId: string;
  statementType: string;
  status: string;
  sql: string;
  startedAt: number;
  endedAt: number | null;
  cancelRequested: boolean;
}

/**
 * `POST /api/query/run-sync`.
 *
 * @java QueryResult
 */
export interface QueryResult {
  columns: string[];
  rows: Record<string, unknown>[];
  /** Durée mesurée côté serveur. L'interface locale de l'éditeur l'omettait, et personne ne le savait. */
  durationMs: number;
  error: string | null;
  tableRegistered: boolean;
  /** `KAFKA_DIRECT` ou `FLINK` — null sur les chemins d'erreur. */
  engine: string | null;
  /**
   * Réserves du moteur sur un résultat par ailleurs réussi : au premier chef les prédicats WHERE
   * que le lecteur direct n'a pas su appliquer. Les taire présenterait un scan non filtré comme
   * un résultat filtré.
   */
  warnings: string[];
}

/**
 * `GET /api/dashboard` — sondé toutes les 30 s par le shell applicatif.
 *
 * @java DashboardResponse
 */
export interface DashboardResponse {
  topics: string[];
  topicSizes: Record<string, number>;
  totalMessages: number;
  tables: string[];
  jobs: FlinkJobSummary[];
  health: boolean;
  /** Nom d'affichage choisi au déploiement — pas une information sur le broker. */
  clusterName: string;
  /** L'adresse réellement utilisée, y compris après un repointage à chaud. */
  bootstrapServers: string;
  topicLastMessages: Record<string, number>;
  /**
   * Le préfixe résolu des topics que l'application s'écrit à elle-même — vide sur un déploiement
   * qui n'en pose pas. Le navigateur pose la même question que le serveur (« est-ce un des
   * nôtres ? ») et ne peut y répondre qu'avec cette forme-là.
   */
  internalTopicPrefix: string;
}

/**
 * La série d'activité d'un topic — la sparkline de la colonne « Activity » du tableau de bord.
 *
 * Elle est mesurée à partir des **offsets**, pas des enregistrements : le serveur résout chaque
 * frontière de bucket en offset (`listOffsets(forTimestamp)`) et soustrait. Un bucket compte donc
 * des offsets produits, ce qui n'est pas la même chose que le nombre de messages présents dans le
 * topic aujourd'hui — `topicSizes` répond à cette seconde question.
 *
 * Deux champs disent ce que la courbe ne mesure pas, et ils existent pour la même raison que
 * partout ailleurs ici : une mesure impossible ne doit pas revenir en zéro. `coveredFromMs` est
 * l'instant à partir duquel la série est complète (avant lui, la rétention a supprimé des
 * enregistrements, donc les buckets sont des planchers), et `partitionsMeasured` en face de
 * `partitionsTotal` dit si toutes les partitions ont répondu.
 *
 * @java TopicActivity
 */
export interface TopicActivity {
  topic: string;
  windowStartMs: number;
  windowEndMs: number;
  bucketMs: number;
  /** Un compte par bucket, du plus ancien au plus récent. Vide quand `available` est faux. */
  counts: number[];
  total: number;
  /** `null` quand toute la fenêtre est couverte ; sinon, ce qui précède est un plancher. */
  coveredFromMs: number | null;
  partitionsMeasured: number;
  partitionsTotal: number;
  /** Faux quand rien n'a pu être lu — distinct d'un topic sans trafic. */
  available: boolean;
  note: string | null;
}

/**
 * `GET /api/dashboard/activity?topics=…` — une série par topic, sur une fenêtre commune.
 *
 * La fenêtre est déclarée ici et pas par topic : toutes les séries sont découpées sur les mêmes
 * frontières, ce qui est la condition pour que vingt-cinq sparklines dans une colonne veuillent
 * dire la même chose. Un topic demandé et absent de `topics` n'a pas été mesuré — le budget de
 * lecture a mordu, ou le cluster ne le connaît pas — et `warnings` le nomme, sans quoi une courbe
 * absente se lirait comme un topic sans trafic.
 *
 * @java TopicActivityResponse
 */
export interface TopicActivityResponse {
  topics: Record<string, TopicActivity>;
  windowStartMs: number;
  windowEndMs: number;
  bucketMs: number;
  buckets: number;
  available: boolean;
  warnings: string[];
}

/**
 * Où en est un groupe de consommateurs sur une partition.
 *
 * `committedOffset` et `lag` sont nullables à dessein : un groupe qui n'a jamais commité sur une
 * partition n'y a pas de position, et rendre ça par `0` se lirait « à jour au tout début » —
 * l'exact contraire de « personne ne lit cette partition ».
 *
 * @java PartitionLag
 */
export interface PartitionLag {
  partition: number;
  committedOffset: number | null;
  endOffset: number;
  /** `endOffset - committedOffset`. Peut être négatif : un reset d'offset laisse ça derrière lui. */
  lag: number | null;
  memberId: string | null;
  clientId: string | null;
  host: string | null;
}

/**
 * Le retard d'un groupe sur un topic.
 *
 * @java ConsumerGroupLag
 */
export interface ConsumerGroupLag {
  groupId: string;
  type: string;
  state: string;
  members: number;
  assignedMembers: number;
  /** `false` quand le groupe n'a pas pu être décrit : `members` est alors inconnu, pas nul. */
  membersKnown: boolean;
  totalLag: number;
  partitionsWithoutCommit: number;
  partitions: PartitionLag[];
  error: string | null;
}

/**
 * `GET /api/topic/{name}/consumers` — qui lit ce topic, et où il en est.
 *
 * Les compteurs de portée ne sont pas décoratifs : une liste vide veut dire « personne ne lit ce
 * topic » ou « on n'a regardé que deux cents groupes sur trois mille », et `available` sépare les
 * deux d'un troisième cas qui leur ressemblait à s'y méprendre — la lecture qui a échoué.
 *
 * @java TopicConsumers
 */
export interface TopicConsumers {
  topic: string;
  groups: ConsumerGroupLag[];
  groupsExamined: number;
  groupsEligible: number;
  groupsInCluster: number;
  truncated: boolean;
  available: boolean;
  warnings: string[];
}

/**
 * Le retard d'un groupe sur une partition, exprimé en temps.
 *
 * `lagMs` à `null` veut dire « pas mesuré » — jamais « à jour ». Zéro est une affirmation, et une
 * mesure impossible ne doit pas pouvoir la produire ; `note` dit pourquoi.
 *
 * @java PartitionTimeLag
 */
export interface PartitionTimeLag {
  partition: number;
  committedOffset: number | null;
  endOffset: number;
  recordLag: number | null;
  lagMs: number | null;
  oldestWaitingTimestamp: number | null;
  note: string | null;
}

/**
 * `GET /api/topic/{name}/time-lag?group=…` — l'âge du plus vieux message que ce groupe n'a pas lu.
 *
 * Les compteurs situent la lecture : un maximum pris sur la moitié des partitions est un plancher,
 * et `complete` est ce qui permet de le dire au lieu de le laisser passer pour un maximum.
 *
 * @java TopicTimeLag
 */
export interface TopicTimeLag {
  topic: string;
  groupId: string;
  partitions: PartitionTimeLag[];
  maxLagMs: number | null;
  avgLagMs: number | null;
  partitionsMeasured: number;
  partitionsCaughtUp: number;
  partitionsWithoutCommit: number;
  partitionsUnknown: number;
  available: boolean;
  error: string | null;
  warnings: string[];
}

/**
 * `POST /api/topic/{name}/search` — une passe de recherche bornée, et ce qu'elle a couvert.
 *
 * Les compteurs de couverture font partie du contrat : une recherche n'est jamais silencieusement
 * partielle, donc `scanned` / `stopReason` / `exhausted` décrivent la passe et `nextCursor` permet
 * de la reprendre. `elapsedMs` a déjà manqué une fois dans une fixture de capture d'écran, ce qui
 * affichait « 4 318 scanned in NaNs » au lieu d'échouer.
 *
 * `stopReason` est plus étroit ici que côté Java, où il n'existe pas de type union : le serveur
 * n'émet que ces cinq valeurs et l'UI branche dessus.
 *
 * @java TopicSearchResponse
 */
export interface TopicSearchResponse {
  hits: TopicMessage[];
  scanned: number;
  matched: number;
  elapsedMs: number;
  exhausted: boolean;
  stopReason: 'MAX_HITS' | 'MAX_SCAN' | 'TIMEOUT' | 'EXHAUSTED' | 'ERROR';
  nextCursor: Record<string, number>;
  warnings: string[];
}

/**
 * `GET /api/query/init` — le catalogue de l'éditeur SQL, et pourquoi il est vide quand il l'est.
 *
 * Les deux champs d'erreur sont le correctif d'un `catch` vide : un broker injoignable, une adresse
 * de bootstrap fausse et un runtime Flink encore en démarrage rendaient tous « Engine offline ·
 * 0 tables · 0 topics », sans rien pour les distinguer. Les deux sondes sont indépendantes — l'une
 * qui échoue ne doit pas vider l'autre.
 *
 * @java QueryInitResponse
 */
export interface QueryInitResponse {
  topics: string[];
  tables: string[];
  health: boolean;
  kafkaError: string | null;
  flinkError: string | null;
}

/**
 * `GET /api/metrics` — une métrique Prometheus adossée à une requête SQL.
 *
 * `lastValue` à `null` laisse la métrique en statut `pending` : la cause habituelle est un SQL
 * d'agrégat sans alias `AS metric_value`, pas une ressource indisponible.
 *
 * @java MetricConfig
 */
export interface MetricConfig {
  id: string;
  name: string;
  type: string;
  /** `null` sur une métrique de gabarit : elle n'a pas de SQL, ses paramètres tiennent lieu de requête. */
  sql: string | null;
  description: string;
  warningThreshold: number | null;
  criticalThreshold: number | null;
  lastValue: number | null;
  lastUpdateTime: number | null;
  errorMessage: string | null;
  history: number[];
  lastSummary: Record<string, unknown> | null;
  createTableSql: string | null;
  templateType: string | null;
  templateParams: Record<string, unknown> | null;
  executionMode: string | null;
  labelTopic: string | null;
  labelFields: string[] | null;
  /**
   * Les valeurs qui *composent* `lastValue`, une série par nom, alignées index par index sur
   * `history`. `null` à un index veut dire « pas mesurée à ce rafraîchissement » — jamais zéro,
   * qui dessinerait une chute qui n'a pas eu lieu.
   */
  componentHistory: Record<string, (number | null)[]> | null;
}

/** @java MetricSuggestionSource */
export type MetricSuggestionSource = 'AUDIT' | 'STREAM_FLOW' | 'LINEAGE' | 'PROCESS_MINING';

/**
 * Les topics que la proposition lit tiennent-ils encore quelque chose ? Question posée au cluster
 * au moment de la dérivation, pas au moment de l'observation.
 *
 * `ABSENT` n'arrive jamais jusqu'ici : une telle proposition est écartée côté serveur et comptée
 * dans les `notes`. `UNKNOWN` veut dire « on n'a pas pu demander » — jamais « il n'y a rien » —, ce
 * qui est exactement la distinction que le panneau doit rendre : une carte non vérifiée s'affiche
 * comme avant, elle ne s'affiche pas comme une carte sans données.
 *
 * @java MetricDataState
 */
export type MetricDataState = 'POPULATED' | 'EMPTY' | 'ABSENT' | 'UNKNOWN';

/**
 * `POST /api/metrics/suggestions` — un KPI contextuel proposé, avec ce sur quoi il repose.
 *
 * `evidence` n'est jamais vide et `thresholdBasis` dit d'où sortent les seuils : c'est toute la
 * différence entre une proposition et le bandeau « 99,98 % de disponibilité » que la page d'aide
 * des métriques affichait sans que rien ne l'ait jamais mesuré.
 *
 * @java MetricSuggestion
 */
export interface MetricSuggestion {
  id: string;
  source: MetricSuggestionSource;
  title: string;
  rationale: string;
  evidence: string[];
  thresholdBasis: string | null;
  caveats: string[];
  alreadyConfigured: boolean;
  existingMetricName: string | null;
  /** Ce que le cluster a répondu sur les topics lus — voir `MetricDataState`. */
  dataState: MetricDataState;
  metric: MetricConfig;
}

/**
 * `POST /api/metrics/suggestions` — les propositions et l'état des observations dont elles sortent.
 *
 * Une liste vide veut dire deux choses opposées — « rien n'a encore été mesuré » et « ce qui a été
 * mesuré n'appelle aucun KPI ». Les champs d'observation sont ce qui les sépare.
 *
 * @java MetricSuggestions
 */
export interface MetricSuggestions {
  suggestions: MetricSuggestion[];
  auditAvailable: boolean;
  auditId: string | null;
  auditTimestamp: number | null;
  auditSource: string | null;
  auditTopics: number;
  flowChainsSubmitted: number;
  /** Un processus mesuré a-t-il été renvoyé ? Compté à part des cartes qu'il produit. */
  processMeasured: boolean;
  notes: string[];
}

/**
 * Une trace Stream Flow telle que le navigateur l'a gardée, renvoyée au serveur pour que la même
 * dérivation réponde pour les deux familles d'observations.
 *
 * @java FlowChainEvidence
 */
export interface FlowChainEvidence {
  messageKey: string | null;
  searchPath: string | null;
  tracedAt: number | null;
  hops: FlowChainHop[];
}

/** @java FlowChainHop */
export interface FlowChainHop {
  topic: string;
  firstTimestamp: number | null;
  latencyFromPreviousMs: number | null;
  occurrences: number | null;
}

/**
 * Le processus mesuré tel que le navigateur l'a gardé, renvoyé pour qu'un KPI puisse en être
 * dérivé. Délibérément plus étroit que `ProcessModel` : seules les transitions et les reprises
 * sont lues côté serveur, et renvoyer les variantes reviendrait à faire traverser une frontière à
 * des données que personne n'utilise.
 *
 * @java ProcessModelEvidence
 */
export interface ProcessModelEvidence {
  measuredAt: number | null;
  cases: number | null;
  windowStartMs: number | null;
  windowEndMs: number | null;
  eventTimeSource: string | null;
  transitions: MeasuredTransition[];
  repeats: MeasuredRepeat[];
}

/**
 * Une succession directe et ce qu'elle a coûté. `from` et `to` sont des libellés d'activité — des
 * noms de topics, sauf sur un topic dont le statut a été mappé ; les ramener à un topic est la
 * règle de `ProcessModelBuilder` et s'applique côté serveur.
 *
 * @java MeasuredTransition
 */
export interface MeasuredTransition {
  from: string;
  to: string;
  occurrences: number | null;
  cases: number | null;
  p50Ms: number | null;
  p95Ms: number | null;
  maxMs: number | null;
}

/** Une étape qu'un même cas a repassée — redélivrance, reprise ou boucle métier.
 *
 * @java MeasuredRepeat
 */
export interface MeasuredRepeat {
  activity: string;
  casesAffected: number | null;
  maxOccurrencesInOneCase: number | null;
}

/*
 * ─── Réponses ad hoc ──────────────────────────────────────────────────────────────────────────
 *
 * Ce qui suit n'a délibérément pas de marqueur `@java` : ces endpoints répondent par un `Map.of(…)`
 * construit dans le contrôleur, pas par un record du domaine, donc il n'existe rien contre quoi
 * `check-api-types.py` pourrait les résoudre — et un marqueur qui ne vérifie rien vaut moins que
 * pas de marqueur, il fait croire à un contrôle.
 *
 * Les nommer ici sert quand même à quelque chose : c'est le motif `axios.get<{ … }>(…)` déclaré au
 * point d'appel qui a tué la page Compare, parce qu'une forme écrite à la main au milieu d'un
 * composant n'est relue par personne. Rassemblées, elles se comparent au contrôleur d'un coup
 * d'œil. Le jour où l'un de ces endpoints reçoit un vrai record, l'interface déménage au-dessus
 * avec son marqueur.
 */

/** `GET /api/query/ddl-preview` — l'un ou l'autre, jamais les deux. */
/** @java DdlPreviewResponse */
export interface DdlPreviewResponse {
  ddl?: string;
  error?: string;
}

/**
 * `POST /api/query/validate` — syntaxe seule ; le catalogue n'est pas consulté.
 *
 * @java SqlValidationResponse
 */
export interface SqlValidationResponse {
  valid: boolean;
  error?: string;
}

/**
 * `POST /api/query/cancel/{queryId}` — ce qui a réellement été annulé.
 *
 * `cancelled: false` est un résultat normal, pas un échec : un scan `KAFKA_DIRECT` n'a aucun job
 * Flink à annuler. L'UI doit dire « requête abandonnée » et non « annulée » dans ce cas.
 *
 * @java QueryCancelResponse
 */
export interface QueryCancelResponse {
  cancelled: boolean;
  outcome: string;
}

/**
 * `POST /api/config/test-llm` — test de connectivité du fournisseur LLM.
 *
 * `modelCheck` n'est présent que chez OpenRouter, seul fournisseur ici à publier ce que sait faire
 * un modèle donné. Son absence ne veut donc rien dire du modèle : elle veut dire qu'on n'a pas
 * demandé.
 *
 * @java LlmTestResponse
 */
export interface LlmTestResponse {
  ok: boolean;
  message: string;
  /** Le fournisseur et le modèle réellement sondés — ceux du candidat quand il y en a un. */
  provider: string;
  model: string;
  /**
   * Vrai quand la sonde a testé un modèle (ou un endpoint) que le déploiement n'utilise pas — une
   * saisie du formulaire, pas encore appliquée. La phrase affichée doit suivre : « joignable » ne
   * dit pas la même chose d'un candidat et de ce qui tourne.
   */
  candidate: boolean;
  modelCheck?: LlmModelCheck;
  keyStatus?: LlmKeyStatus;
}

/**
 * Ce qu'il reste sur la clé, chez les fournisseurs qui le publient — voir le record Java.
 *
 * Tout est nullable et `null` veut dire « la passerelle ne l'a pas dit ». Un cas mérite attention
 * plus que les autres : une clé sans plafond rapporte `limitUsd` à `null` alors que `usageUsd` est
 * parfaitement connu. C'est un état réel — un crédit sans limite — qu'il ne faut ni rendre comme
 * « 0 restant », ni confondre avec `error`, qui dit qu'on n'a pas pu demander.
 *
 * @java LlmKeyStatus
 */
export interface LlmKeyStatus {
  usageUsd: number | null;
  limitUsd: number | null;
  remainingUsd: number | null;
  freeTier: boolean | null;
  error: string | null;
}

/**
 * `POST /api/process-mining/profiling/validate` — identifiant du mapping retenu.
 *
 * @java FieldMappingValidation
 */
export interface FieldMappingValidation {
  fieldMappingId: string;
}

/**
 * `POST /api/metrics/preview-template` — exécution à blanc d'une métrique avant enregistrement.
 *
 * Le record existait déjà côté serveur (`MetricPreviewResult`) : cette interface en était un
 * doublon écrit à la main, que rien ne reliait à lui — et son commentaire nommait un endpoint
 * (`/api/metrics/test`) que la page n'appelle pas. Deux dérives qu'un marqueur aurait empêchées.
 *
 * @java MetricPreviewResult
 */
export interface MetricTestResponse {
  value?: number | null;
  rows?: Record<string, unknown>[];
  error?: string;
  summary?: Record<string, unknown>;
}

/**
 * Un refus, avec sa raison — la forme que tout `catch` de ce dépôt lit déjà à travers
 * `extractApiErrorMessage`.
 *
 * @java ApiError
 */
export interface ApiError {
  error: string;
}

/**
 * `GET /api/metrics/metadata` — les colonnes de chaque table Flink enregistrée.
 *
 * Sans marqueur `@java` : le contrôleur sert une `Map<String, List<String>>` et non un record, donc
 * `check-api-types.py` n'a rien contre quoi la résoudre. Nommée ici tout de même, plutôt que
 * déclarée à la main au point d'appel : non vérifiée est permis, invisible ne l'est pas.
 */
export type TableMetadata = Record<string, string[]>;

/** @java HealthStatus */
export type HealthStatus = 'HEALTHY' | 'WARNING' | 'CRITICAL';

/** @java AuditStatus */
export type AuditStatus = 'RUNNING' | 'COMPLETED' | 'CANCELLED' | 'FAILED';

/**
 * Un constat sur un topic. La sévérité est graduée : un topic prend la pire de ses sévérités.
 *
 * @java TopicIssue
 */
export interface TopicIssue {
  message: string;
  severity: HealthStatus;
}

/** @java TopicAudit */
export interface TopicAudit {
  name: string;
  messageCount: number;
  format: MessageFormat;
  poisonMessageCount: number;
  duplicateCount: number;
  healthStatus: HealthStatus;
  issues: TopicIssue[];
}

/**
 * Une étape d'un flux. `averageLatencyMs` est un `Long` côté Java, donc nullable — une étape dont
 * la latence n'a pas pu être mesurée n'est pas une étape à zéro milliseconde.
 *
 * @java StepInfo
 */
export interface StepInfo {
  topicName: string;
  count: number;
  throughputPercentage: number;
  averageLatencyMs: number | null;
}

/** @java FlowAudit */
export interface FlowAudit {
  flowName: string;
  steps: StepInfo[];
  /** Ratio 0..1 — pas un pourcentage (l'UI multiplie par 100). */
  overallHealthScore: number;
}

/**
 * `GET /api/audit/status/{id}` · `/last` · `/history/{id}` — le rapport d'audit du cluster.
 *
 * `globalStats` est un `Map<String, Object>` côté Java, et c'est ce que dit ce type. La page en a
 * une lecture bien plus riche (`GlobalStats` dans `Audit.tsx` : phase, progression, `stopReason`,
 * `healthScore`, `scopeNotes`…), mais cette forme-là n'est promise par aucun record : elle est
 * assemblée clé par clé dans `AuditService`. La déclarer ici comme un objet typé ferait passer
 * pour un contrat ce qui est une convention, et c'est exactement le genre d'affirmation écrite à
 * la main que ce fichier existe pour supprimer. Le rétrécissement est donc explicite, en un seul
 * point de `Audit.tsx`, où il se voit.
 *
 * @java AuditReport
 */
export interface AuditReport {
  auditId: string;
  status: AuditStatus;
  totalTopics: number;
  totalMessages: number;
  criticalTopicsCount: number;
  warningTopicsCount: number;
  topicAudits: TopicAudit[];
  flowAudits: FlowAudit[];
  globalStats: Record<string, unknown>;
}

/**
 * Une anomalie relevée par l'analyse Process Mining.
 *
 * Les unions de littéraux sont plus étroites que le `String` du record Java : le serveur n'émet que
 * ces valeurs et l'UI branche dessus (icônes, couleurs de sévérité). Élargir à `string` pour
 * satisfaire le script supprimerait de la sûreté de type au nom d'un contrôle qui existe pour en
 * apporter.
 *
 * @java AnomalyReport
 */
export interface AnomalyReport {
  id: string;
  topic: string;
  type: 'SEQUENCE' | 'TEMPORAL' | 'STRUCTURAL' | 'CARDINALITY' | 'BUSINESS';
  severity: 'CRITICAL' | 'MAJOR' | 'MINOR';
  fields: string[];
  description: string;
  /** Peut être null, même raison. */
  probableCause: string | null;
  /** Peut être null : le modèle n'a pas à inventer une requête faute d'en avoir une. */
  sqlSuggestion: string | null;
}

/**
 * Un passage cité par SpectraLLM à l'appui d'une analyse (RAG). Vide chez les autres fournisseurs.
 *
 * @java RagSource
 */
export interface RagSource {
  text: string;
  sourceFile: string | null;
  score: number | null;
}

/**
 * `POST /api/process-mining/snapshot` — le résultat d'une analyse Process Mining.
 *
 * La forme vivait au point d'appel, dans `ProcessMining.tsx` : exactement le motif décrit en tête
 * de ce fichier, et le premier à dériver quand `error` a été ajouté au record Java pour distinguer
 * un échec d'un résultat. Elle est ici pour que le script le vérifie.
 *
 * `error` est ce qui sépare les deux : un modèle injoignable répond 200 — ce n'est pas une requête
 * malformée — et la raison voyageait dans `comments`, où la page l'affichait comme un commentaire
 * d'analyse sous un diagramme vide. Quand ce champ est posé, rien d'autre dans l'objet n'est un
 * constat.
 *
 * @java ProcessMiningResult
 */
/** @java MetricPriority */
export interface MetricPriority {
  /** L'id de la carte KPI, tel que le panneau de suggestions le frappe. */
  id: string;
  /** Une phrase du modèle : pourquoi celle-là plutôt qu'une autre, sur ce parc. */
  why: string;
}

export interface ProcessMiningResult {
  flowchart: string | null;
  comments: string | null;
  hypotheses: string[];
  blindSpots: string[];
  anomalies: AnomalyReport[];
  ragSources: RagSource[];
  error: string | null;
  /** Ce que l'appel a coûté, ou `null` si le client ne l'a pas relevé. */
  usage: LlmUsage | null;
  /**
   * Ce que l'analyse a effectivement pu regarder. `null` en mode live, où la portée d'une fenêtre
   * est déjà rapportée par `WINDOW_STATS`.
   */
  coverage: ProcessMiningCoverage | null;
  /**
   * Le processus mesuré. Calculé ici, jamais affirmé par le modèle — et c'est ce qui permet une
   * exécution sans LLM du tout : les transitions, les variantes et les latences sont du comptage,
   * seule leur lecture demandait un modèle. `null` quand aucun log d'événements n'a pu être bâti
   * *et* que rien ne l'a demandé (une réponse d'un serveur antérieur, par exemple).
   */
  processModel: ProcessModel | null;
  /**
   * Les KPI que le modèle suivrait, parmi ceux que le processus mesuré rend possibles.
   *
   * Le seul champ ici que le modèle remplit vraiment, et l'exception est délibérée : choisir entre
   * des candidats et dire pourquoi est un jugement — ce pour quoi un modèle sert — là où les
   * mesures au-dessus sont du comptage. Il porte des ids, jamais des métriques, et un id absent de
   * la liste qu'on lui a montrée est écarté côté serveur avant d'arriver ici.
   */
  metricPriorities: MetricPriority[];
}

/**
 * Quelle horloge a ordonné le log d'événements — `ProcessModel.TimeSource`.
 *
 * @java TimeSource
 */
export type ProcessModelTimeSource = 'MAPPED_FIELD' | 'MIXED' | 'RECORD_TIMESTAMP';

/**
 * Un nœud du graphe : une étape du pipeline, éventuellement affinée par son statut —
 * `ProcessModel.Activity`.
 *
 * @java Activity
 */
export interface ProcessActivity {
  name: string;
  occurrences: number;
  cases: number;
}

/**
 * Une relation de succession directe, et ce qu'elle a coûté.
 *
 * `outOfOrderCount` compte les occurrences que le broker a vues dans l'ordre inverse de leur
 * horodatage métier : le log étant trié par temps d'événement, une latence ne peut pas être
 * négative, et c'est ce désaccord entre deux horloges qui trahit un producteur désynchronisé.
 *
 * @java Edge
 */
export interface ProcessEdge {
  from: string;
  to: string;
  occurrences: number;
  cases: number;
  p50Ms: number;
  p95Ms: number;
  maxMs: number;
  outOfOrderCount: number;
}

/**
 * Un chemin distinct de bout en bout, et combien de cas l'ont emprunté — `ProcessModel.Variant`.
 *
 * @java Variant
 */
export interface ProcessVariant {
  path: string[];
  cases: number;
  /** Un id de cas qui l'a emprunté, pour que l'affirmation soit vérifiable sur le cluster. */
  example: string;
}

/**
 * Une activité qui a commencé ou terminé des cas — `ProcessModel.Endpoint`.
 *
 * @java Endpoint
 */
export interface ProcessEndpoint {
  activity: string;
  cases: number;
}

/**
 * Une activité qu'un même cas a visitée plus d'une fois — redélivrance, reprise ou boucle
 * légitime. Nommée pour ce qui a été observé, pas pour ce que ça veut dire : lequel des trois
 * c'est dépend du métier, et c'est la part du modèle.
 *
 * @java Repeat
 */
export interface ProcessRepeat {
  activity: string;
  casesAffected: number;
  maxOccurrencesInOneCase: number;
}

/**
 * Le processus, mesuré — l'agrégat d'un log d'événements, calculé à partir des digests plutôt
 * qu'inféré par un modèle.
 *
 * `available` à faux n'est pas un processus vide : sans id de corrélation il n'y a pas de log du
 * tout, et `unavailableReason` dit lequel des deux c'est. Même règle que `SnapshotRead` — une
 * mesure qu'on n'a pas pu prendre ne doit pas ressembler à une mesure valant zéro.
 *
 * @java ProcessModel
 */
export interface ProcessModel {
  available: boolean;
  unavailableReason: string | null;
  cases: number;
  events: number;
  /** Enregistrements lus qui ne portaient pas d'id de corrélation : hors du log. */
  eventsWithoutCase: number;
  windowStartMs: number;
  windowEndMs: number;
  eventTimeSource: ProcessModelTimeSource;
  activities: ProcessActivity[];
  edges: ProcessEdge[];
  variants: ProcessVariant[];
  starts: ProcessEndpoint[];
  ends: ProcessEndpoint[];
  repeats: ProcessRepeat[];
  /** Les cas retenus comme exemples travaillés dans le prompt. */
  spotlightCases: string[];
  variantsOmitted: number;
  edgesOmitted: number;
  /** Ce qu'il ne faut pas sur-lire : effets de bord de fenêtre, replis d'horloge, plafonds. */
  notes: string[];
}

/**
 * Ce qu'un topic de l'exécution a réellement apporté.
 *
 * Trois nombres et non deux. `messagesMeasured` sont les enregistrements entrés dans le log
 * d'événements : ils comptent dans chaque transition, variante et latence sur lesquelles repose la
 * réponse, qu'aucun d'eux ne soit montré ou non. `messagesDetailed` sont ceux inlinés verbatim —
 * une trace de cas témoin, ou, faute de log d'événements, l'échantillon par topic.
 *
 * Les confondre disait « 6 sur 3 000 » d'une exécution qui en avait mesuré trois mille, et envoyait
 * le lecteur augmenter un budget de prompt consommé à 6 %. Un `messagesMeasured` total à zéro veut
 * dire qu'aucun log n'a pu être construit et que l'échantillonnage par topic a tourné : c'est ce qui
 * permet de distinguer les deux chemins sans un second drapeau qui dériverait.
 *
 * `readable` à faux veut dire qu'aucune partition n'a été décrite pour ce topic : un topic absent
 * ou une faute de frappe, pas un topic vide.
 *
 * @java TopicCoverage
 */
export interface TopicCoverage {
  topic: string;
  messagesRead: number;
  messagesMeasured: number;
  messagesDetailed: number;
  readable: boolean;
}

/**
 * La portée d'une analyse Process Mining — voir `pages/processMiningCoverage.ts`, qui la met en
 * phrases, et le record Java, qui explique pourquoi elle existe.
 *
 * @java ProcessMiningCoverage
 */
export interface ProcessMiningCoverage {
  topics: TopicCoverage[];
  messagesRead: number;
  messagesMeasured: number;
  messagesDetailed: number;
  promptChars: number;
  promptCharBudget: number;
  /** La lecture s'est arrêtée sur son propre budget : les décomptes sont des planchers. */
  readTruncated: boolean;
  readError: string | null;
  warnings: string[];
}

/**
 * Ce qu'un appel au modèle a coûté.
 *
 * Les comptes de tokens sont nullables et `null` veut dire « non rapporté », pas zéro : l'API de
 * SpectraLLM n'en renvoie aucun et une passerelle OpenAI-compatible allégée peut omettre l'objet
 * `usage`. Zéro dirait que l'appel était gratuit — le même genre de mensonge poli que l'audit
 * précédent a retiré ailleurs. `durationMs` est toujours mesuré côté serveur, donc toujours réel.
 *
 * @java LlmUsage
 */
export interface LlmUsage {
  inputTokens: number | null;
  outputTokens: number | null;
  /**
   * Ce que le fournisseur dit que l'appel a coûté, en USD — `null` quand il ne le dit pas.
   *
   * Rapporté, jamais calculé : aucune table de prix ne vit dans cette application, donc un montant
   * affiché est un montant qu'un fournisseur a assumé. OpenRouter le renvoie sur chaque réponse ;
   * l'API OpenAI, Ollama et SpectraLLM non, et il y reste `null`. Attention, `0` est une vraie
   * mesure — un modèle gratuit — et non une mesure absente.
   */
  costUsd: number | null;
  /**
   * Combien de tokens du prompt ont été servis depuis le cache du fournisseur — `null` quand il ne
   * le rapporte pas, `0` quand le prompt a manqué le cache. Une mesure, pas une promesse : rien ici
   * n'affirme une économie, on rapporte ce que le fournisseur a compté.
   */
  cachedInputTokens: number | null;
  /**
   * Combien des jetons produits ont servi à délibérer avant de répondre — `null` quand le
   * fournisseur ne le rapporte pas.
   *
   * Déjà compris dans `outputTokens` : c'en est une ventilation, pas un ajout. Ce que ça apporte
   * est donc l'*explication* d'un coût et non le coût — deux analyses au rendu identique peuvent
   * différer d'un facteur trois ici, et rien d'autre à l'écran ne le dirait. La nullité se lit à
   * l'envers de `cachedInputTokens` : `0` est le cas ordinaire, une vraie mesure disant que le
   * modèle n'a pas raisonné, et seul `null` veut dire que personne n'a compté.
   */
  reasoningTokens: number | null;
  durationMs: number;
  provider: string;
  model: string;
}

/**
 * Ce que la passerelle dit du modèle configuré — voir {@link LlmModelCheck}.
 *
 * Quatre valeurs plutôt qu'un booléen à cause de la troisième : OpenRouter liste
 * `response_format` et `structured_outputs` séparément, et un modèle qui a le premier sans le
 * second *accepte* le champ puis ignore le schéma. Aucune 4xx, donc le verrou par modèle du client
 * ne se déclenche jamais et le déploiement croit décoder sous contrainte alors que non.
 *
 * @java SchemaSupport
 */
export type SchemaSupport = 'CONSTRAINED' | 'ACCEPTED_UNCONSTRAINED' | 'UNSUPPORTED' | 'UNKNOWN';

/**
 * Ce que la passerelle dit du modèle que ce déploiement appelle.
 *
 * Tout est nullable et `null` veut dire « le catalogue ne l'a pas dit » — même règle que
 * `LlmUsage`, et pour la même raison : ce dossier existe pour remplacer des suppositions, donc un
 * fait qu'il n'a pas pu établir ne doit pas revenir avec l'allure d'un fait établi négativement.
 * Les *jugements* (`schemaSupport`, `promptBudgetFits`) sont calculés côté serveur ; la page n'en
 * fait que des phrases. Une règle de notation dupliquée des deux côtés est une règle qui dérive.
 *
 * @java LlmModelCheck
 */
export interface LlmModelCheck {
  id: string | null;
  name: string | null;
  contextLength: number | null;
  /**
   * `false` est un vrai constat — un modèle d'embeddings, de rerank ou de synthèse vocale ne peut
   * pas répondre à un prompt Process Mining — et `null` veut dire que les modalités n'ont pas été
   * rapportées, ce qui ne doit surtout pas s'afficher comme un refus.
   */
  emitsText: boolean | null;
  schemaSupport: SchemaSupport;
  /**
   * `true` : le modèle refuse qu'on désactive le raisonnement, donc une part de `claude.max-tokens`
   * part en délibération à chaque appel, par construction. `null` pour un modèle qui ne publie pas
   * de bloc de raisonnement — le cas ordinaire, pas une dénégation.
   */
  reasoningMandatory: boolean | null;
  /** L'estimation plancher de ce qu'un prompt réclame, en tokens, réponse comprise. */
  promptBudgetTokens: number | null;
  /**
   * Un **plancher, pas un étalonnage** : le ratio de quatre caractères par token est délibérément
   * optimiste, donc un budget qui passe ici peut malgré tout ne pas tenir, tandis qu'un budget
   * refusé ne tient certainement pas. `null` quand la fenêtre est inconnue.
   */
  promptBudgetFits: boolean | null;
  /**
   * Si cette clé peut réellement atteindre le modèle. Consulté seulement quand la recherche du
   * modèle a échoué, parce que c'est le seul cas que ça départage : une clé d'organisation
   * restreinte reçoit la même 404 qu'un slug mal tapé. `null` — la valeur ordinaire — veut dire
   * que la question n'a pas été posée, ou n'a pas pu être tranchée : la liste par clé est paginée,
   * donc un slug absent d'une page tronquée est un slug qu'on n'a pas regardé.
   */
  availableToKey: boolean | null;
  /** Pourquoi la consultation n'a rien donné, ou `null` quand elle a donné quelque chose. */
  error: string | null;
}

/**
 * Une ligne de la liste restreinte des modèles.
 *
 * @java LlmModelOption
 */
export interface LlmModelOption {
  id: string;
  name: string | null;
  contextLength: number | null;
  schemaSupport: SchemaSupport;
  reasoningMandatory: boolean | null;
  promptPriceUsdPerMillion: number | null;
  completionPriceUsdPerMillion: number | null;
  /**
   * Ce qu'une fenêtre Process Mining coûterait sur ce modèle — **une projection, pas une mesure**,
   * et la distinction n'est pas de la pédanterie : `LlmUsage.costUsd` est *lu* chez le fournisseur
   * précisément parce qu'aucune table de prix ne vit ici, alors que celui-ci est un prix publié
   * multiplié par une estimation, sur le même plancher optimiste que partout ailleurs. Il peut donc
   * sous-estimer, et doit être étiqueté partout où il s'affiche. `null` quand le modèle ne publie
   * pas de prix — jamais `0`, qui est une vraie mesure (un modèle gratuit).
   */
  projectedCostUsd: number | null;
}

/**
 * Les modèles vers lesquels cette application pourrait être pointée, et ce qui a été demandé pour
 * les obtenir.
 *
 * `criteria` n'est pas de l'ornement : une vue filtrée présentée comme « les modèles » est le même
 * mensonge qu'une liste tronquée présentée comme complète. Et `available: false` avec une liste
 * vide n'est pas `available: true` avec une liste vide — « on n'a pas pu demander » contre « rien
 * ne correspond », et seule la seconde dit quelque chose du catalogue.
 *
 * @java LlmModelShortlist
 */
export interface LlmModelShortlist {
  available: boolean;
  models: LlmModelOption[];
  criteria: string[];
  error: string | null;
}

/**
 * Une ligne de l'historique d'audit : de quoi comparer des runs sans charger les rapports.
 *
 * Déclarée ici plutôt qu'au point d'appel parce qu'elle en a maintenant deux — la page Audit, qui
 * liste les runs passés, et la page Métriques, qui compare l'identifiant du dernier run à celui
 * dont ses propositions sont issues. Une forme anonyme dupliquée est exactement ce qui a tué la
 * page Compare.
 *
 * @java AuditRunSummary
 */
export interface AuditRunSummary {
  auditId: string;
  status: string;
  timestamp: number;
  durationMs: number | null;
  totalTopics: number;
  totalMessages: number;
  criticalTopicsCount: number;
  warningTopicsCount: number;
  healthScore: number | null;
  topicPrefix: string | null;
  /** Enregistré avant la sévérité graduée : forme illisible par la vue actuelle. */
  legacy: boolean;
}

/**
 * L'historique borné, avec ce que la lecture a couvert — une liste qui montrerait 20 runs sur 500
 * sans le dire se lirait « le cluster n'a jamais été audité avant ».
 *
 * @java AuditHistory
 */
export interface AuditHistory {
  runs: AuditRunSummary[];
  recordsScanned: number;
  exhausted: boolean;
  warnings: string[];
}

/**
 * Une colonne d'une entité du modèle de données. Les chemins imbriqués gardent leur notation
 * pointée (`customer.address.city`).
 *
 * @java DataModelColumn
 */
export interface DataModelColumn {
  name: string;
  type: string;
  primaryKey: boolean;
  /** Id de l'entité que cette colonne référence, `null` pour une colonne ordinaire. */
  references: string | null;
  /**
   * Le mot d'entité que le nom de cette colonne *désigne* (`order_id` → `order`), `null` quand
   * il ne désigne personne — soit il ne se lit pas comme un identifiant (`amount`, ou `paid` et
   * `valid`, qui finissent seulement par « id »), soit il nomme *cette* entité (`id`, ou
   * `order_id` sur un topic orders), ce qui est une identité et non une référence.
   *
   * C'est la règle que le serveur applique avant de chercher une cible, exposée pour que l'UI
   * puisse marquer une colonne qui se lit comme une clé étrangère sans avoir produit de
   * relation. Un `keyBase` non nul avec un `references` nul veut donc dire exactement une
   * chose — aucun topic sélectionné ne porte ce nom — puisqu'une cible présente dans le modèle
   * produit toujours une relation.
   */
  keyBase: string | null;
}

/**
 * Un nœud du modèle de données : un topic Kafka lu comme une table. `primaryKey` est détectée,
 * jamais inventée — `null` quand rien d'identifiant n'a été trouvé — et `messageCount` nullable
 * parce qu'un comptage qui n'a pas pu être lu n'est pas un topic vide.
 *
 * @java DataModelEntity
 */
export interface DataModelEntity {
  id: string;
  topic: string;
  format: MessageFormat | null;
  columns: DataModelColumn[];
  primaryKey: string | null;
  messageCount: number | null;
}

/** @java RelationConfidence */
export type RelationConfidence = 'HIGH' | 'MEDIUM' | 'LOW';

/**
 * Une relation déduite — jamais observée, Kafka n'a pas de clés étrangères — donc chaque arête
 * porte la preuve sur laquelle elle repose et le grade de cette preuve.
 *
 * @java DataModelRelation
 */
export interface DataModelRelation {
  from: string;
  to: string;
  fromColumn: string;
  toColumn: string | null;
  confidence: RelationConfidence;
  reason: string;
}

/**
 * `POST /api/data-model` — le modèle déduit, avec les bornes de ce qui a réellement été lu :
 * un topic sans schéma, au-delà du plafond ou dont la lecture a échoué est nommé dans
 * `warnings`, jamais silencieusement absent du graphe.
 *
 * @java DataModelResponse
 */
export interface DataModelResponse {
  entities: DataModelEntity[];
  relations: DataModelRelation[];
  warnings: string[];
  topicsRequested: number;
  topicsAnalyzed: number;
  truncated: boolean;
}

/**
 * `GET /api/data-model/limits` — les bornes du serveur, pour que la page n'en garde pas une
 * copie. `maxTopics` est le plafond dur (`explorer.data-model-max-topics`) : ce qu'une
 * génération ne peut pas dépasser quoi qu'elle demande. `defaultMaxTopics` est ce qu'elle
 * analyse quand elle ne demande rien. Les deux derniers disent combien de temps une génération
 * peut prendre, ce dont la page a besoin pour dimensionner sa propre attente — et qu'elle ne
 * doit surtout pas recopier en dur.
 *
 * @java DataModelLimits
 */
export interface DataModelLimits {
  maxTopics: number;
  defaultMaxTopics: number;
  /** Combien de temps un topic peut prendre avant d'être abandonné, côté serveur. */
  perTopicTimeoutMs: number;
  /** Combien de topics sont inférés en parallèle. */
  inferenceThreads: number;
}
