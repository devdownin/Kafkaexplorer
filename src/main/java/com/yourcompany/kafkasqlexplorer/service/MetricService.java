// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors
package com.yourcompany.kafkasqlexplorer.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yourcompany.kafkasqlexplorer.config.ExplorerConfig;
import com.yourcompany.kafkasqlexplorer.config.KafkaConfig;
import com.yourcompany.kafkasqlexplorer.domain.KafkaMessage;
import com.yourcompany.kafkasqlexplorer.domain.MetricConfig;
import com.yourcompany.kafkasqlexplorer.domain.MetricExecutionMode;
import com.yourcompany.kafkasqlexplorer.domain.MetricPreviewResult;
import com.yourcompany.kafkasqlexplorer.domain.MetricTemplateDescriptor;
import com.yourcompany.kafkasqlexplorer.domain.MetricTemplateType;
import com.yourcompany.kafkasqlexplorer.domain.QueryRequest;
import com.yourcompany.kafkasqlexplorer.domain.QueryResult;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tag;
import jakarta.annotation.PostConstruct;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.PartitionInfo;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Bridges Flink SQL queries to Prometheus metrics via Micrometer.
 *
 * Each Prometheus type maps to a distinct Micrometer instrument:
 *   GAUGE     → io.micrometer.core.instrument.Gauge
 *              Point-in-time value that can go up or down.
 *              SQL must return: metric_value (+ optional label columns)
 *
 *   COUNTER   → io.micrometer.core.instrument.Counter
 *              Cumulative, monotonically increasing value.
 *              SQL returns the current total; the service tracks the delta
 *              and increments the counter accordingly.
 *              SQL must return: metric_value (+ optional label columns)
 *
 *   HISTOGRAM → io.micrometer.core.instrument.DistributionSummary
 *              (publishPercentileHistogram = true → Prometheus-native buckets)
 *              Each row's metric_value is recorded as one observation.
 *              Use a bucket SQL (le column) or let Micrometer auto-bucket.
 *              SQL must return: metric_value, [le] (+ optional label columns)
 *
 *   SUMMARY   → io.micrometer.core.instrument.DistributionSummary
 *              (publishPercentiles 0.5, 0.75, 0.9, 0.95, 0.99)
 *              Each metric_value is recorded as an observation; Micrometer
 *              computes client-side quantiles exposed as _summary{quantile=…}.
 *              SQL must return: metric_value (+ optional label columns)
 */
@Service
public class MetricService {

    private static final Logger log = LoggerFactory.getLogger(MetricService.class);
    private static final int MAX_HISTORY = 50;
    private static final int DEFAULT_TEMPLATE_MAX_ROWS = 10_000;
    private static final long DEFAULT_TEMPLATE_TIMEOUT_MS = 30_000L;
    private static final String DEFAULT_TEMPLATE_READ_MODE = "earliest-offset";

    private static final List<MetricTemplateDescriptor> TEMPLATE_DESCRIPTORS = List.of(
        new MetricTemplateDescriptor(
            MetricTemplateType.TOPIC_COUNT_DELTA.name(),
            "Topic Count Delta",
            "Compare two bounded topic counts and compute a gap, ratio or percentage difference.",
            List.of("GAUGE"),
            List.of("leftSql", "rightSql", "operation")
        ),
        new MetricTemplateDescriptor(
            MetricTemplateType.TOPIC_TRANSIT_LATENCY.name(),
            "Topic Transit Latency",
            "Measure processing latency between two topics by matching events on a key and comparing timestamps.",
            List.of("GAUGE", "HISTOGRAM", "SUMMARY"),
            List.of("sourceSql", "targetSql")
        )
    );

    private record MetricComputationResult(
        List<Map<String, Object>> rows,
        Double displayValue,
        String error,
        Map<String, Object> summary
    ) {
        static MetricComputationResult error(String error) {
            return new MetricComputationResult(List.of(), null, error, Map.of());
        }
    }

    private record CorrelationEvent(String matchKey, long eventTime) {}

    // ── metric state ─────────────────────────────────────────────────────────
    private final Map<String, MetricConfig>              metrics           = new ConcurrentHashMap<>();
    private final Map<String, LinkedList<Double>>        historyMap        = new ConcurrentHashMap<>();

    // ── Micrometer instruments per type ──────────────────────────────────────
    /** GAUGE:     metricId → (labelKey → holder)  */
    private final Map<String, Map<String, AtomicReference<Double>>> gaugeHolders = new ConcurrentHashMap<>();
    /** COUNTER:   metricId → (labelKey → Counter) */
    private final Map<String, Map<String, Counter>>                  counterMeters = new ConcurrentHashMap<>();
    /** COUNTER:   metricId → (labelKey → lastSeenValue) for delta computation */
    private final Map<String, Map<String, Double>>                   lastCounterValues = new ConcurrentHashMap<>();
    /** HISTOGRAM / SUMMARY: metricId → (labelKey → DistributionSummary) */
    private final Map<String, Map<String, DistributionSummary>>      distributionMeters = new ConcurrentHashMap<>();

    private final FlinkSqlService flinkSqlService;
    private final MeterRegistry   meterRegistry;
    private final KafkaConfig     kafkaConfig;
    private final ExplorerConfig  explorerConfig;
    private final KafkaAdminService kafkaAdminService;
    private final MessageFieldExtractorService messageFieldExtractorService;
    private final ObjectMapper    objectMapper = new ObjectMapper();

