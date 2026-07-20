// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors
package com.yourcompany.kafkasqlexplorer.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConfigurationProperties(prefix = "explorer")
public class ExplorerConfig {

    private String clusterName = "DOCKER CLUSTER";
    private String auditHistoryTopic = "internal.audit.history";
    private String metricsConfigTopic = "internal.metrics.config";
    private int defaultMaxRows = 50;
    private long defaultQueryTimeoutMs = 10000;
    private long auditQueryTimeoutMs = 5000;
    private int inferenceSampleSize = 10;
    private long inferencePollTimeoutMs = 2000;
    private boolean allowCrossJoin = false;
    private boolean allowSystemTableAccess = false;
    /**
     * TTL of the Kafka metadata caches (topic list, topic descriptors). Kept short so a
     * newly created topic shows up quickly in the workbench and auto-registration.
     */
    private int cacheExpireSeconds = 30;
    private String flinkJobStorePath = "data/flink-jobs.json";
    private long flinkJobRetentionHours = 24;

    public String getClusterName() {
        return clusterName;
    }

    public void setClusterName(String clusterName) {
        this.clusterName = clusterName;
    }

    public String getAuditHistoryTopic() {
        return auditHistoryTopic;
    }

    public void setAuditHistoryTopic(String auditHistoryTopic) {
        this.auditHistoryTopic = auditHistoryTopic;
    }

    public String getMetricsConfigTopic() {
        return metricsConfigTopic;
    }

    public void setMetricsConfigTopic(String metricsConfigTopic) {
        this.metricsConfigTopic = metricsConfigTopic;
    }

    public int getDefaultMaxRows() {
        return defaultMaxRows;
    }

    public void setDefaultMaxRows(int defaultMaxRows) {
        this.defaultMaxRows = defaultMaxRows;
    }

    public long getDefaultQueryTimeoutMs() {
        return defaultQueryTimeoutMs;
    }

    public void setDefaultQueryTimeoutMs(long defaultQueryTimeoutMs) {
        this.defaultQueryTimeoutMs = defaultQueryTimeoutMs;
    }

    public long getAuditQueryTimeoutMs() {
        return auditQueryTimeoutMs;
    }

    public void setAuditQueryTimeoutMs(long auditQueryTimeoutMs) {
        this.auditQueryTimeoutMs = auditQueryTimeoutMs;
    }

    public int getInferenceSampleSize() {
        return inferenceSampleSize;
    }

    public void setInferenceSampleSize(int inferenceSampleSize) {
        this.inferenceSampleSize = inferenceSampleSize;
    }

    public long getInferencePollTimeoutMs() {
        return inferencePollTimeoutMs;
    }

    public void setInferencePollTimeoutMs(long inferencePollTimeoutMs) {
        this.inferencePollTimeoutMs = inferencePollTimeoutMs;
    }

    public boolean isAllowCrossJoin() {
        return allowCrossJoin;
    }

    public void setAllowCrossJoin(boolean allowCrossJoin) {
        this.allowCrossJoin = allowCrossJoin;
    }

    public boolean isAllowSystemTableAccess() {
        return allowSystemTableAccess;
    }

    public void setAllowSystemTableAccess(boolean allowSystemTableAccess) {
        this.allowSystemTableAccess = allowSystemTableAccess;
    }

    public int getCacheExpireSeconds() {
        return cacheExpireSeconds;
    }

    public void setCacheExpireSeconds(int cacheExpireSeconds) {
        this.cacheExpireSeconds = cacheExpireSeconds;
    }

    public String getFlinkJobStorePath() {
        return flinkJobStorePath;
    }

    public void setFlinkJobStorePath(String flinkJobStorePath) {
        this.flinkJobStorePath = flinkJobStorePath;
    }

    public long getFlinkJobRetentionHours() {
        return flinkJobRetentionHours;
    }

    public void setFlinkJobRetentionHours(long flinkJobRetentionHours) {
        this.flinkJobRetentionHours = flinkJobRetentionHours;
    }
}
