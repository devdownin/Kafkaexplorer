// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors
package com.compagnonsdudev.kafkasqlexplorer.web;

import com.compagnonsdudev.kafkasqlexplorer.config.ExplorerConfig;
import com.compagnonsdudev.kafkasqlexplorer.config.KafkaConfig;
import com.compagnonsdudev.kafkasqlexplorer.service.FlinkSqlService;
import com.compagnonsdudev.kafkasqlexplorer.service.KafkaAdminService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * What the header's connection pill is fed.
 *
 * <p>It renders {@code clusterName}, whose shipped default was {@code KRAFT 4.2} — a Kafka version
 * asserted by every deployment without anything ever having asked the broker, in the one element
 * whose job is to say what you are connected to. Nothing overrode it: not a compose stack, not the
 * Settings page, not the documented environment variables. And {@code POST /api/config} repoints
 * the cluster at runtime while the label stays put.
 *
 * <p>The label is now a name, and what is verifiable travels beside it: {@code bootstrapServers},
 * read from the running {@link KafkaConfig} so that a runtime repoint is reflected.
 *
 * <p>Standalone MockMvc: nothing here needs a Spring context.
 */
class DashboardControllerTest {

    private KafkaAdminService kafkaAdminService;
    private FlinkSqlService flinkSqlService;
    private ExplorerConfig explorerConfig;
    private KafkaConfig kafkaConfig;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        kafkaAdminService = Mockito.mock(KafkaAdminService.class);
        flinkSqlService = Mockito.mock(FlinkSqlService.class);
        explorerConfig = new ExplorerConfig();
        kafkaConfig = new KafkaConfig();

        when(flinkSqlService.listTables()).thenReturn(List.of());
        when(flinkSqlService.getActiveJobs()).thenReturn(List.of());

        mockMvc = MockMvcBuilders
            .standaloneSetup(new DashboardController(kafkaAdminService, flinkSqlService, explorerConfig, kafkaConfig))
            .build();
    }

    private void brokerAnswers() throws Exception {
        when(kafkaAdminService.listTopics()).thenReturn(List.of("orders"));
        when(kafkaAdminService.getTopicsSize(anyList())).thenReturn(Map.of("orders", 12L));
        when(kafkaAdminService.getTopicsLastMessageTimestamps(anyList())).thenReturn(Map.of("orders", 1_700_000_000_000L));
        when(kafkaAdminService.ping()).thenReturn(true);
    }

    @Test
    void reportsTheBootstrapAddressItIsActuallyUsing() throws Exception {
        brokerAnswers();
        kafkaConfig.setBootstrapServers("kafka:29092");
        explorerConfig.setClusterName("Orders prod");

        mockMvc.perform(get("/api/dashboard"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.clusterName").value("Orders prod"))
            .andExpect(jsonPath("$.bootstrapServers").value("kafka:29092"))
            .andExpect(jsonPath("$.health").value(true));
    }

    /** A runtime repoint changes the address the pill reports — the label alone never could. */
    @Test
    void followsARuntimeRepoint() throws Exception {
        brokerAnswers();
        kafkaConfig.setBootstrapServers("staging:9092");

        mockMvc.perform(get("/api/dashboard"))
            .andExpect(jsonPath("$.bootstrapServers").value("staging:9092"));
    }

    /** An unreachable broker must still say which address was tried. */
    @Test
    void reportsTheAddressEvenWhenTheBrokerIsDown() throws Exception {
        when(kafkaAdminService.listTopics()).thenThrow(new RuntimeException("Connection refused"));
        kafkaConfig.setBootstrapServers("kafka:9092");

        mockMvc.perform(get("/api/dashboard"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.health").value(false))
            .andExpect(jsonPath("$.bootstrapServers").value("kafka:9092"))
            .andExpect(jsonPath("$.topics").isEmpty());
    }

    /** The shipped default names nothing it has not checked. */
    @Test
    void theDefaultClusterLabelAssertsNoKafkaVersion() {
        assertEquals("Kafka cluster", new ExplorerConfig().getClusterName());
    }
}
