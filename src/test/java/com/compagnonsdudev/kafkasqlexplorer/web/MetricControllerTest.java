// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors
package com.compagnonsdudev.kafkasqlexplorer.web;

import com.compagnonsdudev.kafkasqlexplorer.domain.MetricSuggestionRequest;
import com.compagnonsdudev.kafkasqlexplorer.domain.MetricSuggestions;
import com.compagnonsdudev.kafkasqlexplorer.service.FlinkSqlService;
import com.compagnonsdudev.kafkasqlexplorer.service.KafkaAdminService;
import com.compagnonsdudev.kafkasqlexplorer.service.MessageFieldExtractorService;
import com.compagnonsdudev.kafkasqlexplorer.service.MessageFormatterService;
import com.compagnonsdudev.kafkasqlexplorer.service.MetricService;
import com.compagnonsdudev.kafkasqlexplorer.service.MetricSuggestionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@code POST /api/metrics/suggestions}, whose body is optional and whose record has grown a
 * component since it was introduced — the two properties that make binding worth pinning.
 *
 * <p>Jackson binds a record through its canonical constructor, so an absent property arrives as
 * {@code null}: a body as small as {@code {"fieldMappingId":"x"}} has to work, and so does no body
 * at all. That is the exact class of failure {@code StreamFlowControllerTest} was written for,
 * caught there on a minimal JSON body; this endpoint had no test to catch it with.
 *
 * <p>Standalone MockMvc rather than {@code @WebMvcTest}: nothing here needs a Spring context, and
 * the six collaborators are mocks, of which only one is ever consulted.
 */
class MetricControllerTest {

    private MetricSuggestionService suggestionService;
    private MockMvc mockMvc;

    private static final MetricSuggestions EMPTY =
        new MetricSuggestions(List.of(), false, null, null, null, 0, 0, List.of("nothing measured yet"));

    @BeforeEach
    void setUp() {
        suggestionService = Mockito.mock(MetricSuggestionService.class);
        mockMvc = MockMvcBuilders
            .standaloneSetup(new MetricController(
                Mockito.mock(MetricService.class),
                Mockito.mock(FlinkSqlService.class),
                Mockito.mock(KafkaAdminService.class),
                Mockito.mock(MessageFormatterService.class),
                Mockito.mock(MessageFieldExtractorService.class),
                suggestionService))
            .build();
    }

    private ArgumentCaptor<MetricSuggestionRequest> captureRequest() {
        ArgumentCaptor<MetricSuggestionRequest> captor = ArgumentCaptor.forClass(MetricSuggestionRequest.class);
        verify(suggestionService).suggest(captor.capture());
        return captor;
    }

    /**
     * The panel calls this before the browser has anything to contribute, and a caller poking the
     * API by hand has no reason to send a body at all. {@code required = false} is what makes that
     * a 200 instead of a 400, and the service is handed the null it is written to accept.
     */
    @Test
    void aCallWithNoBodyAtAllIsAnsweredWithTheAuditDerivedProposals() throws Exception {
        when(suggestionService.suggest(any())).thenReturn(EMPTY);

        mockMvc.perform(post("/api/metrics/suggestions"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.auditAvailable").value(false))
            .andExpect(jsonPath("$.notes[0]").value("nothing measured yet"));

        assertThat(captureRequest().getValue()).isNull();
    }

    /** What the panel actually sends on a browser that has recorded no trace. */
    @Test
    void anEmptyChainListBinds() throws Exception {
        when(suggestionService.suggest(any())).thenReturn(EMPTY);

        mockMvc.perform(post("/api/metrics/suggestions")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"flowChains\":[]}"))
            .andExpect(status().isOk());

        MetricSuggestionRequest bound = captureRequest().getValue();
        assertThat(bound.chains()).isEmpty();
        assertThat(bound.fieldMappingId()).isNull();
    }

    /**
     * The half of the body that arrived later. A record's canonical constructor takes every
     * component, so the property added second is precisely the one whose absence — or whose
     * presence alone — breaks binding if the record ever grows a primitive.
     */
    @Test
    void aFieldMappingIdAloneBindsWithNoChains() throws Exception {
        when(suggestionService.suggest(any())).thenReturn(EMPTY);

        mockMvc.perform(post("/api/metrics/suggestions")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"fieldMappingId\":\"mapping-7\"}"))
            .andExpect(status().isOk());

        MetricSuggestionRequest bound = captureRequest().getValue();
        assertThat(bound.fieldMappingId()).isEqualTo("mapping-7");
        // Absent, not empty — and `chains()` is what turns that null into a usable list.
        assertThat(bound.flowChains()).isNull();
        assertThat(bound.chains()).isEmpty();
    }

    /** A whole trace, since that is the shape the browser sends once a key has been traced. */
    @Test
    void aTracedChainBindsWithItsHops() throws Exception {
        when(suggestionService.suggest(any())).thenReturn(EMPTY);

        mockMvc.perform(post("/api/metrics/suggestions")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"flowChains":[{"messageKey":"ORD-42","tracedAt":1750000000000,
                     "hops":[{"topic":"demo.orders.1.received","firstTimestamp":1750000000000},
                             {"topic":"demo.orders.2.validated","latencyFromPreviousMs":1200,"occurrences":3}]}],
                     "fieldMappingId":"mapping-7"}"""))
            .andExpect(status().isOk());

        MetricSuggestionRequest bound = captureRequest().getValue();
        assertThat(bound.chains()).hasSize(1);
        var chain = bound.chains().get(0);
        assertThat(chain.messageKey()).isEqualTo("ORD-42");
        // Every component boxed: `searchPath` was not sent, and the request still binds.
        assertThat(chain.searchPath()).isNull();
        assertThat(chain.hops()).hasSize(2);
        assertThat(chain.hops().get(0).latencyFromPreviousMs()).isNull();
        assertThat(chain.hops().get(1).latencyFromPreviousMs()).isEqualTo(1200L);
        assertThat(chain.hops().get(1).occurrences()).isEqualTo(3);
    }
}
