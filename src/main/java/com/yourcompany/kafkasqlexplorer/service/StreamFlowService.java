// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors
package com.yourcompany.kafkasqlexplorer.service;

import com.yourcompany.kafkasqlexplorer.config.ExplorerConfig;
import com.yourcompany.kafkasqlexplorer.domain.StreamFlowHit;
import com.yourcompany.kafkasqlexplorer.domain.StreamFlowRequest;
import com.yourcompany.kafkasqlexplorer.domain.StreamFlowResponse;
import com.yourcompany.kafkasqlexplorer.domain.StreamFlowStats;
import com.yourcompany.kafkasqlexplorer.domain.TopicMessage;
import com.yourcompany.kafkasqlexplorer.domain.TopicSearchRequest;
import com.yourcompany.kafkasqlexplorer.domain.TopicSearchResponse;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Traces one message key across Kafka topics and derives the pipeline it travelled through.
 *
 * <p>The matching itself belongs to {@link MessageMatcher}, and the scanning to
 * {@link TopicSearchService}: a trace is a topic search run over many topics, so it must find
 * exactly what the Topic Explorer's search finds. Two engines with two sets of semantics — this
 * service used to carry its own regex / JSONPath / XPath matcher and its own record fetch — meant a
 * key found by one screen could be missed by the other, and every improvement had to be made twice.
 * What that consolidation brings here: field operators, Kafka header search, the streaming parser
 * that prunes subtrees instead of building a document tree, per-topic scan budgets with a stop
 * reason, and values truncated on the way out rather than a thousand full payloads held in memory
 * per topic.
 *
 * <p>The scan is bounded on purpose and says so: {@link StreamFlowStats} reports what was covered
 * and why it stopped, and {@link StreamFlowResponse#warnings()} names every topic that could not be
 * read, could not be parsed, or hit a cap. An empty graph must read as "the key is not in the window
 * I scanned", never as a bare "not found".
 *
 * <p>A criterion the user can fix — a broken regex, a malformed path — is rejected up front with
 * {@link IllegalArgumentException} instead of quietly degrading.
 */
@Service
public class StreamFlowService {

    private static final Logger log = LoggerFactory.getLogger(StreamFlowService.class);

    /** Hard ceiling on records scanned in one topic, whatever the request asks for. */
    private static final int MAX_RECORDS_PER_TOPIC = 1000;
    /**
     * Applied when the request asks for nothing usable. {@code maxMessagesPerTopic} is a primitive
     * {@code int}, so a JSON body that omits it deserializes to 0 — which used to scan zero records
     * per topic and report an empty flow for a key that was right there.
     */
    private static final int DEFAULT_RECORDS_PER_TOPIC = 100;
    private static final int THREAD_POOL_SIZE = 10;
    /**
     * Matching records kept per topic. The graph needs the first sighting and a sample, not every
     * copy: the true count within the scan travels in {@link TopicSearchResponse#matched()}, and a
     * topic that hits this cap is reported as such rather than under-reporting in silence.
     */
    private static final int MAX_HITS_PER_TOPIC = 25;
    /** Characters of a matching payload kept as evidence next to the graph. */
    private static final int PREVIEW_CHARS = 240;
    /** Warnings are a diagnostic, not a log: past this many per kind the rest is summarised. */
    private static final int MAX_WARNINGS_PER_KIND = 8;
    /** Header searches are written {@code header:correlation-id} in the search path field. */
    private static final String HEADER_PREFIX = "header:";

    private final KafkaAdminService kafkaAdminService;
    private final TopicSearchService topicSearchService;
    private final ExplorerConfig explorerConfig;
    private final ExecutorService executorService;

    public StreamFlowService(KafkaAdminService kafkaAdminService, TopicSearchService topicSearchService,
                             ExplorerConfig explorerConfig) {
        this.kafkaAdminService = kafkaAdminService;
        this.topicSearchService = topicSearchService;
        this.explorerConfig = explorerConfig;
        AtomicInteger counter = new AtomicInteger();
        ThreadFactory factory = runnable -> {
            Thread thread = new Thread(runnable, "stream-flow-" + counter.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
        this.executorService = Executors.newFixedThreadPool(THREAD_POOL_SIZE, factory);
    }

    @PreDestroy
    public void shutdown() {
        executorService.shutdown();
        try {
            if (!executorService.awaitTermination(5, TimeUnit.SECONDS)) {
                executorService.shutdownNow();
            }
        } catch (InterruptedException e) {
            executorService.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    /**
     * @throws IllegalArgumentException when the criterion itself is unusable (no key, invalid regex,
     *         malformed path) — the caller can fix those, so they are reported rather than silently
     *         returning an empty flow
     */
    public StreamFlowResponse getStreamFlow(StreamFlowRequest request) {
        long startNanos = System.nanoTime();
        List<String> warnings = new ArrayList<>();

        int recordLimit = resolveRecordLimit(request.maxMessagesPerTopic());
        Integer timeLimit = request.timeLimitMinutes() != null && request.timeLimitMinutes() > 0
            ? request.timeLimitMinutes()
            : null;

        // Validated on the request thread so a broken regex or path answers 400 before a single
        // consumer is opened. Each topic scan builds its own matcher from the same criteria —
        // MessageMatcher is not thread-safe, and ten workers share nothing.
        TopicSearchRequest criteria = buildCriteria(request, recordLimit, timeLimit,
            explorerConfig.getSearchTimeoutMs());
        MessageMatcher.from(criteria);

        List<String> topicsToScan = resolveTopics(request, warnings);
        if (topicsToScan.isEmpty()) {
            return emptyResponse(warnings, 0, recordLimit, timeLimit, startNanos);
        }

        long deadlineNanos = startNanos + TimeUnit.MILLISECONDS.toNanos(explorerConfig.getStreamFlowTimeoutMs());
        List<TopicScan> scans = scanTopics(topicsToScan, request, recordLimit, timeLimit, deadlineNanos);

        return buildResponse(topicsToScan.size(), scans, recordLimit, timeLimit,
            startNanos, deadlineNanos, warnings);
    }

    /**
     * Translates the trace's criterion into the topic-search vocabulary.
     *
     * <p>The search path drives the mode: {@code header:name} compares one Kafka header,
     * {@code /a/b} is an XPath, {@code $..id} or a filter needs the full JSONPath engine, and
     * anything else — {@code $.a.b}, {@code a.b[].c}, a bare field name — is a dot-notation path
     * resolved against JSON or XML alike. A bare field name used to be rejected outright ("neither
     * JSONPath nor XPath") even though it is the notation the Topic Explorer's own field list
     * produces.
     */
    private TopicSearchRequest buildCriteria(StreamFlowRequest request, int recordLimit,
                                             Integer timeLimit, int timeoutMs) {
        String key = request.messageKey() == null ? "" : request.messageKey().trim();
        if (key.isEmpty()) {
            throw new IllegalArgumentException(
                "A message key is required: without a search term there is nothing to trace.");
        }
        String path = request.searchPath() == null ? "" : request.searchPath().trim();
        boolean caseSensitive = request.isCaseSensitive();
        boolean searchHeaders = request.isSearchHeaders();
        String from = timeLimit != null ? "TIMESTAMP" : "LAST_N";

        if (path.isEmpty()) {
            return new TopicSearchRequest(key, request.useRegex() ? "REGEX" : "CONTAINS",
                caseSensitive, true, searchHeaders, null, null, null,
                from, null, timeLimit, null, null, null, MAX_HITS_PER_TOPIC, recordLimit, timeoutMs);
        }

        String mode;
        String field;
        if (path.regionMatches(true, 0, HEADER_PREFIX, 0, HEADER_PREFIX.length())) {
            mode = "HEADER";
            field = path.substring(HEADER_PREFIX.length()).trim();
            if (field.isEmpty()) {
                throw new IllegalArgumentException("A header name is required after \"header:\".");
            }
        } else if (path.startsWith("/")) {
            mode = "XPATH";
            field = path;
        } else {
            // A simple path rides the streaming walker, which prunes subtrees instead of building a
            // document; only what it cannot express falls back to the full JSONPath engine.
            mode = needsFullJsonPath(path) ? "JSONPATH" : "FIELD";
            field = path;
        }
        // CONTAINS, not EQ: the historical behaviour compared the extracted value with
        // String.contains, and a correlation id is often embedded in a larger header value
        // (a W3C traceparent carries the trace id inside a dash-separated tuple).
        String operator = request.useRegex() ? "REGEX" : "CONTAINS";
        return new TopicSearchRequest(null, mode, caseSensitive, false, searchHeaders, field,
            operator, key, from, null, timeLimit, null, null, null,
            MAX_HITS_PER_TOPIC, recordLimit, timeoutMs);
    }

    /**
     * Recursive descent ({@code $..id}) and filters ({@code $.items[?(@.qty>1)]}) are beyond the
     * dot-notation walker. Written without a leading {@code $} they are not valid JSONPath either,
     * and the compiler says so — better than a path that quietly matches nothing.
     */
    private static boolean needsFullJsonPath(String path) {
        return path.contains("..") || path.contains("[?");
    }

    /** The record cap actually applied: the request's, floored to a usable value and capped. */
    private static int resolveRecordLimit(int requested) {
        int limit = requested > 0 ? requested : DEFAULT_RECORDS_PER_TOPIC;
        return Math.min(limit, MAX_RECORDS_PER_TOPIC);
    }

    /**
     * Topics in scope. Explicit targets win; otherwise the whole cluster, minus the explorer's own
     * internal topics (an audit report is a JSON blob full of topic names and message keys — it
     * matches almost anything and would draw a node that means nothing) and capped, because a
     * cluster with thousands of topics would otherwise open thousands of consumers.
     */
    private List<String> resolveTopics(StreamFlowRequest request, List<String> warnings) {
        List<String> requested = request.targetTopics();
        if (requested != null && !requested.isEmpty()) {
            List<String> targets = requested.stream()
                .filter(t -> t != null && !t.isBlank())
                .map(String::trim)
                .distinct()
                .toList();
            warnUnknownTopics(targets, warnings);
            return targets;
        }

        List<String> all;
        try {
            all = kafkaAdminService.listTopics();
        } catch (Exception e) {
            log.error("Failed to list topics for stream flow", e);
            warnings.add("Could not list the cluster's topics: " + rootMessage(e));
            return List.of();
        }

        Set<String> excluded = Set.of(explorerConfig.getAuditHistoryTopic(), explorerConfig.getMetricsConfigTopic());
        List<String> candidates = all.stream()
            .filter(t -> !excluded.contains(t))
            .sorted()
            .toList();

        int maxTopics = explorerConfig.getStreamFlowMaxTopics();
        if (candidates.size() > maxTopics) {
            warnings.add("No target topic selected and the cluster has " + candidates.size()
                + " topics: only the first " + maxTopics + " (alphabetical) were scanned. "
                + "Select the topics to trace, or raise explorer.stream-flow-max-topics.");
            return candidates.subList(0, maxTopics);
        }
        return candidates;
    }

    private void warnUnknownTopics(List<String> targets, List<String> warnings) {
        List<String> known;
        try {
            known = kafkaAdminService.listTopics();
        } catch (Exception e) {
            log.debug("Could not verify target topics: {}", e.getMessage());
            return;
        }
        List<String> unknown = targets.stream().filter(t -> !known.contains(t)).toList();
        if (!unknown.isEmpty()) {
            warnings.add("Unknown topic(s), nothing was read from them: " + String.join(", ", unknown) + ".");
        }
    }

    /**
     * Fans the topics out on the pool and collects under a deadline.
     *
     * <p>Waiting on {@code join()} without a bound was a trap: a whole-cluster trace against an
     * unhealthy broker could pin the HTTP thread for many minutes with no way to tell the user what
     * was happening. Past the deadline the remaining futures are cancelled — a {@code supplyAsync}
     * task that has not started yet never runs — and the response reports how many topics were
     * skipped.
     */
    private List<TopicScan> scanTopics(List<String> topics, StreamFlowRequest request, int recordLimit,
                                       Integer timeLimit, long deadlineNanos) {
        List<CompletableFuture<TopicScan>> futures = topics.stream()
            .map(topic -> CompletableFuture.supplyAsync(
                () -> scanTopic(topic, request, recordLimit, timeLimit, deadlineNanos), executorService))
            .toList();

        List<TopicScan> scans = new ArrayList<>(futures.size());
        boolean budgetSpent = false;
        for (CompletableFuture<TopicScan> future : futures) {
            if (budgetSpent) {
                future.cancel(false);
                continue;
            }
            long remaining = deadlineNanos - System.nanoTime();
            try {
                scans.add(future.get(Math.max(remaining, 0), TimeUnit.NANOSECONDS));
            } catch (TimeoutException e) {
                budgetSpent = true;
                future.cancel(false);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                budgetSpent = true;
                future.cancel(false);
            } catch (Exception e) {
                log.warn("Stream-flow topic scan failed", e);
            }
        }
        return scans;
    }

    private TopicScan scanTopic(String topic, StreamFlowRequest request, int recordLimit,
                                Integer timeLimit, long deadlineNanos) {
        long remainingMs = TimeUnit.NANOSECONDS.toMillis(deadlineNanos - System.nanoTime());
        if (remainingMs <= 0) {
            return TopicScan.skipped(topic);
        }
        // No topic may outlive the trace's own budget, so its scan is bounded by whichever of the
        // per-search timeout and the remaining trace budget comes first.
        int timeoutMs = (int) Math.min(explorerConfig.getSearchTimeoutMs(), remainingMs);

        TopicSearchResponse response;
        try {
            response = topicSearchService.search(topic, buildCriteria(request, recordLimit, timeLimit, timeoutMs));
        } catch (Exception e) {
            log.warn("Failed to scan topic {} for stream flow", topic, e);
            return TopicScan.failed(topic, rootMessage(e));
        }
        if (response == null) {
            return TopicScan.failed(topic, "no response");
        }
        if ("ERROR".equals(response.stopReason())) {
            String detail = response.warnings().isEmpty() ? "scan failed" : response.warnings().get(0);
            return TopicScan.failed(topic, detail);
        }

        boolean capped = response.matched() > response.hits().size();
        boolean truncated = !response.exhausted() || response.scanned() >= recordLimit;
        return new TopicScan(topic, response.hits(), response.matched(), response.scanned(),
            truncated, capped, false, null, response.warnings());
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Graph assembly
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Turns per-topic hits into a chain ordered by first sighting.
     *
     * <p>The first version sorted <em>every</em> occurrence by timestamp and drew an edge between
     * each consecutive pair from different topics. A key seen twice in the same topic (a retry, a
     * compacted update) therefore produced back-edges — {@code orders → billing}, {@code billing →
     * orders} — and topics sharing a millisecond were ordered by whichever worker finished first,
     * so the same trace drew a different picture on each run. One node per topic, ordered by first
     * sighting and tie-broken by name, is both stable and what "where did this message go" means.
     */
    private StreamFlowResponse buildResponse(int topicsInScope, List<TopicScan> scans,
                                             int recordLimit, Integer timeLimit, long startNanos,
                                             long deadlineNanos, List<String> warnings) {
        List<TopicScan> matched = scans.stream().filter(s -> !s.hits().isEmpty()).toList();

        List<StreamFlowHit> hits = new ArrayList<>(matched.size());
        for (TopicScan scan : matched) {
            List<TopicMessage> ordered = scan.hits().stream()
                .sorted(Comparator.comparingLong(TopicMessage::timestamp)
                    .thenComparingLong(TopicMessage::offset))
                .toList();
            TopicMessage first = ordered.get(0);
            TopicMessage last = ordered.get(ordered.size() - 1);
            hits.add(new StreamFlowHit(scan.topic(), scan.matched(), first.timestamp(), last.timestamp(),
                first.partition(), first.offset(), first.key(), preview(first.value()),
                null, scan.capped()));
        }
        hits.sort(Comparator.comparingLong(StreamFlowHit::firstTimestamp)
            .thenComparing(StreamFlowHit::topic));

        List<Map<String, String>> nodes = new ArrayList<>(hits.size());
        List<Map<String, String>> edges = new ArrayList<>(Math.max(hits.size() - 1, 0));
        List<StreamFlowHit> chained = new ArrayList<>(hits.size());
        Set<Long> ambiguousTimestamps = new LinkedHashSet<>();
        boolean negativeHop = false;

        for (int i = 0; i < hits.size(); i++) {
            StreamFlowHit hit = hits.get(i);
            Long latency = null;
            if (i > 0) {
                StreamFlowHit previous = hits.get(i - 1);
                latency = hit.firstTimestamp() - previous.firstTimestamp();
                if (latency == 0) {
                    ambiguousTimestamps.add(hit.firstTimestamp());
                }
                if (latency < 0) {
                    negativeHop = true;
                }
                Map<String, String> edge = new LinkedHashMap<>();
                edge.put("from", previous.topic());
                edge.put("to", hit.topic());
                edge.put("label", formatLatency(latency));
                edges.add(edge);
            }
            chained.add(new StreamFlowHit(hit.topic(), hit.occurrences(), hit.firstTimestamp(),
                hit.lastTimestamp(), hit.firstPartition(), hit.firstOffset(), hit.firstKey(),
                hit.preview(), latency, hit.occurrencesCapped()));

            // LinkedHashMap, not Map.of: the renderer falls back to positional access when a key is
            // missing, and Map.of's iteration order is deliberately randomised per JVM run.
            Map<String, String> node = new LinkedHashMap<>();
            node.put("id", hit.topic());
            node.put("label", hit.topic());
            node.put("type", "topic");
            node.put("timestamp", String.valueOf(hit.firstTimestamp()));
            node.put("hits", String.valueOf(hit.occurrences()));
            nodes.add(node);
        }

        if (!ambiguousTimestamps.isEmpty()) {
            warnings.add("Some topics share the same first-sighting timestamp to the millisecond; "
                + "their order in the chain is arbitrary.");
        }
        if (negativeHop) {
            warnings.add("A hop has a negative latency: record timestamps are set by the producers, "
                + "so their clocks disagree. The order of the chain cannot be trusted to the millisecond.");
        }

        collectScanWarnings(scans, recordLimit, timeLimit, warnings);

        int scanned = (int) scans.stream().filter(s -> !s.skipped() && s.failure() == null).count();
        int failed = (int) scans.stream().filter(s -> s.failure() != null).count();
        int skipped = Math.max(topicsInScope - scanned - failed, 0);
        boolean truncated = scans.stream().anyMatch(TopicScan::truncated);
        String stopReason = skipped > 0 && System.nanoTime() >= deadlineNanos ? "TIME_BUDGET" : "COMPLETE";
        if ("TIME_BUDGET".equals(stopReason)) {
            warnings.add("The " + (explorerConfig.getStreamFlowTimeoutMs() / 1000)
                + "s trace budget was spent before " + skipped + " topic(s) could be read. "
                + "Narrow the target topics, or raise explorer.stream-flow-timeout-ms.");
        }

        StreamFlowStats stats = new StreamFlowStats(
            topicsInScope, scanned, skipped, failed,
            scans.stream().mapToInt(TopicScan::scanned).sum(),
            scans.stream().mapToInt(TopicScan::matched).sum(),
            elapsedMs(startNanos), truncated, stopReason, recordLimit, timeLimit);

        return new StreamFlowResponse(nodes, edges, chained, stats, warnings);
    }

    /**
     * Everything that would otherwise turn into a silent zero: a topic that could not be read, a
     * payload the search path could not apply to (reported by the scan itself), a scan that stopped
     * on one of its caps.
     */
    private void collectScanWarnings(List<TopicScan> scans, int recordLimit, Integer timeLimit,
                                     List<String> warnings) {
        List<String> failures = scans.stream()
            .filter(s -> s.failure() != null)
            .map(s -> s.topic() + " (" + s.failure() + ")")
            .toList();
        if (!failures.isEmpty()) {
            warnings.add("Could not read " + failures.size() + " topic(s): " + summarise(failures));
        }

        // Forwarded verbatim from the scan, prefixed by the topic they belong to.
        List<String> scanNotes = scans.stream()
            .filter(s -> s.failure() == null)
            .flatMap(s -> s.warnings().stream().map(w -> s.topic() + ": " + w))
            .toList();
        if (!scanNotes.isEmpty()) {
            warnings.add(summarise(scanNotes));
        }

        List<String> capped = scans.stream().filter(TopicScan::capped).map(TopicScan::topic).toList();
        if (!capped.isEmpty()) {
            warnings.add(capped.size() + " topic(s) hold more matches than the " + MAX_HITS_PER_TOPIC
                + " kept per topic (" + summarise(capped) + "); the counts shown are a floor.");
        }

        List<String> truncated = scans.stream().filter(TopicScan::truncated).map(TopicScan::topic).toList();
        if (!truncated.isEmpty()) {
            String scope = timeLimit != null
                ? "the " + timeLimit + "-minute window was not read whole"
                : "only the last " + recordLimit + " record(s) were read, so an older match may exist";
            warnings.add(truncated.size() + " topic(s) were not scanned to the end: " + scope + " ("
                + summarise(truncated) + "). Raise \"Max messages / topic\" to widen the scan.");
        }
    }

    private static String summarise(List<String> items) {
        if (items.size() <= MAX_WARNINGS_PER_KIND) {
            return String.join(", ", items);
        }
        return String.join(", ", items.subList(0, MAX_WARNINGS_PER_KIND))
            + " and " + (items.size() - MAX_WARNINGS_PER_KIND) + " more";
    }

    private static String preview(String value) {
        if (value == null) return null;
        String flat = value.strip();
        return flat.length() <= PREVIEW_CHARS ? flat : flat.substring(0, PREVIEW_CHARS) + "…";
    }

    private StreamFlowResponse emptyResponse(List<String> warnings, int topicsInScope,
                                             int recordLimit, Integer timeLimit, long startNanos) {
        return new StreamFlowResponse(List.of(), List.of(), List.of(),
            new StreamFlowStats(topicsInScope, 0, 0, 0, 0, 0, elapsedMs(startNanos), false,
                "COMPLETE", recordLimit, timeLimit),
            warnings);
    }

    private static long elapsedMs(long startNanos) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);
    }

    private static String formatLatency(long millis) {
        if (millis < 0) return String.valueOf(millis) + " ms";
        if (millis < 1000) return "+" + millis + " ms";
        if (millis < 60_000) return "+" + String.format("%.1f", millis / 1000.0) + " s";
        return "+" + (millis / 60_000) + " min";
    }

    /** The outermost message is often {@code null} or a wrapper with no detail. */
    private static String rootMessage(Throwable error) {
        Throwable current = error;
        String message = null;
        while (current != null) {
            if (current.getMessage() != null && !current.getMessage().isBlank()) {
                message = current.getMessage();
            }
            current = current.getCause();
        }
        return message != null ? message : error.getClass().getSimpleName();
    }

    /** One topic's scan, as the graph builder needs it. */
    private record TopicScan(String topic, List<TopicMessage> hits, int matched, int scanned,
                             boolean truncated, boolean capped, boolean skipped, String failure,
                             List<String> warnings) {

        static TopicScan skipped(String topic) {
            return new TopicScan(topic, List.of(), 0, 0, false, false, true, null, List.of());
        }

        static TopicScan failed(String topic, String failure) {
            return new TopicScan(topic, List.of(), 0, 0, false, false, false, failure, List.of());
        }
    }
}
