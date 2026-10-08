// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors
package com.compagnonsdudev.kafkasqlexplorer.forecast;

import com.compagnonsdudev.kafkasqlexplorer.domain.MetricConfig;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;

/** Bounded best-effort journal: the metric refresh never waits for a database operation. */
public final class MetricObservationJournal {
    public record Sample(Map<String, String> labels, Double value) {
        public Sample { labels = Map.copyOf(labels); }
    }
    private static final Logger log = LoggerFactory.getLogger(MetricObservationJournal.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final List<String> COMPONENTS = List.of("leftValue", "rightValue", "avgLatencyMs",
        "p95LatencyMs", "maxLatencyMs", "maxLagMs", "avgLagMs");
    private static final List<String> COVERAGE_KEYS = List.of("rowsRead", "leftRows", "rightRows",
        "matchedCount", "matchRate", "measuredPartitions", "totalPartitions", "truncated", "partial");
    private final MetricHistoryProperties properties;
    private final MetricObservationStore store;
    private final ArrayBlockingQueue<List<MetricObservation>> queue;
    private final Counter persisted, dropped, rejected, failures;
    private final Thread writer;
    private volatile boolean running;
    private final java.util.Set<String> autoEnrolled = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private final java.util.concurrent.atomic.AtomicBoolean boundReported = new java.util.concurrent.atomic.AtomicBoolean();

    public MetricObservationJournal(MetricHistoryProperties properties, MetricObservationStore store,
                                    MeterRegistry registry) {
        properties.validateEnabled();
        this.properties = properties;
        this.store = store;
        queue = new ArrayBlockingQueue<>(properties.getQueueCapacity());
        persisted = registry.counter("explorer_forecast_history_persisted_total");
        dropped = registry.counter("explorer_forecast_history_dropped_total");
        rejected = registry.counter("explorer_forecast_history_rejected_total");
        failures = registry.counter("explorer_forecast_history_write_failures_total");
        Gauge.builder("explorer_forecast_history_queue_batches", queue, ArrayBlockingQueue::size).register(registry);
        writer = new Thread(this::writeLoop, "metric-observation-writer");
        writer.setDaemon(true);
    }

    /** Explicit ids first; then eligible metrics, first come first enrolled, within the shared bound. */
    public boolean selects(MetricConfig metric) {
        if (properties.getMetricIds().contains(metric.id()) || autoEnrolled.contains(metric.id())) return true;
        if (!properties.isEnrollEligible() || !ForecastEligibility.blockers(metric).isEmpty()) return false;
        synchronized (autoEnrolled) {
            if (autoEnrolled.size() + properties.getMetricIds().size() < MetricHistoryProperties.MAX_METRICS) {
                autoEnrolled.add(metric.id());
                return true;
            }
        }
        if (boundReported.compareAndSet(false, true))
            log.warn("History enrollment bound of {} metrics reached; further eligible metrics are not recorded",
                MetricHistoryProperties.MAX_METRICS);
        return false;
    }

    public void rejectFrame() { rejected.increment(); }

    /** Samples already follow the same per-label reduction as Micrometer, not the card's first row. */
    public void capture(MetricConfig metric, String endpoint, List<Sample> samples,
                        Map<String, Object> summary, boolean failed, long observedAt) {
        if (!selects(metric)) return;
        try {
            String version = MetricObservation.collectedVersion(metric, endpoint, properties.getCollectorId());
            String run = UUID.randomUUID().toString();
            Map<String, Object> coverage = new LinkedHashMap<>();
            if (summary != null) for (String key : COVERAGE_KEYS) {
                Object value = summary.get(key);
                if (value instanceof Boolean || value instanceof Number) coverage.put(key, value);
            }
            // Without complete source provenance raw SQL is collected, never claimed prediction-ready.
            String quality = failed ? "UNMEASURED" : "OBSERVED";
            if (!failed && (summary == null || "RAW_SQL".equals(metric.templateType()) || metric.templateType() == null))
                quality = "UNVERIFIED_SCOPE";
            if (!failed && summary != null && (summary.containsKey("scopeNote")
                || Boolean.TRUE.equals(summary.get("truncated")) || Boolean.TRUE.equals(summary.get("partial"))
                || summary.get("warnings") instanceof List<?> w && !w.isEmpty())) quality = "LIMITED_SCOPE";
            if (!failed && metric.labelFields() != null && !metric.labelFields().isEmpty()) quality = "UNVERIFIED_LABELS";
            String unit = MetricObservation.unit(metric);
            List<MetricObservation> batch = new ArrayList<>();
            if (failed) {
                batch.add(point(metric, version, "collection", Map.of(), "UNKNOWN", run, observedAt,
                    null, quality, coverage));
            } else {
                for (Sample sample : samples) {
                    if (batch.size() >= properties.getMaxSeriesPerRefresh()) { rejected.increment(); continue; }
                    if (sample.value() == null || !Double.isFinite(sample.value())) { rejected.increment(); continue; }
                    batch.add(point(metric, version, "value", sample.labels(), unit, run, observedAt,
                        sample.value(), quality, coverage));
                }
                if (summary != null) for (String component : COMPONENTS) {
                    if (!(summary.get(component) instanceof Number n) || !Double.isFinite(n.doubleValue())) continue;
                    if (batch.size() >= properties.getMaxSeriesPerRefresh()) { rejected.increment(); continue; }
                    // Components describe the complete metric, never a row-specific label combination.
                    String componentUnit = component.endsWith("Ms") ? "milliseconds" : "UNKNOWN";
                    batch.add(point(metric, version, component, Map.of(), componentUnit, run, observedAt,
                        n.doubleValue(), quality, coverage));
                }
            }
            if (batch.isEmpty()) { rejected.increment(); return; }
            if (samples.size() + (summary == null ? 0 : COMPONENTS.stream()
                .filter(c -> summary.get(c) instanceof Number).count()) > properties.getMaxSeriesPerRefresh()) {
                batch = batch.stream().map(o -> MetricObservation.create(o.clusterId(), o.metricId(),
                    o.definitionVersion(), o.component(), o.labels(), o.unit(), o.semanticKind(),
                    o.collectorRunId(), o.observedAt(), o.value(), "LIMITED_SERIES", o.coverage())).toList();
            }
            // Reject an oversized frame as a whole; an unlabelled partial frame would look complete.
            if (MAPPER.writeValueAsBytes(batch).length > 128 * 1024) { rejected.increment(batch.size()); return; }
            if (!running || !queue.offer(List.copyOf(batch))) dropped.increment(batch.size());
        } catch (Exception e) {
            rejected.increment();
            // SQL, labels and connection details are deliberately absent from this message.
            log.warn("Metric observation frame rejected ({})", e.getClass().getSimpleName());
        }
    }

    private MetricObservation point(MetricConfig m, String version, String component, Map<String, String> labels,
        String unit, String run, long at, Double value, String quality, Map<String, Object> coverage) {
        return MetricObservation.create(properties.getClusterId(), m.id(), version, component, labels,
            unit, m.type() == null ? "GAUGE" : m.type(), run, at, value, quality, coverage);
    }

    @PostConstruct
    public synchronized void start() {
        if (running) return;
        running = true;
        writer.start();
    }

    private void writeLoop() {
        long nextPurge = 0;
        while (running || !queue.isEmpty()) {
            try {
                List<MetricObservation> batch = queue.poll(500, TimeUnit.MILLISECONDS);
                if (batch != null) {
                    boolean saved = false;
                    for (int attempt = 0; attempt < 2 && !saved; attempt++) {
                        try { store.append(batch); saved = true; persisted.increment(batch.size()); }
                        catch (Exception e) { failures.increment(); }
                    }
                    if (!saved) {
                        dropped.increment(batch.size());
                        log.warn("Metric history persistence unavailable; {} observations lost", batch.size());
                    }
                }
                long now = System.currentTimeMillis();
                if (now >= nextPurge) {
                    nextPurge = now + Duration.ofMinutes(1).toMillis();
                    try { store.purgeBefore(now - properties.getRetention().toMillis(), 10000); }
                    catch (Exception e) { failures.increment(); }
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
    }

    @PreDestroy
    public void close() {
        running = false;
        try { writer.join(2000); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        if (writer.isAlive()) writer.interrupt();
        List<MetricObservation> pending;
        while ((pending = queue.poll()) != null) dropped.increment(pending.size());
    }
}
