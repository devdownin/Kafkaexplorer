// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors
package com.compagnonsdudev.kafkasqlexplorer.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.compagnonsdudev.kafkasqlexplorer.config.ExplorerConfig;
import com.compagnonsdudev.kafkasqlexplorer.domain.FlinkManagedJobDetails;
import com.compagnonsdudev.kafkasqlexplorer.domain.FlinkJobSummary;
import com.compagnonsdudev.kafkasqlexplorer.domain.QueryRequest;
import org.apache.flink.api.common.JobID;
import org.apache.flink.api.common.JobStatus;
import org.apache.flink.core.execution.JobClient;
import org.apache.flink.table.api.TableEnvironment;
import org.apache.flink.table.api.TableResult;
import org.apache.flink.streaming.api.environment.StreamExecutionEnvironment;
import org.apache.flink.table.api.EnvironmentSettings;
import org.apache.flink.table.api.bridge.java.StreamTableEnvironment;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.*;

class FlinkSqlServiceJobRegistryTest {

    private FlinkSqlService service;
    private FlinkJobStore flinkJobStore;
    private Map<String, FlinkSqlService.JobInfo> heldJobs;
    private Path storePath;
    private TableEnvironment tableEnv;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() throws Exception {
        tableEnv = mock(TableEnvironment.class);
        // Une soumission enregistre désormais sa table source comme le fait une lecture, donc
        // elle demande le catalogue. Non bouchonné, le mock rend `null` et `listTables()` part
        // en NPE avant d'avoir rien soumis — ce qui ne dirait rien du registre de jobs, seul
        // objet de cette classe. Vide : ces cas n'ont aucun topic derrière eux.
        when(tableEnv.listTables()).thenReturn(new String[0]);

        ExplorerConfig config = new ExplorerConfig();
        config.setAllowCrossJoin(true);
        config.setAllowSystemTableAccess(true);
        config.setDefaultMaxRows(50);
        config.setDefaultQueryTimeoutMs(10_000);
        storePath = Files.createTempFile("flink-job-store-", ".json");
        config.setFlinkJobStorePath(storePath.toString());
        flinkJobStore = new FlinkJobStore(config);

        // Mocking FlinkRuntimeCoordinator to execute actions immediately
        FlinkRuntimeCoordinator runtimeCoordinator = mock(FlinkRuntimeCoordinator.class);
        when(runtimeCoordinator.runMutation(anyString(), any(java.util.function.Supplier.class)))
            .thenAnswer(invocation -> ((java.util.function.Supplier<?>) invocation.getArgument(1)).get());
        when(runtimeCoordinator.runRead(anyString(), any(java.util.function.Supplier.class)))
            .thenAnswer(invocation -> ((java.util.function.Supplier<?>) invocation.getArgument(1)).get());
        // The planner path passes its own wait budget, which is a different overload — unstubbed,
        // it answers null and the statement fails before ever registering a job.
        when(runtimeCoordinator.runMutation(anyString(), any(java.util.function.Supplier.class), anyLong()))
            .thenAnswer(invocation -> ((java.util.function.Supplier<?>) invocation.getArgument(1)).get());
        when(runtimeCoordinator.runRead(anyString(), any(java.util.function.Supplier.class), anyLong()))
            .thenAnswer(invocation -> ((java.util.function.Supplier<?>) invocation.getArgument(1)).get());

        SqlQueryValidator validator = mock(SqlQueryValidator.class);
        doNothing().when(validator).validate(anyString());

        service = new FlinkSqlService(
            tableEnv,
            runtimeCoordinator,
            config,
            validator,
            mock(KafkaAdminService.class),
            mock(SchemaInferenceService.class),
            mock(DdlGeneratorService.class),
            flinkJobStore,
            mock(FlinkTableStore.class)
        );

        Field field = FlinkSqlService.class.getDeclaredField("heldJobs");
        field.setAccessible(true);
        heldJobs = (Map<String, FlinkSqlService.JobInfo>) field.get(service);
        heldJobs.clear();
    }

    @Test
    void getActiveJobsReturnsStructuredStatus() {
        JobClient client = mock(JobClient.class);
        when(client.getJobID()).thenReturn(new JobID());
        when(client.getJobStatus()).thenReturn(CompletableFuture.completedFuture(JobStatus.RUNNING));

        FlinkSqlService.JobInfo info = new FlinkSqlService.JobInfo(
            "job-1",
            "CREATE TABLE demo (id STRING)",
            "CREATE_TABLE",
            "SYNC_READ",
            client,
            1_700_000_000_000L
        );
        heldJobs.put("job-1", info);

        List<FlinkJobSummary> jobs = service.getActiveJobs();

        assertEquals(1, jobs.size());
        FlinkJobSummary summary = jobs.get(0);
        assertEquals("job-1", summary.queryId());
        assertEquals("CREATE_TABLE", summary.statementType());
        assertEquals("RUNNING", summary.status());
        assertNotNull(summary.flinkJobId());
        assertNull(summary.endedAt());
    }

