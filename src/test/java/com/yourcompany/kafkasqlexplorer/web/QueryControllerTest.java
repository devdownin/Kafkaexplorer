// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors
package com.yourcompany.kafkasqlexplorer.web;

import com.yourcompany.kafkasqlexplorer.domain.MessageFormat;
import com.yourcompany.kafkasqlexplorer.service.DdlGeneratorService;
import com.yourcompany.kafkasqlexplorer.service.FlinkJobService;
import com.yourcompany.kafkasqlexplorer.service.FlinkSqlService;
import com.yourcompany.kafkasqlexplorer.service.KafkaAdminService;
import com.yourcompany.kafkasqlexplorer.service.SchemaInferenceService;
import com.yourcompany.kafkasqlexplorer.service.SqlExplorationService;
import com.yourcompany.kafkasqlexplorer.service.SqlQueryValidator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The two endpoints of this controller that answer when something has gone wrong — both of which
 * shipped without a test, and both of which threw the reason away.
 *
 * <p>{@code /ddl-preview} used to return {@code Map.of("error", e.getMessage())}. {@code Map.of}
 * rejects a null value and {@code getMessage()} is null for a {@link NullPointerException}, so an
 * inference failing that way turned the handler's own error path into a 500 — which the UI could
 * only report as a generic "Failed to generate DDL preview".
 *
 * <p>{@code /init} caught both of its probes and ignored them ({@code // Ignore and show empty
 * list}), so an unreachable broker and a Flink runtime still starting up produced exactly the same
 * screen with nothing to tell them apart.
 *
 * <p>Standalone MockMvc: nothing here needs a Spring context.
 */
class QueryControllerTest {

    private SchemaInferenceService schemaInferenceService;
    private DdlGeneratorService ddlGeneratorService;
    private KafkaAdminService kafkaAdminService;
    private FlinkSqlService flinkSqlService;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        schemaInferenceService = Mockito.mock(SchemaInferenceService.class);
        ddlGeneratorService = Mockito.mock(DdlGeneratorService.class);
        kafkaAdminService = Mockito.mock(KafkaAdminService.class);
        flinkSqlService = Mockito.mock(FlinkSqlService.class);
        QueryController controller = new QueryController(
            flinkSqlService,
            Mockito.mock(SqlExplorationService.class),
            Mockito.mock(FlinkJobService.class),
            kafkaAdminService,
            Mockito.mock(SqlQueryValidator.class),
            schemaInferenceService,
            ddlGeneratorService);
        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    @Test
    void returnsTheGeneratedDdl() throws Exception {
        when(schemaInferenceService.detectFormat(anyString())).thenReturn(MessageFormat.JSON);
        when(schemaInferenceService.inferSchema(anyString(), any())).thenReturn(Map.of("id", "STRING"));
        when(ddlGeneratorService.generateDdl(anyString(), any(), any())).thenReturn("CREATE TABLE demo_orders (id STRING)");

        mockMvc.perform(get("/api/query/ddl-preview").param("topic", "demo.orders"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.ddl").value("CREATE TABLE demo_orders (id STRING)"));
    }

    @Test
    void reportsAFailureWithItsMessage() throws Exception {
        when(schemaInferenceService.detectFormat(anyString()))
            .thenThrow(new IllegalStateException("Topic demo.orders holds no message to sample"));

        mockMvc.perform(get("/api/query/ddl-preview").param("topic", "demo.orders"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.error").value("Topic demo.orders holds no message to sample"));
    }

    /**
     * The regression this handler shipped: a throwable with no message must still produce a body,
     * not a 500. {@code SqlErrorClassifier.explain} is documented never to return null or blank,
     * and falls back to the exception's class name.
     */
    @Test
    void answersWithABodyEvenWhenTheFailureCarriesNoMessage() throws Exception {
        when(schemaInferenceService.detectFormat(anyString())).thenThrow(new NullPointerException());

        mockMvc.perform(get("/api/query/ddl-preview").param("topic", "demo.orders"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.error").value("NullPointerException"));
    }

    // ── GET /init ────────────────────────────────────────────────────────────────
    //
    // Both halves of this endpoint used to end in an empty catch, so an unreachable broker and a
    // Flink runtime still starting up rendered as the same "Engine offline · 0 tables · 0 topics"
    // with nothing to act on.

    @Test
    void reportsWhyTheBrokerIsUnreachable() throws Exception {
        when(kafkaAdminService.pingDetail())
            .thenReturn(new KafkaAdminService.PingResult(false, "Connection to node -1 refused"));
        when(flinkSqlService.listTables()).thenReturn(List.of("orders"));

        mockMvc.perform(get("/api/query/init"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.health").value(false))
            .andExpect(jsonPath("$.kafkaError").value("Connection to node -1 refused"))
            // Flink answering is independent: one side failing must not empty the other.
            .andExpect(jsonPath("$.tables[0]").value("orders"))
            .andExpect(jsonPath("$.flinkError").doesNotExist());
    }

    @Test
    void reportsWhyTheTableListIsEmpty() throws Exception {
        when(kafkaAdminService.pingDetail()).thenReturn(new KafkaAdminService.PingResult(true, null));
        when(kafkaAdminService.listTopics()).thenReturn(List.of("demo.orders"));
        when(kafkaAdminService.getTopicsSize(any())).thenReturn(Map.of("demo.orders", 12L));
        when(flinkSqlService.listTables()).thenThrow(new IllegalStateException("Flink is still starting"));

        mockMvc.perform(get("/api/query/init"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.health").value(true))
            .andExpect(jsonPath("$.topics[0]").value("demo.orders"))
            .andExpect(jsonPath("$.kafkaError").doesNotExist())
            .andExpect(jsonPath("$.flinkError").value("Flink is still starting"));
    }

    /**
     * A broker that answers the probe but refuses the metadata call is not "offline": the health
     * flag stays true and the reason is reported beside it, so the UI does not tell an operator to
     * go and check a connection that is working.
     */
    @Test
    void keepsTheClusterHealthyWhenOnlyTheMetadataCallFails() throws Exception {
        when(kafkaAdminService.pingDetail()).thenReturn(new KafkaAdminService.PingResult(true, null));
        when(kafkaAdminService.listTopics()).thenThrow(new IllegalStateException("Not authorized to access topics"));
        when(flinkSqlService.listTables()).thenReturn(List.of());

        mockMvc.perform(get("/api/query/init"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.health").value(true))
            .andExpect(jsonPath("$.kafkaError").value("Not authorized to access topics"));
    }

    @Test
    void reportsNoFailureWhenBothAnswer() throws Exception {
        when(kafkaAdminService.pingDetail()).thenReturn(new KafkaAdminService.PingResult(true, null));
        when(kafkaAdminService.listTopics()).thenReturn(List.of("demo.orders", "demo.empty"));
        when(kafkaAdminService.getTopicsSize(any())).thenReturn(Map.of("demo.orders", 12L, "demo.empty", 0L));
        when(flinkSqlService.listTables()).thenReturn(List.of("orders"));

        mockMvc.perform(get("/api/query/init"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.health").value(true))
            // A topic with no message is filtered out, as it always was.
            .andExpect(jsonPath("$.topics.length()").value(1))
            .andExpect(jsonPath("$.kafkaError").doesNotExist())
            .andExpect(jsonPath("$.flinkError").doesNotExist());
    }
}
