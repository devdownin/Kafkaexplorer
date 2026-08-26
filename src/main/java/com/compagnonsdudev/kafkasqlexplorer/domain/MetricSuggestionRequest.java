// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors
package com.compagnonsdudev.kafkasqlexplorer.domain;

import java.util.List;

/**
 * What the caller contributes to the suggestion pass. The audit side is read server-side; the
 * traces are not, so the browser sends the ones it kept.
 *
 * <p>{@code fieldMappingId} is the same kind of contribution: the Process Mining pipeline keeps it
 * in the browser's draft, and it unlocks the one thing this application knows about a topic's
 * <em>real</em> business key — the mapping an operator validated, rather than the {@code id}
 * convention everything else falls back to.
 *
 * <p>{@code processModel} is the third, and the strongest of them: the directly-follows graph a
 * Process Mining run measured, with per-transition quantiles over every record it read. It lives in
 * the browser for the same reason the traces do — nothing keeps it server-side once the analysis has
 * answered.
 *
 * <p>The whole body is optional — {@code POST /api/metrics/suggestions} with no body answers with
 * the audit- and lineage-derived proposals alone.
 */
public record MetricSuggestionRequest(List<FlowChainEvidence> flowChains, String fieldMappingId,
                                      ProcessModelEvidence processModel) {

    /** Backwards-compatible: a body carrying only the traces still binds. */
    public MetricSuggestionRequest(List<FlowChainEvidence> flowChains) {
        this(flowChains, null, null);
    }

    /** Backwards-compatible: a body from before the measured process was carried back. */
    public MetricSuggestionRequest(List<FlowChainEvidence> flowChains, String fieldMappingId) {
        this(flowChains, fieldMappingId, null);
    }

    public List<FlowChainEvidence> chains() {
        return flowChains == null ? List.of() : flowChains;
    }
}
