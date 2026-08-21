// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors
package com.compagnonsdudev.kafkasqlexplorer.config;

import com.compagnonsdudev.kafkasqlexplorer.service.ExplorerConsumerGroups;
import jakarta.annotation.PostConstruct;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

@Configuration
@ConfigurationProperties(prefix = "explorer")
public class ExplorerConfig {

    /**
     * Hands the configured prefix to the one class that names internal consumers.
     *
     * <p>Here rather than in a listener of its own, and that is the whole point of the placement:
     * {@code MetricService} and {@code FieldMappingStore} both open a consumer while restoring
     * state at boot, and both inject this bean — so this runs before either of them exists. A
     * {@code @PostConstruct} on some other component would be racing them for no reason.
     */
    @PostConstruct
    void applyConsumerGroupPrefix() {
        ExplorerConsumerGroups.usePrefix(consumerGroupPrefix);
        resolvedInternalPrefix = resolveInternalTopicPrefix(internalTopicPrefix);
    }

    private static final Logger log = LoggerFactory.getLogger(ExplorerConfig.class);

    /** Ce qu'un nom de topic Kafka accepte. Un prefixe hors de ca ne peut pas etre cree. */
    private static final Pattern TOPIC_CHARS = Pattern.compile("[a-zA-Z0-9._-]+");

    /**
     * Prefix for the topics this application writes to the user's cluster for its own state —
     * the audit history, the metric configurations and the validated field mappings.
     *
     * <p>Empty by default, and an empty value changes nothing: the topics keep the
     * {@code internal.*} names they have always had. It exists for the deployment that shares a
     * cluster with other tenants, or whose naming convention reserves a namespace per
     * application — the same need {@code explorer.consumer-group-prefix} answers for the groups
     * this application creates, and it follows the same three rules.
     *
     * <p>It renames <em>only</em> what this application writes for itself. It is not applied to a
     * topic of the user's own pipelines, and it is not applied to the {@code flink_table_*} group
     * id of generated DDL.
     *
     * <p>A value Kafka could not accept as part of a topic name is refused with a WARN naming the
     * property, and the run continues unprefixed — a prefix that cannot be created is not a
     * stricter policy, it is three stores that fail to write with nothing saying why. A value
     * with no trailing separator gets one, since {@code acme} would otherwise yield
     * {@code acmeinternal.audit.history}.
     *
     * <p>And it is <em>not</em> retroactive: changing it points the application at different
     * topics, it does not move what the previous setting wrote. That is the one place it differs
     * from the consumer-group prefix, which keeps recognising its default so a rename does not
     * orphan what came before — a topic cannot be recognised, only read or not read. An operator
     * who sets this on a live deployment starts a fresh audit history; the note is in
     * {@code application.yml} beside the property.
     */
    private String internalTopicPrefix = "";

    /** The prefix as it is actually applied: validated, separator-terminated, never null. */
    private String resolvedInternalPrefix = "";

    /**
     * Validates the configured prefix and gives it a trailing separator. Package-private so
     * {@code ExplorerConfigTest} can drive it without a context.
     */
    static String resolveInternalTopicPrefix(String configured) {
        String trimmed = configured == null ? "" : configured.trim();
        if (trimmed.isEmpty()) {
            return "";
        }
        if (!TOPIC_CHARS.matcher(trimmed).matches()) {
            log.warn("explorer.internal-topic-prefix=\"{}\" is not usable in a Kafka topic name "
                + "(allowed: letters, digits, dot, dash, underscore) — the internal topics keep "
                + "their unprefixed names.", trimmed);
            return "";
        }
        char last = trimmed.charAt(trimmed.length() - 1);
        return last == '.' || last == '-' || last == '_' ? trimmed : trimmed + ".";
    }

    /** The name this application actually reads and writes for one of its own stores. */
    private String internalTopic(String name) {
        return resolvedInternalPrefix.isEmpty() ? name : resolvedInternalPrefix + name;
    }

    /**
     * Whether a topic name is one this application writes for itself.
     *
     * <p>The marker is {@code <prefix>internal.}, so with no prefix this is exactly the
     * {@code startsWith("internal.")} test that was written literally at the one call site that
     * needed it, and with a prefix it follows. It stays a prefix test rather than an exact match
     * against the three configured names, because the demo stack's own marker topic
     * ({@code internal.demo.seeded}) was covered by the literal and should stay covered.
     */
    public boolean isInternalTopic(String name) {
        return name != null && name.startsWith(resolvedInternalPrefix + "internal.");
    }

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
    /**
     * Run one throw-away query after startup so the first real one does not pay for Calcite
     * class loading and Janino codegen. Measured on this codebase: without it the first SELECT
     * of a process takes ~5.5 s against ~1.2 s warm; with it, ~1.6 s. It runs on a daemon
     * thread after ApplicationReadyEvent, so it never delays readiness.
     */
    private boolean flinkWarmupEnabled = true;

