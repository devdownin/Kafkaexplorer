// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors
package com.compagnonsdudev.kafkasqlexplorer.web;

import com.compagnonsdudev.kafkasqlexplorer.service.AuditPromptCatalog;
import com.compagnonsdudev.kafkasqlexplorer.config.ExplorerConfig;
import com.compagnonsdudev.kafkasqlexplorer.config.KafkaConfig;
import com.compagnonsdudev.kafkasqlexplorer.service.FieldMappingStore;
import com.compagnonsdudev.kafkasqlexplorer.service.FieldProfilingService;
import com.compagnonsdudev.kafkasqlexplorer.service.KafkaLiveConsumer;
import com.compagnonsdudev.kafkasqlexplorer.service.LlmAnalysisService;
import com.compagnonsdudev.kafkasqlexplorer.service.SseEmitterManager;
import com.compagnonsdudev.kafkasqlexplorer.service.StartupRestore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.Locale;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The stop endpoint's contract. Standalone MockMvc — nothing here needs a Spring context.
 *
 * <p>The id arrives in the URL path and used to be logged as it came, which CodeQL flagged as a
 * log-injection sink: a {@code %0A} lets a caller forge whatever line it likes in the file that is
 * supposed to be the record of what happened. Session ids are server-minted UUIDs, so the fix is to
 * refuse anything else rather than to escape it — the untrusted value never reaches the log at all.
 */
class ProcessMiningControllerTest {

    private KafkaLiveConsumer kafkaLiveConsumer;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        kafkaLiveConsumer = Mockito.mock(KafkaLiveConsumer.class);
        mockMvc = MockMvcBuilders
            .standaloneSetup(new ProcessMiningController(
                Mockito.mock(FieldProfilingService.class),
                Mockito.mock(LlmAnalysisService.class),
                kafkaLiveConsumer,
                Mockito.mock(SseEmitterManager.class),
                Mockito.mock(AuditPromptCatalog.class),
                newFieldMappingStore()))
            .build();
    }

    /**
     * A real store, but one that never opens a consumer: this controller does not read it, and a
     * restore against no broker would only spend the startup budget for nothing.
     */
    private static FieldMappingStore newFieldMappingStore() {
        ExplorerConfig config = new ExplorerConfig();
        return new FieldMappingStore(new KafkaConfig(), config, new StartupRestore(config));
    }

    @Test
    void stopsTheSessionNamedByAValidId() throws Exception {
        String sessionId = UUID.randomUUID().toString();

        mockMvc.perform(delete("/api/process-mining/live/" + sessionId))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.stopped").value(true));

        verify(kafkaLiveConsumer).stopSession(sessionId);
    }

    /** An id that is not a session id names no session — refuse it, and never echo it. */
    @Test
    void refusesAMalformedIdWithoutTouchingTheConsumer() throws Exception {
        for (String malformed : new String[]{
                "not-a-uuid",
                "../../etc/passwd",
                "%s".formatted(UUID.randomUUID()) + "-extra",
                ""}) {
            mockMvc.perform(delete("/api/process-mining/live/{id}", malformed))
                .andExpect(status().is4xxClientError());
        }

        verify(kafkaLiveConsumer, never()).stopSession(anyString());
    }

    /**
     * The log-injection case itself: a newline in the path must not reach the logger, and must not
     * be treated as a session id either.
     */
    @Test
    void refusesAnIdCarryingALineBreak() throws Exception {
        mockMvc.perform(delete("/api/process-mining/live/{id}",
                UUID.randomUUID() + "\nINFO  forged log line"))
            .andExpect(status().is4xxClientError());

        verify(kafkaLiveConsumer, never()).stopSession(anyString());
    }

    /**
     * The id is reconstructed rather than passed through, so what reaches the session map is the
     * canonical rendering. An id differing from the minted one only by case used to match no
     * session at all, and said {@code stopped: true} while stopping nothing.
     */
    @Test
    void normalisesTheIdBeforeUsingIt() throws Exception {
        String sessionId = UUID.randomUUID().toString();

        mockMvc.perform(delete("/api/process-mining/live/" + sessionId.toUpperCase(Locale.ROOT)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.sessionId").value(sessionId));

        verify(kafkaLiveConsumer).stopSession(sessionId);
    }
}
