// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors
package com.yourcompany.kafkasqlexplorer.service;

import com.jayway.jsonpath.Configuration;
import com.jayway.jsonpath.InvalidPathException;
import com.jayway.jsonpath.JsonPath;
import com.jayway.jsonpath.Option;
import com.yourcompany.kafkasqlexplorer.config.ExplorerConfig;
import com.yourcompany.kafkasqlexplorer.domain.StreamFlowHit;
import com.yourcompany.kafkasqlexplorer.domain.StreamFlowRequest;
import com.yourcompany.kafkasqlexplorer.domain.StreamFlowResponse;
import com.yourcompany.kafkasqlexplorer.domain.StreamFlowStats;
import jakarta.annotation.PreDestroy;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.w3c.dom.Document;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.xpath.XPath;
import javax.xml.xpath.XPathConstants;
import javax.xml.xpath.XPathExpression;
import javax.xml.xpath.XPathFactory;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
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
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Traces one message key across Kafka topics and derives the pipeline it travelled through.
 *
 * <p>The scan is bounded on purpose — the last N records of each topic in scope, under a wall-clock
 * budget — and it says so: {@link StreamFlowStats} reports what was covered and why it stopped, and
 * {@link StreamFlowResponse#warnings()} names every topic that could not be read, could not be
 * parsed, or hit its record cap. An empty graph must be readable as "the key is not in the window I
 * scanned", never as a bare "not found".
 *
 * <p>A criterion the user can fix — a broken regex, a search path that is neither JSONPath nor XPath
 * — is rejected up front with {@link IllegalArgumentException} instead of quietly degrading. The
 * previous version compiled the regex in a try/catch and, on failure, fell back to a plain substring
 * search on the regex source: the trace came back empty, or worse subtly wrong, with nothing said.
 */
@Service
public class StreamFlowService {

    private static final Logger log = LoggerFactory.getLogger(StreamFlowService.class);

    /** Hard ceiling on records read from one topic, whatever the request asks for. */
    private static final int MAX_RECORDS_PER_TOPIC = 1000;
    /**
     * Applied when the request asks for nothing usable. {@code maxMessagesPerTopic} is a primitive
     * {@code int}, so a JSON body that omits it deserializes to 0 — which used to scan zero records
     * per topic and report an empty flow for a key that was right there.
     */
    private static final int DEFAULT_RECORDS_PER_TOPIC = 100;
    private static final int THREAD_POOL_SIZE = 10;
    /** Characters of a matching payload kept as evidence next to the graph. */
    private static final int PREVIEW_CHARS = 240;
    /** Warnings are a diagnostic, not a log: past this many per kind the rest is summarised. */
    private static final int MAX_WARNINGS_PER_KIND = 8;

    /**
     * Path evaluation must not cost an exception per record. {@code SUPPRESS_EXCEPTIONS} turns a
     * missing path into a {@code null} result — on a 1 000-record scan where the path is absent,
     * that is 1 000 stack traces not built.
     */
    private static final Configuration JSON_PATH_CONFIG =
        Configuration.defaultConfiguration().addOptions(Option.SUPPRESS_EXCEPTIONS);

    private final KafkaAdminService kafkaAdminService;
    private final ExplorerConfig explorerConfig;
    private final ExecutorService executorService;

    /**
     * DocumentBuilder, XPath and their factories are NOT thread-safe, and topics are
     * scanned from a pool of {@value #THREAD_POOL_SIZE} threads — each worker thread
     * gets its own parser instances, reset() before reuse.
     */
    private final ThreadLocal<DocumentBuilder> documentBuilders =
        ThreadLocal.withInitial(StreamFlowService::createSecureDocumentBuilder);
    private final ThreadLocal<XPath> xPaths =
        ThreadLocal.withInitial(() -> XPathFactory.newInstance().newXPath());

    public StreamFlowService(KafkaAdminService kafkaAdminService, ExplorerConfig explorerConfig) {
        this.kafkaAdminService = kafkaAdminService;
        this.explorerConfig = explorerConfig;
        AtomicInteger counter = new AtomicInteger();
        ThreadFactory factory = runnable -> {
            Thread thread = new Thread(runnable, "stream-flow-" + counter.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
        this.executorService = Executors.newFixedThreadPool(THREAD_POOL_SIZE, factory);
    }

    private static DocumentBuilder createSecureDocumentBuilder() {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
            factory.setXIncludeAware(false);
            factory.setExpandEntityReferences(false);
            return factory.newDocumentBuilder();
        } catch (Exception e) {
            throw new IllegalStateException("Cannot create secure XML parser", e);
        }
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
     *         search path that is neither a JSONPath nor an XPath) — the caller can fix those, so
     *         they are reported rather than silently returning an empty flow.
     */
    public StreamFlowResponse getStreamFlow(StreamFlowRequest request) {
        long startNanos = System.nanoTime();
        List<String> warnings = new ArrayList<>();

        Criterion criterion = Criterion.of(request);
        int recordLimit = resolveRecordLimit(request.maxMessagesPerTopic());
        Integer timeLimit = request.timeLimitMinutes() != null && request.timeLimitMinutes() > 0
            ? request.timeLimitMinutes()
            : null;

        List<String> topicsToScan = resolveTopics(request, warnings);
        if (topicsToScan.isEmpty()) {
            return emptyResponse(warnings, 0, 0, recordLimit, timeLimit, startNanos);
        }

        long deadlineNanos = startNanos + TimeUnit.MILLISECONDS.toNanos(explorerConfig.getStreamFlowTimeoutMs());
        List<TopicScan> scans = scanTopics(topicsToScan, criterion, recordLimit, timeLimit, deadlineNanos);

        return buildResponse(topicsToScan.size(), scans, criterion, recordLimit, timeLimit,
            startNanos, deadlineNanos, warnings);
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
     * <p>Waiting on {@code join()} without a bound was a trap: each topic read carries its own 20 s
     * broker budget, so a whole-cluster trace on an unhealthy broker could pin the HTTP thread for
     * many minutes with no way to tell the user what was happening. Past the deadline the remaining
     * futures are cancelled — a {@code supplyAsync} task that has not started yet never runs — and
     * the response reports how many topics were skipped.
     */
    private List<TopicScan> scanTopics(List<String> topics, Criterion criterion, int recordLimit,
                                       Integer timeLimit, long deadlineNanos) {
        List<CompletableFuture<TopicScan>> futures = topics.stream()
            .map(topic -> CompletableFuture.supplyAsync(
                () -> scanTopic(topic, criterion, recordLimit, timeLimit, deadlineNanos), executorService))
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

    private TopicScan scanTopic(String topic, Criterion criterion, int recordLimit,
                                Integer timeLimit, long deadlineNanos) {
        if (System.nanoTime() >= deadlineNanos) {
            return TopicScan.skipped(topic);
        }

        List<ConsumerRecord<String, String>> records;
        try {
            records = timeLimit != null
                ? kafkaAdminService.getRecordsSince(topic, timeLimit, recordLimit)
                : kafkaAdminService.getRecentRecords(topic, recordLimit);
        } catch (Exception e) {
            log.warn("Failed to scan topic {} for stream flow", topic, e);
            return TopicScan.failed(topic, rootMessage(e));
        }
        if (records == null || records.isEmpty()) {
            return new TopicScan(topic, List.of(), 0, 0, 0, false, false, null);
        }

        // Compiled once per topic rather than once per record: the expression is constant for the
        // whole scan, and XPathExpression is not thread-safe so it cannot be shared across workers.
        XPathExpression xpath;
        try {
            xpath = criterion.xpath() == null ? null : xPaths.get().compile(criterion.xpath());
        } catch (Exception e) {
            return TopicScan.failed(topic, "Invalid XPath: " + rootMessage(e));
        }

        List<Occurrence> occurrences = new ArrayList<>();
        int formatMismatch = 0;
        int parseFailures = 0;

        for (ConsumerRecord<String, String> record : records) {
            MatchOutcome outcome = match(record, criterion, xpath);
            switch (outcome) {
                case MATCH -> occurrences.add(new Occurrence(
                    record.timestamp(), record.partition(), record.offset(), record.key(),
                    preview(record.value())));
                case FORMAT_MISMATCH -> formatMismatch++;
                case PARSE_ERROR -> parseFailures++;
                case NO_MATCH -> { /* nothing to record */ }
            }
        }

        return new TopicScan(topic, occurrences, records.size(), formatMismatch, parseFailures,
            records.size() >= recordLimit, false, null);
    }

    /**
     * Why a record did or did not match. A bare boolean hid the two cases that explain an empty
     * trace: the payload was not in the format the search path expects, or it could not be parsed
     * at all. Both are reported as warnings instead of looking like "the key is not here".
     */
    private enum MatchOutcome { MATCH, NO_MATCH, FORMAT_MISMATCH, PARSE_ERROR }

    private MatchOutcome match(ConsumerRecord<String, String> record, Criterion criterion, XPathExpression xpath) {
        String value = record.value();

        if (criterion.isPathScoped()) {
            // A search path is a scope, not a hint: when one is given, only what it extracts decides.
            // Falling back to a raw substring search (the old behaviour on any payload that was
            // neither JSON nor XML) reported hits the path had never matched.
            if (value == null || value.isBlank()) {
                return MatchOutcome.FORMAT_MISMATCH;
            }
            String trimmed = value.trim();
            if (criterion.jsonPath() != null) {
                if (!looksJson(trimmed)) {
                    return MatchOutcome.FORMAT_MISMATCH;
                }
                return matchJsonPath(value, criterion);
            }
            if (!looksXml(trimmed)) {
                return MatchOutcome.FORMAT_MISMATCH;
            }
            return matchXPath(value, criterion, xpath);
        }

        if (record.key() != null && criterion.test(record.key())) return MatchOutcome.MATCH;
        if (value != null && criterion.test(value)) return MatchOutcome.MATCH;
        return MatchOutcome.NO_MATCH;
    }

    private static boolean looksJson(String trimmed) {
        return trimmed.startsWith("{") || trimmed.startsWith("[");
    }

    private static boolean looksXml(String trimmed) {
        return trimmed.startsWith("<");
    }

    private MatchOutcome matchJsonPath(String json, Criterion criterion) {
        Object result;
        try {
            result = criterion.jsonPath().read(json, JSON_PATH_CONFIG);
        } catch (Exception e) {
            // Malformed document (the path itself was validated when the request came in).
            return MatchOutcome.PARSE_ERROR;
        }
        return matchesExtracted(result, criterion) ? MatchOutcome.MATCH : MatchOutcome.NO_MATCH;
    }

    /**
     * An indefinite path ({@code $.items[*].id}, {@code $..id}) yields a list; testing
     * {@code String.valueOf(list)} would match on the rendering of the whole collection, brackets
     * and commas included. Each element is tested on its own.
     */
    private boolean matchesExtracted(Object result, Criterion criterion) {
        if (result == null) return false;
        if (result instanceof Iterable<?> values) {
            for (Object element : values) {
                if (element != null && criterion.test(String.valueOf(element))) return true;
            }
            return false;
        }
        return criterion.test(String.valueOf(result));
    }

    private MatchOutcome matchXPath(String xml, Criterion criterion, XPathExpression expression) {
        Document doc;
        try {
            DocumentBuilder builder = documentBuilders.get();
            builder.reset();
            doc = builder.parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            return MatchOutcome.PARSE_ERROR;
        }
        try {
            // NODESET first: evaluating as STRING keeps only the first matching node, so a key
            // carried by the second <item> of a document went unseen.
            Object nodes = expression.evaluate(doc, XPathConstants.NODESET);
            if (nodes instanceof NodeList list) {
                for (int i = 0; i < list.getLength(); i++) {
                    Node node = list.item(i);
                    String text = node.getTextContent();
                    if (text != null && criterion.test(text)) return MatchOutcome.MATCH;
                }
                return MatchOutcome.NO_MATCH;
            }
        } catch (Exception ignored) {
            // Expression returns a scalar (string(), count(), concat()…): evaluate it as a string.
        }
        try {
            String result = (String) expression.evaluate(doc, XPathConstants.STRING);
            return result != null && criterion.test(result) ? MatchOutcome.MATCH : MatchOutcome.NO_MATCH;
        } catch (Exception e) {
            return MatchOutcome.NO_MATCH;
        }
    }

    private static String preview(String value) {
        if (value == null) return null;
        String flat = value.strip();
        return flat.length() <= PREVIEW_CHARS ? flat : flat.substring(0, PREVIEW_CHARS) + "…";
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Graph assembly
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Turns per-topic hits into a chain ordered by first sighting.
     *
     * <p>The previous version sorted <em>every</em> occurrence by timestamp and drew an edge between
     * each consecutive pair from different topics. A key seen twice in the same topic (a retry, a
     * compacted update) therefore produced back-edges — {@code orders → billing}, {@code billing →
     * orders} — and topics sharing a millisecond were ordered by whichever worker finished first,
     * so the same trace drew a different picture on each run. One node per topic, ordered by first
     * sighting and tie-broken by name, is both stable and what "where did this message go" means.
     */
    private StreamFlowResponse buildResponse(int topicsInScope, List<TopicScan> scans, Criterion criterion,
                                             int recordLimit, Integer timeLimit, long startNanos,
                                             long deadlineNanos, List<String> warnings) {
        List<TopicScan> matched = scans.stream().filter(s -> !s.occurrences().isEmpty()).toList();

        List<StreamFlowHit> hits = new ArrayList<>(matched.size());
        for (TopicScan scan : matched) {
            List<Occurrence> ordered = scan.occurrences().stream()
                .sorted(Comparator.comparingLong(Occurrence::timestamp).thenComparingLong(Occurrence::offset))
                .toList();
            Occurrence first = ordered.get(0);
            Occurrence last = ordered.get(ordered.size() - 1);
            hits.add(new StreamFlowHit(scan.topic(), ordered.size(), first.timestamp(), last.timestamp(),
                first.partition(), first.offset(), first.key(), first.preview(), null));
        }
        hits.sort(Comparator.comparingLong(StreamFlowHit::firstTimestamp)
            .thenComparing(StreamFlowHit::topic));

        List<Map<String, String>> nodes = new ArrayList<>(hits.size());
        List<Map<String, String>> edges = new ArrayList<>(Math.max(hits.size() - 1, 0));
        List<StreamFlowHit> chained = new ArrayList<>(hits.size());
        Set<Long> ambiguousTimestamps = new LinkedHashSet<>();

        for (int i = 0; i < hits.size(); i++) {
            StreamFlowHit hit = hits.get(i);
            Long latency = null;
            if (i > 0) {
                StreamFlowHit previous = hits.get(i - 1);
                latency = hit.firstTimestamp() - previous.firstTimestamp();
                if (latency == 0) {
                    ambiguousTimestamps.add(hit.firstTimestamp());
                }
                Map<String, String> edge = new LinkedHashMap<>();
                edge.put("from", previous.topic());
                edge.put("to", hit.topic());
                edge.put("label", formatLatency(latency));
                edges.add(edge);
            }
            chained.add(new StreamFlowHit(hit.topic(), hit.occurrences(), hit.firstTimestamp(),
                hit.lastTimestamp(), hit.firstPartition(), hit.firstOffset(), hit.firstKey(),
                hit.preview(), latency));

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

        collectScanWarnings(scans, criterion, recordLimit, timeLimit, warnings);

        int scanned = scans.stream().filter(s -> !s.skipped() && s.failure() == null).mapToInt(s -> 1).sum();
        int failed = (int) scans.stream().filter(s -> s.failure() != null).count();
        int skipped = topicsInScope - scanned - failed;
        boolean truncated = scans.stream().anyMatch(TopicScan::truncated);
        String stopReason = skipped > 0 && System.nanoTime() >= deadlineNanos ? "TIME_BUDGET" : "COMPLETE";
        if ("TIME_BUDGET".equals(stopReason)) {
            warnings.add("The " + (explorerConfig.getStreamFlowTimeoutMs() / 1000)
                + "s trace budget was spent before " + skipped + " topic(s) could be read. "
                + "Narrow the target topics, or raise explorer.stream-flow-timeout-ms.");
        }

        StreamFlowStats stats = new StreamFlowStats(
            topicsInScope, scanned, Math.max(skipped, 0), failed,
            scans.stream().mapToInt(TopicScan::scanned).sum(),
            scans.stream().mapToInt(s -> s.occurrences().size()).sum(),
            elapsedMs(startNanos), truncated, stopReason, recordLimit, timeLimit);

        return new StreamFlowResponse(nodes, edges, chained, stats, warnings);
    }

    /**
     * Everything that would otherwise turn into a silent zero: a topic that could not be read, a
     * payload the search path could not apply to, a scan that stopped on its record cap.
     */
    private void collectScanWarnings(List<TopicScan> scans, Criterion criterion, int recordLimit,
                                     Integer timeLimit, List<String> warnings) {
        List<String> failures = scans.stream()
            .filter(s -> s.failure() != null)
            .map(s -> s.topic() + " (" + s.failure() + ")")
            .toList();
        if (!failures.isEmpty()) {
            warnings.add("Could not read " + failures.size() + " topic(s): " + summarise(failures));
        }

        if (criterion.isPathScoped()) {
            String expected = criterion.jsonPath() != null ? "JSON" : "XML";
            List<String> incompatible = scans.stream()
                .filter(s -> s.scanned() > 0 && s.formatMismatch() == s.scanned())
                .map(TopicScan::topic)
                .toList();
            if (!incompatible.isEmpty()) {
                warnings.add("The search path could not be applied to " + incompatible.size()
                    + " topic(s) — no record scanned is " + expected + ": " + summarise(incompatible)
                    + ". Clear the search path to match the raw key and payload instead.");
            }
            List<String> unparsed = scans.stream()
                .filter(s -> s.parseFailures() > 0)
                .map(s -> s.topic() + " (" + s.parseFailures() + ")")
                .toList();
            if (!unparsed.isEmpty()) {
                warnings.add("Malformed " + expected + " payloads were skipped in "
                    + unparsed.size() + " topic(s): " + summarise(unparsed) + ".");
            }
        }

        List<String> truncated = scans.stream().filter(TopicScan::truncated).map(TopicScan::topic).toList();
        if (!truncated.isEmpty()) {
            String scope = timeLimit != null
                ? "the oldest " + recordLimit + " record(s) of the " + timeLimit
                  + "-minute window were read, so a later match may have been missed"
                : "only the last " + recordLimit + " record(s) were read, so an older match may exist";
            warnings.add(truncated.size() + " topic(s) filled the per-topic cap: " + scope + " ("
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

    private StreamFlowResponse emptyResponse(List<String> warnings, int topicsInScope, int scanned,
                                             int recordLimit, Integer timeLimit, long startNanos) {
        return new StreamFlowResponse(List.of(), List.of(), List.of(),
            new StreamFlowStats(topicsInScope, scanned, 0, 0, 0, 0, elapsedMs(startNanos), false,
                "COMPLETE", recordLimit, timeLimit),
            warnings);
    }

    private static long elapsedMs(long startNanos) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);
    }

    private static String formatLatency(long millis) {
        if (millis < 0) return "";
        if (millis < 1000) return "+" + millis + " ms";
        if (millis < 60_000) return "+" + String.format("%.1f", millis / 1000.0) + " s";
        return "+" + (millis / 60_000) + " min";
    }

    /** Flink-style: the outermost message is often {@code null} or a wrapper with no detail. */
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

    // ─────────────────────────────────────────────────────────────────────────
    // Criterion
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * The compiled search criterion, built once per request.
     *
     * <p>Compiling per request rather than per record matters: {@code JsonPath.read(json, path)}
     * re-parses the path string for every message, and the XPath expression used to be recompiled
     * a thousand times per topic.
     */
    private record Criterion(Pattern regex, String literal, JsonPath jsonPath, String xpath) {

        static Criterion of(StreamFlowRequest request) {
            String key = request.messageKey() == null ? "" : request.messageKey().trim();
            if (key.isEmpty()) {
                throw new IllegalArgumentException(
                    "A message key is required: without a search term there is nothing to trace.");
            }

            Pattern regex = null;
            if (request.useRegex()) {
                try {
                    regex = Pattern.compile(key);
                } catch (PatternSyntaxException e) {
                    // Used to be swallowed, leaving a plain substring search on the regex source.
                    throw new IllegalArgumentException("Invalid regular expression: "
                        + (e.getDescription() == null ? e.getMessage() : e.getDescription())
                        + (e.getIndex() >= 0 ? " (at index " + e.getIndex() + ")" : ""), e);
                }
            }

            String path = request.searchPath() == null ? "" : request.searchPath().trim();
            if (path.isEmpty()) {
                return new Criterion(regex, key, null, null);
            }
            if (path.startsWith("$")) {
                try {
                    return new Criterion(regex, key, JsonPath.compile(path), null);
                } catch (InvalidPathException e) {
                    throw new IllegalArgumentException("Invalid JSONPath \"" + path + "\": " + e.getMessage(), e);
                }
            }
            if (path.startsWith("/")) {
                try {
                    XPathFactory.newInstance().newXPath().compile(path);
                } catch (Exception e) {
                    throw new IllegalArgumentException("Invalid XPath \"" + path + "\": " + e.getMessage(), e);
                }
                return new Criterion(regex, key, null, path);
            }
            throw new IllegalArgumentException("Search path \"" + path
                + "\" is neither a JSONPath (starts with $) nor an XPath (starts with /).");
        }

        boolean isPathScoped() {
            return jsonPath != null || xpath != null;
        }

        boolean test(String content) {
            if (content == null) return false;
            return regex != null ? regex.matcher(content).find() : content.contains(literal);
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Per-topic scan result
    // ─────────────────────────────────────────────────────────────────────────

    private record Occurrence(long timestamp, int partition, long offset, String key, String preview) {}

    private record TopicScan(String topic, List<Occurrence> occurrences, int scanned,
                             int formatMismatch, int parseFailures, boolean truncated,
                             boolean skipped, String failure) {

        static TopicScan skipped(String topic) {
            return new TopicScan(topic, List.of(), 0, 0, 0, false, true, null);
        }

        static TopicScan failed(String topic, String failure) {
            return new TopicScan(topic, List.of(), 0, 0, 0, false, false, failure);
        }
    }
}