    public MetricService(FlinkSqlService flinkSqlService, MeterRegistry meterRegistry,
                         KafkaConfig kafkaConfig, ExplorerConfig explorerConfig,
                         KafkaAdminService kafkaAdminService,
                         MessageFieldExtractorService messageFieldExtractorService) {
        this.flinkSqlService = flinkSqlService;
        this.meterRegistry   = meterRegistry;
        this.kafkaConfig     = kafkaConfig;
        this.explorerConfig  = explorerConfig;
        this.kafkaAdminService = kafkaAdminService;
        this.messageFieldExtractorService = messageFieldExtractorService;
    }

    // ── Bootstrap ─────────────────────────────────────────────────────────────

    @PostConstruct
    public void init() {
        restoreFromKafka();
        migrateStaleMetrics();
        if (metrics.isEmpty()) {
            seedDefaultMetrics();
        }
    }

    /**
     * If restored metrics reference demo_orders_in but that table isn't registered in Flink,
     * repoint them to the first available table. This handles the case where the application
     * was previously run with demo data that no longer exists.
     */
    private void migrateStaleMetrics() {
        List<String> tables = flinkSqlService.listTables();
        boolean demoExists = tables.contains("demo_orders_in");
        if (demoExists || tables.isEmpty()) return;   // nothing to fix

        String replacement = tables.get(0);
        log.info("demo_orders_in not found in Flink — migrating stale metrics to '{}'", replacement);

        metrics.replaceAll((id, m) -> {
            if (m.sql() == null || !m.sql().contains("demo_orders_in")) return m;
            String newSql = m.sql().replace("demo_orders_in", replacement);
            MetricConfig updated = new MetricConfig(
                m.id(), m.name(), m.type(), newSql, m.description(),
                m.warningThreshold(), m.criticalThreshold(),
                null, m.lastUpdateTime(), null,
                m.history() != null ? m.history() : List.of(),
                m.lastSummary() != null ? new LinkedHashMap<>(m.lastSummary()) : Map.of(),
                m.createTableSql(),
                m.templateType(),
                m.templateParams() != null ? new LinkedHashMap<>(m.templateParams()) : Map.of(),
                m.executionMode(),
                m.labelTopic(),
                m.labelFields() != null ? List.copyOf(m.labelFields()) : List.of());
            persistToKafka(updated);
            return updated;
        });
    }

    /**
     * One representative metric for each Prometheus type.
     * Uses demo_orders_in if registered, otherwise the first available Flink table,
     * otherwise skips seeding (no tables means no useful examples yet).
     */
    private void seedDefaultMetrics() {
        List<String> tables = flinkSqlService.listTables();
        if (tables.isEmpty()) {
            log.info("No Flink tables registered yet — skipping default metric seeding. " +
                     "Create tables via the Query Workbench (CREATE TABLE …) then add metrics manually.");
            return;
        }

        String table = tables.contains("demo_orders_in") ? "demo_orders_in" : tables.get(0);
        log.info("Seeding default metrics using Flink table '{}'", table);

        // GAUGE — point-in-time value (queue depth, active connections, …)
        addMetric(table + "_count", "GAUGE",
            "SELECT COUNT(*) AS metric_value\nFROM " + table,
            "Current record count in " + table,
            500.0, 1000.0);

        // COUNTER — cumulative total that only increases (requests, events, …)
        addMetric(table + "_total", "COUNTER",
            "SELECT COUNT(*) AS metric_value\nFROM " + table,
            "Cumulative record total in " + table + " (delta-tracked counter)",
            null, null);

        // HISTOGRAM — distribution of observed values with Prometheus-native buckets
        // Works generically on any numeric column; falls back to COUNT if no numeric columns detected.
        addMetric(table + "_histogram", "HISTOGRAM",
            "SELECT COUNT(*) AS metric_value\nFROM " + table,
            "Record distribution histogram for " + table + " — replace with a numeric column observation",
            null, null);

        // SUMMARY — quantile observations (latency, amount, …)
        addMetric(table + "_summary", "SUMMARY",
            "SELECT COUNT(*) AS metric_value\nFROM " + table,
            "Record summary for " + table + " — replace metric_value with a numeric column (e.g. AVG(amount))",
            null, null);
    }

    private void addMetric(String name, String type, String sql, String description,
                           Double warning, Double critical) {
        String id = UUID.randomUUID().toString();
        save(new MetricConfig(id, name, type, sql, description, warning, critical, null, null, null));
    }

    // ── Public API ────────────────────────────────────────────────────────────

    public List<MetricConfig> getAllMetrics() {
        return new ArrayList<>(metrics.values());
    }

    public Optional<MetricConfig> getById(String id) {
        return Optional.ofNullable(metrics.get(id));
    }

    public List<MetricTemplateDescriptor> listTemplates() {
        return TEMPLATE_DESCRIPTORS;
    }

    public MetricPreviewResult previewMetric(MetricConfig metric) {
        try {
            MetricConfig normalized = normalizeMetric(metric);
            validateMetric(normalized);
            executeCreateTableIfPresent(normalized);

            MetricComputationResult result = computeMetric(normalized);
            Map<String, Object> summary = new LinkedHashMap<>(result.summary());
            if (MetricExecutionMode.FLINK_MANAGED_JOB.name().equals(normalized.executionMode())) {
                summary.put("plannedExecutionMode", MetricExecutionMode.FLINK_MANAGED_JOB.name());
                summary.put("previewExecutionMode", MetricExecutionMode.TEMPLATE_BOUNDED_SCAN.name());
                summary.put("managedJobStatus", "PLANNED");
                summary.put("managedJobNote", "Preview uses a bounded scan until Flink job orchestration is implemented.");
            }
            List<Map<String, Object>> previewRows = result.rows().size() > 50
                ? result.rows().subList(0, 50)
                : result.rows();
            return new MetricPreviewResult(result.displayValue(), previewRows, result.error(), summary);
        } catch (IllegalArgumentException e) {
            return new MetricPreviewResult(null, List.of(), e.getMessage(), Map.of());
        } catch (Exception e) {
            return new MetricPreviewResult(null, List.of(),
                e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName(), Map.of());
        }
    }

