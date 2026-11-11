// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors
package com.compagnonsdudev.kafkasqlexplorer.service;

import com.compagnonsdudev.kafkasqlexplorer.config.ClaudeConfig;
import com.compagnonsdudev.kafkasqlexplorer.config.AssistantProperties;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Every call to a model counted and timed under its consumer — Process Mining's and the
 * assistant's in one series, so the two uses of one model can be compared in production.
 */
class MeteredLlmClientTest {

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();

    private double count(String consumer, String outcome) {
        var counter = registry.find("explorer_llm_requests").tags("consumer", consumer, "outcome", outcome).counter();
        return counter == null ? 0 : counter.count();
    }

    @Test
    void countsAndTimesEachCallUnderItsConsumer() {
        LlmClient ok = (system, user) -> "fine";
        LlmClient metered = new MeteredLlmClient(ok, "process_mining", registry);

        metered.generate("s", "u");
        metered.generateWithMeta("s", "u");

        assertEquals(2, count("process_mining", "ok"));
        assertEquals(2, registry.find("explorer_llm_request_duration").tag("consumer", "process_mining").timer().count());
    }

    @Test
    void aFailureIsCountedAndStillThrown() {
        LlmClient failing = (system, user) -> { throw new IllegalStateException("connection refused"); };
        LlmClient metered = new MeteredLlmClient(failing, "assistant", registry);

        assertThrows(IllegalStateException.class, () -> metered.generate("s", "u"));
        assertEquals(1, count("assistant", "error"));
        assertEquals(0, count("assistant", "ok"));
    }

    @Test
    void bothConsumersReportIntoTheSameSeries() {
        ClaudeConfig claude = new ClaudeConfig();
        claude.setProvider(ClaudeConfig.Provider.OLLAMA);
        AssistantProperties properties = new AssistantProperties();
        // Built through the Spring constructors: what production wires, minus the network.
        LlmClient pm = new LlmClientProvider(claude, registry).get();
        LlmClient assistant = new AssistantLlm(claude, properties, registry).client(claude);

        assertInstanceOf(MeteredLlmClient.class, pm);
        assertInstanceOf(MeteredLlmClient.class, assistant);
    }
}
