// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors
package com.compagnonsdudev.kafkasqlexplorer.service;

import com.compagnonsdudev.kafkasqlexplorer.config.KafkaConfig;
import com.compagnonsdudev.kafkasqlexplorer.domain.PartitionTimeLag;
import com.compagnonsdudev.kafkasqlexplorer.domain.TopicTimeLag;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.DescribeTopicsResult;
import org.apache.kafka.clients.admin.ListConsumerGroupOffsetsResult;
import org.apache.kafka.clients.admin.ListOffsetsResult;
import org.apache.kafka.clients.admin.TopicDescription;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.MockConsumer;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.common.KafkaFuture;
import org.apache.kafka.common.Node;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.TopicPartitionInfo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * A consumer group's delay measured in time, driven through a mocked AdminClient and a
 * {@link MockConsumer}.
 *
 * <p>What is pinned here is the same distinction the record lag had to be fixed for, one layer
 * further: a delay that could not be measured is {@code null}, never {@code 0}. Zero means "caught
 * up", it is what a Prometheus gauge publishes as "the backlog is gone", and a compacted offset or
 * a spent budget must not be able to produce it.
 */
class KafkaAdminServiceTimeLagTest {

    private static final String TOPIC = "demo.orders";
    private static final String GROUP = "orders-api";
    private static final Node NODE = new Node(1, "broker", 9092);

    private AdminClient admin;
    private MockConsumer<byte[], byte[]> consumer;
    private KafkaAdminService service;

    @BeforeEach
    void setUp() {
        admin = mock(AdminClient.class);
        consumer = new MockConsumer<>("none");
        service = new KafkaAdminService(new KafkaConfig()) {
            @Override
            protected Consumer<byte[], byte[]> createTimeLagConsumer(Properties props) {
                return consumer;
            }
        };
        service.setAdminClientForTest(admin);
    }

    // ── harness ──────────────────────────────────────────────────────────────

    private void topicWithPartitions(int count) {
        List<TopicPartitionInfo> partitions = IntStream.range(0, count)
            .mapToObj(i -> new TopicPartitionInfo(i, NODE, List.of(NODE), List.of(NODE)))
            .collect(Collectors.toList());
        DescribeTopicsResult result = mock(DescribeTopicsResult.class);
        when(result.allTopicNames()).thenReturn(KafkaFuture.completedFuture(
            Map.of(TOPIC, new TopicDescription(TOPIC, false, partitions))));
        when(admin.describeTopics(anyCollection())).thenReturn(result);
    }

    private void committed(Map<Integer, Long> byPartition) {
        Map<TopicPartition, OffsetAndMetadata> offsets = new LinkedHashMap<>();
        byPartition.forEach((partition, offset) -> offsets.put(new TopicPartition(TOPIC, partition),
            offset == null ? null : new OffsetAndMetadata(offset)));
        ListConsumerGroupOffsetsResult result = mock(ListConsumerGroupOffsetsResult.class);
        when(result.partitionsToOffsetAndMetadata(anyString()))
            .thenReturn(KafkaFuture.completedFuture(offsets));
        when(admin.listConsumerGroupOffsets(any(Map.class))).thenReturn(result);
    }

    private void endOffsets(Map<Integer, Long> byPartition) {
        Map<TopicPartition, ListOffsetsResult.ListOffsetsResultInfo> ends = new LinkedHashMap<>();
        byPartition.forEach((partition, offset) -> ends.put(new TopicPartition(TOPIC, partition),
            new ListOffsetsResult.ListOffsetsResultInfo(offset, -1L, Optional.empty())));
        ListOffsetsResult result = mock(ListOffsetsResult.class);
        when(result.all()).thenReturn(KafkaFuture.completedFuture(ends));
        when(admin.listOffsets(any(Map.class))).thenReturn(result);
    }

    /** One record waiting at {@code offset} on {@code partition}, stamped {@code ageMs} ago. */
    private void recordWaiting(int partition, long offset, long ageMs) {
        consumer.schedulePollTask(() -> consumer.addRecord(new ConsumerRecord<>(
            TOPIC, partition, offset, System.currentTimeMillis() - ageMs,
            org.apache.kafka.common.record.TimestampType.CREATE_TIME,
            0, 0, new byte[0], new byte[0],
            new org.apache.kafka.common.header.internals.RecordHeaders(), Optional.empty())));
    }

    private static PartitionTimeLag partition(TopicTimeLag lag, int partition) {
        return lag.partitions().stream().filter(p -> p.partition() == partition).findFirst().orElseThrow();
    }

    // ── the measurement ──────────────────────────────────────────────────────

