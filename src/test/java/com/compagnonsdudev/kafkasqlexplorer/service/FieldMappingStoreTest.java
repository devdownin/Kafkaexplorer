// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors
package com.compagnonsdudev.kafkasqlexplorer.service;

import com.compagnonsdudev.kafkasqlexplorer.config.ExplorerConfig;
import com.compagnonsdudev.kafkasqlexplorer.config.KafkaConfig;
import com.compagnonsdudev.kafkasqlexplorer.domain.FieldMapping;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.MockConsumer;
import org.apache.kafka.clients.producer.MockProducer;
import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.Node;
import org.apache.kafka.common.PartitionInfo;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The mapping validated in Process Mining is the only artefact this application produces by
 * <em>correcting a model</em>, and it used to live in memory alone: a restart lost it, and the
 * Metrics suggestions then reported a mapping they could no longer resolve. What is pinned here is
 * that it survives, and that neither half of the persistence can take anything else down with it.
 */
class FieldMappingStoreTest {

    private static final String TOPIC = "internal.field.mappings";
    private static final TopicPartition TP = new TopicPartition(TOPIC, 0);

    private MockConsumer<String, String> consumer;
    private MockProducer<String, String> producer;
    private FieldMappingStore store;

    @BeforeEach
    void setUp() {
        KafkaConfig kafkaConfig = mock(KafkaConfig.class);
        when(kafkaConfig.getKafkaProperties()).thenReturn(Map.of("bootstrap.servers", "localhost:9092"));
        consumer = new MockConsumer<>("earliest");
        // Kafka 4.x dropped the (boolean, Serializer, Serializer) overload; a null partitioner
        // puts every record on partition 0, which is all this test needs.
        producer = new MockProducer<>(true, null, new StringSerializer(), new StringSerializer());
        store = newStore(kafkaConfig, new ExplorerConfig());
    }

    private FieldMappingStore newStore(KafkaConfig kafkaConfig, ExplorerConfig explorerConfig) {
        return new FieldMappingStore(kafkaConfig, explorerConfig, new StartupRestore(explorerConfig)) {
            @Override
            Producer<String, String> createProducer() {
                return producer;
            }

            @Override
            Consumer<String, String> createConsumer() {
                return consumer;
            }
        };
    }

    private static FieldMapping mapping(String id, String topic, String path) {
        return new FieldMapping(id, Map.of(topic, path), Map.of(), Map.of(), Map.of());
    }

    /** Seeds the mock topic with raw JSON documents, one record each, keyed by mapping id. */
    private void seed(String... payloads) {
        consumer.updatePartitions(TOPIC, List.of(
            new PartitionInfo(TOPIC, 0, null, new Node[0], new Node[0])));
        consumer.assign(List.of(TP));
        consumer.updateBeginningOffsets(Map.of(TP, 0L));
        Map<TopicPartition, Long> end = new HashMap<>();
        end.put(TP, (long) payloads.length);
        consumer.updateEndOffsets(end);
        for (int i = 0; i < payloads.length; i++) {
            // A null payload is a tombstone; the key is the mapping id the record is about, so a
            // deletion has to carry the id of what it deletes, not its own index.
            String key = payloads[i] == null ? keyOf(payloads[i > 0 ? i - 1 : 0]) : keyOf(payloads[i]);
            consumer.addRecord(new ConsumerRecord<>(TOPIC, 0, i, key, payloads[i]));
        }
    }

    /** The id inside a seeded document, or a per-record fallback when it carries none. */
    private static String keyOf(String payload) {
        if (payload == null) return "map-0";
        int at = payload.indexOf("\"id\":\"");
        if (at < 0) return "map-0";
        int from = at + 6;
        return payload.substring(from, payload.indexOf('"', from));
    }

    private static String json(String id, String topic, String path) {
        return """
            {"id":"%s","correlationIdPaths":{"%s":"%s"},
             "timestampPaths":{},"statusPaths":{},"statusEquivalences":{}}"""
            .formatted(id, topic, path);
    }

    @Test
    void aValidatedMappingIsWrittenToTheTopicUnderItsOwnId() {
        store.put(mapping("map-1", "demo.orders.1.received", "$.orderId"));

        assertEquals(1, producer.history().size());
        ProducerRecord<String, String> written = producer.history().get(0);
        assertEquals(TOPIC, written.topic());
        // Keyed by mapping id, which is what makes the topic compactable to one record per mapping.
        assertEquals("map-1", written.key());
        assertTrue(written.value().contains("\"orderId\"") || written.value().contains("$.orderId"),
            written.value());
        // And it is usable immediately, without waiting for the write.
        assertTrue(store.find("map-1").isPresent());
    }

    @Test
    void mappingsComeBackAfterARestart() {
        seed(json("map-0", "demo.orders.1.received", "$.orderId"));

        store.restoreFromKafka();

        assertTrue(store.find("map-0").isPresent());
        assertEquals("$.orderId", store.find("map-0").get().correlationIdPaths().get("demo.orders.1.received"));
    }

