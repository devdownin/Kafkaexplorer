// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors
package com.compagnonsdudev.kafkasqlexplorer.web;

import com.compagnonsdudev.kafkasqlexplorer.config.ExplorerConfig;
import com.compagnonsdudev.kafkasqlexplorer.domain.TopicConsumers;
import com.compagnonsdudev.kafkasqlexplorer.domain.TopicMessage;
import com.compagnonsdudev.kafkasqlexplorer.domain.TopicTimeLag;
import com.compagnonsdudev.kafkasqlexplorer.service.DdlGeneratorService;
import com.compagnonsdudev.kafkasqlexplorer.service.KafkaAdminService;
import com.compagnonsdudev.kafkasqlexplorer.service.MessageFormatterService;
import com.compagnonsdudev.kafkasqlexplorer.service.SchemaInferenceService;
import com.compagnonsdudev.kafkasqlexplorer.service.TopicSearchService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The topic endpoints, and the distinctions their status codes are chosen to keep.
 *
 * <p>All three tested here are cases where the wrong code would be a different <em>answer</em>,
 * not a rougher one:
 *
 * <ul>
 *   <li>reading one record by its coordinates <b>404s when nothing is there</b> — compacted away or
 *       out of range — because a caller must be able to tell that from a failure;</li>
 *   <li>asking who consumes a topic <b>never 404s on "nobody"</b>: an empty list is a legitimate
 *       answer, and the payload carries the scope of the read so it cannot be mistaken for a
 *       failed one;</li>
 *   <li>asking how far behind a group is <b>answers 200 even when nothing could be measured</b>,
 *       with {@code available} and the reason in the body — a 404 would say the topic or the group
 *       does not exist, which is a different thing from "this could not be read".</li>
 * </ul>
 *
 * <p>That last distinction is the one this codebase keeps re-deriving: a measurement that could
 * not be taken is not a zero, and it is not an absence either.
 */
class TopicControllerTest {

    private KafkaAdminService kafkaAdminService;
    private TopicSearchService topicSearchService;
    private MockMvc mockMvc;

    private static final String TOPIC = "demo.orders.1.received";

    @BeforeEach
    void setUp() {
        kafkaAdminService = Mockito.mock(KafkaAdminService.class);
        topicSearchService = Mockito.mock(TopicSearchService.class);
        mockMvc = MockMvcBuilders
            .standaloneSetup(new TopicController(
                kafkaAdminService,
                Mockito.mock(SchemaInferenceService.class),
                Mockito.mock(DdlGeneratorService.class),
                Mockito.mock(MessageFormatterService.class),
                topicSearchService,
                new ExplorerConfig()))
            .build();
    }

    private static TopicMessage message() {
        return new TopicMessage(0, 12, 1_760_000_000_000L, "ORD-1042",
            "{\"id\":\"ORD-1042\"}", Map.of(), 18, false);
    }

    @Test
    void aRecordIsReturnedByItsCoordinates() throws Exception {
        when(topicSearchService.readRecord(anyString(), anyInt(), anyLong())).thenReturn(message());

        mockMvc.perform(get("/api/topic/" + TOPIC + "/record").param("partition", "0").param("offset", "12"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.key").value("ORD-1042"));
    }

    /**
     * Compacted away, or past the end. 404 rather than an empty body: "there is no record there"
     * and "the read failed" send a caller to different places, and the search hit's "truncated"
     * badge points here.
     */
    @Test
    void anOffsetHoldingNoRecordIsNotFound() throws Exception {
        when(topicSearchService.readRecord(anyString(), anyInt(), anyLong())).thenReturn(null);

        mockMvc.perform(get("/api/topic/" + TOPIC + "/record").param("partition", "0").param("offset", "999999"))
            .andExpect(status().isNotFound());
    }

    /** Nobody consuming a topic is an answer about the cluster, not a missing resource. */
    @Test
    void aTopicNothingConsumesStillAnswers200() throws Exception {
        when(kafkaAdminService.getTopicConsumers(anyString(), anyInt()))
            .thenReturn(new TopicConsumers(TOPIC, List.of(), 0, 0, 40, false, true, List.of()));

        mockMvc.perform(get("/api/topic/" + TOPIC + "/consumers"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.available").value(true))
            // La portée voyage avec la réponse : une liste vide ne peut pas se lire comme un échec.
            .andExpect(jsonPath("$.groupsInCluster").value(40));
    }

    /**
     * And the read that failed: still 200, but {@code available} false with the reason. This is the
     * distinction the whole consumer-lag work was done for — "we asked and nobody reads it" and
     * "we could not ask" must not arrive as the same payload.
     */
    @Test
    void aConsumerReadThatFailedSaysSoInTheBodyRatherThanInTheStatus() throws Exception {
        when(kafkaAdminService.getTopicConsumers(anyString(), anyInt()))
            .thenReturn(TopicConsumers.unavailable(TOPIC, "The coordinator did not answer."));

        mockMvc.perform(get("/api/topic/" + TOPIC + "/consumers"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.available").value(false))
            .andExpect(jsonPath("$.warnings[0]").value("The coordinator did not answer."));
    }

    @Test
    void aDelayThatCouldNotBeMeasuredIs200WithItsReason() throws Exception {
        when(kafkaAdminService.getConsumerTimeLag(anyString(), anyString()))
            .thenReturn(TopicTimeLag.unavailable(TOPIC, "orders-api", "No committed offset for this group."));

        mockMvc.perform(get("/api/topic/" + TOPIC + "/time-lag").param("group", "orders-api"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.available").value(false))
            .andExpect(jsonPath("$.error").value("No committed offset for this group."));
    }

    /**
     * {@code /topic/:name} is a client-side route, and its parameter is a Kafka topic name — which
     * carries dots. Registered alone, this controller must not answer it; {@code SpaRoutingTest}
     * asserts the other half, that the SPA does, which it did not until that test was written.
     */
    @Test
    void thereIsNoServerSideTopicPage() throws Exception {
        mockMvc.perform(get("/topic/" + TOPIC)).andExpect(status().isNotFound());
    }
}
