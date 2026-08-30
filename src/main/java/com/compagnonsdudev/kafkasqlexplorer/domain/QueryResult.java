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
    List<String> warnings,
    /**
     * Non-null when the engine answered with a <em>changelog</em> rather than a set of rows: some
     * of the rows returned withdraw or replace an earlier one. Null means every row emitted is an
     * insert, which is the ordinary case — see {@link ChangelogInfo}.
     */
    ChangelogInfo changelog
) {
    /** Backwards-compatible constructor (no tableRegistered, no engine, no warnings). */
    public QueryResult(List<String> columns, List<Map<String, Object>> rows, long durationMs, String error) {
        this(columns, rows, durationMs, error, false, null, List.of(), null);
    }

    /** Backwards-compatible constructor (no engine, no warnings). */
    public QueryResult(List<String> columns, List<Map<String, Object>> rows, long durationMs, String error, boolean tableRegistered) {
        this(columns, rows, durationMs, error, tableRegistered, null, List.of(), null);
    }

    /** Backwards-compatible constructor (no warnings). */
    public QueryResult(List<String> columns, List<Map<String, Object>> rows, long durationMs, String error, boolean tableRegistered, String engine) {
        this(columns, rows, durationMs, error, tableRegistered, engine, List.of(), null);
    }

    /** Backwards-compatible constructor (no changelog). */
    public QueryResult(List<String> columns, List<Map<String, Object>> rows, long durationMs, String error,
                       boolean tableRegistered, String engine, List<String> warnings) {
        this(columns, rows, durationMs, error, tableRegistered, engine, warnings, null);
    }

    /**
     * Rebuilds with different warnings, <strong>keeping everything else</strong> — the changelog
     * included. Written through a shorter constructor it would have silently dropped it, which is
     * the defect {@link #withRegisteredFlag(boolean)} carried for warnings.
     */
    public QueryResult withWarnings(List<String> newWarnings) {
        return new QueryResult(columns, rows, durationMs, error, tableRegistered, engine,
            newWarnings == null ? List.of() : List.copyOf(newWarnings), changelog);
    }

    public QueryResult withChangelog(ChangelogInfo info) {
        return new QueryResult(columns, rows, durationMs, error, tableRegistered, engine, warnings, info);
    }

    /**
     * Marks the result as having auto-registered a table, keeping the rest.
     *
     * <p>It used to be written at the call site through the six-argument constructor, which
     * defaults {@code warnings} to an empty list: a query that both registered its topic and had
     * something to say about how it was read — the commonest shape of a first SELECT on a topic —
     * lost the caveat on the way out, and the caveats are precisely what stops a fallback from
     * passing for a normal answer.
     */
    public QueryResult withRegisteredFlag(boolean registered) {
        return new QueryResult(columns, rows, durationMs, error, registered, engine, warnings, changelog);
    }
}
