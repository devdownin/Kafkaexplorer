// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors
package com.compagnonsdudev.kafkasqlexplorer.mcp.tools;

import com.compagnonsdudev.kafkasqlexplorer.domain.QueryRequest;
import com.compagnonsdudev.kafkasqlexplorer.domain.QueryResult;
import com.compagnonsdudev.kafkasqlexplorer.mcp.contract.Coverage;
import com.compagnonsdudev.kafkasqlexplorer.mcp.contract.StopReason;
import com.compagnonsdudev.kafkasqlexplorer.mcp.contract.ToolResult;
import com.compagnonsdudev.kafkasqlexplorer.mcp.contract.Warning;
import com.compagnonsdudev.kafkasqlexplorer.mcp.guard.McpErrorCode;
import com.compagnonsdudev.kafkasqlexplorer.mcp.guard.McpGuard;
import com.compagnonsdudev.kafkasqlexplorer.mcp.guard.McpToolException;
import com.compagnonsdudev.kafkasqlexplorer.mcp.guard.ToolGuard;
import com.compagnonsdudev.kafkasqlexplorer.mcp.observability.ToolCategory;
import com.compagnonsdudev.kafkasqlexplorer.service.FlinkSqlService;
import com.compagnonsdudev.kafkasqlexplorer.service.FlinkTableStore;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * SQL over Kafka topics, and the catalogue of tables it can be written against.
 *
 * <p>The differentiating tool of the whole server: a {@code SELECT} with a {@code WHERE} and a
 * {@code JOIN} over vanilla Kafka, no Confluent Cloud, no DDL for the caller to write — auto
 * registration infers the table from the topic. It is also the tool most able to hurt the cluster,
 * which is why every ceiling here is hard.
 *
 * <p><b>A user error is returned as a user error.</b> {@code FlinkSqlService} already separates the
 * planner's own complaints (a parse error, an unknown column) from an engine failure, and the
 * distinction has to survive the trip to the agent: a model told "engine unavailable" for a
 * misspelled column retries the same query, while the planner's message names the column and the
 * position and gets it right on the second attempt. Anything the service reports as an error comes
 * back as {@code -32046} with that message intact, rather than an empty result set — which is the
 * one shape a model reads as "no matching rows".
 *
 * <p>The write surface stays closed here whatever the readonly flag says: {@code FlinkSqlService}
 * whitelists {@code SELECT}, {@code EXPLAIN}, {@code SHOW}, {@code DESCRIBE} and
 * {@code CREATE TABLE}, so a {@code DELETE} or an {@code INSERT} is refused by the engine before
 * this class has an opinion. That is deliberate: one whitelist, in the place that executes.
 */
public class SqlMcpTools implements ReadOnlyMcpTools {

    private final FlinkSqlService flink;
    private final FlinkTableStore tableStore;
    private final ToolGuard guard;

    public SqlMcpTools(FlinkSqlService flink, FlinkTableStore tableStore, ToolGuard guard) {
        this.flink = flink;
        this.tableStore = tableStore;
        this.guard = guard;
    }

    @Override
    public ToolCategory category() {
        return ToolCategory.EXPLORATION;
    }

