package com.yourcompany.kafkasqlexplorer.service;

import com.yourcompany.kafkasqlexplorer.config.ExplorerConfig;
import com.yourcompany.kafkasqlexplorer.domain.ConsumerGroupLag;
import com.yourcompany.kafkasqlexplorer.domain.PartitionLag;
import com.yourcompany.kafkasqlexplorer.domain.TopicConsumers;
import io.micrometer.core.instrument.search.MeterNotFoundException;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyInt;
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
        return new ConsumerGroupLag(id, "CLASSIC", "STABLE", assignedMembers, assignedMembers,
                totalLag, withoutCommit,
                List.of(new PartitionLag(0, 10L, 10L + totalLag, totalLag, null, null, null)),
                null);
    }

    private void topicReturns(String topic, ConsumerGroupLag... groups) {
        when(kafkaAdminService.getTopicConsumers(eq(topic), anyInt()))
                .thenReturn(new TopicConsumers(topic, List.of(groups), groups.length, groups.length,
                        false, List.of()));
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
                .thenReturn(new TopicConsumers("demo.orders", List.of(group("g", 10L, 1, 0)), 1, 1, false, List.of()))
                .thenReturn(new TopicConsumers("demo.orders", List.of(group("g", 900L, 1, 0)), 1, 1, false, List.of()));

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
                .thenReturn(new TopicConsumers("demo.orders", List.of(group("g", 500L, 1, 0)), 1, 1, false, List.of()))
                .thenThrow(new IllegalStateException("coordinator unavailable"));

        metrics.refresh();
        metrics.refresh();

        // Retomber à zéro serait la seule mauvaise réponse : elle se lit « le retard a été résorbé ».
        assertEquals(500.0, registry.get("kafka.consumer.group.lag")
                .tags("group", "g", "topic", "demo.orders").gauge().value());
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
                .thenReturn(new TopicConsumers("demo.orders", List.of(group("a", 1L, 1, 0)), 1, 1, false, List.of()))
                .thenReturn(new TopicConsumers("demo.orders", List.of(group("b", 2L, 1, 0), group("a", 5L, 1, 0)), 2, 2, false, List.of()));

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
}
