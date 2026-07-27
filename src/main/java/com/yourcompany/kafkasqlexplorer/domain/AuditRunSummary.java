// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors
package com.yourcompany.kafkasqlexplorer.domain;

/**
 * One row of the audit history: enough to compare runs over time without loading the reports.
 *
 * <p>A full {@link AuditReport} carries one entry per topic, so listing history by deserializing
 * whole reports would move megabytes to answer "did the cluster improve?". These summaries are
 * read straight off the JSON stored in {@code internal.audit.history}.
 *
 * @param auditId      run identifier — pass it to the history detail endpoint for the full report
 * @param status       COMPLETED / CANCELLED / FAILED as recorded
 * @param timestamp    epoch millis the report was produced, or the Kafka record timestamp when the
 *                     report itself has none
 * @param durationMs   how long the run took, null when not recorded
 * @param totalTopics  topics the run actually audited
 * @param totalMessages messages counted across those topics
 * @param criticalTopicsCount topics with at least one CRITICAL finding
 * @param warningTopicsCount  topics whose worst finding is a WARNING
 * @param healthScore  0..1 as computed by the run, null when not recorded
 * @param topicPrefix  prefix the run was restricted to, null for a full-cluster scan
 * @param legacy       true when the record predates graded severity: it stores the retired
 *                     {@code UNHEALTHY} status and string issues, so it cannot be loaded into the
 *                     current report view. Counted under {@code criticalTopicsCount} because the
 *                     old scale did not record the distinction — the flag says so rather than
 *                     letting the number pass for a graded one.
 */
public record AuditRunSummary(
    String auditId,
    String status,
    long timestamp,
    Long durationMs,
    long totalTopics,
    long totalMessages,
    int criticalTopicsCount,
    int warningTopicsCount,
    Double healthScore,
    String topicPrefix,
    boolean legacy
) {}
