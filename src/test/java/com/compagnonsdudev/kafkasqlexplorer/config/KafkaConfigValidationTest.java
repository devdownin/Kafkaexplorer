// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors
package com.compagnonsdudev.kafkasqlexplorer.config;

import org.apache.kafka.clients.CommonClientConfigs;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The connection settings are checked at startup, and this pins <em>why</em>.
 *
 * <p>{@link KafkaConfig#getKafkaProperties()} decides the security protocol with an if/else that
 * has no final branch, so every value it does not recognise took the PLAIN one. The failure that
 * matters is not a typo being tolerated: it is that {@code mode: SASL_SSL} — the spelling Kafka's
 * own {@code security.protocol} uses, so an entirely natural thing to write — produced a
 * <em>plaintext</em> connection, with nothing anywhere saying so. A misconfiguration that refuses
 * to start is a five-minute fix; one that silently drops the encryption is not discovered at all.
 */
class KafkaConfigValidationTest {

    private static KafkaConfig config(String mode) {
        KafkaConfig config = new KafkaConfig();
        config.setBootstrapServers("broker:9092");
        config.setMode(mode);
        return config;
    }

    @Test
    void theShippedDefaultsAreValid() {
        assertEquals(List.of(), new KafkaConfig().validationProblems());
    }

    @Test
    void theThreeKnownModesPass() {
        assertEquals(List.of(), config("PLAIN").validationProblems());
        assertEquals(List.of(), config("SSL").validationProblems());

        KafkaConfig cloud = config("CONFLUENT_CLOUD");
        cloud.setConfluentKey("key");
        cloud.setConfluentSecret("secret");
        assertEquals(List.of(), cloud.validationProblems());
    }

    /** The case the whole check exists for. */
    @Test
    void aModeThatWouldSilentlyBecomePlaintextIsRefused() {
        KafkaConfig config = config("SASL_SSL");

        List<String> problems = config.validationProblems();

        assertEquals(1, problems.size());
        assertTrue(problems.get(0).contains("kafka.mode"), problems.get(0));
        assertTrue(problems.get(0).contains("SASL_SSL"), problems.get(0));
        // The message has to name what the accepted values are, or it only says "no".
        assertTrue(problems.get(0).contains("CONFLUENT_CLOUD"), problems.get(0));

        assertThrows(IllegalStateException.class, config::validateAtStartup);
    }

    /** And it really was plaintext: no security protocol at all is PLAINTEXT to a Kafka client. */
    @Test
    void thatSameModeUsedToProduceAPlaintextClient() {
        Map<String, String> props = config("SASL_SSL").getKafkaProperties();

        assertTrue(props.get(CommonClientConfigs.SECURITY_PROTOCOL_CONFIG) == null
            || props.get(CommonClientConfigs.SECURITY_PROTOCOL_CONFIG).startsWith("PLAINTEXT"));
    }

    /** Surrounding whitespace is accepted here, so it must not take the plaintext branch there. */
    @Test
    void aModeIsTrimmedOnBothSides() {
        KafkaConfig config = config("  SSL  ");

        assertEquals(List.of(), config.validationProblems());
        assertEquals("SSL", config.getKafkaProperties().get(CommonClientConfigs.SECURITY_PROTOCOL_CONFIG));
    }

    /**
     * Without credentials the JAAS string carried the four letters {@code null} as the username,
     * which the broker rejects with a message naming neither the property nor this application.
     */
    @Test
    void confluentCloudWithoutCredentialsIsRefused() {
        List<String> problems = config("CONFLUENT_CLOUD").validationProblems();

        assertEquals(2, problems.size());
        assertTrue(problems.stream().anyMatch(p -> p.contains("kafka.confluent-key")), problems.toString());
        assertTrue(problems.stream().anyMatch(p -> p.contains("kafka.confluent-secret")), problems.toString());
    }

    @Test
    void anEmptyBootstrapAddressIsRefused() {
        KafkaConfig config = config("PLAIN");
        config.setBootstrapServers("  ");

        assertTrue(config.validationProblems().stream()
            .anyMatch(p -> p.contains("kafka.bootstrap-servers")), config.validationProblems().toString());
    }

    @Test
    void aTruststoreThatIsNotThereIsRefusedWithItsPath() {
        KafkaConfig config = config("SSL");
        config.setTruststorePath("/nowhere/truststore.jks");

        List<String> problems = config.validationProblems();

        assertEquals(1, problems.size());
        assertTrue(problems.get(0).contains("/nowhere/truststore.jks"), problems.get(0));
    }

    @Test
    void aTruststoreThatIsThereIsAccepted() throws Exception {
        Path store = Files.createTempFile("truststore", ".jks");
        try {
            KafkaConfig config = config("SSL");
            config.setTruststorePath(store.toString());

            assertEquals(List.of(), config.validationProblems());
        } finally {
            Files.deleteIfExists(store);
        }
    }
}
