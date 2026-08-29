// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors
package com.compagnonsdudev.kafkasqlexplorer.service;

import com.compagnonsdudev.kafkasqlexplorer.config.KafkaConfig;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.DescribeTopicsResult;
import org.apache.kafka.clients.admin.ListOffsetsResult;
import org.apache.kafka.clients.admin.OffsetSpec;
import org.apache.kafka.clients.admin.TopicDescription;
import org.apache.kafka.common.KafkaFuture;
import org.apache.kafka.common.Node;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.TopicPartitionInfo;
import org.apache.kafka.common.internals.KafkaFutureImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The dashboard's "Last Message" column, which is answered from offset metadata rather than by
 * reading a record.
 *
 * <p>What is under test is mostly the shape of the answer, because the cost is not observable from
 * outside: the previous implementation opened a consumer, assigned every non-empty partition of
 * every topic on the cluster and polled until each had delivered its last record — a fetch batch
 * per partition, every thirty seconds, to keep one {@code long} apiece. These tests hold a mocked
 * {@code AdminClient} and nothing else, so a read that went back to polling would answer nothing
 * at all here; and {@link #asksTheBrokerForTheMaxTimestampRatherThanReadingRecords} names the spec
 * explicitly, since "answers correctly" alone would also be true of a much more expensive route.
 *
 * <p>The rest is the rule every read in this service follows: a partition that could not be
 * measured costs its own contribution and never a fabricated instant, and a broker that predates
 * the spec (Kafka 3.0, KIP-734) selects the older route instead of reporting an empty column.
 */
class KafkaAdminServiceLastMessageTest {

    private static final Node NODE = new Node(1, "broker", 9092);
    private static final String TOPIC = "demo.orders.1.received";
    private static final String OTHER = "demo.payments.settled";
    private static final long NOW = 1_700_000_000_000L;

    private AdminClient admin;
    private KafkaAdminService service;
    /** partition → the answer ListOffsets(maxTimestamp) gives for it. */
    private final Map<TopicPartition, KafkaFuture<ListOffsetsResult.ListOffsetsResultInfo>> answers =
            new LinkedHashMap<>();
    private final List<OffsetSpec> specsAsked = new ArrayList<>();

    @BeforeEach
    void setUp() {
        admin = mock(AdminClient.class);
        service = new KafkaAdminService(new KafkaConfig());
        service.setAdminClientForTest(admin);
        when(admin.listOffsets(any(Map.class))).thenAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            Map<TopicPartition, OffsetSpec> request = (Map<TopicPartition, OffsetSpec>) invocation.getArgument(0);
            Map<TopicPartition, KafkaFuture<ListOffsetsResult.ListOffsetsResultInfo>> result = new LinkedHashMap<>();
            request.forEach((tp, spec) -> {
                specsAsked.add(spec);
                result.put(tp, answers.getOrDefault(tp, info(-1L, -1L)));
            });
            return new ListOffsetsResult(result);
        });
    }

    // ── fixture ──────────────────────────────────────────────────────────────

    private void partitionNewestAt(String topic, int index, long timestamp) {
        answers.put(new TopicPartition(topic, index), info(42L, timestamp));
    }

    private void emptyPartition(String topic, int index) {
        answers.put(new TopicPartition(topic, index), info(-1L, -1L));
    }

    private void unreadablePartition(String topic, int index, Throwable cause) {
        KafkaFutureImpl<ListOffsetsResult.ListOffsetsResultInfo> failed = new KafkaFutureImpl<>();
        failed.completeExceptionally(cause);
        answers.put(new TopicPartition(topic, index), failed);
    }

    private static KafkaFuture<ListOffsetsResult.ListOffsetsResultInfo> info(long offset, long timestamp) {
        return KafkaFuture.completedFuture(
                new ListOffsetsResult.ListOffsetsResultInfo(offset, timestamp, Optional.empty()));
    }

    /** Declares each named topic with the partitions the fixture gave it. */
    private void topicsExist(String... names) {
        Map<String, KafkaFuture<TopicDescription>> values = new LinkedHashMap<>();
        for (String name : names) {
            List<TopicPartitionInfo> partitions = answers.keySet().stream()
                    .filter(tp -> tp.topic().equals(name))
                    .map(tp -> new TopicPartitionInfo(tp.partition(), NODE, List.of(NODE), List.of(NODE)))
                    .toList();
            if (partitions.isEmpty()) {
                partitions = List.of(new TopicPartitionInfo(0, NODE, List.of(NODE), List.of(NODE)));
            }
            values.put(name, KafkaFuture.completedFuture(new TopicDescription(name, false, partitions)));
        }
        describeAnswers(values);
    }

    private void describeAnswers(Map<String, KafkaFuture<TopicDescription>> values) {
        DescribeTopicsResult result = mock(DescribeTopicsResult.class);
        when(result.topicNameValues()).thenReturn(values);
        when(admin.describeTopics(anyCollection())).thenReturn(result);
    }

    // ── what the column says ─────────────────────────────────────────────────

    @Test
    void asksTheBrokerForTheMaxTimestampRatherThanReadingRecords() {
        partitionNewestAt(TOPIC, 0, NOW - 60_000);
        topicsExist(TOPIC);

        service.getTopicsLastMessageTimestamps(List.of(TOPIC));

        assertFalse(specsAsked.isEmpty(), "the offsets were never asked for");
        assertTrue(specsAsked.stream().allMatch(spec -> spec instanceof OffsetSpec.MaxTimestampSpec),
                "the column must come from the offset index, not from a record read: " + specsAsked);
    }

    @Test
    void keepsTheNewestOfThePartitions() {
        partitionNewestAt(TOPIC, 0, NOW - 3_600_000);
        partitionNewestAt(TOPIC, 1, NOW - 60_000);
        partitionNewestAt(TOPIC, 2, NOW - 900_000);
        topicsExist(TOPIC);

        assertEquals(NOW - 60_000, service.getTopicsLastMessageTimestamps(List.of(TOPIC)).get(TOPIC));
    }

    @Test
    void aTopicWithNoRecordCarriesNoInstantRatherThanTheEpoch() {
        emptyPartition(TOPIC, 0);
        emptyPartition(TOPIC, 1);
        topicsExist(TOPIC);

        // -1 is what an empty partition answers. Rendered as an instant it reads "56 years ago",
        // which is a worse answer than the absence the UI already knows how to show.
        assertFalse(service.getTopicsLastMessageTimestamps(List.of(TOPIC)).containsKey(TOPIC));
    }

    @Test
    void anEmptyPartitionDoesNotHideTheTopicsOtherRecords() {
        emptyPartition(TOPIC, 0);
        partitionNewestAt(TOPIC, 1, NOW - 120_000);
        topicsExist(TOPIC);

        assertEquals(NOW - 120_000, service.getTopicsLastMessageTimestamps(List.of(TOPIC)).get(TOPIC));
    }

    @Test
    void aPartitionThatDidNotAnswerCostsOnlyItself() {
        unreadablePartition(TOPIC, 0, new org.apache.kafka.common.errors.LeaderNotAvailableException("no leader"));
        partitionNewestAt(TOPIC, 1, NOW - 300_000);
        partitionNewestAt(OTHER, 0, NOW - 30_000);
        topicsExist(TOPIC, OTHER);

        Map<String, Long> timestamps = service.getTopicsLastMessageTimestamps(List.of(TOPIC, OTHER));

        // The instant can only be older than the truth — never invented, and never at the cost of
        // the topic beside it.
        assertEquals(NOW - 300_000, timestamps.get(TOPIC));
        assertEquals(NOW - 30_000, timestamps.get(OTHER));
    }

    @Test
    void aTopicThatCouldNotBeDescribedCostsOnlyItsOwnRow() {
        partitionNewestAt(OTHER, 0, NOW - 30_000);
        Map<String, KafkaFuture<TopicDescription>> values = new LinkedHashMap<>();
        KafkaFutureImpl<TopicDescription> failed = new KafkaFutureImpl<>();
        failed.completeExceptionally(new org.apache.kafka.common.errors.UnknownTopicOrPartitionException(TOPIC));
        values.put(TOPIC, failed);
        values.put(OTHER, KafkaFuture.completedFuture(new TopicDescription(OTHER, false,
                List.of(new TopicPartitionInfo(0, NODE, List.of(NODE), List.of(NODE))))));
        describeAnswers(values);

        // A topic deleted between the listTopics that named it and this call is an ordinary race
        // on a live cluster; allTopicNames() turned it into an empty column for everybody.
        Map<String, Long> timestamps = service.getTopicsLastMessageTimestamps(List.of(TOPIC, OTHER));

        assertFalse(timestamps.containsKey(TOPIC));
        assertEquals(NOW - 30_000, timestamps.get(OTHER));
    }
}
