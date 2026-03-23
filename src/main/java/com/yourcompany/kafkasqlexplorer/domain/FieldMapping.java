// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors
package com.yourcompany.kafkasqlexplorer.domain;

import java.util.List;
import java.util.Map;

public record FieldMapping(
    String id,
    Map<String, String> correlationIdPaths,   // topic -> jsonpath
    Map<String, String> timestampPaths,
    Map<String, String> statusPaths,
    Map<String, Map<String, List<String>>> statusEquivalences  // canonical -> [aliases]
) {
    public String toPromptBlock() {
        StringBuilder sb = new StringBuilder();
        sb.append("### CORRELATION_ID\n");
        if (correlationIdPaths != null) {
            correlationIdPaths.forEach((t, p) -> sb.append("  ").append(t).append(" -> ").append(p).append("\n"));
        }
        sb.append("### TIMESTAMP\n");
        if (timestampPaths != null) {
            timestampPaths.forEach((t, p) -> sb.append("  ").append(t).append(" -> ").append(p).append("\n"));
        }
        sb.append("### STATUS\n");
        if (statusPaths != null) {
            statusPaths.forEach((t, p) -> sb.append("  ").append(t).append(" -> ").append(p).append("\n"));
        }
        if (statusEquivalences != null && !statusEquivalences.isEmpty()) {
            sb.append("### STATUS EQUIVALENCES\n");
            statusEquivalences.forEach((canonical, aliases) ->
                sb.append("  ").append(canonical).append(" = ").append(aliases).append("\n"));
        }
        return sb.toString();
    }
}
