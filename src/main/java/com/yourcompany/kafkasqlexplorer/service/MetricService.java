package com.yourcompany.kafkasqlexplorer.service;

import com.yourcompany.kafkasqlexplorer.domain.MetricConfig;
import com.yourcompany.kafkasqlexplorer.domain.QueryRequest;
import com.yourcompany.kafkasqlexplorer.domain.QueryResult;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

@Service
public class MetricService {
    private static final Logger log = LoggerFactory.getLogger(MetricService.class);
    private final Map<String, MetricConfig> metrics = new ConcurrentHashMap<>();
    private final Map<String, AtomicReference<Double>> gaugeValues = new ConcurrentHashMap<>();

    private final FlinkSqlService flinkSqlService;
    private final MeterRegistry meterRegistry;

    public MetricService(FlinkSqlService flinkSqlService, MeterRegistry meterRegistry) {
        this.flinkSqlService = flinkSqlService;
        this.meterRegistry = meterRegistry;

        // Pre-configured examples
        addMetric("business_events_total", "COUNTER",
            "SELECT COUNT(*) as metric_value FROM demo_orders_in", "Total number of business events processed");
        addMetric("business_avg_latency", "GAUGE",
            "SELECT AVG(CAST(event_time AS DOUBLE)) as metric_value FROM demo_orders_in", "Average business event timestamp (demo)");
        addMetric("business_hourly_events", "HISTOGRAM",
            "SELECT COUNT(*) as metric_value FROM TABLE(TUMBLE(TABLE demo_orders_in, DESCRIPTOR(event_time), INTERVAL '1' HOUR)) GROUP BY window_start, window_end", "Hourly event count distribution");
    }

    private void addMetric(String name, String type, String sql, String description) {
        String id = UUID.randomUUID().toString();
        save(new MetricConfig(id, name, type, sql, description, null, null, null, null, null));
    }

    public List<MetricConfig> getAllMetrics() {
        return new ArrayList<>(metrics.values());
    }

    public Optional<MetricConfig> getById(String id) {
        return Optional.ofNullable(metrics.get(id));
    }

    public void save(MetricConfig metric) {
        String id = metric.id();
        if (id == null || id.isEmpty()) {
            id = UUID.randomUUID().toString();
        }

        MetricConfig newMetric = new MetricConfig(
            id, metric.name(), metric.type(), metric.sql(), metric.description(),
            metric.warningThreshold(), metric.criticalThreshold(),
            metric.lastValue(), metric.lastUpdateTime(), metric.errorMessage()
        );

        metrics.put(id, newMetric);

        // Re-register if exists to update metadata, or create new
        AtomicReference<Double> valueHolder = gaugeValues.computeIfAbsent(id, k -> new AtomicReference<>(0.0));

        Gauge.builder("explorer_business_metric", valueHolder, AtomicReference::get)
            .tags(List.of(
                Tag.of("metric_id", id),
                Tag.of("metric_name", metric.name()),
                Tag.of("metric_type", metric.type())
            ))
            .description(metric.description())
            .register(meterRegistry);
    }

    public void delete(String id) {
        metrics.remove(id);
        gaugeValues.remove(id);
        // Best effort to remove from registry if supported by registry type
        meterRegistry.find("explorer_business_metric").tag("metric_id", id).meters().forEach(meterRegistry::remove);
    }

    @Scheduled(fixedRateString = "${explorer.metrics-refresh-rate:30000}")
    public void refreshMetrics() {
        log.debug("Refreshing {} business metrics", metrics.size());
        metrics.forEach((id, config) -> {
            try {
                QueryResult result = flinkSqlService.executeSql(new QueryRequest(config.sql(), "latest-offset", 1, 5000L, null));
                if (result.error() != null) {
                    updateMetricState(id, null, result.error());
                } else if (!result.rows().isEmpty()) {
                    Object val = result.rows().get(0).values().iterator().next();
                    if (val instanceof Number num) {
                        double doubleVal = num.doubleValue();
                        updateMetricState(id, doubleVal, null);
                        if (gaugeValues.containsKey(id)) {
                            gaugeValues.get(id).set(doubleVal);
                        }
                    } else {
                        updateMetricState(id, null, "Result is not a number: " + val);
                    }
                } else {
                    updateMetricState(id, null, "No rows returned");
                }
            } catch (Exception e) {
                log.error("Failed to refresh metric {}: {}", config.name(), e.getMessage());
                updateMetricState(id, null, e.getMessage());
            }
        });
    }

    private void updateMetricState(String id, Double value, String error) {
        MetricConfig current = metrics.get(id);
        if (current != null) {
            metrics.put(id, new MetricConfig(
                current.id(), current.name(), current.type(), current.sql(), current.description(),
                current.warningThreshold(), current.criticalThreshold(),
                value, System.currentTimeMillis(), error
            ));
        }
    }
}
