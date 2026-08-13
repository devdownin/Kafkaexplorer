// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors
package com.compagnonsdudev.kafkasqlexplorer.domain;

import java.util.List;
import java.util.Map;

public record QueryResult(
    List<String> columns,
    List<Map<String, Object>> rows,
    long durationMs,
    String error,
    boolean tableRegistered,
    /** Execution engine used: "KAFKA_DIRECT" for bounded SELECT reads, "FLINK" for EXPLAIN/DDL. Null on error paths. */
    String engine,
    /**
     * Non-fatal caveats about the result — most importantly, predicates the direct engine could
     * not apply. Silently returning unfiltered rows for a WHERE it does not understand makes the
     * result look precise when it is not, so the caveat travels with the data.
     */
    List<String> warnings
) {
    /** Backwards-compatible constructor (no tableRegistered, no engine, no warnings). */
    public QueryResult(List<String> columns, List<Map<String, Object>> rows, long durationMs, String error) {
        this(columns, rows, durationMs, error, false, null, List.of());
    }

    /** Backwards-compatible constructor (no engine, no warnings). */
    public QueryResult(List<String> columns, List<Map<String, Object>> rows, long durationMs, String error, boolean tableRegistered) {
        this(columns, rows, durationMs, error, tableRegistered, null, List.of());
    }

    /** Backwards-compatible constructor (no warnings). */
    public QueryResult(List<String> columns, List<Map<String, Object>> rows, long durationMs, String error, boolean tableRegistered, String engine) {
        this(columns, rows, durationMs, error, tableRegistered, engine, List.of());
    }

    public QueryResult withWarnings(List<String> newWarnings) {
        return new QueryResult(columns, rows, durationMs, error, tableRegistered, engine,
            newWarnings == null ? List.of() : List.copyOf(newWarnings));
    }
}
