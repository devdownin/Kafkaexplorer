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
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
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

    private final KafkaAdminService kafkaAdminService;
    private final FlinkSqlService flinkSqlService;
    private final SchemaInferenceService schemaInferenceService;
    private final DdlGeneratorService ddlGeneratorService;
    private final NamingConventionService namingConventionService;
    private final MessageFieldExtractorService messageFieldExtractorService;
    private final KafkaConfig kafkaConfig;
    private final ExplorerConfig explorerConfig;
    private final ObjectMapper objectMapper = new ObjectMapper();

    private final Map<String, AuditReport> auditRuns = new ConcurrentHashMap<>();
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
        AuditReport initialReport = new AuditReport(auditId, AuditStatus.RUNNING, 0, 0, 0, Collections.emptyList(), Collections.emptyList(), Collections.emptyMap());
        auditRuns.put(auditId, initialReport);

        auditExecutor.submit(() -> runAuditAsync(auditId, options));
        return auditId;
    }

    public AuditReport getAuditReport(String auditId) {
        return auditRuns.get(auditId);
    }

    public AuditReport getLastAuditReport() {
        return lastAuditId != null ? auditRuns.get(lastAuditId) : null;
    }

    /**
     * Executes the audit process (submitted to {@link #auditExecutor} by {@link #startAudit})
     * so the HTTP thread returns immediately with the audit id.
     * The results are stored in an in-memory map and also persisted to Kafka.
     */
    protected void runAuditAsync(String auditId, AuditOptions options) {
        try {
            List<String> topics = kafkaAdminService.listTopics();
            Map<String, Long> topicSizes = kafkaAdminService.getTopicsSize(topics);

            // Parallelize topic auditing on a bounded pool (not the shared commonPool). Each topic
            // is isolated (auditTopicSafe) so one topic's failure degrades that topic to UNHEALTHY
            // rather than aborting the whole cluster audit via CompletableFuture.join().
            List<CompletableFuture<TopicAudit>> futures = topics.stream()
                .map(topic -> CompletableFuture.supplyAsync(
                    () -> auditTopicSafe(topic, topicSizes.getOrDefault(topic, 0L), options), topicAuditExecutor))
                .toList();

            List<TopicAudit> topicAudits = futures.stream()
                .map(CompletableFuture::join)
                .collect(Collectors.toList());

            long unhealthyCount = topicAudits.stream()
                .filter(a -> HealthStatus.UNHEALTHY.equals(a.healthStatus()))
                .count();

            List<FlowAudit> flowAudits = options.checkFlows()
                ? identifyAndAuditFlows(topicAudits)
                : Collections.emptyList();

            long totalMessages = topicSizes.values().stream().mapToLong(Long::longValue).sum();

            Map<String, Object> globalStats = new LinkedHashMap<>();
            globalStats.put("timestamp", System.currentTimeMillis());

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

            auditRuns.put(auditId, finalReport);
            persistAuditHistory(finalReport);

        } catch (Exception e) {
            log.error("Audit failed for id {}", auditId, e);
            auditRuns.put(auditId, new AuditReport(auditId, AuditStatus.FAILED, 0, 0, 0, Collections.emptyList(), Collections.emptyList(), Map.of("error", e.getMessage())));
        }
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

        if (options.checkSchema()) {
            format = schemaInferenceService.detectFormat(topicName);
            schema = schemaInferenceService.inferSchema(topicName, format);
            registerTableIfNeeded(topicName, schema, format);
        }

        long exactCount = options.checkExactCount()
            ? getExactCount(topicName, approximateCount)
            : approximateCount;

        long duplicates = options.checkDuplicates()
            ? detectDuplicates(topicName, schema)
            : 0;

        int poisonCount = 0;
        List<String> issues = new ArrayList<>();

        if (options.checkPoisonMessages()) {
            List<String> samples = kafkaAdminService.getSampleMessages(topicName, 10);
            for (String sample : samples) {
                if (format == MessageFormat.JSON && !(sample.trim().startsWith("{") || sample.trim().startsWith("["))) {
                    poisonCount++;
                } else if (format == MessageFormat.XML && !sample.trim().startsWith("<")) {
                    poisonCount++;
                }
            }
        }

        if (poisonCount > 0) issues.add("Detected " + poisonCount + " malformed messages in sample.");
        if (options.checkExactCount() && approximateCount > 0 && exactCount == 0) issues.add("Flink SQL returned 0 rows despite Kafka having messages.");
        if (duplicates > 0) issues.add("Detected " + duplicates + " key(s) with duplicate records.");

        HealthStatus status = issues.isEmpty() ? HealthStatus.HEALTHY : HealthStatus.UNHEALTHY;

        return new TopicAudit(topicName, exactCount, format, poisonCount, duplicates, status, issues);
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

    private long getExactCount(String topicName, long approximateCount) {
        String tableName = DdlGeneratorService.toTableName(topicName);
        QueryResult countResult = flinkSqlService.executeSql(new QueryRequest(
            "SELECT COUNT(*) AS metric_value FROM " + tableName, null, 1, explorerConfig.getAuditQueryTimeoutMs(), null));
        if (countResult.error() == null && !countResult.rows().isEmpty()) {
            // The direct Kafka engine aliases aggregates (metric_value / count_all, Double values)
            // while Flink names them EXPR$0 (Long) — accept the first numeric value either way.
            Long value = firstNumericValue(countResult.rows().get(0));
            if (value != null) return value;
        }
        return approximateCount;
    }

    private Long firstNumericValue(Map<String, Object> row) {
        for (Object val : row.values()) {
            if (val instanceof Number number) return number.longValue();
        }
        return null;
    }

    /**
     * Counts keys that appear more than once, scanning messages directly.
     * The previous SQL implementation (GROUP BY subquery with HAVING) is not supported by
     * the direct Kafka SELECT engine and silently returned 0 for every topic.
     */
    private long detectDuplicates(String topicName, Map<String, String> schema) {
        String keyField = namingConventionService.findKeyField(schema);
        if (keyField == null) return 0;

        List<ConsumerRecord<String, String>> records =
            kafkaAdminService.getEarliestRecords(topicName, DUPLICATE_SCAN_MAX_MESSAGES);
        if (records.isEmpty()) return 0;

        Map<String, Integer> keyCounts = new HashMap<>();
        for (ConsumerRecord<String, String> record : records) {
            String value = record.value();
            if (value == null || value.isBlank()) continue;
            String key = extractField(value, keyField);
            if (key == null || key.isBlank()) continue;
            keyCounts.merge(key, 1, Integer::sum);
        }
        return keyCounts.values().stream().filter(count -> count > 1).count();
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

        for (FlowAudit flow : initialFlows) {
            List<FlowAudit.StepInfo> stepsWithLatency = new ArrayList<>();
            for (int i = 0; i < flow.steps().size(); i++) {
                FlowAudit.StepInfo step = flow.steps().get(i);
                Long latency = null;
                if (i > 0) {
                    latency = calculateLatency(flow.steps().get(i - 1).topicName(), step.topicName());
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
    private Long calculateLatency(String sourceTopic, String targetTopic) {
        List<ConsumerRecord<String, String>> sourceRecords =
            kafkaAdminService.getRecentRecords(sourceTopic, LATENCY_SCAN_MAX_MESSAGES);
        List<ConsumerRecord<String, String>> targetRecords =
            kafkaAdminService.getRecentRecords(targetTopic, LATENCY_SCAN_MAX_MESSAGES);
        if (sourceRecords.isEmpty() || targetRecords.isEmpty()) return null;

        // Keep the earliest source timestamp per id (first emission of the business event)
        Map<String, Long> sourceTimesById = new HashMap<>();
        for (ConsumerRecord<String, String> record : sourceRecords) {
            if (record.value() == null) continue;
            String id = extractField(record.value(), "id");
            if (id != null && !id.isBlank()) {
                sourceTimesById.merge(id, record.timestamp(), Math::min);
            }
        }
        if (sourceTimesById.isEmpty()) return null;

        long totalDeltaMs = 0;
        int matched = 0;
        for (ConsumerRecord<String, String> record : targetRecords) {
            if (record.value() == null) continue;
            String id = extractField(record.value(), "id");
            if (id == null) continue;
            Long sourceTs = sourceTimesById.get(id);
            if (sourceTs != null && record.timestamp() > sourceTs) {
                totalDeltaMs += record.timestamp() - sourceTs;
                matched++;
            }
        }
        return matched == 0 ? null : totalDeltaMs / matched;
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
