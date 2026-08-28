// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors
package com.compagnonsdudev.kafkasqlexplorer.service;

import com.compagnonsdudev.kafkasqlexplorer.config.ExplorerConfig;
import com.compagnonsdudev.kafkasqlexplorer.domain.StreamFlowCoverage;
import com.compagnonsdudev.kafkasqlexplorer.domain.StreamFlowHit;
import com.compagnonsdudev.kafkasqlexplorer.domain.StreamFlowProgress;
import com.compagnonsdudev.kafkasqlexplorer.domain.StreamFlowRequest;
import com.compagnonsdudev.kafkasqlexplorer.domain.StreamFlowResponse;
import com.compagnonsdudev.kafkasqlexplorer.domain.TopicMessage;
import com.compagnonsdudev.kafkasqlexplorer.domain.TopicSearchRequest;
import com.compagnonsdudev.kafkasqlexplorer.domain.TopicSearchResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
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
        return new StreamFlowRequest(key, 10, path, window, regex, null, null, null, topics, null, null);
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

    /**
     * A whole-cluster trace does not read the topics this application writes for itself. That
     * exclusion used to name two of the three by hand ({@code Set.of(auditHistory, metricsConfig)}),
     * so the field mappings and the demo stack's marker topic were traced — and a configured
     * {@code explorer.internal-topic-prefix} moved the names out from under the literals.
     */
    @Test
    void aWholeClusterTraceSkipsTheApplicationsOwnTopics() throws Exception {
        when(kafkaAdminService.listTopics()).thenReturn(List.of(
            "orders", "internal.audit.history", "internal.metrics.config",
            "internal.field.mappings", "internal.demo.seeded"));
        onSearch("orders", found(List.of(message(0, 1L, 100L, "K-1", "created")), 1));

        service.getStreamFlow(request("K-1", null, false, null, null));

        verify(topicSearchService, never()).search(eq("internal.field.mappings"), any());
        verify(topicSearchService, never()).search(eq("internal.demo.seeded"), any());
        verify(topicSearchService, never()).search(eq("internal.audit.history"), any());
        verify(topicSearchService).search(eq("orders"), any());
    }

    /** And it follows a configured prefix, which a literal could not. */
    @Test
    void theExclusionFollowsTheConfiguredInternalPrefix() throws Exception {
        ExplorerConfig prefixed = new ExplorerConfig();
        prefixed.setInternalTopicPrefix("acme");
        StreamFlowService prefixedService =
            new StreamFlowService(kafkaAdminService, topicSearchService, prefixed);
        when(kafkaAdminService.listTopics())
            .thenReturn(List.of("orders", "acme.internal.audit.history"));
        onSearch("orders", found(List.of(message(0, 1L, 100L, "K-1", "created")), 1));

        prefixedService.getStreamFlow(request("K-1", null, false, null, null));

        verify(topicSearchService, never()).search(eq("acme.internal.audit.history"), any());
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
            new StreamFlowRequest("K-1", null, null, null, false, null, null, null, null, null, null));

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

    // ── Which topics a whole-cluster trace reads ────────────────────────────

    /**
     * The budget used to be spent alphabetically: on a cluster larger than the cap, a trace read
     * {@code a*} onwards and never reached the topics that had seen traffic in the last minute.
     */
    @Test
    void aCappedWholeClusterScanReadsTheMostRecentlyActiveTopics() throws Exception {
        ExplorerConfig config = new ExplorerConfig();
        config.setStreamFlowMaxTopics(2);
        service = new StreamFlowService(kafkaAdminService, topicSearchService, config);

        when(kafkaAdminService.listTopics()).thenReturn(List.of("aardvark", "billing", "orders"));
        when(kafkaAdminService.getTopicsLastMessageTimestamps(any()))
            .thenReturn(Map.of("aardvark", 1_000L, "billing", 9_000L, "orders", 8_000L));
        onSearch("billing", nothing());
        onSearch("orders", nothing());

        StreamFlowResponse response = service.getStreamFlow(request("K-1", null, false, null, null));

        assertEquals(2, response.stats().topicsInScope());
        verify(topicSearchService).search(eq("billing"), any());
        verify(topicSearchService).search(eq("orders"), any());
        verify(topicSearchService, Mockito.never()).search(eq("aardvark"), any());
        assertTrue(response.warnings().stream().anyMatch(w -> w.contains("most recently active")),
            "the choice must be stated, got " + response.warnings());
    }

    /** A topic whose newest record predates the window cannot hold a match inside it. */
    @Test
    void aTimeWindowDropsTopicsWithNothingInsideIt() throws Exception {
        when(kafkaAdminService.listTopics()).thenReturn(List.of("cold", "warm"));
        long now = System.currentTimeMillis();
        when(kafkaAdminService.getTopicsLastMessageTimestamps(any()))
            .thenReturn(Map.of("cold", now - 3_600_000L, "warm", now - 1_000L));
        onSearch("warm", nothing());

        StreamFlowResponse response = service.getStreamFlow(request("K-1", null, false, 5, null));

        assertEquals(1, response.stats().topicsInScope());
        verify(topicSearchService, Mockito.never()).search(eq("cold"), any());
        assertTrue(response.warnings().stream().anyMatch(w -> w.contains("cold")),
            "a dropped topic must be named, got " + response.warnings());
    }

    /** Unknown is not cold: a topic with no reported timestamp is still read, just read last. */
    @Test
    void aTopicWithNoKnownTimestampIsKeptAndRankedLast() throws Exception {
        ExplorerConfig config = new ExplorerConfig();
        config.setStreamFlowMaxTopics(2);
        service = new StreamFlowService(kafkaAdminService, topicSearchService, config);

        when(kafkaAdminService.listTopics()).thenReturn(List.of("busy", "quiet", "unknown"));
        when(kafkaAdminService.getTopicsLastMessageTimestamps(any()))
            .thenReturn(Map.of("busy", 9_000L, "quiet", 1_000L));
        onSearch("busy", nothing());
        onSearch("quiet", nothing());

        service.getStreamFlow(request("K-1", null, false, null, null));

        verify(topicSearchService).search(eq("busy"), any());
        verify(topicSearchService).search(eq("quiet"), any());
    }

    /** Timestamps unavailable: the old truncation stands, and the warning says it is degraded. */
    @Test
    void anUnreadableActivityRankingFallsBackToAlphabeticalAndSaysSo() throws Exception {
        ExplorerConfig config = new ExplorerConfig();
        config.setStreamFlowMaxTopics(1);
        service = new StreamFlowService(kafkaAdminService, topicSearchService, config);

        when(kafkaAdminService.listTopics()).thenReturn(List.of("alpha", "beta"));
        when(kafkaAdminService.getTopicsLastMessageTimestamps(any()))
            .thenThrow(new IllegalStateException("broker unreachable"));
        onSearch("alpha", nothing());

        StreamFlowResponse response = service.getStreamFlow(request("K-1", null, false, null, null));

        verify(topicSearchService).search(eq("alpha"), any());
        assertTrue(response.warnings().stream().anyMatch(w -> w.contains("alphabetical")),
            "a degraded choice is still a choice, got " + response.warnings());
    }

    // ── Continuing a trace ──────────────────────────────────────────────────

    /** A second pass draws one graph, not two: prior hops are chained with the new ones. */
    @Test
    void aContinuedTraceMergesTheEarlierHopsIntoOneChain() throws Exception {
        when(kafkaAdminService.listTopics()).thenReturn(List.of("billing"));
        onSearch("billing", found(List.of(message(1, 7L, 300L, "K-1", "charged")), 3));

        StreamFlowHit prior = new StreamFlowHit("orders", 1, 100L, 100L, 0, 1L, "K-1",
            "{\"id\":\"K-1\"}", null, false);
        StreamFlowResponse response = service.getStreamFlow(new StreamFlowRequest(
            "K-1", 10, null, null, false, null, null, null, List.of("billing"),
            List.of(prior), new StreamFlowCoverage(249, 24_800, 5, 12_000L)));

        assertEquals(List.of("orders", "billing"),
            response.hits().stream().map(StreamFlowHit::topic).toList());
        assertEquals(1, response.edges().size());
        assertEquals("orders", response.edges().get(0).get("from"));
        // La latence du saut est calculée sur la chaîne fusionnée, comme pour une passe unique.
        assertEquals(200L, response.hits().get(1).latencyFromPreviousMs());
    }

    /** The coverage line legends the whole picture, so a continued pass adds to what came before. */
    @Test
    void aContinuedTraceReportsTheCoverageOfBothPasses() throws Exception {
        when(kafkaAdminService.listTopics()).thenReturn(List.of("billing"));
        onSearch("billing", found(List.of(message(0, 1L, 300L, "K-1", "x")), 40));

        StreamFlowResponse response = service.getStreamFlow(new StreamFlowRequest(
            "K-1", 10, null, null, false, null, null, null, List.of("billing"),
            List.of(), new StreamFlowCoverage(249, 24_800, 5, 12_000L)));

        assertEquals(250, response.stats().topicsScanned());
        assertEquals(24_840, response.stats().messagesScanned());
        assertEquals(6, response.stats().matches());
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

    /**
     * A client that hangs up stops the scan instead of leaving workers reading for nobody.
     *
     * <p>Both facts this pins down — the hit is kept, the rest is not read — used to rest on an
     * ordering nothing guarantees. The topics all start at once on a ten-thread pool and
     * {@code scanTopic} reads the cancel flag on entry, so raising it "once one topic has
     * completed" could raise it on an *empty* one that won the race: {@code a} then returned
     * without searching, and the graph came back empty. It failed exactly that way on a loaded
     * runner. So the flag is now raised by the only event that means the hit is recorded — a
     * partial graph with a node in it — and the empty topics block until then, which is what makes
     * "the remaining topics were never read" true rather than likely: with more topics than
     * threads, the ones still queued when the flag goes up never start.</p>
     */
    @Test
    void cancellingStopsTheScanAndKeepsWhatWasFound() throws Exception {
        // Plus de topics que le pool n'a de fils (10) : les derniers sont en file, pas en vol.
        List<String> topics = new java.util.ArrayList<>(List.of("a"));
        for (int i = 0; i < 14; i++) {
            topics.add("empty-" + i);
        }
        when(kafkaAdminService.listTopics()).thenReturn(topics);

        AtomicBoolean cancelled = new AtomicBoolean();
        CountDownLatch hitRecorded = new CountDownLatch(1);

        onSearch("a", found(List.of(message(0, 1L, 100L, "K-1", "x")), 1));
        for (String topic : topics.subList(1, topics.size())) {
            // Un topic vide ne peut pas terminer avant que le hit soit enregistré : sans cela,
            // c'est lui qui déclencherait l'annulation, et le hit ne serait jamais lu.
            when(topicSearchService.search(eq(topic), any())).thenAnswer(invocation -> {
                hitRecorded.await(5, TimeUnit.SECONDS);
                return nothing();
            });
        }

        RecordingListener listener = new RecordingListener() {
            @Override public void onPartialFlow(StreamFlowResponse flow) {
                super.onPartialFlow(flow);
                // Le seul événement qui signifie « quelque chose a été trouvé » : le graphe
                // partiel n'est republié que lorsqu'un topic vient d'y ajouter un saut.
                if (!flow.nodes().isEmpty()) {
                    cancelled.set(true);
                    hitRecorded.countDown();
                }
            }
        };

        StreamFlowResponse result = service.trace(request("K-1", null, false, null, null),
            listener, cancelled::get);

        assertEquals("CANCELLED", result.stats().stopReason());
        assertEquals(1, result.nodes().size(), "what was found before the stop is kept");
        assertTrue(result.stats().topicsScanned() < topics.size(),
            "the topics still queued when the flag went up were never read");
        assertTrue(result.warnings().stream().anyMatch(w -> w.contains("Stopped early")),
            "expected the partial scan to be stated, got " + result.warnings());
        // Nommés, pas seulement comptés : sans leurs noms, impossible de savoir si les topics
        // abandonnés étaient justement ceux qui comptaient — et c'est la portée d'une reprise.
        assertEquals(result.stats().topicsSkipped(), result.stats().skippedTopics().size());
        assertTrue(result.stats().skippedTopics().stream().noneMatch(
                t -> result.hits().stream().anyMatch(h -> h.topic().equals(t))),
            "a topic that answered is not skipped, got " + result.stats().skippedTopics());
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

        service.getStreamFlow(new StreamFlowRequest("K-1", 10, null, null, false, true, null, null, null, null, null));

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
            new StreamFlowRequest("K-1", 10, "$.orderId", null, false, true, null, null, null, null, null)));
    }

    @Test
    void anExactKeySearchRefusesARegex() {
        assertThrows(IllegalArgumentException.class, () -> service.getStreamFlow(
            new StreamFlowRequest("K-.*", 10, null, null, true, true, null, null, null, null, null)));
    }

    private TopicSearchRequest captureCriteria() {
        ArgumentCaptor<TopicSearchRequest> captor = ArgumentCaptor.forClass(TopicSearchRequest.class);
        verify(topicSearchService).search(any(), captor.capture());
        return captor.getValue();
    }
}
