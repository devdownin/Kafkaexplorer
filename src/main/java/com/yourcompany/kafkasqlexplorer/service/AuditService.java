// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors
package com.yourcompany.kafkasqlexplorer.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yourcompany.kafkasqlexplorer.config.ExplorerConfig;
import com.yourcompany.kafkasqlexplorer.config.KafkaConfig;
import com.yourcompany.kafkasqlexplorer.domain.*;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringSerializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import jakarta.annotation.PreDestroy;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

/**
 * Service providing cluster-wide health and performance auditing.
 * It combines Kafka metadata, Flink SQL queries, and naming convention heuristics
 * to provide a high-level view of data quality and throughput.
 */
@Service
public class AuditService {

    private static final Logger log = LoggerFactory.getLogger(AuditService.class);

    /** Cap on messages scanned per topic when counting duplicate keys in-process. */
    private static final int DUPLICATE_SCAN_MAX_MESSAGES = 10_000;
    /** Cap on messages scanned per topic when correlating flow latency in-process. */
    private static final int LATENCY_SCAN_MAX_MESSAGES = 1_000;
    /** Messages sampled per topic for format detection / poison checks. */
    private static final int POISON_SAMPLE_SIZE = 10;
    /**
     * Reports kept in memory. Every report holds one entry per topic, so an unbounded map grew
     * without limit on a long-running instance — one audit of a 2 000-topic cluster is already
     * hundreds of kilobytes and nothing ever evicted it.
     */
    private static final int MAX_RETAINED_RUNS = 20;

    private final KafkaAdminService kafkaAdminService;
    private final FlinkSqlService flinkSqlService;
    private final SchemaInferenceService schemaInferenceService;
    private final DdlGeneratorService ddlGeneratorService;
    private final NamingConventionService namingConventionService;
    private final MessageFieldExtractorService messageFieldExtractorService;
    private final KafkaConfig kafkaConfig;
    private final ExplorerConfig explorerConfig;
    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * Retained runs, newest last. Access is guarded by the map itself (see {@link #storeReport})
     * because eviction reads the insertion order — a plain ConcurrentHashMap has none.
     */
    private final Map<String, AuditReport> auditRuns = Collections.synchronizedMap(
        new LinkedHashMap<>(16, 0.75f, false) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, AuditReport> eldest) {
                return size() > MAX_RETAINED_RUNS;
            }
        });
    private volatile String lastAuditId = null;

