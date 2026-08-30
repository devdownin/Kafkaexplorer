// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors
package com.compagnonsdudev.kafkasqlexplorer.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.compagnonsdudev.kafkasqlexplorer.config.ExplorerConfig;
import com.compagnonsdudev.kafkasqlexplorer.config.KafkaConfig;
import com.compagnonsdudev.kafkasqlexplorer.domain.KafkaMessage;
import com.compagnonsdudev.kafkasqlexplorer.domain.MetricConfig;
import com.compagnonsdudev.kafkasqlexplorer.domain.MetricExecutionMode;
import com.compagnonsdudev.kafkasqlexplorer.domain.MetricPreviewResult;
import com.compagnonsdudev.kafkasqlexplorer.domain.MetricTemplateDescriptor;
import com.compagnonsdudev.kafkasqlexplorer.domain.MetricTemplateType;
import com.compagnonsdudev.kafkasqlexplorer.domain.QueryRequest;
import com.compagnonsdudev.kafkasqlexplorer.domain.QueryResult;
import com.compagnonsdudev.kafkasqlexplorer.domain.TopicTimeLag;
import com.compagnonsdudev.kafkasqlexplorer.util.LogSafe;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tag;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.PartitionInfo;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
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
    /**
     * The read mode a latency template takes unless it says otherwise, and it is deliberately not
     * the one every other template takes.
     *
     * <p>A transit latency answers "how long is this hop taking", which is a question about now.
     * Read from the earliest offset it answered a different one: the average over the oldest
     * records the row cap allowed, recomputed every thirty seconds and therefore never moving on
     * a topic older than that cap — a pipeline that degrades today changed nothing on the chart.
     * The audit's duplicate scan was turned round for the same reason and states the same rule
     * ({@code explorer.audit-duplicate-scan-from}); {@code earliest-offset} restores the old
     * behaviour for a metric that really is asking about the beginning of a topic.
     */
    private static final String DEFAULT_LATENCY_READ_MODE = "latest-offset";
    private static final Set<String> READ_MODES = Set.of("earliest-offset", "latest-offset");
    /**
     * What a count delta may do with its two numbers.
     *
     * <p>Named once and read twice — by the save-time validation and by the message the compute
     * switch throws on an unrecognised value — because the two drifting apart is how a metric
     * comes to be accepted by the API and then to fail on every refresh for ever.
     */
    private static final Set<String> DELTA_OPERATIONS =
        Set.of("LEFT_MINUS_RIGHT", "ABS_DIFF", "RATIO", "PERCENT_GAP");

    /**
     * Where a count delta gets its two numbers.
     *
     * <p>{@code RECORDS} runs the two queries and counts the rows that come back — the original
     * behaviour, and the only one that can honour a {@code WHERE}. {@code OFFSETS} asks the log
     * instead: {@code endOffsets − beginningOffsets} summed over the partitions, which
     * {@link KafkaAdminService#getTopicsSize} already answers <em>for both topics in one call</em>.
     * That is not a faster version of the same measurement, it is a cheaper and a <em>larger</em>
     * one: no record is read or parsed, the direct reader's 100 000-record ceiling does not apply,
     * so a topic of any size is countable, and the two sides come out of the same pair of
     * {@code listOffsets} responses — which removes the interval D4 is about rather than leaning
     * it in the safe direction.
     *
     * <p>What it counts is <b>offsets produced, not records present</b>: a transaction marker takes
     * one, and a record later compacted away still counts, because it was produced. That is the
     * right answer to "how many did this stage emit", which is what a silent-drop alarm between
     * two stages asks, and the wrong one to "how many are in there now" on a compacted topic.
     * {@code getTopicActivity} draws the same distinction for the dashboard's sparkline.
     *
     * <p>{@code AUTO} takes offsets when the metric names both topics and neither query is
     * anything but a plain whole-topic count — the case where the two measurements answer the same
     * question — and records otherwise. Whichever it picked is in the summary; it is never silent.
     */
    private static final Set<String> COUNT_MODES = Set.of("AUTO", "OFFSETS", "RECORDS");

    /**
     * What the two numbers are compared over.
     *
     * <p>{@code TOTAL} compares the counts themselves, which is what this template always did and
     * what makes it lose its sensitivity as history accumulates: on two topics running for months,
     * a total outage that started an hour ago is a fraction of a percent of the lifetime totals,
     * under every threshold anyone would set. {@code SINCE_LAST_REFRESH} compares what each side
     * produced since the previous cycle, which is a quantity a threshold can fire on. Its first
     * refresh publishes nothing and says why: there is no previous cycle to subtract.
     */
    private static final Set<String> COUNT_WINDOWS = Set.of("TOTAL", "SINCE_LAST_REFRESH");

    /** A plain whole-topic count: the shape offsets answer exactly. */
    private static final Pattern WHOLE_TOPIC_COUNT = Pattern.compile(
        "(?is)^\\s*SELECT\\s+COUNT\\s*\\(\\s*\\*\\s*\\)\\s*(?:AS\\s+`?\\w+`?\\s*)?"
            + "FROM\\s+`?\\w[\\w.]*`?\\s*;?\\s*$");

    /** metricId → the counts the previous refresh saw, for a windowed comparison. */
    private final Map<String, double[]> countDeltaBaselines = new ConcurrentHashMap<>();
    /** A window shorter than a second is not a window; one longer than a week is not a metric. */
    private static final long MIN_LATENCY_WINDOW_MS = 1_000L;
    private static final long MAX_LATENCY_WINDOW_MS = 7L * 24 * 3_600_000L;
    private static final long MIN_REFRESH_INTERVAL_MS = 1_000L;
    private static final long MAX_REFRESH_INTERVAL_MS = 24L * 3_600_000L;
    private static final int MAX_TEMPLATE_MAX_ROWS = 1_000_000;
    private static final long MAX_TEMPLATE_TIMEOUT_MS = 600_000L;

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
        ),
        new MetricTemplateDescriptor(
            MetricTemplateType.CONSUMER_TIME_LAG.name(),
            "Consumer Lag in Time",
            "How far behind a consumer group is in time, not in records: the age of the oldest "
                + "message still waiting. No SQL — committed offsets and record timestamps.",
            List.of("GAUGE"),
            List.of("topic", "group")
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

    /** One matched pair: the latency, and when the source event happened. */
    private record LatencyObservation(long sourceEventTime, double latencyMs) {}

    /**
     * A row column that is a measurement about the row rather than a label of it.
     *
     * <p>Every non-{@code metric_value} column becomes a Prometheus label, which is right for a
     * GROUP BY value and catastrophic for a timestamp — one series per observation. This one is
     * excluded from the tags and from the label key, exactly as {@code metric_value} is, and read
     * only by the distribution dedup below.
     */
    private static final String OBSERVED_AT_COLUMN = "__observed_at";

    /**
     * Columns that are never labels: the value itself, and anything marked with the {@code __}
     * prefix.
     *
     * <p>The prefix is a rule rather than a list because the alternative was found the hard way.
     * Every other column becomes a Prometheus label, which is right for a GROUP BY value and
     * ruinous for a number that moves: a count delta put {@code left_value} and {@code right_value}
     * in its row, so on any live topic the label set changed at <em>every refresh</em> and each
     * series carried exactly one data point. The metric could not be graphed or alerted on at all
     * — the one thing it exists for — and nothing said so, because the registry stayed small:
     * {@code pruneStaleSeries} deregistered the previous series each cycle, which is the tidy
     * version of the same defect.
     */
    private static boolean isReservedColumn(String column) {
        return "metric_value".equalsIgnoreCase(column) || (column != null && column.startsWith("__"));
    }

    /** Companion series: one gauge per (metric, name), carrying no row labels. */
    private static final String LAST_SUCCESS_SERIES = "explorer_metric_last_success_timestamp_seconds";
    private static final String MATCH_RATE_SERIES = "explorer_metric_correlation_match_rate";
    /**
     * The p95 of a correlated latency, for the metric types that carry no quantiles of their own.
     *
     * <p>A {@code SUMMARY} already publishes {@code explorer_metric_summary{quantile="0.95"}} and a
     * {@code HISTOGRAM} its buckets, so publishing this beside them would be two answers to one
     * question — the shape this codebase keeps removing. What lacks one is a {@code GAUGE}, where
     * only the average comes out and the p95 was computed, put in the summary and alerted on by
     * nobody. A latency alert is set on the tail: an average holds still while the worst decile
     * doubles, which is the case the whole template exists to catch.
     */
    private static final String LATENCY_P95_SERIES = "explorer_metric_correlation_latency_p95_ms";
    /** How long the last full refresh cycle took, which is what makes its cost visible at all. */
    private static final String REFRESH_DURATION_SERIES = "explorer_metrics_refresh_duration_seconds";
    private static final List<String> COMPANION_SERIES =
        List.of(LAST_SUCCESS_SERIES, MATCH_RATE_SERIES, LATENCY_P95_SERIES);

    // ── metric state ─────────────────────────────────────────────────────────
    private final Map<String, MetricConfig>              metrics           = new ConcurrentHashMap<>();
    private final Map<String, LinkedList<Double>>        historyMap        = new ConcurrentHashMap<>();
    /** metricId → (series name → rolling values), the components of the value in {@link #historyMap}. */
    private final Map<String, Map<String, LinkedList<Double>>> componentHistoryMap = new ConcurrentHashMap<>();
    /** metricId → (series name → holder), for the gauges that describe the metric rather than its rows. */
    private final Map<String, Map<String, AtomicReference<Double>>> companionHolders = new ConcurrentHashMap<>();
    private final Map<String, Map<String, Gauge>>                   companionMeters  = new ConcurrentHashMap<>();
    /** metricId → (labelKey → newest observation already recorded), for a sliding-window distribution. */
    private final Map<String, Map<String, Long>> distributionWatermarks = new ConcurrentHashMap<>();

    // ── Micrometer instruments per type ──────────────────────────────────────
    /** GAUGE:     metricId → (labelKey → holder)  */
    private final Map<String, Map<String, AtomicReference<Double>>> gaugeHolders = new ConcurrentHashMap<>();
    /** GAUGE:     metricId → (labelKey → registered Gauge) — kept so a stale series can be removed. */
    private final Map<String, Map<String, Gauge>>                   gaugeMeters   = new ConcurrentHashMap<>();
    /** COUNTER:   metricId → (labelKey → Counter) */
    private final Map<String, Map<String, Counter>>                  counterMeters = new ConcurrentHashMap<>();
    /** COUNTER:   metricId → (labelKey → lastSeenValue) for delta computation */
    private final Map<String, Map<String, Double>>                   lastCounterValues = new ConcurrentHashMap<>();
    /** HISTOGRAM / SUMMARY: metricId → (labelKey → DistributionSummary) */
    private final Map<String, Map<String, DistributionSummary>>      distributionMeters = new ConcurrentHashMap<>();
    /**
     * HISTOGRAM / SUMMARY: metricId → (labelKey → count of observations already recorded).
     * The scheduled refresh re-scans the full bounded (earliest-offset) backlog every cycle;
     * without this watermark the accumulating DistributionSummary would re-record every past
     * observation each cycle, inflating _count/_sum and biasing the distribution toward older
     * data. Only the suffix beyond the recorded count is recorded on subsequent cycles (B2).
     */
    private final Map<String, Map<String, Integer>>                  distributionRecordedCounts = new ConcurrentHashMap<>();

    private final FlinkSqlService flinkSqlService;
    private final MeterRegistry   meterRegistry;
    private final KafkaConfig     kafkaConfig;
    private final ExplorerConfig  explorerConfig;
    private final KafkaAdminService kafkaAdminService;
    private final MessageFieldExtractorService messageFieldExtractorService;
    private final StartupRestore startupRestore;
    private final ObjectMapper    objectMapper = new ObjectMapper();

    /** Shared producer for config persistence — creating a KafkaProducer per save is expensive. */
    private volatile Producer<String, String> configProducer;

    /**
     * Per-refresh-cycle memoization of metric queries: the seeded metrics all share the same
     * COUNT(*) SQL, so without this each 30s cycle re-scans the same topic once per metric.
     * Set only for the duration of {@link #refreshMetrics()} (single scheduler thread).
     */
    private final ThreadLocal<Map<String, QueryResult>> refreshCycleQueryCache = new ThreadLocal<>();

    /**
     * Per-refresh-cycle cache of a topic's latest-message leaf fields. Resolving configured labels
     * calls the expensive {@link KafkaAdminService#getLatestMessage} (spins up a throwaway consumer)
     * once per labelled metric; without this, several metrics labelling off the same topic each
     * pay that cost every cycle. Set only for the duration of a refresh (single scheduler thread).
     */
    private final ThreadLocal<Map<String, Map<String, String>>> refreshCycleLabelCache = new ThreadLocal<>();

    /** Serializes scheduled and on-demand refreshes so meter state is never mutated concurrently. */
    private final java.util.concurrent.locks.ReentrantLock refreshLock = new java.util.concurrent.locks.ReentrantLock();

    /**
     * The loop's own tick, read from the same property the schedule is built on.
     *
     * <p>The field initializer is the value a hand-constructed instance keeps: {@code @Value} is
     * applied by the container, and every test here builds the service with {@code new}.
     */
    @Value("${explorer.metrics-refresh-rate:30000}")
    private long metricsRefreshRateMs = 30_000L;

    /** metricId → when its last refresh started, for the metrics that ask for a slower cadence. */
    private final Map<String, Long> lastRefreshStartedAt = new ConcurrentHashMap<>();

    /** Holder for {@link #REFRESH_DURATION_SERIES}, registered on the first cycle that completes. */
    private final AtomicReference<Double> refreshDurationHolder = new AtomicReference<>(0.0);
    private volatile boolean refreshDurationRegistered;
    /** Said once, not once per cycle: a warning repeated every tick is one people filter out. */
    private volatile boolean refreshOverrunReported;

    public MetricService(FlinkSqlService flinkSqlService, MeterRegistry meterRegistry,
                         KafkaConfig kafkaConfig, ExplorerConfig explorerConfig,
                         KafkaAdminService kafkaAdminService,
                         MessageFieldExtractorService messageFieldExtractorService,
                         StartupRestore startupRestore) {
        this.flinkSqlService = flinkSqlService;
        this.meterRegistry   = meterRegistry;
        this.kafkaConfig     = kafkaConfig;
        this.explorerConfig  = explorerConfig;
        this.kafkaAdminService = kafkaAdminService;
        this.messageFieldExtractorService = messageFieldExtractorService;
        this.startupRestore = startupRestore;
    }

    // ── Bootstrap ─────────────────────────────────────────────────────────────

    /**
     * Restores the configured metrics, then seeds examples <b>only if the store is known to be
     * empty</b>.
     *
     * <p>That distinction is the whole of this method. "No metric is configured" and "the metric
     * configurations could not be read" are two different answers, and seeding acts on the first:
     * it mints four metrics with fresh ids and writes them back to {@code internal.metrics.config},
     * beside an operator's own metrics that this process simply failed to read. Guarding on
     * {@code metrics.isEmpty()} alone made an unreachable broker at boot look exactly like a first
     * run. It has been harmless so far only by accident — Flink holds no table at boot, so
     * {@code seedDefaultMetrics} returns early — and an accident is not a guard.
     */
    @PostConstruct
    public void init() {
        boolean restored = restoreFromKafka();
        migrateStaleMetrics();
        if (!restored) {
            if (metrics.isEmpty()) {
                // Said only when it changes the outcome: a partial restore that did bring metrics
                // back would not have seeded anyway, and has already reported itself.
                log.warn("No example metric was seeded: the existing configuration could not be "
                    + "read, so an empty store here does not mean the cluster has none.");
            }
            return;
        }
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

            MetricComputationResult result = computeMetric(normalized, true);
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
        MetricConfig previous = metrics.get(id);
        // The UI only ever sees the DDL with credentials masked; if it sends that DDL back on an
        // edit, restore the real secrets from the stored version so they are not overwritten with
        // '******' (which would break Flink table registration).
        String effectiveDdl = previous != null
            ? DdlGeneratorService.restoreMaskedProperties(metric.createTableSql(), previous.createTableSql())
            : metric.createTableSql();
        MetricConfig m = normalizeMetric(new MetricConfig(
            id, metric.name(), metric.type(), metric.sql(), metric.description(),
            metric.warningThreshold(), metric.criticalThreshold(),
            metric.lastValue(), metric.lastUpdateTime(), metric.errorMessage(),
            metric.history() != null ? metric.history() : List.of(),
            metric.lastSummary() != null ? metric.lastSummary() : Map.of(),
            effectiveDdl,
            metric.templateType(),
            metric.templateParams(),
            metric.executionMode(),
            metric.labelTopic(),
            metric.labelFields() != null ? metric.labelFields() : List.of()));
        validateMetric(m, true);
        metrics.put(id, m);
        // When an existing metric's shape changes (type, SQL, labels, template, …),
        // any Micrometer series it already registered would linger forever with stale
        // tags/values — e.g. an old GAUGE series surviving a switch to COUNTER, or dead
        // label series after a SQL/label edit. Purge them so the next refresh rebuilds
        // cleanly. Value history is intentionally preserved across an edit.
        if (previous != null && metricShapeChanged(previous, m)) {
            purgeMeters(id);
        }
        persistToKafka(m);
    }

    public void delete(String id) {
        metrics.remove(id);
        historyMap.remove(id);
        componentHistoryMap.remove(id);
        purgeMeters(id);
        persistTombstone(id);
    }

    /**
     * Remove every Micrometer instrument and cached delta/holder state for a metric id.
     * Does NOT touch {@link #historyMap} or {@link #metrics} — callers decide those.
     */
    private void purgeMeters(String id) {
        gaugeHolders.remove(id);
        gaugeMeters.remove(id);
        lastCounterValues.remove(id);
        counterMeters.remove(id);
        distributionMeters.remove(id);
        distributionRecordedCounts.remove(id);
        distributionWatermarks.remove(id);
        companionHolders.remove(id);
        companionMeters.remove(id);
        // The interval-comparison baseline and the cadence bookkeeping are per-metric state of the
        // same kind. A metric deleted and recreated under one id, or one whose two queries were
        // edited, would otherwise subtract from a baseline measured for a different question — the
        // "count went backwards" refusal catches it, but as a lost refresh rather than as nothing.
        countDeltaBaselines.remove(id);
        lastRefreshStartedAt.remove(id);
        meterRegistry.find("explorer_metric_gauge").tag("metric_id", id).meters().forEach(meterRegistry::remove);
        meterRegistry.find("explorer_metric_counter").tag("metric_id", id).meters().forEach(meterRegistry::remove);
        meterRegistry.find("explorer_metric_histogram").tag("metric_id", id).meters().forEach(meterRegistry::remove);
        meterRegistry.find("explorer_metric_summary").tag("metric_id", id).meters().forEach(meterRegistry::remove);
        COMPANION_SERIES.forEach(name ->
            meterRegistry.find(name).tag("metric_id", id).meters().forEach(meterRegistry::remove));
    }

    /** True when a re-saved metric differs in any field that affects its Micrometer series. */
    private boolean metricShapeChanged(MetricConfig a, MetricConfig b) {
        return !Objects.equals(a.type(), b.type())
            || !Objects.equals(a.sql(), b.sql())
            || !Objects.equals(a.labelTopic(), b.labelTopic())
            || !Objects.equals(a.labelFields(), b.labelFields())
            || !Objects.equals(a.templateType(), b.templateType())
            || !Objects.equals(a.templateParams(), b.templateParams())
            || !Objects.equals(a.createTableSql(), b.createTableSql())
            || !Objects.equals(a.executionMode(), b.executionMode());
    }

    // ── Scheduled refresh ─────────────────────────────────────────────────────

    /**
     * The scan option a metric asks for, and the one it deliberately does not.
     *
     * <p>This was a lone {@code scan.startup.mode='earliest-offset'} under a comment promising the
     * query "reads all data that exists in Kafka at query start time, then terminates (no
     * indefinite streaming)". That sentence is the contract of {@code scan.bounded.mode};
     * {@code scan.startup.mode} says where a scan <em>begins</em>. Nothing bounded anything,
     * therefore, and the option that was there changed nothing either — {@code DdlGeneratorService}
     * already writes {@code earliest-offset} into every table it generates. The environment is
     * built {@code inStreamingMode()} ({@code FlinkConfig}), so the source stayed unbounded and a
     * metric's read either blocked until its own timeout or came back with the first rows an
     * endless scan happened to yield.
     *
     * <p><b>The bounded option was added, then measured as refused, and that measurement was
     * wrong.</b> {@code flink-connector-kafka:5.0.0-2.2} answered a hint carrying it with
     * {@code ValidationException: Unsupported options found for 'kafka'}, and the refusal was
     * attributed to {@code scan.bounded.mode}. Two things made that conclusion unsound, neither
     * visible from where it was drawn. The hint never reached the planner at all:
     * {@code FlinkSqlService.stripSqlComments} erased {@code /* … *}{@code /} blocks, and a
     * Calcite hint is comment-shaped. And the key actually refused in the same {@code WITH (…)}
     * was a different one — {@code json.ignore-parse-errors} written without its {@code value.}
     * prefix — the exception listing every unconsumed option together, so the blame fell on the
     * option just added rather than on the one that had always been wrong.
     *
     * <p>Both corrected, {@code KafkaClusterIntegrationTest.thisConnectorBoundsAScanWhenAsked}
     * measures the opposite: the count runs through the planner and terminates. So the sentence
     * this constant was named for is expressible here after all.
     *
     * <p>The hint still carries the startup mode alone, and that is a decision rather than an
     * omission: what bounds a metric's read changes what the metric <em>measures</em> — a count
     * over "everything at query start" is not the count the templates publish today, whose
     * semantics ({@link #isSingleTableRead}, {@code QueryRequest.directRead}, the window and
     * offset-count modes) were settled against the direct reader. Sending the option again is a
     * change to that, argued and measured on its own, not a line to flip here.
     */
    private static final String SCAN_STARTUP_EARLIEST = "'scan.startup.mode'='earliest-offset'";

    private static final Pattern FROM_TABLE = Pattern.compile("(?i)\\bFROM\\b\\s+(\\w[\\w.]*)");

    private static final Pattern JOIN_KEYWORD = Pattern.compile("(?i)\\bJOIN\\b");

    /** The scan option a template read asks for. */
    private String scanHint() {
        return "/*+ OPTIONS(" + SCAN_STARTUP_EARLIEST + ") */";
    }

    /**
     * Inject the scan hint after the first table reference in a FROM clause, unless the SQL
     * already carries a hint or an OPTIONS(...) clause.
     */
    private String injectScanHint(String sql, String hint) {
        if (sql == null || hint == null) return sql;
        if (sql.contains("/*+") || sql.toUpperCase(Locale.ROOT).contains("OPTIONS(")) return sql;
        // Match FROM <word> — skip subqueries (followed by '(')
        Matcher m = FROM_TABLE.matcher(sql);
        if (m.find()) {
            return sql.substring(0, m.end(1)) + " " + hint + sql.substring(m.end(1));
        }
        return sql;
    }

    /**
     * A single-table read: the one shape the direct Kafka reader can answer without lying.
     *
     * <p>That reader regex-matches one name out of {@code FROM} and knows neither JOIN nor
     * subqueries, so asking it for anything else returns rows that quietly ignore half the
     * statement — the exact reason a user error stopped falling back to it. The templates
     * generate this shape and nothing else ({@code MetricSuggestionService.countSql} and
     * {@code correlationSql}); anything an operator has since made more complex goes to the
     * planner instead, and the summary names the engine that answered.
     *
     * <p>It fails closed by construction: every branch that is not certain answers false, which
     * costs the planner's slower path and never a wrong row.
     */
    static boolean isSingleTableRead(String sql) {
        if (sql == null || sql.isBlank()) return false;
        String body = sql.trim();
        if (!body.toUpperCase(Locale.ROOT).startsWith("SELECT")) return false;
        if (JOIN_KEYWORD.matcher(body).find()) return false;
        if (body.replaceAll("\\s+", "").toUpperCase(Locale.ROOT).contains("(SELECT")) return false;
        Matcher m = FROM_TABLE.matcher(body);
        if (!m.find()) return false;
        // A comma straight after the table name is a table list, i.e. a join written the old way.
        if (body.substring(m.end(1)).stripLeading().startsWith(",")) return false;
        // A second FROM is a shape this reader cannot honour either.
        return !m.find();
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

        // Not a template parameter by nature, but templateParams is this record's free-form
        // per-metric bag and already carries every other knob; a component of its own would be a
        // forty-three-call-site change for one number. Checked here, for any metric.
        long refreshIntervalMs = getLongParam(params, "refreshIntervalMs", 0L);
        if (refreshIntervalMs != 0L
            && (refreshIntervalMs < MIN_REFRESH_INTERVAL_MS || refreshIntervalMs > MAX_REFRESH_INTERVAL_MS)) {
            throw new IllegalArgumentException("refreshIntervalMs must be between "
                + MIN_REFRESH_INTERVAL_MS + " and " + MAX_REFRESH_INTERVAL_MS
                + ", or 0 to refresh on every cycle (was " + refreshIntervalMs + ")");
        }

        switch (templateType) {
            case RAW_SQL -> {
                if (metric.sql() == null || metric.sql().isBlank()) {
                    throw new IllegalArgumentException("SQL is required for RAW_SQL metrics");
                }
            }
            case TOPIC_COUNT_DELTA -> {
                /*
                 * Two shapes, and each needs what it reads. A records count needs the two queries;
                 * an offsets count needs the two topics and no SQL at all, since it asks the log
                 * rather than the query engine. AUTO needs whichever it will resolve to, which is
                 * why the check follows resolveCountMode rather than restating its rule.
                 */
                String mode = resolveCountMode(params,
                    getStringParam(params, "leftTopic", ""), getStringParam(params, "rightTopic", ""));
                if ("OFFSETS".equals(mode)) {
                    requireParam(params, "leftTopic");
                    requireParam(params, "rightTopic");
                } else {
                    requireParam(params, "leftSql");
                    requireParam(params, "rightSql");
                }
                validateScanParams(params, DEFAULT_TEMPLATE_READ_MODE);
                // Checked here rather than from inside the refresh loop, where an unrecognised
                // value threw every thirty seconds for ever on a metric the API had answered 200
                // to. It is the rule CONSUMER_TIME_LAG's aggregation follows nine lines below,
                // and the last of this template's parameters that did not follow it.
                String operation = getStringParam(params, "operation", "LEFT_MINUS_RIGHT")
                    .toUpperCase(Locale.ROOT);
                if (!DELTA_OPERATIONS.contains(operation)) {
                    throw new IllegalArgumentException("TOPIC_COUNT_DELTA operation must be one of "
                        + DELTA_OPERATIONS + " (was '" + operation + "')");
                }
                String countBy = getStringParam(params, "countBy", "AUTO").toUpperCase(Locale.ROOT);
                if (!COUNT_MODES.contains(countBy)) {
                    throw new IllegalArgumentException("countBy must be one of " + COUNT_MODES
                        + " (was '" + countBy + "')");
                }
                String window = getStringParam(params, "window", "TOTAL").toUpperCase(Locale.ROOT);
                if (!COUNT_WINDOWS.contains(window)) {
                    throw new IllegalArgumentException("window must be one of " + COUNT_WINDOWS
                        + " (was '" + window + "')");
                }
                if (!"GAUGE".equals(metricType)) {
                    throw new IllegalArgumentException("TOPIC_COUNT_DELTA supports GAUGE metrics only");
                }
            }
            case TOPIC_TRANSIT_LATENCY -> {
                String sourceSql = requireParam(params, "sourceSql");
                String targetSql = requireParam(params, "targetSql");
                validateScanParams(params, DEFAULT_LATENCY_READ_MODE);
                long windowMs = getLongParam(params, "windowMs", 0L);
                if (windowMs != 0L
                    && (windowMs < MIN_LATENCY_WINDOW_MS || windowMs > MAX_LATENCY_WINDOW_MS)) {
                    throw new IllegalArgumentException("windowMs must be between "
                        + MIN_LATENCY_WINDOW_MS + " and " + MAX_LATENCY_WINDOW_MS
                        + ", or 0 to bound the two reads by row count instead (was " + windowMs + ")");
                }
                /*
                 * A window is a direct-reader instruction, so a side the planner will answer
                 * cannot honour it — and a window silently ignored on one side is worse than none
                 * at all: the two reads would cover different stretches of time while the summary
                 * says they cover the same one. Refused where it can still be corrected, on the
                 * same rule as the operation and the scan parameters beside it.
                 */
                if (windowMs != 0L && !(isSingleTableRead(sourceSql) && isSingleTableRead(targetSql))) {
                    throw new IllegalArgumentException("windowMs bounds the read by time, which "
                        + "only the direct Kafka reader can do — and it answers a single-table "
                        + "SELECT. The "
                        + (isSingleTableRead(sourceSql) ? "target" : "source")
                        + " query joins or nests, so it goes to the Flink planner, which would "
                        + "read a different stretch of time. Simplify that query, or leave "
                        + "windowMs at 0 and bound both sides by maxRowsPerSide.");
                }
                if (!Set.of("GAUGE", "HISTOGRAM", "SUMMARY").contains(metricType)) {
                    throw new IllegalArgumentException("TOPIC_TRANSIT_LATENCY supports GAUGE, HISTOGRAM or SUMMARY");
                }
            }
            case CONSUMER_TIME_LAG -> {
                requireParam(params, "topic");
                // The group is required rather than resolved to "the worst one reading this
                // topic": that choice would move between refreshes, so the series would silently
                // change subject, and an alert on it could never be traced back to a consumer.
                requireParam(params, "group");
                if (!"GAUGE".equals(metricType)) {
                    throw new IllegalArgumentException("CONSUMER_TIME_LAG supports GAUGE metrics only");
                }
                String aggregation = getStringParam(params, "aggregation", "MAX").toUpperCase(Locale.ROOT);
                if (!Set.of("MAX", "AVG").contains(aggregation)) {
                    throw new IllegalArgumentException("CONSUMER_TIME_LAG aggregation must be MAX or AVG");
                }
            }
        }
    }

    /**
     * The scan a two-query template will run, checked when it is saved rather than on every
     * refresh for ever.
     *
     * <p>These three decide what the metric actually measures — how much of each topic is read,
     * from which end, and how long a side may take — and all three were taken on trust: a
     * mistyped {@code maxRowsPerSide} threw {@code NumberFormatException} from inside the refresh
     * loop, once every thirty seconds, on a metric the API had accepted with a 200. The sibling
     * template three lines below has always checked its own {@code aggregation} here.
     */
    private void validateScanParams(Map<String, Object> params, String defaultReadMode) {
        int maxRows = getIntParam(params, "maxRowsPerSide", DEFAULT_TEMPLATE_MAX_ROWS);
        if (maxRows < 1 || maxRows > MAX_TEMPLATE_MAX_ROWS) {
            throw new IllegalArgumentException(
                "maxRowsPerSide must be between 1 and " + MAX_TEMPLATE_MAX_ROWS + " (was " + maxRows + ")");
        }
        long timeoutMs = getLongParam(params, "timeoutMs", DEFAULT_TEMPLATE_TIMEOUT_MS);
        if (timeoutMs < 1_000L || timeoutMs > MAX_TEMPLATE_TIMEOUT_MS) {
            throw new IllegalArgumentException(
                "timeoutMs must be between 1000 and " + MAX_TEMPLATE_TIMEOUT_MS + " (was " + timeoutMs + ")");
        }
        String readMode = getStringParam(params, "readMode", defaultReadMode);
        if (!READ_MODES.contains(readMode)) {
            throw new IllegalArgumentException("readMode must be one of " + READ_MODES + " (was '" + readMode + "')");
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

    /**
     * @param preview a dry run for the editor rather than a refresh. It must not touch state a
     *                running metric owns — a windowed count delta keeps the previous cycle's
     *                counts, and previewing one would leave it comparing against an instant the
     *                operator was only looking at.
     */
    private MetricComputationResult computeMetric(MetricConfig config, boolean preview) {
        return switch (MetricTemplateType.fromValue(config.templateType())) {
            case RAW_SQL -> computeRawSqlMetric(config);
            case TOPIC_COUNT_DELTA -> computeCountDeltaMetric(config, preview);
            case TOPIC_TRANSIT_LATENCY -> computeTransitLatencyMetric(config);
            case CONSUMER_TIME_LAG -> computeConsumerTimeLagMetric(config);
        };
    }

    /**
     * A consumer group's delay in milliseconds — the age of the oldest record it has not read.
     *
     * <p>The only template that runs no SQL, because no query over the topic can answer it: the
     * position is in {@code __consumer_offsets} and the age is in a record's timestamp. It exists
     * because the record lag alone is not actionable — the same ten thousand messages are ten
     * seconds of traffic on one topic and a fortnight on another, and it is the second case that
     * wakes somebody up.
     *
     * <p>A partial read is <b>reported, not averaged over</b>: when partitions could not be
     * measured the value is still published (the ones that answered are real) and the summary
     * says how many did not, because a maximum taken over half the partitions is a floor. A read
     * that measured nothing at all is an error, never a zero — zero means "caught up", and a
     * gauge that says so while nothing could be read silences the alert it exists to raise.
     */
    private MetricComputationResult computeConsumerTimeLagMetric(MetricConfig config) {
        Map<String, Object> params = config.templateParams() != null ? config.templateParams() : Map.of();
        String topic = requireParam(params, "topic");
        String group = requireParam(params, "group");
        String aggregation = getStringParam(params, "aggregation", "MAX").toUpperCase(Locale.ROOT);

        TopicTimeLag lag = kafkaAdminService.getConsumerTimeLag(topic, group);
        if (!lag.available()) {
            return MetricComputationResult.error(lag.error() != null
                ? lag.error()
                : "The delay of '" + group + "' on '" + topic + "' could not be measured.");
        }
        Long value = "AVG".equals(aggregation) ? lag.avgLagMs() : lag.maxLagMs();
        if (value == null) {
            return MetricComputationResult.error("No partition's delay could be measured for '" + group + "'.");
        }

        Map<String, Object> row = new LinkedHashMap<>();
        // Both labels are pinned by the configuration, so neither can change subject between two
        // refreshes — the reason the group is a required parameter.
        row.put("topic", topic);
        row.put("group", group);
        row.put("metric_value", value.doubleValue());

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("aggregation", aggregation);
        summary.put("maxLagMs", lag.maxLagMs());
        summary.put("avgLagMs", lag.avgLagMs());
        summary.put("partitionsMeasured", lag.partitionsMeasured());
        summary.put("partitionsCaughtUp", lag.partitionsCaughtUp());
        summary.put("partitionsWithoutCommit", lag.partitionsWithoutCommit());
        summary.put("partitionsUnknown", lag.partitionsUnknown());
        summary.put("complete", lag.complete());
        if (!lag.complete()) {
            summary.put("scopeNote", "Measured over " + lag.partitionsMeasured() + " partition(s); "
                + lag.partitionsUnknown() + " could not be read and " + lag.partitionsWithoutCommit()
                + " have no committed offset, so this value is a floor, not a maximum.");
        }
        if (!lag.warnings().isEmpty()) summary.put("warnings", lag.warnings());

        return new MetricComputationResult(List.of(row), value.doubleValue(), null, summary);
    }

    private MetricComputationResult computeRawSqlMetric(MetricConfig config) {
        /*
         * Le SQL de l'opérateur, sur le moteur qui peut y répondre honnêtement.
         *
         * Ce chemin demandait le planner sans condition, sous un commentaire disant « jamais le
         * lecteur direct : le SQL libre est celui de l'opérateur et peut avoir besoin du
         * planner ». C'était écrit quand le planner ne répondait à rien : dans les faits chaque
         * métrique retombait sur le lecteur direct, qui rend un agrégat en une ligne. Le moteur
         * réparé, le planner répond vraiment — et un COUNT(*) en streaming est un changelog sans
         * fin, ce qui donne deux mauvaises réponses selon la taille du topic. Sur un gros topic il
         * remplit le plafond de lignes et la dernière est un compte *partiel* publié comme un
         * total. Sur un petit, il ne l'atteint jamais, bloque, et la métrique paie son budget de
         * temps entier à chaque rafraîchissement avant de retomber — mesuré à 30 s sur un topic de
         * huit enregistrements.
         *
         * La forme décide, pas le mode : une lecture d'une seule table est ce que le lecteur
         * direct sait honorer (`isSingleTableRead`, la règle que les modèles appliquent déjà), et
         * elle rend le nombre que cette métrique publiait avant. Tout le reste — une jointure, une
         * sous-requête — a réellement besoin du planner, et c'est un progrès qu'il y aille : le
         * lecteur direct y lisait une table et ignorait le reste en silence.
         */
        boolean directRead = isSingleTableRead(config.sql());
        QueryResult result = executeMetricQuery(
            config.sql(),
            DEFAULT_TEMPLATE_MAX_ROWS,
            DEFAULT_TEMPLATE_TIMEOUT_MS,
            DEFAULT_TEMPLATE_READ_MODE,
            directRead
        );
        if (result.error() != null) return MetricComputationResult.error(result.error());
        if (result.rows().isEmpty()) {
            return MetricComputationResult.error("No rows returned — check table name and Kafka connectivity");
        }
        // Le même refus que pour un écart entre deux requêtes, et pour la même raison : publier la
        // dernière ligne d'un changelog tronqué, c'est publier un compte partiel sous les traits
        // d'un total, sur la valeur même qui déclenche une alerte.
        if (isTruncatedChangelog(result, DEFAULT_TEMPLATE_MAX_ROWS)) {
            return MetricComputationResult.error("The query filled its row budget ("
                + DEFAULT_TEMPLATE_MAX_ROWS + " rows), so the value it returned is a partial "
                + "aggregate taken mid-changelog rather than a total. Add a LIMIT, or write a "
                + "query the direct reader can answer (a single table, no join and no subquery).");
        }

        /*
         * Sur un changelog, la valeur est la *dernière* ligne.
         *
         * `extractPrimaryMetricValue` rend la première valeur numérique rencontrée, ce qui est
         * l'agrégat sur le lecteur direct — une ligne — et `+I(1)` sur un changelog rétractable du
         * planner. C'est exactement le défaut que le chemin des écarts entre deux requêtes a dû
         * corriger, resté debout ici et devenu atteignable le jour où le planner s'est mis à
         * répondre. Le changement est borné au moteur qui produit des changelogs : ce que publie
         * une métrique servie par le lecteur direct ne bouge pas.
         */
        Double displayValue = "FLINK".equals(result.engine())
            ? lastNumericMetricValue(result.rows())
            : extractPrimaryMetricValue(result.rows());
        return new MetricComputationResult(
            result.rows(),
            displayValue,
            null,
            Map.of("rowCount", result.rows().size())
        );
    }

    /** True when the statement is a plain whole-topic count, which offsets answer exactly. */
    static boolean isWholeTopicCount(String sql) {
        return sql != null && WHOLE_TOPIC_COUNT.matcher(sql.trim()).matches();
    }

    /** Both sides' numbers, however they were obtained. */
    /**
     * @param sharedScan the two counts came out of one read of one topic, so they describe the
     *                   same instant — which is what makes {@code gapMs} a zero that was measured
     *                   rather than one nobody took.
     */
    private record CountRead(Double left, Double right, String error, String engine, long gapMs,
                             List<String> warnings, boolean sharedScan) {
        static CountRead failed(String error) {
            return new CountRead(null, null, error, null, 0L, List.of(), false);
        }
        CountRead(Double left, Double right, String error, String engine, long gapMs,
                  List<String> warnings) {
            this(left, right, error, engine, gapMs, warnings, false);
        }
    }

    /**
     * Both counts out of the log's offsets, in one call and therefore at one instant.
     *
     * <p><b>Two ways of not having a number, and neither may be published as one.</b> A topic that
     * does not exist is refused by the {@code listTopics} check below, because an unknown name and
     * an empty topic are indistinguishable once either has become a count, and a 0 against a real
     * count on the other side reads as total loss — the invented critical finding this codebase
     * keeps removing. A topic that exists but whose <em>offsets</em> did not come back is the same
     * refusal by the other door, and it used to go straight through: the guard for it was written
     * ({@code left == null || right == null}) and could never fire, because {@code getTopicsSize}
     * pre-seeds every requested name at {@code 0} and swallows the failure. Both sides then came
     * back zero and {@code PERCENT_GAP} published {@code 0.0} — "nothing is being lost", from the
     * metric that exists to report loss, on any broker blip. {@code getTopicRecordCounts} is the
     * same read omitting what it could not measure, which is what makes that guard live.
     */
    private CountRead countByOffsets(String leftTopic, String rightTopic) {
        if (leftTopic.isBlank() || rightTopic.isBlank()) {
            return CountRead.failed("An offset count needs both topics named (leftTopic and "
                + "rightTopic): it asks the log for its own offsets rather than running a query.");
        }
        List<String> wanted = leftTopic.equals(rightTopic)
            ? List.of(leftTopic) : List.of(leftTopic, rightTopic);
        List<String> missing;
        try {
            List<String> known = kafkaAdminService.listTopics();
            missing = wanted.stream().filter(t -> !known.contains(t)).toList();
        } catch (Exception e) {
            return CountRead.failed("The cluster's topics could not be listed, so an offset count "
                + "cannot tell an absent topic from an empty one: " + SqlErrorClassifier.explain(e));
        }
        if (!missing.isEmpty()) {
            return CountRead.failed("No topic named " + String.join(" or ", missing)
                + " on this cluster. An offset count is refused rather than reported as zero: a "
                + "zero against a real count on the other side reads as total loss.");
        }

        long startedAt = System.currentTimeMillis();
        Map<String, Long> sizes = kafkaAdminService.getTopicRecordCounts(wanted);
        Long left = sizes.get(leftTopic);
        Long right = sizes.get(rightTopic);
        if (left == null || right == null) {
            // Named, because "we could not measure demo.orders.2.validated" and "the broker is
            // gone" send an operator to two different places — and the refusal has to be legible
            // enough that nobody is tempted to read the silence as a zero.
            String unmeasured = left == null && right == null
                ? leftTopic + " or " + rightTopic
                : (left == null ? leftTopic : rightTopic);
            return CountRead.failed("The broker did not answer with offsets for " + unmeasured
                + ". The count is refused rather than reported as zero: a zero here is a "
                + "measurement, and against a real count on the other side it reads as total loss.");
        }
        // One pair of listOffsets responses covers both sides, so the interval between the two
        // measurements is genuinely zero — see the ordering note on the records path below, which
        // exists because that path cannot say the same. The call's own duration is not that
        // interval and is not reported as one.
        long unusedCallDuration = System.currentTimeMillis() - startedAt;
        log.debug("Counted '{}' and '{}' from offsets in {} ms", LogSafe.name(leftTopic),
            LogSafe.name(rightTopic), unusedCallDuration);
        return new CountRead(left.doubleValue(), right.doubleValue(), null, "KAFKA_OFFSETS",
            0L, List.of());
    }

    /** Both counts by running the two queries, which is the only way to honour a predicate. */
    private CountRead countByRecords(Map<String, Object> params, int maxRows, long timeoutMs,
                                     String readMode) {
        String leftSql = requireParam(params, "leftSql");
        String rightSql = requireParam(params, "rightSql");
        /*
         * The right side is read first, and the order is the measurement's, not the form's.
         *
         * Two counts cannot be taken at one instant on this path — a whole query separates them.
         * So the arithmetic leans, and the only choice is which way. Every operation here grows
         * with the left side, and the panel proposes the upstream topic there with a threshold
         * that fires when the value is high — so reading the left side *last* lets the traffic of
         * the interval land in it, and a gap that survives that is a real one. Read the other way
         * round, the same traffic lands in the denominator and the gap is understated: the metric
         * under-reports exactly the loss it exists to report.
         *
         * It is the rule KafkaAdminService already follows for consumer lag — committed offsets
         * first, log end offsets last. ABS_DIFF is the one operation this cannot help: it is
         * symmetric, so no ordering is conservative for it, and gapMs is what says how much room
         * the interval left. The offsets path above needs none of this.
         */
        long rightReadAt = System.currentTimeMillis();
        FlinkSqlService.QueryPair pair = executeMetricPair(
            rightSql, leftSql, maxRows, timeoutMs, readMode);
        QueryResult rightResult = pair.first();
        if (rightResult.error() != null) return CountRead.failed("Right query: " + rightResult.error());
        QueryResult leftResult = pair.second();
        if (leftResult.error() != null) return CountRead.failed("Left query: " + leftResult.error());
        /*
         * When both sides are counts over the same topic they came out of one read, so there is no
         * interval between them to lean either way — the gap is zero because it *is* zero, the
         * same thing the offsets count gets for a different reason. Two different topics still pay
         * a whole query, and the note above is what that ordering is for.
         */
        long gapMs = pair.sharedScan() ? 0L : System.currentTimeMillis() - rightReadAt;

        SideRead left = aggregateValue(leftResult, maxRows, "left");
        if (left.error() != null) return CountRead.failed(left.error());
        SideRead right = aggregateValue(rightResult, maxRows, "right");
        if (right.error() != null) return CountRead.failed(right.error());

        // A floor is not a count, and two floors compared read as no gap at all — which is the one
        // answer this metric must never give by accident. Offsets have no such ceiling, and the
        // message says so rather than leaving the operator without a way out.
        if (left.capped() || right.capped()) {
            boolean both = left.capped() && right.capped();
            return CountRead.failed(
                (both ? "Both counts stopped" : (left.capped() ? "The left count stopped" : "The right count stopped"))
                + " on the direct reader's " + FlinkSqlService.AGGREGATE_SCAN_RECORDS
                + "-record ceiling, so " + (both ? "they are floors" : "it is a floor")
                + " rather than a total. A gap measured between floors reads as no gap, so nothing "
                + "is published. Count these topics by offsets instead (countBy = OFFSETS), which "
                + "reads no records and has no ceiling.");
        }

        List<String> warnings = new ArrayList<>();
        for (QueryResult result : List.of(rightResult, leftResult)) {
            if (result.warnings() == null) continue;
            for (String warning : result.warnings()) {
                if (warning != null && !warning.isBlank() && !warnings.contains(warning)) {
                    warnings.add(warning);
                }
            }
        }
        String engine = Objects.equals(leftResult.engine(), rightResult.engine())
            ? String.valueOf(leftResult.engine())
            : leftResult.engine() + "/" + rightResult.engine();
        return new CountRead(left.value(), right.value(), null, engine, gapMs, warnings,
            pair.sharedScan());
    }

    /** Offsets when both sides ask the question offsets answer, records otherwise. */
    private String resolveCountMode(Map<String, Object> params, String leftTopic, String rightTopic) {
        String requested = getStringParam(params, "countBy", "AUTO").toUpperCase(Locale.ROOT);
        if (!"AUTO".equals(requested)) return requested;
        if (leftTopic.isBlank() || rightTopic.isBlank()) return "RECORDS";
        String leftSql = getStringParam(params, "leftSql", "");
        String rightSql = getStringParam(params, "rightSql", "");
        boolean plain = (leftSql.isBlank() || isWholeTopicCount(leftSql))
            && (rightSql.isBlank() || isWholeTopicCount(rightSql));
        return plain ? "OFFSETS" : "RECORDS";
    }

    private MetricComputationResult computeCountDeltaMetric(MetricConfig config, boolean preview) {
        Map<String, Object> params = config.templateParams() != null ? config.templateParams() : Map.of();
        int maxRows = getIntParam(params, "maxRowsPerSide", DEFAULT_TEMPLATE_MAX_ROWS);
        long timeoutMs = getLongParam(params, "timeoutMs", DEFAULT_TEMPLATE_TIMEOUT_MS);
        // A count must see the whole topic, so this side is read from the beginning whatever the
        // latency template does — the two templates ask different questions of the same broker.
        String readMode = getStringParam(params, "readMode", DEFAULT_TEMPLATE_READ_MODE);
        String leftTopic = getStringParam(params, "leftTopic", "");
        String rightTopic = getStringParam(params, "rightTopic", "");
        String countBy = resolveCountMode(params, leftTopic, rightTopic);

        CountRead read = "OFFSETS".equals(countBy)
            ? countByOffsets(leftTopic, rightTopic)
            : countByRecords(params, maxRows, timeoutMs, readMode);
        if (read.error() != null) return MetricComputationResult.error(read.error());

        double leftTotal = read.left();
        double rightTotal = read.right();

        // ── what the two numbers are compared over ──────────────────────────────
        String window = getStringParam(params, "window", "TOTAL").toUpperCase(Locale.ROOT);
        boolean windowed = "SINCE_LAST_REFRESH".equals(window);
        double comparedLeft = leftTotal;
        double comparedRight = rightTotal;
        String windowNote = null;
        if (windowed) {
            if (preview || config.id() == null || config.id().isBlank()) {
                // A preview has no previous refresh, and must not invent one: writing a baseline
                // here would leave a running metric subtracting from an instant nobody measured.
                windowNote = "Previewed as totals: this metric publishes what each side produced "
                    + "since the previous refresh, and a preview has no previous refresh.";
            } else {
                double[] baseline =
                    countDeltaBaselines.put(config.id(), new double[]{leftTotal, rightTotal});
                if (baseline == null) {
                    return MetricComputationResult.error("Baseline established. This comparison "
                        + "reports what each side produced since the previous refresh, so the first "
                        + "one has nothing to subtract; the next refresh reports the gap over the "
                        + "interval.");
                }
                comparedLeft = leftTotal - baseline[0];
                comparedRight = rightTotal - baseline[1];
                if (comparedLeft < 0 || comparedRight < 0) {
                    return MetricComputationResult.error("A side's count went backwards, so the "
                        + "interval cannot be measured: a topic was recreated, or its offsets were "
                        + "reset. The baseline is re-established and the next refresh reports "
                        + "normally.");
                }
            }
        }

        String operation = getStringParam(params, "operation", "LEFT_MINUS_RIGHT").toUpperCase(Locale.ROOT);
        final double left = comparedLeft;
        final double right = comparedRight;
        /*
         * Total loss is the state this metric exists to catch, and it was the one it could not say.
         *
         * PERCENT_GAP divides by the right side, so "left > 0, right = 0" — everything the source
         * produced and nothing arrived — was refused as a division by zero and published nothing
         * at all: the most alarming reading this template can take, and the alert stayed silent on
         * it while firing happily at 3 %. It is not an indefinite form but a definition, and it is
         * stated as one rather than derived: the formula's own limit there is infinity, and 100 is
         * the number a threshold is set against, so any threshold below 100 fires. Both sides at
         * zero is not a loss — nothing was produced and nothing was missed — and reads 0.
         *
         * RATIO stays refused. There is no defensible finite value for a ratio to zero, and the
         * error already names the two operations that report the same fact as a number.
         */
        boolean totalLoss = "PERCENT_GAP".equals(operation) && right == 0.0 && left > 0.0;
        Double metricValue = switch (operation) {
            case "LEFT_MINUS_RIGHT" -> left - right;
            case "ABS_DIFF" -> Math.abs(left - right);
            case "RATIO" -> right == 0.0 ? null : left / right;
            case "PERCENT_GAP" -> right == 0.0
                ? (left > 0.0 ? 100.0 : 0.0)
                : ((left - right) * 100.0) / right;
            // Unreachable through save(), which refuses an unknown operation — kept because a
            // record read back from internal.metrics.config predates that check.
            default -> throw new IllegalArgumentException("Unsupported count delta operation: "
                + operation + ". Expected one of " + DELTA_OPERATIONS + ".");
        };
        if (metricValue == null) {
            return MetricComputationResult.error("Cannot compute " + operation
                + " when the right side counts zero: " + operation + " divides by it. The left side "
                + "counted " + formatCount(left) + (windowed ? " over this interval" : "")
                + ", so if the right topic really produced nothing that is the finding — "
                + "LEFT_MINUS_RIGHT, ABS_DIFF or PERCENT_GAP report it as a number.");
        }

        Map<String, Object> row = new LinkedHashMap<>();
        row.put("metric_value", metricValue);
        // Measurements, not labels: these move on every refresh of a live topic, and a label that
        // moves mints a new time series per scrape — see isReservedColumn.
        row.put("__left_value", left);
        row.put("__right_value", right);
        row.put("operation", operation);
        addIfPresent(row, "left_topic", params.get("leftTopic"));
        addIfPresent(row, "right_topic", params.get("rightTopic"));

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("leftValue", left);
        summary.put("rightValue", right);
        summary.put("operation", operation);
        summary.put("countedBy", countBy);
        summary.put("window", window);
        if (windowed) {
            summary.put("leftTotal", leftTotal);
            summary.put("rightTotal", rightTotal);
        }
        summary.put("engine", read.engine());
        summary.put("readGapMs", read.gapMs());
        // Stated, never inferred from the gap being zero: two separate reads can round to the
        // same millisecond, and "these describe one instant" is a claim about how they were taken.
        if (read.sharedScan()) summary.put("sharedScan", true);
        if (totalLoss) summary.put("totalLoss", true);
        summary.put("scopeNote",
            countDeltaScopeNote(countBy, windowed, windowNote, operation, read, maxRows, readMode,
                totalLoss));
        if (!read.warnings().isEmpty()) summary.put("warnings", read.warnings());

        return new MetricComputationResult(List.of(row), metricValue, null, summary);
    }

    private String countDeltaScopeNote(String countBy, boolean windowed, String windowNote,
                                       String operation, CountRead read, int maxRows, String readMode,
                                       boolean totalLoss) {
        StringBuilder note = new StringBuilder();
        if ("OFFSETS".equals(countBy)) {
            note.append("Counted from the log's own offsets — no record is read — and both sides "
                + "come out of one call, so they describe the same instant. That counts offsets "
                + "produced rather than records present: a transaction marker takes one, and a "
                + "record later compacted away still counts.");
        } else {
            if (read.sharedScan()) {
                note.append("Counted by reading records, both sides out of one read of the one "
                    + "topic they share, so they describe the same instant and no traffic falls "
                    + "between them. Read ").append(describeReadEnd(readMode));
            } else {
                note.append("Counted by reading records, right side first and left ")
                    .append(read.gapMs())
                    .append(" ms later, so traffic in between lands in the left count and this ")
                    .append("ABS_DIFF".equals(operation)
                        ? "difference can move either way" : "value can only be overstated")
                    .append(", never understated. Read ").append(describeReadEnd(readMode));
            }
            note.append(". A side the direct reader answered covers at most ")
                .append(FlinkSqlService.AGGREGATE_SCAN_RECORDS)
                .append(" record(s); a side the planner answered covers at most ")
                .append(maxRows).append(" row(s).");
        }
        if (windowed) {
            note.append(" Compared over what each side produced since the previous refresh, not "
                + "over the totals — a lifetime total loses its sensitivity as history accumulates.");
        }
        if (windowNote != null) note.append(' ').append(windowNote);
        if (totalLoss) {
            note.append(" The right side counted zero against a non-zero left side, so the gap is "
                + "total and is reported as 100 % — a definition for that case, not the formula's "
                + "own answer, which divides by the right side.");
        }
        return note.toString();
    }

    /** Which end of the topic a read entered by, in the words the summary uses. */
    private String describeReadEnd(String readMode) {
        Long since = FlinkSqlService.sinceTimestampOf(readMode);
        if (since != null) return "forward from " + Instant.ofEpochMilli(since);
        return DEFAULT_LATENCY_READ_MODE.equals(readMode)
            ? "from the most recent records backwards"
            : "from the earliest offset";
    }

    /**
     * What the two correlated reads covered, and the one thing a window cannot avoid.
     *
     * <p>A snapshot manufactures unmatched events at its trailing edge: a source produced a second
     * before the window closed has its target somewhere after it, outside both reads, and that is
     * indistinguishable from a target that never came. It is not a defect and it is not a finding,
     * so it is stated rather than corrected — {@code ProcessModelBuilder} says the same thing about
     * the cases its window cuts in half, and for the same reason: what looks like a defect and is
     * not must be named, or it will be read as one.
     */
    private String latencyScopeNote(int maxRows, String readMode, long windowMs, Long windowFromMs) {
        if (windowMs <= 0 || windowFromMs == null) {
            return "Correlated over at most " + maxRows + " row(s) per side, read "
                + describeReadEnd(readMode) + ". The two sides are capped by row count rather than "
                + "by time, so on topics of different throughputs they cover different stretches of "
                + "it and the match rate is depressed by that misalignment as much as by a real "
                + "loss. Set a window to compare the same stretch of time on both sides.";
        }
        return "Correlated over the same " + formatDurationMs(windowMs) + " on both sides — from "
            + Instant.ofEpochMilli(windowFromMs) + " — at most " + maxRows + " row(s) each. A source "
            + "produced near the end of that window has its target after it, outside both reads, so "
            + "it counts as unmatched: the trailing edge understates the match rate by roughly one "
            + "hop's latency worth of traffic, whatever the pipeline is doing.";
    }

    /** A duration in the words a scope note uses, never as a bare millisecond count. */
    private static String formatDurationMs(long ms) {
        if (ms >= 3_600_000L && ms % 3_600_000L == 0) return (ms / 3_600_000L) + " h";
        if (ms >= 60_000L && ms % 60_000L == 0) return (ms / 60_000L) + " min";
        if (ms >= 1_000L && ms % 1_000L == 0) return (ms / 1_000L) + " s";
        return ms + " ms";
    }

    private String formatCount(double value) {
        return value == Math.rint(value) ? String.valueOf((long) value) : String.valueOf(value);
    }

    /**
     * Carry the engine's own caveats into the metric's summary.
     *
     * <p>{@code QueryResult.warnings} exists to say which predicates the direct reader could not
     * apply — "silently returning unfiltered rows for a WHERE it does not understand makes the
     * result look precise when it is not", as its own javadoc puts it. The metric engine read it
     * nowhere, on the path whose output feeds an alert, so a metric filtered by a predicate the
     * reader had dropped published a number computed over everything.
     */
    private void addScanWarnings(Map<String, Object> summary, QueryResult... results) {
        List<String> warnings = new ArrayList<>();
        for (QueryResult result : results) {
            if (result != null && result.warnings() != null) {
                for (String warning : result.warnings()) {
                    if (warning != null && !warning.isBlank() && !warnings.contains(warning)) {
                        warnings.add(warning);
                    }
                }
            }
        }
        if (!warnings.isEmpty()) summary.put("warnings", warnings);
    }

    private MetricComputationResult computeTransitLatencyMetric(MetricConfig config) {
        Map<String, Object> params = config.templateParams() != null ? config.templateParams() : Map.of();
        int maxRows = getIntParam(params, "maxRowsPerSide", DEFAULT_TEMPLATE_MAX_ROWS);
        long timeoutMs = getLongParam(params, "timeoutMs", DEFAULT_TEMPLATE_TIMEOUT_MS);
        // The most recent records, not the oldest — see DEFAULT_LATENCY_READ_MODE.
        String readMode = getStringParam(params, "readMode", DEFAULT_LATENCY_READ_MODE);

        /*
         * A row cap is not a window, and on two topics it is not even one window.
         *
         * maxRowsPerSide reads the last N records of each side, so two topics of different
         * throughputs are read over two different stretches of time — ten thousand records is an
         * hour of the source and four minutes of the target — and the pairs that survive are the
         * ones whose two halves happened to fall in the overlap. What that costs is not the
         * average, which is computed over real pairs, but the matchRate beside it: the rate is
         * depressed by the misalignment exactly as it is by a genuine loss, and those send an
         * operator to opposite places. windowMs reads both sides from the *same instant*,
         * computed once here rather than per read, so the two windows are the same window and the
         * rate means what it says. Absent, everything below is unchanged.
         */
        long windowMs = getLongParam(params, "windowMs", 0L);
        Long windowFromMs = null;
        if (windowMs > 0) {
            windowFromMs = System.currentTimeMillis() - windowMs;
            readMode = FlinkSqlService.sinceReadMode(windowFromMs);
        }

        String sourceSql = requireParam(params, "sourceSql");
        String targetSql = requireParam(params, "targetSql");
        QueryResult sourceResult =
            executeMetricQuery(sourceSql, maxRows, timeoutMs, readMode, isSingleTableRead(sourceSql));
        if (sourceResult.error() != null) {
            return MetricComputationResult.error("Source query: " + sourceResult.error());
        }
        QueryResult targetResult =
            executeMetricQuery(targetSql, maxRows, timeoutMs, readMode, isSingleTableRead(targetSql));
        if (targetResult.error() != null) {
            return MetricComputationResult.error("Target query: " + targetResult.error());
        }

        List<CorrelationEvent> sourceEvents = extractCorrelationEvents(sourceResult.rows(), "sourceSql");
        List<CorrelationEvent> targetEvents = extractCorrelationEvents(targetResult.rows(), "targetSql");
        if (sourceEvents.isEmpty() || targetEvents.isEmpty()) {
            // Which side, and over what — an empty topic, a read that covered none of it and a
            // projection missing its two columns are three states, and one message named none.
            String which = sourceEvents.isEmpty() && targetEvents.isEmpty() ? "Neither query"
                : sourceEvents.isEmpty() ? "The source query" : "The target query";
            return MetricComputationResult.error(which + " yielded a row carrying both match_key and "
                + "event_time, over at most " + maxRows + " row(s) read " + describeReadEnd(readMode)
                + ". Either the read covered no message, or the projection does not alias those two "
                + "columns.");
        }

        Map<String, Deque<Long>> targetsByKey = new HashMap<>();
        for (CorrelationEvent event : targetEvents) {
            targetsByKey
                .computeIfAbsent(event.matchKey(), ignored -> new ArrayDeque<>())
                .addLast(event.eventTime());
        }

        List<LatencyObservation> observations = new ArrayList<>();
        int unmatchedSourceCount = 0;
        int outOfOrderCount = 0;
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
                // A target stamped before its own source: two producers' clocks disagreeing, or
                // an event back-dated on the way. It is dropped either way — a negative latency is
                // not a latency — but it is a finding about the estate, so it is counted rather
                // than absorbed. ProcessModelBuilder reports the same thing as outOfOrderCount and
                // Stream Flow draws it as a dashed red edge.
                candidates.removeFirst();
                outOfOrderCount++;
            }
            if (candidates.isEmpty()) {
                unmatchedSourceCount++;
                continue;
            }
            long targetTs = candidates.removeFirst();
            observations.add(new LatencyObservation(sourceEvent.eventTime(), targetTs - sourceEvent.eventTime()));
        }
        int unmatchedTargetCount = targetsByKey.values().stream().mapToInt(Deque::size).sum();

        if (observations.isEmpty()) {
            return MetricComputationResult.error("No correlated messages found between the source and "
                + "target queries: " + sourceEvents.size() + " source event(s) and " + targetEvents.size()
                + " target event(s) were read and none share a match_key with a later timestamp"
                + (outOfOrderCount > 0
                    ? ", though " + outOfOrderCount + " target(s) did match a key while being stamped "
                      + "*before* their source, which is a clock disagreement rather than a miss."
                    : "."));
        }

        // In event-time order for what follows: the distribution dedup below is positional unless
        // a row says when it was observed, and a list re-sorted by match key on every cycle is the
        // one thing that assumption cannot survive.
        observations.sort(Comparator.comparingLong(LatencyObservation::sourceEventTime));
        List<Double> latencies = observations.stream().map(LatencyObservation::latencyMs).toList();

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
            for (LatencyObservation observation : observations) {
                Map<String, Object> row = new LinkedHashMap<>(sharedLabels);
                row.put("metric_value", observation.latencyMs());
                // Not a label — see OBSERVED_AT_COLUMN. It is what lets the distribution record
                // each observation once across refreshes whose window slides.
                row.put(OBSERVED_AT_COLUMN, observation.sourceEventTime());
                rows.add(row);
            }
        }

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("matchedCount", latencies.size());
        summary.put("unmatchedSourceCount", unmatchedSourceCount);
        /*
         * The rate is what stops the average being read as a verdict on the pipeline.
         *
         * A source event whose target never arrived contributes nothing to the value, so when a
         * downstream stage stalls the slow pairs stop being pairs and the published latency is
         * that of whatever still completes: the metric *improves* as the pipeline breaks. At the
         * limit one message getting through fast reads as a perfectly healthy hop. The rate is
         * also exported as a series of its own (MATCH_RATE_SERIES), because a figure that only
         * exists in a summary nobody alerts on cannot correct the figure that is alerted on.
         */
        summary.put("unmatchedTargetCount", unmatchedTargetCount);
        summary.put("outOfOrderCount", outOfOrderCount);
        summary.put("matchRate", latencies.size() / (double) (latencies.size() + unmatchedSourceCount));
        summary.put("avgLatencyMs", avgLatencyMs);
        summary.put("p95LatencyMs", percentile(latencies, 0.95));
        summary.put("maxLatencyMs", latencies.stream().mapToDouble(Double::doubleValue).max().orElse(avgLatencyMs));
        // What the two reads covered, and what they dropped on the way: a row without both
        // columns is skipped in silence, so a projection that half works looks like a healthy run.
        summary.put("sourceRowsRead", sourceResult.rows().size());
        summary.put("sourceEventsUsed", sourceEvents.size());
        summary.put("targetRowsRead", targetResult.rows().size());
        summary.put("targetEventsUsed", targetEvents.size());
        summary.put("sourceEngine", sourceResult.engine());
        summary.put("targetEngine", targetResult.engine());
        summary.put("scopeNote", latencyScopeNote(maxRows, readMode, windowMs, windowFromMs));
        if (windowMs > 0) {
            summary.put("windowMs", windowMs);
            summary.put("windowFromMs", windowFromMs);
        }
        addScanWarnings(summary, sourceResult, targetResult);

        return new MetricComputationResult(rows, avgLatencyMs, null, summary);
    }

    /** One template read, with the scan option this stack can express. */
    private QueryResult executeMetricQuery(String sql, int maxRows, long timeoutMs, String readMode,
                                           boolean directRead) {
        return runMetricQuery(injectScanHint(sql, scanHint()), maxRows, timeoutMs, readMode, directRead);
    }

    /**
     * Two template reads as a pair, so the engine can serve both from one topic read when they are
     * two counts over the same topic.
     *
     * <p>The per-cycle memoization keys on the SQL, so it never brought two different WHERE
     * clauses over one topic together; sharing is decided inside the engine, on what the reads
     * turn out to be. The cache is still consulted first and filled afterwards — a second metric
     * posing one of these statements in the same cycle must still be free.
     */
    private FlinkSqlService.QueryPair executeMetricPair(String firstSql, String secondSql,
                                                        int maxRows, long timeoutMs, String readMode) {
        String first = injectScanHint(firstSql, scanHint());
        String second = injectScanHint(secondSql, scanHint());
        boolean firstDirect = isSingleTableRead(firstSql);
        boolean secondDirect = isSingleTableRead(secondSql);
        Map<String, QueryResult> cycleCache = refreshCycleQueryCache.get();
        if (cycleCache != null) {
            QueryResult a = cycleCache.get(cacheKey(first, maxRows, timeoutMs, readMode, firstDirect));
            QueryResult b = cycleCache.get(cacheKey(second, maxRows, timeoutMs, readMode, secondDirect));
            if (a != null && b != null) return new FlinkSqlService.QueryPair(a, b, false);
        }
        FlinkSqlService.QueryPair pair = flinkSqlService.executeSqlPair(
            firstDirect ? QueryRequest.directSql(first, maxRows, timeoutMs, readMode)
                        : QueryRequest.sql(first, maxRows, timeoutMs, readMode),
            secondDirect ? QueryRequest.directSql(second, maxRows, timeoutMs, readMode)
                         : QueryRequest.sql(second, maxRows, timeoutMs, readMode));
        if (cycleCache != null) {
            cycleCache.put(cacheKey(first, maxRows, timeoutMs, readMode, firstDirect), pair.first());
            cycleCache.put(cacheKey(second, maxRows, timeoutMs, readMode, secondDirect), pair.second());
        }
        return pair;
    }

    private static String cacheKey(String sql, int maxRows, long timeoutMs, String readMode,
                                   boolean directRead) {
        return sql + '|' + maxRows + '|' + timeoutMs + '|' + readMode + '|' + directRead;
    }

    private QueryResult runMetricQuery(String sql, int maxRows, long timeoutMs, String readMode,
                                       boolean directRead) {
        Map<String, QueryResult> cycleCache = refreshCycleQueryCache.get();
        if (cycleCache == null) return submitMetricQuery(sql, maxRows, timeoutMs, readMode, directRead);
        String key = cacheKey(sql, maxRows, timeoutMs, readMode, directRead);
        return cycleCache.computeIfAbsent(key,
            k -> submitMetricQuery(sql, maxRows, timeoutMs, readMode, directRead));
    }

    private QueryResult submitMetricQuery(String sql, int maxRows, long timeoutMs, String readMode,
                                          boolean directRead) {
        return flinkSqlService.executeSql(directRead
            ? QueryRequest.directSql(sql, maxRows, timeoutMs, readMode)
            : QueryRequest.sql(sql, maxRows, timeoutMs, readMode));
    }

    /**
     * One side of a two-query metric: the value, or the reason there is none.
     *
     * @param capped the read stopped on the direct reader's aggregate ceiling, so the value is a
     *               floor rather than a total — which a comparison must refuse rather than publish.
     */
    private record SideRead(Double value, String error, boolean capped) {
        static SideRead failed(String error) { return new SideRead(null, error, false); }
    }

    /**
     * The value of an aggregate side, and it is the <b>last</b> numeric row rather than the first.
     *
     * <p>On the direct reader an aggregate is one row, so the two are the same. On the Flink
     * planner they are not: the environment is streaming, so {@code COUNT(*)} is a retract
     * changelog — {@code +I(1)}, {@code -U(1) +U(2)}, {@code -U(2) +U(3)} … — whose rows arrive in
     * order and whose {@code RowKind} the collector drops. Taking the first numeric value read
     * {@code 1} off any topic large enough to fill the row budget before the scan ended, so a
     * {@code PERCENT_GAP} between two such topics published {@code 0.0}: no loss, on the alarm
     * whose whole purpose is to report loss, and precisely on the topics worth alarming about.
     *
     * <p>The last row is the final aggregate only if the changelog is <em>complete</em>, which is
     * why a result that filled the row budget is refused instead: the last row of a truncated
     * changelog is a partial count that looks exactly like a total.
     */
    /**
     * La lecture s'est-elle arrêtée <em>au milieu</em> d'un changelog ?
     *
     * <p>Un {@code COUNT(*)} en streaming rend un changelog rétractable : la dernière ligne n'est
     * l'agrégat final que si le changelog est <em>complet</em>. Arrêtée sur le plafond de lignes,
     * sa dernière ligne est un compte partiel qui ressemble exactement à un total.
     *
     * <p>Une seule définition, parce que deux endroits la lisent — l'écart entre deux requêtes et
     * le SQL libre — et que deux copies de « FLINK et lignes >= plafond » finissent par diverger.
     * Le lecteur direct est hors sujet : il rend une ligne pour un agrégat, jamais un changelog.
     */
    private static boolean isTruncatedChangelog(QueryResult result, int maxRows) {
        return "FLINK".equals(result.engine()) && result.rows().size() >= maxRows;
    }

    private SideRead aggregateValue(QueryResult result, int maxRows, String side) {
        if (result.rows().isEmpty()) {
            return SideRead.failed("The " + side + " query returned no row — the topic may hold "
                + "nothing, or its table may not resolve on this cluster.");
        }
        if (isTruncatedChangelog(result, maxRows)) {
            return SideRead.failed("The " + side + " query filled its row budget (" + maxRows
                + " rows), so the aggregate it returned is a partial count taken mid-changelog "
                + "rather than a total. Raise maxRowsPerSide, or write a query the direct reader "
                + "can answer (a single table, no join and no subquery).");
        }
        Double value = lastNumericMetricValue(result.rows());
        if (value == null) {
            return SideRead.failed("The " + side + " query returned rows but no numeric "
                + "metric_value — alias the aggregate, as in COUNT(*) AS metric_value.");
        }
        return new SideRead(value, null, isAggregateScanCapped(result));
    }

    /** The direct reader says so in its warnings when an aggregate stopped on its own ceiling. */
    private boolean isAggregateScanCapped(QueryResult result) {
        return result.warnings() != null && result.warnings().stream()
            .anyMatch(w -> w != null && w.startsWith(FlinkSqlService.AGGREGATE_SCAN_CAPPED));
    }

    /** The final value of a changelog, and the only value of a single-row aggregate. */
    private Double lastNumericMetricValue(List<Map<String, Object>> rows) {
        Double last = null;
        for (Map<String, Object> row : rows) {
            Double value = extractValue(row);
            if (value != null) last = value;
        }
        return last;
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

    /** Epoch seconds/millis, ISO-8601 or a space-separated local date-time — see {@link EventTime}. */
    private Long toEpochMillis(Object value) {
        return EventTime.toEpochMillis(value);
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
        try {
            return Integer.parseInt(String.valueOf(value).trim());
        } catch (NumberFormatException e) {
            // "For input string: 10k" names the string and not the setting, and reached the
            // operator as the metric's error rather than as a refused save.
            throw new IllegalArgumentException(
                "Template parameter " + key + " must be a whole number, not '" + value + "'");
        }
    }

    private long getLongParam(Map<String, Object> params, String key, long defaultValue) {
        Object value = params.get(key);
        if (value == null || String.valueOf(value).isBlank()) return defaultValue;
        try {
            return Long.parseLong(String.valueOf(value).trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(
                "Template parameter " + key + " must be a whole number, not '" + value + "'");
        }
    }

    private void addIfPresent(Map<String, Object> target, String key, Object value) {
        if (value != null && !String.valueOf(value).isBlank()) {
            target.put(key, value);
        }
    }

    @Scheduled(fixedRateString = "${explorer.metrics-refresh-rate:30000}")
    public void refreshMetrics() {
        // Serialize with on-demand single-metric refreshes so counter deltas / gauge holders are
        // never mutated concurrently (the processing path assumes a single refresh thread).
        refreshLock.lock();
        try {
            beginRefreshCycle();
            try {
                doRefreshMetrics();
            } finally {
                endRefreshCycle();
            }
        } finally {
            refreshLock.unlock();
        }
    }

    private void beginRefreshCycle() {
        refreshCycleQueryCache.set(new HashMap<>());
        refreshCycleLabelCache.set(new HashMap<>());
    }

    private void endRefreshCycle() {
        refreshCycleQueryCache.remove();
        refreshCycleLabelCache.remove();
    }

    /**
     * Recompute a single metric immediately (on-demand "refresh now"), reusing the exact same
     * pipeline as the scheduled cycle. Returns the updated config, or empty if the id is unknown.
     */
    public Optional<MetricConfig> refreshMetric(String id) {
        if (!metrics.containsKey(id)) return Optional.empty();
        refreshLock.lock();
        try {
            MetricConfig config = metrics.get(id);
            if (config == null) return Optional.empty();
            beginRefreshCycle();
            try {
                // Deliberately not subject to refreshIntervalMs: this is somebody pressing
                // Refresh, and an explicit gesture is never a cadence to be rationed.
                lastRefreshStartedAt.put(id, System.currentTimeMillis());
                refreshSingleMetric(id, config);
            } finally {
                endRefreshCycle();
            }
            return Optional.ofNullable(metrics.get(id));
        } finally {
            refreshLock.unlock();
        }
    }

    private void doRefreshMetrics() {
        long cycleStart = System.currentTimeMillis();
        int refreshed = 0;
        int skipped = 0;
        for (Map.Entry<String, MetricConfig> entry : metrics.entrySet()) {
            if (!isDue(entry.getKey(), entry.getValue(), cycleStart)) {
                skipped++;
                continue;
            }
            lastRefreshStartedAt.put(entry.getKey(), System.currentTimeMillis());
            refreshSingleMetric(entry.getKey(), entry.getValue());
            refreshed++;
        }
        recordCycleDuration(System.currentTimeMillis() - cycleStart, refreshed, skipped);
    }

    /**
     * Whether this metric wants to run on this tick.
     *
     * <p>Every metric was recomputed on every tick, which is the right default for a gauge over a
     * cheap query and the wrong one for a two-query template: those read two topics, and asking
     * that of a broker every thirty seconds because a single-row gauge beside them wants it is how
     * the refresh loop becomes the most expensive thing this application does. {@code
     * refreshIntervalMs} is the metric's own cadence, and it can only <em>slow</em> one down: the
     * loop's tick is the floor, so a value below it changes nothing — the form says so rather than
     * accepting a number that cannot be honoured.
     *
     * <p>Skipping touches no state. The gauge keeps the value it was last measured at, which is
     * correct — it <em>was</em> measured, just not now — and {@link #LAST_SUCCESS_SERIES} is what
     * dates it, so an alert can already tell a held value from a fresh one.
     */
    private boolean isDue(String id, MetricConfig config, long now) {
        long interval = configuredRefreshIntervalMs(config);
        if (interval <= 0) return true;
        Long last = lastRefreshStartedAt.get(id);
        if (last == null) return true;
        // A tolerance, because the cadence is a multiple of the loop's tick whatever is asked for:
        // without it a 5-minute interval polled every 30 s fires at 5 min 30 s, every time.
        long tolerance = Math.max(1_000L, interval / 10);
        return now - last >= interval - tolerance;
    }

    private long configuredRefreshIntervalMs(MetricConfig config) {
        Map<String, Object> params = config.templateParams();
        return params == null ? 0L : getLongParam(params, "refreshIntervalMs", 0L);
    }

    /**
     * Publish what the cycle cost, and say once when it outlasts its own tick.
     *
     * <p>The cost of the refresh loop was the one thing about it nobody could see: it is single
     * threaded by design — the meter state assumes one writer — so a cycle that outlasts its tick
     * does not pile up threads, it simply runs back to back with no idle, and the only symptom is
     * a broker doing more work than anyone asked it for. More threads would be the wrong answer;
     * a number and a per-metric cadence are the right ones, and this is the number.
     */
    private void recordCycleDuration(long durationMs, int refreshed, int skipped) {
        refreshDurationHolder.set(durationMs / 1000.0);
        if (!refreshDurationRegistered) {
            Gauge.builder(REFRESH_DURATION_SERIES, refreshDurationHolder, AtomicReference::get)
                .description("Duration of the last metric refresh cycle, in seconds")
                .register(meterRegistry);
            refreshDurationRegistered = true;
        }
        if (metricsRefreshRateMs > 0 && durationMs > metricsRefreshRateMs && !refreshOverrunReported) {
            refreshOverrunReported = true;
            log.warn("Metric refresh cycle took {} ms, longer than its own {} ms tick "
                    + "({} metric(s) refreshed, {} skipped by their own interval). The loop is "
                    + "single threaded, so it now runs back to back: give the expensive metrics a "
                    + "refreshIntervalMs of their own, or count by offsets where both sides are "
                    + "whole-topic counts. Said once per process.",
                durationMs, metricsRefreshRateMs, refreshed, skipped);
        }
    }

    private void refreshSingleMetric(String id, MetricConfig config) {
        try {
            MetricConfig normalized = normalizeMetric(config);
            validateMetric(normalized);

            if (MetricExecutionMode.FLINK_MANAGED_JOB.name().equals(normalized.executionMode())) {
                updateMetricState(id, null, null, buildManagedJobPlannedSummary(normalized));
                return;
            }

            executeCreateTableIfPresent(normalized);

            MetricComputationResult result = computeMetric(normalized, false);
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

        Double primaryValue = (type.equals("HISTOGRAM") || type.equals("SUMMARY"))
            ? recordDistributionRows(metricId, config, rows, configuredLabels,
                                     type.equals("HISTOGRAM"), displayValueOverride)
            : processScalarRows(metricId, config, rows, configuredLabels,
                                 type.equals("COUNTER"), displayValueOverride);

        pruneStaleSeries(metricId, liveLabelKeys(rows, configuredLabels));

        if (primaryValue != null) {
            updateHistory(metricId, primaryValue);
            // After updateHistory, which is what sets the length every series is aligned to.
            updateComponentHistory(metricId, summary);
            updateMetricState(metricId, primaryValue, null, summary);
            publishCompanions(metricId, config, summary);
        }
    }

    /**
     * The two gauges that describe the metric rather than its rows.
     *
     * <p>{@link #LAST_SUCCESS_SERIES} is what makes every other gauge here readable. A refresh
     * that fails keeps the previous value — deliberately, since a broker blip must not read as
     * "the backlog cleared" — but a frozen gauge and a fresh one are indistinguishable from
     * outside, so an alert on {@code value > N} fires the same way whether the condition is real
     * and stuck or simply no longer measured. Set only on a cycle that actually produced a value,
     * it lets the alert require both:
     * {@code explorer_metric_gauge > N and time() - explorer_metric_last_success_timestamp_seconds < 120}.
     * A timestamp rather than a boolean: same cardinality, and it carries <em>how</em> stale.
     * {@code ConsumerLagMetrics} carries the same series for the same reason.
     *
     * <p>{@link #MATCH_RATE_SERIES} is published for any metric whose summary reports one, which
     * today is the transit latency — see the note beside {@code matchRate}.
     */
    private void publishCompanions(String metricId, MetricConfig config, Map<String, Object> summary) {
        publishCompanionGauge(metricId, config, LAST_SUCCESS_SERIES,
            "Epoch seconds of the last refresh that produced a value for this metric",
            System.currentTimeMillis() / 1000.0);
        if (summary != null && summary.get("matchRate") instanceof Number rate) {
            publishCompanionGauge(metricId, config, MATCH_RATE_SERIES,
                "Share of source events this metric could pair with a target event (0..1)",
                rate.doubleValue());
        }
        // Only where the type carries no quantiles of its own — see LATENCY_P95_SERIES.
        String type = config.type() == null ? "GAUGE" : config.type().toUpperCase(Locale.ROOT);
        if (summary != null && !"HISTOGRAM".equals(type) && !"SUMMARY".equals(type)
            && summary.get("p95LatencyMs") instanceof Number p95) {
            publishCompanionGauge(metricId, config, LATENCY_P95_SERIES,
                "95th percentile of the correlated latency, in milliseconds",
                p95.doubleValue());
        }
    }

    private void publishCompanionGauge(String metricId, MetricConfig config, String name,
                                       String description, double value) {
        companionHolders
            .computeIfAbsent(metricId, k -> new ConcurrentHashMap<>())
            .computeIfAbsent(name, k -> {
                AtomicReference<Double> ref = new AtomicReference<>(value);
                Gauge gauge = Gauge.builder(name, ref, AtomicReference::get)
                    .description(description)
                    // The metric's own identity and nothing from its rows: a companion describes
                    // the measurement, so it must not multiply with the label series.
                    .tags(List.of(
                        Tag.of("metric_id", metricId),
                        Tag.of("metric_name", config.name() == null ? "" : config.name()),
                        Tag.of("metric_type", config.type() == null ? "GAUGE" : config.type())))
                    .register(meterRegistry);
                companionMeters.computeIfAbsent(metricId, x -> new ConcurrentHashMap<>()).put(name, gauge);
                return ref;
            })
            .set(value);
    }

    /** The set of label series produced by this cycle's rows (rows without a numeric value are ignored). */
    private Set<String> liveLabelKeys(List<Map<String, Object>> rows, Map<String, String> configuredLabels) {
        Set<String> keys = new HashSet<>();
        for (Map<String, Object> row : rows) {
            if (extractValue(row) == null) continue;
            keys.add(buildLabelKey(row, configuredLabels));
        }
        return keys;
    }

    /**
     * Drop Micrometer series (and their internal state) whose label key did not appear in the
     * latest successful refresh. Without this, a GROUP BY value that stops occurring — or a label
     * sourced from the latest Kafka message that keeps changing — leaves a series registered
     * forever, frozen at its last value and inflating cardinality (B4). Only invoked with the live
     * key set of a non-empty successful cycle, so a transiently empty/errored cycle prunes nothing.
     */
    private void pruneStaleSeries(String metricId, Set<String> liveKeys) {
        if (liveKeys.isEmpty()) return;   // no successful rows this cycle — keep existing series

        removeStaleMeters(gaugeMeters.get(metricId), liveKeys);
        retainLiveKeys(gaugeHolders.get(metricId), liveKeys);

        removeStaleMeters(counterMeters.get(metricId), liveKeys);
        retainLiveKeys(lastCounterValues.get(metricId), liveKeys);

        removeStaleMeters(distributionMeters.get(metricId), liveKeys);
        retainLiveKeys(distributionRecordedCounts.get(metricId), liveKeys);
        retainLiveKeys(distributionWatermarks.get(metricId), liveKeys);
    }

    private void removeStaleMeters(Map<String, ? extends Meter> series, Set<String> liveKeys) {
        if (series == null) return;
        for (String key : new ArrayList<>(series.keySet())) {
            if (!liveKeys.contains(key)) {
                Meter meter = series.remove(key);
                if (meter != null) meterRegistry.remove(meter);
            }
        }
    }

    private void retainLiveKeys(Map<String, ?> state, Set<String> liveKeys) {
        if (state != null) state.keySet().retainAll(liveKeys);
    }

    /** GAUGE / COUNTER: each row is idempotent (set) or delta-tracked, so re-scans are safe. */
    private Double processScalarRows(String metricId, MetricConfig config, List<Map<String, Object>> rows,
                                     Map<String, String> configuredLabels, boolean counter,
                                     Double displayValueOverride) {
        if (counter) {
            return processCounterRows(metricId, config, rows, configuredLabels, displayValueOverride);
        }
        // GAUGE — point-in-time; last row wins per label series.
        Double primaryValue = displayValueOverride;
        for (Map<String, Object> row : rows) {
            Double value = extractValue(row);
            if (value == null) continue;

            List<Tag> tags = buildTags(metricId, config, row, configuredLabels);
            String labelKey = buildLabelKey(row, configuredLabels);
            processGauge(metricId, labelKey, tags, value);

            if (primaryValue == null) primaryValue = value;
        }
        return primaryValue;
    }

    /**
     * COUNTER: a counter's SQL yields the current cumulative total per label series. Rows that
     * map to the same label key are summed into one cumulative value <em>before</em> the delta is
     * computed. Previously each such row drove a separate delta against the shared last-seen
     * value, so several independent groups collapsed onto one key (e.g. a GROUP BY whose grouping
     * column is not selected → all rows share the empty key) incremented the counter by only the
     * last row's total, silently discarding the rest (B5). Summing is order-independent and, for a
     * cumulative counter, reconstitutes the intended series total. The single-row case is
     * unchanged (sum of one value == that value).
     */
    private Double processCounterRows(String metricId, MetricConfig config, List<Map<String, Object>> rows,
                                      Map<String, String> configuredLabels, Double displayValueOverride) {
        Map<String, Double>    totalsByLabel = new LinkedHashMap<>();
        Map<String, List<Tag>> tagsByLabel   = new LinkedHashMap<>();
        Double primaryValue = displayValueOverride;

        for (Map<String, Object> row : rows) {
            Double value = extractValue(row);
            if (value == null) continue;
            String labelKey = buildLabelKey(row, configuredLabels);
            totalsByLabel.merge(labelKey, value, Double::sum);
            tagsByLabel.computeIfAbsent(labelKey, k -> buildTags(metricId, config, row, configuredLabels));
            if (primaryValue == null) primaryValue = value;
        }

        totalsByLabel.forEach((labelKey, total) ->
            processCounter(metricId, labelKey, tagsByLabel.get(labelKey), total));
        return primaryValue;
    }

    /**
     * HISTOGRAM / SUMMARY: record only observations not already recorded in earlier refresh
     * cycles. Because the scheduled refresh re-scans a bounded slice of the topic every cycle,
     * recording every returned row unconditionally would re-count the same messages each time —
     * inflating _count/_sum and skewing the distribution toward whatever the window holds (B2).
     *
     * <p>There are two schemes, and which one applies is decided by the rows themselves.
     *
     * <p><b>By observation time</b>, when every row of a label series carries
     * {@link #OBSERVED_AT_COLUMN}: record those newer than the newest already recorded, then
     * advance the watermark. This is the only scheme that survives a <em>sliding</em> window —
     * a latency metric now reads the most recent records, so each cycle drops observations off
     * the front and gains others at the back while the count stays the same, which the positional
     * scheme reads as "nothing new" for ever. It is also what removes the bias the positional
     * scheme had here: the rows used to be ordered by match key, so the suffix beyond the recorded
     * count was the observations whose key sorted highest — a sample selected by an attribute
     * unrelated to the measurement, published as a p95. Two observations sharing a millisecond
     * across two cycles are recorded once, which is the safe direction for a distribution that
     * must never be inflated.
     *
     * <p><b>By position</b> otherwise, unchanged: an earliest-offset scan yields an append-only
     * stream in a stable order, so only the suffix beyond the previously recorded count is new. A
     * series that shrinks (retention trim / stream reset) resets its watermark to the current size
     * without re-recording, so the accumulated summary is never inflated.
     *
     * @return the first observed value (for display / history), or the override when provided.
     */
    private Double recordDistributionRows(String metricId, MetricConfig config,
                                          List<Map<String, Object>> rows,
                                          Map<String, String> configuredLabels,
                                          boolean histogram, Double displayValueOverride) {
        Map<String, Integer> recordedCounts =
            distributionRecordedCounts.computeIfAbsent(metricId, k -> new ConcurrentHashMap<>());
        Map<String, Long> watermarks =
            distributionWatermarks.computeIfAbsent(metricId, k -> new ConcurrentHashMap<>());

        // Group ordered values (and a representative tag set) per label series for this cycle.
        // Same labelKey ⟺ same tag set by construction, so the first row's tags are canonical.
        Map<String, List<Double>> valuesByLabel = new LinkedHashMap<>();
        Map<String, List<Long>>   stampsByLabel = new LinkedHashMap<>();
        Map<String, List<Tag>>    tagsByLabel   = new LinkedHashMap<>();
        Double primaryValue = displayValueOverride;

        for (Map<String, Object> row : rows) {
            Double value = extractValue(row);
            if (value == null) continue;
            String labelKey = buildLabelKey(row, configuredLabels);
            valuesByLabel.computeIfAbsent(labelKey, k -> new ArrayList<>()).add(value);
            stampsByLabel.computeIfAbsent(labelKey, k -> new ArrayList<>()).add(observedAt(row));
            tagsByLabel.computeIfAbsent(labelKey, k -> buildTags(metricId, config, row, configuredLabels));
            if (primaryValue == null) primaryValue = value;
        }

        valuesByLabel.forEach((labelKey, values) -> {
            List<Tag> tags = tagsByLabel.get(labelKey);
            List<Long> stamps = stampsByLabel.get(labelKey);

            if (!stamps.contains(null)) {
                long watermark = watermarks.getOrDefault(labelKey, Long.MIN_VALUE);
                long newest = watermark;
                for (int i = 0; i < values.size(); i++) {
                    if (stamps.get(i) <= watermark) continue;
                    if (histogram) processHistogram(metricId, labelKey, tags, values.get(i));
                    else           processSummary(metricId, labelKey, tags, values.get(i));
                    newest = Math.max(newest, stamps.get(i));
                }
                watermarks.put(labelKey, newest);
                return;
            }

            int alreadyRecorded = recordedCounts.getOrDefault(labelKey, 0);
            // On a shrink, skip recording (startIndex == size) and just reset the watermark —
            // never re-record the surviving prefix, which would inflate the accumulated summary.
            int startIndex = values.size() < alreadyRecorded ? values.size() : alreadyRecorded;
            for (int i = startIndex; i < values.size(); i++) {
                if (histogram) processHistogram(metricId, labelKey, tags, values.get(i));
                else           processSummary(metricId, labelKey, tags, values.get(i));
            }
            recordedCounts.put(labelKey, values.size());
        });

        return primaryValue;
    }

    /** When the row says it was observed, or null when it does not say. */
    private Long observedAt(Map<String, Object> row) {
        Object value = row.get(OBSERVED_AT_COLUMN);
        if (value instanceof Number n) return n.longValue();
        if (value == null) return null;
        try {
            return Long.parseLong(String.valueOf(value).trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    // GAUGE → Gauge (current point-in-time value)
    private void processGauge(String metricId, String labelKey, List<Tag> tags, double value) {
        gaugeHolders
            .computeIfAbsent(metricId, k -> new ConcurrentHashMap<>())
            .computeIfAbsent(labelKey, k -> {
                AtomicReference<Double> ref = new AtomicReference<>(0.0);
                Gauge gauge = Gauge.builder("explorer_metric_gauge", ref, AtomicReference::get)
                    .description("Flink SQL gauge metric")
                    .tags(tags)
                    .register(meterRegistry);
                gaugeMeters.computeIfAbsent(metricId, x -> new ConcurrentHashMap<>()).put(labelKey, gauge);
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
            if (!isReservedColumn(e.getKey())) {
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
            if (!isReservedColumn(e.getKey())) {
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
            Map<String, String> extractedFields = latestLeafFields(config.labelTopic());
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

    /** Latest-message leaf fields for a topic, memoized per refresh cycle (see refreshCycleLabelCache). */
    private Map<String, String> latestLeafFields(String topic) {
        Map<String, Map<String, String>> cache = refreshCycleLabelCache.get();
        if (cache != null) {
            Map<String, String> cached = cache.get(topic);
            if (cached != null) return cached;
        }
        Map<String, String> fields = computeLatestLeafFields(topic);
        if (cache != null) cache.put(topic, fields);
        return fields;
    }

    private Map<String, String> computeLatestLeafFields(String topic) {
        Optional<KafkaMessage> latestMessage = kafkaAdminService.getLatestMessage(topic);
        if (latestMessage.isEmpty() || latestMessage.get().value() == null || latestMessage.get().value().isBlank()) {
            return Map.of();
        }
        return messageFieldExtractorService.extractLeafFields(latestMessage.get().value());
    }

    private void updateHistory(String id, Double value) {
        LinkedList<Double> history = historyMap.computeIfAbsent(id, k -> new LinkedList<>());
        history.addLast(value);
        if (history.size() > MAX_HISTORY) history.removeFirst();
    }

    /**
     * The summary keys worth keeping a series of, in the order a reader should draw them.
     *
     * <p>A closed list rather than "every number in the summary": most of what a summary carries is
     * scope — rows read, partitions measured, a match rate — and a series of those is noise on a
     * card. These are the values the metric's own number is <em>made of</em>, which is what
     * {@code history} cannot show for a two-query template: on a gap it holds the difference, and
     * the two counts are what an operator needs to see move.
     */
    private static final List<String> COMPONENT_SERIES = List.of(
        "leftValue", "rightValue",
        "avgLatencyMs", "p95LatencyMs", "maxLatencyMs",
        "maxLagMs", "avgLagMs");

    /**
     * Append this refresh's components, keeping every series exactly as long as {@code history}.
     *
     * <p>Two rules, and they are the same rule twice. A key this refresh did not produce appends
     * {@code null}, never {@code 0} — a zero draws a fall that never happened, and "not measured"
     * and "measured as nothing" are the distinction this codebase keeps everywhere else. And a key
     * seen for the <em>first</em> time is back-filled with nulls to the current length, so index
     * <i>i</i> is the same refresh in every series however the metric was edited in between. That
     * also makes the whole thing self-healing: a metric switched from one template to another
     * simply flatlines its old series into nulls until they scroll out of the window, with no
     * shape-change detection to get wrong.
     */
    private void updateComponentHistory(String id, Map<String, Object> summary) {
        Map<String, LinkedList<Double>> series =
            componentHistoryMap.computeIfAbsent(id, k -> new ConcurrentHashMap<>());
        int length = historyMap.getOrDefault(id, new LinkedList<>()).size();

        Map<String, Double> observed = new LinkedHashMap<>();
        if (summary != null) {
            for (String key : COMPONENT_SERIES) {
                if (summary.get(key) instanceof Number n && Double.isFinite(n.doubleValue())) {
                    observed.put(key, n.doubleValue());
                }
            }
        }
        // Nothing to track and nothing tracked yet: do not mint empty series on every gauge.
        if (observed.isEmpty() && series.isEmpty()) return;

        for (String key : observed.keySet()) {
            series.computeIfAbsent(key, k -> {
                LinkedList<Double> backfilled = new LinkedList<>();
                for (int i = 0; i < length - 1; i++) backfilled.addLast(null);
                return backfilled;
            });
        }
        for (Map.Entry<String, LinkedList<Double>> entry : series.entrySet()) {
            LinkedList<Double> values = entry.getValue();
            values.addLast(observed.get(entry.getKey()));
            while (values.size() > MAX_HISTORY) values.removeFirst();
            while (values.size() > length) values.removeFirst();
        }
    }

    /** A snapshot of the component series, for the record this refresh writes. */
    private Map<String, List<Double>> componentHistorySnapshot(String id) {
        Map<String, LinkedList<Double>> series = componentHistoryMap.get(id);
        if (series == null || series.isEmpty()) return Map.of();
        Map<String, List<Double>> copy = new LinkedHashMap<>();
        // In the order a reader should draw them, not the map's.
        for (String key : COMPONENT_SERIES) {
            LinkedList<Double> values = series.get(key);
            if (values != null && !values.isEmpty()) copy.put(key, new ArrayList<>(values));
        }
        return copy;
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
            current.labelFields() != null ? List.copyOf(current.labelFields()) : List.of(),
            componentHistorySnapshot(id)));
    }

    // ── Kafka persistence ─────────────────────────────────────────────────────

    /**
     * Deletes a metric from the topic <em>and lets the broker reclaim it</em>.
     *
     * <p>This used to write a {@code MetricConfig(id, …, "DELETED")} — a sentinel with a non-null
     * value. The restore reads it correctly, so the metric did disappear; what could never happen
     * is compaction, which reclaims a key only on a null value. On a compacted topic every metric
     * ever deleted therefore stayed on the log for good, and "deleted" meant "hidden".
     *
     * <p>The sentinel is still understood on the way in (see {@code restoreFromKafka}), because a
     * topic written by an earlier build is exactly what this store exists to read.
     */
    private void persistTombstone(String id) {
        try {
            configProducer().send(new ProducerRecord<>(
                explorerConfig.getMetricsConfigTopic(), id, null)).get();
        } catch (Exception e) {
            log.warn("Failed to write the tombstone for metric {}: {}", LogSafe.name(id), e.toString());
            closeConfigProducer();
        }
    }

    private void persistToKafka(MetricConfig metric) {
        try {
            configProducer().send(new ProducerRecord<>(
                explorerConfig.getMetricsConfigTopic(),
                metric.id(),
                objectMapper.writeValueAsString(metric))).get();
        } catch (Exception e) {
            log.warn("Failed to persist metric config: {}", e.getMessage());
            // Drop the producer so the next attempt reconnects with fresh state/config
            closeConfigProducer();
        }
    }

    private Producer<String, String> configProducer() {
        Producer<String, String> existing = configProducer;
        if (existing != null) return existing;
        synchronized (this) {
            if (configProducer == null) {
                configProducer = createProducer();
            }
            return configProducer;
        }
    }

    /** Seam for tests: overridden to inject a MockProducer instead of a real one. */
    protected Producer<String, String> createProducer() {
        Properties props = new Properties();
        props.putAll(kafkaConfig.getKafkaProperties());
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG,   StringSerializer.class.getName());
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        return new KafkaProducer<>(props);
    }

    private synchronized void closeConfigProducer() {
        if (configProducer != null) {
            try {
                // Bounded: the no-arg close() waits indefinitely for buffered records, and
                // this runs at shutdown, when the broker is often already gone.
                configProducer.close(Duration.ofSeconds(5));
            } catch (Exception ignored) {
            }
            configProducer = null;
        }
    }

    @PreDestroy
    public void shutdown() {
        closeConfigProducer();
    }

    /** The restore runs during startup, so it is bounded on both the calls and the whole read. */
    private static final long RESTORE_BUDGET_MS = 10_000;

    /**
     * Test seam, and the reason there is one: the restore's outcome now decides whether the store
     * seeds example metrics, so it has to be drivable without a broker.
     */
    Consumer<String, String> createConsumer() {
        Properties props = new Properties();
        props.putAll(kafkaConfig.getKafkaProperties());
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG,   StringDeserializer.class.getName());
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        ExplorerConsumerGroups.configure(props, "metrics-restorer");
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        // Fail fast when the broker is unreachable — this runs during application startup, and the
        // client default of 5 s was measured at half of a boot with nothing listening. The budget
        // is shared with the other startup restore and configurable; see StartupRestore.
        props.put(ConsumerConfig.DEFAULT_API_TIMEOUT_MS_CONFIG,
            String.valueOf(startupRestore.discoveryTimeoutMs()));
        props.put(ConsumerConfig.REQUEST_TIMEOUT_MS_CONFIG,
            String.valueOf(startupRestore.requestTimeoutMs()));
        return new KafkaConsumer<>(props);
    }

    /**
     * Reads the metric configurations back, and <b>says which of the three answers it got</b>.
     *
     * <p>It used to end in {@code log.debug("Restore from Kafka failed: " + e.getMessage())}, which
     * is two defects in one line: DEBUG is no line at all on a default install, and
     * {@code getMessage()} is {@code null} for an NPE — so the commonest reason a Metrics page
     * comes up empty left the log saying either nothing or {@code null}. It also announced
     * "Restored N metric configuration(s)" after a loop that exits on its deadline just as
     * readily as on the end offsets, so a read cut in half reported its fraction as the whole.
     *
     * @return {@code true} when the topic was read to its end — which includes a topic that does
     *         not exist yet, since that is a complete answer about an empty store
     */
    boolean restoreFromKafka() {
        String topic = explorerConfig.getMetricsConfigTopic();
        String earlier = startupRestore.brokerUnreachableReason();
        if (earlier != null) {
            log.warn("Metric configurations were not restored from {}: the broker had already "
                + "failed to answer an earlier startup read ({}). The metrics configured on this "
                + "cluster are not available to this process.", topic, earlier);
            return false;
        }

        long startedAt = System.currentTimeMillis();
        try (Consumer<String, String> consumer = createConsumer()) {
            List<PartitionInfo> partitionInfos = consumer.partitionsFor(topic);
            if (partitionInfos == null || partitionInfos.isEmpty()) {
                return true; // topic does not exist yet — nothing to restore
            }
            List<TopicPartition> partitions = partitionInfos.stream()
                .map(pi -> new TopicPartition(pi.topic(), pi.partition()))
                .toList();
            consumer.assign(partitions);
            consumer.seekToBeginning(partitions);
            Map<TopicPartition, Long> endOffsets = consumer.endOffsets(partitions);
            // The cursor is seeded from where this read seeked — stated, not read back — and moves
            // only on records the loop was handed. It used to be consumer.position(), which is the
            // client's prefetch position and runs ahead of what has been delivered: the loop could
            // exit with configurations still in flight and report the fraction as the whole. See
            // TopicReadCursor.
            TopicReadCursor cursor = TopicReadCursor.of(consumer.beginningOffsets(partitions), endOffsets);

            // Poll until every partition reaches its end offset. An empty poll does NOT mean
            // the topic is exhausted (the first poll after assignment is typically empty
            // while the fetch is in flight), so completion is tracked by the cursor instead.
            long deadline = startedAt + RESTORE_BUDGET_MS;
            while (System.currentTimeMillis() < deadline && cursor.hasUnread()) {
                ConsumerRecords<String, String> records = consumer.poll(Duration.ofMillis(300));
                for (ConsumerRecord<String, String> record : records) {
                    cursor.advance(record);
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
                            record.partition(), record.offset(), SqlErrorClassifier.explain(e));
                    }
                }
            }
            boolean complete = !cursor.hasUnread();
            long ms = System.currentTimeMillis() - startedAt;
            if (!complete) {
                log.warn("Only part of {} was read: the {} ms restore budget ran out after {} ms "
                    + "with {} metric configuration(s) restored. The rest are absent from this "
                    + "process and will not be refreshed or exported.",
                    topic, RESTORE_BUDGET_MS, ms, metrics.size());
            } else {
                log.info("Restored {} metric configuration(s) from {} in {} ms",
                    metrics.size(), topic, ms);
            }
            return complete;
        } catch (Exception e) {
            long ms = System.currentTimeMillis() - startedAt;
            String reason = SqlErrorClassifier.explain(e);
            if (ms >= startupRestore.discoveryTimeoutMs()) {
                startupRestore.brokerDidNotAnswer("metric configurations", reason);
            }
            log.warn("Metric configurations could not be restored from {} after {} ms: {}",
                topic, ms, reason);
            return false;
        }
    }

}
