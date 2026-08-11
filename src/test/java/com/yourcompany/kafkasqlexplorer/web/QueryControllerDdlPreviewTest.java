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

import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The DDL preview's failure path, which had no test and could not answer at all in the one case
 * where a caller most needs a reason.
 *
 * <p>The handler used to return {@code Map.of("error", e.getMessage())}. {@code Map.of} rejects a
 * null value and {@code getMessage()} is null for a {@link NullPointerException}, so an inference
 * failing that way turned this handler's own error path into a 500 — which the UI could only
 * report as a generic "Failed to generate DDL preview", losing the reason entirely.
 *
 * <p>Standalone MockMvc: nothing here needs a Spring context.
 */
class QueryControllerDdlPreviewTest {

    private SchemaInferenceService schemaInferenceService;
    private DdlGeneratorService ddlGeneratorService;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        schemaInferenceService = Mockito.mock(SchemaInferenceService.class);
        ddlGeneratorService = Mockito.mock(DdlGeneratorService.class);
        QueryController controller = new QueryController(
            Mockito.mock(FlinkSqlService.class),
            Mockito.mock(SqlExplorationService.class),
            Mockito.mock(FlinkJobService.class),
            Mockito.mock(KafkaAdminService.class),
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
}