    public void save(MetricConfig metric) {
        String id = (metric.id() == null || metric.id().isEmpty())
            ? UUID.randomUUID().toString() : metric.id();
        MetricConfig m = normalizeMetric(new MetricConfig(
            id, metric.name(), metric.type(), metric.sql(), metric.description(),
            metric.warningThreshold(), metric.criticalThreshold(),
            metric.lastValue(), metric.lastUpdateTime(), metric.errorMessage(),
            metric.history() != null ? metric.history() : List.of(),
            metric.lastSummary() != null ? metric.lastSummary() : Map.of(),
            metric.createTableSql(),
            metric.templateType(),
            metric.templateParams(),
            metric.executionMode(),
            metric.labelTopic(),
            metric.labelFields() != null ? metric.labelFields() : List.of()));
        validateMetric(m, true);
        metrics.put(id, m);
        persistToKafka(m);
    }

    public void delete(String id) {
        metrics.remove(id);
        historyMap.remove(id);
        gaugeHolders.remove(id);
        lastCounterValues.remove(id);
        counterMeters.remove(id);
        distributionMeters.remove(id);
        meterRegistry.find("explorer_metric_gauge").tag("metric_id", id).meters().forEach(meterRegistry::remove);
        meterRegistry.find("explorer_metric_counter").tag("metric_id", id).meters().forEach(meterRegistry::remove);
        meterRegistry.find("explorer_metric_histogram").tag("metric_id", id).meters().forEach(meterRegistry::remove);
        meterRegistry.find("explorer_metric_summary").tag("metric_id", id).meters().forEach(meterRegistry::remove);
        persistToKafka(new MetricConfig(id, null, null, null, null, null, null, null, null, "DELETED"));
    }

    // ── Scheduled refresh ─────────────────────────────────────────────────────

    /**
     * Bounded-scan hint: reads all data that exists in Kafka at query start time, then
     * terminates (no indefinite streaming). This is essential for aggregate metrics:
     * without it, COUNT(*) with latest-offset sees 0 messages and times out.
     */
    private static final String BOUNDED_HINT =
        "/*+ OPTIONS('scan.startup.mode'='earliest-offset') */";

    /**
     * Inject the bounded-scan hint after the first table reference in a FROM clause,
     * unless the SQL already carries a hint or an OPTIONS(...) clause.
     */
    private String injectBoundedHint(String sql) {
        if (sql == null) return null;
        if (sql.contains("/*+") || sql.toUpperCase().contains("OPTIONS(")) return sql;
        // Match FROM <word> — skip subqueries (followed by '(')
        Matcher m = Pattern.compile("(?i)\\bFROM\\b\\s+(\\w[\\w.]*)").matcher(sql);
        if (m.find()) {
            return sql.substring(0, m.end(1)) + " " + BOUNDED_HINT + sql.substring(m.end(1));
        }
        return sql;
    }

    private MetricConfig normalizeMetric(MetricConfig metric) {
        String normalizedType = metric.type() == null || metric.type().isBlank()
            ? "GAUGE"
            : metric.type().trim().toUpperCase(Locale.ROOT);
        MetricTemplateType templateType = MetricTemplateType.fromValue(metric.templateType());
        MetricExecutionMode executionMode = metric.executionMode() == null || metric.executionMode().isBlank()
            ? (templateType == MetricTemplateType.RAW_SQL ? MetricExecutionMode.SQL : MetricExecutionMode.TEMPLATE_BOUNDED_SCAN)
            : MetricExecutionMode.valueOf(metric.executionMode().trim().toUpperCase(Locale.ROOT));

        return new MetricConfig(
            metric.id(),
            metric.name(),
            normalizedType,
            metric.sql(),
            metric.description(),
            metric.warningThreshold(),
            metric.criticalThreshold(),
            metric.lastValue(),
            metric.lastUpdateTime(),
            metric.errorMessage(),
            metric.history() != null ? metric.history() : List.of(),
            metric.lastSummary() != null ? new LinkedHashMap<>(metric.lastSummary()) : Map.of(),
            metric.createTableSql(),
            templateType.name(),
            metric.templateParams() != null ? new LinkedHashMap<>(metric.templateParams()) : Map.of(),
            executionMode.name(),
            metric.labelTopic(),
            metric.labelFields() != null ? List.copyOf(metric.labelFields()) : List.of()
        );
    }

    private void validateMetric(MetricConfig metric) {
        validateMetric(metric, false);
    }

