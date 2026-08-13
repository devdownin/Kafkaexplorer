// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors
package com.compagnonsdudev.kafkasqlexplorer.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.compagnonsdudev.kafkasqlexplorer.config.ExplorerConfig;
import com.compagnonsdudev.kafkasqlexplorer.config.KafkaConfig;
import com.compagnonsdudev.kafkasqlexplorer.domain.*;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringSerializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import jakarta.annotation.PreDestroy;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

/**
 * Service providing cluster-wide health and performance auditing.
 * It combines Kafka metadata, Flink SQL queries, and naming convention heuristics
 * to provide a high-level view of data quality and throughput.
 */
@Service
public class AuditService {

    private static final Logger log = LoggerFactory.getLogger(AuditService.class);

    /** Cap on messages scanned per topic when counting duplicate keys in-process. */
    private static final int DUPLICATE_SCAN_MAX_MESSAGES = 10_000;
    /** Cap on messages scanned per topic when correlating flow latency in-process. */
    private static final int LATENCY_SCAN_MAX_MESSAGES = 1_000;
    /** Messages sampled per topic for format detection / poison checks. */
    private static final int POISON_SAMPLE_SIZE = 10;
    /**
     * Reports kept in memory. Every report holds one entry per topic, so an unbounded map grew
     * without limit on a long-running instance — one audit of a 2 000-topic cluster is already
     * hundreds of kilobytes and nothing ever evicted it.
     */
    private static final int MAX_RETAINED_RUNS = 20;

    private final KafkaAdminService kafkaAdminService;
    private final FlinkSqlService flinkSqlService;
    private final SchemaInferenceService schemaInferenceService;
    private final DdlGeneratorService ddlGeneratorService;
    private final NamingConventionService namingConventionService;
    private final MessageFieldExtractorService messageFieldExtractorService;
    private final KafkaConfig kafkaConfig;
    private final ExplorerConfig explorerConfig;
    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * Retained runs, newest last. Access is guarded by the map itself (see {@link #storeReport})
     * because eviction reads the insertion order — a plain ConcurrentHashMap has none.
     */
    private final Map<String, AuditReport> auditRuns = Collections.synchronizedMap(
        new LinkedHashMap<>(16, 0.75f, false) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, AuditReport> eldest) {
                return size() > MAX_RETAINED_RUNS;
            }
        });
    private volatile String lastAuditId = null;

    /**
     * The run currently in flight, {@code null} when idle. {@link #startAudit} refuses to queue a
     * second one: the audit executor is single-threaded, so clicking "Run new audit" five times
     * used to line up five full cluster scans.
     *
     * @param cancelled raised by {@link #cancelAudit}; the run polls it between topics
     */
    private record RunHandle(String auditId, AtomicBoolean cancelled) {
        RunHandle(String auditId) {
            this(auditId, new AtomicBoolean(false));
        }
    }

    /** Why a run stopped short of its scope. */
    private enum StopReason { REQUESTED, TIME_BUDGET }

    /**
     * The cluster's consumer groups for one run, re-read when the snapshot goes stale.
     *
     * <p>One read shared across topics is what makes the consumer-lag check affordable — otherwise
     * every topic re-lists every group of the cluster. But a snapshot taken once and kept for the
     * whole run compares committed positions from the run's first minute against end offsets read
     * half an hour later. That direction is safe (a lag can only be overstated, never understated,
     * and no finding turns on an overstated lag) yet a backlog overstated by thirty minutes of
     * traffic is one nobody can act on. The TTL bounds the staleness without giving the saving
     * back: at 60 s, a thirty-minute run pays about thirty reads rather than one per topic.
     *
     * <p>{@code synchronized} on purpose, refresh included. Four topic workers share this, and the
     * alternative — letting three of them read a stale snapshot while the fourth refreshes — buys a
     * few seconds of parallelism at the price of a report whose rows were measured against
     * different instants. Serialising the refresh is exactly what each of those threads would have
     * done on its own before this existed.
     */
    private final class GroupSnapshotHolder {
        private final int maxGroups;
        private final long ttlMs;
        private KafkaAdminService.GroupSnapshot snapshot;
        private long takenAt;

        GroupSnapshotHolder(int maxGroups, long ttlMs) {
            this.maxGroups = maxGroups;
            this.ttlMs = ttlMs;
        }

        synchronized KafkaAdminService.GroupSnapshot current() {
            long now = System.currentTimeMillis();
            if (snapshot == null || (ttlMs > 0 && now - takenAt >= ttlMs)) {
                snapshot = kafkaAdminService.groupSnapshot(maxGroups, null);
                takenAt = now;
            }
            return snapshot;
        }
    }

    private final AtomicReference<RunHandle> currentRun = new AtomicReference<>();

