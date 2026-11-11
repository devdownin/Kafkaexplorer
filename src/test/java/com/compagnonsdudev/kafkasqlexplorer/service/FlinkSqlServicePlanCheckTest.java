// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors
package com.compagnonsdudev.kafkasqlexplorer.service;

import com.compagnonsdudev.kafkasqlexplorer.config.ExplorerConfig;
import org.apache.flink.streaming.api.environment.StreamExecutionEnvironment;
import org.apache.flink.table.api.EnvironmentSettings;
import org.apache.flink.table.api.bridge.java.StreamTableEnvironment;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

/**
 * {@link FlinkSqlService#planCheck} and {@link FlinkSqlService#sourceSchema} — the two entry points
 * the SQL assistant's gate stands on, against a real planner.
 *
 * <p>What matters is the split, not the plan: a statement the model got wrong has to come back as
 * {@code USER_ERROR} with the planner's sentence, because that sentence is what is sent back to the
 * model for one repair — and an engine failure must not be, since it is not the model's fault.
 * Nothing here executes a job, so the runtime is handed back as it was found.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class FlinkSqlServicePlanCheckTest {

    private FlinkSqlService service;

    @BeforeAll
    void setup() {
        StreamExecutionEnvironment env = StreamExecutionEnvironment.createLocalEnvironment();
        StreamTableEnvironment tableEnv = StreamTableEnvironment.create(env,
            EnvironmentSettings.newInstance().inStreamingMode().build());
        tableEnv.executeSql("CREATE TABLE orders (order_id STRING, status STRING, amount DOUBLE) "
            + "WITH ('connector' = 'datagen')");

        ExplorerConfig config = new ExplorerConfig();
        FlinkRuntimeCoordinator coordinator = new FlinkRuntimeCoordinator(tableEnv);
        service = new FlinkSqlService(tableEnv, coordinator, config,
            new SqlQueryValidator(config, tableEnv, coordinator),
            mock(KafkaAdminService.class), mock(SchemaInferenceService.class),
            mock(DdlGeneratorService.class), mock(FlinkTableStore.class));
    }

    @Test
    void plansAStatementOverAKnownTable() {
        FlinkSqlService.PlanCheck check =
            service.planCheck("SELECT status, COUNT(*) AS n FROM orders GROUP BY status");

        assertTrue(check.planned(), () -> "a valid aggregate must plan: " + check.message());
        assertNull(check.kind());
    }

    @Test
    void anUnknownColumnIsTheStatementsFault() {
        FlinkSqlService.PlanCheck check = service.planCheck("SELECT customer FROM orders");

        assertFalse(check.planned());
        assertEquals(SqlErrorClassifier.Kind.USER_ERROR, check.kind(),
            "an invented column is what the repair round exists for: " + check.message());
        assertTrue(check.message().contains("customer"), check.message());
    }

    @Test
    void anUnknownTableIsAnsweredRatherThanSwallowed() {
        // SqlQueryValidator swallows this on purpose (it runs before registration). The gate must
        // not: a table the model invented is precisely what it has to report.
        FlinkSqlService.PlanCheck check = service.planCheck("SELECT * FROM invented_table");

        assertFalse(check.planned());
        assertEquals(SqlErrorClassifier.Kind.USER_ERROR, check.kind(), check.message());
    }

    @Test
    void aSyntaxErrorCarriesItsPosition() {
        FlinkSqlService.PlanCheck check = service.planCheck("SELECT order_id, FROM orders");

        assertFalse(check.planned());
        assertEquals(SqlErrorClassifier.Kind.USER_ERROR, check.kind());
        assertTrue(check.message().contains("line 1"), check.message());
    }

    @Test
    void describesARegisteredSource() {
        Map<String, String> columns = service.sourceSchema("orders");

        assertEquals(java.util.List.of("order_id", "status", "amount"), java.util.List.copyOf(columns.keySet()));
        assertTrue(service.sourceSchema("nothing_named_so").isEmpty(),
            "no topic and no table: an empty map, which the context reports as omitted");
    }
}