    private void validateMetric(MetricConfig metric, boolean requireName) {
        if (requireName && (metric.name() == null || metric.name().isBlank())) {
            throw new IllegalArgumentException("Metric name is required");
        }
        if (metric.labelFields() != null && !metric.labelFields().isEmpty()
            && (metric.labelTopic() == null || metric.labelTopic().isBlank())) {
            throw new IllegalArgumentException("A Kafka topic is required when label fields are configured");
        }

        MetricTemplateType templateType = MetricTemplateType.fromValue(metric.templateType());
        String metricType = metric.type() == null ? "GAUGE" : metric.type().toUpperCase(Locale.ROOT);
        Map<String, Object> params = metric.templateParams() != null ? metric.templateParams() : Map.of();
        MetricExecutionMode executionMode = MetricExecutionMode.valueOf(metric.executionMode().toUpperCase(Locale.ROOT));

        if (executionMode == MetricExecutionMode.FLINK_MANAGED_JOB && templateType == MetricTemplateType.RAW_SQL) {
            throw new IllegalArgumentException("FLINK_MANAGED_JOB is only available for template metrics");
        }

        switch (templateType) {
            case RAW_SQL -> {
                if (metric.sql() == null || metric.sql().isBlank()) {
                    throw new IllegalArgumentException("SQL is required for RAW_SQL metrics");
                }
            }
            case TOPIC_COUNT_DELTA -> {
                requireParam(params, "leftSql");
                requireParam(params, "rightSql");
                if (!"GAUGE".equals(metricType)) {
                    throw new IllegalArgumentException("TOPIC_COUNT_DELTA supports GAUGE metrics only");
                }
            }
            case TOPIC_TRANSIT_LATENCY -> {
                requireParam(params, "sourceSql");
                requireParam(params, "targetSql");
                if (!Set.of("GAUGE", "HISTOGRAM", "SUMMARY").contains(metricType)) {
                    throw new IllegalArgumentException("TOPIC_TRANSIT_LATENCY supports GAUGE, HISTOGRAM or SUMMARY");
                }
            }
        }
    }

    private void executeCreateTableIfPresent(MetricConfig config) {
        if (config.createTableSql() == null || config.createTableSql().isBlank()) return;

        String ddl = config.createTableSql().trim();
        if (!ddl.toUpperCase(Locale.ROOT).contains("IF NOT EXISTS")) {
            ddl = ddl.replaceFirst("(?i)(CREATE\\s+TABLE\\s+)", "$1IF NOT EXISTS ");
        }
        try {
            flinkSqlService.executeSql(QueryRequest.ddl(ddl, 10_000L));
            log.debug("CREATE TABLE DDL executed for metric '{}'", config.name());
        } catch (Exception ddlEx) {
            log.debug("CREATE TABLE for metric '{}' skipped: {}", config.name(), ddlEx.getMessage());
        }
    }

    private MetricComputationResult computeMetric(MetricConfig config) {
        return switch (MetricTemplateType.fromValue(config.templateType())) {
            case RAW_SQL -> computeRawSqlMetric(config);
            case TOPIC_COUNT_DELTA -> computeCountDeltaMetric(config);
            case TOPIC_TRANSIT_LATENCY -> computeTransitLatencyMetric(config);
        };
    }

    private MetricComputationResult computeRawSqlMetric(MetricConfig config) {
        QueryResult result = executeMetricQuery(
            config.sql(),
            DEFAULT_TEMPLATE_MAX_ROWS,
            DEFAULT_TEMPLATE_TIMEOUT_MS,
            DEFAULT_TEMPLATE_READ_MODE
        );
        if (result.error() != null) return MetricComputationResult.error(result.error());
        if (result.rows().isEmpty()) {
            return MetricComputationResult.error("No rows returned — check table name and Kafka connectivity");
        }

        Double displayValue = extractPrimaryMetricValue(result.rows());
        return new MetricComputationResult(
            result.rows(),
            displayValue,
            null,
            Map.of("rowCount", result.rows().size())
        );
    }

    private MetricComputationResult computeCountDeltaMetric(MetricConfig config) {
        Map<String, Object> params = config.templateParams() != null ? config.templateParams() : Map.of();
        int maxRows = getIntParam(params, "maxRowsPerSide", DEFAULT_TEMPLATE_MAX_ROWS);
        long timeoutMs = getLongParam(params, "timeoutMs", DEFAULT_TEMPLATE_TIMEOUT_MS);
        String readMode = getStringParam(params, "readMode", DEFAULT_TEMPLATE_READ_MODE);

        QueryResult leftResult = executeMetricQuery(requireParam(params, "leftSql"), maxRows, timeoutMs, readMode);
        if (leftResult.error() != null) return MetricComputationResult.error(leftResult.error());
        QueryResult rightResult = executeMetricQuery(requireParam(params, "rightSql"), maxRows, timeoutMs, readMode);
        if (rightResult.error() != null) return MetricComputationResult.error(rightResult.error());

        Double leftValue = extractPrimaryMetricValue(leftResult.rows());
        Double rightValue = extractPrimaryMetricValue(rightResult.rows());
        if (leftValue == null || rightValue == null) {
            return MetricComputationResult.error("Both queries must return a numeric metric_value");
        }

        String operation = getStringParam(params, "operation", "LEFT_MINUS_RIGHT").toUpperCase(Locale.ROOT);
        Double metricValue = switch (operation) {
            case "LEFT_MINUS_RIGHT" -> leftValue - rightValue;
            case "ABS_DIFF" -> Math.abs(leftValue - rightValue);
            case "RATIO" -> rightValue == 0.0 ? null : leftValue / rightValue;
            case "PERCENT_GAP" -> rightValue == 0.0 ? null : ((leftValue - rightValue) * 100.0) / rightValue;
            default -> throw new IllegalArgumentException("Unsupported count delta operation: " + operation);
        };
        if (metricValue == null) {
            return MetricComputationResult.error("Cannot compute " + operation + " when right metric value is zero");
        }

        Map<String, Object> row = new LinkedHashMap<>();
        row.put("metric_value", metricValue);
        row.put("left_value", leftValue);
        row.put("right_value", rightValue);
        row.put("operation", operation);
        addIfPresent(row, "left_topic", params.get("leftTopic"));
        addIfPresent(row, "right_topic", params.get("rightTopic"));

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("leftValue", leftValue);
        summary.put("rightValue", rightValue);
        summary.put("operation", operation);

        return new MetricComputationResult(List.of(row), metricValue, null, summary);
    }