    @Test
    void getHeldJobsDropsAJobThatHasFinished() {
        // The regression this pins: getHeldJobs() used to hand back `heldJobs`
        // without reconciling it, so a finished query stayed "active" for its three callers —
        // POST /api/config (which refuses a cluster repoint with 409 while jobs run), the
        // lineage graph, and the KPI suggestions — until some other screen called
        // getActiveJobs(). An operator could be refused a config save in the name of a query
        // they had already watched finish.
        JobClient client = mock(JobClient.class);
        when(client.getJobID()).thenReturn(new JobID());
        when(client.getJobStatus()).thenReturn(CompletableFuture.completedFuture(JobStatus.FINISHED));

        heldJobs.put("job-done", new FlinkSqlService.JobInfo(
            "job-done", "SELECT 1", "SELECT", "SYNC_READ", client, 1_700_000_000_000L));

        assertTrue(service.getHeldJobs().isEmpty(),
            "a finished job must not be reported as active");
    }

    @Test
    void getHeldJobsKeepsAJobStillRunning() {
        // The other half: reconciling must not throw away work that really is in flight, or the
        // 409 guard would stop protecting the thing it exists for.
        JobClient client = mock(JobClient.class);
        when(client.getJobID()).thenReturn(new JobID());
        when(client.getJobStatus()).thenReturn(CompletableFuture.completedFuture(JobStatus.RUNNING));

        heldJobs.put("job-live", new FlinkSqlService.JobInfo(
            "job-live", "INSERT INTO sink SELECT * FROM src", "INSERT", "FLINK_JOB", client,
            1_700_000_000_000L));

        assertEquals(1, service.getHeldJobs().size());
    }

    @Test
    void cancelQueryKeepsJobButMarksCancellationRequested() {
        JobClient client = mock(JobClient.class);
        when(client.getJobID()).thenReturn(new JobID());
        when(client.getJobStatus()).thenReturn(CompletableFuture.completedFuture(JobStatus.RUNNING));
        when(client.cancel()).thenReturn(CompletableFuture.completedFuture(null));

        FlinkSqlService.JobInfo info = new FlinkSqlService.JobInfo(
            "job-2",
            "CREATE TABLE demo2 (id STRING)",
            "CREATE_TABLE",
            "SYNC_READ",
            client,
            1_700_000_000_000L
        );
        heldJobs.put("job-2", info);

        service.cancelQuery("job-2");
        List<FlinkJobSummary> jobs = service.getActiveJobs();

        assertEquals(1, jobs.size());
        assertTrue(jobs.get(0).cancelRequested());
        assertEquals("CANCELLING", jobs.get(0).status());
        verify(client).cancel();
    }

    /**
     * Pressing Stop just as the query finishes.
     *
     * <p>The embedded runtime gives each job its own MiniCluster and shuts it down when the job
     * ends, so `cancel()` on that JobClient throws Flink's own
     * `IllegalStateException("MiniCluster is not yet running or has already been shut down")` —
     * synchronously. Unguarded, that reached `POST /api/query/cancel/{id}` as a 500 with a stack
     * trace about a MiniCluster the user does not know they are running.
     */
    @Test
    void cancelQuerySaysNothingWasCancelledWhenTheJobHasAlreadyFinished() {
        JobClient client = mock(JobClient.class);
        when(client.getJobID()).thenReturn(new JobID());
        when(client.getJobStatus()).thenThrow(
            new IllegalStateException("MiniCluster is not yet running or has already been shut down."));
        when(client.cancel()).thenThrow(
            new IllegalStateException("MiniCluster is not yet running or has already been shut down."));

        heldJobs.put("job-gone", new FlinkSqlService.JobInfo(
            "job-gone", "SELECT 1", "SELECT", "SYNC_READ", client, 1_700_000_000_000L));

        // Not CANCELLED: nothing was. Reporting otherwise is the exact claim CancelOutcome exists
        // to prevent, one step further along than the case it was written for.
        assertEquals(FlinkSqlService.CancelOutcome.NO_ACTIVE_JOB, service.cancelQuery("job-gone"));
        assertTrue(service.getHeldJobs().isEmpty(),
            "a job whose runtime is gone must stop counting as active");
    }

