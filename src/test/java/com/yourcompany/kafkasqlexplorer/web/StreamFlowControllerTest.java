// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors
package com.yourcompany.kafkasqlexplorer.web;

import com.yourcompany.kafkasqlexplorer.config.ExplorerConfig;
import com.yourcompany.kafkasqlexplorer.domain.StreamFlowRequest;
import com.yourcompany.kafkasqlexplorer.domain.StreamFlowResponse;
import com.yourcompany.kafkasqlexplorer.domain.StreamFlowStats;
import com.yourcompany.kafkasqlexplorer.service.StreamFlowService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The controller's contract, which had no test and shipped a bug because of it: a
 * {@code GET /stream-flow} mapping shadowed {@link SpaController} and answered a page refresh with
 * a circular-view-path 500.
 *
 * <p>Standalone MockMvc rather than {@code @WebMvcTest}: nothing here needs a Spring context, and
 * registering only this controller is exactly what makes the "no GET mapping" assertion meaningful.
 */
class StreamFlowControllerTest {

    private StreamFlowService streamFlowService;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        streamFlowService = Mockito.mock(StreamFlowService.class);
        mockMvc = MockMvcBuilders
            .standaloneSetup(new StreamFlowController(streamFlowService, new ExplorerConfig()))
            .build();
    }

    private static StreamFlowResponse emptyFlow() {
        return new StreamFlowResponse(List.of(), List.of(), List.of(),
            new StreamFlowStats(1, 1, 0, 0, 10, 0, 5L, false, "COMPLETE", 100, null), List.of());
    }

    @Test
    void aTraceReturnsTheFlowAsJson() throws Exception {
        when(streamFlowService.getStreamFlow(any())).thenReturn(emptyFlow());

        mockMvc.perform(post("/api/stream-flow")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"messageKey\":\"K-1\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.stats.stopReason").value("COMPLETE"));
    }

    /**
     * The reason has to travel in the body: {@code server.error.include-message} defaults to
     * {@code never}, so a ResponseStatusException would reach the UI as a bare "Bad Request".
     */
    @Test
    void anUnusableCriterionAnswers400WithItsReason() throws Exception {
        when(streamFlowService.getStreamFlow(any()))
            .thenThrow(new IllegalArgumentException("Invalid regular expression: Unclosed character class"));

        mockMvc.perform(post("/api/stream-flow")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"messageKey\":\"user-[\",\"useRegex\":true}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.message").value("Invalid regular expression: Unclosed character class"));
    }

    @Test
    void theStreamingEndpointRejectsABadCriterionBeforeOpeningTheStream() throws Exception {
        doThrow(new IllegalArgumentException("A message key is required."))
            .when(streamFlowService).validateCriterion(any(StreamFlowRequest.class));

        mockMvc.perform(post("/api/stream-flow/stream")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"messageKey\":\"\"}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.message").value("A message key is required."));

        Mockito.verify(streamFlowService, Mockito.never()).trace(any(), any(), any());
    }

    @Test
    void theStreamingEndpointOpensAnEventStream() throws Exception {
        when(streamFlowService.trace(any(), any(), any())).thenReturn(emptyFlow());

        mockMvc.perform(post("/api/stream-flow/stream")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"messageKey\":\"K-1\"}"))
            .andExpect(status().isOk())
            .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_EVENT_STREAM));
    }

    /**
     * {@code /stream-flow} belongs to the SPA router. With only this controller registered, a GET
     * that finds no handler is the proof it claims nothing there — the mapping that existed used to
     * return the view name "stream-flow" to an app with no template engine.
     */
    @Test
    void theControllerClaimsNoClientSideRoute() throws Exception {
        mockMvc.perform(get("/stream-flow")).andExpect(status().isNotFound());
        mockMvc.perform(get("/api/stream-flow")).andExpect(status().isMethodNotAllowed());
    }
}
