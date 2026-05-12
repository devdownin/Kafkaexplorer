package com.yourcompany.kafkasqlexplorer.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yourcompany.kafkasqlexplorer.config.ExplorerConfig;
import com.yourcompany.kafkasqlexplorer.domain.FlinkManagedJobDetails;
import com.yourcompany.kafkasqlexplorer.domain.FlinkJobSummary;
import com.yourcompany.kafkasqlexplorer.domain.QueryRequest;
import org.apache.flink.api.common.JobID;
import org.apache.flink.api.common.JobStatus;
import org.apache.flink.core.execution.JobClient;
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
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class FlinkSqlServiceJobRegistryTest {

    private FlinkSqlService service;
    private FlinkJobStore flinkJobStore;
    private Map<String, FlinkSqlService.JobInfo> activeJobs;
    private Path storePath;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() throws Exception {
        StreamTableEnvironment tableEnv = StreamTableEnvironment.create(
            StreamExecutionEnvironment.createLocalEnvironment(),
            EnvironmentSettings.newInstance().inStreamingMode().build()
        );

        ExplorerConfig config = new ExplorerConfig();
        config.setDefaultMaxRows(50);
        config.setDefaultQueryTimeoutMs(10_000);
        storePath = Files.createTempFile("flink-job-store-", ".json");
        config.setFlinkJobStorePath(storePath.toString());
        flinkJobStore = new FlinkJobStore(new ObjectMapper(), config);
        FlinkRuntimeCoordinator runtimeCoordinator = new FlinkRuntimeCoordinator(tableEnv);

        service = new FlinkSqlService(
            tableEnv,
            runtimeCoordinator,
            config,
            new SqlQueryValidator(config, tableEnv, runtimeCoordinator),
            mock(KafkaAdminService.class),
            mock(SchemaInferenceService.class),
            mock(DdlGeneratorService.class),
            flinkJobStore
        );

        tableEnv.executeSql(
            "CREATE TABLE IF NOT EXISTS job_source (" +
                "  id BIGINT" +
                ") WITH (" +
                "  'connector'='datagen'," +
                "  'number-of-rows'='1'" +
                ")"
        );
        tableEnv.executeSql(
            "CREATE TABLE IF NOT EXISTS job_sink (" +
                "  id BIGINT" +
                ") WITH (" +
                "  'connector'='blackhole'" +
                ")"
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

        FlinkJobSummary summary = spyService.submitJob(QueryRequest.builder()
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
        TableResult mockResult = mock(TableResult.class);
        JobClient mockClient = mock(JobClient.class);
        when(mockClient.getJobID()).thenReturn(JobID.generate());
        when(mockClient.getJobStatus()).thenReturn(CompletableFuture.completedFuture(JobStatus.RUNNING));
        when(mockResult.getJobClient()).thenReturn(java.util.Optional.of(mockClient));

        FlinkSqlService spyService = spy(service);
        doReturn(mockResult).when(spyService).executeMutationSql(anyString(), anyString());

        FlinkJobSummary summary = spyService.submitJob(QueryRequest.builder()
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
        return config;
    }
}
