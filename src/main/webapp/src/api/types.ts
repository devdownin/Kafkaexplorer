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
