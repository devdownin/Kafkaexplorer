package com.yourcompany.kafkasqlexplorer.service;

import com.yourcompany.kafkasqlexplorer.config.ExplorerConfig;
import org.apache.flink.table.api.TableResult;
import org.apache.flink.table.api.bridge.java.StreamTableEnvironment;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class SqlQueryValidator {

    private static final Logger log = LoggerFactory.getLogger(SqlQueryValidator.class);
    private final ExplorerConfig explorerConfig;
    private final StreamTableEnvironment tableEnv;

    public SqlQueryValidator(ExplorerConfig explorerConfig, StreamTableEnvironment tableEnv) {
        this.explorerConfig = explorerConfig;
        this.tableEnv = tableEnv;
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

        try {
            // We use EXPLAIN to get the execution plan and check for forbidden patterns
            String plan = tableEnv.explainSql(sql).toUpperCase();

            if (!explorerConfig.isAllowCrossJoin() && isCrossJoinInPlan(plan)) {
                throw new IllegalArgumentException("Cross joins are not allowed in this environment.");
            }

            if (!explorerConfig.isAllowSystemTableAccess() && isSystemTableInPlan(plan)) {
                throw new IllegalArgumentException("Access to system tables is restricted.");
            }
        } catch (Exception e) {
            if (e instanceof IllegalArgumentException) throw (IllegalArgumentException) e;
            log.warn("SQL validation via EXPLAIN failed for query: {}. Error: {}", sql, e.getMessage());
            // If explain fails (e.g. table not found), we let the actual execution handle it
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
