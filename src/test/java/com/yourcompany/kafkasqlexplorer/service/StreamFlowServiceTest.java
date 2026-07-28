// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors
package com.yourcompany.kafkasqlexplorer.service;

import com.yourcompany.kafkasqlexplorer.config.ExplorerConfig;
import com.yourcompany.kafkasqlexplorer.domain.StreamFlowHit;
import com.yourcompany.kafkasqlexplorer.domain.StreamFlowRequest;
import com.yourcompany.kafkasqlexplorer.domain.StreamFlowResponse;
import com.yourcompany.kafkasqlexplorer.domain.TopicMessage;
import com.yourcompany.kafkasqlexplorer.domain.TopicSearchRequest;
import com.yourcompany.kafkasqlexplorer.domain.TopicSearchResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The trace's own logic: how a criterion is translated into a topic search, and how per-topic hits
 * become a chain. What a criterion actually matches is {@link MessageMatcherTest}'s subject — there
 * is one matching engine now, tested in one place.
 */
class StreamFlowServiceTest {

    private KafkaAdminService kafkaAdminService;
    private TopicSearchService topicSearchService;
    private StreamFlowService service;

    @BeforeEach
    void setUp() {
        kafkaAdminService = Mockito.mock(KafkaAdminService.class);
        topicSearchService = Mockito.mock(TopicSearchService.class);
        service = new StreamFlowService(kafkaAdminService, topicSearchService, new ExplorerConfig());
    }

    private static StreamFlowRequest request(String key, String path, boolean regex, Integer window,
                                             List<String> topics) {
        return new StreamFlowRequest(key, 10, path, window, regex, null, null, topics);
    }

    private static TopicMessage message(int partition, long offset, long timestamp, String key, String value) {
        return TopicMessage.of(partition, offset, timestamp, key, value, Map.of(), 8000);
    }

    private static TopicSearchResponse found(List<TopicMessage> hits, int scanned) {
        return new TopicSearchResponse(hits, scanned, hits.size(), 5L, true, "EXHAUSTED",
            Map.of(), List.of());
    }

    private static TopicSearchResponse nothing() {
        return new TopicSearchResponse(List.of(), 5, 0, 5L, true, "EXHAUSTED", Map.of(), List.of());
    }

    private void onSearch(String topic, TopicSearchResponse response) {
        when(topicSearchService.search(eq(topic), any())).thenReturn(response);
    }

    @Test
    void chainsTopicsByFirstSighting() throws Exception {
        when(kafkaAdminService.listTopics()).thenReturn(List.of("topic1", "topic2", "topic3"));
        onSearch("topic1", found(List.of(message(0, 1L, 100L, "key1", "value1")), 1));
        onSearch("topic2", found(List.of(message(0, 1L, 200L, "key2", "contains-key1")), 1));
        onSearch("topic3", nothing());

        StreamFlowResponse response = service.getStreamFlow(request("key1", null, false, null, null));

        assertEquals(2, response.nodes().size());
        assertEquals(1, response.edges().size());
        Map<String, String> edge = response.edges().get(0);
        assertEquals("topic1", edge.get("from"));
        assertEquals("topic2", edge.get("to"));
        assertEquals("+100 ms", edge.get("label"), "the edge label carries the hop latency");
    }

