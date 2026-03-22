package com.yourcompany.kafkasqlexplorer.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yourcompany.kafkasqlexplorer.config.ExplorerConfig;
import com.yourcompany.kafkasqlexplorer.config.KafkaConfig;
import com.yourcompany.kafkasqlexplorer.domain.MetricConfig;
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
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Duration;
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
    private final ObjectMapper    objectMapper = new ObjectMapper();

    public MetricService(FlinkSqlService flinkSqlService, MeterRegistry meterRegistry,
                         KafkaConfig kafkaConfig, ExplorerConfig explorerConfig) {
        this.flinkSqlService = flinkSqlService;
        this.meterRegistry   = meterRegistry;
        this.kafkaConfig     = kafkaConfig;
        this.explorerConfig  = explorerConfig;
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
                m.history() != null ? m.history() : List.of());
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

    public void save(MetricConfig metric) {
        String id = (metric.id() == null || metric.id().isEmpty())
            ? UUID.randomUUID().toString() : metric.id();
        MetricConfig m = new MetricConfig(
            id, metric.name(), metric.type(), metric.sql(), metric.description(),
            metric.warningThreshold(), metric.criticalThreshold(),
            metric.lastValue(), metric.lastUpdateTime(), metric.errorMessage(),
            metric.history() != null ? metric.history() : List.of(),
            metric.createTableSql());
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

    @Scheduled(fixedRateString = "${explorer.metrics-refresh-rate:30000}")
    public void refreshMetrics() {
        metrics.forEach((id, config) -> {
            try {
                // ── Step 1: register the Flink table if a DDL is attached ──────────────
                if (config.createTableSql() != null && !config.createTableSql().isBlank()) {
                    String ddl = config.createTableSql().trim();
                    // Ensure IF NOT EXISTS so re-runs are idempotent
                    if (!ddl.toUpperCase().contains("IF NOT EXISTS")) {
                        ddl = ddl.replaceFirst("(?i)(CREATE\\s+TABLE\\s+)", "$1IF NOT EXISTS ");
                    }
                    try {
                        flinkSqlService.executeSql(new QueryRequest(ddl, null, 1, 10_000L, null));
                        log.debug("CREATE TABLE DDL executed for metric '{}'", config.name());
                    } catch (Exception ddlEx) {
                        // Table already exists or DDL error — log and continue to the query
                        log.debug("CREATE TABLE for metric '{}' skipped: {}", config.name(), ddlEx.getMessage());
                    }
                }

                // ── Step 2: run the bounded metric SQL ────────────────────────────────
                // Pass "earliest-offset" so FlinkSqlService does NOT inject its own
                // latest-offset hint on top of our bounded-scan hint.
                String boundedSql = injectBoundedHint(config.sql());
                QueryResult result = flinkSqlService.executeSql(
                    new QueryRequest(boundedSql, "earliest-offset", 10_000, 30_000L, null));
                if (result.error() != null) {
                    updateMetricState(id, null, result.error());
                } else if (!result.rows().isEmpty()) {
                    processRows(id, config, result);
                } else {
                    updateMetricState(id, null, "No rows returned — check table name and Kafka connectivity");
                }
            } catch (Exception e) {
                String msg = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
                // Flink ValidationException wraps the root cause as "SQL validation failed. ..."
                // Provide a cleaner hint when the table simply doesn't exist yet.
                if (msg.contains("not found") || msg.contains("SQL validation failed")) {
                    msg = msg + " → Check that the table is registered in Flink (run CREATE TABLE in the Query Workbench first)";
                }
                updateMetricState(id, null, msg);
            }
        });
    }

    // ── Core processing — dispatches by Prometheus type ───────────────────────

    private void processRows(String metricId, MetricConfig config, QueryResult result) {
        String type = config.type() == null ? "GAUGE" : config.type().toUpperCase();
        Double primaryValue = null;

        for (Map<String, Object> row : result.rows()) {
            Double value   = extractValue(row);
            if (value == null) continue;

            List<Tag> tags = buildTags(metricId, config, row);
            String labelKey = buildLabelKey(row);

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
            updateMetricState(metricId, primaryValue, null);
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

    private List<Tag> buildTags(String metricId, MetricConfig config, Map<String, Object> row) {
        List<Tag> tags = new ArrayList<>();
        tags.add(Tag.of("metric_id",   metricId));
        tags.add(Tag.of("metric_name", config.name()));
        tags.add(Tag.of("metric_type", config.type()));
        for (Map.Entry<String, Object> e : row.entrySet()) {
            if (!"metric_value".equalsIgnoreCase(e.getKey())) {
                tags.add(Tag.of(e.getKey().toLowerCase(), String.valueOf(e.getValue())));
            }
        }
        return tags;
    }

    private String buildLabelKey(Map<String, Object> row) {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, Object> e : row.entrySet()) {
            if (!"metric_value".equalsIgnoreCase(e.getKey())) {
                sb.append(e.getKey()).append('=').append(e.getValue()).append('|');
            }
        }
        return sb.toString();
    }

    private void updateHistory(String id, Double value) {
        LinkedList<Double> history = historyMap.computeIfAbsent(id, k -> new LinkedList<>());
        history.addLast(value);
        if (history.size() > MAX_HISTORY) history.removeFirst();
    }

    private void updateMetricState(String id, Double value, String error) {
        MetricConfig current = metrics.get(id);
        if (current == null) return;
        metrics.put(id, new MetricConfig(
            current.id(), current.name(), current.type(), current.sql(), current.description(),
            current.warningThreshold(), current.criticalThreshold(),
            value != null ? value : current.lastValue(),
            System.currentTimeMillis(), error,
            new ArrayList<>(historyMap.getOrDefault(id, new LinkedList<>())),
            current.createTableSql()));
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
        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(props)) {
            consumer.subscribe(Collections.singletonList(explorerConfig.getMetricsConfigTopic()));
            long start = System.currentTimeMillis();
            while (System.currentTimeMillis() - start < 2000) {
                ConsumerRecords<String, String> records = consumer.poll(Duration.ofMillis(500));
                if (records.isEmpty()) break;
                for (ConsumerRecord<String, String> record : records) {
                    MetricConfig cfg = objectMapper.readValue(record.value(), MetricConfig.class);
                    if ("DELETED".equals(cfg.errorMessage())) metrics.remove(record.key());
                    else metrics.put(record.key(), cfg);
                }
            }
        } catch (Exception e) {
            log.debug("Restore from Kafka failed: {}", e.getMessage());
        }
    }
}
