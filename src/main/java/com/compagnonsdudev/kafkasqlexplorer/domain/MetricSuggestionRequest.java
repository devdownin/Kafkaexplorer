// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors
package com.compagnonsdudev.kafkasqlexplorer.domain;

import java.util.List;

/**
 * What the caller contributes to the suggestion pass. The audit side is read server-side; the
 * traces are not, so the browser sends the ones it kept.
 *
 * <p>The whole body is optional — {@code POST /api/metrics/suggestions} with no body answers with
 * the audit-derived proposals alone.
 */
public record MetricSuggestionRequest(List<FlowChainEvidence> flowChains) {

    public List<FlowChainEvidence> chains() {
        return flowChains == null ? List.of() : flowChains;
    }
}
