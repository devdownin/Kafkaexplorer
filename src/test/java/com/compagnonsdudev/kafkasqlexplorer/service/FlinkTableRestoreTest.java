// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors
package com.compagnonsdudev.kafkasqlexplorer.service;

import com.compagnonsdudev.kafkasqlexplorer.config.ExplorerConfig;
import com.compagnonsdudev.kafkasqlexplorer.domain.QueryRequest;
import com.compagnonsdudev.kafkasqlexplorer.domain.QueryResult;
import org.apache.flink.streaming.api.environment.StreamExecutionEnvironment;
import org.apache.flink.table.api.EnvironmentSettings;
import org.apache.flink.table.api.bridge.java.StreamTableEnvironment;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * Stop, start, and find the table you wrote — through both halves' real code paths.
 *
 * <p>A restart is simulated the only way it can honestly be: by building a second
 * {@code FlinkSqlService} over a fresh Flink {@code TableEnvironment}, pointed at the same store.
 * The first one executes the statement and keeps it, the second knows nothing until the restore
 * runs. That seam — written by one process, read by another — is where a format written one way
 * and read another would show up, and it is exactly the seam the whole feature is about.
 *
 * <p>The tables here use the {@code datagen} connector: it needs no broker, and what is under test
 * is whether the definition comes back, not whether Kafka answers.
 */
class FlinkTableRestoreTest {

    @TempDir
    Path tempDir;

    private static final String DDL =
        "CREATE TABLE kse_restore_probe (id INT, label STRING) WITH ('connector' = 'datagen', "
            + "'number-of-rows' = '1')";

    /** One process: its own Flink runtime, over a shared store. */
    private record Process(FlinkSqlService service, FlinkTableStore store) {
    }

    private Process start(Path storePath) throws Exception {
        StreamExecutionEnvironment env = StreamExecutionEnvironment.createLocalEnvironment();
        StreamTableEnvironment tableEnv = StreamTableEnvironment.create(env,
            EnvironmentSettings.newInstance().inStreamingMode().build());

        ExplorerConfig config = new ExplorerConfig();
        config.setDefaultQueryTimeoutMs(30_000);
        config.setFlinkJobStorePath(Files.createTempFile("flink-jobs-restore-", ".json").toString());
        config.setFlinkTableStorePath(storePath.toString());

        FlinkRuntimeCoordinator coordinator = new FlinkRuntimeCoordinator(tableEnv);
        FlinkTableStore store = new FlinkTableStore(config);
        FlinkSqlService service = new FlinkSqlService(
            tableEnv, coordinator, config,
            new SqlQueryValidator(config, tableEnv, coordinator),
            mock(KafkaAdminService.class), mock(SchemaInferenceService.class),
            mock(DdlGeneratorService.class), new FlinkJobStore(config), store);
        return new Process(service, store);
    }

    @Test
    void aTableWrittenInTheEditorIsThereAfterARestart() throws Exception {
        Path storePath = tempDir.resolve("flink-tables.json");

        Process first = start(storePath);
        QueryResult created = first.service().executeSql(QueryRequest.sql(DDL, 1, null, null));
        assertNull(created.error(), "the CREATE TABLE has to succeed for the rest to mean anything");
        assertTrue(first.service().listTables().contains("kse_restore_probe"));

        Process second = start(storePath);
        assertFalse(second.service().listTables().contains("kse_restore_probe"),
            "a fresh Flink catalogue is empty — this is what a restart used to leave behind");

        new FlinkTableRestore(second.service(), second.store()).restore();

        assertTrue(second.service().listTables().contains("kse_restore_probe"));
        assertEquals("INT", second.service().getTableSchema("kse_restore_probe").get("id"),
            "the definition that comes back is the one that was written, not a generated one");
    }

    /**
     * A statement that fails is not the answer to a query — nothing to keep, and keeping it would
     * make every later boot replay a definition that cannot work.
     */
    @Test
    void aCreateTableThatFailedIsNotKept() throws Exception {
        Path storePath = tempDir.resolve("flink-tables.json");
        Process first = start(storePath);

        // A type that does not exist, rather than a connector that does not: Flink resolves a
        // connector only when the table is read, so an unknown one is accepted at DDL time and
        // would not have failed here at all.
        QueryResult failed = first.service().executeSql(QueryRequest.sql(
            "CREATE TABLE kse_broken (id NOSUCHTYPE) WITH ('connector' = 'datagen')",
            1, null, null));

        assertNotNull(failed.error(), "the statement is expected to be refused by Flink");
        assertTrue(first.store().all().isEmpty());
    }

    /**
     * One bad definition costs one table. A restore that stopped at the first failure would lose
     * every definition written after it, for a fault in one — a topic deleted, a connector option
     * renamed by a Flink upgrade.
     */
    @Test
    void oneDefinitionThatNoLongerWorksDoesNotCostTheOthers() throws Exception {
        Path storePath = tempDir.resolve("flink-tables.json");
        Process first = start(storePath);
        // Written directly to the store: it has to have been valid once to be there at all, and
        // what is under test is the boot that comes after it stopped being so — a type or an
        // option a Flink upgrade removed, which is a statement the planner now refuses outright.
        first.store().remember("CREATE TABLE kse_stale (id NOSUCHTYPE) WITH ('connector' = 'datagen')");
        first.service().executeSql(QueryRequest.sql(DDL, 1, null, null));
        assertEquals(2, first.store().all().size());

        Process second = start(storePath);
        new FlinkTableRestore(second.service(), second.store()).restore();

        assertTrue(second.service().listTables().contains("kse_restore_probe"),
            "the good definition was restored despite the bad one being first");
        assertFalse(second.service().listTables().contains("kse_stale"));
    }

    // ── Dropping ──────────────────────────────────────────────────────────────

    @Test
    void droppingATableRemovesItFromFlinkAndFromTheStore() throws Exception {
        Path storePath = tempDir.resolve("flink-tables.json");
        Process first = start(storePath);
        first.service().executeSql(QueryRequest.sql(DDL, 1, null, null));

        assertTrue(first.service().dropTable("kse_restore_probe"));
        assertFalse(first.service().listTables().contains("kse_restore_probe"));

        Process second = start(storePath);
        new FlinkTableRestore(second.service(), second.store()).restore();
        assertFalse(second.service().listTables().contains("kse_restore_probe"),
            "and it stays dropped — otherwise the store would replay a table nobody can remove");
    }

    @Test
    void droppingSomethingThatWasNeverThereSaysSoRatherThanFailing() throws Exception {
        Process process = start(tempDir.resolve("flink-tables.json"));

        assertFalse(process.service().dropTable("kse_never_created"));
    }

    /** A backtick in a path variable is SQL injection into an engine that runs whatever it is given. */
    @Test
    void aNameThatCannotGoIntoAStatementIsRefused() throws Exception {
        Process process = start(tempDir.resolve("flink-tables.json"));

        assertThrows(IllegalArgumentException.class,
            () -> process.service().dropTable("orders`; DROP TABLE users; --"));
    }
}