    @McpTool(name = "kex_sql_query", annotations = @McpTool.McpAnnotations(
            readOnlyHint = true, destructiveHint = false, openWorldHint = true),
            description = """
            Run a read-only SQL statement against the Kafka topics of this cluster and return the
            rows. A topic can be queried by name with no DDL: the table is inferred from a sample of
            its records on first use. Call kex_infer_schema first if you need the column names.

            Only SELECT, EXPLAIN, SHOW, DESCRIBE and CREATE TABLE are accepted; anything else is
            refused by the engine.

            Read `engine` in the answer. FLINK means the planner ran the statement, with JOIN and
            subquery support. KAFKA_DIRECT means a bounded direct reader answered instead: it reads
            a single table and applies only the predicates it can evaluate, and any it dropped are
            listed in `warnings`. A result whose warnings mention a dropped predicate is NOT a
            filtered result.

            `coverage.stopReason` RECORD_LIMIT means the row cap cut the answer: there are more
            matching rows than were returned.""")
    public ToolResult<SqlView.SqlAnswer> sqlQuery(
            @McpToolParam(description = "The SQL statement") String sql,
            @McpToolParam(required = false, description = "earliest (default for scans) or latest; honoured for single-table reads")
            String readMode,
            @McpToolParam(required = false, description = "Maximum rows; clamped by the server ceiling")
            Integer maxRows,
            @McpToolParam(required = false, description = "Time budget in ms; clamped by the server ceiling")
            Integer timeoutMs) {

        long startedAt = System.currentTimeMillis();
        if (sql == null || sql.isBlank()) {
            throw new McpToolException(McpErrorCode.VALIDATION_FAILED, McpGuard.VALIDATION,
                    "sql is required");
        }

        int rowCap = guard.clampRows(maxRows);
        long budget = guard.clampBudget(timeoutMs);
        List<Warning> warnings = new ArrayList<>(guard.clampWarnings("maxRows", maxRows, rowCap));
        warnings.addAll(guard.clampWarnings("timeoutMs", timeoutMs, budget));

        QueryResult result = flink.executeSync(new QueryRequest(sql, null, rowCap, budget, readMode));

        if (result.error() != null) {
            // The planner's own sentence, carried through. Substituting a generic failure here
            // would cost the model the line and column it needs to fix the statement, and an empty
            // result set would be worse still: it reads as "nothing matched".
            throw new McpToolException(McpErrorCode.VALIDATION_FAILED, McpGuard.VALIDATION, result.error());
        }

        result.warnings().forEach(w -> warnings.add(Warning.warn("ENGINE", guard.dlp().scrub(w))));

        List<Map<String, Object>> rows = result.rows() == null ? List.of() : result.rows();
        boolean atCap = rows.size() >= rowCap;
        long elapsed = System.currentTimeMillis() - startedAt;

        SqlView.SqlAnswer answer = new SqlView.SqlAnswer(
                result.columns() == null ? List.of() : result.columns(),
                rows,
                result.engine(),
                result.changelog() == null ? null : result.changelog().toString());

        Coverage coverage = atCap
                ? Coverage.partial(1, 1, List.of(), rows.size(), elapsed, StopReason.RECORD_LIMIT, null)
                : Coverage.exhausted(1, rows.size(), elapsed);

        return new ToolResult<>(answer, coverage, warnings, atCap);
    }

    @McpTool(name = "kex_list_tables", annotations = @McpTool.McpAnnotations(
            readOnlyHint = true, destructiveHint = false, openWorldHint = true),
            description = """
            List the Flink tables this application currently has registered, with their columns and
            the CREATE TABLE that defines them.

            The list is the state of this process: a table auto-registered by an earlier
            kex_sql_query appears here, and a topic that has never been queried does not — its
            absence says nothing about whether the topic exists. Use kex_list_topics for that.

            Credentials in the DDL are redacted.""")
    public ToolResult<List<SqlView.RegisteredTable>> listTables() {
        long startedAt = System.currentTimeMillis();

        List<String> names;
        try {
            names = flink.listTables();
        } catch (RuntimeException e) {
            throw new McpToolException(McpErrorCode.DEPENDENCY_UNAVAILABLE,
                    "the Flink runtime could not be read: " + e.getMessage());
        }

        Map<String, String> storedDdl = new java.util.HashMap<>();
        if (tableStore.isEnabled()) {
            tableStore.all().forEach(stored -> storedDdl.put(stored.name(), stored.sql()));
        }

        List<Warning> warnings = new ArrayList<>();
        List<SqlView.RegisteredTable> tables = new ArrayList<>();
        for (String name : names) {
            Map<String, String> columns;
            try {
                columns = flink.getTableSchema(name);
            } catch (RuntimeException e) {
                columns = Map.of();
                warnings.add(Warning.warn("SCHEMA_UNREAD",
                        "columns unavailable for table " + name + ": " + e.getMessage()));
            }
            tables.add(new SqlView.RegisteredTable(name, columns, guard.dlp().scrubDdl(storedDdl.get(name))));
        }

        return ToolResult.of(tables,
                Coverage.exhausted(tables.size(), 0L, System.currentTimeMillis() - startedAt), warnings);
    }
}
