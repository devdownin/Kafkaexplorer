// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors
package com.compagnonsdudev.kafkasqlexplorer.domain;

public record QueryRequest(
    String sql,
    String topic,
    Integer maxRows,
    Long timeout,
    String readMode,
    /**
     * Optional client-generated id for this run. A synchronous query only learns its server-side
     * id from the response, which arrives when the query is already over — useless for cancelling
     * it. Letting the caller name the run up front is what makes "stop this query" possible.
     * Ignored unless it looks like an id (see {@code FlinkSqlService.resolveQueryId}).
     */
    String queryId
) {
    /** Backwards-compatible form for callers that never cancel (audit, table preview, tests). */
    public QueryRequest(String sql, String topic, Integer maxRows, Long timeout, String readMode) {
        this(sql, topic, maxRows, timeout, readMode, null);
    }

    public static QueryRequest sql(String sql, Integer maxRows, Long timeout, String readMode) {
        return new QueryRequest(sql, null, maxRows, timeout, readMode);
    }

    public static QueryRequest ddl(String sql, Long timeout) {
        return new QueryRequest(sql, null, 1, timeout, null);
    }
}