    /**
     * The same fact reached through the status poll rather than through Stop.
     *
     * <p>`buildJobSummary` caught every exception into "UNKNOWN" and left `endedAt` null, so a
     * finished job never left `heldJobs` — and the three callers of `getHeldJobs()` act
     * on that answer: `POST /api/config` refuses a cluster repoint with 409, the lineage graph
     * draws a node per job, the KPI suggestions derive an edge from each.
     */
    @Test
    void aJobWhoseRuntimeIsGoneStopsBeingActive() {
        JobClient client = mock(JobClient.class);
        when(client.getJobID()).thenReturn(new JobID());
        when(client.getJobStatus()).thenThrow(
            new IllegalStateException("MiniCluster is not yet running or has already been shut down."));

        heldJobs.put("job-vanished", new FlinkSqlService.JobInfo(
            "job-vanished", "SELECT 1", "SELECT", "SYNC_READ", client, 1_700_000_000_000L));

        assertTrue(service.getHeldJobs().isEmpty());
    }

    /**
     * The other half: a status call that merely times out says nothing about the job, so the job
     * stays active. Marking it ended there would weaken the 409 guard on a slow runtime.
     */
    @Test
    void aJobWhoseStatusTimesOutStaysActive() {
        JobClient client = mock(JobClient.class);
        when(client.getJobID()).thenReturn(new JobID());
        when(client.getJobStatus()).thenReturn(new CompletableFuture<>()); // never completes

        heldJobs.put("job-slow", new FlinkSqlService.JobInfo(
            "job-slow", "INSERT INTO sink SELECT * FROM src", "INSERT", "FLINK_JOB", client,
            1_700_000_000_000L));

        assertEquals(1, service.getHeldJobs().size());
    }

    @Test
    void submitJobRegistersInsertIntoStatementWithoutSyncCollection() {
        // Mock TableResult to avoid Flink optimizer NPE (metadataHandlerProvider=null) in embedded environment
        // for INSERT INTO statements during unit tests.
        TableResult mockResult = mock(TableResult.class);
        JobClient mockClient = mock(JobClient.class);
        when(mockClient.getJobID()).thenReturn(JobID.generate());
        when(mockClient.getJobStatus()).thenReturn(CompletableFuture.completedFuture(JobStatus.RUNNING));
        when(mockResult.getJobClient()).thenReturn(java.util.Optional.of(mockClient));

        // Using a spy to mock executeMutationSql while keeping the rest of the service logic
        FlinkSqlService spyService = spy(service);
        doReturn(mockResult).when(spyService).executeMutationSql(anyString(), anyString());

        FlinkJobSummary summary = spyService.submitJob(QueryRequest.sql("INSERT INTO job_sink SELECT * FROM job_source", null, null, null));

        assertEquals("INSERT", summary.statementType());
        assertFalse(summary.queryId().isBlank());
        assertFalse(summary.flinkJobId().isBlank());
        assertTrue(heldJobs.containsKey(summary.queryId()));
    }

    @Test
    void executeSyncRejectsInsertStatementsWithModeGuidance() {
        var result = service.executeSync(QueryRequest.sql("INSERT INTO job_sink SELECT * FROM job_source", null, null, null));

        assertNotNull(result.error());
        assertTrue(result.error().contains("/api/query/jobs"));
    }

    @Test
    void submittedJobIsRecoverableFromPersistentStore() {
        TableResult mockResult = mock(TableResult.class);
        JobClient mockClient = mock(JobClient.class);
        when(mockClient.getJobID()).thenReturn(JobID.generate());
        when(mockClient.getJobStatus()).thenReturn(CompletableFuture.completedFuture(JobStatus.RUNNING));
        when(mockResult.getJobClient()).thenReturn(java.util.Optional.of(mockClient));

        FlinkSqlService spyService = spy(service);
        doReturn(mockResult).when(spyService).executeMutationSql(anyString(), anyString());

        FlinkJobSummary summary = spyService.submitJob(QueryRequest.sql("INSERT INTO job_sink SELECT * FROM job_source", null, null, null));

        FlinkJobStore reloadedStore = new FlinkJobStore(configFor(storePath));
        FlinkManagedJobDetails persisted = reloadedStore.findById(summary.queryId()).orElseThrow();

        assertEquals(summary.queryId(), persisted.queryId());
        assertEquals("ASYNC_JOB", persisted.executionMode());
        assertFalse(persisted.history().isEmpty());
    }

