// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors
package com.compagnonsdudev.kafkasqlexplorer.mcp.tools;

import com.compagnonsdudev.kafkasqlexplorer.mcp.contract.Measured;

import java.util.List;

/** What {@code kex_run_audit} and {@code kex_get_audit} return. */
public final class AuditView {

    private AuditView() {
    }

    /**
     * The handle on a started run.
     *
     * <p>{@code started} false is the case that matters: an audit was already in flight, so this
     * call <em>attached</em> to it rather than starting one. Its scope is whatever that run chose,
     * which is not what this caller asked for — reported here instead of letting the caller read
     * another run's findings as an answer to its own question.
     *
     * @param runId    the id to poll with kex_get_audit
     * @param started  false when an audit was already running and this call attached to it
     * @param scope    the topic prefix this run covers, {@code null} for the whole cluster
     * @param note     what the caller has to know before reading the report
     */
    public record Run(String runId, boolean started, String scope, String note) {
    }

    /**
     * One topic's findings.
     *
     * @param topic          the topic
     * @param health         HEALTHY / WARNING / CRITICAL
     * @param messageCount   records counted, unmeasured when the count could not be read
     * @param poisonMessages payloads that did not parse
     * @param duplicates     duplicate keys found
     * @param issues         each finding, worst first, in the words the audit used
     */
    public record TopicFinding(
            String topic,
            String health,
            Measured<Long> messageCount,
            int poisonMessages,
            long duplicates,
            List<String> issues
    ) {
    }

    /**
     * One flow, step by step.
     *
     * <p>{@code healthScore} is a <b>0..1 ratio</b>, not a percentage — the UI multiplies by 100
     * and a reader who does the same twice reports 8 500 % health.
     */
    public record FlowFinding(String flow, double healthScore, List<Step> steps) {
    }

    /**
     * One step of a flow.
     *
     * @param topic         the step's topic
     * @param count         records that reached it
     * @param throughputPct share of the previous step, as a percentage
     * @param latencyMs     time from the previous step, unmeasured when no pair could be timed
     */
    public record Step(String topic, long count, double throughputPct, Measured<Long> latencyMs) {
    }

    /**
     * A run's findings.
     *
     * <p>{@code status} is the first thing to read. {@code RUNNING} means these are partial results
     * from a scan still in progress, and {@code CANCELLED} means a partial answer over the topics
     * reached before the stop — in neither case is the absence of a finding the absence of a
     * problem, and {@code coverage.stopReason} carries the same fact in the envelope.
     *
     * @param runId          the run
     * @param status         RUNNING / COMPLETED / CANCELLED / FAILED
     * @param healthScore    the run's own score, unmeasured while the run has not computed one
     * @param topicsAudited  topics with a verdict
     * @param totalMessages  records counted across them, unmeasured when no count ran
     * @param criticalTopics topics graded CRITICAL
     * @param warningTopics  topics graded WARNING
     * @param scope          the topic prefix this run covered, {@code null} for the whole cluster
     * @param findings       per-topic findings, worst first — healthy topics are not listed
     * @param flows          per-flow findings
     */
    public record Report(
            String runId,
            String status,
            Measured<Double> healthScore,
            int topicsAudited,
            Measured<Long> totalMessages,
            int criticalTopics,
            int warningTopics,
            String scope,
            List<TopicFinding> findings,
            List<FlowFinding> flows
    ) {
    }
}