    private MetricComputationResult computeTransitLatencyMetric(MetricConfig config) {
        Map<String, Object> params = config.templateParams() != null ? config.templateParams() : Map.of();
        int maxRows = getIntParam(params, "maxRowsPerSide", DEFAULT_TEMPLATE_MAX_ROWS);
        long timeoutMs = getLongParam(params, "timeoutMs", DEFAULT_TEMPLATE_TIMEOUT_MS);
        String readMode = getStringParam(params, "readMode", DEFAULT_TEMPLATE_READ_MODE);

        QueryResult sourceResult = executeMetricQuery(requireParam(params, "sourceSql"), maxRows, timeoutMs, readMode);
        if (sourceResult.error() != null) return MetricComputationResult.error(sourceResult.error());
        QueryResult targetResult = executeMetricQuery(requireParam(params, "targetSql"), maxRows, timeoutMs, readMode);
        if (targetResult.error() != null) return MetricComputationResult.error(targetResult.error());

        List<CorrelationEvent> sourceEvents = extractCorrelationEvents(sourceResult.rows(), "sourceSql");
        List<CorrelationEvent> targetEvents = extractCorrelationEvents(targetResult.rows(), "targetSql");
        if (sourceEvents.isEmpty() || targetEvents.isEmpty()) {
            return MetricComputationResult.error("Transit latency requires match_key and event_time rows on both queries");
        }

        Map<String, Deque<Long>> targetsByKey = new HashMap<>();
        for (CorrelationEvent event : targetEvents) {
            targetsByKey
                .computeIfAbsent(event.matchKey(), ignored -> new ArrayDeque<>())
                .addLast(event.eventTime());
        }

        List<Double> latencies = new ArrayList<>();
        int unmatchedSourceCount = 0;
        sourceEvents.sort(Comparator.comparing(CorrelationEvent::matchKey).thenComparingLong(CorrelationEvent::eventTime));
        targetsByKey.values().forEach(queue -> {
            List<Long> sorted = new ArrayList<>(queue);
            sorted.sort(Long::compareTo);
            queue.clear();
            queue.addAll(sorted);
        });

        for (CorrelationEvent sourceEvent : sourceEvents) {
            Deque<Long> candidates = targetsByKey.get(sourceEvent.matchKey());
            if (candidates == null || candidates.isEmpty()) {
                unmatchedSourceCount++;
                continue;
            }
            while (!candidates.isEmpty() && candidates.peekFirst() < sourceEvent.eventTime()) {
                candidates.removeFirst();
            }
            if (candidates.isEmpty()) {
                unmatchedSourceCount++;
                continue;
            }
            long targetTs = candidates.removeFirst();
            latencies.add((double) (targetTs - sourceEvent.eventTime()));
        }

        if (latencies.isEmpty()) {
            return MetricComputationResult.error("No correlated messages found between source and target queries");
        }

        double avgLatencyMs = latencies.stream().mapToDouble(Double::doubleValue).average().orElse(0.0);
        Map<String, Object> sharedLabels = new LinkedHashMap<>();
        addIfPresent(sharedLabels, "source_topic", params.get("sourceTopic"));
        addIfPresent(sharedLabels, "target_topic", params.get("targetTopic"));

        List<Map<String, Object>> rows = new ArrayList<>();
        if ("GAUGE".equalsIgnoreCase(config.type())) {
            Map<String, Object> row = new LinkedHashMap<>(sharedLabels);
            row.put("metric_value", avgLatencyMs);
            rows.add(row);
        } else {
            for (Double latency : latencies) {
                Map<String, Object> row = new LinkedHashMap<>(sharedLabels);
                row.put("metric_value", latency);
                rows.add(row);
            }
        }

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("matchedCount", latencies.size());
        summary.put("unmatchedSourceCount", unmatchedSourceCount);
        summary.put("avgLatencyMs", avgLatencyMs);
        summary.put("p95LatencyMs", percentile(latencies, 0.95));
        summary.put("maxLatencyMs", latencies.stream().mapToDouble(Double::doubleValue).max().orElse(avgLatencyMs));

        return new MetricComputationResult(rows, avgLatencyMs, null, summary);
    }

    private QueryResult executeMetricQuery(String sql, int maxRows, long timeoutMs, String readMode) {
        return flinkSqlService.executeSql(QueryRequest.sql(injectBoundedHint(sql), maxRows, timeoutMs, readMode));
    }

