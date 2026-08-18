// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors
package com.compagnonsdudev.kafkasqlexplorer.web;

import com.compagnonsdudev.kafkasqlexplorer.domain.DataModelResponse;
import com.compagnonsdudev.kafkasqlexplorer.service.DataModelService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.mockito.ArgumentMatchers.anyList;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Standalone MockMvc, same discipline as {@link StreamFlowControllerTest}: nothing here needs
 * a Spring context, and registering only this controller is what makes the "no GET mapping on
 * the client-side route" assertion meaningful.
 */
class DataModelControllerTest {

    private DataModelService dataModelService;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        dataModelService = Mockito.mock(DataModelService.class);
        mockMvc = MockMvcBuilders
                .standaloneSetup(new DataModelController(dataModelService))
                .build();
    }

    @Test
    void aSelectionReturnsTheModelAsJson() throws Exception {
        Mockito.when(dataModelService.buildModel(anyList())).thenReturn(
                new DataModelResponse(List.of(), List.of(), List.of(), 2, 0, false));

        mockMvc.perform(post("/api/data-model")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"topics\":[\"demo.orders\",\"demo.payments\"]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.topicsRequested").value(2));
    }

    /**
     * The reason travels in the body: {@code server.error.include-message} defaults to
     * {@code never}, so anything else reaches the UI as a bare "Bad Request".
     */
    @Test
    void anEmptySelectionAnswers400WithItsReason() throws Exception {
        mockMvc.perform(post("/api/data-model")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"topics\":[]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(
                        "Select at least one topic to build the model from."));
    }

    /** Jackson binds records through the canonical constructor: no body must not 500. */
    @Test
    void aMissingBodyAnswers400() throws Exception {
        mockMvc.perform(post("/api/data-model").contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").exists());
    }

    @Test
    void aSelectionOfBlankTopicsAnswers400WithTheServicesReason() throws Exception {
        Mockito.when(dataModelService.buildModel(anyList()))
                .thenThrow(new IllegalArgumentException("At least one topic is required."));

        mockMvc.perform(post("/api/data-model")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"topics\":[\"  \"]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("At least one topic is required."));
    }

    /**
     * {@code /data-model} belongs to the SPA router — a GET mapping here would shadow
     * {@link SpaController} and 500 a page refresh. Same rule as {@code /stream-flow}.
     */
    @Test
    void theControllerClaimsNoClientSideRoute() throws Exception {
        mockMvc.perform(get("/data-model")).andExpect(status().isNotFound());
        mockMvc.perform(get("/api/data-model")).andExpect(status().isMethodNotAllowed());
    }
}
