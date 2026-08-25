// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors
package com.compagnonsdudev.kafkasqlexplorer.web;

import com.compagnonsdudev.kafkasqlexplorer.domain.ProcessMiningResult;
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
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;
import java.util.Locale;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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
    private FieldProfilingService fieldProfilingService;
    private LlmAnalysisService llmAnalysisService;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        kafkaLiveConsumer = Mockito.mock(KafkaLiveConsumer.class);
        fieldProfilingService = Mockito.mock(FieldProfilingService.class);
        llmAnalysisService = Mockito.mock(LlmAnalysisService.class);
        mockMvc = MockMvcBuilders
            .standaloneSetup(new ProcessMiningController(
                fieldProfilingService,
                llmAnalysisService,
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

    /**
     * A request that names no topic is refused here, not by a NullPointerException three layers
     * down. It used to reach {@code profile(null, …)}, where the read throws and the 500 that comes
     * back says nothing about the missing field. The refusal is served in the record's own shape,
     * with {@code error} set, which is what the page reads on a 200 and on a 4xx alike.
     */
    @Test
    void refusesAProfilingRequestThatNamesNoTopic() throws Exception {
        for (String body : new String[]{"{}", "{\"topics\":[]}", "{\"topics\":[\"  \"]}"}) {
            mockMvc.perform(post("/api/process-mining/profiling/start")
                    .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").exists());
        }

        verify(fieldProfilingService, never()).profile(any(), any());
    }

    @Test
    void refusesASnapshotRequestThatNamesNoTopic() throws Exception {
        mockMvc.perform(post("/api/process-mining/snapshot")
                .contentType(MediaType.APPLICATION_JSON).content("{\"topics\":[]}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error").exists());

        verify(llmAnalysisService, never()).analyzeSnapshot(any(), any(), any(), any());
    }

    /**
     * The mapping validated at step 3 is what says which field correlates a record across topics.
     * The store is bounded and restored best-effort at boot, so it can legitimately be gone — and
     * the analysis then runs on whatever the model infers instead. That was said to the log and to
     * nobody else, so an operator who had just corrected a mapping by hand had no way to know it
     * had not been applied.
     */
    @Test
    void saysWhenTheValidatedMappingIsNoLongerHeld() throws Exception {
        Mockito.when(llmAnalysisService.analyzeSnapshot(any(), any(), any(), any()))
            .thenReturn(new ProcessMiningResult("flowchart TD", "ok",
                List.of(), List.of(), List.of()));

        mockMvc.perform(post("/api/process-mining/snapshot")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"topics\":[\"orders\"],\"fieldMappingId\":\"" + UUID.randomUUID() + "\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.coverage.warnings[0]").value(
                org.hamcrest.Matchers.containsString("no longer held")));
    }

    /** No mapping asked for, nothing to warn about: the note must not fire on every run. */
    @Test
    void saysNothingWhenNoMappingWasAskedFor() throws Exception {
        Mockito.when(llmAnalysisService.analyzeSnapshot(any(), any(), any(), any()))
            .thenReturn(new ProcessMiningResult("flowchart TD", "ok",
                List.of(), List.of(), List.of()));

        mockMvc.perform(post("/api/process-mining/snapshot")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"topics\":[\"orders\"]}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.coverage").doesNotExist());
    }
}
