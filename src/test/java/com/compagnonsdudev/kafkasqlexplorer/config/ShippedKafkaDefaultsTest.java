// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors
package com.compagnonsdudev.kafkasqlexplorer.config;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * What the shipped configuration actually puts in force, read from the running context.
 *
 * <p>`kafka.consumer-group-protocol` is deliberately two different values in two places: the field
 * in {@link KafkaConfig} keeps `classic`, which works against any broker version and is what an
 * absent property must still mean, while `application.yml` ships `consumer` — KIP-848 — because
 * every bundled stack runs Kafka 4.3 in KRaft mode and already set it explicitly. A split like
 * that is only safe while something reads it: `docs/check-config-table.py` compares the
 * *documented* default against the YAML and would not notice the YAML line being deleted, and the
 * Java default is exactly what would then silently take over — the deployment would fall back to
 * the classic protocol with nothing on screen or in the log saying so.
 *
 * <p>So this asserts the value in force through the real binder, not the constant. It is the same
 * distinction the rest of this codebase keeps making: a default that is written down is not the
 * same claim as a default that was observed.
 */
@SpringBootTest
class ShippedKafkaDefaultsTest {

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        // Nothing listens here; the context must not be pointed at a real broker to answer a
        // question about configuration.
        registry.add("kafka.bootstrap-servers", () -> "localhost:9092");
    }

    @Autowired
    private KafkaConfig kafkaConfig;

    @Test
    void theShippedRebalanceProtocolIsKip848() {
        assertEquals("consumer", kafkaConfig.getConsumerGroupProtocol(),
            "application.yml ships the KIP-848 protocol; losing that line would silently fall back "
                + "to the classic one through KafkaConfig's own default");
    }

    @Test
    void theCodeDefaultStaysTheCompatibleOne() {
        // The other half, and the reason the split is deliberate: a deployment that sets no
        // property at all — an embedder, a test, a trimmed configuration — must still get the
        // protocol that works against a 3.x broker.
        assertEquals("classic", new KafkaConfig().getConsumerGroupProtocol());
    }
}
