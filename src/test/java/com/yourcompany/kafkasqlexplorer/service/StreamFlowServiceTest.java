// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors
package com.yourcompany.kafkasqlexplorer.service;

import com.yourcompany.kafkasqlexplorer.config.ExplorerConfig;
import com.yourcompany.kafkasqlexplorer.domain.StreamFlowHit;
import com.yourcompany.kafkasqlexplorer.domain.StreamFlowRequest;
import com.yourcompany.kafkasqlexplorer.domain.StreamFlowResponse;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.internals.RecordHeaders;
import org.apache.kafka.common.record.TimestampType;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class StreamFlowServiceTest {

    private KafkaAdminService kafkaAdminService;

    private StreamFlowService newService() {
        kafkaAdminService = Mockito.mock(KafkaAdminService.class);
        return new StreamFlowService(kafkaAdminService, new ExplorerConfig());
    }

    private static ConsumerRecord<String, String> record(String topic, long timestamp, String key, String value) {
        return record(topic, 0, 0L, timestamp, key, value);
    }

    private static ConsumerRecord<String, String> record(String topic, int partition, long offset,
                                                          long timestamp, String key, String value) {
        return new ConsumerRecord<>(topic, partition, offset, timestamp, TimestampType.CREATE_TIME,
            0, 0, key, value, new RecordHeaders(), Optional.empty());
    }

    @Test
    public void testGetStreamFlow() throws Exception {
        StreamFlowService streamFlowService = newService();
        when(kafkaAdminService.listTopics()).thenReturn(Arrays.asList("topic1", "topic2", "topic3"));

        when(kafkaAdminService.getRecentRecords(eq("topic1"), anyInt()))
            .thenReturn(List.of(record("topic1", 100L, "key1", "value1")));
        when(kafkaAdminService.getRecentRecords(eq("topic2"), anyInt()))
            .thenReturn(List.of(record("topic2", 200L, "key2", "contains-key1")));
        when(kafkaAdminService.getRecentRecords(eq("topic3"), anyInt()))
            .thenReturn(List.of(record("topic3", 300L, "key3", "value3")));

        StreamFlowRequest request = new StreamFlowRequest("key1", 10, null, null, false, null);
        StreamFlowResponse response = streamFlowService.getStreamFlow(request);

        assertEquals(2, response.nodes().size());
        assertEquals(1, response.edges().size());

        Map<String, String> edge = response.edges().get(0);
        assertEquals("topic1", edge.get("from"));
        assertEquals("topic2", edge.get("to"));
        assertEquals("+100 ms", edge.get("label"), "the edge label carries the hop latency");
    }

    @Test
    public void testGetStreamFlowWithJsonPath() throws Exception {
        StreamFlowService streamFlowService = newService();
        when(kafkaAdminService.listTopics()).thenReturn(Collections.singletonList("orders"));

        String jsonValue = "{\"orderId\": \"ORD-123\", \"status\": \"CREATED\"}";
        when(kafkaAdminService.getRecentRecords(eq("orders"), anyInt()))
            .thenReturn(List.of(record("orders", 100L, null, jsonValue)));

        // Matches correct value
        StreamFlowResponse response1 = streamFlowService.getStreamFlow(
            new StreamFlowRequest("ORD-123", 10, "$.orderId", null, false, null));
        assertEquals(1, response1.nodes().size());

        // Does not match incorrect value at path
        StreamFlowResponse response2 = streamFlowService.getStreamFlow(
            new StreamFlowRequest("ORD-999", 10, "$.orderId", null, false, null));
        assertEquals(0, response2.nodes().size());

        // Does not match incorrect path
        StreamFlowResponse response3 = streamFlowService.getStreamFlow(
            new StreamFlowRequest("ORD-123", 10, "$.wrongPath", null, false, null));
        assertEquals(0, response3.nodes().size());
    }

    @Test
    public void testGetStreamFlowWithRegex() throws Exception {
        StreamFlowService streamFlowService = newService();
        when(kafkaAdminService.listTopics()).thenReturn(Collections.singletonList("logs"));

        when(kafkaAdminService.getRecentRecords(eq("logs"), anyInt()))
            .thenReturn(List.of(record("logs", 100L, null, "ERROR: user-456 failed to login")));

        StreamFlowResponse response1 = streamFlowService.getStreamFlow(
            new StreamFlowRequest("user-.* failed", 10, null, null, true, null));
        assertEquals(1, response1.nodes().size());

        StreamFlowResponse response2 = streamFlowService.getStreamFlow(
            new StreamFlowRequest("user-\\d{4} failed", 10, null, null, true, null));
        assertEquals(0, response2.nodes().size());
    }

    /** An unusable criterion is reported, not silently degraded into a different search. */
    @Test
    public void testInvalidRegexIsRejected() {
        StreamFlowService streamFlowService = newService();

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
            () -> streamFlowService.getStreamFlow(new StreamFlowRequest("user-[", 10, null, null, true, null)));
        assertTrue(error.getMessage().toLowerCase().contains("regular expression"), error.getMessage());
    }

    @Test
    public void testInvalidSearchPathIsRejected() {
        StreamFlowService streamFlowService = newService();

        assertThrows(IllegalArgumentException.class, () -> streamFlowService.getStreamFlow(
            new StreamFlowRequest("k", 10, "orderId", null, false, null)), "neither JSONPath nor XPath");
        assertThrows(IllegalArgumentException.class, () -> streamFlowService.getStreamFlow(
            new StreamFlowRequest("k", 10, "$.[", null, false, null)), "malformed JSONPath");
        assertThrows(IllegalArgumentException.class, () -> streamFlowService.getStreamFlow(
            new StreamFlowRequest("k", 10, "/root[", null, false, null)), "malformed XPath");
    }

    @Test
    public void testBlankMessageKeyIsRejected() {
        StreamFlowService streamFlowService = newService();

        assertThrows(IllegalArgumentException.class, () -> streamFlowService.getStreamFlow(
            new StreamFlowRequest("   ", 10, null, null, false, null)));
        assertThrows(IllegalArgumentException.class, () -> streamFlowService.getStreamFlow(
            new StreamFlowRequest(null, 10, null, null, false, null)));
    }

    /**
     * A key seen twice in the same topic must not produce a back-edge: the chain is one node per
     * topic, ordered by first sighting.
     */
    @Test
    public void testRepeatedOccurrencesDoNotCreateBackEdges() throws Exception {
        StreamFlowService streamFlowService = newService();
        when(kafkaAdminService.listTopics()).thenReturn(List.of("orders", "billing"));

        when(kafkaAdminService.getRecentRecords(eq("orders"), anyInt())).thenReturn(List.of(
            record("orders", 0, 10L, 100L, "K-1", "created"),
            record("orders", 0, 11L, 300L, "K-1", "retried")));
        when(kafkaAdminService.getRecentRecords(eq("billing"), anyInt())).thenReturn(List.of(
            record("billing", 1, 42L, 200L, "K-1", "charged")));

        StreamFlowResponse response = streamFlowService.getStreamFlow(
            new StreamFlowRequest("K-1", 10, null, null, false, null));

        assertEquals(2, response.nodes().size());
        assertEquals(1, response.edges().size());
        assertEquals("orders", response.edges().get(0).get("from"));
        assertEquals("billing", response.edges().get(0).get("to"));

        List<StreamFlowHit> hits = response.hits();
        assertEquals(2, hits.size());
        StreamFlowHit orders = hits.get(0);
        assertEquals("orders", orders.topic());
        assertEquals(2, orders.occurrences());
        assertEquals(100L, orders.firstTimestamp());
        assertEquals(300L, orders.lastTimestamp());
        assertEquals(10L, orders.firstOffset());
        assertNull(orders.latencyFromPreviousMs(), "the first hop has no latency");

        StreamFlowHit billing = hits.get(1);
        assertEquals(1, billing.firstPartition());
        assertEquals(100L, billing.latencyFromPreviousMs());
        assertEquals("charged", billing.preview());
    }

    /** The node map keeps its keys in a fixed order — the renderer falls back to positional access. */
    @Test
    public void testNodeCarriesHitCountAndOrderedKeys() throws Exception {
        StreamFlowService streamFlowService = newService();
        when(kafkaAdminService.listTopics()).thenReturn(List.of("orders"));
        when(kafkaAdminService.getRecentRecords(eq("orders"), anyInt())).thenReturn(List.of(
            record("orders", 0, 1L, 100L, "K-1", "a"),
            record("orders", 0, 2L, 150L, "K-1", "b")));

        StreamFlowResponse response = streamFlowService.getStreamFlow(
            new StreamFlowRequest("K-1", 10, null, null, false, null));

        Map<String, String> node = response.nodes().get(0);
        assertEquals(List.of("id", "label", "type", "timestamp", "hits"), List.copyOf(node.keySet()));
        assertEquals("orders", node.get("id"));
        assertEquals("2", node.get("hits"));
    }

    /**
     * A search path is a scope, not a hint: on a payload it cannot be applied to, the record does
     * not match, and the response says why instead of leaving an unexplained empty graph.
     */
    @Test
    public void testSearchPathDoesNotFallBackToRawSearch() throws Exception {
        StreamFlowService streamFlowService = newService();
        when(kafkaAdminService.listTopics()).thenReturn(List.of("plain"));
        when(kafkaAdminService.getRecentRecords(eq("plain"), anyInt()))
            .thenReturn(List.of(record("plain", 100L, "K-1", "raw text mentioning ORD-123")));

        StreamFlowResponse response = streamFlowService.getStreamFlow(
            new StreamFlowRequest("ORD-123", 10, "$.orderId", null, false, null));

        assertEquals(0, response.nodes().size());
        assertTrue(response.warnings().stream().anyMatch(w -> w.contains("search path")),
            "expected a warning about the inapplicable path, got " + response.warnings());
    }

    /** Malformed JSON is skipped and reported, never counted as a match nor as a clean miss. */
    @Test
    public void testMalformedJsonIsReported() throws Exception {
        StreamFlowService streamFlowService = newService();
        when(kafkaAdminService.listTopics()).thenReturn(List.of("orders"));
        when(kafkaAdminService.getRecentRecords(eq("orders"), anyInt()))
            .thenReturn(List.of(record("orders", 100L, null, "{\"orderId\": \"ORD-1")));

        StreamFlowResponse response = streamFlowService.getStreamFlow(
            new StreamFlowRequest("ORD-1", 10, "$.orderId", null, false, null));

        assertEquals(0, response.nodes().size());
        assertTrue(response.warnings().stream().anyMatch(w -> w.contains("Malformed JSON")),
            "expected a warning about the malformed payload, got " + response.warnings());
    }

    /** XPath is evaluated over the whole node set: a match on the second element still counts. */
    @Test
    public void testXPathMatchesBeyondTheFirstNode() throws Exception {
        StreamFlowService streamFlowService = newService();
        when(kafkaAdminService.listTopics()).thenReturn(List.of("orders"));
        String xml = "<order><item><id>A-1</id></item><item><id>A-2</id></item></order>";
        when(kafkaAdminService.getRecentRecords(eq("orders"), anyInt()))
            .thenReturn(List.of(record("orders", 100L, null, xml)));

        StreamFlowResponse response = streamFlowService.getStreamFlow(
            new StreamFlowRequest("A-2", 10, "/order/item/id", null, false, null));

        assertEquals(1, response.nodes().size());
    }

    /** An indefinite JSONPath yields a list; each element is tested on its own. */
    @Test
    public void testIndefiniteJsonPathTestsEachElement() throws Exception {
        StreamFlowService streamFlowService = newService();
        when(kafkaAdminService.listTopics()).thenReturn(List.of("orders"));
        when(kafkaAdminService.getRecentRecords(eq("orders"), anyInt())).thenReturn(List.of(
            record("orders", 100L, null, "{\"items\":[{\"id\":\"A-1\"},{\"id\":\"A-2\"}]}")));

        StreamFlowResponse response = streamFlowService.getStreamFlow(
            new StreamFlowRequest("A-2", 10, "$.items[*].id", null, false, null));

        assertEquals(1, response.nodes().size());
    }

    /** A body omitting maxMessagesPerTopic deserializes to 0 — which used to scan nothing. */
    @Test
    public void testZeroMaxMessagesFallsBackToADefault() throws Exception {
        StreamFlowService streamFlowService = newService();
        when(kafkaAdminService.listTopics()).thenReturn(List.of("orders"));
        when(kafkaAdminService.getRecentRecords(eq("orders"), anyInt())).thenReturn(List.of());

        StreamFlowResponse response = streamFlowService.getStreamFlow(
            new StreamFlowRequest("K-1", 0, null, null, false, null));

        verify(kafkaAdminService).getRecentRecords("orders", 100);
        assertEquals(100, response.stats().maxMessagesPerTopic());
    }

    /** A scan that filled its cap may have missed older matches, and has to say so. */
    @Test
    public void testFilledCapIsReportedAsTruncated() throws Exception {
        StreamFlowService streamFlowService = newService();
        when(kafkaAdminService.listTopics()).thenReturn(List.of("orders"));
        when(kafkaAdminService.getRecentRecords(eq("orders"), anyInt())).thenReturn(List.of(
            record("orders", 0, 1L, 100L, "K-1", "a"),
            record("orders", 0, 2L, 150L, "other", "b")));

        StreamFlowResponse response = streamFlowService.getStreamFlow(
            new StreamFlowRequest("K-1", 2, null, null, false, null));

        assertTrue(response.stats().truncated());
        assertEquals(2, response.stats().messagesScanned());
        assertEquals(1, response.stats().matches());
        assertTrue(response.warnings().stream().anyMatch(w -> w.contains("per-topic cap")),
            "expected a truncation warning, got " + response.warnings());
    }

    /** A topic that cannot be read is named, instead of being indistinguishable from "no match". */
    @Test
    public void testUnreadableTopicIsReported() throws Exception {
        StreamFlowService streamFlowService = newService();
        when(kafkaAdminService.listTopics()).thenReturn(List.of("orders", "broken"));
        when(kafkaAdminService.getRecentRecords(eq("orders"), anyInt()))
            .thenReturn(List.of(record("orders", 100L, "K-1", "a")));
        when(kafkaAdminService.getRecentRecords(eq("broken"), anyInt()))
            .thenThrow(new IllegalStateException("broker down"));

        StreamFlowResponse response = streamFlowService.getStreamFlow(
            new StreamFlowRequest("K-1", 10, null, null, false, null));

        assertEquals(1, response.stats().topicsFailed());
        assertEquals(1, response.stats().topicsScanned());
        assertTrue(response.warnings().stream().anyMatch(w -> w.contains("broken (broker down)")),
            "expected the failing topic to be named, got " + response.warnings());
    }

    /** The explorer's own bookkeeping topics match almost any key; they are out of a blind scan. */
    @Test
    public void testInternalTopicsAreExcludedFromAWholeClusterScan() throws Exception {
        StreamFlowService streamFlowService = newService();
        when(kafkaAdminService.listTopics())
            .thenReturn(List.of("orders", "internal.audit.history", "internal.metrics.config"));
        when(kafkaAdminService.getRecentRecords(eq("orders"), anyInt()))
            .thenReturn(List.of(record("orders", 100L, "K-1", "a")));

        StreamFlowResponse response = streamFlowService.getStreamFlow(
            new StreamFlowRequest("K-1", 10, null, null, false, null));

        assertEquals(1, response.stats().topicsInScope());
        Mockito.verify(kafkaAdminService, Mockito.never()).getRecentRecords(eq("internal.audit.history"), anyInt());
    }

    /** Naming a topic that does not exist is a typo worth surfacing, not an empty result. */
    @Test
    public void testUnknownTargetTopicIsReported() throws Exception {
        StreamFlowService streamFlowService = newService();
        when(kafkaAdminService.listTopics()).thenReturn(List.of("orders"));
        when(kafkaAdminService.getRecentRecords(eq("odrers"), anyInt())).thenReturn(List.of());

        StreamFlowResponse response = streamFlowService.getStreamFlow(
            new StreamFlowRequest("K-1", 10, null, null, false, List.of("odrers")));

        assertTrue(response.warnings().stream().anyMatch(w -> w.contains("odrers")),
            "expected the unknown topic to be named, got " + response.warnings());
    }

    /** A time window routes the read through the timestamp-seeking path. */
    @Test
    public void testTimeWindowUsesRecordsSince() throws Exception {
        StreamFlowService streamFlowService = newService();
        when(kafkaAdminService.listTopics()).thenReturn(List.of("orders"));
        when(kafkaAdminService.getRecordsSince(eq("orders"), anyInt(), anyInt())).thenReturn(List.of());

        StreamFlowResponse response = streamFlowService.getStreamFlow(
            new StreamFlowRequest("K-1", 25, null, 15, false, null));

        verify(kafkaAdminService).getRecordsSince("orders", 15, 25);
        assertEquals(15, response.stats().timeLimitMinutes());
    }
}
