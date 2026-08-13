// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors
package com.compagnonsdudev.kafkasqlexplorer.service;

import com.compagnonsdudev.kafkasqlexplorer.config.ExplorerConfig;
import org.apache.flink.table.api.TableEnvironment;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class SqlQueryValidator {

    private static final Logger log = LoggerFactory.getLogger(SqlQueryValidator.class);
    private final ExplorerConfig explorerConfig;
    private final TableEnvironment tableEnv;
    private final FlinkRuntimeCoordinator runtimeCoordinator;

    @Autowired
    public SqlQueryValidator(ExplorerConfig explorerConfig, TableEnvironment tableEnv, FlinkRuntimeCoordinator runtimeCoordinator) {
        this.explorerConfig = explorerConfig;
        this.tableEnv = tableEnv;
        this.runtimeCoordinator = runtimeCoordinator;
    }

    public void validate(String sql) {
        if (sql == null || sql.trim().isEmpty()) return;
        String upperSql = sql.toUpperCase();

        if (!explorerConfig.isAllowCrossJoin()) {
            if (upperSql.contains("CROSS JOIN")) {
                throw new IllegalArgumentException("Cross joins are not allowed in this environment.");
            }
        }

        if (explorerConfig.isAllowCrossJoin() && explorerConfig.isAllowSystemTableAccess()) {
            return;
        }

        // EXPLAIN only supports SELECT/EXPLAIN — DDL (CREATE TABLE, ALTER, DROP) is not supported
        // and will throw "Unsupported operation: CreateTableOperation". Skip for DDL.
        String upperTrimmed = sql.trim().toUpperCase();
        if (!upperTrimmed.startsWith("SELECT") && !upperTrimmed.startsWith("EXPLAIN")) {
            return;
        }

        try {
            // We use EXPLAIN to get the execution plan and check for forbidden patterns
            String plan = runtimeCoordinator.runRead("sql-validator-explain", () -> tableEnv.explainSql(sql).toUpperCase());

            if (!explorerConfig.isAllowCrossJoin() && isCrossJoinInPlan(plan)) {
                throw new IllegalArgumentException("Cross joins are not allowed in this environment.");
            }

            if (!explorerConfig.isAllowSystemTableAccess() && isSystemTableInPlan(plan)) {
                throw new IllegalArgumentException("Access to system tables is restricted.");
            }
        } catch (Exception e) {
            if (e instanceof IllegalArgumentException) throw (IllegalArgumentException) e;
            // A parse error never depends on the catalog, so reporting it here costs no Kafka round
            // trip and gives the editor its line/column immediately.
            if (SqlErrorClassifier.isSyntaxError(e)) {
                throw new IllegalArgumentException(SqlErrorClassifier.explain(e), e);
            }
            // EXPLAIN fails for tables not registered in Flink (e.g. Kafka topics with dotted names
            // or tables referenced before auto-registration). This is expected — actual execution handles it.
            log.debug("SQL validation via EXPLAIN skipped (table not resolvable): {}", e.getMessage());
        }
    }

    private boolean isCrossJoinInPlan(String plan) {
        // Flink plans often use these keywords for cross/cartesian joins
        return plan.contains("JOIN_TYPE: CROSS") ||
               plan.contains("CROSS JOIN") ||
               plan.contains("CARTESIAN") ||
               (plan.contains("JOIN") && !plan.contains("CONDITION") && !plan.contains("ON") && !plan.contains("USING") && !plan.contains("JOIN_TYPE"));
    }

    private boolean isSystemTableInPlan(String plan) {
        return plan.contains("INFORMATION_SCHEMA") ||
               plan.contains("SYS.") ||
               plan.contains("SYSTEM.");
    }
}
