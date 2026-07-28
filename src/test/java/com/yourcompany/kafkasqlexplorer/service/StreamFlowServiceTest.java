// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors
package com.yourcompany.kafkasqlexplorer.service;

import com.yourcompany.kafkasqlexplorer.config.ExplorerConfig;
import com.yourcompany.kafkasqlexplorer.domain.StreamFlowHit;
import com.yourcompany.kafkasqlexplorer.domain.StreamFlowProgress;
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
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

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
        return new StreamFlowRequest(key, 10, path, window, regex, null, null, null, topics);
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
        // LAST_N + un plancher temporel : les messages les plus récents *dans* la fenêtre.
        assertEquals("LAST_N", criteria.resolvedFrom());
        assertEquals(15, criteria.sinceMinutes());
        assertEquals(15, response.stats().timeLimitMinutes());
    }

    /** An omitted cap used to scan nothing at all; it is simply the default now. */
    @Test
    void anOmittedMaxMessagesFallsBackToADefault() throws Exception {
        when(kafkaAdminService.listTopics()).thenReturn(List.of("orders"));
        onSearch("orders", nothing());

        StreamFlowResponse response = service.getStreamFlow(
            new StreamFlowRequest("K-1", null, null, null, false, null, null, null, null));

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

    // ── Streaming ───────────────────────────────────────────────────────────

    /** Collects what a streamed trace emits, in order. */
    private static class RecordingListener implements StreamFlowService.TraceListener {
        final List<StreamFlowProgress> progress = new CopyOnWriteArrayList<>();
        final List<StreamFlowResponse> partials = new CopyOnWriteArrayList<>();

        @Override public void onProgress(StreamFlowProgress p) { progress.add(p); }

        @Override public void onPartialFlow(StreamFlowResponse flow) { partials.add(flow); }
    }

    @Test
    void aStreamedTraceReportsEveryTopicAsItCompletes() throws Exception {
        when(kafkaAdminService.listTopics()).thenReturn(List.of("a", "b", "c"));
        onSearch("a", found(List.of(message(0, 1L, 100L, "K-1", "x")), 4));
        onSearch("b", nothing());
        onSearch("c", found(List.of(message(0, 1L, 200L, "K-1", "y")), 6));

        RecordingListener listener = new RecordingListener();
        StreamFlowResponse result = service.trace(request("K-1", null, false, null, null),
            listener, () -> false);

        // One opening event plus one per topic.
        assertEquals(4, listener.progress.size());
        StreamFlowProgress opening = listener.progress.get(0);
        assertEquals(0, opening.topicsCompleted());
        assertEquals(3, opening.topicsInScope());
        assertNull(opening.lastTopic());

        StreamFlowProgress last = listener.progress.get(listener.progress.size() - 1);
        assertEquals(3, last.topicsCompleted());
        assertEquals(15, last.messagesScanned(), "the counters accumulate across topics");
        assertEquals(2, last.matches());
        assertNotNull(last.lastTopic());

        assertEquals(2, result.nodes().size());
    }

    /** Only a topic that adds to the graph republishes it — an empty topic is progress, not a flow. */
    @Test
    void aPartialFlowIsPublishedOnlyByATopicThatMatched() throws Exception {
        when(kafkaAdminService.listTopics()).thenReturn(List.of("a", "b", "c"));
        onSearch("a", found(List.of(message(0, 1L, 100L, "K-1", "x")), 1));
        onSearch("b", nothing());
        onSearch("c", found(List.of(message(0, 1L, 200L, "K-1", "y")), 1));

        RecordingListener listener = new RecordingListener();
        service.trace(request("K-1", null, false, null, null), listener, () -> false);

        assertEquals(2, listener.partials.size());
        StreamFlowResponse first = listener.partials.get(0);
        StreamFlowResponse second = listener.partials.get(1);
        assertEquals(1, first.nodes().size(), "the graph grows as topics answer");
        assertEquals(2, second.nodes().size());
        assertEquals(1, second.edges().size());
    }

    /** A partial has pending topics, not skipped ones — the difference reads as a broken scan. */
    @Test
    void aPartialFlowIsMarkedRunningAndCountsNothingAsSkipped() throws Exception {
        when(kafkaAdminService.listTopics()).thenReturn(List.of("a", "b", "c"));
        onSearch("a", found(List.of(message(0, 1L, 100L, "K-1", "x")), 1));
        onSearch("b", nothing());
        onSearch("c", nothing());

        RecordingListener listener = new RecordingListener();
        service.trace(request("K-1", null, false, null, null), listener, () -> false);

        StreamFlowResponse partial = listener.partials.get(0);
        assertEquals("RUNNING", partial.stats().stopReason());
        assertEquals(0, partial.stats().topicsSkipped());
        assertEquals(3, partial.stats().topicsInScope());
    }

    /** A client that hangs up stops the scan instead of leaving workers reading for nobody. */
    @Test
    void cancellingStopsTheScanAndKeepsWhatWasFound() throws Exception {
        when(kafkaAdminService.listTopics()).thenReturn(List.of("a", "b", "c", "d"));
        onSearch("a", found(List.of(message(0, 1L, 100L, "K-1", "x")), 1));
        onSearch("b", nothing());
        onSearch("c", nothing());
        onSearch("d", nothing());

        // Raised as soon as the first topic has been recorded.
        AtomicBoolean cancelled = new AtomicBoolean();
        RecordingListener listener = new RecordingListener() {
            @Override public void onProgress(StreamFlowProgress p) {
                super.onProgress(p);
                if (p.topicsCompleted() >= 1) cancelled.set(true);
            }
        };

        StreamFlowResponse result = service.trace(request("K-1", null, false, null, null),
            listener, cancelled::get);

        assertEquals("CANCELLED", result.stats().stopReason());
        assertEquals(1, result.nodes().size(), "what was found before the stop is kept");
        assertTrue(result.stats().topicsScanned() < 4, "the remaining topics were never read");
        assertTrue(result.warnings().stream().anyMatch(w -> w.contains("Stopped early")),
            "expected the partial scan to be stated, got " + result.warnings());
    }

    /** The plain request/response entry point behaves exactly like a trace nobody is watching. */
    @Test
    void theNonStreamingEntryPointStillWorks() throws Exception {
        when(kafkaAdminService.listTopics()).thenReturn(List.of("a"));
        onSearch("a", found(List.of(message(0, 1L, 100L, "K-1", "x")), 1));

        StreamFlowResponse result = service.getStreamFlow(request("K-1", null, false, null, null));

        assertEquals(1, result.nodes().size());
        assertEquals("COMPLETE", result.stats().stopReason());
    }

    @Test
    void validateCriterionRejectsWithoutReadingAnything() {
        assertThrows(IllegalArgumentException.class,
            () -> service.validateCriterion(request("user-[", null, true, null, null)));
        Mockito.verifyNoInteractions(topicSearchService);
    }

    // ── Exact record key ────────────────────────────────────────────────────

    @Test
    void anExactKeySearchComparesTheKeyAndTargetsItsPartition() throws Exception {
        when(kafkaAdminService.listTopics()).thenReturn(List.of("orders"));
        onSearch("orders", nothing());

        service.getStreamFlow(new StreamFlowRequest("K-1", 10, null, null, false, true, null, null, null));

        TopicSearchRequest criteria = captureCriteria();
        assertEquals("KEY", criteria.resolvedMode());
        assertEquals("EQ", criteria.resolvedOperator());
        assertEquals("K-1", criteria.value());
        assertTrue(criteria.isKeyPartitioning());
    }

    /** The key is not inside the payload: asking for both is a contradiction, not a preference. */
    @Test
    void anExactKeySearchRefusesASearchPath() {
        assertThrows(IllegalArgumentException.class, () -> service.getStreamFlow(
            new StreamFlowRequest("K-1", 10, "$.orderId", null, false, true, null, null, null)));
    }

    @Test
    void anExactKeySearchRefusesARegex() {
        assertThrows(IllegalArgumentException.class, () -> service.getStreamFlow(
            new StreamFlowRequest("K-.*", 10, null, null, true, true, null, null, null)));
    }

    private TopicSearchRequest captureCriteria() {
        ArgumentCaptor<TopicSearchRequest> captor = ArgumentCaptor.forClass(TopicSearchRequest.class);
        verify(topicSearchService).search(any(), captor.capture());
        return captor.getValue();
    }
}
