package com.yourcompany.kafkasqlexplorer.service;

import com.yourcompany.kafkasqlexplorer.config.ExplorerConfig;
import com.yourcompany.kafkasqlexplorer.domain.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class AuditServiceTest {

    private KafkaAdminService kafkaAdminService;
    private FlinkSqlService flinkSqlService;
    private SchemaInferenceService schemaInferenceService;
    private DdlGeneratorService ddlGeneratorService;
    private NamingConventionService namingConventionService;
    private com.yourcompany.kafkasqlexplorer.config.KafkaConfig kafkaConfig;
    private ExplorerConfig explorerConfig;
    private AuditService auditService;

    @BeforeEach
    void setUp() {
        kafkaAdminService = mock(KafkaAdminService.class);
        flinkSqlService = mock(FlinkSqlService.class);
        schemaInferenceService = mock(SchemaInferenceService.class);
        ddlGeneratorService = mock(DdlGeneratorService.class);
        namingConventionService = new NamingConventionService();
        kafkaConfig = mock(com.yourcompany.kafkasqlexplorer.config.KafkaConfig.class);
        explorerConfig = new ExplorerConfig();
        when(kafkaConfig.getKafkaProperties()).thenReturn(Map.of("bootstrap.servers", "localhost:9092"));

        auditService = new AuditService(kafkaAdminService, flinkSqlService, schemaInferenceService, ddlGeneratorService, namingConventionService, new MessageFieldExtractorService(), kafkaConfig, explorerConfig) {
            @Override
            protected void persistAuditHistory(AuditReport report) {
                // Skip Kafka persistence in unit tests
            }
        };
    }

    @Test
    void testStartAudit() throws Exception {
        when(kafkaAdminService.listTopics()).thenReturn(Collections.emptyList());
        String auditId = auditService.startAudit(AuditOptions.all());
        assertNotNull(auditId);
        AuditReport report = auditService.getAuditReport(auditId);
        assertNotNull(report);
        // It might be COMPLETED already if it runs very fast even if async
        assertTrue(List.of(AuditStatus.RUNNING, AuditStatus.COMPLETED).contains(report.status()));
    }

    @Test
    void testAuditProcess() throws Exception {
        String auditId = "test-audit";
        when(kafkaAdminService.listTopics()).thenReturn(List.of("demo.test.1", "demo.test.2"));
        when(kafkaAdminService.getTopicsSize(any())).thenReturn(Map.of("demo.test.1", 100L, "demo.test.2", 80L));
        when(schemaInferenceService.detectFormat(anyString())).thenReturn(MessageFormat.JSON);
        when(schemaInferenceService.inferSchema(anyString(), any())).thenReturn(Map.of("id", "STRING"));
        when(flinkSqlService.listTables()).thenReturn(Collections.emptyList());
        // Without this stub, generateDdl() returns null → QueryRequest.sql() = null → NPE in mock answer
        when(ddlGeneratorService.generateDdl(anyString(), any(), any()))
                .thenReturn("CREATE TABLE demo_test_1 (id STRING) WITH ('connector'='blackhole')");

        // Mock Flink count results
        QueryResult count1 = new QueryResult(List.of("EXPR$0"), List.of(Map.of("EXPR$0", 100L)), 10, null);
        QueryResult count2 = new QueryResult(List.of("EXPR$0"), List.of(Map.of("EXPR$0", 80L)), 10, null);
        QueryResult latency = new QueryResult(List.of("AVG"), List.of(Map.of("AVG", 500L)), 10, null);

        when(flinkSqlService.executeSql(any(QueryRequest.class))).thenAnswer(invocation -> {
            QueryRequest req = invocation.getArgument(0);
            if (req.sql().contains("COUNT(*)")) {
                if (req.sql().contains("demo.test.1")) return count1;
                return count2;
            }
            if (req.sql().contains("AVG")) return latency;
            return new QueryResult(Collections.emptyList(), Collections.emptyList(), 0, null);
        });

        // Run audit synchronously for testing
        auditService.runAuditAsync(auditId, AuditOptions.all());

        AuditReport report = auditService.getAuditReport(auditId);
        if (AuditStatus.FAILED.equals(report.status())) {
            fail("Audit failed with: " + report.globalStats().get("error"));
        }
        assertEquals(AuditStatus.COMPLETED, report.status());
        assertEquals(2, report.totalTopics());
        assertEquals(180, report.totalMessages());

        // Check flows
        assertFalse(report.flowAudits().isEmpty());
        FlowAudit flow = report.flowAudits().get(0);
        assertEquals("demo.test", flow.flowName());
        assertEquals(2, flow.steps().size());
    }

    @Test
    void auditIsolatesAFailingTopic() throws Exception {
        when(kafkaAdminService.listTopics()).thenReturn(List.of("demo.test.1", "demo.test.2"));
        when(kafkaAdminService.getTopicsSize(any())).thenReturn(Map.of("demo.test.1", 100L, "demo.test.2", 80L));
        when(schemaInferenceService.detectFormat(anyString())).thenReturn(MessageFormat.JSON);
        when(schemaInferenceService.inferSchema(eq("demo.test.1"), any())).thenReturn(Map.of("id", "STRING"));
        // One topic blows up during audit — it must not abort the whole run.
        when(schemaInferenceService.inferSchema(eq("demo.test.2"), any())).thenThrow(new RuntimeException("boom"));
        when(flinkSqlService.listTables()).thenReturn(Collections.emptyList());
        when(ddlGeneratorService.generateDdl(anyString(), any(), any()))
                .thenReturn("CREATE TABLE demo_test_1 (id STRING) WITH ('connector'='blackhole')");
        when(flinkSqlService.executeSql(any(QueryRequest.class)))
                .thenReturn(new QueryResult(List.of("EXPR$0"), List.of(Map.of("EXPR$0", 100L)), 10, null));

        auditService.runAuditAsync("iso", AuditOptions.all());

        AuditReport report = auditService.getAuditReport("iso");
        assertEquals(AuditStatus.COMPLETED, report.status(), "one bad topic must not fail the whole audit");
        assertEquals(2, report.totalTopics());

        TopicAudit failed = report.topicAudits().stream()
                .filter(t -> "demo.test.2".equals(t.name())).findFirst().orElseThrow();
        assertEquals(HealthStatus.UNHEALTHY, failed.healthStatus());
        assertTrue(failed.issues().stream().anyMatch(s -> s.contains("Audit failed")),
                "the degraded topic should carry the failure reason");

        TopicAudit ok = report.topicAudits().stream()
                .filter(t -> "demo.test.1".equals(t.name())).findFirst().orElseThrow();
        assertEquals(HealthStatus.HEALTHY, ok.healthStatus());
    }
}
