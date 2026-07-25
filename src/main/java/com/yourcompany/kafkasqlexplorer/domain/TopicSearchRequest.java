// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors
package com.yourcompany.kafkasqlexplorer.domain;

import java.util.List;
import java.util.Map;

/**
 * Criteria for a bounded server-side scan of a topic. Every field is optional: an empty request
 * simply returns the first records from the chosen starting point.
 *
 * @param mode          {@code CONTAINS} (default) / {@code REGEX} on the raw value, or {@code FIELD}
 *                      to compare a JSON / XML path
 * @param field         dot-notation path for {@code FIELD} mode, JSONPath accepted ({@code $.a.b[0].c})
 * @param operator      EQ (default) / NEQ / CONTAINS / REGEX / GT / GTE / LT / LTE / EXISTS
 * @param from          EARLIEST (default) / LATEST / TIMESTAMP / OFFSET — where the scan starts
 * @param cursor        partition → next offset, echoed back from a previous response to resume
 * @param maxScan       records to read before giving up, clamped to the server budget
 */
public record TopicSearchRequest(
    String query,
    String mode,
    Boolean caseSensitive,
    Boolean searchKey,
    String field,
    String operator,
    String value,
    String from,
    Long fromTimestamp,
    Integer sinceMinutes,
    Long fromOffset,
    Map<String, Long> cursor,
    List<Integer> partitions,
    Integer maxHits,
    Integer maxScan,
    Integer timeoutMs
) {
    public boolean isCaseSensitive() {
        return Boolean.TRUE.equals(caseSensitive);
    }

    public boolean isSearchKey() {
        return !Boolean.FALSE.equals(searchKey);
    }

    public String resolvedMode() {
        return mode == null || mode.isBlank() ? "CONTAINS" : mode.trim().toUpperCase();
    }

    public String resolvedOperator() {
        return operator == null || operator.isBlank() ? "EQ" : operator.trim().toUpperCase();
    }

    public String resolvedFrom() {
        if (from != null && !from.isBlank()) {
            return from.trim().toUpperCase();
        }
        if (fromTimestamp != null || sinceMinutes != null) {
            return "TIMESTAMP";
        }
        if (fromOffset != null) {
            return "OFFSET";
        }
        return "EARLIEST";
    }
}