    private List<CorrelationEvent> extractCorrelationEvents(List<Map<String, Object>> rows, String queryName) {
        List<CorrelationEvent> events = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            Object key = findValueIgnoreCase(row, "match_key");
            Object eventTime = findValueIgnoreCase(row, "event_time");
            if (key == null || eventTime == null) continue;

            Long epochMs = toEpochMillis(eventTime);
            if (epochMs == null) {
                throw new IllegalArgumentException(
                    "Column event_time from " + queryName + " must be ISO-8601 or epoch-based"
                );
            }
            events.add(new CorrelationEvent(String.valueOf(key), epochMs));
        }
        return events;
    }

    private Object findValueIgnoreCase(Map<String, Object> row, String key) {
        for (Map.Entry<String, Object> entry : row.entrySet()) {
            if (key.equalsIgnoreCase(entry.getKey())) return entry.getValue();
        }
        return null;
    }

    private Long toEpochMillis(Object value) {
        if (value instanceof Number n) {
            long timestamp = n.longValue();
            return timestamp < 10_000_000_000L ? timestamp * 1000L : timestamp;
        }
        if (value instanceof String s) {
            String text = s.trim();
            if (text.isEmpty()) return null;
            try {
                long timestamp = Long.parseLong(text);
                return timestamp < 10_000_000_000L ? timestamp * 1000L : timestamp;
            } catch (NumberFormatException ignored) {
                try {
                    return Instant.parse(text).toEpochMilli();
                } catch (Exception ignoredIso) {
                    try {
                        return java.time.LocalDateTime.parse(text.replace(' ', 'T'))
                            .toInstant(java.time.ZoneOffset.UTC)
                            .toEpochMilli();
                    } catch (Exception ignoredLocal) {
                        return null;
                    }
                }
            }
        }
        return null;
    }

    private double percentile(List<Double> values, double quantile) {
        List<Double> sorted = new ArrayList<>(values);
        sorted.sort(Double::compareTo);
        int index = (int) Math.ceil(quantile * sorted.size()) - 1;
        index = Math.max(0, Math.min(index, sorted.size() - 1));
        return sorted.get(index);
    }

    private Double extractPrimaryMetricValue(List<Map<String, Object>> rows) {
        for (Map<String, Object> row : rows) {
            Double value = extractValue(row);
            if (value != null) return value;
        }
        return null;
    }

    private String requireParam(Map<String, Object> params, String key) {
        Object value = params.get(key);
        if (value == null || String.valueOf(value).isBlank()) {
            throw new IllegalArgumentException("Missing required template parameter: " + key);
        }
        return String.valueOf(value);
    }

    private String getStringParam(Map<String, Object> params, String key, String defaultValue) {
        Object value = params.get(key);
        return value == null || String.valueOf(value).isBlank() ? defaultValue : String.valueOf(value);
    }

    private int getIntParam(Map<String, Object> params, String key, int defaultValue) {
        Object value = params.get(key);
        if (value == null || String.valueOf(value).isBlank()) return defaultValue;
        return Integer.parseInt(String.valueOf(value));
    }

    private long getLongParam(Map<String, Object> params, String key, long defaultValue) {
        Object value = params.get(key);
        if (value == null || String.valueOf(value).isBlank()) return defaultValue;
        return Long.parseLong(String.valueOf(value));
    }

    private void addIfPresent(Map<String, Object> target, String key, Object value) {
        if (value != null && !String.valueOf(value).isBlank()) {
            target.put(key, value);
        }
    }

    @Scheduled(fixedRateString = "${explorer.metrics-refresh-rate:30000}")
    public void refreshMetrics() {
        metrics.forEach((id, config) -> {
            try {
                MetricConfig normalized = normalizeMetric(config);
                validateMetric(normalized);

                if (MetricExecutionMode.FLINK_MANAGED_JOB.name().equals(normalized.executionMode())) {
                    updateMetricState(id, null, null, buildManagedJobPlannedSummary(normalized));
                    return;
                }

                executeCreateTableIfPresent(normalized);

                MetricComputationResult result = computeMetric(normalized);
                if (result.error() != null) {
                    updateMetricState(id, null, result.error(), result.summary());
                } else if (!result.rows().isEmpty()) {
                    Map<String, String> configuredLabels = resolveConfiguredLabels(normalized);
                    processRows(id, normalized, result.rows(), result.displayValue(), result.summary(), configuredLabels);
                } else {
                    updateMetricState(id, null, "No rows returned — check table name and Kafka connectivity", result.summary());
                }
            } catch (Exception e) {
                String msg = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
                // Flink ValidationException wraps the root cause as "SQL validation failed. ..."
                // Provide a cleaner hint when the table simply doesn't exist yet.
                if (msg.contains("not found") || msg.contains("SQL validation failed")) {
                    msg = msg + " → Check that the table is registered in Flink (run CREATE TABLE in the Query Workbench first)";
                }
                updateMetricState(id, null, msg, Map.of());
            }
        });
    }

    private Map<String, Object> buildManagedJobPlannedSummary(MetricConfig metric) {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("managedJobStatus", "PLANNED");
        summary.put("requestedExecutionMode", MetricExecutionMode.FLINK_MANAGED_JOB.name());
        summary.put("currentExecutionFallback", MetricExecutionMode.TEMPLATE_BOUNDED_SCAN.name());
        summary.put("managedJobNote", "Waiting for Flink job orchestration support before continuous execution can start.");
        summary.put("templateType", metric.templateType());
        return summary;
    }

    // ── Core processing — dispatches by Prometheus type ───────────────────────

    private void processRows(String metricId, MetricConfig config, List<Map<String, Object>> rows,
                             Double displayValueOverride, Map<String, Object> summary,
                             Map<String, String> configuredLabels) {
        String type = config.type() == null ? "GAUGE" : config.type().toUpperCase();
        Double primaryValue = displayValueOverride;

        for (Map<String, Object> row : rows) {
            Double value   = extractValue(row);
            if (value == null) continue;

            List<Tag> tags = buildTags(metricId, config, row, configuredLabels);
            String labelKey = buildLabelKey(row, configuredLabels);

            switch (type) {
                case "COUNTER"   -> processCounter(metricId, labelKey, tags, value);
                case "HISTOGRAM" -> processHistogram(metricId, labelKey, tags, value);
                case "SUMMARY"   -> processSummary(metricId, labelKey, tags, value);
                default          -> processGauge(metricId, labelKey, tags, value);  // GAUGE
            }
            if (primaryValue == null) primaryValue = value;
        }

        if (primaryValue != null) {
            updateHistory(metricId, primaryValue);
            updateMetricState(metricId, primaryValue, null, summary);
        }
    }

    // GAUGE → Gauge (current point-in-time value)
    private void processGauge(String metricId, String labelKey, List<Tag> tags, double value) {
        gaugeHolders
            .computeIfAbsent(metricId, k -> new ConcurrentHashMap<>())
            .computeIfAbsent(labelKey, k -> {
                AtomicReference<Double> ref = new AtomicReference<>(0.0);
                Gauge.builder("explorer_metric_gauge", ref, AtomicReference::get)
                    .description("Flink SQL gauge metric")
                    .tags(tags)
                    .register(meterRegistry);
                return ref;
            })
            .set(value);
    }

    // COUNTER → Counter (delta-based; SQL returns cumulative total)
    private void processCounter(String metricId, String labelKey, List<Tag> tags, double value) {
        Map<String, Counter> counters = counterMeters
            .computeIfAbsent(metricId, k -> new ConcurrentHashMap<>());
        Map<String, Double> lastVals = lastCounterValues
            .computeIfAbsent(metricId, k -> new ConcurrentHashMap<>());

        Counter counter = counters.computeIfAbsent(labelKey, k ->
            Counter.builder("explorer_metric_counter")
                .description("Flink SQL counter metric (cumulative total)")
                .tags(tags)
                .register(meterRegistry));

        double prev  = lastVals.getOrDefault(labelKey, 0.0);
        double delta = value - prev;
        if (delta > 0) counter.increment(delta);
        lastVals.put(labelKey, value);
    }

    // HISTOGRAM → DistributionSummary with Prometheus-native bucket histogram
    private void processHistogram(String metricId, String labelKey, List<Tag> tags, double value) {
        distributionMeters
            .computeIfAbsent(metricId, k -> new ConcurrentHashMap<>())
            .computeIfAbsent(labelKey, k ->
                DistributionSummary.builder("explorer_metric_histogram")
                    .description("Flink SQL histogram metric (auto-bucketed)")
                    .tags(tags)
                    .publishPercentileHistogram()   // → Prometheus-native _bucket/_count/_sum
                    .minimumExpectedValue(1.0)
                    .maximumExpectedValue(1_000_000.0)
                    .register(meterRegistry))
            .record(value);
    }

    // SUMMARY → DistributionSummary with client-side quantiles (P50/P75/P90/P95/P99)
    private void processSummary(String metricId, String labelKey, List<Tag> tags, double value) {
        distributionMeters
            .computeIfAbsent(metricId, k -> new ConcurrentHashMap<>())
            .computeIfAbsent(labelKey, k ->
                DistributionSummary.builder("explorer_metric_summary")
                    .description("Flink SQL summary metric (P50/P75/P90/P95/P99)")
                    .tags(tags)
                    .publishPercentiles(0.5, 0.75, 0.9, 0.95, 0.99)  // → _summary{quantile=…}
                    .register(meterRegistry))
            .record(value);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private Double extractValue(Map<String, Object> row) {
        for (Map.Entry<String, Object> e : row.entrySet()) {
            if ("metric_value".equalsIgnoreCase(e.getKey()) && e.getValue() instanceof Number n) {
                return n.doubleValue();
            }
        }
        return null;
    }

    private List<Tag> buildTags(String metricId, MetricConfig config, Map<String, Object> row,
                                Map<String, String> configuredLabels) {
        Map<String, String> tagValues = new LinkedHashMap<>();
        tagValues.put("metric_id", metricId);
        tagValues.put("metric_name", config.name());
        tagValues.put("metric_type", config.type());
        configuredLabels.forEach(tagValues::putIfAbsent);
        for (Map.Entry<String, Object> e : row.entrySet()) {
            if (!"metric_value".equalsIgnoreCase(e.getKey())) {
                tagValues.put(messageFieldExtractorService.sanitizeLabelKey(e.getKey()), String.valueOf(e.getValue()));
            }
        }
        return tagValues.entrySet().stream()
            .map(entry -> Tag.of(entry.getKey(), entry.getValue()))
            .toList();
    }

    private String buildLabelKey(Map<String, Object> row, Map<String, String> configuredLabels) {
        StringBuilder sb = new StringBuilder();
        configuredLabels.forEach((key, value) -> sb.append(key).append('=').append(value).append('|'));
        for (Map.Entry<String, Object> e : row.entrySet()) {
            if (!"metric_value".equalsIgnoreCase(e.getKey())) {
                sb.append(messageFieldExtractorService.sanitizeLabelKey(e.getKey()))
                    .append('=').append(e.getValue()).append('|');
            }
        }
        return sb.toString();
    }

    private Map<String, String> resolveConfiguredLabels(MetricConfig config) {
        if (config.labelTopic() == null || config.labelTopic().isBlank()
            || config.labelFields() == null || config.labelFields().isEmpty()) {
            return Map.of();
        }

        try {
            Optional<KafkaMessage> latestMessage = kafkaAdminService.getLatestMessage(config.labelTopic());
            if (latestMessage.isEmpty() || latestMessage.get().value() == null || latestMessage.get().value().isBlank()) {
                return Map.of();
            }

            Map<String, String> extractedFields = messageFieldExtractorService.extractLeafFields(latestMessage.get().value());
            if (extractedFields.isEmpty()) {
                return Map.of();
            }

            Map<String, String> labels = new LinkedHashMap<>();
            for (String fieldPath : config.labelFields()) {
                String value = extractedFields.get(fieldPath);
                if (value == null) continue;

                String baseKey = messageFieldExtractorService.sanitizeLabelKey(fieldPath);
                String candidateKey = baseKey;
                int suffix = 2;
                while (labels.containsKey(candidateKey) && !Objects.equals(labels.get(candidateKey), value)) {
                    candidateKey = baseKey + "_" + suffix++;
                }
                labels.put(candidateKey, value);
            }
            return labels;
        } catch (Exception e) {
            log.debug("Failed to resolve configured labels for metric '{}': {}", config.name(), e.getMessage());
            return Map.of();
        }
    }

    private void updateHistory(String id, Double value) {
        LinkedList<Double> history = historyMap.computeIfAbsent(id, k -> new LinkedList<>());
        history.addLast(value);
        if (history.size() > MAX_HISTORY) history.removeFirst();
    }

    private void updateMetricState(String id, Double value, String error, Map<String, Object> summary) {
        MetricConfig current = metrics.get(id);
        if (current == null) return;
        metrics.put(id, new MetricConfig(
            current.id(), current.name(), current.type(), current.sql(), current.description(),
            current.warningThreshold(), current.criticalThreshold(),
            value != null ? value : current.lastValue(),
            System.currentTimeMillis(), error,
            new ArrayList<>(historyMap.getOrDefault(id, new LinkedList<>())),
            summary != null && !summary.isEmpty() ? new LinkedHashMap<>(summary) : current.lastSummary(),
            current.createTableSql(),
            current.templateType(),
            current.templateParams(),
            current.executionMode(),
            current.labelTopic(),
            current.labelFields() != null ? List.copyOf(current.labelFields()) : List.of()));
    }

    // ── Kafka persistence ─────────────────────────────────────────────────────

    private void persistToKafka(MetricConfig metric) {
        Properties props = new Properties();
        props.putAll(kafkaConfig.getKafkaProperties());
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG,   StringSerializer.class.getName());
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        try (KafkaProducer<String, String> producer = new KafkaProducer<>(props)) {
            producer.send(new ProducerRecord<>(
                explorerConfig.getMetricsConfigTopic(),
                metric.id(),
                objectMapper.writeValueAsString(metric))).get();
        } catch (Exception e) {
            log.warn("Failed to persist metric config: {}", e.getMessage());
        }
    }

    private void restoreFromKafka() {
        Properties props = new Properties();
        props.putAll(kafkaConfig.getKafkaProperties());
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG,   StringDeserializer.class.getName());
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        props.put(ConsumerConfig.GROUP_ID_CONFIG,         "explorer-metrics-restorer-" + UUID.randomUUID());
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        // Fail fast when the broker is unreachable — this runs during application startup.
        props.put(ConsumerConfig.DEFAULT_API_TIMEOUT_MS_CONFIG, "5000");
        props.put(ConsumerConfig.REQUEST_TIMEOUT_MS_CONFIG, "4000");
        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(props)) {
            List<PartitionInfo> partitionInfos = consumer.partitionsFor(explorerConfig.getMetricsConfigTopic());
            if (partitionInfos == null || partitionInfos.isEmpty()) {
                return; // topic does not exist yet — nothing to restore
            }
            List<TopicPartition> partitions = partitionInfos.stream()
                .map(pi -> new TopicPartition(pi.topic(), pi.partition()))
                .toList();
            consumer.assign(partitions);
            consumer.seekToBeginning(partitions);
            Map<TopicPartition, Long> endOffsets = consumer.endOffsets(partitions);

            // Poll until every partition reaches its end offset. An empty poll does NOT mean
            // the topic is exhausted (the first poll after assignment is typically empty
            // while the fetch is in flight), so completion is tracked by position instead.
            long deadline = System.currentTimeMillis() + 10_000;
            while (System.currentTimeMillis() < deadline && !reachedEndOffsets(consumer, endOffsets)) {
                ConsumerRecords<String, String> records = consumer.poll(Duration.ofMillis(300));
                for (ConsumerRecord<String, String> record : records) {
                    try {
                        if (record.value() == null) {
                            metrics.remove(record.key());
                            continue;
                        }
                        MetricConfig cfg = objectMapper.readValue(record.value(), MetricConfig.class);
                        if ("DELETED".equals(cfg.errorMessage())) metrics.remove(record.key());
                        else metrics.put(record.key(), cfg);
                    } catch (Exception e) {
                        // One corrupt record must not abort the whole restore
                        log.warn("Skipping unreadable metric config at {}:{}: {}",
                            record.partition(), record.offset(), e.getMessage());
                    }
                }
            }
            log.info("Restored {} metric configuration(s) from Kafka", metrics.size());
        } catch (Exception e) {
            log.debug("Restore from Kafka failed: {}", e.getMessage());
        }
    }

    private boolean reachedEndOffsets(KafkaConsumer<String, String> consumer,
                                      Map<TopicPartition, Long> endOffsets) {
        for (Map.Entry<TopicPartition, Long> entry : endOffsets.entrySet()) {
            if (consumer.position(entry.getKey()) < entry.getValue()) {
                return false;
            }
        }
        return true;
    }
}
