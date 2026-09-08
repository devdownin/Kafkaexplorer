// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors
package com.compagnonsdudev.kafkasqlexplorer.mcp.console;

import com.compagnonsdudev.kafkasqlexplorer.config.KafkaConfig;
import com.compagnonsdudev.kafkasqlexplorer.mcp.McpProperties;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.MockConsumer;
import org.apache.kafka.clients.consumer.OffsetAndTimestamp;
import org.apache.kafka.clients.consumer.OffsetResetStrategy;
import org.apache.kafka.common.Node;
import org.apache.kafka.common.PartitionInfo;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class McpAuditReplayServiceTest {

    private static final String TOPIC = "internal.mcp.audit";
    private static final TopicPartition PARTITION = new TopicPartition(TOPIC, 0);

    private final McpProperties properties = new McpProperties();

    /** A replay service driven by a MockConsumer, so no broker is needed. */
    private McpAuditReplayService serviceOver(MockConsumer<byte[], byte[]> consumer) {
        return new McpAuditReplayService(mock(KafkaConfig.class), properties) {
            @Override
            protected Consumer<byte[], byte[]> createConsumer(Properties props) {
                return consumer;
            }
        };
    }

    /**
     * A {@link MockConsumer} with a time index.
     *
     * <p>{@code offsetsForTimes} is "Not implemented yet." in Kafka's own mock, and it is the call
     * this service seeks with — so the mock is given the one behaviour a broker has here: the first
     * record at or after the requested time, or {@code null} when the partition holds none.
     */
    private static final class TimeIndexedConsumer extends MockConsumer<byte[], byte[]> {
        private final List<ConsumerRecord<byte[], byte[]>> records;

        TimeIndexedConsumer(List<ConsumerRecord<byte[], byte[]>> records) {
            super(OffsetResetStrategy.EARLIEST);
            this.records = records;
        }

        @Override
        public synchronized Map<TopicPartition, OffsetAndTimestamp> offsetsForTimes(
                Map<TopicPartition, Long> timestamps) {
            Map<TopicPartition, OffsetAndTimestamp> found = new HashMap<>();
            timestamps.forEach((partition, target) -> found.put(partition, records.stream()
                    .filter(record -> record.timestamp() >= target)
                    .findFirst()
                    .map(record -> new OffsetAndTimestamp(record.offset(), record.timestamp()))
                    .orElse(null)));
            return found;
        }
    }

    private static MockConsumer<byte[], byte[]> consumerWith(long beginningOffset,
                                                             List<ConsumerRecord<byte[], byte[]>> records) {
        MockConsumer<byte[], byte[]> consumer = new TimeIndexedConsumer(records);
        Node node = new Node(0, "localhost", 9092);
        consumer.updatePartitions(TOPIC, List.of(
                new PartitionInfo(TOPIC, 0, node, new Node[]{node}, new Node[]{node})));

        Map<TopicPartition, Long> beginning = new HashMap<>();
        beginning.put(PARTITION, beginningOffset);
        consumer.updateBeginningOffsets(beginning);

        Map<TopicPartition, Long> end = new HashMap<>();
        end.put(PARTITION, beginningOffset + records.size());
        consumer.updateEndOffsets(end);

        // MockConsumer refuses a record for an unassigned partition; the service assigns the same
        // partition again, which keeps what is already buffered.
        consumer.assign(List.of(PARTITION));
        records.forEach(consumer::addRecord);
        return consumer;
    }

    private static ConsumerRecord<byte[], byte[]> call(long offset, long timestamp, String tool) {
        return new ConsumerRecord<>(TOPIC, 0, offset, timestamp,
                org.apache.kafka.common.record.TimestampType.CREATE_TIME, 0, 0,
                "agent-7".getBytes(StandardCharsets.UTF_8),
                ("{\"tool\":\"" + tool + "\"}").getBytes(StandardCharsets.UTF_8),
                new org.apache.kafka.common.header.internals.RecordHeaders(), java.util.Optional.empty());
    }

    @Test
    void a_topic_that_was_never_created_is_an_empty_trail_not_an_empty_window() {
        // The two are opposite conclusions from the same empty list.
        MockConsumer<byte[], byte[]> consumer = new TimeIndexedConsumer(List.of());

        McpAuditReplayService.Replay replay = serviceOver(consumer)
                .replay(Instant.ofEpochMilli(0), Instant.now());

        assertThat(replay.topicExists()).isFalse();
        assertThat(replay.calls()).isEmpty();
        assertThat(replay.warnings()).anySatisfy(w -> assertThat(w).contains("empty trail"));
    }

    @Test
    void the_calls_in_the_window_come_back_oldest_first() {
        long now = System.currentTimeMillis();
        MockConsumer<byte[], byte[]> consumer = consumerWith(0L, List.of(
                call(0, now - 3_000, "kex_list_topics"),
                call(1, now - 2_000, "kex_sql_query")));

        McpAuditReplayService.Replay replay = serviceOver(consumer)
                .replay(Instant.ofEpochMilli(now - 10_000), Instant.ofEpochMilli(now));

        assertThat(replay.calls()).hasSize(2);
        assertThat(replay.calls().get(0).get("tool").asText()).isEqualTo("kex_list_topics");
        assertThat(replay.topicExists()).isTrue();
    }

    @Test
    void a_record_after_the_window_is_scanned_but_not_returned() {
        long now = System.currentTimeMillis();
        MockConsumer<byte[], byte[]> consumer = consumerWith(0L, List.of(
                call(0, now - 3_000, "kex_list_topics"),
                call(1, now + 60_000, "kex_sql_query")));

        McpAuditReplayService.Replay replay = serviceOver(consumer)
                .replay(Instant.ofEpochMilli(now - 10_000), Instant.ofEpochMilli(now));

        assertThat(replay.calls()).hasSize(1);
        assertThat(replay.recordsScanned()).isEqualTo(2);
    }

    @Test
    void retention_that_removed_the_start_of_the_window_is_said_rather_than_read_as_quiet() {
        // An absence over a window the scan never reached is not evidence of absence.
        long now = System.currentTimeMillis();
        MockConsumer<byte[], byte[]> consumer = consumerWith(500L, List.of(
                call(500, now - 1_000, "kex_list_topics")));

        McpAuditReplayService.Replay replay = serviceOver(consumer)
                .replay(Instant.ofEpochMilli(now - 10_000), Instant.ofEpochMilli(now));

        assertThat(replay.scanReachedWindowStart()).isFalse();
        assertThat(replay.warnings()).anySatisfy(
                w -> assertThat(w).contains("not evidence of absence"));
    }

    @Test
    void a_record_that_is_not_readable_json_is_named_rather_than_failing_the_replay() {
        long now = System.currentTimeMillis();
        ConsumerRecord<byte[], byte[]> broken = new ConsumerRecord<>(TOPIC, 0, 0L, now - 1_000,
                org.apache.kafka.common.record.TimestampType.CREATE_TIME, 0, 0,
                null, "not json".getBytes(StandardCharsets.UTF_8),
                new org.apache.kafka.common.header.internals.RecordHeaders(), java.util.Optional.empty());
        MockConsumer<byte[], byte[]> consumer = consumerWith(0L, List.of(broken));

        McpAuditReplayService.Replay replay = serviceOver(consumer)
                .replay(Instant.ofEpochMilli(now - 10_000), Instant.ofEpochMilli(now));

        assertThat(replay.calls()).isEmpty();
        assertThat(replay.warnings()).anySatisfy(w -> assertThat(w).contains("not readable"));
    }

    @Test
    void a_broker_that_cannot_be_read_says_so_rather_than_returning_an_empty_window() {
        McpAuditReplayService service = new McpAuditReplayService(mock(KafkaConfig.class), properties) {
            @Override
            protected Consumer<byte[], byte[]> createConsumer(Properties props) {
                throw new IllegalStateException("no broker");
            }
        };

        McpAuditReplayService.Replay replay = service.replay(Instant.ofEpochMilli(0), Instant.now());

        assertThat(replay.calls()).isEmpty();
        assertThat(replay.scanReachedWindowStart()).isFalse();
        assertThat(replay.warnings()).anySatisfy(w -> assertThat(w).contains("no broker"));
    }
}
