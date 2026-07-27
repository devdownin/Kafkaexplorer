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
    /**
     * Records read from the end of the audit-history topic when listing past runs. The topic is
     * append-only and a report holds one entry per topic, so the read is bounded and the response
     * states whether older runs exist beyond it.
     */
    private int auditHistoryMaxRecords = 200;
    private String metricsConfigTopic = "internal.metrics.config";
    private int defaultMaxRows = 50;
    private long defaultQueryTimeoutMs = 10000;
    private long auditQueryTimeoutMs = 5000;
    /**
     * Wall-clock budget for one full audit run, 0 to disable. A cluster of 2 000 topics with exact
     * counts enabled runs one Flink {@code COUNT(*)} per topic at {@code auditQueryTimeoutMs} each
     * on four workers, so it can legitimately occupy forty minutes. Past the budget the run stops
     * like a cancellation and reports how far it got — a partial report that says so beats an
     * operator watching a progress bar for an hour.
     */
    private long auditMaxDurationMs = 1_800_000;
    /**
     * Where duplicate detection reads from: LATEST (the most recent messages, the default) or
     * EARLIEST. Everything else in the audit samples recent messages; scanning from the start of
     * the topic judged the oldest surviving records, which on a topic with retention is rarely
     * what an operator is asking about.
     */
    private String auditDuplicateScanFrom = "LATEST";
    private int inferenceSampleSize = 10;
    private long inferencePollTimeoutMs = 2000;
    private boolean allowCrossJoin = false;
    private boolean allowSystemTableAccess = false;
    /**
     * When true, SELECT queries are executed through the real Flink planner first, falling back
     * to the in-process direct Kafka reader if the planner fails (historically a
     * FlinkRelMetadataQuery NPE). Set to false to always use the direct reader.
     */
    private boolean flinkSelectEnabled = true;
    /**
     * TTL of the Kafka metadata caches (topic list, topic descriptors). Kept short so a
     * newly created topic shows up quickly in the workbench and auto-registration.
     */
    private int cacheExpireSeconds = 30;
    /** Hits returned by one topic-search pass before it stops and hands back a cursor. */
    private int searchMaxHits = 100;
    /** Records read by one topic-search pass before it stops and hands back a cursor. */
    private int searchMaxScan = 20000;
    /** Wall-clock budget for one topic-search pass. */
    private int searchTimeoutMs = 10000;
    /** Message values longer than this are truncated in search results and samples. */
    private int searchMaxValueChars = 8000;
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

    public int getAuditHistoryMaxRecords() {
        return auditHistoryMaxRecords;
    }

    public void setAuditHistoryMaxRecords(int auditHistoryMaxRecords) {
        this.auditHistoryMaxRecords = auditHistoryMaxRecords;
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

    public long getAuditMaxDurationMs() {
        return auditMaxDurationMs;
    }

    public void setAuditMaxDurationMs(long auditMaxDurationMs) {
        this.auditMaxDurationMs = auditMaxDurationMs;
    }

    public String getAuditDuplicateScanFrom() {
        return auditDuplicateScanFrom;
    }

    public void setAuditDuplicateScanFrom(String auditDuplicateScanFrom) {
        this.auditDuplicateScanFrom = auditDuplicateScanFrom;
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

    public boolean isFlinkSelectEnabled() {
        return flinkSelectEnabled;
    }

    public void setFlinkSelectEnabled(boolean flinkSelectEnabled) {
        this.flinkSelectEnabled = flinkSelectEnabled;
    }

    public int getCacheExpireSeconds() {
        return cacheExpireSeconds;
    }

    public void setCacheExpireSeconds(int cacheExpireSeconds) {
        this.cacheExpireSeconds = cacheExpireSeconds;
    }

    public int getSearchMaxHits() {
        return searchMaxHits;
    }

    public void setSearchMaxHits(int searchMaxHits) {
        this.searchMaxHits = searchMaxHits;
    }

    public int getSearchMaxScan() {
        return searchMaxScan;
    }

    public void setSearchMaxScan(int searchMaxScan) {
        this.searchMaxScan = searchMaxScan;
    }

    public int getSearchTimeoutMs() {
        return searchTimeoutMs;
    }

    public void setSearchTimeoutMs(int searchTimeoutMs) {
        this.searchTimeoutMs = searchTimeoutMs;
    }

    public int getSearchMaxValueChars() {
        return searchMaxValueChars;
    }

    public void setSearchMaxValueChars(int searchMaxValueChars) {
        this.searchMaxValueChars = searchMaxValueChars;
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
