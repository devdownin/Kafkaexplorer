// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors
package com.yourcompany.kafkasqlexplorer.domain;

import java.util.List;
import java.util.Map;

/**
 * Result of a stream-flow trace: the graph, the evidence behind it, and the bounds of the scan.
 *
 * <p>{@code nodes} / {@code edges} keep their historical shape (string maps, {@code from} / {@code to}
 * on an edge) — they feed the SVG renderer directly. Everything a reader needs to judge the picture
 * travels alongside: {@link StreamFlowHit} per topic and {@link StreamFlowStats} for the scan
 * itself.
 */
public record StreamFlowResponse(
        List<Map<String, String>> nodes,
        List<Map<String, String>> edges,
        List<StreamFlowHit> hits,
        StreamFlowStats stats,
        List<String> warnings
) {
}
