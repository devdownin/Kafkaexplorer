// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors
package com.yourcompany.kafkasqlexplorer.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.util.ArrayList;
import java.util.List;

@Configuration
@ConfigurationProperties(prefix = "explorer")
public class ExplorerConfig {

    /**
     * Display label for the connected cluster, shown in the header's connection pill.
     *
     * <p>It is a <em>name</em>, not a fact about the broker: nothing here verifies it, and
     * {@code POST /api/config} can repoint the app at another cluster while this string stays put.
     * The shipped default therefore claims nothing — it used to be {@code KRAFT 4.2} in
     * {@code application.yml}, so every deployment asserted a Kafka version it had never checked,
     * in the one element whose job is to say what you are connected to. What <em>is</em> verified
     * travels beside it: the effective bootstrap address, which the header shows on hover.
     */
    private String clusterName = "Kafka cluster";
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
     * How long the cluster's consumer groups, read once for an audit run, stay usable before being
     * re-read. {@code 0} takes the snapshot once for the whole run.
     *
     * <p>The snapshot is what stops the consumer-lag check from re-listing every group of the
     * cluster on every topic. Taken once and never refreshed, though, a run of half an hour
     * compares committed positions from its first minute against end offsets read thirty minutes
     * later: the lag is only ever overstated, never understated, but "overstated by half an hour of
     * traffic" is a wide enough margin to make a reported backlog hard to believe. This bounds the
     * staleness without giving the cost back — at 60 s, a thirty-minute run pays about thirty
     * reads instead of one per topic.
     */
    private long auditGroupSnapshotTtlMs = 60_000;
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
    /**
     * Cap for a single record fetched by its coordinates (GET /api/topic/{name}/record).
     * A search result is deliberately truncated to keep a hundred hits small; reading one record
     * on purpose is the opposite need, so this is a hundred times larger — but still a cap, or a
     * single pathological payload would be enough to sink the browser.
     */
    private int recordMaxValueChars = 1000000;
    /**
     * Wall-clock budget for one stream-flow trace. Each topic read carries its own 20 s broker
     * budget, so a whole-cluster trace against an unhealthy broker would otherwise hold the HTTP
     * thread for many minutes. Past the budget the trace returns what it has and names the topics
     * it never reached.
     */
    private long streamFlowTimeoutMs = 60000;
    /**
     * Topics scanned by a stream-flow trace that names no target topic. A trace opens one consumer
     * per topic; on a cluster with thousands of them, scanning everything is never what the user
     * meant by leaving the field empty.
     */
    private int streamFlowMaxTopics = 250;
    /**
     * Consumer groups whose committed offsets are read when reporting a topic's lag. Each group
     * costs a coordinator lookup, and a cluster can hold thousands; past this cap the response
     * says so rather than pretending the list is complete.
     */
    private int consumerGroupMaxGroups = 200;
    /**
     * Topics whose consumer lag is exported as Prometheus gauges. Named explicitly rather than
     * discovered: a series per group × topic is how a metrics backend gets killed, and the topics
     * worth alerting on are a short, deliberate list. Empty (the default) registers no gauge and
     * starts no polling thread.
     */
    private List<String> lagMetricsTopics = new ArrayList<>();
    /**
     * Hard ceiling on the number of lag series, whatever the topic list turns out to contain.
     * Reaching it is logged once — a silently truncated metric is worse than an absent one.
     */
    private int lagMetricsMaxSeries = 500;
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

    public long getAuditGroupSnapshotTtlMs() {
        return auditGroupSnapshotTtlMs;
    }

    public void setAuditGroupSnapshotTtlMs(long auditGroupSnapshotTtlMs) {
        this.auditGroupSnapshotTtlMs = auditGroupSnapshotTtlMs;
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

    public int getRecordMaxValueChars() {
        return recordMaxValueChars;
    }

    public void setRecordMaxValueChars(int recordMaxValueChars) {
        this.recordMaxValueChars = recordMaxValueChars;
    }

    public long getStreamFlowTimeoutMs() {
        return streamFlowTimeoutMs;
    }

    public void setStreamFlowTimeoutMs(long streamFlowTimeoutMs) {
        this.streamFlowTimeoutMs = streamFlowTimeoutMs;
    }

    public int getStreamFlowMaxTopics() {
        return streamFlowMaxTopics;
    }

    public void setStreamFlowMaxTopics(int streamFlowMaxTopics) {
        this.streamFlowMaxTopics = streamFlowMaxTopics;
    }

    public int getConsumerGroupMaxGroups() {
        return consumerGroupMaxGroups;
    }

    public void setConsumerGroupMaxGroups(int consumerGroupMaxGroups) {
        this.consumerGroupMaxGroups = consumerGroupMaxGroups;
    }

    public List<String> getLagMetricsTopics() {
        return lagMetricsTopics;
    }

    public void setLagMetricsTopics(List<String> lagMetricsTopics) {
        this.lagMetricsTopics = lagMetricsTopics;
    }

    public int getLagMetricsMaxSeries() {
        return lagMetricsMaxSeries;
    }

    public void setLagMetricsMaxSeries(int lagMetricsMaxSeries) {
        this.lagMetricsMaxSeries = lagMetricsMaxSeries;
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
