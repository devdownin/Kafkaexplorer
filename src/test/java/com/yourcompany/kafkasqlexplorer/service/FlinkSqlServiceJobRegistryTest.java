package com.yourcompany.kafkasqlexplorer.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yourcompany.kafkasqlexplorer.config.ExplorerConfig;
import com.yourcompany.kafkasqlexplorer.domain.FlinkManagedJobDetails;
import com.yourcompany.kafkasqlexplorer.domain.FlinkJobSummary;
import com.yourcompany.kafkasqlexplorer.domain.QueryRequest;
import org.apache.flink.api.common.JobID;
import org.apache.flink.api.common.JobStatus;
import org.apache.flink.core.execution.JobClient;
import org.apache.flink.table.api.TableEnvironment;
import org.apache.flink.table.api.TableResult;
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
import static org.mockito.Mockito.*;

class FlinkSqlServiceJobRegistryTest {

    private FlinkSqlService service;
    private FlinkJobStore flinkJobStore;
    private Map<String, FlinkSqlService.JobInfo> activeJobs;
    private Path storePath;
    private TableEnvironment tableEnv;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() throws Exception {
        tableEnv = mock(TableEnvironment.class);
        // FlinkSqlService calls listTables() in autoRegisterTableIfNeeded
        when(tableEnv.listTables()).thenReturn(new String[]{"job_sink", "job_source"});

        ExplorerConfig config = new ExplorerConfig();
        config.setDefaultMaxRows(50);
        config.setDefaultQueryTimeoutMs(10_000);
        storePath = Files.createTempFile("flink-job-store-", ".json");
        config.setFlinkJobStorePath(storePath.toString());
        config.setFlinkJobRetentionHours(24);
        flinkJobStore = new FlinkJobStore(new ObjectMapper(), config);

        FlinkRuntimeCoordinator runtimeCoordinator = mock(FlinkRuntimeCoordinator.class);
        // Mock runtimeCoordinator to just run the actions immediately
        when(runtimeCoordinator.runRead(anyString(), any(java.util.function.Supplier.class))).thenAnswer(inv -> {
            java.util.function.Supplier<?> s = inv.getArgument(1);
            return s.get();
        });
        when(runtimeCoordinator.runMutation(anyString(), any(java.util.function.Supplier.class))).thenAnswer(inv -> {
            java.util.function.Supplier<?> s = inv.getArgument(1);
            return s.get();
        });

        service = new FlinkSqlService(
            tableEnv,
            runtimeCoordinator,
            config,
            mock(SqlQueryValidator.class),
            mock(KafkaAdminService.class),
            mock(SchemaInferenceService.class),
            mock(DdlGeneratorService.class),
            flinkJobStore
        );

        Field field = FlinkSqlService.class.getDeclaredField("activeJobs");
        field.setAccessible(true);
        activeJobs = (Map<String, FlinkSqlService.JobInfo>) field.get(service);
        activeJobs.clear();
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
        activeJobs.put("job-1", info);

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
        activeJobs.put("job-2", info);

        service.cancelQuery("job-2");
        List<FlinkJobSummary> jobs = service.getActiveJobs();

        assertEquals(1, jobs.size());
        assertTrue(jobs.get(0).cancelRequested());
        assertEquals("CANCELLING", jobs.get(0).status());
        verify(client).cancel();
    }

    @Test
    void submitJobRegistersInsertIntoStatementWithoutSyncCollection() {
        TableResult tableResult = mock(TableResult.class);
        JobClient client = mock(JobClient.class);
        when(client.getJobID()).thenReturn(new JobID());
        when(client.getJobStatus()).thenReturn(CompletableFuture.completedFuture(JobStatus.RUNNING));
        when(tableResult.getJobClient()).thenReturn(Optional.of(client));

        when(tableEnv.executeSql(anyString())).thenReturn(tableResult);

        FlinkJobSummary summary = service.submitJob(QueryRequest.builder()
            .sql("INSERT INTO job_sink SELECT * FROM job_source")
            .build());

        assertEquals("INSERT", summary.statementType());
        assertFalse(summary.queryId().isBlank());
        assertFalse(summary.flinkJobId().isBlank());
        assertTrue(activeJobs.containsKey(summary.queryId()));
    }

    @Test
    void executeSyncRejectsInsertStatementsWithModeGuidance() {
        var result = service.executeSync(QueryRequest.builder()
            .sql("INSERT INTO job_sink SELECT * FROM job_source")
            .build());

        assertNotNull(result.error());
        assertTrue(result.error().contains("/api/query/jobs"));
    }

    @Test
    void submittedJobIsRecoverableFromPersistentStore() {
        TableResult tableResult = mock(TableResult.class);
        JobClient client = mock(JobClient.class);
        when(client.getJobID()).thenReturn(new JobID());
        when(client.getJobStatus()).thenReturn(CompletableFuture.completedFuture(JobStatus.RUNNING));
        when(tableResult.getJobClient()).thenReturn(Optional.of(client));

        when(tableEnv.executeSql(anyString())).thenReturn(tableResult);

        FlinkJobSummary summary = service.submitJob(QueryRequest.builder()
            .sql("INSERT INTO job_sink SELECT * FROM job_source")
            .build());

        FlinkJobStore reloadedStore = new FlinkJobStore(new ObjectMapper(), configFor(storePath));
        FlinkManagedJobDetails persisted = reloadedStore.findById(summary.queryId()).orElseThrow();

        assertEquals(summary.queryId(), persisted.queryId());
        assertEquals("ASYNC_JOB", persisted.executionMode());
        assertFalse(persisted.history().isEmpty());
    }

    private ExplorerConfig configFor(Path path) {
        ExplorerConfig config = new ExplorerConfig();
        config.setDefaultMaxRows(50);
        config.setDefaultQueryTimeoutMs(10_000);
        config.setFlinkJobStorePath(path.toString());
        config.setFlinkJobRetentionHours(24);
        return config;
    }
}
