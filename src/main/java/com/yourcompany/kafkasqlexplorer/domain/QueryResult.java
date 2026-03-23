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
    boolean tableRegistered
) {
    /** Backwards-compatible constructor (no tableRegistered). */
    public QueryResult(List<String> columns, List<Map<String, Object>> rows, long durationMs, String error) {
        this(columns, rows, durationMs, error, false);
    }
}
