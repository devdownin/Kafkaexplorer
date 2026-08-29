// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors
package com.compagnonsdudev.kafkasqlexplorer.service;

import com.compagnonsdudev.kafkasqlexplorer.config.ExplorerConfig;
import com.compagnonsdudev.kafkasqlexplorer.domain.FlinkManagedJobDetails;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The store had no test at all, and what it holds is the only record of what the Flink engine has
 * been asked to run. Each case here is a defect it shipped with rather than a property of the
 * design: the store is written to on a five-second poll, read at boot, and fed by every statement
 * the planner answers — so "it grows", "it rewrites itself for nothing" and "it keeps the secret
 * out of a statement that was refused" are not hypotheticals.
 */
class FlinkJobStoreTest {

    @TempDir
    Path dir;

    private ExplorerConfig configFor(Path store) {
        ExplorerConfig config = new ExplorerConfig();
        config.setFlinkJobStorePath(store.toString());
        return config;
    }

    private FlinkJobStore storeAt(Path store) {
        return new FlinkJobStore(configFor(store));
    }

    /**
     * The case that looks harmless: a {@code CREATE TABLE} submitted in Flink Job mode is
     * <em>rejected</em> — and the rejection path filed the statement verbatim, so the Kafka client
     * properties it carries went to {@code data/} and came back out through
     * {@code GET /api/query/jobs}. Everything else in this codebase that returns or logs DDL goes
     * through {@code maskSensitiveProperties}; this store was the exception.
     */
    @Test
    void aStoredStatementCarriesNoCredentials() throws Exception {
        Path file = dir.resolve("flink-jobs.json");
        FlinkJobStore store = storeAt(file);

        store.create("job-1", null, "CREATE_TABLE", "ASYNC_JOB", "FAILED", "Rejected before execution",
            "CREATE TABLE t (id STRING) WITH ('connector'='kafka', "
                + "'properties.ssl.truststore.password' = 'hunter2-truststore', "
                + "'properties.sasl.jaas.config' = 'org.apache.kafka.common.security.plain.PlainLoginModule "
                + "required username=\"k\" password=\"hunter2-jaas\";')",
            1_700_000_000_000L, "Only INSERT INTO statements are allowed in Flink Job mode.");

        String served = store.findById("job-1").orElseThrow().sql();
        String onDisk = Files.readString(file);

        assertFalse(served.contains("hunter2-truststore"), "the API must not hand back a password");
        assertFalse(served.contains("hunter2-jaas"), "the API must not hand back a JAAS secret");
        assertFalse(onDisk.contains("hunter2-truststore"), "the store must not write a password to disk");
        assertFalse(onDisk.contains("hunter2-jaas"), "the store must not write a JAAS secret to disk");
        assertTrue(served.contains("******"), "the statement is still readable, minus its secrets");
    }

    /**
     * A running job answers RUNNING on every poll, and the dashboard polls every five seconds. The
     * store rewrote its whole file each time to record that nothing had happened, because
     * {@code lastUpdatedAt} moved — which is a fact about the clock, not about the job.
     */
    @Test
    void anObservationThatChangedNothingIsNotWritten() {
        FlinkJobStore store = storeAt(dir.resolve("flink-jobs.json"));
        FlinkManagedJobDetails created = store.create("job-1", "flink-1", "INSERT", "ASYNC_JOB",
            "RUNNING", "Submitted via Flink Job mode", "INSERT INTO sink SELECT * FROM src",
            1_700_000_000_000L, null);

        FlinkManagedJobDetails again = store.update("job-1", "RUNNING", "Submitted via Flink Job mode",
            null, false, null, null, "flink-1");

        assertSame(created, again, "an unchanged record is handed back, not rebuilt and rewritten");
        assertEquals(created.lastUpdatedAt(), again.lastUpdatedAt(),
            "lastUpdatedAt means 'when this record last changed'");
        assertEquals(1, again.history().size(), "and nothing is added to the history either");
    }

    /** The other half: a real change still lands, history and all. */
    @Test
    void anObservationThatChangedSomethingIsWritten() throws Exception {
        Path file = dir.resolve("flink-jobs.json");
        FlinkJobStore store = storeAt(file);
        store.create("job-1", "flink-1", "INSERT", "ASYNC_JOB", "RUNNING", "Submitted via Flink Job mode",
            "INSERT INTO sink SELECT * FROM src", 1_700_000_000_000L, null);

        FlinkManagedJobDetails updated = store.update("job-1", "FINISHED", null, null, false, null, null, null);

        assertEquals("FINISHED", updated.status());
        assertEquals(2, updated.history().size());
        assertTrue(Files.readString(file).contains("FINISHED"), "and it reached the file");
    }

