// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors
package com.yourcompany.kafkasqlexplorer.web;

import com.yourcompany.kafkasqlexplorer.config.ClaudeConfig;
import com.yourcompany.kafkasqlexplorer.config.KafkaConfig;
import com.yourcompany.kafkasqlexplorer.service.AuditService;
import com.yourcompany.kafkasqlexplorer.service.FlinkSqlService;
import com.yourcompany.kafkasqlexplorer.service.KafkaAdminService;
import com.yourcompany.kafkasqlexplorer.service.SseEmitterManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The settings endpoint's contract.
 *
 * <p>Standalone MockMvc, like {@link StreamFlowControllerTest}: registering only this controller is
 * what makes the "no {@code GET /config} mapping" assertion mean something — that mapping existed,
 * shadowed {@link SpaController} and turned a refresh of the Settings page into a 500.
 */
class ConfigControllerTest {

    private KafkaConfig kafkaConfig;
    private KafkaAdminService kafkaAdminService;
    private AuditService auditService;
    private FlinkSqlService flinkSqlService;
    private SseEmitterManager sseEmitterManager;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        kafkaConfig = new KafkaConfig();
        kafkaConfig.setBootstrapServers("old:9092");
        kafkaAdminService = Mockito.mock(KafkaAdminService.class);
        auditService = Mockito.mock(AuditService.class);
        flinkSqlService = Mockito.mock(FlinkSqlService.class);
        sseEmitterManager = Mockito.mock(SseEmitterManager.class);
        when(flinkSqlService.getActiveJobsDetails()).thenReturn(Map.of());
        when(sseEmitterManager.activeSessions()).thenReturn(0);

        mockMvc = MockMvcBuilders
            .standaloneSetup(new ConfigController(kafkaConfig, kafkaAdminService, new ClaudeConfig(),
                auditService, flinkSqlService, sseEmitterManager))
            .build();
    }

    /** `/config` belongs to the SPA. A controller mapping there answers a refresh with a 500. */
    @Test
    void thereIsNoServerSideConfigPage() throws Exception {
        mockMvc.perform(get("/config")).andExpect(status().isNotFound());
        mockMvc.perform(post("/config").param("bootstrapServers", "new:9092"))
            .andExpect(status().isNotFound());
    }

    @Test
    void anIdleClusterAcceptsTheChange() throws Exception {
        mockMvc.perform(post("/api/config").contentType(MediaType.APPLICATION_JSON)
                .content("{\"bootstrapServers\":\"new:9092\"}"))
            .andExpect(status().isOk());

        assertEquals("new:9092", kafkaConfig.getBootstrapServers());
        verify(kafkaAdminService).init();
    }

    /** Repointing under a running audit would have one report describe two clusters. */
    @Test
    void repointingIsRefusedWhileWorkRunsAgainstTheCurrentCluster() throws Exception {
        when(auditService.runningAuditId()).thenReturn("audit-7");

        mockMvc.perform(post("/api/config").contentType(MediaType.APPLICATION_JSON)
                .content("{\"bootstrapServers\":\"new:9092\"}"))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("audit-7")))
            .andExpect(jsonPath("$.inFlight").isArray());

        assertEquals("old:9092", kafkaConfig.getBootstrapServers(), "nothing was applied");
        verify(kafkaAdminService, never()).init();
    }

    /** The operator who knows what is running is told, not blocked. */
    @Test
    void forceAppliesTheChangeAnyway() throws Exception {
        when(auditService.runningAuditId()).thenReturn("audit-7");

        mockMvc.perform(post("/api/config").contentType(MediaType.APPLICATION_JSON)
                .content("{\"bootstrapServers\":\"new:9092\",\"force\":true}"))
            .andExpect(status().isOk());

        assertEquals("new:9092", kafkaConfig.getBootstrapServers());
    }

    /**
     * Only a change of cluster is guarded. Editing an LLM setting while an audit runs touches
     * nothing the audit reads, and refusing it would be a rule with no reason behind it.
     */
    @Test
    void llmSettingsAreNotGuardedByClusterActivity() throws Exception {
        when(auditService.runningAuditId()).thenReturn("audit-7");

        mockMvc.perform(post("/api/config").contentType(MediaType.APPLICATION_JSON)
                .content("{\"llmModel\":\"claude-opus-4-6\"}"))
            .andExpect(status().isOk());
    }

    /** Live Process Mining sessions and Flink jobs count as work in flight too. */
    @Test
    void flinkJobsAndLiveSessionsAlsoRefuseTheChange() throws Exception {
        when(flinkSqlService.getActiveJobsDetails())
            .thenReturn(Map.of("j1", Mockito.mock(FlinkSqlService.JobInfo.class)));
        when(sseEmitterManager.activeSessions()).thenReturn(2);

        mockMvc.perform(post("/api/config").contentType(MediaType.APPLICATION_JSON)
                .content("{\"mode\":\"SSL\"}"))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("Flink job")))
            .andExpect(jsonPath("$.message")
                .value(org.hamcrest.Matchers.containsString("live Process Mining session")));
    }
}
