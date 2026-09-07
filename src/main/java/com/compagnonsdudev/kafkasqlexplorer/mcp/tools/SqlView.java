// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors
package com.compagnonsdudev.kafkasqlexplorer.mcp.tools;

import java.util.List;
import java.util.Map;

/** What the SQL tools return. */
public final class SqlView {

    private SqlView() {
    }

    /**
     * The answer to a {@code kex_sql_query}.
     *
     * @param columns column names, in the projection's order
     * @param rows    the rows, each keyed by column name
     * @param engine  which engine actually answered — {@code FLINK} (the planner, with JOIN and
     *                subquery support) or {@code KAFKA_DIRECT} (the bounded direct reader). The
     *                distinction is not trivia: the direct reader reads one table and applies only
     *                the predicates it understands, so a caller comparing two results has to know
     *                which one it got
     * @param changelog non-null when some rows withdraw or replace earlier ones rather than each
     *                  being an insert
     */
    public record SqlAnswer(
            List<String> columns,
            List<Map<String, Object>> rows,
            String engine,
            String changelog
    ) {}

    /**
     * One Flink table this application knows about.
     *
     * @param name       table name, as SQL would name it
     * @param columns    column name to type
     * @param ddl        the {@code CREATE TABLE}, with credentials redacted; null when this
     *                   application did not store one
     */
    public record RegisteredTable(String name, Map<String, String> columns, String ddl) {}
}
