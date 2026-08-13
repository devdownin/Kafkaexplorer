// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors
package com.compagnonsdudev.kafkasqlexplorer.service;

import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.junit.jupiter.api.Test;

import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The internal consumer groups: named in one place, and never committing.
 *
 * <p>Seven of them left {@code enable.auto.commit} at its default — true — so every metadata read
 * committed offsets under a fresh random group id and left a consumer group behind on the cluster.
 * The Dashboard polls every 30 seconds and drives two of those readers, so an idle tab produced a
 * few thousand phantom groups a day, each one then graded STALLED and reported by the audit as a
 * critical finding about the explorer's own leftovers.
 */
class ExplorerConsumerGroupsTest {

    @Test
    void configureNamesTheGroupAndStopsItCommitting() {
        Properties props = new Properties();
        ExplorerConsumerGroups.configure(props, "metadata");

        String groupId = props.getProperty(ConsumerConfig.GROUP_ID_CONFIG);
        assertTrue(groupId.startsWith("kafka-explorer-metadata-"), groupId);
        // Le cœur du correctif : les deux réglages voyagent ensemble et ne peuvent plus se séparer.
        assertEquals("false", props.getProperty(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG));
    }

    @Test
    void aSessionReaderKeepsAnIdTraceableBackToItsSession() {
        Properties props = new Properties();
        ExplorerConsumerGroups.configureForSession(props, "live", "session-42");

        assertEquals("kafka-explorer-live-session-42", props.getProperty(ConsumerConfig.GROUP_ID_CONFIG));
        assertEquals("false", props.getProperty(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG));
    }

    @Test
    void twoTransientReadersNeverShareAnId() {
        assertNotEquals(ExplorerConsumerGroups.transientGroup("search"),
                        ExplorerConsumerGroups.transientGroup("search"));
    }

    @Test
    void recognisesItsOwnGroups() {
        assertTrue(ExplorerConsumerGroups.isExplorerGroup(ExplorerConsumerGroups.transientGroup("metadata")));
        assertTrue(ExplorerConsumerGroups.isExplorerGroup(ExplorerConsumerGroups.forSession("live", "s1")));
    }

    /*
     * Les groupes déjà posés sur un cluster par une version antérieure comptent aussi : sans ça,
     * un déploiement neuf afficherait une semaine de groupes fantômes comme autant de
     * consommateurs tiers — soit exactement la confusion qu'on vient de retirer.
     */
    @Test
    void recognisesTheGroupsOlderBuildsLeftBehind() {
        for (String legacy : new String[] {
            "kafka-sql-explorer-bulk-metadata-9f1",
            "kafka-sql-explorer-timestamps-2b7",
            "explorer-earliest-77c",
            "explorer-metrics-restorer-31a",
            "audit-history-reader-004",
            "topic-search-8ac",
            "flow-records-5de",
            "live-consumer-session-1",
            "snapshot-reader-abc",
        }) {
            assertTrue(ExplorerConsumerGroups.isExplorerGroup(legacy), legacy);
        }
    }

    /*
     * Le groupe que `DdlGeneratorService` écrit dans chaque table Flink générée. C'est bien
     * l'application qui le fait exister sur le cluster de l'utilisateur : il n'a rien à faire
     * parmi les consommateurs d'un topic, ni à occuper une place sous le plafond de lecture.
     */
    @Test
    void recognisesTheGroupItGivesToEveryGeneratedFlinkTable() {
        assertTrue(ExplorerConsumerGroups.isExplorerGroup("flink_table_demo_orders_1_received"));
    }

    /*
     * Reconnu comme le nôtre d'origine, mais jamais comme supprimable : ce DDL est publié pour être
     * copié, donc le même id peut désigner un job Flink que l'utilisateur fait tourner lui-même.
     * Les deux questions — « est-ce un consommateur de son pipeline ? » et « avons-nous le droit de
     * le supprimer ? » — ont ici des réponses opposées, d'où deux prédicats.
     */
    @Test
    void doesNotTreatTheGeneratedFlinkTableGroupAsOursToDelete() {
        assertFalse(ExplorerConsumerGroups.isOwnReaderGroup("flink_table_demo_orders_1_received"));
    }

    @Test
    void treatsItsOwnReadersAsBothOursAndDeletable() {
        for (String own : new String[] {
            "kafka-explorer-metadata-abc", "kafka-sql-explorer-timestamps-9", "snapshot-reader-1",
        }) {
            assertTrue(ExplorerConsumerGroups.isExplorerGroup(own), own);
            assertTrue(ExplorerConsumerGroups.isOwnReaderGroup(own), own);
        }
        assertFalse(ExplorerConsumerGroups.isOwnReaderGroup("orders-service"));
        assertFalse(ExplorerConsumerGroups.isOwnReaderGroup(null));
    }

    @Test
    void leavesRealConsumerGroupsAlone() {
        for (String real : new String[] {
            "orders-service", "payments", "connect-sink-s3", "",
            // Proche, mais pas à nous : un groupe qui *contient* le préfixe n'en est pas un.
            "my-kafka-explorer-clone", "explorer",
        }) {
            assertFalse(ExplorerConsumerGroups.isExplorerGroup(real), real);
        }
        assertFalse(ExplorerConsumerGroups.isExplorerGroup(null));
    }
}
