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
import java.util.concurrent.atomic.AtomicBoolean;
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

    /** Columns that are never labels. */
    private static boolean isReservedColumn(String column) {
        return "metric_value".equalsIgnoreCase(column) || OBSERVED_AT_COLUMN.equalsIgnoreCase(column);
    }

    /** Companion series: one gauge per (metric, name), carrying no row labels. */
    private static final String LAST_SUCCESS_SERIES = "explorer_metric_last_success_timestamp_seconds";
    private static final String MATCH_RATE_SERIES = "explorer_metric_correlation_match_rate";
    private static final List<String> COMPANION_SERIES = List.of(LAST_SUCCESS_SERIES, MATCH_RATE_SERIES);

    // ── metric state ─────────────────────────────────────────────────────────
    private final Map<String, MetricConfig>              metrics           = new ConcurrentHashMap<>();
    private final Map<String, LinkedList<Double>>        historyMap        = new ConcurrentHashMap<>();
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
    private volatile KafkaProducer<String, String> configProducer;

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
        purgeMeters(id);
        persistToKafka(new MetricConfig(id, null, null, null, null, null, null, null, null, "DELETED"));
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
     * What "bounded" actually takes, which is two options rather than one.
     *
     * <p>This was a lone {@code scan.startup.mode='earliest-offset'} under a comment promising the
     * query "reads all data that exists in Kafka at query start time, then terminates (no
     * indefinite streaming)". That sentence is the contract of {@code scan.bounded.mode};
     * {@code scan.startup.mode} says where a scan <em>begins</em>. The two are not alternatives
     * and the missing one is the one that ends the scan.
     *
     * <p>Nothing bounded anything, therefore, and the option that was there changed nothing
     * either: {@code DdlGeneratorService} already writes {@code earliest-offset} into every table
     * it generates, which is every table these metrics read. The environment is built
     * {@code inStreamingMode()} ({@code FlinkConfig}), so the source stayed unbounded and each
     * side of a two-query metric either blocked until its own timeout — 30 s, twice, on a 30 s
     * schedule — or came back with the first rows an endless scan happened to yield: for a
     * projection the oldest records, and for {@code COUNT(*)} the head of a retract changelog,
     * whose first row is {@code +I(1)}.
     */
    private static final String SCAN_BOUNDED_OPTION = "'scan.bounded.mode'='latest-offset'";
    private static final String SCAN_STARTUP_EARLIEST = "'scan.startup.mode'='earliest-offset'";

    /** Option names, used to recognise a connector that will not take them — see the latch below. */
    private static final List<String> SCAN_OPTION_NAMES = List.of("scan.bounded.mode");

    private static final Pattern FROM_TABLE = Pattern.compile("(?i)\\bFROM\\b\\s+(\\w[\\w.]*)");

    private static final Pattern JOIN_KEYWORD = Pattern.compile("(?i)\\bJOIN\\b");

    /**
     * Raised once, for the life of the process, when a query fails on the scan options themselves.
     *
     * <p>The options above are the connector's, not ours, and a deployment can be pointed at a
     * Kafka connector that predates {@code scan.bounded.mode} — on which asking for it turns every
     * template metric from slow into broken. So a failure that names the option earns one retry
     * without it, and the answer is remembered rather than re-derived on every refresh: the same
     * degrade-once-and-remember shape {@code OpenAiCompatibleLlmClient} uses for a gateway that
     * refuses {@code response_format}. What it costs when it latches is stated in the metric's
     * own summary, never inferred from silence.
     */
    private final AtomicBoolean scanOptionsRefused = new AtomicBoolean(false);

    /** The scan bounds a template asks for, or null once the connector has refused them. */
    private String scanHint() {
        if (scanOptionsRefused.get()) return null;
        return "/*+ OPTIONS(" + SCAN_STARTUP_EARLIEST + "," + SCAN_BOUNDED_OPTION + ") */";
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

    /** Did this failure come from the scan options rather than from the query? */
    private boolean refusesScanOptions(QueryResult result) {
        if (result == null || result.error() == null) return false;
        String error = result.error().toLowerCase(Locale.ROOT);
        return SCAN_OPTION_NAMES.stream().anyMatch(error::contains);
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
                validateScanParams(params, DEFAULT_TEMPLATE_READ_MODE);
                if (!"GAUGE".equals(metricType)) {
                    throw new IllegalArgumentException("TOPIC_COUNT_DELTA supports GAUGE metrics only");
                }
            }
            case TOPIC_TRANSIT_LATENCY -> {
                requireParam(params, "sourceSql");
                requireParam(params, "targetSql");
                validateScanParams(params, DEFAULT_LATENCY_READ_MODE);
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

    private MetricComputationResult computeMetric(MetricConfig config) {
        return switch (MetricTemplateType.fromValue(config.templateType())) {
            case RAW_SQL -> computeRawSqlMetric(config);
            case TOPIC_COUNT_DELTA -> computeCountDeltaMetric(config);
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
        QueryResult result = executeMetricQuery(
            config.sql(),
            DEFAULT_TEMPLATE_MAX_ROWS,
            DEFAULT_TEMPLATE_TIMEOUT_MS,
            DEFAULT_TEMPLATE_READ_MODE,
            // Never the direct reader: raw SQL is the operator's own, and may need the planner.
            false
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
        // A count must see the whole topic, so this side is read from the beginning whatever the
        // latency template does — the two templates ask different questions of the same broker.
        String readMode = getStringParam(params, "readMode", DEFAULT_TEMPLATE_READ_MODE);

        String leftSql = requireParam(params, "leftSql");
        String rightSql = requireParam(params, "rightSql");
        /*
         * The right side is read first, and the order is the measurement's, not the form's.
         *
         * Two counts cannot be taken at one instant — a whole query separates them, and on the
         * shipped defaults that can be a 30 s timeout plus a hundred-thousand-record scan. So the
         * arithmetic leans, and the only choice is which way. Every operation here grows with the
         * left side (LEFT_MINUS_RIGHT, RATIO and PERCENT_GAP all do), and the panel proposes the
         * upstream topic on the left with a threshold that fires when the value is high — so
         * reading the left side *last* lets the traffic of the interval land in it, and a gap that
         * survives that is a real one. Read the other way round, the same traffic lands in the
         * denominator and the gap is understated: the metric under-reports exactly the loss it
         * exists to report, which is the failure this ordering exists to prevent.
         *
         * It is the rule KafkaAdminService already follows for consumer lag — committed offsets
         * first, log end offsets last, "so a consumer committing between the two calls can only
         * make the lag look larger". ABS_DIFF is the one operation this cannot help: it is
         * symmetric, so no ordering is conservative for it, and readGapMs below is what says how
         * much room the interval left.
         */
        long readStartedAt = System.currentTimeMillis();
        QueryResult rightResult =
            executeMetricQuery(rightSql, maxRows, timeoutMs, readMode, isSingleTableRead(rightSql));
        if (rightResult.error() != null) {
            return MetricComputationResult.error("Right query: " + rightResult.error());
        }
        long rightReadAt = System.currentTimeMillis();
        QueryResult leftResult =
            executeMetricQuery(leftSql, maxRows, timeoutMs, readMode, isSingleTableRead(leftSql));
        if (leftResult.error() != null) {
            return MetricComputationResult.error("Left query: " + leftResult.error());
        }
        long readGapMs = System.currentTimeMillis() - rightReadAt;
        long totalReadMs = System.currentTimeMillis() - readStartedAt;

        SideRead left = aggregateValue(leftResult, maxRows, "left");
        if (left.error() != null) return MetricComputationResult.error(left.error());
        SideRead right = aggregateValue(rightResult, maxRows, "right");
        if (right.error() != null) return MetricComputationResult.error(right.error());

        // A floor is not a count, and two floors compared read as no gap at all — which is the
        // one answer this metric must never give by accident.
        if (left.capped() || right.capped()) {
            boolean both = left.capped() && right.capped();
            return MetricComputationResult.error(
                (both ? "Both counts stopped" : (left.capped() ? "The left count stopped" : "The right count stopped"))
                + " on the direct reader's " + FlinkSqlService.AGGREGATE_SCAN_RECORDS
                + "-record ceiling, so " + (both ? "they are floors" : "it is a floor")
                + " rather than a total. A gap measured between floors reads as no gap, so nothing "
                + "is published: count topics this reader can read in full, or measure the drop "
                + "another way.");
        }

        double leftValue = left.value();
        double rightValue = right.value();
        String operation = getStringParam(params, "operation", "LEFT_MINUS_RIGHT").toUpperCase(Locale.ROOT);
        Double metricValue = switch (operation) {
            case "LEFT_MINUS_RIGHT" -> leftValue - rightValue;
            case "ABS_DIFF" -> Math.abs(leftValue - rightValue);
            case "RATIO" -> rightValue == 0.0 ? null : leftValue / rightValue;
            case "PERCENT_GAP" -> rightValue == 0.0 ? null : ((leftValue - rightValue) * 100.0) / rightValue;
            default -> throw new IllegalArgumentException("Unsupported count delta operation: " + operation);
        };
        if (metricValue == null) {
            return MetricComputationResult.error("Cannot compute " + operation
                + " when the right query counts zero: " + operation + " divides by it. The left "
                + "query counted " + formatCount(leftValue) + ", so if the right topic really is "
                + "empty that is the finding — LEFT_MINUS_RIGHT or ABS_DIFF report it as a number.");
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
        summary.put("leftEngine", leftResult.engine());
        summary.put("rightEngine", rightResult.engine());
        // How much room the interval between the two reads left. A number rather than a
        // reassurance: on a topic doing a thousand records a second, four seconds here is four
        // thousand records that are in one count and not the other.
        summary.put("readGapMs", readGapMs);
        summary.put("readDurationMs", totalReadMs);
        // Accurate per engine rather than in one clause: the row cap bounds what the planner
        // returns, while the direct reader ignores it for an aggregate and stops on its own
        // record ceiling — two different bounds, and a note naming the wrong one is worse than none.
        summary.put("scopeNote", "The right side is counted first and the left "
            + readGapMs + " ms later, so traffic in between lands in the left count and this "
            + ("ABS_DIFF".equals(operation) ? "difference can move either way" : "value can only be overstated")
            + ", never understated. Read " + describeReadEnd(readMode)
            + ". A side the direct reader answered covers at most "
            + FlinkSqlService.AGGREGATE_SCAN_RECORDS + " record(s); a side the planner answered "
            + "covers at most " + maxRows + " row(s).");
        addScanWarnings(summary, leftResult, rightResult);

        return new MetricComputationResult(List.of(row), metricValue, null, summary);
    }

    /** Which end of the topic a read entered by, in the words the summary uses. */
    private String describeReadEnd(String readMode) {
        return DEFAULT_LATENCY_READ_MODE.equals(readMode)
            ? "from the most recent records backwards"
            : "from the earliest offset";
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
        summary.put("scopeNote", "Correlated over at most " + maxRows + " row(s) per side, read "
            + describeReadEnd(readMode) + ".");
        addScanWarnings(summary, sourceResult, targetResult);

        return new MetricComputationResult(rows, avgLatencyMs, null, summary);
    }

    /**
     * One template read: the scan bounds asked for, and one retry without them if the connector
     * turns out not to know them (see {@link #scanOptionsRefused}).
     */
    private QueryResult executeMetricQuery(String sql, int maxRows, long timeoutMs, String readMode,
                                           boolean directRead) {
        String hint = scanHint();
        QueryResult result = runMetricQuery(injectScanHint(sql, hint), maxRows, timeoutMs, readMode, directRead);
        if (hint == null || !refusesScanOptions(result)) return result;
        if (scanOptionsRefused.compareAndSet(false, true)) {
            log.warn("This Kafka connector refused {} — template scans are unbounded for the rest of "
                    + "this process, so a read stops on its row cap or its timeout instead of at the "
                    + "end of the topic. Cause: {}",
                SCAN_OPTION_NAMES, LogSafe.text(result.error()));
        }
        return runMetricQuery(sql, maxRows, timeoutMs, readMode, directRead);
    }

    private QueryResult runMetricQuery(String sql, int maxRows, long timeoutMs, String readMode,
                                       boolean directRead) {
        Map<String, QueryResult> cycleCache = refreshCycleQueryCache.get();
        if (cycleCache == null) return submitMetricQuery(sql, maxRows, timeoutMs, readMode, directRead);
        String key = sql + '|' + maxRows + '|' + timeoutMs + '|' + readMode + '|' + directRead;
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
    private SideRead aggregateValue(QueryResult result, int maxRows, String side) {
        if (result.rows().isEmpty()) {
            return SideRead.failed("The " + side + " query returned no row — the topic may hold "
                + "nothing, or its table may not resolve on this cluster.");
        }
        if ("FLINK".equals(result.engine()) && result.rows().size() >= maxRows) {
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
        metrics.forEach(this::refreshSingleMetric);
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

    private KafkaProducer<String, String> configProducer() {
        KafkaProducer<String, String> existing = configProducer;
        if (existing != null) return existing;
        synchronized (this) {
            if (configProducer == null) {
                Properties props = new Properties();
                props.putAll(kafkaConfig.getKafkaProperties());
                props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG,   StringSerializer.class.getName());
                props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
                configProducer = new KafkaProducer<>(props);
            }
            return configProducer;
        }
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

            // Poll until every partition reaches its end offset. An empty poll does NOT mean
            // the topic is exhausted (the first poll after assignment is typically empty
            // while the fetch is in flight), so completion is tracked by position instead.
            long deadline = startedAt + RESTORE_BUDGET_MS;
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
                            record.partition(), record.offset(), SqlErrorClassifier.explain(e));
                    }
                }
            }
            boolean complete = reachedEndOffsets(consumer, endOffsets);
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

    private boolean reachedEndOffsets(Consumer<String, String> consumer,
                                      Map<TopicPartition, Long> endOffsets) {
        for (Map.Entry<TopicPartition, Long> entry : endOffsets.entrySet()) {
            if (consumer.position(entry.getKey()) < entry.getValue()) {
                return false;
            }
        }
        return true;
    }
}