    /** The last record for an id wins, whether or not the topic has been compacted yet. */
    @Test
    void aLaterVersionOfAMappingReplacesTheEarlierOne() {
        seed(json("map-0", "t", "$.old"), json("map-0", "t", "$.new"));

        store.restoreFromKafka();

        assertEquals("$.new", store.find("map-0").get().correlationIdPaths().get("t"));
    }

    /** One unreadable record must not cost the mappings that are perfectly valid. */
    @Test
    void anUnreadableRecordIsSkippedAndTheRestAreRestored() {
        seed("{not json at all", json("map-1", "t", "$.id"));

        store.restoreFromKafka();

        assertTrue(store.find("map-1").isPresent());
    }

    /**
     * A key deleted by compaction, or a tombstone we ever write, means the mapping is gone — not
     * that the restore should fail.
     */
    @Test
    void aTombstoneRemovesTheMapping() {
        // Replayed in offset order, exactly as a restore reads them: the mapping, then its deletion.
        seed(json("map-0", "t", "$.id"), null);

        store.restoreFromKafka();

        assertFalse(store.find("map-0").isPresent());
    }

    /**
     * A property a later version added must not cost every mapping on the topic — the reader is
     * lenient about keys it does not know, like the two LLM services are.
     */
    @Test
    void aMappingWrittenByALaterVersionStillLoads() {
        seed("""
            {"id":"map-0","correlationIdPaths":{"t":"$.id"},"timestampPaths":{},
             "statusPaths":{},"statusEquivalences":{},"validatedBy":"someone","version":3}""");

        store.restoreFromKafka();

        assertTrue(store.find("map-0").isPresent());
    }

    /** An absent topic is the ordinary first-run case, not a failure. */
    @Test
    void anAbsentTopicRestoresNothingAndDoesNotThrow() {
        store.restoreFromKafka();

        assertFalse(store.find("map-0").isPresent());
    }

    /**
     * The whole point of the store is still to answer the pipeline. A broker that cannot be
     * reached at startup leaves it empty and the app running — never a startup that fails.
     */
    @Test
    void aFailingRestoreLeavesTheStoreUsable() {
        ExplorerConfig config = new ExplorerConfig();
        FieldMappingStore failing = new FieldMappingStore(
            mock(KafkaConfig.class), config, new StartupRestore(config)) {
            @Override
            Consumer<String, String> createConsumer() {
                throw new IllegalStateException("no broker");
            }

            @Override
            Producer<String, String> createProducer() {
                return producer;
            }
        };

        failing.init();   // must not throw

        failing.put(mapping("map-9", "t", "$.id"));
        assertTrue(failing.find("map-9").isPresent());
    }

    /**
     * An absent topic is a complete answer about an empty store; an unreachable broker is not.
     *
     * <p>The difference is what decides whether {@code MetricService} seeds its examples, and what
     * the log is allowed to claim. It was invisible before: both ended in the same
     * {@code log.debug}, i.e. no line at all on a default install.
     */
    @Test
    void anAbsentTopicIsACompleteReadAndAFailureIsNot() {
        assertTrue(store.restoreFromKafka());

        ExplorerConfig config = new ExplorerConfig();
        FieldMappingStore failing = new FieldMappingStore(
            mock(KafkaConfig.class), config, new StartupRestore(config)) {
            @Override
            Consumer<String, String> createConsumer() {
                throw new IllegalStateException("no broker");
            }
        };

        assertFalse(failing.restoreFromKafka());
    }

    /**
     * The second restore of a boot reads the first one's verdict instead of re-paying for it.
     *
     * <p>Both restores hit the same broker within a second of each other, and each used to wait out
     * its own 5 s API timeout to establish the same fact — 10.1 s of a 14.5 s boot, measured with
     * nothing listening. Here the verdict is already recorded, so no consumer is opened at all:
     * {@code createConsumer} would throw if it were.
     */
    @Test
    void aBrokerAlreadyKnownUnreachableIsNotAskedTwice() {
        ExplorerConfig config = new ExplorerConfig();
        StartupRestore startupRestore = new StartupRestore(config);
        startupRestore.brokerDidNotAnswer("metric configurations", "Connection refused");

        FieldMappingStore second = new FieldMappingStore(mock(KafkaConfig.class), config, startupRestore) {
            @Override
            Consumer<String, String> createConsumer() {
                throw new AssertionError("the broker had already been declared unreachable");
            }
        };

        assertFalse(second.restoreFromKafka());
    }

    @Test
    void aMappingWithoutAnIdIsNeitherKeptNorWritten() {
        store.put(new FieldMapping(null, Map.of(), Map.of(), Map.of(), Map.of()));
        store.put(null);

        assertTrue(producer.history().isEmpty());
    }
}