    /**
     * How long one state restore may wait for the broker to answer its first metadata call at
     * startup.
     *
     * <p>Two beans read a topic while the context is still building — {@code MetricService}
     * (metric configurations) and {@code FieldMappingStore} (validated Process Mining mappings) —
     * and both did so under the Kafka client's 5 s default API timeout. Measured on this codebase
     * with no broker listening: those two waits are <b>10.1 s of a 14.5 s boot</b>, spent
     * discovering twice, over two clients, that nothing is there. The cost is paid on exactly the
     * deployment that is already in trouble.
     *
     * <p>Three seconds is a wide margin over a healthy metadata call (tens of milliseconds on a
     * local broker, well under a second through a TLS + SASL handshake) and it is what the
     * restore consumers use as their API timeout. Raise it for a cluster that is genuinely slow to
     * answer at boot; the restore that gives up now says so at WARN, so a value set too low is
     * visible in the log rather than silent.
     */
    private long startupRestoreTimeoutMs = 3000;

    private String auditHistoryTopic = "internal.audit.history";
    /**
     * Records read from the end of the audit-history topic when listing past runs. The topic is
     * append-only and a report holds one entry per topic, so the read is bounded and the response
     * states whether older runs exist beyond it.
     */
    private int auditHistoryMaxRecords = 200;
    private String metricsConfigTopic = "internal.metrics.config";
    /**
     * Where the field mappings validated in Process Mining are kept.
     *
     * <p>Keyed by mapping id, so the topic is meant to be compacted: only the last version of a
     * mapping matters. The store bounds what it holds in memory anyway, and a restore replays in
     * offset order, so an uncompacted topic costs a longer startup read rather than a wrong answer.
     */
    private String fieldMappingTopic = "internal.field.mappings";
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
    /**
     * Hard ceiling on how many topics one {@code POST /api/data-model} may analyse. The request
     * carries its own, lower, per-run value — this is what bounds it, so a mistyped number cannot
     * tie the inference pool up for hours. Each topic costs up to
     * {@code DataModelService.PER_TOPIC_TIMEOUT_MS}, so 100 is roughly a half-hour worst case,
     * the same order as the audit's own budget.
     */
    private int dataModelMaxTopics = 100;
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
     * Topics one {@code GET /api/dashboard/activity} may measure. The dashboard asks for the rows
     * it is displaying, so the natural ceiling is its largest page; a request past this is cut and
     * the response names what it left out.
     */
    private int activityMaxTopics = 100;
    /**
     * Ceiling on partitions x bucket boundaries for one activity read — the real unit of work,
     * since a topic with sixty partitions costs sixty times one with a single partition. Each
     * lookup is a broker-side index seek, no record is read, and the boundaries are issued in
     * parallel; 20 000 is roughly a page of twenty-five topics with a dozen partitions each.
     */
    private int activityMaxLookups = 20000;
    /** Window of the dashboard sparkline when the request names none. */
    private long activityDefaultWindowMs = 86400000L;
    /** Points in that series when the request names none — 24 buckets of one hour by default. */
    private int activityBuckets = 24;
    /**
     * Prefix for the consumer groups <strong>this application creates for itself</strong> — and
     * for nothing else. It renames the explorer's own readers (metadata, samples, searches,
     * traces, live sessions); it never touches a group belonging to the user's pipelines, and it
     * is not written into the generated Flink DDL, whose {@code flink_table_*} id is copied into
     * jobs this application does not run.
     *
     * <p>It exists because those ids are visible on the cluster — in {@code kafka-consumer-groups
     * .sh}, in a colleague's monitoring, in whatever convention an organisation applies to group
     * names. A deployment that must name them {@code acme.kse.} can, without the application
     * losing track of which groups are its own.
     *
     * <p>Absent, empty or unusable falls back to {@link
     * com.compagnonsdudev.kafkasqlexplorer.service.ExplorerConsumerGroups#DEFAULT_PREFIX} — see
     * {@code resolvePrefix}, which also appends a separator when the value has none. The default
     * stays recognised whatever this is set to, so changing it does not orphan the groups the
     * previous setting left behind.
     */
    private String consumerGroupPrefix = ExplorerConsumerGroups.DEFAULT_PREFIX;
    /**
     * Delete the consumer groups this application left on the cluster, at startup.
     *
     * <p>Off, and it is the only setting here that makes the app <em>write</em> to a cluster it
     * otherwise only reads. It exists because older builds committed offsets under a fresh random
     * group id on every metadata read, leaving thousands of empty groups behind for
     * {@code offsets.retention.minutes}; those are recognisable (see {@code
     * ExplorerConsumerGroups.isExplorerGroup}) but nothing removes them. Turning this on removes
     * only groups that carry one of those names <em>and</em> are EMPTY or DEAD — a running
     * explorer's live session holds members and is therefore never touched.
     */
    private boolean cleanupOwnGroups = false;
    /** Ceiling on one cleanup pass, so a cluster full of them cannot turn startup into a batch job. */
    private int cleanupOwnGroupsMax = 500;
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
    /**
     * Also export each watched group's backlog <em>in time</em> ({@code kafka_consumer_group_lag_seconds}).
     *
     * <p>Off by default and separate from {@link #lagMetricsTopics} because of what it costs: the
     * other lag gauges read metadata, this one opens a consumer and reads one record per lagging
     * partition, every refresh. On a short, deliberate topic list that is nothing; turned on
     * cluster-wide it would not be. A group caught up on every partition is published as 0 without
     * any read.
     */
    private boolean lagMetricsTime = false;
    private String flinkJobStorePath = "data/flink-jobs.json";
    private long flinkJobRetentionHours = 24;