    /**
     * Retention is a duration, which is the right rule for the handful of submitted jobs this store
     * was written for and the wrong one for what actually writes here — every statement the planner
     * answers. A metric on a thirty-second loop contributes ~2 900 records a day, and each write
     * serialises the whole list.
     */
    @Test
    void theStoreIsBoundedInCount() {
        FlinkJobStore store = storeAt(dir.resolve("flink-jobs.json"));
        int over = FlinkJobStore.MAX_RETAINED_JOBS + 10;
        for (int i = 0; i < over; i++) {
            store.create("job-" + i, "flink-" + i, "SELECT", "SYNC_READ", "FINISHED", "done",
                "SELECT 1", 1_700_000_000_000L + i, null);
        }

        assertEquals(FlinkJobStore.MAX_RETAINED_JOBS, store.listAll().size());
        assertTrue(store.findById("job-0").isEmpty(), "the oldest went first");
        assertTrue(store.findById("job-" + (over - 1)).isPresent(), "the newest stayed");
    }

    /**
     * And the bound never removes a job that is still running: that is precisely the row the
     * dashboard, the 409 repoint guard and the lineage graph are asking about.
     */
    @Test
    void aRunningJobSurvivesTheCountBound() {
        FlinkJobStore store = storeAt(dir.resolve("flink-jobs.json"));
        // Oldest of all, so age alone would have chosen it first.
        store.create("job-live", "flink-live", "INSERT", "ASYNC_JOB", "RUNNING", "Submitted via Flink Job mode",
            "INSERT INTO sink SELECT * FROM src", 1_600_000_000_000L, null);
        for (int i = 0; i < FlinkJobStore.MAX_RETAINED_JOBS + 10; i++) {
            store.create("job-" + i, "flink-" + i, "SELECT", "SYNC_READ", "FINISHED", "done",
                "SELECT 1", 1_700_000_000_000L + i, null);
        }

        assertTrue(store.findById("job-live").isPresent(), "a job still running is never dropped");
        assertEquals(FlinkJobStore.MAX_RETAINED_JOBS, store.listAll().size());
    }

    /**
     * Retention that only applies while the application is busy is not retention: the prune ran on
     * the way to a write, so a store nothing wrote to again kept its expired records for good — and
     * handed them back at the next boot.
     */
    @Test
    void anExpiredRecordIsGoneFromTheFileAtBoot() throws Exception {
        Path file = dir.resolve("flink-jobs.json");
        long twoDaysAgo = System.currentTimeMillis() - 48L * 60 * 60 * 1000;
        Files.writeString(file, "[{\"queryId\":\"old\",\"flinkJobId\":\"f\",\"statementType\":\"SELECT\","
            + "\"executionMode\":\"SYNC_READ\",\"status\":\"FINISHED\",\"statusDetail\":\"done\","
            + "\"sql\":\"SELECT 1\",\"startedAt\":" + twoDaysAgo + ",\"endedAt\":" + twoDaysAgo + ","
            + "\"cancelRequested\":false,\"cancelRequestedAt\":null,\"errorMessage\":null,"
            + "\"lastUpdatedAt\":" + twoDaysAgo + ",\"history\":[]}]");

        FlinkJobStore store = storeAt(file);   // default retention is 24 h

        assertTrue(store.findById("old").isEmpty());
        assertFalse(Files.readString(file).contains("\"old\""),
            "the prune has to reach the file, or nothing ever removes it");
    }

    /**
     * A store that will not parse costs what it held, never the boot — and it is read at boot,
     * which is exactly when an interrupted write from the previous run surfaces.
     */
    @Test
    void aFileThatWillNotParseCostsItsContentAndNothingElse() throws Exception {
        Path file = dir.resolve("flink-jobs.json");
        Files.writeString(file, "[{\"queryId\": \"half-writ");

        FlinkJobStore store = storeAt(file);

        assertTrue(store.listAll().isEmpty());
        assertEquals("job-1", store.create("job-1", "f", "INSERT", "ASYNC_JOB", "RUNNING", "d",
            "INSERT INTO a SELECT * FROM b", 1_700_000_000_000L, null).queryId());
    }

    /**
     * {@code data/} is a volume an operator may mount beside other things, and the class that
     * writes the other two stores there restricts them to their owner. This one wrote in place with
     * the default umask.
     */
    @Test
    void theFileIsNotWorldReadable() throws Exception {
        Path file = dir.resolve("flink-jobs.json");
        FlinkJobStore store = storeAt(file);
        store.create("job-1", "f", "INSERT", "ASYNC_JOB", "RUNNING", "d",
            "INSERT INTO a SELECT * FROM b", 1_700_000_000_000L, null);

        Set<PosixFilePermission> perms;
        try {
            perms = Files.getPosixFilePermissions(file);
        } catch (UnsupportedOperationException notPosix) {
            return;   // best-effort by design — see JsonStoreFile.restrictToOwner
        }
        assertEquals(Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE), perms);
    }
}
