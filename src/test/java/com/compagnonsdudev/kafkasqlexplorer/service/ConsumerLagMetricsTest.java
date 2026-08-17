// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors
package com.compagnonsdudev.kafkasqlexplorer.service;

import com.compagnonsdudev.kafkasqlexplorer.config.ExplorerConfig;
import com.compagnonsdudev.kafkasqlexplorer.domain.ConsumerGroupLag;
import com.compagnonsdudev.kafkasqlexplorer.domain.PartitionLag;
import com.compagnonsdudev.kafkasqlexplorer.domain.TopicConsumers;
import com.compagnonsdudev.kafkasqlexplorer.domain.TopicTimeLag;
import io.micrometer.core.instrument.search.MeterNotFoundException;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ConsumerLagMetricsTest {

    private KafkaAdminService kafkaAdminService;
    private ExplorerConfig explorerConfig;
    private SimpleMeterRegistry registry;
    private ConsumerLagMetrics metrics;

    @BeforeEach
    void setUp() {
        kafkaAdminService = mock(KafkaAdminService.class);
        explorerConfig = new ExplorerConfig();
        registry = new SimpleMeterRegistry();
        metrics = new ConsumerLagMetrics(kafkaAdminService, explorerConfig, registry);
    }

    private static ConsumerGroupLag group(String id, long totalLag, int assignedMembers, int withoutCommit) {
        return new ConsumerGroupLag(id, "CLASSIC", "STABLE", assignedMembers, assignedMembers, true,
                totalLag, withoutCommit,
                List.of(new PartitionLag(0, 10L, 10L + totalLag, totalLag, null, null, null)),
                null);
    }

    private static TopicConsumers available(String topic, ConsumerGroupLag... groups) {
        return new TopicConsumers(topic, List.of(groups), groups.length, groups.length,
                groups.length, false, true, List.of());
    }

    private void topicReturns(String topic, ConsumerGroupLag... groups) {
        when(kafkaAdminService.getTopicConsumers(eq(topic), anyInt()))
                .thenReturn(available(topic, groups));
    }

    @Test
    void exportsLagMembersAndUncommittedPartitionsPerGroupAndTopic() {
        explorerConfig.setLagMetricsTopics(List.of("demo.orders"));
        topicReturns("demo.orders", group("orders-api", 42L, 2, 1));

        metrics.refresh();

        assertEquals(42.0, registry.get("kafka.consumer.group.lag")
                .tags("group", "orders-api", "topic", "demo.orders").gauge().value());
        assertEquals(2.0, registry.get("kafka.consumer.group.assigned.members")
                .tags("group", "orders-api", "topic", "demo.orders").gauge().value());
        // Sans cette série, une alerte sur le seul retard raterait un groupe qui ignore la moitié
        // des partitions — leur arriéré n'est pas compté dans le retard.
        assertEquals(1.0, registry.get("kafka.consumer.group.partitions.without.commit")
                .tags("group", "orders-api", "topic", "demo.orders").gauge().value());
    }

    @Test
    void registersNothingAndPollsNothingWhenNoTopicIsConfigured() {
        metrics.refresh();

        assertTrue(registry.getMeters().isEmpty(), "an unconfigured feature is not a degraded one");
        verify(kafkaAdminService, never()).getTopicConsumers(eq("demo.orders"), anyInt());
    }

    @Test
    void updatesGaugesInPlaceAcrossRefreshes() {
        explorerConfig.setLagMetricsTopics(List.of("demo.orders"));
        when(kafkaAdminService.getTopicConsumers(eq("demo.orders"), anyInt()))
                .thenReturn(available("demo.orders", group("g", 10L, 1, 0)))
                .thenReturn(available("demo.orders", group("g", 900L, 1, 0)));

        metrics.refresh();
        metrics.refresh();

        assertEquals(900.0, registry.get("kafka.consumer.group.lag")
                .tags("group", "g", "topic", "demo.orders").gauge().value());
        assertEquals(1, registry.find("kafka.consumer.group.lag").gauges().size(),
                "the same series must be updated, not duplicated");
    }

    @Test
    void keepsTheLastValueWhenARefreshFails() {
        explorerConfig.setLagMetricsTopics(List.of("demo.orders"));
        when(kafkaAdminService.getTopicConsumers(eq("demo.orders"), anyInt()))
                .thenReturn(available("demo.orders", group("g", 500L, 1, 0)))
                .thenThrow(new IllegalStateException("coordinator unavailable"));

        metrics.refresh();
        metrics.refresh();

        // Retomber à zéro serait la seule mauvaise réponse : elle se lit « le retard a été résorbé ».
        assertEquals(500.0, registry.get("kafka.consumer.group.lag")
                .tags("group", "g", "topic", "demo.orders").gauge().value());
    }

    /*
     * Même exigence, un cran plus fin : ce n'est pas le rafraîchissement entier qui échoue, c'est
     * un groupe. Il revient alors avec un retard de zéro — qui, publié en jauge, se lit « à jour »
     * et éteint précisément l'alerte qui devrait sonner.
     */
    @Test
    void keepsTheLastValueOfAGroupWhoseOffsetsCouldNotBeRead() {
        explorerConfig.setLagMetricsTopics(List.of("demo.orders"));
        when(kafkaAdminService.getTopicConsumers(eq("demo.orders"), anyInt()))
                .thenReturn(available("demo.orders", group("g", 500L, 1, 0)))
                .thenReturn(available("demo.orders",
                        ConsumerGroupLag.failed("g", "CLASSIC", "coordinator moved")));

        metrics.refresh();
        metrics.refresh();

        assertEquals(500.0, registry.get("kafka.consumer.group.lag")
                .tags("group", "g", "topic", "demo.orders").gauge().value());
    }

    /*
     * Garder la dernière valeur est juste, mais sans date elle est indiscernable d'une valeur
     * fraîche : une alerte « lag > N » se déclenche pareil que le retard soit réel et bloqué ou
     * simplement plus mesuré, et l'opérateur qu'elle réveille n'a aucun moyen de trancher. Le
     * timestamp ne bouge donc que lorsqu'une mesure a réellement abouti.
     */
    @Test
    void datesEachValueSoAFrozenGaugeCanBeToldFromAFreshOne() {
        explorerConfig.setLagMetricsTopics(List.of("demo.orders"));
        topicReturns("demo.orders", group("g", 42L, 1, 0));

        metrics.refresh();

        double measuredAt = registry.get("kafka.consumer.group.lag.last.success.timestamp.seconds")
                .tags("group", "g", "topic", "demo.orders").gauge().value();
        assertTrue(measuredAt > 1_600_000_000d, "a unix timestamp in seconds, not a duration");
        assertTrue(measuredAt <= System.currentTimeMillis() / 1000d + 1);
    }

    @Test
    void doesNotRefreshTheTimestampOfAGroupThatCouldNotBeRead() throws Exception {
        explorerConfig.setLagMetricsTopics(List.of("demo.orders"));
        when(kafkaAdminService.getTopicConsumers(eq("demo.orders"), anyInt()))
                .thenReturn(available("demo.orders", group("g", 500L, 1, 0)))
                .thenReturn(available("demo.orders",
                        ConsumerGroupLag.failed("g", "CLASSIC", "coordinator moved")));

        metrics.refresh();
        double first = registry.get("kafka.consumer.group.lag.last.success.timestamp.seconds")
                .tags("group", "g", "topic", "demo.orders").gauge().value();
        Thread.sleep(1100);
        metrics.refresh();

        assertEquals(first, registry.get("kafka.consumer.group.lag.last.success.timestamp.seconds")
                .tags("group", "g", "topic", "demo.orders").gauge().value(),
                "the lag keeps its last value, so its date must keep it too — that is what makes "
                    + "the freeze visible to an alert");
    }

    /* Une lecture qui a échoué revient vide : agir dessus publierait « plus aucun consommateur ». */
    @Test
    void keepsTheLastValuesWhenTheTopicCouldNotBeRead() {
        explorerConfig.setLagMetricsTopics(List.of("demo.orders"));
        when(kafkaAdminService.getTopicConsumers(eq("demo.orders"), anyInt()))
                .thenReturn(available("demo.orders", group("g", 500L, 1, 0)))
                .thenReturn(TopicConsumers.unavailable("demo.orders", "broker unreachable"));

        metrics.refresh();
        metrics.refresh();

        assertEquals(500.0, registry.get("kafka.consumer.group.lag")
                .tags("group", "g", "topic", "demo.orders").gauge().value());
    }

    /*
     * Garder la dernière valeur est juste pour une lecture ratée et faux pour un groupe supprimé :
     * un consommateur décommissionné exportait à vie le retard de son dernier jour, et l'alerte
     * posée dessus ne pouvait plus jamais retomber.
     */
    @Test
    void dropsTheSeriesOfAGroupThatASuccessfulReadNoLongerReturns() {
        explorerConfig.setLagMetricsTopics(List.of("demo.orders"));
        when(kafkaAdminService.getTopicConsumers(eq("demo.orders"), anyInt()))
                .thenReturn(available("demo.orders", group("old", 500L, 1, 0), group("new", 3L, 1, 0)))
                .thenReturn(available("demo.orders", group("new", 4L, 1, 0)));

        metrics.refresh();
        metrics.refresh();

        assertEquals(0, registry.find("kafka.consumer.group.lag")
                .tags("group", "old", "topic", "demo.orders").gauges().size(),
                "a group that is gone must stop exporting the backlog it had on its last day");
        assertEquals(0, registry.find("kafka.consumer.group.assigned.members")
                .tags("group", "old", "topic", "demo.orders").gauges().size());
        assertEquals(0, registry.find("kafka.consumer.group.lag.last.success.timestamp.seconds")
                .tags("group", "old", "topic", "demo.orders").gauges().size(),
                "dating a series that no longer exists would keep it alive on its own");
        assertEquals(4.0, registry.get("kafka.consumer.group.lag")
                .tags("group", "new", "topic", "demo.orders").gauge().value());
    }

    /* Et la place ainsi libérée retourne au plafond, sinon il compterait des groupes disparus. */
    @Test
    void aVanishedGroupGivesItsSlotBackToTheCap() {
        explorerConfig.setLagMetricsTopics(List.of("demo.orders"));
        explorerConfig.setLagMetricsMaxSeries(1);
        when(kafkaAdminService.getTopicConsumers(eq("demo.orders"), anyInt()))
                .thenReturn(available("demo.orders", group("old", 1L, 1, 0)))
                .thenReturn(available("demo.orders", group("fresh", 9L, 1, 0)));

        metrics.refresh();
        metrics.refresh();

        assertEquals(9.0, registry.get("kafka.consumer.group.lag")
                .tags("group", "fresh", "topic", "demo.orders").gauge().value());
    }

    @Test
    void oneUnreachableTopicDoesNotStopTheOthers() {
        explorerConfig.setLagMetricsTopics(List.of("broken", "demo.orders"));
        when(kafkaAdminService.getTopicConsumers(eq("broken"), anyInt()))
                .thenThrow(new IllegalStateException("nope"));
        topicReturns("demo.orders", group("g", 7L, 1, 0));

        metrics.refresh();

        assertEquals(7.0, registry.get("kafka.consumer.group.lag")
                .tags("group", "g", "topic", "demo.orders").gauge().value());
    }

    @Test
    void capsTheNumberOfSeries() {
        explorerConfig.setLagMetricsTopics(List.of("demo.orders"));
        explorerConfig.setLagMetricsMaxSeries(2);
        topicReturns("demo.orders", group("a", 1L, 1, 0), group("b", 2L, 1, 0), group("c", 3L, 1, 0));

        metrics.refresh();

        assertEquals(2, registry.find("kafka.consumer.group.lag").gauges().size());
        assertThrows(MeterNotFoundException.class, () -> registry.get("kafka.consumer.group.lag")
                .tags("group", "c", "topic", "demo.orders").gauge());
    }

    @Test
    void theCapFreezesTheSetInsteadOfLettingItDependOnOrdering() {
        explorerConfig.setLagMetricsTopics(List.of("demo.orders"));
        explorerConfig.setLagMetricsMaxSeries(1);
        when(kafkaAdminService.getTopicConsumers(eq("demo.orders"), anyInt()))
                .thenReturn(available("demo.orders", group("a", 1L, 1, 0)))
                .thenReturn(available("demo.orders", group("b", 2L, 1, 0), group("a", 5L, 1, 0)));

        metrics.refresh();
        metrics.refresh();

        // « a » garde sa place : sinon la série exportée changerait au gré de l'ordre des réponses.
        assertEquals(5.0, registry.get("kafka.consumer.group.lag")
                .tags("group", "a", "topic", "demo.orders").gauge().value());
        assertEquals(1, registry.find("kafka.consumer.group.lag").gauges().size());
    }

    @Test
    void ignoresBlankAndDuplicateTopicEntries() {
        explorerConfig.setLagMetricsTopics(List.of("demo.orders", "  ", " demo.orders "));
        topicReturns("demo.orders", group("g", 3L, 1, 0));

        metrics.refresh();

        assertEquals(1, registry.find("kafka.consumer.group.lag").gauges().size());
    }

    // ── The backlog in time (opt-in) ─────────────────────────────────────────

    private void timeLagIs(String group, Long maxLagMs) {
        when(kafkaAdminService.getConsumerTimeLag("demo.orders", group)).thenReturn(
                maxLagMs == null
                        ? TopicTimeLag.unavailable("demo.orders", group, "No partition could be read.")
                        : new TopicTimeLag("demo.orders", group, List.of(), maxLagMs, maxLagMs,
                                1, 0, 0, 0, true, null, List.of()));
    }

    @Test
    void exportsNothingInTimeUntilItIsAskedFor() {
        explorerConfig.setLagMetricsTopics(List.of("demo.orders"));
        topicReturns("demo.orders", group("orders-api", 4000L, 1, 0));

        metrics.refresh();

        assertTrue(registry.find("kafka.consumer.group.lag.seconds").gauges().isEmpty(),
                "it reads a record per lagging partition — nobody pays for that by default");
        verify(kafkaAdminService, never()).getConsumerTimeLag(anyString(), anyString());
    }

    @Test
    void exportsTheBacklogInSecondsWhenEnabled() {
        explorerConfig.setLagMetricsTopics(List.of("demo.orders"));
        explorerConfig.setLagMetricsTime(true);
        topicReturns("demo.orders", group("orders-api", 4000L, 1, 0));
        timeLagIs("orders-api", 185_000L);

        metrics.refresh();

        assertEquals(185.0, registry.get("kafka.consumer.group.lag.seconds")
                .tags("group", "orders-api", "topic", "demo.orders").gauge().value());
    }

    @Test
    void aCaughtUpGroupIsZeroSecondsAndCostsNoRead() {
        explorerConfig.setLagMetricsTopics(List.of("demo.orders"));
        explorerConfig.setLagMetricsTime(true);
        topicReturns("demo.orders", group("orders-api", 0L, 2, 0));

        metrics.refresh();

        assertEquals(0.0, registry.get("kafka.consumer.group.lag.seconds")
                .tags("group", "orders-api", "topic", "demo.orders").gauge().value());
        // Committed at the end of every partition: that IS the measurement, no record to read.
        verify(kafkaAdminService, never()).getConsumerTimeLag(anyString(), anyString());
    }

    /*
     * L'inverse de la règle des jauges en nombre, et c'est délibéré : un retard vieillit tout
     * seul, donc une valeur figée n'est pas périmée, elle est fausse — et de plus en plus.
     */
    @Test
    void anAgeThatCanNoLongerBeMeasuredIsRemovedRatherThanFrozen() {
        explorerConfig.setLagMetricsTopics(List.of("demo.orders"));
        explorerConfig.setLagMetricsTime(true);
        topicReturns("demo.orders", group("orders-api", 4000L, 1, 0));
        timeLagIs("orders-api", 185_000L);
        metrics.refresh();

        timeLagIs("orders-api", null);
        metrics.refresh();

        assertTrue(registry.find("kafka.consumer.group.lag.seconds").gauges().isEmpty(),
                "a frozen age reads younger than the truth every minute that passes");
        // The record lag keeps its last value: a count that stopped being read is still the count.
        assertEquals(4000.0, registry.get("kafka.consumer.group.lag")
                .tags("group", "orders-api", "topic", "demo.orders").gauge().value());
    }
}
