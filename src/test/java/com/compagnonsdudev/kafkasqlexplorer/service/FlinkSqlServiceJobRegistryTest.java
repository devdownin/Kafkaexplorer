// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors
package com.compagnonsdudev.kafkasqlexplorer.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.compagnonsdudev.kafkasqlexplorer.config.ExplorerConfig;
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
    private Map<String, FlinkSqlService.JobInfo> heldJobs;
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
            mock(FlinkTableStore.class)
        );

        Field field = FlinkSqlService.class.getDeclaredField("heldJobs");
        field.setAccessible(true);
        heldJobs = (Map<String, FlinkSqlService.JobInfo>) field.get(service);
        heldJobs.clear();
    }

    /**
     * The status wording, on a job the runtime says is running.
     *
     * <p>It went through {@code getActiveJobs()}, which read the job store and is gone with the
     * dashboard's job table. What it was really asserting is
     * {@link FlinkSqlService#buildJobSummary}, so that is what it calls now — the sweep is the
     * only other reader and it can say "kept" or "dropped", never which status was read.
     */
    @Test
    void aHeldJobIsSummarisedWithItsRuntimeStatus() {
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

        FlinkJobSummary summary = service.buildJobSummary(info);

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

        assertEquals(1, service.getHeldJobs().size(), "a cancelled job is still held until it ends");
        FlinkJobSummary summary = service.buildJobSummary(info);
        assertTrue(summary.cancelRequested());
        assertEquals("CANCELLING", summary.status());
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

    /**
     * Un INSERT est refusé, et le refus ne renvoie plus vers une porte qui n'existe plus.
     *
     * <p>Le message nommait {@code /api/query/jobs} — l'endpoint du mode « Flink job » du SQL
     * editor. Ce mode ne fonctionnait pas, il a été retiré et l'endpoint avec, donc un message qui
     * le nomme envoie l'opérateur nulle part. Le refus lui-même reste : il est plus précis que la
     * whitelist d'{@code executeSql}, qui se lit comme une restriction de sécurité.
     */
    @Test
    void executeSyncRefusesAnInsertWithoutPointingAtARemovedEndpoint() {
        var result = service.executeSync(QueryRequest.sql("INSERT INTO job_sink SELECT * FROM job_source", null, null, null));

        assertNotNull(result.error());
        assertTrue(result.error().contains("INSERT INTO is not run by this application"), result.error());
        assertFalse(result.error().contains("/api/query/jobs"), result.error());
        assertFalse(result.error().toLowerCase().contains("job mode"), result.error());
    }

    /**
     * A status poll that never answers says nothing about the job.
     *
     * <p>It was reported as UNKNOWN, which the sweep reads as the job being over: one slow answer
     * stamped an {@code endedAt} on a job that was still running and dropped it out of the
     * registry that {@code POST /api/config}'s 409 guard, the lineage graph and the KPI
     * suggestions all read. The sibling above pins that the job stays; this one pins that the
     * status says so rather than inventing one.
     */
    @Test
    void aStatusThatCouldNotBeReadDoesNotEndTheJob() {
        JobClient client = mock(JobClient.class);
        when(client.getJobID()).thenReturn(new JobID());
        when(client.getJobStatus()).thenReturn(new CompletableFuture<>()); // never completes

        heldJobs.put("job-slow", new FlinkSqlService.JobInfo(
            "job-slow", "INSERT INTO sink SELECT * FROM src", "INSERT", "FLINK_JOB", client,
            1_700_000_000_000L));

        assertEquals(1, service.getHeldJobs().size(), "a job whose status we could not read is still a job");
        FlinkJobSummary summary = service.buildJobSummary(heldJobs.get("job-slow"));
        assertEquals(FlinkSqlService.STATUS_UNAVAILABLE, summary.status());
        assertNull(summary.endedAt(), "and it has not ended — we simply could not tell");
    }

    /*
     * `cancellingAJobThatAlreadyEndedKeepsHowItEnded` stood here.
     *
     * It pinned that pressing Kill on a dashboard card whose job had finished between two
     * five-second polls did not write UNKNOWN over the recorded outcome — the one thing the job
     * store was for. There is no store and no card: `cancelQuery` on an unknown id now answers
     * NO_ACTIVE_JOB and writes nothing, which the two cases above already cover. Kept as a note
     * rather than deleted silently, because the rule it stated — a verdict already observed is not
     * ours to replace — is the reason nothing was put back in its place.
     */

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

        // Without this the assertion below is vacuous: an empty map proves nothing if nothing was
        // ever put in it. `getJobID()` is called from the JobInfo constructor and from nowhere
        // else, so it is the evidence the read really did register a job before handing it back.
        verify(client, atLeastOnce()).getJobID();
        assertTrue(heldJobs.isEmpty(),
            "the read has returned, so nothing is holding its JobClient any more");
    }

}