    /**
     * A status poll that never answers says nothing about the job.
     *
     * <p>It was reported as UNKNOWN, which {@link FlinkJobStore#isTerminal} counts as the job being
     * over: one slow answer stamped an `endedAt` on a job that was still running, dropped it out of
     * `getActiveJobs()` — the dashboard's list — and put it on the retention clock, so a long
     * INSERT could be pruned out of the store while it ran. The sibling above pins the in-memory
     * half of this; the store is where the damage was.
     */
    @Test
    void aStatusThatCouldNotBeReadDoesNotEndTheJob() {
        JobClient client = mock(JobClient.class);
        when(client.getJobID()).thenReturn(new JobID());
        when(client.getJobStatus()).thenReturn(new CompletableFuture<>()); // never completes

        heldJobs.put("job-slow", new FlinkSqlService.JobInfo(
            "job-slow", "INSERT INTO sink SELECT * FROM src", "INSERT", "FLINK_JOB", client,
            1_700_000_000_000L));

        List<FlinkJobSummary> active = service.getActiveJobs();

        assertEquals(1, active.size(), "a job whose status we could not read is still a job");
        assertEquals(FlinkSqlService.STATUS_UNAVAILABLE, active.get(0).status());
        assertNull(active.get(0).endedAt(), "and it has not ended — we simply could not tell");
        assertNull(flinkJobStore.findById("job-slow").orElseThrow().endedAt());
    }

    /**
     * Pressing Kill on a dashboard card whose job finished between two five-second polls.
     *
     * <p>There is no live JobClient by then, and the fallback wrote UNKNOWN over the record — so
     * the one thing the store is for, how the job ended, was destroyed by the gesture that could
     * no longer change it. The attempt is worth recording; the verdict is not ours to replace.
     */
    @Test
    void cancellingAJobThatAlreadyEndedKeepsHowItEnded() {
        flinkJobStore.create("job-over", "flink-1", "INSERT", "ASYNC_JOB", "FINISHED",
            "Submitted via Flink Job mode", "INSERT INTO sink SELECT * FROM src",
            1_700_000_000_000L, null);

        assertEquals(FlinkSqlService.CancelOutcome.NO_ACTIVE_JOB, service.cancelQuery("job-over"));

        FlinkManagedJobDetails after = flinkJobStore.findById("job-over").orElseThrow();
        assertEquals("FINISHED", after.status(), "the recorded outcome stands");
        assertTrue(after.cancelRequested(), "the attempt is still recorded");
        assertTrue(after.history().get(after.history().size() - 1).detail().contains("already ended"),
            "and it is in the history, under the status that was actually observed");
    }

    /**
     * A synchronous read is over when the request returns, and nothing else knows that.
     *
     * <p>Its job used to be left in `heldJobs` until some other caller happened to run the status
     * sweep — which on a headless deployment is nobody, while the metric refresh loop adds one
     * every thirty seconds, each holding a JobClient. An open browser hid it: the dashboard poll is
     * the only sweeper there is.
     */
    @Test
    void aSynchronousReadHandsItsJobBackWhenItReturns() {
        JobClient client = mock(JobClient.class);
        when(client.getJobID()).thenReturn(JobID.generate());
        when(client.getJobStatus()).thenReturn(CompletableFuture.completedFuture(JobStatus.FINISHED));

        TableResult planned = mock(TableResult.class);
        when(planned.getJobClient()).thenReturn(Optional.of(client));
        when(planned.collect()).thenReturn(org.apache.flink.util.CloseableIterator.empty());
        when(planned.getResolvedSchema())
            .thenReturn(org.apache.flink.table.catalog.ResolvedSchema.of());
        when(tableEnv.executeSql(anyString())).thenReturn(planned);

        service.executeSql(new QueryRequest(
            "CREATE TABLE released (id STRING)", null, 10, null, null, "kse-sync-read-1"));

        assertTrue(flinkJobStore.findById("kse-sync-read-1").isPresent(),
            "the statement really did register a job — without this the assertion below is vacuous");
        assertTrue(heldJobs.isEmpty(),
            "the read has returned, so nothing is holding its JobClient any more");
    }

    private ExplorerConfig configFor(Path path) {
        ExplorerConfig config = new ExplorerConfig();
        config.setDefaultMaxRows(50);
        config.setDefaultQueryTimeoutMs(10_000);
        config.setFlinkJobStorePath(path.toString());
        return config;
    }
}