    /**
     * Dedicated executor for background audit runs. Spring's {@code @Async} cannot be used
     * here: {@code startAudit()} would self-invoke the async method, bypassing the proxy and
     * running the whole audit synchronously on the HTTP thread.
     */
    private final ExecutorService auditExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "cluster-audit-runner");
        t.setDaemon(true);
        return t;
    });

    /**
     * Bounded pool for per-topic audits. Fanning one commonPool task out per topic opened
     * dozens of simultaneous Kafka consumers (schema sampling + duplicate scans) on large
     * clusters; four workers keep the audit parallel without hammering the broker.
     */
    private final ExecutorService topicAuditExecutor = Executors.newFixedThreadPool(4, r -> {
        Thread t = new Thread(r);
        t.setName("audit-topic-worker-" + t.getId());
        t.setDaemon(true);
        return t;
    });

    /** Shared producer for audit-history persistence — one KafkaProducer per report is wasteful. */
    private volatile KafkaProducer<String, String> historyProducer;

    public AuditService(KafkaAdminService kafkaAdminService,
                        FlinkSqlService flinkSqlService,
                        SchemaInferenceService schemaInferenceService,
                        DdlGeneratorService ddlGeneratorService,
                        NamingConventionService namingConventionService,
                        MessageFieldExtractorService messageFieldExtractorService,
                        KafkaConfig kafkaConfig,
                        ExplorerConfig explorerConfig) {
        this.kafkaAdminService = kafkaAdminService;
        this.flinkSqlService = flinkSqlService;
        this.schemaInferenceService = schemaInferenceService;
        this.ddlGeneratorService = ddlGeneratorService;
        this.namingConventionService = namingConventionService;
        this.messageFieldExtractorService = messageFieldExtractorService;
        this.kafkaConfig = kafkaConfig;
        this.explorerConfig = explorerConfig;
    }

    public String startAudit(AuditOptions options) {
        String auditId = UUID.randomUUID().toString();
        lastAuditId = auditId;
        Map<String, Object> pending = new LinkedHashMap<>();
        pending.put("phase", "starting");
        pending.put("startedAt", System.currentTimeMillis());
        pending.put("options", describeOptions(options));
        storeReport(new AuditReport(auditId, AuditStatus.RUNNING, 0, 0, 0,
            Collections.emptyList(), Collections.emptyList(), pending));

        auditExecutor.submit(() -> runAuditAsync(auditId, options));
        return auditId;
    }

    public AuditReport getAuditReport(String auditId) {
        return auditRuns.get(auditId);
    }

    public AuditReport getLastAuditReport() {
        return lastAuditId != null ? auditRuns.get(lastAuditId) : null;
    }

    private void storeReport(AuditReport report) {
        auditRuns.put(report.auditId(), report);
    }

    /** Echoes the checks that actually ran, so a report can be read without guessing its scope. */
    private static Map<String, Object> describeOptions(AuditOptions options) {
        Map<String, Object> described = new LinkedHashMap<>();
        described.put("checkSchema", options.checkSchema());
        described.put("checkExactCount", options.checkExactCount());
        described.put("checkPoisonMessages", options.checkPoisonMessages());
        described.put("checkDuplicates", options.checkDuplicates());
        described.put("checkFlows", options.checkFlows());
        described.put("topicPrefix", options.normalizedPrefix());
        return described;
    }

    /**
     * Executes the audit process (submitted to {@link #auditExecutor} by {@link #startAudit})
     * so the HTTP thread returns immediately with the audit id.
     * The results are stored in an in-memory map and also persisted to Kafka.
     */
    protected void runAuditAsync(String auditId, AuditOptions options) {
        long startedAt = System.currentTimeMillis();
        try {
            List<String> topics = kafkaAdminService.listTopics();
            // Optional prefix filter: audit only topics whose name starts with it.
            String prefix = options.normalizedPrefix();
            if (prefix != null) {
                topics = topics.stream().filter(t -> t.startsWith(prefix)).toList();
            }
            Map<String, Long> topicSizes = kafkaAdminService.getTopicsSize(topics);

            int totalTopics = topics.size();
            AtomicInteger completed = new AtomicInteger();
            publishProgress(auditId, options, startedAt, "topics", 0, totalTopics);

            // Parallelize topic auditing on a bounded pool (not the shared commonPool). Each topic
            // is isolated (auditTopicSafe) so one topic's failure degrades that topic to UNHEALTHY
            // rather than aborting the whole cluster audit via CompletableFuture.join().
            List<CompletableFuture<TopicAudit>> futures = topics.stream()
                .map(topic -> CompletableFuture
                    .supplyAsync(() -> auditTopicSafe(topic, topicSizes.getOrDefault(topic, 0L), options),
                        topicAuditExecutor)
                    // Publish progress as topics land so the UI can show a real bar instead of a
                    // decorative one — a full-cluster audit runs for minutes with no other signal.
                    .whenComplete((audit, error) -> publishProgress(
                        auditId, options, startedAt, "topics", completed.incrementAndGet(), totalTopics)))
                .toList();

            List<TopicAudit> topicAudits = futures.stream()
                .map(CompletableFuture::join)
                .collect(Collectors.toList());

            long unhealthyCount = topicAudits.stream()
                .filter(a -> HealthStatus.UNHEALTHY.equals(a.healthStatus()))
                .count();

            List<FlowAudit> flowAudits = Collections.emptyList();
            if (options.checkFlows()) {
                publishProgress(auditId, options, startedAt, "flows", totalTopics, totalTopics);
                flowAudits = identifyAndAuditFlows(topicAudits);
            }

            long totalMessages = topicSizes.values().stream().mapToLong(Long::longValue).sum();

            Map<String, Object> globalStats = new LinkedHashMap<>();
            globalStats.put("timestamp", System.currentTimeMillis());
            globalStats.put("startedAt", startedAt);
            globalStats.put("durationMs", System.currentTimeMillis() - startedAt);
            globalStats.put("options", describeOptions(options));
            List<String> scopeNotes = scopeNotes(options, topicAudits);
            if (!scopeNotes.isEmpty()) globalStats.put("scopeNotes", scopeNotes);

            // KRaft upgrade completeness: features finalized below what the brokers support.
            // metadata.version lagging means a rolling upgrade was never finalized with
            // `kafka-features.sh upgrade` — new metadata features stay disabled cluster-wide.
            List<Map<String, Object>> laggingFeatures = kafkaAdminService.getLaggingFeatures();
            if (!laggingFeatures.isEmpty()) {
                globalStats.put("laggingFeatures", laggingFeatures);
                laggingFeatures.stream()
                    .filter(f -> "metadata.version".equals(f.get("feature")))
                    .findFirst()
                    .ifPresent(f -> globalStats.put("metadataVersionWarning", String.format(
                        "KRaft metadata.version is finalized at %s while brokers support up to %s — "
                        + "the cluster upgrade is incomplete until `kafka-features.sh upgrade "
                        + "--release-version <version>` is run (new metadata features stay disabled).",
                        f.get("finalizedVersion"), f.get("supportedMaxVersion"))));
            }

            AuditReport finalReport = new AuditReport(
                auditId,
                AuditStatus.COMPLETED,
                topics.size(),
                totalMessages,
                (int) unhealthyCount,
                topicAudits,
                flowAudits,
                globalStats
            );

            storeReport(finalReport);
            persistAuditHistory(finalReport);

        } catch (Exception e) {
            log.error("Audit failed for id {}", auditId, e);
            Map<String, Object> failure = new LinkedHashMap<>();
            // e.getMessage() is null for plenty of exceptions (NPE, TimeoutException…) and a null
            // here reached the UI as "audit finished, nothing to report".
            failure.put("error", e.getMessage() != null ? e.getMessage() : e.getClass().getName());
            failure.put("errorType", e.getClass().getSimpleName());
            failure.put("startedAt", startedAt);
            failure.put("durationMs", System.currentTimeMillis() - startedAt);
            failure.put("options", describeOptions(options));
            storeReport(new AuditReport(auditId, AuditStatus.FAILED, 0, 0, 0,
                Collections.emptyList(), Collections.emptyList(), failure));
        }
    }

    /** Replaces the in-flight RUNNING report so a poll returns real progress. */
    private void publishProgress(String auditId, AuditOptions options, long startedAt,
                                 String phase, int done, int total) {
        AuditReport current = auditRuns.get(auditId);
        if (current != null && current.status() != AuditStatus.RUNNING) return; // finished/replaced
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("phase", phase);
        stats.put("topicsCompleted", done);
        stats.put("topicsTotal", total);
        stats.put("startedAt", startedAt);
        stats.put("options", describeOptions(options));
        storeReport(new AuditReport(auditId, AuditStatus.RUNNING, total, 0, 0,
            Collections.emptyList(), Collections.emptyList(), stats));
    }

    /**
     * States what the run did NOT cover. Several checks quietly degrade to "0 findings" — a
     * duplicate scan reads only the first {@value #DUPLICATE_SCAN_MAX_MESSAGES} messages, and
     * a check whose prerequisite is disabled produces nothing at all. Reporting zero without
     * saying so reads as "clean", which is the one answer an audit must never fake.
     */
    private List<String> scopeNotes(AuditOptions options, List<TopicAudit> topicAudits) {
        List<String> notes = new ArrayList<>();
        if (options.checkDuplicates()) {
            notes.add("Duplicate detection scans at most " + DUPLICATE_SCAN_MAX_MESSAGES
                + " messages from the start of each topic — larger topics are only partially covered.");
        }
        if (options.checkPoisonMessages()) {
            notes.add("Poison-message detection parses a sample of " + POISON_SAMPLE_SIZE
                + " recent messages per topic, not the full topic.");
        }
        if (options.checkFlows()) {
            notes.add("Flow latency correlates the last " + LATENCY_SCAN_MAX_MESSAGES
                + " messages of each pair of consecutive topics on a shared \"id\" field.");
        }
        long degraded = topicAudits.stream()
            .filter(t -> t.issues().stream().anyMatch(i -> i.startsWith("Audit failed")))
            .count();
        if (degraded > 0) {
            notes.add(degraded + " topic(s) could not be audited and are reported as UNHEALTHY.");
        }
        return notes;
    }

    /**
     * Isolates a single topic's audit: any failure (Kafka sampling error, schema inference, …) is
     * turned into a degraded UNHEALTHY {@link TopicAudit} carrying the reason, so one bad topic
     * never fails the whole cluster audit.
     */
    private TopicAudit auditTopicSafe(String topicName, long approximateCount, AuditOptions options) {
        try {
            return auditTopic(topicName, approximateCount, options);
        } catch (Exception e) {
            String reason = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            log.warn("Audit failed for topic '{}': {}", topicName, reason);
            return new TopicAudit(topicName, approximateCount, MessageFormat.AUTO, 0, 0L,
                HealthStatus.UNHEALTHY, List.of("Audit failed: " + reason));
        }
    }

    private TopicAudit auditTopic(String topicName, long approximateCount, AuditOptions options) {
        MessageFormat format = MessageFormat.AUTO;
        Map<String, String> schema = Collections.emptyMap();
        List<String> issues = new ArrayList<>();

        // One sample serves format detection, schema inference and the poison check. Each of the
        // three used to open its own KafkaConsumer (describeTopics + assign + seek + poll) to read
        // the very same ten messages — three round trips per topic, times every topic.
        List<String> samples = (options.checkSchema() || options.checkPoisonMessages())
            ? kafkaAdminService.getSampleMessages(topicName, POISON_SAMPLE_SIZE)
            : Collections.emptyList();

        if (options.checkSchema()) {
            format = schemaInferenceService.detectFormat(topicName, samples);
            schema = schemaInferenceService.inferSchema(topicName, format, samples);
            registerTableIfNeeded(topicName, schema, format);
        } else if (options.checkPoisonMessages()) {
            // Poison detection needs to know what "well-formed" means for this topic. Without the
            // schema pass the format stayed AUTO and the check silently matched nothing, so every
            // topic came back with 0 poison messages.
            format = dominantFormat(samples);
        }

        long exactCount = approximateCount;
        if (options.checkExactCount()) {
            ExactCount counted = getExactCount(topicName, approximateCount);
            exactCount = counted.value();
            if (counted.error() != null) {
                issues.add("Exact count unavailable (" + counted.error() + ") — showing the offset estimate.");
            }
        }

        DuplicateScan duplicateScan = options.checkDuplicates()
            ? detectDuplicates(topicName, schema)
            : DuplicateScan.skipped();

        int poisonCount = 0;
        if (options.checkPoisonMessages()) {
            for (String sample : samples) {
                if (!matchesFormat(sample, format)) poisonCount++;
            }
        }

        if (poisonCount > 0) {
            issues.add("Detected " + poisonCount + " malformed message(s) out of "
                + samples.size() + " sampled.");
        }
        if (options.checkExactCount() && approximateCount > 0 && exactCount == 0) {
            issues.add("Flink SQL returned 0 rows despite Kafka having messages.");
        }
        if (duplicateScan.duplicateKeys() > 0) {
            issues.add("Detected " + duplicateScan.duplicateKeys() + " duplicate value(s) of '"
                + duplicateScan.keyField() + "' over the first " + duplicateScan.scanned() + " message(s).");
        }

        HealthStatus status = issues.isEmpty() ? HealthStatus.HEALTHY : HealthStatus.UNHEALTHY;

        return new TopicAudit(topicName, exactCount, format, poisonCount,
            duplicateScan.duplicateKeys(), status, issues);
    }

    /** Format shared by most of the sample, used when the schema pass is disabled. */
    private static MessageFormat dominantFormat(List<String> samples) {
        int json = 0;
        int xml = 0;
        for (String sample : samples) {
            if (sample == null) continue;
            String trimmed = sample.trim();
            if (trimmed.startsWith("{") || trimmed.startsWith("[")) json++;
            else if (trimmed.startsWith("<")) xml++;
        }
        if (json == 0 && xml == 0) return MessageFormat.AUTO;
        return json >= xml ? MessageFormat.JSON : MessageFormat.XML;
    }

    /**
     * A message is poison when it does not parse as the topic's format. The previous check only
     * looked at the first character, so a truncated {@code {"id":} counted as valid JSON — the
     * exact payload a poison check exists to catch.
     */
    private boolean matchesFormat(String message, MessageFormat format) {
        if (message == null || message.isBlank()) return format != MessageFormat.JSON && format != MessageFormat.XML;
        String trimmed = message.trim();
        if (format == MessageFormat.JSON) {
            if (!(trimmed.startsWith("{") || trimmed.startsWith("["))) return false;
            try {
                objectMapper.readTree(trimmed);
                return true;
            } catch (Exception e) {
                return false;
            }
        }
        if (format == MessageFormat.XML) {
            if (!trimmed.startsWith("<")) return false;
            // extractLeafFields returns an empty map on a parse failure, and a well-formed XML
            // document always has at least one leaf.
            return !messageFieldExtractorService.extractLeafFields(trimmed).isEmpty();
        }
        return true; // AUTO / AVRO: nothing to validate against
    }

    private void registerTableIfNeeded(String topicName, Map<String, String> schema, MessageFormat format) {
        String tableName = DdlGeneratorService.toTableName(topicName);
        if (!flinkSqlService.listTables().contains(tableName)) {
            String ddl = ddlGeneratorService.generateDdl(topicName, schema, format);
            QueryResult ddlResult = flinkSqlService.executeSql(new QueryRequest(ddl, null, null, null, null));
            if (ddlResult.error() != null) {
                log.warn("Could not register table '{}' for audit: {}", tableName, ddlResult.error());
            }
        }
    }

    /** Exact count, plus the reason it fell back to the offset estimate (null when exact). */
    private record ExactCount(long value, String error) {}

    private ExactCount getExactCount(String topicName, long approximateCount) {
        String tableName = DdlGeneratorService.toTableName(topicName);
        QueryResult countResult = flinkSqlService.executeSql(new QueryRequest(
            "SELECT COUNT(*) AS metric_value FROM " + tableName, null, 1, explorerConfig.getAuditQueryTimeoutMs(), null));
        if (countResult.error() != null) {
            // Falling back silently made the "exact count" column indistinguishable from the
            // offset estimate it is supposed to correct.
            return new ExactCount(approximateCount, truncate(countResult.error()));
        }
        if (!countResult.rows().isEmpty()) {
            // The direct Kafka engine aliases aggregates (metric_value / count_all, Double values)
            // while Flink names them EXPR$0 (Long) — accept the first numeric value either way.
            Long value = firstNumericValue(countResult.rows().get(0));
            if (value != null) return new ExactCount(value, null);
        }
        return new ExactCount(approximateCount, "query returned no value");
    }

    private static String truncate(String message) {
        String single = message.replaceAll("\\s+", " ").trim();
        return single.length() > 160 ? single.substring(0, 157) + "…" : single;
    }

    private Long firstNumericValue(Map<String, Object> row) {
        for (Object val : row.values()) {
            if (val instanceof Number number) return number.longValue();
        }
        return null;
    }

    /**
     * Outcome of a duplicate scan: how many distinct values repeated, which key was grouped on,
     * and how many messages that verdict is based on.
     */
    private record DuplicateScan(long duplicateKeys, String keyField, int scanned) {
        static DuplicateScan skipped() {
            return new DuplicateScan(0, null, 0);
        }
    }

    /**
     * Counts keys that appear more than once, scanning messages directly.
     * The previous SQL implementation (GROUP BY subquery with HAVING) is not supported by
     * the direct Kafka SELECT engine and silently returned 0 for every topic.
     */
    private DuplicateScan detectDuplicates(String topicName, Map<String, String> schema) {
        String keyField = namingConventionService.findKeyField(schema);

        List<ConsumerRecord<String, String>> records =
            kafkaAdminService.getEarliestRecords(topicName, DUPLICATE_SCAN_MAX_MESSAGES);
        if (records.isEmpty()) return DuplicateScan.skipped();

        Map<String, Integer> keyCounts = new HashMap<>();
        int scanned = 0;
        for (ConsumerRecord<String, String> record : records) {
            String key;
            if (keyField != null) {
                String value = record.value();
                if (value == null || value.isBlank()) continue;
                key = extractField(value, keyField);
            } else {
                // No id-like field in the schema — which is also the case whenever schema
                // inference is switched off. Grouping on the Kafka record key is the meaningful
                // fallback, and the previous "give up and report 0" made the check look clean.
                key = record.key();
            }
            if (key == null || key.isBlank()) continue;
            keyCounts.merge(key, 1, Integer::sum);
            scanned++;
        }
        if (scanned == 0) return DuplicateScan.skipped();

        long duplicates = keyCounts.values().stream().filter(count -> count > 1).count();
        return new DuplicateScan(duplicates, keyField != null ? keyField : "Kafka record key", scanned);
    }

    /**
     * Extracts a field value from a raw JSON/XML message. XML leaf paths are prefixed with
     * the root tag ("order.id"), so a suffix match is attempted after the direct lookup.
     */
    private String extractField(String message, String field) {
        Map<String, String> fields = messageFieldExtractorService.extractLeafFields(message);
        String direct = fields.get(field);
        if (direct != null) return direct;
        String suffix = "." + field;
        for (Map.Entry<String, String> entry : fields.entrySet()) {
            if (entry.getKey().endsWith(suffix)) return entry.getValue();
        }
        return null;
    }

    /**
     * Heuristically groups topics into logical business processes (Flows)
     * and calculates metrics like latency.
     */
    private List<FlowAudit> identifyAndAuditFlows(List<TopicAudit> topicAudits) {
        List<FlowAudit> initialFlows = namingConventionService.identifyFlows(topicAudits);
        List<FlowAudit> flowsWithLatency = new ArrayList<>();

        // Every topic in the middle of a flow is both a target (of the previous pair) and a
        // source (of the next one), so an unmemoized calculateLatency read each one from Kafka
        // twice. One cache per run: a topic is fetched at most once, whatever its position.
        Map<String, Map<String, Long>> idTimesByTopic = new HashMap<>();

        for (FlowAudit flow : initialFlows) {
            List<FlowAudit.StepInfo> stepsWithLatency = new ArrayList<>();
            for (int i = 0; i < flow.steps().size(); i++) {
                FlowAudit.StepInfo step = flow.steps().get(i);
                Long latency = null;
                if (i > 0) {
                    latency = calculateLatency(flow.steps().get(i - 1).topicName(), step.topicName(), idTimesByTopic);
                }
                stepsWithLatency.add(new FlowAudit.StepInfo(step.topicName(), step.count(), step.throughputPercentage(), latency));
            }
            flowsWithLatency.add(new FlowAudit(flow.flowName(), stepsWithLatency, flow.overallHealthScore()));
        }

        return flowsWithLatency;
    }

    /**
     * Average delta between Kafka record timestamps of messages sharing the same "id" field
     * in the source and target topics, computed over recent messages. The previous SQL
     * implementation used a JOIN, which the direct Kafka SELECT engine does not support,
     * so latency was silently always null.
     */
    private Long calculateLatency(String sourceTopic, String targetTopic,
                                  Map<String, Map<String, Long>> idTimesByTopic) {
        Map<String, Long> sourceTimesById = firstSeenById(sourceTopic, idTimesByTopic);
        Map<String, Long> targetTimesById = firstSeenById(targetTopic, idTimesByTopic);
        if (sourceTimesById.isEmpty() || targetTimesById.isEmpty()) return null;

        long totalDeltaMs = 0;
        int matched = 0;
        for (Map.Entry<String, Long> target : targetTimesById.entrySet()) {
            Long sourceTs = sourceTimesById.get(target.getKey());
            if (sourceTs != null && target.getValue() > sourceTs) {
                totalDeltaMs += target.getValue() - sourceTs;
                matched++;
            }
        }
        return matched == 0 ? null : totalDeltaMs / matched;
    }

    /**
     * Earliest Kafka record timestamp per "id" over the topic's recent messages — the first
     * time each business event was seen there. Memoized for the duration of one audit run.
     *
     * <p>Both sides of a pair are now reduced the same way: one delta per id instead of one per
     * target record, so an id republished five times downstream no longer weighs five times more
     * in the average.
     */
    private Map<String, Long> firstSeenById(String topic, Map<String, Map<String, Long>> cache) {
        return cache.computeIfAbsent(topic, name -> {
            List<ConsumerRecord<String, String>> records =
                kafkaAdminService.getRecentRecords(name, LATENCY_SCAN_MAX_MESSAGES);
            Map<String, Long> timesById = new HashMap<>();
            for (ConsumerRecord<String, String> record : records) {
                if (record.value() == null) continue;
                String id = extractField(record.value(), "id");
                if (id != null && !id.isBlank()) {
                    timesById.merge(id, record.timestamp(), Math::min);
                }
            }
            return timesById;
        });
    }

    @PreDestroy
    public void shutdown() {
        shutdownExecutor(auditExecutor);
        shutdownExecutor(topicAuditExecutor);
        closeHistoryProducer();
    }

    private void shutdownExecutor(ExecutorService executor) {
        executor.shutdown();
        try {
            if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    protected void persistAuditHistory(AuditReport report) {
        try {
            String value = objectMapper.writeValueAsString(report);
            historyProducer().send(new ProducerRecord<>(explorerConfig.getAuditHistoryTopic(), report.auditId(), value)).get();
            log.info("Persisted audit {} to history topic", report.auditId());
        } catch (Exception e) {
            log.warn("Failed to persist audit history: {}", e.getMessage());
            // Drop the producer so the next attempt reconnects with fresh state/config
            closeHistoryProducer();
        }
    }

    private KafkaProducer<String, String> historyProducer() {
        KafkaProducer<String, String> existing = historyProducer;
        if (existing != null) return existing;
        synchronized (this) {
            if (historyProducer == null) {
                Properties props = new Properties();
                props.putAll(kafkaConfig.getKafkaProperties());
                props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
                props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
                props.put(ProducerConfig.MAX_BLOCK_MS_CONFIG, 500); // Don't block for long in tests or if Kafka is down
                historyProducer = new KafkaProducer<>(props);
            }
            return historyProducer;
        }
    }

    private synchronized void closeHistoryProducer() {
        if (historyProducer != null) {
            try {
                historyProducer.close();
            } catch (Exception ignored) {
            }
            historyProducer = null;
        }
    }
}
