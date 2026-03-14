package com.yourcompany.kafkasqlexplorer.service;

import com.yourcompany.kafkasqlexplorer.domain.MetricConfig;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class MetricService {
    private final Map<String, MetricConfig> metrics = new ConcurrentHashMap<>();

    public MetricService() {
        // Pre-configured examples
        addMetric("Events Total", "COUNTER",
            "SELECT COUNT(*) as metric_value FROM events_table", "Total number of events processed");
        addMetric("Avg Latency", "GAUGE",
            "SELECT AVG(latency) as metric_value FROM events_table", "Average processing latency");
        addMetric("Events per Minute", "HISTOGRAM",
            "SELECT COUNT(*) as metric_value, window_start, window_end FROM TABLE(TUMBLE(TABLE events_table, DESCRIPTOR(event_time), INTERVAL '1' MINUTE)) GROUP BY window_start, window_end", "Number of events per minute");
    }

    private void addMetric(String name, String type, String sql, String description) {
        String id = UUID.randomUUID().toString();
        metrics.put(id, new MetricConfig(id, name, type, sql, description, null, null));
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
        metrics.put(id, new MetricConfig(id, metric.name(), metric.type(), metric.sql(), metric.description(),
            metric.warningThreshold(), metric.criticalThreshold()));
    }

    public void delete(String id) {
        metrics.remove(id);
    }
}
