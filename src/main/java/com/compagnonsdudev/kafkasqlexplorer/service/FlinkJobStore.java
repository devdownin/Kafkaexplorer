// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors
package com.compagnonsdudev.kafkasqlexplorer.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.compagnonsdudev.kafkasqlexplorer.config.ExplorerConfig;
import com.compagnonsdudev.kafkasqlexplorer.domain.FlinkJobHistoryEntry;
import com.compagnonsdudev.kafkasqlexplorer.domain.FlinkManagedJobDetails;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Objects;

/**
 * What ran through the Flink engine, kept across a restart.
 *
 * <p>Three rules are load-bearing and none of them is visible from the shape of the code.
 *
 * <p><b>It is bounded twice, and the second bound is the one that binds.</b> Retention is a
 * duration ({@code explorer.flink-job-retention-hours}), which is the right rule for the jobs this
 * store was written for — {@code INSERT INTO} statements submitted in Flink Job mode, a handful a
 * day. It is the wrong rule for what actually writes here: <em>every</em> statement the planner
 * answers registers a job, so a metric refreshing on a thirty-second loop contributes ~2 900
 * records a day and the SQL editor one per Run. Each write serialises the whole list, so an
 * unbounded store is not merely a large file but a rewrite whose cost grows with the day's
 * traffic. {@link #MAX_RETAINED_JOBS} caps it, dropping the oldest <em>ended</em> jobs first and
 * never a job still running — same shape as {@code AuditService.MAX_RETAINED_RUNS} and
 * {@code FieldMappingStore.MAX_ENTRIES}.
 *
 * <p><b>The SQL is masked on the way in.</b> A statement carries Kafka client properties, so a
 * hand-written {@code CREATE TABLE} embeds the SSL passwords and the Confluent
 * {@code sasl.jaas.config} secret — and this store both writes them to {@code data/} and serves
 * them back through {@code GET /api/query/jobs}. The rule the rest of the codebase follows is that
 * DDL returned to the UI or written to a file goes through
 * {@link DdlGeneratorService#maskSensitiveProperties}; nothing here replays the SQL, so masking it
 * at the door costs nothing. The reason it mattered is the case that looks harmless: a
 * {@code CREATE TABLE} submitted in Job mode is <em>rejected</em>, and the rejection path stored
 * the statement verbatim.
 *
 * <p><b>The file is replaced, never written in place</b> ({@link JsonStoreFile}, which exists for
 * exactly this and which this store was the one not to use). It is read at boot, which is when an
 * interrupted write from the previous run surfaces — as a file that will not parse, and therefore
 * as the loss of every job record rather than of the one being written.
 */
@Service
public class FlinkJobStore {

    private static final Logger log = LoggerFactory.getLogger(FlinkJobStore.class);

    /**
     * How many job records are kept, whatever the retention window says.
     *
     * <p>Sized so an ordinary editing session is entirely inside it while the file stays a few
     * hundred kilobytes; a job still running is never dropped, so the bound cannot cost the
     * dashboard a job it is meant to show.
     */
    static final int MAX_RETAINED_JOBS = 200;

    private final ObjectMapper objectMapper;
    private final java.nio.file.Path storePath;
    private final long retentionMs;
    /**
     * Insertion-ordered and guarded by {@code this}: every method that touches it is
     * {@code synchronized}, and the order is what makes the retained set reproducible when two
     * jobs share a timestamp.
     */
    private final Map<String, FlinkManagedJobDetails> jobs = new LinkedHashMap<>();

    public FlinkJobStore(ExplorerConfig explorerConfig) {
        this.objectMapper = new ObjectMapper();
        this.storePath = java.nio.file.Path.of(explorerConfig.getFlinkJobStorePath()).toAbsolutePath().normalize();
        this.retentionMs = Duration.ofHours(explorerConfig.getFlinkJobRetentionHours()).toMillis();
        load();
    }

    public synchronized FlinkManagedJobDetails create(
        String queryId,
        String flinkJobId,
        String statementType,
        String executionMode,
        String status,
        String statusDetail,
        String sql,
        long startedAt,
        String errorMessage
    ) {
        long now = System.currentTimeMillis();
        Long endedAt = isTerminal(status) ? now : null;
        FlinkManagedJobDetails details = new FlinkManagedJobDetails(
            queryId,
            flinkJobId,
            statementType,
            executionMode,
            status,
            statusDetail,
            DdlGeneratorService.maskSensitiveProperties(sql),
            startedAt,
            endedAt,
            false,
            null,
            errorMessage,
            now,
            List.of(new FlinkJobHistoryEntry(now, status, firstNonBlank(statusDetail, errorMessage)))
        );
        jobs.put(queryId, details);
        flush();
        return details;
    }

