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
    String queryId,
    /**
     * Ask for the direct Kafka reader rather than the Flink planner, for a SELECT the planner
     * cannot express.
     *
     * <p>It exists for one caller — the metric templates — and for one reason: {@code readMode}
     * is honoured by the direct reader alone, so "the most recent N records" is a question the
     * planner has no syntax for (a Kafka scan starting at {@code latest-offset} and bounded at
     * {@code latest-offset} reads nothing at all), and a metric that means "recent" must not
     * silently become a metric over the oldest records the row cap allowed.
     *
     * <p>The caller carries the cost of asking: the direct reader regex-matches one table name
     * out of {@code FROM} and supports neither JOIN nor subqueries, so it must be asked only for
     * the shape it can answer honestly — {@code MetricService.isSingleTableRead} is that check.
     * Absent or false, nothing changes and the planner is consulted first, as always.
     */
    Boolean directRead
) {
    /** Backwards-compatible form for callers that never cancel (audit, table preview, tests). */
    public QueryRequest(String sql, String topic, Integer maxRows, Long timeout, String readMode) {
        this(sql, topic, maxRows, timeout, readMode, null, null);
    }

    /** Backwards-compatible form for callers that name their run but take the usual engine. */
    public QueryRequest(String sql, String topic, Integer maxRows, Long timeout, String readMode,
                        String queryId) {
        this(sql, topic, maxRows, timeout, readMode, queryId, null);
    }

    public static QueryRequest sql(String sql, Integer maxRows, Long timeout, String readMode) {
        return new QueryRequest(sql, null, maxRows, timeout, readMode);
    }

    /** As {@link #sql}, but answered by the direct Kafka reader — see {@link #directRead()}. */
    public static QueryRequest directSql(String sql, Integer maxRows, Long timeout, String readMode) {
        return new QueryRequest(sql, null, maxRows, timeout, readMode, null, Boolean.TRUE);
    }

    public static QueryRequest ddl(String sql, Long timeout) {
        return new QueryRequest(sql, null, 1, timeout, null);
    }

    /** True when the caller explicitly asked for the direct reader. */
    public boolean wantsDirectRead() {
        return Boolean.TRUE.equals(directRead);
    }
}
