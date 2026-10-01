// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors
package com.compagnonsdudev.kafkasqlexplorer.forecast;

import java.time.Duration;
import java.util.Set;

public class MetricHistoryProperties {
    private boolean enabled;
    private String clusterId = "";
    private String collectorId = "";
    private String jdbcUrl = "";
    private String username = "";
    private String password = "";
    private Set<String> metricIds = Set.of();
    private int queueCapacity = 128;
    private int maxSeriesPerRefresh = 100;
    private Duration retention = Duration.ofDays(30);

    public void validateEnabled() {
        if (!enabled) return;
        if (clusterId.isBlank() || collectorId.isBlank() || !jdbcUrl.startsWith("jdbc:postgresql:"))
            throw new IllegalArgumentException("History requires cluster-id, collector-id and a PostgreSQL JDBC URL");
        if (metricIds.isEmpty() || metricIds.size() > 100 || metricIds.stream().anyMatch(String::isBlank))
            throw new IllegalArgumentException("History requires 1..100 explicitly selected metric ids");
        if (queueCapacity < 1 || queueCapacity > 1024 || maxSeriesPerRefresh < 1 || maxSeriesPerRefresh > 1000)
            throw new IllegalArgumentException("History queue/series limits are out of bounds");
        if (retention == null || retention.compareTo(Duration.ofDays(1)) < 0
            || retention.compareTo(Duration.ofDays(90)) > 0)
            throw new IllegalArgumentException("History retention must be 1..90 days");
    }

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean v) { enabled = v; }
    public String getClusterId() { return clusterId; }
    public void setClusterId(String v) { clusterId = v; }
    public String getCollectorId() { return collectorId; }
    public void setCollectorId(String v) { collectorId = v; }
    public String getJdbcUrl() { return jdbcUrl; }
    public void setJdbcUrl(String v) { jdbcUrl = v; }
    public String getUsername() { return username; }
    public void setUsername(String v) { username = v; }
    public String getPassword() { return password; }
    public void setPassword(String v) { password = v; }
    public Set<String> getMetricIds() { return metricIds; }
    public void setMetricIds(Set<String> v) { metricIds = Set.copyOf(v); }
    public int getQueueCapacity() { return queueCapacity; }
    public void setQueueCapacity(int v) { queueCapacity = v; }
    public int getMaxSeriesPerRefresh() { return maxSeriesPerRefresh; }
    public void setMaxSeriesPerRefresh(int v) { maxSeriesPerRefresh = v; }
    public Duration getRetention() { return retention; }
    public void setRetention(Duration v) { retention = v; }
}
