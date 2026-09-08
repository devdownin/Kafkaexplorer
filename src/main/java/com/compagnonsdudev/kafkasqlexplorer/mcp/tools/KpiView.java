// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors
package com.compagnonsdudev.kafkasqlexplorer.mcp.tools;

import com.compagnonsdudev.kafkasqlexplorer.mcp.contract.Measured;

import java.util.List;

/** What {@code kex_suggest_kpis} returns. */
public final class KpiView {

    private KpiView() {
    }

    /**
     * One proposed KPI, with what it was derived from.
     *
     * <p><b>{@code thresholdBasis} is the field that keeps this honest, and it can be absent.</b>
     * A KPI with a number attached reads as a recommendation; a KPI whose threshold rests on
     * nothing is a number this application invented, and a model asked for "alerting thresholds"
     * will publish it as one. Where no measurement supports a threshold there is none, and the
     * proposal says what would have to be measured first.
     *
     * <p>{@code dataState} is the second: {@code ABSENT} means a topic the proposal reads is gone,
     * {@code EMPTY} that it holds nothing, and either way the metric would be created and never
     * produce a value. {@code alreadyConfigured} names the metric that covers it today.
     *
     * @param id                a stable id for this proposal within the run
     * @param source            AUDIT / STREAM_FLOW / LINEAGE / PROCESS_MINING — what produced it
     * @param title             what it measures, in a line
     * @param rationale         why it is worth measuring here
     * @param evidence          the observations it rests on, in the words of the run that made them
     * @param thresholdBasis    the measurement a threshold would rest on, unmeasured when none does
     * @param caveats           what would make this metric misleading
     * @param dataState         POPULATED / EMPTY / ABSENT / UNKNOWN
     * @param alreadyConfigured true when a configured metric already covers this
     * @param existingMetric    that metric's name, {@code null} when there is none
     * @param sql               the Flink SQL the metric would run
     */
    public record Kpi(
            String id,
            String source,
            String title,
            String rationale,
            List<String> evidence,
            Measured<String> thresholdBasis,
            List<String> caveats,
            String dataState,
            boolean alreadyConfigured,
            String existingMetric,
            String sql
    ) {
    }

    /**
     * The proposals and the evidence available when they were made.
     *
     * <p>{@code auditRunId} is what makes a proposal checkable: it names the run whose findings
     * produced these, so a caller can read that run rather than take the KPI on trust. Without an
     * audit the list is thinner and {@code notes} says which sources were missing — a short list
     * because nothing had been measured is a different answer from a short list because the
     * cluster is simple.
     *
     * @param kpis              the proposals, most relevant first
     * @param auditRunId        the audit run they were derived from, unmeasured when none was read
     * @param auditTopics       topics that run covered
     * @param notes             what evidence was missing, and what would unlock more proposals
     */
    public record Suggestions(
            List<Kpi> kpis,
            Measured<String> auditRunId,
            int auditTopics,
            List<String> notes
    ) {
    }
}