    /**
     * Dedicated executor for background audit runs. Spring's {@code @Async} cannot be used
     * here: {@code startAudit()} would self-invoke the async method, bypassing the proxy and
     * running the whole audit synchronously on the HTTP thread.
     */
    private final ExecutorService auditExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "cluster-audit-runner");
        t.setDaemon(true);
        return t;
    });

    /**
     * Bounded pool for per-topic audits. Fanning one commonPool task out per topic opened
     * dozens of simultaneous Kafka consumers (schema sampling + duplicate scans) on large
     * clusters; four workers keep the audit parallel without hammering the broker.
     */
    private final ExecutorService topicAuditExecutor = Executors.newFixedThreadPool(4, r -> {
        Thread t = new Thread(r);
        t.setName("audit-topic-worker-" + t.getId());
        t.setDaemon(true);
        return t;
    });

    /** Shared producer for audit-history persistence — one KafkaProducer per report is wasteful. */
    private volatile KafkaProducer<String, String> historyProducer;

    public AuditService(KafkaAdminService kafkaAdminService,
                        FlinkSqlService flinkSqlService,
                        SchemaInferenceService schemaInferenceService,
                        DdlGeneratorService ddlGeneratorService,
                        NamingConventionService namingConventionService,
                        MessageFieldExtractorService messageFieldExtractorService,
                        KafkaConfig kafkaConfig,
                        ExplorerConfig explorerConfig) {
        this.kafkaAdminService = kafkaAdminService;
        this.flinkSqlService = flinkSqlService;
        this.schemaInferenceService = schemaInferenceService;
        this.ddlGeneratorService = ddlGeneratorService;
        this.namingConventionService = namingConventionService;
        this.messageFieldExtractorService = messageFieldExtractorService;
        this.kafkaConfig = kafkaConfig;
        this.explorerConfig = explorerConfig;
    }

    /**
     * Outcome of a start request.
     *
     * @param auditId the run to follow — the newly started one, or the one already in flight
     * @param started false when a run was already in progress and this request started nothing
     */
    public record AuditStart(String auditId, boolean started) {}

    /**
     * The id of the run in flight, or null when the cluster is not being audited.
     *
     * <p>Read by the settings endpoint: repointing Kafka in the middle of a scan would have the
     * report describe two different clusters under one heading.
     */
    public String runningAuditId() {
        RunHandle handle = currentRun.get();
        return handle == null ? null : handle.auditId();
    }

    public AuditStart startAudit(AuditOptions options) {
        String auditId = UUID.randomUUID().toString();
        RunHandle handle = new RunHandle(auditId);
        if (!currentRun.compareAndSet(null, handle)) {
            // Hand back the in-flight run so the caller can attach to it instead of queueing.
            RunHandle inFlight = currentRun.get();
            if (inFlight != null) return new AuditStart(inFlight.auditId(), false);
            // The run finished between the CAS and the read — retry once, now unambiguously idle.
            if (!currentRun.compareAndSet(null, handle)) {
                RunHandle other = currentRun.get();
                return new AuditStart(other != null ? other.auditId() : auditId, false);
            }
        }

        lastAuditId = auditId;
        Map<String, Object> pending = new LinkedHashMap<>();
        pending.put("phase", "starting");
        pending.put("startedAt", System.currentTimeMillis());
        pending.put("options", describeOptions(options));
        storeReport(new AuditReport(auditId, AuditStatus.RUNNING, 0, 0, 0, 0,
            Collections.emptyList(), Collections.emptyList(), pending));

        auditExecutor.submit(() -> runAuditAsync(auditId, options));
        return new AuditStart(auditId, true);
    }

    /** Outcome of a cancel request. */
    public enum CancelResult {
        /** The stop flag is raised; the run winds down at its next checkpoint. */
        CANCELLING,
        /** The run had already finished (or failed) — nothing to stop. */
        ALREADY_FINISHED,
        /** No run with that id is known. */
        NOT_FOUND,
    }

    /**
     * Asks the run to stop. Cancellation is <strong>cooperative</strong>: the audit polls the flag
     * between topics and between phases, so the run ends within roughly one topic's work rather
     * than instantly. Interrupting mid-topic would abandon a KafkaConsumer or a Flink job
     * mid-flight; a topic's work is already bounded (a 500 ms poll loop, a 5 s query timeout).
     */
    public CancelResult cancelAudit(String auditId) {
        RunHandle handle = currentRun.get();
        if (handle != null && handle.auditId().equals(auditId)) {
            handle.cancelled().set(true);
            log.info("Cancellation requested for audit {}", auditId);
            return CancelResult.CANCELLING;
        }
        return auditRuns.containsKey(auditId) ? CancelResult.ALREADY_FINISHED : CancelResult.NOT_FOUND;
    }

    public AuditReport getAuditReport(String auditId) {
        return auditRuns.get(auditId);
    }

    public AuditReport getLastAuditReport() {
        return lastAuditId != null ? auditRuns.get(lastAuditId) : null;
    }

    private void storeReport(AuditReport report) {
        auditRuns.put(report.auditId(), report);
    }

    /** Echoes the checks that actually ran, so a report can be read without guessing its scope. */
    private static Map<String, Object> describeOptions(AuditOptions options) {
        Map<String, Object> described = new LinkedHashMap<>();
        described.put("checkSchema", options.checkSchema());
        described.put("checkExactCount", options.checkExactCount());
        described.put("checkPoisonMessages", options.checkPoisonMessages());
        described.put("checkDuplicates", options.checkDuplicates());
        described.put("checkFlows", options.checkFlows());
        described.put("topicPrefix", options.normalizedPrefix());
        return described;
    }

    /**
     * Executes the audit process (submitted to {@link #auditExecutor} by {@link #startAudit})
     * so the HTTP thread returns immediately with the audit id.
     * The results are stored in an in-memory map and also persisted to Kafka.
     */
    protected void runAuditAsync(String auditId, AuditOptions options) {
        long startedAt = System.currentTimeMillis();
        // A direct call (tests) has no handle registered; such a run is simply never cancelled.
        RunHandle handle = currentRun.get();
        AtomicBoolean cancelled = handle != null && handle.auditId().equals(auditId)
            ? handle.cancelled()
            : new AtomicBoolean(false);
        long budgetMs = explorerConfig.getAuditMaxDurationMs();
        long deadlineNanos = budgetMs > 0
            ? System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(budgetMs)
            : Long.MAX_VALUE;
        // One predicate for both ways a run can end early, so every checkpoint honours both.
        java.util.function.Supplier<StopReason> stopReason = () -> {
            if (cancelled.get()) return StopReason.REQUESTED;
            if (System.nanoTime() >= deadlineNanos) return StopReason.TIME_BUDGET;
            return null;
        };
        try {
            List<String> topics = kafkaAdminService.listTopics();
            // Optional prefix filter: audit only topics whose name starts with it.
            String prefix = options.normalizedPrefix();
            if (prefix != null) {
                topics = topics.stream().filter(t -> t.startsWith(prefix)).toList();
            }
            Map<String, Long> topicSizes = kafkaAdminService.getTopicsSize(topics);

            int totalTopics = topics.size();
            AtomicInteger completed = new AtomicInteger();
            publishProgress(auditId, options, startedAt, "topics", 0, totalTopics);

            // The cluster's groups, read once and shared across topics. Per topic, this used to
            // re-list every group of the cluster, re-describe up to two hundred of them and re-read
            // their offsets — the same answer, bought again for each of the hundreds of topics, and
            // the 30 s cache behind it expires many times over during a run that takes minutes.
            GroupSnapshotHolder groupSnapshot = options.checkConsumerLag()
                ? new GroupSnapshotHolder(explorerConfig.getConsumerGroupMaxGroups(),
                    explorerConfig.getAuditGroupSnapshotTtlMs())
                : null;

            // Parallelize topic auditing on a bounded pool (not the shared commonPool). Each topic
            // is isolated (auditTopicSafe) so one topic's failure degrades that topic to CRITICAL
            // rather than aborting the whole cluster audit via CompletableFuture.join().
            List<CompletableFuture<TopicAudit>> futures = topics.stream()
                .map(topic -> CompletableFuture
                    // Every topic is submitted up front, so cancelling cannot un-queue them:
                    // the check has to happen inside the task. Queued topics then cost nothing.
                    .supplyAsync(() -> stopReason.get() != null
                            ? null
                            : auditTopicSafe(topic, topicSizes.getOrDefault(topic, 0L), options,
                                groupSnapshot),
                        topicAuditExecutor)
                    // Publish progress as topics land so the UI can show a real bar instead of a
                    // decorative one — a full-cluster audit runs for minutes with no other signal.
                    .whenComplete((audit, error) -> publishProgress(
                        auditId, options, startedAt, "topics", completed.incrementAndGet(), totalTopics)))
                .toList();

            List<TopicAudit> topicAudits = futures.stream()
                .map(CompletableFuture::join)
                .filter(Objects::nonNull) // skipped after cancellation
                .collect(Collectors.toList());

            int criticalCount = (int) topicAudits.stream()
                .filter(a -> a.healthStatus() == HealthStatus.CRITICAL)
                .count();
            int warningCount = (int) topicAudits.stream()
                .filter(a -> a.healthStatus() == HealthStatus.WARNING)
                .count();

            // Flow correlation re-reads two topics per step; there is no point paying for it on a
            // run the operator has already stopped.
            List<FlowAudit> flowAudits = Collections.emptyList();
            if (options.checkFlows() && stopReason.get() == null) {
                publishProgress(auditId, options, startedAt, "flows", totalTopics, totalTopics);
                flowAudits = identifyAndAuditFlows(topicAudits);
            }

            // Sum what the table actually shows. Using topicSizes here meant the KPI stayed on the
            // offset estimate while the per-topic column showed the exact Flink counts.
            long totalMessages = topicAudits.stream().mapToLong(TopicAudit::messageCount).sum();

            StopReason stopped = stopReason.get();
            boolean wasCancelled = stopped != null;
            int auditedTopics = topicAudits.size();

            Map<String, Object> globalStats = new LinkedHashMap<>();
            globalStats.put("timestamp", System.currentTimeMillis());
            globalStats.put("startedAt", startedAt);
            globalStats.put("durationMs", System.currentTimeMillis() - startedAt);
            globalStats.put("options", describeOptions(options));
            // Scored over the topics actually audited, not the ones that were in scope: a run
            // stopped after 10 of 2 000 topics must not read as "1 990 topics are healthy".
            globalStats.put("healthScore", healthScore(auditedTopics, criticalCount, warningCount));
            List<String> scopeNotes = scopeNotes(options, topicAudits);
            if (wasCancelled) {
                globalStats.put("cancelled", true);
                globalStats.put("stopReason", stopped.name());
                globalStats.put("topicsInScope", totalTopics);
                String why = stopped == StopReason.TIME_BUDGET
                    ? "the " + formatBudget(budgetMs) + " time budget was exhausted"
                    : "the run was cancelled";
                scopeNotes.add(0, "Stopped after " + auditedTopics + " of " + totalTopics
                    + " topic(s) — " + why + ", the remaining topics were not audited.");
            }
            if (!scopeNotes.isEmpty()) globalStats.put("scopeNotes", scopeNotes);

            // KRaft upgrade completeness: features finalized below what the brokers support.
            // metadata.version lagging means a rolling upgrade was never finalized with
            // `kafka-features.sh upgrade` — new metadata features stay disabled cluster-wide.
            List<Map<String, Object>> laggingFeatures = kafkaAdminService.getLaggingFeatures();
            if (!laggingFeatures.isEmpty()) {
                globalStats.put("laggingFeatures", laggingFeatures);
                laggingFeatures.stream()
                    .filter(f -> "metadata.version".equals(f.get("feature")))
                    .findFirst()
                    .ifPresent(f -> globalStats.put("metadataVersionWarning", String.format(
                        "KRaft metadata.version is finalized at %s while brokers support up to %s — "
                        + "the cluster upgrade is incomplete until `kafka-features.sh upgrade "
                        + "--release-version <version>` is run (new metadata features stay disabled).",
                        f.get("finalizedVersion"), f.get("supportedMaxVersion"))));
            }

            AuditReport finalReport = new AuditReport(
                auditId,
                wasCancelled ? AuditStatus.CANCELLED : AuditStatus.COMPLETED,
                // totalTopics is what was audited, so the KPI matches the table it sits above;
                // globalStats.topicsInScope keeps the original figure for a cancelled run.
                auditedTopics,
                totalMessages,
                criticalCount,
                warningCount,
                topicAudits,
                flowAudits,
                globalStats
            );

            storeReport(finalReport);
            persistAuditHistory(finalReport);

        } catch (Exception e) {
            log.error("Audit failed for id {}", auditId, e);
            Map<String, Object> failure = new LinkedHashMap<>();
            // e.getMessage() is null for plenty of exceptions (NPE, TimeoutException…) and a null
            // here reached the UI as "audit finished, nothing to report".
            failure.put("error", e.getMessage() != null ? e.getMessage() : e.getClass().getName());
            failure.put("errorType", e.getClass().getSimpleName());
            failure.put("startedAt", startedAt);
            failure.put("durationMs", System.currentTimeMillis() - startedAt);
            failure.put("options", describeOptions(options));
            storeReport(new AuditReport(auditId, AuditStatus.FAILED, 0, 0, 0, 0,
                Collections.emptyList(), Collections.emptyList(), failure));
        } finally {
            // Release the slot whatever happened, otherwise a single failed run would block every
            // subsequent start for the lifetime of the process.
            if (handle != null) currentRun.compareAndSet(handle, null);
        }
    }

    /** "30m" / "90s" — used in the scope note explaining why a run stopped. */
    private static String formatBudget(long budgetMs) {
        long seconds = budgetMs / 1000;
        return seconds % 60 == 0 && seconds >= 60 ? (seconds / 60) + "m" : seconds + "s";
    }

    /**
     * Cluster health as a 0..1 ratio. A CRITICAL topic costs a full point, a WARNING half of one —
     * the previous "unhealthy vs total" ratio treated a single duplicate key and an unreadable
     * topic as the same loss.
     */
    private static double healthScore(int totalTopics, int criticalCount, int warningCount) {
        if (totalTopics <= 0) return 1.0;
        double penalty = (criticalCount + 0.5 * warningCount) / totalTopics;
        return Math.max(0.0, Math.min(1.0, 1.0 - penalty));
    }

    /** Replaces the in-flight RUNNING report so a poll returns real progress. */
    private void publishProgress(String auditId, AuditOptions options, long startedAt,
                                 String phase, int done, int total) {
        AuditReport current = auditRuns.get(auditId);
        if (current != null && current.status() != AuditStatus.RUNNING) return; // finished/replaced
        RunHandle handle = currentRun.get();
        boolean cancelling = handle != null && handle.auditId().equals(auditId) && handle.cancelled().get();
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("phase", phase);
        stats.put("topicsCompleted", done);
        stats.put("topicsTotal", total);
        stats.put("startedAt", startedAt);
        // Cancellation is cooperative, so the UI needs to say "stopping" rather than look frozen.
        if (cancelling) stats.put("cancelling", true);
        stats.put("options", describeOptions(options));
        storeReport(new AuditReport(auditId, AuditStatus.RUNNING, total, 0, 0, 0,
            Collections.emptyList(), Collections.emptyList(), stats));
    }

    /**
     * States what the run did NOT cover. Several checks quietly degrade to "0 findings" — a
     * duplicate scan reads only the first {@value #DUPLICATE_SCAN_MAX_MESSAGES} messages, and
     * a check whose prerequisite is disabled produces nothing at all. Reporting zero without
     * saying so reads as "clean", which is the one answer an audit must never fake.
     */
    private List<String> scopeNotes(AuditOptions options, List<TopicAudit> topicAudits) {
        List<String> notes = new ArrayList<>();
        if (options.checkDuplicates()) {
            notes.add("Duplicate detection scans at most " + DUPLICATE_SCAN_MAX_MESSAGES
                + " messages from the " + (scansDuplicatesFromEarliest() ? "start" : "end")
                + " of each topic — larger topics are only partially covered.");
        }
        if (options.checkPoisonMessages()) {
            notes.add("Poison-message detection parses a sample of " + POISON_SAMPLE_SIZE
                + " recent messages per topic, not the full topic.");
        }
        if (options.checkFlows()) {
            notes.add("Flow latency correlates the last " + LATENCY_SCAN_MAX_MESSAGES
                + " messages of each pair of consecutive topics on a shared \"id\" field.");
        }
        if (options.checkConsumerLag()) {
            long ttlMs = explorerConfig.getAuditGroupSnapshotTtlMs();
            notes.add("Consumer lag reads at most " + explorerConfig.getConsumerGroupMaxGroups()
                + " of the cluster's groups, shared across topics and re-read "
                + (ttlMs > 0 ? "every " + (ttlMs / 1000) + "s" : "never (once for the whole run)")
                + ", and reports only what no amount of waiting would resolve — a group that is "
                + "simply behind on a live topic is not a finding. Committed positions are therefore "
                + "up to that old while each topic's end offsets are read as it is audited, so a lag "
                + "can only be overstated, never understated.");
        }
        long degraded = topicAudits.stream()
            .filter(t -> t.issues().stream().anyMatch(i -> i.message().startsWith("Audit failed")))
            .count();
        if (degraded > 0) {
            notes.add(degraded + " topic(s) could not be audited and are reported as CRITICAL.");
        }
        return notes;
    }

    /**
     * Isolates a single topic's audit: any failure (Kafka sampling error, schema inference, …) is
     * turned into a degraded CRITICAL {@link TopicAudit} carrying the reason, so one bad topic
     * never fails the whole cluster audit. CRITICAL and not WARNING: no verdict at all is the
     * worst outcome an audit can produce for a topic.
     */
    private TopicAudit auditTopicSafe(String topicName, long approximateCount, AuditOptions options,
                                      GroupSnapshotHolder groupSnapshot) {
        try {
            return auditTopic(topicName, approximateCount, options, groupSnapshot);
        } catch (Exception e) {
            String reason = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            log.warn("Audit failed for topic '{}': {}", topicName, reason);
            return new TopicAudit(topicName, approximateCount, MessageFormat.AUTO, 0, 0L,
                HealthStatus.CRITICAL, List.of(TopicIssue.critical("Audit failed: " + reason)));
        }
    }

    private TopicAudit auditTopic(String topicName, long approximateCount, AuditOptions options,
                                  GroupSnapshotHolder groupSnapshot) {
        MessageFormat format = MessageFormat.AUTO;
        Map<String, String> schema = Collections.emptyMap();
        List<TopicIssue> issues = new ArrayList<>();

        // One sample serves format detection, schema inference and the poison check. Each of the
        // three used to open its own KafkaConsumer (describeTopics + assign + seek + poll) to read
        // the very same ten messages — three round trips per topic, times every topic.
        List<String> samples = (options.checkSchema() || options.checkPoisonMessages())
            ? kafkaAdminService.getSampleMessages(topicName, POISON_SAMPLE_SIZE)
            : Collections.emptyList();

        if (options.checkSchema()) {
            format = schemaInferenceService.detectFormat(topicName, samples);
            schema = schemaInferenceService.inferSchema(topicName, format, samples);
            registerTableIfNeeded(topicName, schema, format);
        } else if (options.checkPoisonMessages()) {
            // Poison detection needs to know what "well-formed" means for this topic. Without the
            // schema pass the format stayed AUTO and the check silently matched nothing, so every
            // topic came back with 0 poison messages.
            format = dominantFormat(samples);
        }

        long exactCount = approximateCount;
        if (options.checkExactCount()) {
            ExactCount counted = getExactCount(topicName, approximateCount);
            exactCount = counted.value();
            if (counted.error() != null) {
                // The topic's data is fine; it is the measurement that is degraded.
                issues.add(TopicIssue.warning(
                    "Exact count unavailable (" + counted.error() + ") — showing the offset estimate."));
            }
        }

        DuplicateScan duplicateScan = options.checkDuplicates()
            ? detectDuplicates(topicName, schema)
            : DuplicateScan.skipped();

        int poisonCount = 0;
        if (options.checkPoisonMessages()) {
            for (String sample : samples) {
                if (!matchesFormat(sample, format)) poisonCount++;
            }
        }

        if (poisonCount > 0) {
            // Unparseable payloads in the topic itself — consumers downstream will break on them.
            issues.add(TopicIssue.critical("Detected " + poisonCount + " malformed message(s) out of "
                + samples.size() + " sampled."));
        }
        if (options.checkExactCount() && approximateCount > 0 && exactCount == 0) {
            issues.add(TopicIssue.critical("Flink SQL returned 0 rows despite Kafka having messages."));
        }
        if (duplicateScan.duplicateKeys() > 0) {
            // Duplicates are frequently legitimate (updates keyed by entity id), so they are a
            // signal to look at, not a defect on their own.
            issues.add(TopicIssue.warning("Detected " + duplicateScan.duplicateKeys()
                + " duplicate value(s) of '" + duplicateScan.keyField()
                + "' over the " + (scansDuplicatesFromEarliest() ? "first " : "last ")
                + duplicateScan.scanned() + " message(s)."));
        }

        if (options.checkConsumerLag()) {
            issues.addAll(consumerLagIssues(topicName, groupSnapshot));
        }

        HealthStatus status = issues.stream()
            .map(TopicIssue::severity)
            .reduce(HealthStatus.HEALTHY, HealthStatus::max);

        return new TopicAudit(topicName, exactCount, format, poisonCount,
            duplicateScan.duplicateKeys(), status, issues);
    }

    /**
     * What the topic's consumer groups say about it.
     *
     * <p>Deliberately not a threshold on the lag itself: a large but draining backlog is ordinary
     * on a live topic, and any number one picked would be arbitrary — the same reason a message
     * count moving is not a finding here. What is reported is structural, and each case means
     * something a number could not say:
     *
     * <ul>
     *   <li><b>STALLED</b> — records waiting with no member assigned to this topic. Nothing will
     *       drain them however long one waits, so this is CRITICAL.</li>
     *   <li><b>PARTIAL</b> — never committed on some partitions. The reported lag does not count
     *       what the group ignores, so the backlog is larger than it looks.</li>
     *   <li><b>AHEAD</b> — a committed offset past the end of the log, which an offset reset or a
     *       recreated topic leaves behind. The group will skip whatever arrives before it.</li>
     * </ul>
     *
     * <p>A group that could not be read is reported as a degraded measurement rather than
     * dropped: silence would be indistinguishable from "this topic is fine".
     */
    private List<TopicIssue> consumerLagIssues(String topicName, GroupSnapshotHolder groupSnapshot) {
        TopicConsumers consumers;
        try {
            consumers = groupSnapshot == null
                ? kafkaAdminService.getTopicConsumers(topicName, explorerConfig.getConsumerGroupMaxGroups())
                : kafkaAdminService.getTopicConsumers(topicName, groupSnapshot.current());
        } catch (Exception e) {
            return List.of(TopicIssue.warning("Consumer groups could not be read ("
                + (e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName()) + ")."));
        }

        List<TopicIssue> found = new ArrayList<>();
        if (!consumers.available()) {
            // A read that failed produces an empty group list, which used to leave this check
            // silent — indistinguishable from "every group on this topic is healthy". A check that
            // could not run says so, like every other one here.
            found.add(TopicIssue.warning("Consumer groups could not be read for this topic ("
                + (consumers.warnings().isEmpty() ? "no reason given" : consumers.warnings().get(0))
                + ")."));
            return found;
        }
        for (ConsumerGroupLag group : consumers.groups()) {
            switch (group.health()) {
                case STALLED -> found.add(TopicIssue.critical("Consumer group '" + group.groupId()
                    + "' is " + group.totalLag() + " message(s) behind with no member assigned to this "
                    + "topic — nothing is draining it."));
                case PARTIAL -> found.add(TopicIssue.warning("Consumer group '" + group.groupId()
                    + "' has never committed on " + group.partitionsWithoutCommit() + " of this topic's "
                    + group.partitions().size() + " partition(s); their backlog is not counted in its lag."));
                case AHEAD -> found.add(TopicIssue.warning("Consumer group '" + group.groupId()
                    + "' has a committed offset past the end of the log — it will skip whatever "
                    + "arrives before it catches back up."));
                case UNKNOWN -> found.add(TopicIssue.warning("Consumer group '" + group.groupId()
                    + "' could not be read (" + group.error() + ")."));
                // BEHIND and CAUGHT_UP are what a healthy live topic looks like.
                case BEHIND, CAUGHT_UP -> { }
            }
        }
        return found;
    }

    /** Format shared by most of the sample, used when the schema pass is disabled. */
    private static MessageFormat dominantFormat(List<String> samples) {
        int json = 0;
        int xml = 0;
        for (String sample : samples) {
            if (sample == null) continue;
            String trimmed = sample.trim();
            if (trimmed.startsWith("{") || trimmed.startsWith("[")) json++;
            else if (trimmed.startsWith("<")) xml++;
        }
        if (json == 0 && xml == 0) return MessageFormat.AUTO;
        return json >= xml ? MessageFormat.JSON : MessageFormat.XML;
    }

    /**
     * A message is poison when it does not parse as the topic's format. The previous check only
     * looked at the first character, so a truncated {@code {"id":} counted as valid JSON — the
     * exact payload a poison check exists to catch.
     */
    private boolean matchesFormat(String message, MessageFormat format) {
        if (message == null || message.isBlank()) return format != MessageFormat.JSON && format != MessageFormat.XML;
        String trimmed = message.trim();
        if (format == MessageFormat.JSON) {
            if (!(trimmed.startsWith("{") || trimmed.startsWith("["))) return false;
            try {
                objectMapper.readTree(trimmed);
                return true;
            } catch (Exception e) {
                return false;
            }
        }
        if (format == MessageFormat.XML) {
            if (!trimmed.startsWith("<")) return false;
            // extractLeafFields returns an empty map on a parse failure, and a well-formed XML
            // document always has at least one leaf.
            return !messageFieldExtractorService.extractLeafFields(trimmed).isEmpty();
        }
        return true; // AUTO / AVRO: nothing to validate against
    }

    private void registerTableIfNeeded(String topicName, Map<String, String> schema, MessageFormat format) {
        String tableName = DdlGeneratorService.toTableName(topicName);
        if (!flinkSqlService.listTables().contains(tableName)) {
            String ddl = ddlGeneratorService.generateDdl(topicName, schema, format);
            QueryResult ddlResult = flinkSqlService.executeSql(new QueryRequest(ddl, null, null, null, null));
            if (ddlResult.error() != null) {
                log.warn("Could not register table '{}' for audit: {}", tableName, ddlResult.error());
            }
        }
    }

    /** Exact count, plus the reason it fell back to the offset estimate (null when exact). */
    private record ExactCount(long value, String error) {}

    private ExactCount getExactCount(String topicName, long approximateCount) {
        String tableName = DdlGeneratorService.toTableName(topicName);
        QueryResult countResult = flinkSqlService.executeSql(new QueryRequest(
            "SELECT COUNT(*) AS metric_value FROM " + tableName, null, 1, explorerConfig.getAuditQueryTimeoutMs(), null));
        if (countResult.error() != null) {
            // Falling back silently made the "exact count" column indistinguishable from the
            // offset estimate it is supposed to correct.
            return new ExactCount(approximateCount, truncate(countResult.error()));
        }
        if (!countResult.rows().isEmpty()) {
            // The direct Kafka engine aliases aggregates (metric_value / count_all, Double values)
            // while Flink names them EXPR$0 (Long) — accept the first numeric value either way.
            Long value = firstNumericValue(countResult.rows().get(0));
            if (value != null) return new ExactCount(value, null);
        }
        return new ExactCount(approximateCount, "query returned no value");
    }

    private static String truncate(String message) {
        String single = message.replaceAll("\\s+", " ").trim();
        return single.length() > 160 ? single.substring(0, 157) + "…" : single;
    }

    private Long firstNumericValue(Map<String, Object> row) {
        for (Object val : row.values()) {
            if (val instanceof Number number) return number.longValue();
        }
        return null;
    }

    /**
     * Outcome of a duplicate scan: how many distinct values repeated, which key was grouped on,
     * and how many messages that verdict is based on.
     */
    private record DuplicateScan(long duplicateKeys, String keyField, int scanned) {
        static DuplicateScan skipped() {
            return new DuplicateScan(0, null, 0);
        }
    }

    /**
     * Counts keys that appear more than once, scanning messages directly.
     * The previous SQL implementation (GROUP BY subquery with HAVING) is not supported by
     * the direct Kafka SELECT engine and silently returned 0 for every topic.
     */
    private DuplicateScan detectDuplicates(String topicName, Map<String, String> schema) {
        String keyField = namingConventionService.findKeyField(schema);

        // Recent by default: every other check samples recent messages, and on a topic with
        // retention the oldest surviving records are rarely what an operator is asking about.
        // `explorer.audit-duplicate-scan-from: EARLIEST` restores the previous behaviour.
        boolean fromEarliest = scansDuplicatesFromEarliest();
        List<ConsumerRecord<String, String>> records = fromEarliest
            ? kafkaAdminService.getEarliestRecords(topicName, DUPLICATE_SCAN_MAX_MESSAGES)
            : kafkaAdminService.getRecentRecords(topicName, DUPLICATE_SCAN_MAX_MESSAGES);
        if (records.isEmpty()) return DuplicateScan.skipped();

        Map<String, Integer> keyCounts = new HashMap<>();
        int scanned = 0;
        for (ConsumerRecord<String, String> record : records) {
            String key;
            if (keyField != null) {
                String value = record.value();
                if (value == null || value.isBlank()) continue;
                key = extractField(value, keyField);
            } else {
                // No id-like field in the schema — which is also the case whenever schema
                // inference is switched off. Grouping on the Kafka record key is the meaningful
                // fallback, and the previous "give up and report 0" made the check look clean.
                key = record.key();
            }
            if (key == null || key.isBlank()) continue;
            keyCounts.merge(key, 1, Integer::sum);
            scanned++;
        }
        if (scanned == 0) return DuplicateScan.skipped();

        long duplicates = keyCounts.values().stream().filter(count -> count > 1).count();
        return new DuplicateScan(duplicates, keyField != null ? keyField : "Kafka record key", scanned);
    }

    private boolean scansDuplicatesFromEarliest() {
        return "EARLIEST".equalsIgnoreCase(explorerConfig.getAuditDuplicateScanFrom());
    }

    /**
     * Extracts a field value from a raw JSON/XML message. XML leaf paths are prefixed with
     * the root tag ("order.id"), so a suffix match is attempted after the direct lookup.
     */
    private String extractField(String message, String field) {
        Map<String, String> fields = messageFieldExtractorService.extractLeafFields(message);
        String direct = fields.get(field);
        if (direct != null) return direct;
        String suffix = "." + field;
        for (Map.Entry<String, String> entry : fields.entrySet()) {
            if (entry.getKey().endsWith(suffix)) return entry.getValue();
        }
        return null;
    }

    /**
     * Heuristically groups topics into logical business processes (Flows)
     * and calculates metrics like latency.
     */
    private List<FlowAudit> identifyAndAuditFlows(List<TopicAudit> topicAudits) {
        List<FlowAudit> initialFlows = namingConventionService.identifyFlows(topicAudits);
        List<FlowAudit> flowsWithLatency = new ArrayList<>();

        // Every topic in the middle of a flow is both a target (of the previous pair) and a
        // source (of the next one), so an unmemoized calculateLatency read each one from Kafka
        // twice. One cache per run: a topic is fetched at most once, whatever its position.
        Map<String, Map<String, Long>> idTimesByTopic = new HashMap<>();

        for (FlowAudit flow : initialFlows) {
            List<FlowAudit.StepInfo> stepsWithLatency = new ArrayList<>();
            for (int i = 0; i < flow.steps().size(); i++) {
                FlowAudit.StepInfo step = flow.steps().get(i);
                Long latency = null;
                if (i > 0) {
                    latency = calculateLatency(flow.steps().get(i - 1).topicName(), step.topicName(), idTimesByTopic);
                }
                stepsWithLatency.add(new FlowAudit.StepInfo(step.topicName(), step.count(), step.throughputPercentage(), latency));
            }
            flowsWithLatency.add(new FlowAudit(flow.flowName(), stepsWithLatency, flow.overallHealthScore()));
        }

        return flowsWithLatency;
    }

    /**
     * Average delta between Kafka record timestamps of messages sharing the same "id" field
     * in the source and target topics, computed over recent messages. The previous SQL
     * implementation used a JOIN, which the direct Kafka SELECT engine does not support,
     * so latency was silently always null.
     */
    private Long calculateLatency(String sourceTopic, String targetTopic,
                                  Map<String, Map<String, Long>> idTimesByTopic) {
        Map<String, Long> sourceTimesById = firstSeenById(sourceTopic, idTimesByTopic);
        Map<String, Long> targetTimesById = firstSeenById(targetTopic, idTimesByTopic);
        if (sourceTimesById.isEmpty() || targetTimesById.isEmpty()) return null;

        long totalDeltaMs = 0;
        int matched = 0;
        for (Map.Entry<String, Long> target : targetTimesById.entrySet()) {
            Long sourceTs = sourceTimesById.get(target.getKey());
            if (sourceTs != null && target.getValue() > sourceTs) {
                totalDeltaMs += target.getValue() - sourceTs;
                matched++;
            }
        }
        return matched == 0 ? null : totalDeltaMs / matched;
    }

    /**
     * Earliest Kafka record timestamp per "id" over the topic's recent messages — the first
     * time each business event was seen there. Memoized for the duration of one audit run.
     *
     * <p>Both sides of a pair are now reduced the same way: one delta per id instead of one per
     * target record, so an id republished five times downstream no longer weighs five times more
     * in the average.
     */
    private Map<String, Long> firstSeenById(String topic, Map<String, Map<String, Long>> cache) {
        return cache.computeIfAbsent(topic, name -> {
            List<ConsumerRecord<String, String>> records =
                kafkaAdminService.getRecentRecords(name, LATENCY_SCAN_MAX_MESSAGES);
            Map<String, Long> timesById = new HashMap<>();
            for (ConsumerRecord<String, String> record : records) {
                if (record.value() == null) continue;
                String id = extractField(record.value(), "id");
                if (id != null && !id.isBlank()) {
                    timesById.merge(id, record.timestamp(), Math::min);
                }
            }
            return timesById;
        });
    }

    @PreDestroy
    public void shutdown() {
        // Two pools, one shared deadline with every other service's — see ShutdownBudget.
        ShutdownBudget.shutdown(auditExecutor);
        ShutdownBudget.shutdown(topicAuditExecutor);
        closeHistoryProducer();
    }

    protected void persistAuditHistory(AuditReport report) {
        try {
            String value = objectMapper.writeValueAsString(report);
            historyProducer().send(new ProducerRecord<>(explorerConfig.getAuditHistoryTopic(), report.auditId(), value)).get();
            log.info("Persisted audit {} to history topic", report.auditId());
        } catch (Exception e) {
            log.warn("Failed to persist audit history: {}", e.getMessage());
            // Drop the producer so the next attempt reconnects with fresh state/config
            closeHistoryProducer();
        }
    }

    private KafkaProducer<String, String> historyProducer() {
        KafkaProducer<String, String> existing = historyProducer;
        if (existing != null) return existing;
        synchronized (this) {
            if (historyProducer == null) {
                Properties props = new Properties();
                props.putAll(kafkaConfig.getKafkaProperties());
                props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
                props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
                props.put(ProducerConfig.MAX_BLOCK_MS_CONFIG, 500); // Don't block for long in tests or if Kafka is down
                historyProducer = new KafkaProducer<>(props);
            }
            return historyProducer;
        }
    }

    private synchronized void closeHistoryProducer() {
        if (historyProducer != null) {
            try {
                // KafkaProducer.close() with no argument blocks until every buffered record
                // is acknowledged, however long that takes. This is called from @PreDestroy
                // and from the failure path of persistAuditHistory() — both of which run
                // precisely when the broker may be unreachable, so an unbounded close would
                // hang shutdown until Docker SIGKILLed the JVM.
                historyProducer.close(Duration.ofSeconds(5));
            } catch (Exception ignored) {
            }
            historyProducer = null;
        }
    }
}