    @Test
    void reportsTheAgeOfTheOldestWaitingRecordPerPartition() {
        topicWithPartitions(2);
        committed(Map.of(0, 100L, 1, 200L));
        endOffsets(Map.of(0, 150L, 1, 260L));
        recordWaiting(0, 100L, 5_000L);
        recordWaiting(1, 200L, 60_000L);

        TopicTimeLag lag = service.getConsumerTimeLag(TOPIC, GROUP);

        assertTrue(lag.available());
        assertEquals(2, lag.partitionsMeasured());
        // The worst partition is what an alert is set on: a mean would hide it behind a healthy one.
        assertTrue(lag.maxLagMs() >= 60_000L && lag.maxLagMs() < 70_000L, "max was " + lag.maxLagMs());
        assertTrue(lag.avgLagMs() >= 32_500L && lag.avgLagMs() < 37_500L, "avg was " + lag.avgLagMs());
        assertEquals(50L, partition(lag, 0).recordLag());
        assertTrue(lag.complete());
    }

    @Test
    void aPartitionCommittedAtTheEndIsZeroAndCostsNoRead() {
        topicWithPartitions(2);
        committed(Map.of(0, 150L, 1, 200L));
        endOffsets(Map.of(0, 150L, 1, 260L));
        recordWaiting(1, 200L, 4_000L);

        TopicTimeLag lag = service.getConsumerTimeLag(TOPIC, GROUP);

        assertEquals(0L, partition(lag, 0).lagMs());
        assertEquals(1, lag.partitionsCaughtUp());
        // Only the lagging partition was seeked: a caught-up one has nothing waiting to date.
        assertEquals(List.of(new TopicPartition(TOPIC, 1)), List.copyOf(consumer.assignment()));
    }

    @Test
    void aRecordThatCannotBeReadIsUnknownRatherThanZero() {
        topicWithPartitions(2);
        committed(Map.of(0, 100L, 1, 200L));
        endOffsets(Map.of(0, 150L, 1, 260L));
        recordWaiting(0, 100L, 3_000L);
        // Partition 1 answers nothing: compacted away, or the budget ran out.

        TopicTimeLag lag = service.getConsumerTimeLag(TOPIC, GROUP);

        assertNull(partition(lag, 1).lagMs(), "an unmeasured delay must not be published as 'caught up'");
        assertNotNull(partition(lag, 1).note());
        assertEquals(1, lag.partitionsUnknown());
        assertEquals(1, lag.partitionsMeasured());
        assertFalse(lag.complete(), "a maximum over half the partitions is a floor, and must say so");
        assertFalse(lag.warnings().isEmpty());
    }

    @Test
    void aPartitionWithoutACommitIsCountedApartAndNotAsCaughtUp() {
        topicWithPartitions(2);
        Map<Integer, Long> offsets = new LinkedHashMap<>();
        offsets.put(0, 100L);
        offsets.put(1, null);
        committed(offsets);
        endOffsets(Map.of(0, 150L, 1, 260L));
        recordWaiting(0, 100L, 1_000L);

        TopicTimeLag lag = service.getConsumerTimeLag(TOPIC, GROUP);

        assertEquals(1, lag.partitionsWithoutCommit());
        assertNull(partition(lag, 1).lagMs());
        assertNull(partition(lag, 1).committedOffset());
        assertFalse(lag.complete());
    }

    @Test
    void aGroupWithNoPositionOnTheTopicIsAnErrorNotAZeroDelay() {
        topicWithPartitions(1);
        committed(Map.of());
        endOffsets(Map.of(0, 150L));

        TopicTimeLag lag = service.getConsumerTimeLag(TOPIC, GROUP);

        assertFalse(lag.available());
        assertTrue(lag.error().contains("no committed offset"), lag.error());
        assertNull(lag.maxLagMs());
    }

    @Test
    void refusesToMeasureWithoutAGroup() {
        TopicTimeLag lag = service.getConsumerTimeLag(TOPIC, "  ");

        assertFalse(lag.available());
        assertTrue(lag.error().contains("consumer group is required"), lag.error());
    }

    @Test
    void anUnknownTopicReportsWhyRatherThanAnEmptyReading() {
        DescribeTopicsResult result = mock(DescribeTopicsResult.class);
        when(result.allTopicNames()).thenReturn(KafkaFuture.completedFuture(Map.of()));
        when(admin.describeTopics(anyCollection())).thenReturn(result);

        TopicTimeLag lag = service.getConsumerTimeLag(TOPIC, GROUP);

        assertFalse(lag.available());
        assertTrue(lag.error().contains("does not exist"), lag.error());
    }
}