    /**
     * Folds an observation into a job record, and writes only when the observation changed
     * something.
     *
     * <p>The caller is a status sweep that runs on every dashboard poll — five seconds by
     * default, once per live job — and a running job answers {@code RUNNING} every time. Writing
     * regardless rewrote the whole file on each of those polls to record that nothing had
     * happened, so {@code lastUpdatedAt} is deliberately not a reason to write: it means "when
     * this record last changed", and a record that did not change did not change.
     */
    public synchronized FlinkManagedJobDetails update(
        String queryId,
        String status,
        String statusDetail,
        String errorMessage,
        boolean cancelRequested,
        Long cancelRequestedAt,
        Long endedAt,
        String flinkJobId
    ) {
        FlinkManagedJobDetails existing = jobs.get(queryId);
        if (existing == null) {
            return null;
        }

        long now = System.currentTimeMillis();
        String nextStatus = firstNonBlank(status, existing.status());
        String nextDetail = firstNonBlank(statusDetail, existing.statusDetail());
        String nextError = firstNonBlank(errorMessage, existing.errorMessage());
        Long nextEndedAt = endedAt != null ? endedAt : (isTerminal(nextStatus) ? firstNonNull(existing.endedAt(), now) : existing.endedAt());
        Long nextCancelRequestedAt = cancelRequested ? firstNonNull(cancelRequestedAt, existing.cancelRequestedAt(), now) : existing.cancelRequestedAt();
        String nextFlinkJobId = firstNonBlank(flinkJobId, existing.flinkJobId());

        List<FlinkJobHistoryEntry> history = new ArrayList<>(historyOrEmpty(existing.history()));
        String historyDetail = firstNonBlank(nextDetail, nextError);
        if (history.isEmpty()
            || !history.get(history.size() - 1).status().equals(nextStatus)
            || !safeEquals(history.get(history.size() - 1).detail(), historyDetail)) {
            history.add(new FlinkJobHistoryEntry(now, nextStatus, historyDetail));
        }

        FlinkManagedJobDetails updated = new FlinkManagedJobDetails(
            existing.queryId(),
            nextFlinkJobId,
            existing.statementType(),
            existing.executionMode(),
            nextStatus,
            nextDetail,
            existing.sql(),
            existing.startedAt(),
            nextEndedAt,
            cancelRequested || existing.cancelRequested(),
            nextCancelRequestedAt,
            nextError,
            now,
            List.copyOf(history)
        );
        if (sameSubstance(existing, updated)) {
            return existing;
        }
        jobs.put(queryId, updated);
        flush();
        return updated;
    }

    public synchronized List<FlinkManagedJobDetails> listAll() {
        if (prune()) {
            flush();
        }
        return sortedNewestFirst();
    }

    public synchronized List<FlinkManagedJobDetails> listActive() {
        return listAll().stream()
            .filter(job -> !isTerminal(job.status()))
            .toList();
    }

    public synchronized Optional<FlinkManagedJobDetails> findById(String queryId) {
        if (prune()) {
            flush();
        }
        return Optional.ofNullable(jobs.get(queryId));
    }

    private List<FlinkManagedJobDetails> sortedNewestFirst() {
        return jobs.values().stream()
            .sorted(Comparator.comparingLong(FlinkManagedJobDetails::startedAt).reversed())
            .toList();
    }

    private void load() {
        JsonNode tree = JsonStoreFile.read(objectMapper, storePath, "the Flink job store");
        if (tree == null || !tree.isArray()) {
            return;
        }
        List<FlinkManagedJobDetails> loaded;
        try {
            loaded = objectMapper.convertValue(tree, new TypeReference<List<FlinkManagedJobDetails>>() {});
        } catch (IllegalArgumentException e) {
            log.warn("Ignoring the Flink job store at {}: it does not hold job records ({}).",
                storePath, SqlErrorClassifier.explain(e));
            return;
        }

        long now = System.currentTimeMillis();
        for (FlinkManagedJobDetails job : loaded) {
            FlinkManagedJobDetails normalized = job;
            if (!isTerminal(job.status())) {
                List<FlinkJobHistoryEntry> history = new ArrayList<>(historyOrEmpty(job.history()));
                history.add(new FlinkJobHistoryEntry(now, "UNKNOWN", "Recovered from persisted state without live Flink JobClient"));
                normalized = new FlinkManagedJobDetails(
                    job.queryId(),
                    job.flinkJobId(),
                    job.statementType(),
                    job.executionMode(),
                    "UNKNOWN",
                    "Recovered from persisted state without live Flink JobClient",
                    job.sql(),
                    job.startedAt(),
                    firstNonNull(job.endedAt(), now),
                    job.cancelRequested(),
                    job.cancelRequestedAt(),
                    job.errorMessage(),
                    now,
                    List.copyOf(history)
                );
            }
            jobs.put(normalized.queryId(), normalized);
        }
        // A prune on the way in has to reach the file, or a store nothing ever writes to again
        // keeps its expired records for good — retention that only applies while the application
        // is busy is not retention.
        if (prune()) {
            flush();
        }
    }