    /** Default for {@link #settingsStorePath}, also read by the boot-time replay. */
    public static final String DEFAULT_SETTINGS_STORE_PATH = "data/settings.json";

    /**
     * Keep what the Settings page is used to change, so that stopping the application does not
     * discard it.
     *
     * <p>{@code POST /api/config} used to mutate two singletons and write nothing: the one screen
     * whose entire purpose is data entry was the only one whose input did not survive a restart.
     * On by default — an operator who repoints a cluster expects it to still be repointed
     * tomorrow — and the write is best-effort, so a read-only volume costs a warning and a saved
     * setting that lives for this process, never a refused save. See
     * {@link com.compagnonsdudev.kafkasqlexplorer.service.SettingsStore}.
     */
    private boolean settingsPersistence = true;

    /**
     * Where those settings are kept. Beside the Flink job store, under {@code data/}, which is a
     * named volume in every bundled stack.
     *
     * <p>A file rather than an {@code internal.*} Kafka topic, unlike every other store here, and
     * that is not a matter of taste: these settings <em>contain the bootstrap address</em>, so a
     * topic could neither receive a save that repoints the cluster nor be found at boot.
     */
    private String settingsStorePath = DEFAULT_SETTINGS_STORE_PATH;

    /**
     * Whether credentials entered on the Settings page — the SSL passwords, the Confluent secret,
     * the LLM API key — are written to that file.
     *
     * <p>True, because the alternative keeps half the promise: the operator would find the mode and
     * the keystore path restored and the connection failing, for a password nothing said had been
     * dropped. The file is created readable by its owner alone and the values never travel back out
     * of the API. Set it false for a deployment that will not have them on disk — the fields that
     * were left out are then named, in the save's answer and in the boot log, so what is missing is
     * at least stated.
     */
    private boolean settingsStoreSecrets = true;

    /**
     * Where the {@code CREATE TABLE} statements written in the SQL editor are kept, to be replayed
     * into Flink's in-memory catalogue at startup.
     *
     * <p>Governed by {@link #settingsPersistence} as well, since it answers the same question. A
     * table auto-registered from a Kafka topic is not stored here — it is re-derived on demand,
     * which is the point of auto-registration; what needed keeping is the definition somebody
     * typed, because losing it did not produce an error but a <em>substitution</em>, the generated
     * schema quietly taking the hand-written one's name. See
     * {@link com.compagnonsdudev.kafkasqlexplorer.service.FlinkTableStore}.
     */
    private String flinkTableStorePath = "data/flink-tables.json";

    public String getClusterName() {
        return clusterName;
    }

    public void setClusterName(String clusterName) {
        this.clusterName = clusterName;
    }

    public String getInternalTopicPrefix() {
        return internalTopicPrefix;
    }

