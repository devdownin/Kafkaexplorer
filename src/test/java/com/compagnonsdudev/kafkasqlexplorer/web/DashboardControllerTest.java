// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors
package com.compagnonsdudev.kafkasqlexplorer.web;

import com.compagnonsdudev.kafkasqlexplorer.config.ExplorerConfig;
import com.compagnonsdudev.kafkasqlexplorer.config.KafkaConfig;
import com.compagnonsdudev.kafkasqlexplorer.domain.TopicActivity;
import com.compagnonsdudev.kafkasqlexplorer.domain.TopicActivityResponse;
import com.compagnonsdudev.kafkasqlexplorer.service.FlinkSqlService;
import com.compagnonsdudev.kafkasqlexplorer.service.KafkaAdminService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.verify;
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
 * <p>The activity endpoint is here too, being the dashboard's other read: what is pinned of it is
 * the contract around the measurement rather than the measurement itself (which
 * {@code KafkaAdminServiceActivityTest} owns) — the configured window when the request names none,
 * the cap naming what it left out, and a failed read answered with its reason instead of a 500.
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

    // ── GET /api/dashboard/activity ──────────────────────────────────────────

    private static TopicActivityResponse series(String topic) {
        return new TopicActivityResponse(
            Map.of(topic, new TopicActivity(topic, 1_000L, 5_000L, 1_000L, List.of(1L, 2L, 0L, 4L),
                7L, null, 1, 1, true, null)),
            1_000L, 5_000L, 1_000L, 4, true, List.of());
    }

    /** The window and the resolution come from the configuration when the request names neither. */
    @Test
    void appliesTheConfiguredWindowWhenTheRequestNamesNone() throws Exception {
        explorerConfig.setActivityDefaultWindowMs(3_600_000L);
        explorerConfig.setActivityBuckets(12);
        when(kafkaAdminService.getTopicActivity(anyList(), anyLong(), anyInt(), anyInt()))
            .thenReturn(series("orders"));

        mockMvc.perform(get("/api/dashboard/activity").param("topics", "orders,payments"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.topics.orders.total").value(7))
            .andExpect(jsonPath("$.available").value(true));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<String>> topics = ArgumentCaptor.forClass(List.class);
        verify(kafkaAdminService).getTopicActivity(topics.capture(), Mockito.eq(3_600_000L),
            Mockito.eq(12), Mockito.eq(explorerConfig.getActivityMaxLookups()));
        assertEquals(List.of("orders", "payments"), topics.getValue());
    }

    /**
     * Past the cap the answer says what it did not measure. A curve missing from a row reads as a
     * topic that produced nothing, which is the one thing a bounded read must not leave behind.
     */
    @Test
    void namesTheTopicsTheCapLeftOut() throws Exception {
        explorerConfig.setActivityMaxTopics(2);
        when(kafkaAdminService.getTopicActivity(anyList(), anyLong(), anyInt(), anyInt()))
            .thenReturn(series("t0"));

        String many = String.join(",", IntStream.range(0, 5).mapToObj(i -> "t" + i).toList());
        mockMvc.perform(get("/api/dashboard/activity").param("topics", many))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.warnings[0]").value(org.hamcrest.Matchers.containsString("3 topic(s) beyond the first 2")));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<String>> topics = ArgumentCaptor.forClass(List.class);
        verify(kafkaAdminService).getTopicActivity(topics.capture(), anyLong(), anyInt(), anyInt());
        assertEquals(List.of("t0", "t1"), topics.getValue());
    }

    /** An unreachable broker is answered with a reason, not with a 500 and not with zeros. */
    @Test
    void reportsAFailedReadAsUnavailable() throws Exception {
        when(kafkaAdminService.getTopicActivity(anyList(), anyLong(), anyInt(), anyInt()))
            .thenReturn(TopicActivityResponse.unavailable(0L, 0L, 1_000L, 4, "Connection refused"));

        mockMvc.perform(get("/api/dashboard/activity").param("topics", "orders"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.available").value(false))
            .andExpect(jsonPath("$.topics").isEmpty())
            .andExpect(jsonPath("$.warnings[0]").value("Connection refused"));
    }

    /** The topics are the request: there is no "measure the whole cluster" shape of this call. */
    @Test
    void refusesARequestThatNamesNoTopic() throws Exception {
        mockMvc.perform(get("/api/dashboard/activity"))
            .andExpect(status().isBadRequest());
    }
}