    /**
     * Applies both bounds, and says whether it removed anything.
     *
     * <p>The caller flushes on {@code true}: a prune that only ever ran on the way to a write
     * meant the file shrank when something else happened to change, never because time had passed.
     */
    private boolean prune() {
        long now = System.currentTimeMillis();
        boolean removed = jobs.entrySet().removeIf(entry -> {
            FlinkManagedJobDetails job = entry.getValue();
            return job.endedAt() != null && (now - job.endedAt()) > retentionMs;
        });

        if (jobs.size() <= MAX_RETAINED_JOBS) {
            return removed;
        }
        // Oldest ended first: a job still running is what the dashboard, the 409 repoint guard and
        // the lineage graph are asking about, so the bound must never be what removes it.
        List<FlinkManagedJobDetails> droppable = jobs.values().stream()
            .filter(job -> job.endedAt() != null)
            .sorted(Comparator.comparingLong((FlinkManagedJobDetails job) -> job.endedAt())
                .thenComparingLong(FlinkManagedJobDetails::startedAt))
            .toList();
        int excess = jobs.size() - MAX_RETAINED_JOBS;
        for (FlinkManagedJobDetails job : droppable) {
            if (excess <= 0) break;
            jobs.remove(job.queryId());
            excess--;
            removed = true;
        }
        return removed;
    }

    private void flush() {
        prune();
        try {
            JsonStoreFile.replace(objectMapper, storePath, sortedNewestFirst());
        } catch (IOException e) {
            log.warn("Failed to persist Flink jobs to {}: {}", storePath, e.getMessage());
        }
    }

    /**
     * Everything a reader would notice, {@code lastUpdatedAt} excluded — see {@link #update}.
     */
    private static boolean sameSubstance(FlinkManagedJobDetails left, FlinkManagedJobDetails right) {
        return Objects.equals(left.flinkJobId(), right.flinkJobId())
            && Objects.equals(left.status(), right.status())
            && Objects.equals(left.statusDetail(), right.statusDetail())
            && Objects.equals(left.errorMessage(), right.errorMessage())
            && left.cancelRequested() == right.cancelRequested()
            && Objects.equals(left.cancelRequestedAt(), right.cancelRequestedAt())
            && Objects.equals(left.endedAt(), right.endedAt())
            && Objects.equals(left.history(), right.history());
    }

    /**
     * Whether a status says the job is over.
     *
     * <p>Package-visible because {@code FlinkSqlService} has to ask the same question — a
     * cancellation aimed at a job that already ended must not overwrite how it ended — and two
     * definitions of "terminal" is how they come to disagree.
     *
     * <p>{@code UNKNOWN} is here on purpose: it is what a job recovered from the file carries, and
     * what a job whose runtime has been shut down under it carries. It is <em>not</em> what an
     * unreadable status poll carries — see {@code FlinkSqlService.STATUS_UNAVAILABLE}, which is
     * the difference between "the job is over" and "we could not tell".
     */
    static boolean isTerminal(String status) {
        String upper = status == null ? "" : status.toUpperCase();
        return upper.equals("FINISHED")
            || upper.equals("FAILED")
            || upper.equals("CANCELED")
            || upper.equals("CANCELLED")
            || upper.equals("UNKNOWN");
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    @SafeVarargs
    private static <T> T firstNonNull(T... values) {
        for (T value : values) {
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    private static boolean safeEquals(String left, String right) {
        return left == null ? right == null : left.equals(right);
    }

    private static List<FlinkJobHistoryEntry> historyOrEmpty(List<FlinkJobHistoryEntry> history) {
        return history == null ? List.of() : history;
    }
}
