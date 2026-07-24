// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors
package com.yourcompany.kafkasqlexplorer.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yourcompany.kafkasqlexplorer.config.ExplorerConfig;
import com.yourcompany.kafkasqlexplorer.domain.FlinkJobHistoryEntry;
import com.yourcompany.kafkasqlexplorer.domain.FlinkManagedJobDetails;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class FlinkJobStore {

    private static final Logger log = LoggerFactory.getLogger(FlinkJobStore.class);

    private final ObjectMapper objectMapper;
    private final Path storePath;
    private final long retentionMs;
    private final Map<String, FlinkManagedJobDetails> jobs = new ConcurrentHashMap<>();

    public FlinkJobStore(ExplorerConfig explorerConfig) {
        this.objectMapper = new ObjectMapper();
        this.storePath = Path.of(explorerConfig.getFlinkJobStorePath()).toAbsolutePath().normalize();
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
            sql,
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
        jobs.put(queryId, updated);
        flush();
        return updated;
    }

    public synchronized List<FlinkManagedJobDetails> listAll() {
        pruneExpired();
        return jobs.values().stream()
            .sorted(Comparator.comparingLong(FlinkManagedJobDetails::startedAt).reversed())
            .toList();
    }

    public synchronized List<FlinkManagedJobDetails> listActive() {
        return listAll().stream()
            .filter(job -> !isTerminal(job.status()))
            .toList();
    }

    public synchronized Optional<FlinkManagedJobDetails> findById(String queryId) {
        pruneExpired();
        return Optional.ofNullable(jobs.get(queryId));
    }

    private void load() {
        if (!Files.exists(storePath)) {
            return;
        }
        try {
            if (Files.size(storePath) == 0) {
                return;
            }
        } catch (IOException e) {
            log.warn("Failed to inspect persisted Flink jobs at {}: {}", storePath, e.getMessage());
            return;
        }

        try {
            List<FlinkManagedJobDetails> loaded = objectMapper.readValue(
                storePath.toFile(),
                new TypeReference<List<FlinkManagedJobDetails>>() {}
            );
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
            pruneExpired();
        } catch (IOException e) {
            log.warn("Failed to load persisted Flink jobs from {}: {}", storePath, e.getMessage());
        }
    }

    private void pruneExpired() {
        long now = System.currentTimeMillis();
        jobs.entrySet().removeIf(entry -> {
            FlinkManagedJobDetails job = entry.getValue();
            return job.endedAt() != null && (now - job.endedAt()) > retentionMs;
        });
    }

    private void flush() {
        pruneExpired();
        try {
            if (storePath.getParent() != null) {
                Files.createDirectories(storePath.getParent());
            }
            objectMapper.writerWithDefaultPrettyPrinter()
                .writeValue(storePath.toFile(), new ArrayList<>(new LinkedHashMap<>(jobs).values()));
        } catch (IOException e) {
            log.warn("Failed to persist Flink jobs to {}: {}", storePath, e.getMessage());
        }
    }

    private static boolean isTerminal(String status) {
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