    /** A key seen twice in one topic must not produce a back-edge. */
    @Test
    void repeatedOccurrencesDoNotCreateBackEdges() throws Exception {
        when(kafkaAdminService.listTopics()).thenReturn(List.of("orders", "billing"));
        onSearch("orders", found(List.of(
            message(0, 10L, 100L, "K-1", "created"),
            message(0, 11L, 300L, "K-1", "retried")), 2));
        onSearch("billing", found(List.of(message(1, 42L, 200L, "K-1", "charged")), 1));

        StreamFlowResponse response = service.getStreamFlow(request("K-1", null, false, null, null));

        assertEquals(2, response.nodes().size());
        assertEquals(1, response.edges().size());
        assertEquals("orders", response.edges().get(0).get("from"));
        assertEquals("billing", response.edges().get(0).get("to"));

        List<StreamFlowHit> hits = response.hits();
        StreamFlowHit orders = hits.get(0);
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
    void nodeCarriesHitCountAndOrderedKeys() throws Exception {
        when(kafkaAdminService.listTopics()).thenReturn(List.of("orders"));
        onSearch("orders", found(List.of(
            message(0, 1L, 100L, "K-1", "a"),
            message(0, 2L, 150L, "K-1", "b")), 2));

        StreamFlowResponse response = service.getStreamFlow(request("K-1", null, false, null, null));

        Map<String, String> node = response.nodes().get(0);
        assertEquals(List.of("id", "label", "type", "timestamp", "hits"), List.copyOf(node.keySet()));
        assertEquals("orders", node.get("id"));
        assertEquals("2", node.get("hits"));
    }

    // ── Criterion → topic search ────────────────────────────────────────────

    @Test
    void plainKeySearchesTheKeyPayloadAndHeaders() throws Exception {
        when(kafkaAdminService.listTopics()).thenReturn(List.of("orders"));
        onSearch("orders", nothing());

        service.getStreamFlow(request("K-1", null, false, null, null));

        TopicSearchRequest criteria = captureCriteria();
        assertEquals("CONTAINS", criteria.resolvedMode());
        assertEquals("K-1", criteria.query());
        assertTrue(criteria.isSearchKey());
        assertTrue(criteria.isSearchHeaders(), "a correlation id often lives only in a header");
        assertFalse(criteria.isCaseSensitive());
        assertEquals("LAST_N", criteria.resolvedFrom());
        assertEquals(10, criteria.maxScan());
    }

    @Test
    void regexSwitchesTheSearchMode() throws Exception {
        when(kafkaAdminService.listTopics()).thenReturn(List.of("orders"));
        onSearch("orders", nothing());

        service.getStreamFlow(request("user-\\d+", null, true, null, null));

        assertEquals("REGEX", captureCriteria().resolvedMode());
    }

    @Test
    void aDotPathBecomesAFieldSearch() throws Exception {
        when(kafkaAdminService.listTopics()).thenReturn(List.of("orders"));
        onSearch("orders", nothing());

        service.getStreamFlow(request("ORD-1", "order.items[].sku", false, null, null));

        TopicSearchRequest criteria = captureCriteria();
        assertEquals("FIELD", criteria.resolvedMode());
        assertEquals("order.items[].sku", criteria.field());
        assertEquals("CONTAINS", criteria.resolvedOperator());
        assertEquals("ORD-1", criteria.value());
    }

    @Test
    void aJsonPathBecomesAFieldSearch() throws Exception {
        when(kafkaAdminService.listTopics()).thenReturn(List.of("orders"));
        onSearch("orders", nothing());

        service.getStreamFlow(request("ORD-1", "$.orderId", false, null, null));

        assertEquals("FIELD", captureCriteria().resolvedMode());
    }

    /** Recursive descent and filters need the full engine; a simple path keeps the fast walker. */
    @Test
    void aRecursivePathBecomesAJsonPathSearch() throws Exception {
        when(kafkaAdminService.listTopics()).thenReturn(List.of("orders"));
        onSearch("orders", nothing());

        service.getStreamFlow(request("ORD-1", "$..orderId", false, null, null));

        assertEquals("JSONPATH", captureCriteria().resolvedMode());
    }

    @Test
    void aSlashPathBecomesAnXPathSearch() throws Exception {
        when(kafkaAdminService.listTopics()).thenReturn(List.of("orders"));
        onSearch("orders", nothing());

        service.getStreamFlow(request("ORD-1", "/order/id", false, null, null));

        TopicSearchRequest criteria = captureCriteria();
        assertEquals("XPATH", criteria.resolvedMode());
        assertEquals("/order/id", criteria.field());
    }

    @Test
    void aHeaderPrefixBecomesAHeaderSearch() throws Exception {
        when(kafkaAdminService.listTopics()).thenReturn(List.of("orders"));
        onSearch("orders", nothing());

        service.getStreamFlow(request("abc-123", "header:correlation-id", false, null, null));

        TopicSearchRequest criteria = captureCriteria();
        assertEquals("HEADER", criteria.resolvedMode());
        assertEquals("correlation-id", criteria.field());
        assertEquals("abc-123", criteria.value());
    }

    @Test
    void aTimeWindowSeeksByTimestamp() throws Exception {
        when(kafkaAdminService.listTopics()).thenReturn(List.of("orders"));
        onSearch("orders", nothing());

        StreamFlowResponse response = service.getStreamFlow(request("K-1", null, false, 15, null));

        TopicSearchRequest criteria = captureCriteria();
        assertEquals("TIMESTAMP", criteria.resolvedFrom());
        assertEquals(15, criteria.sinceMinutes());
        assertEquals(15, response.stats().timeLimitMinutes());
    }

    /** A body omitting maxMessagesPerTopic deserializes to 0 — which used to scan nothing. */
    @Test
    void zeroMaxMessagesFallsBackToADefault() throws Exception {
        when(kafkaAdminService.listTopics()).thenReturn(List.of("orders"));
        onSearch("orders", nothing());

        StreamFlowResponse response = service.getStreamFlow(
            new StreamFlowRequest("K-1", 0, null, null, false, null, null, null));

        assertEquals(100, captureCriteria().maxScan());
        assertEquals(100, response.stats().maxMessagesPerTopic());
    }

    // ── Unusable criteria ───────────────────────────────────────────────────

    @Test
    void invalidRegexIsRejectedBeforeAnyTopicIsRead() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
            () -> service.getStreamFlow(request("user-[", null, true, null, null)));
        assertTrue(error.getMessage().toLowerCase().contains("regular expression"), error.getMessage());
        Mockito.verifyNoInteractions(topicSearchService);
    }

    @Test
    void invalidXPathIsRejected() {
        assertThrows(IllegalArgumentException.class,
            () -> service.getStreamFlow(request("K-1", "/root[", false, null, null)));
    }

    @Test
    void blankMessageKeyIsRejected() {
        assertThrows(IllegalArgumentException.class,
            () -> service.getStreamFlow(request("   ", null, false, null, null)));
        assertThrows(IllegalArgumentException.class,
            () -> service.getStreamFlow(request(null, null, false, null, null)));
    }

    @Test
    void aHeaderSearchWithoutANameIsRejected() {
        assertThrows(IllegalArgumentException.class,
            () -> service.getStreamFlow(request("K-1", "header:", false, null, null)));
    }

    // ── Coverage reporting ──────────────────────────────────────────────────

    @Test
    void scanWarningsAreForwardedWithTheirTopic() throws Exception {
        when(kafkaAdminService.listTopics()).thenReturn(List.of("plain"));
        onSearch("plain", new TopicSearchResponse(List.of(), 50, 0, 5L, true, "EXHAUSTED", Map.of(),
            List.of("The path could not be applied: none of the 50 record(s) scanned is JSON or XML.")));

        StreamFlowResponse response = service.getStreamFlow(request("ORD-1", "$.orderId", false, null, null));

        assertEquals(0, response.nodes().size());
        assertTrue(response.warnings().stream().anyMatch(w -> w.startsWith("plain: ")),
            "expected the scan's own warning, prefixed by its topic, got " + response.warnings());
    }

    @Test
    void aScanThatDidNotReachTheEndIsReportedAsPartial() throws Exception {
        when(kafkaAdminService.listTopics()).thenReturn(List.of("orders"));
        onSearch("orders", new TopicSearchResponse(List.of(message(0, 1L, 100L, "K-1", "a")),
            10, 1, 5L, false, "MAX_SCAN", Map.of(), List.of()));

        StreamFlowResponse response = service.getStreamFlow(request("K-1", null, false, null, null));

        assertTrue(response.stats().truncated());
        assertEquals(10, response.stats().messagesScanned());
        assertEquals(1, response.stats().matches());
        assertTrue(response.warnings().stream().anyMatch(w -> w.contains("not scanned to the end")),
            "expected a truncation warning, got " + response.warnings());
    }

    /** More matches than kept: the count shown is a floor and has to say so. */
    @Test
    void aCappedHitListIsReportedAsAFloor() throws Exception {
        when(kafkaAdminService.listTopics()).thenReturn(List.of("orders"));
        onSearch("orders", new TopicSearchResponse(List.of(message(0, 1L, 100L, "K-1", "a")),
            500, 300, 5L, true, "MAX_HITS", Map.of(), List.of()));

        StreamFlowResponse response = service.getStreamFlow(request("K-1", null, false, null, null));

        assertTrue(response.hits().get(0).occurrencesCapped());
        assertTrue(response.warnings().stream().anyMatch(w -> w.contains("floor")),
            "expected a capped-count warning, got " + response.warnings());
    }

    @Test
    void anUnreadableTopicIsNamed() throws Exception {
        when(kafkaAdminService.listTopics()).thenReturn(List.of("orders", "broken"));
        onSearch("orders", found(List.of(message(0, 1L, 100L, "K-1", "a")), 1));
        when(topicSearchService.search(eq("broken"), any()))
            .thenThrow(new IllegalStateException("broker down"));

        StreamFlowResponse response = service.getStreamFlow(request("K-1", null, false, null, null));

        assertEquals(1, response.stats().topicsFailed());
        assertEquals(1, response.stats().topicsScanned());
        assertTrue(response.warnings().stream().anyMatch(w -> w.contains("broken (broker down)")),
            "expected the failing topic to be named, got " + response.warnings());
    }

    /** Producer clocks disagree; a hop that goes backwards is stated, not hidden. */
    @Test
    void aNegativeHopIsReportedAsClockSkew() throws Exception {
        when(kafkaAdminService.listTopics()).thenReturn(List.of("a", "b"));
        onSearch("a", found(List.of(message(0, 1L, 100L, "K-1", "x")), 1));
        onSearch("b", found(List.of(message(0, 1L, 100L, "K-1", "y")), 1));

        StreamFlowResponse response = service.getStreamFlow(request("K-1", null, false, null, null));

        assertTrue(response.warnings().stream().anyMatch(w -> w.contains("arbitrary")),
            "identical timestamps make the order arbitrary, got " + response.warnings());
    }

    /** The explorer's own bookkeeping topics match almost any key; they are out of a blind scan. */
    @Test
    void internalTopicsAreExcludedFromAWholeClusterScan() throws Exception {
        when(kafkaAdminService.listTopics())
            .thenReturn(List.of("orders", "internal.audit.history", "internal.metrics.config"));
        onSearch("orders", found(List.of(message(0, 1L, 100L, "K-1", "a")), 1));

        StreamFlowResponse response = service.getStreamFlow(request("K-1", null, false, null, null));

        assertEquals(1, response.stats().topicsInScope());
        verify(topicSearchService, Mockito.never()).search(eq("internal.audit.history"), any());
    }

    /** Naming a topic that does not exist is a typo worth surfacing, not an empty result. */
    @Test
    void unknownTargetTopicIsReported() throws Exception {
        when(kafkaAdminService.listTopics()).thenReturn(List.of("orders"));
        onSearch("odrers", nothing());

        StreamFlowResponse response = service.getStreamFlow(
            request("K-1", null, false, null, List.of("odrers")));

        assertTrue(response.warnings().stream().anyMatch(w -> w.contains("odrers")),
            "expected the unknown topic to be named, got " + response.warnings());
    }

    private TopicSearchRequest captureCriteria() {
        ArgumentCaptor<TopicSearchRequest> captor = ArgumentCaptor.forClass(TopicSearchRequest.class);
        verify(topicSearchService).search(any(), captor.capture());
        return captor.getValue();
    }
}
