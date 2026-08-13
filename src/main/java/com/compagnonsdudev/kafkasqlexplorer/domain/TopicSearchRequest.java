// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors
package com.compagnonsdudev.kafkasqlexplorer.domain;

import java.util.List;
import java.util.Map;

/**
 * Criteria for a bounded server-side scan of a topic. Every field is optional: an empty request
 * simply returns the first records from the chosen starting point.
 *
 * @param mode          {@code CONTAINS} (default) / {@code REGEX} on the raw value, {@code FIELD}
 *                      to compare a JSON / XML path, {@code XPATH} for an XPath expression, or
 *                      {@code HEADER} to compare one named Kafka header
 * @param field         dot-notation path for {@code FIELD} mode, JSONPath accepted ({@code $.a.b[0].c});
 *                      the XPath expression for {@code XPATH}; the header name for {@code HEADER}
 * @param searchHeaders widens a {@code CONTAINS} / {@code REGEX} search to every header value.
 *                      Off by default here — a topic search means "find this text in the records",
 *                      and turning it on silently would change what an existing search returns.
 *                      A stream-flow trace defaults it on: a correlation id very often lives only
 *                      in a header
 * @param operator      EQ (default) / NEQ / CONTAINS / REGEX / GT / GTE / LT / LTE / EXISTS
 * @param from          EARLIEST (default) / LATEST / LAST_N / TIMESTAMP / OFFSET — where the scan
 *                      starts; {@code LAST_N} walks back {@code maxScan} records from the end
 * @param cursor        partition → next offset, echoed back from a previous response to resume
 * @param maxScan       records to read before giving up, clamped to the server budget
 */
public record TopicSearchRequest(
    String query,
    String mode,
    Boolean caseSensitive,
    Boolean searchKey,
    Boolean searchHeaders,
    /**
     * Scan only the partition the default partitioner would have written this key to. Honoured for
     * an exact {@code KEY} search and nowhere else: it divides the work by the partition count, at
     * the price of two assumptions the search cannot verify — the producer used the default
     * partitioner, and the partition count has not changed since. Opt-in, and the response says
     * what it assumed.
     */
    Boolean keyPartitioning,
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

    public boolean isSearchHeaders() {
        return Boolean.TRUE.equals(searchHeaders);
    }

    public boolean isKeyPartitioning() {
        return Boolean.TRUE.equals(keyPartitioning);
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
