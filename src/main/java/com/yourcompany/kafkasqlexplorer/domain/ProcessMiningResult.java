// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors
package com.yourcompany.kafkasqlexplorer.domain;

import java.util.List;

public record ProcessMiningResult(
    String flowchart,
    String comments,
    List<String> hypotheses,
    List<String> blindSpots,
    List<AnomalyReport> anomalies,
    List<RagSource> ragSources
) {
    /** Backwards-compatible constructor without RAG sources (the LLM JSON never carries them). */
    public ProcessMiningResult(String flowchart, String comments, List<String> hypotheses,
                               List<String> blindSpots, List<AnomalyReport> anomalies) {
        this(flowchart, comments, hypotheses, blindSpots, anomalies, List.of());
    }

    /** Returns a copy of this result with the given RAG sources attached. */
    public ProcessMiningResult withRagSources(List<RagSource> sources) {
        return new ProcessMiningResult(flowchart, comments, hypotheses, blindSpots, anomalies,
            sources == null ? List.of() : sources);
    }
}
