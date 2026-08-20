// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors
package com.compagnonsdudev.kafkasqlexplorer.service;

import com.compagnonsdudev.kafkasqlexplorer.config.ExplorerConfig;
import com.compagnonsdudev.kafkasqlexplorer.domain.QueryRequest;
import com.compagnonsdudev.kafkasqlexplorer.domain.QueryResult;
import org.apache.flink.streaming.api.environment.StreamExecutionEnvironment;
import org.apache.flink.table.api.EnvironmentSettings;
import org.apache.flink.table.api.bridge.java.StreamTableEnvironment;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import java.nio.file.Files;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

/**
 * A SELECT that names no table — {@code SELECT 1 + 1}, and the startup warmup probe.
 *
 * <p>Two defects met on this one statement shape. The direct Kafka reader begins by
 * regex-matching a name out of {@code FROM}, so it can only ever answer "Cannot parse table
 * name from SQL" here — its own complaint, about a statement it was never meant to run. That
 * message was then handed to the caller *in place of* the engine's real one, which is what the
 * warmup probe reported for months while the true cause sat one WARN line above it.
 *
 * <p>These tests pin both halves: the query runs on the planner when the planner works, and
 * when it does not, what comes back names the planner rather than a table nobody wrote.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class FlinkSqlServiceTablelessSelectTest {

    private StreamTableEnvironment tableEnv;
    private ExplorerConfig config;
    private FlinkSqlService service;

    @BeforeAll
    void setup() throws Exception {
        StreamExecutionEnvironment env = StreamExecutionEnvironment.createLocalEnvironment();
        tableEnv = StreamTableEnvironment.create(env,
                EnvironmentSettings.newInstance().inStreamingMode().build());

        config = new ExplorerConfig();
        config.setDefaultMaxRows(50);
        config.setDefaultQueryTimeoutMs(30_000);
        config.setFlinkJobStorePath(Files.createTempFile("flink-jobs-tableless-", ".json").toString());

        FlinkRuntimeCoordinator coordinator = new FlinkRuntimeCoordinator(tableEnv);
        service = new FlinkSqlService(
                tableEnv, coordinator, config,
                new SqlQueryValidator(config, tableEnv, coordinator),
                mock(KafkaAdminService.class), mock(SchemaInferenceService.class),
                mock(DdlGeneratorService.class), new FlinkJobStore(config));
    }

    @Test
    void runsATablelessSelectOnThePlanner() {
        config.setFlinkSelectEnabled(true);

        QueryResult result = service.executeSql(QueryRequest.sql("SELECT 1 + 1 AS two", 5, null, null));

        assertNull(result.error(), "a table-less SELECT is ordinary SQL and must simply run");
        assertEquals("FLINK", result.engine(), "only the planner can answer a query with no topic behind it");
        assertEquals(1, result.rows().size());
        assertEquals(2, ((Number) result.rows().get(0).get("two")).intValue());
    }

    @Test
    void namesThePlannerRatherThanTheTableParserWhenItCannotRun() {
        config.setFlinkSelectEnabled(false);
        try {
            QueryResult result = service.executeSql(QueryRequest.sql("SELECT 1 + 1 AS two", 5, null, null));

            assertNotNull(result.error(), "with no planner there is nothing that can answer this");
            // The whole point: the direct reader's complaint about a missing FROM described a
            // different engine's opinion of a statement it was never meant to run, and it is what
            // the user was shown instead of the real reason.
            assertFalse(result.error().contains("Cannot parse table name"),
                    "the fallback reader's message must not stand in for the engine's: " + result.error());
            assertTrue(result.error().contains("Flink planner"),
                    "the error has to name what is missing: " + result.error());
        } finally {
            config.setFlinkSelectEnabled(true);
        }
    }

    @Test
    void reportsWhetherTheCircuitBreakerHasTakenThePlannerOut() {
        // Written in one place, read in one place, surfaced nowhere — so a process running on the
        // fallback engine for good looked exactly like one that had chosen it.
        assertFalse(service.isFlinkSelectDisabled(),
                "nothing in this class makes the planner fail, so the breaker must still be closed");
    }
}
