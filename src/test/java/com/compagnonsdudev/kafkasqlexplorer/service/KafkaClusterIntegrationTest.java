// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors
package com.compagnonsdudev.kafkasqlexplorer.service;

import com.compagnonsdudev.kafkasqlexplorer.config.KafkaConfig;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.utility.DockerImageName;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.*;

/**
 * End-to-end checks against a real Kafka 4.3 broker (KRaft, native image) that the
 * mocked unit suite cannot cover: the kafka-clients 4.x admin surface used by
 * {@link KafkaAdminService} (metadata quorum, group listing, feature lag), record
 * sampling through the ConsumerRecord copy path, and a KIP-848 consumer actually
 * joining with the new rebalance protocol.
 *
 * Skipped automatically when no Docker daemon is available (e.g. restricted
 * sandboxes); runs in CI and on developer machines.
 */
@Testcontainers(disabledWithoutDocker = true)
class KafkaClusterIntegrationTest {

    @Container
    static final KafkaContainer KAFKA = new KafkaContainer(DockerImageName.parse("apache/kafka-native:4.3.1"));

    private static final String TOPIC = "it.orders.json";

    private static KafkaAdminService adminService;

    @BeforeAll
    static void setUp() {
        KafkaConfig config = new KafkaConfig();
        config.setBootstrapServers(KAFKA.getBootstrapServers());
        config.setSchemaRegistryUrl(null); // no registry in this stack
        adminService = new KafkaAdminService(config);
        adminService.init();

        Properties props = new Properties();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers());
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        try (KafkaProducer<String, String> producer = new KafkaProducer<>(props)) {
            for (int i = 0; i < 3; i++) {
                producer.send(new ProducerRecord<>(TOPIC, "key-" + i, "{\"id\": " + i + ", \"status\": \"NEW\"}"));
            }
            producer.flush();
        }
    }

    @AfterAll
    static void tearDown() {
        if (adminService != null) {
            adminService.close();
        }
    }

    @Test
    void listsTopicsAndCountsMessages() throws Exception {
        List<String> topics = adminService.listTopics();
        assertTrue(topics.contains(TOPIC), "produced topic should be listed");

        Map<String, Long> sizes = adminService.getTopicsSize(List.of(TOPIC));
        assertEquals(3L, sizes.get(TOPIC), "all produced messages should be counted");
    }

    @Test
    void samplesEarliestRecordsWithValues() {
        List<ConsumerRecord<String, String>> records = adminService.getEarliestRecords(TOPIC, 10);
        assertEquals(3, records.size());
        assertTrue(records.get(0).value().contains("\"status\""), "JSON payload should round-trip");
        assertNotNull(records.get(0).key());
    }

    @Test
    void clusterDetailsExposeKraftQuorumGroupsAndFeatures() {
        Map<String, Object> details = adminService.getClusterDetails();
        assertNull(details.get("error"), "cluster details should not degrade to an error");
        assertNotNull(details.get("clusterId"));

        @SuppressWarnings("unchecked")
        Map<String, Object> quorum = (Map<String, Object>) details.get("kraftQuorum");
        assertNotNull(quorum, "KRaft broker must expose the metadata quorum");
        assertTrue((int) quorum.get("leaderId") >= 0);
        assertFalse(((List<?>) quorum.get("voters")).isEmpty(), "single combined node is a voter");

        assertNotNull(details.get("groups"), "Kafka 4 broker must support ListGroups");

        // A freshly formatted 4.3 broker finalizes metadata.version at the release level:
        // it must never be reported as lagging (that signal is reserved for real
        // half-finished rolling upgrades).
        List<Map<String, Object>> lagging = adminService.getLaggingFeatures();
        assertTrue(lagging.stream().noneMatch(f -> "metadata.version".equals(f.get("feature"))),
                "fresh cluster should not report a lagging metadata.version, got: " + lagging);
    }

    @Test
    void kip848ConsumerJoinsAndIsListedWithConsumerType() throws Exception {
        String groupId = "it-kip848-group";
        Properties props = new Properties();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers());
        props.put(ConsumerConfig.GROUP_ID_CONFIG, groupId);
        props.put(ConsumerConfig.GROUP_PROTOCOL_CONFIG, "consumer"); // KIP-848
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());

        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(props)) {
            consumer.subscribe(List.of(TOPIC));

            int received = 0;
            long deadline = System.currentTimeMillis() + 30_000;
            while (received < 3 && System.currentTimeMillis() < deadline) {
                received += consumer.poll(Duration.ofMillis(500)).count();
            }
            assertEquals(3, received, "KIP-848 consumer should receive every produced message");

            @SuppressWarnings("unchecked")
            List<Map<String, Object>> groups =
                    (List<Map<String, Object>>) adminService.getClusterDetails().get("groups");
            Map<String, Object> group = groups.stream()
                    .filter(g -> groupId.equals(g.get("groupId")))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError("group " + groupId + " not listed in " + groups));
            assertEquals("CONSUMER", group.get("type"), "KIP-848 group must be listed with the CONSUMER type");
        }
    }
}
