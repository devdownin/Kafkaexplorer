// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors
package com.yourcompany.kafkasqlexplorer.domain;

import java.util.List;
import java.util.Map;

public record QueryResult(
    List<String> columns,
    List<Map<String, Object>> rows,
    long durationMs,
    String error,
    boolean tableRegistered,
    /** Execution engine used: "KAFKA_DIRECT" for bounded SELECT reads, "FLINK" for EXPLAIN/DDL. Null on error paths. */
    String engine
) {
    /** Backwards-compatible constructor (no tableRegistered, no engine). */
    public QueryResult(List<String> columns, List<Map<String, Object>> rows, long durationMs, String error) {
        this(columns, rows, durationMs, error, false, null);
    }

    /** Backwards-compatible constructor (no engine). */
    public QueryResult(List<String> columns, List<Map<String, Object>> rows, long durationMs, String error, boolean tableRegistered) {
        this(columns, rows, durationMs, error, tableRegistered, null);
    }
}
