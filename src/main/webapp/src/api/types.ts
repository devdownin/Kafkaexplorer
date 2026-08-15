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
  sql: string;
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
export interface DdlPreviewResponse {
  ddl?: string;
  error?: string;
}

/** `POST /api/query/validate` — syntaxe seule ; le catalogue n'est pas consulté. */
export interface SqlValidationResponse {
  valid: boolean;
  error?: string;
}

/**
 * `POST /api/query/cancel/{queryId}` — ce qui a réellement été annulé.
 *
 * `cancelled: false` est un résultat normal, pas un échec : un scan `KAFKA_DIRECT` n'a aucun job
 * Flink à annuler. L'UI doit dire « requête abandonnée » et non « annulée » dans ce cas.
 */
export interface QueryCancelResponse {
  cancelled: boolean;
  outcome: string;
}

/** `POST /api/config/test-llm` — test de connectivité du fournisseur LLM. */
export interface LlmTestResponse {
  ok: boolean;
  message: string;
}

/** `POST /api/process-mining/profiling/validate` — identifiant du mapping retenu. */
export interface FieldMappingValidation {
  fieldMappingId: string;
}

/** `POST /api/metrics/test` — exécution à blanc d'une métrique avant enregistrement. */
export interface MetricTestResponse {
  value?: unknown;
  rows?: unknown[];
  error?: string;
  summary?: Record<string, unknown>;
}

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
  probableCause: string;
  ksqlSuggestion: string;
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
export interface ProcessMiningResult {
  flowchart: string | null;
  comments: string | null;
  hypotheses: string[];
  blindSpots: string[];
  anomalies: AnomalyReport[];
  ragSources: RagSource[];
  error: string | null;
}
