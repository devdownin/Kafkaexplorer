// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors
package com.compagnonsdudev.kafkasqlexplorer.domain;

import java.util.List;

/**
 * One analysis outcome.
 *
 * <p>{@code error} is what separates a failure from a result. Every failure path — no API key, an
 * unreachable provider, an unparseable answer — used to be reported by putting its message in
 * {@code comments} and answering 200, so the page rendered "LLM call failed: Connection refused"
 * inside its *Analysis Commentary* panel, under an empty flowchart, exactly as it renders a real
 * narrative. A refusal that reads like an answer is worse than no answer at all; when this field is
 * set, nothing else in the record is a finding.
 */
public record ProcessMiningResult(
    String flowchart,
    String comments,
    List<String> hypotheses,
    List<String> blindSpots,
    List<AnomalyReport> anomalies,
    List<RagSource> ragSources,
    String error
) {
    /** Backwards-compatible constructor without RAG sources (the LLM JSON never carries them). */
    public ProcessMiningResult(String flowchart, String comments, List<String> hypotheses,
                               List<String> blindSpots, List<AnomalyReport> anomalies) {
        this(flowchart, comments, hypotheses, blindSpots, anomalies, List.of(), null);
    }

    public ProcessMiningResult(String flowchart, String comments, List<String> hypotheses,
                               List<String> blindSpots, List<AnomalyReport> anomalies,
                               List<RagSource> ragSources) {
        this(flowchart, comments, hypotheses, blindSpots, anomalies, ragSources, null);
    }

    /** A failed analysis: the reason, and nothing that could be mistaken for a finding. */
    public static ProcessMiningResult failed(String message) {
        return new ProcessMiningResult(null, null, List.of(), List.of(), List.of(), List.of(), message);
    }

    /** Returns a copy of this result with the given RAG sources attached. */
    public ProcessMiningResult withRagSources(List<RagSource> sources) {
        return new ProcessMiningResult(flowchart, comments, hypotheses, blindSpots, anomalies,
            sources == null ? List.of() : sources, error);
    }
}