    public void setInternalTopicPrefix(String internalTopicPrefix) {
        this.internalTopicPrefix = internalTopicPrefix;
        this.resolvedInternalPrefix = resolveInternalTopicPrefix(internalTopicPrefix);
    }

    public String getAuditHistoryTopic() {
        return internalTopic(auditHistoryTopic);
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
        return internalTopic(metricsConfigTopic);
    }

    public void setMetricsConfigTopic(String metricsConfigTopic) {
        this.metricsConfigTopic = metricsConfigTopic;
    }

    public String getFieldMappingTopic() {
        return internalTopic(fieldMappingTopic);
    }

    public void setFieldMappingTopic(String fieldMappingTopic) {
        this.fieldMappingTopic = fieldMappingTopic;
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

    public int getDataModelMaxTopics() {
        return dataModelMaxTopics;
    }

    public void setDataModelMaxTopics(int dataModelMaxTopics) {
        this.dataModelMaxTopics = dataModelMaxTopics;
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

    public int getActivityMaxTopics() {
        return activityMaxTopics;
    }

    public void setActivityMaxTopics(int activityMaxTopics) {
        this.activityMaxTopics = activityMaxTopics;
    }

    public int getActivityMaxLookups() {
        return activityMaxLookups;
    }

    public void setActivityMaxLookups(int activityMaxLookups) {
        this.activityMaxLookups = activityMaxLookups;
    }

    public long getActivityDefaultWindowMs() {
        return activityDefaultWindowMs;
    }

    public void setActivityDefaultWindowMs(long activityDefaultWindowMs) {
        this.activityDefaultWindowMs = activityDefaultWindowMs;
    }

    public int getActivityBuckets() {
        return activityBuckets;
    }

    public void setActivityBuckets(int activityBuckets) {
        this.activityBuckets = activityBuckets;
    }

    public String getConsumerGroupPrefix() {
        return consumerGroupPrefix;
    }

    public void setConsumerGroupPrefix(String consumerGroupPrefix) {
        this.consumerGroupPrefix = consumerGroupPrefix;
    }

    public boolean isCleanupOwnGroups() {
        return cleanupOwnGroups;
    }

    public void setCleanupOwnGroups(boolean cleanupOwnGroups) {
        this.cleanupOwnGroups = cleanupOwnGroups;
    }

    public int getCleanupOwnGroupsMax() {
        return cleanupOwnGroupsMax;
    }

    public void setCleanupOwnGroupsMax(int cleanupOwnGroupsMax) {
        this.cleanupOwnGroupsMax = cleanupOwnGroupsMax;
    }

    public List<String> getLagMetricsTopics() {
        return lagMetricsTopics;
    }

    public void setLagMetricsTopics(List<String> lagMetricsTopics) {
        this.lagMetricsTopics = lagMetricsTopics;
    }

    public boolean isLagMetricsTime() {
        return lagMetricsTime;
    }

    public void setLagMetricsTime(boolean lagMetricsTime) {
        this.lagMetricsTime = lagMetricsTime;
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

    public boolean isFlinkWarmupEnabled() {
        return flinkWarmupEnabled;
    }

    public void setFlinkWarmupEnabled(boolean flinkWarmupEnabled) {
        this.flinkWarmupEnabled = flinkWarmupEnabled;
    }

    public long getStartupRestoreTimeoutMs() {
        return startupRestoreTimeoutMs;
    }

    public void setStartupRestoreTimeoutMs(long startupRestoreTimeoutMs) {
        this.startupRestoreTimeoutMs = startupRestoreTimeoutMs;
    }

    public boolean isSettingsPersistence() {
        return settingsPersistence;
    }

    public void setSettingsPersistence(boolean settingsPersistence) {
        this.settingsPersistence = settingsPersistence;
    }

    public String getSettingsStorePath() {
        return settingsStorePath;
    }

    public void setSettingsStorePath(String settingsStorePath) {
        this.settingsStorePath = settingsStorePath;
    }

    public boolean isSettingsStoreSecrets() {
        return settingsStoreSecrets;
    }

    public void setSettingsStoreSecrets(boolean settingsStoreSecrets) {
        this.settingsStoreSecrets = settingsStoreSecrets;
    }

    public String getFlinkTableStorePath() {
        return flinkTableStorePath;
    }

    public void setFlinkTableStorePath(String flinkTableStorePath) {
        this.flinkTableStorePath = flinkTableStorePath;
    }
}
