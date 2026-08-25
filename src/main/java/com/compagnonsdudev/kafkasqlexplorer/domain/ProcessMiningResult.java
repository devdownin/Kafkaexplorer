// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors
package com.compagnonsdudev.kafkasqlexplorer.domain;

import com.fasterxml.jackson.annotation.JsonProperty;

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
 *
 * <p>{@code coverage} is what the answer rests on, and like {@code usage} it is attached by the
 * service after the model's JSON has been parsed — never supplied by the model, which is in no
 * position to say what it was shown. It stays null on the live path, where the window's scope is
 * already reported per window by {@code WINDOW_STATS}.
 */
public record ProcessMiningResult(
    String flowchart,
    String comments,
    List<String> hypotheses,
    List<String> blindSpots,
    List<AnomalyReport> anomalies,
    List<RagSource> ragSources,
    String error,
    // Serialized, never deserialized. This record is what the model's own JSON is parsed into, and
    // these two are measurements *about* that call: what it cost, and what it was shown. A model
    // that volunteered either would be stating its own bill and its own scope — and on the live
    // path, where nothing overwrites them afterwards, it would be believed. The claim that they are
    // attached by the service was written in this javadoc before anything enforced it.
    @JsonProperty(access = JsonProperty.Access.READ_ONLY)
    LlmUsage usage,
    @JsonProperty(access = JsonProperty.Access.READ_ONLY)
    ProcessMiningCoverage coverage
) {
    /** Backwards-compatible constructor without RAG sources (the LLM JSON never carries them). */
    public ProcessMiningResult(String flowchart, String comments, List<String> hypotheses,
                               List<String> blindSpots, List<AnomalyReport> anomalies) {
        this(flowchart, comments, hypotheses, blindSpots, anomalies, List.of(), null, null, null);
    }

    public ProcessMiningResult(String flowchart, String comments, List<String> hypotheses,
                               List<String> blindSpots, List<AnomalyReport> anomalies,
                               List<RagSource> ragSources) {
        this(flowchart, comments, hypotheses, blindSpots, anomalies, ragSources, null, null, null);
    }

    /** A failed analysis: the reason, and nothing that could be mistaken for a finding. */
    public static ProcessMiningResult failed(String message) {
        return new ProcessMiningResult(null, null, List.of(), List.of(), List.of(), List.of(),
            message, null, null);
    }

    /**
     * A failed analysis that still cost something. A call that timed out or came back unparseable
     * consumed tokens and wall clock all the same, and hiding that would make the one number an
     * operator uses to size a job — what a run costs — quietly optimistic.
     */
    public static ProcessMiningResult failed(String message, LlmUsage usage) {
        return new ProcessMiningResult(null, null, List.of(), List.of(), List.of(), List.of(),
            message, usage, null);
    }

    /** Returns a copy of this result with the given RAG sources attached. */
    public ProcessMiningResult withRagSources(List<RagSource> sources) {
        return new ProcessMiningResult(flowchart, comments, hypotheses, blindSpots, anomalies,
            sources == null ? List.of() : sources, error, usage, coverage);
    }

    /** Returns a copy of this result with the measured cost of the call attached. */
    public ProcessMiningResult withUsage(LlmUsage measured) {
        return new ProcessMiningResult(flowchart, comments, hypotheses, blindSpots, anomalies,
            ragSources, error, measured, coverage);
    }

    /**
     * Returns a copy of this result stating what it was able to look at.
     *
     * <p>Applied to a failed analysis too, deliberately: a run that read four hundred messages and
     * then lost the model still knows what it read, and the next attempt is sized from that.
     */
    public ProcessMiningResult withCoverage(ProcessMiningCoverage measured) {
        return new ProcessMiningResult(flowchart, comments, hypotheses, blindSpots, anomalies,
            ragSources, error, usage, measured);
    }

    /**
     * Returns a copy carrying one more note about this run's scope.
     *
     * <p>The caller may know something about the scope the analysis itself cannot — the controller
     * knows whether the field mapping the request named was still held, and that decides what
     * "correlated" meant for the whole run. A result with no coverage at all gets one rather than
     * dropping the note: silence is what this record exists to stop.
     */
    public ProcessMiningResult withCoverageWarning(String warning) {
        if (warning == null || warning.isBlank()) {
            return this;
        }
        ProcessMiningCoverage base = coverage != null ? coverage
            : ProcessMiningCoverage.of(List.of(), 0, 0, false, null, List.of());
        return withCoverage(base.withWarning(warning));
    }
}
