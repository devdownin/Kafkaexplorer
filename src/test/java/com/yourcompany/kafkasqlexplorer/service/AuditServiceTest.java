package com.yourcompany.kafkasqlexplorer.service;

import com.yourcompany.kafkasqlexplorer.config.ExplorerConfig;
import com.yourcompany.kafkasqlexplorer.domain.*;
import org.apache.kafka.clients.consumer.ConsumerRecord;
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
        when(schemaInferenceService.detectFormat(anyString(), any())).thenReturn(MessageFormat.JSON);
        when(schemaInferenceService.inferSchema(anyString(), any(), any())).thenReturn(Map.of("id", "STRING"));
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
    void auditFlagsLaggingMetadataVersion() throws Exception {
        when(kafkaAdminService.listTopics()).thenReturn(Collections.emptyList());
        when(kafkaAdminService.getTopicsSize(any())).thenReturn(Collections.emptyMap());
        when(kafkaAdminService.getLaggingFeatures()).thenReturn(List.of(
                new java.util.LinkedHashMap<>(Map.of(
                        "feature", "metadata.version",
                        "finalizedVersion", (short) 21,
                        "supportedMaxVersion", (short) 25))));

        auditService.runAuditAsync("lagging", AuditOptions.all());

        AuditReport report = auditService.getAuditReport("lagging");
        assertEquals(AuditStatus.COMPLETED, report.status());
        assertNotNull(report.globalStats().get("laggingFeatures"));
        String warning = (String) report.globalStats().get("metadataVersionWarning");
        assertNotNull(warning, "a lagging metadata.version must surface a warning");
        assertTrue(warning.contains("21") && warning.contains("25"), "warning should cite both versions");
    }

    @Test
    void auditStaysQuietWhenFeaturesAreCurrent() throws Exception {
        when(kafkaAdminService.listTopics()).thenReturn(Collections.emptyList());
        when(kafkaAdminService.getTopicsSize(any())).thenReturn(Collections.emptyMap());
        when(kafkaAdminService.getLaggingFeatures()).thenReturn(Collections.emptyList());

        auditService.runAuditAsync("current", AuditOptions.all());

        AuditReport report = auditService.getAuditReport("current");
        assertEquals(AuditStatus.COMPLETED, report.status());
        assertNull(report.globalStats().get("laggingFeatures"));
        assertNull(report.globalStats().get("metadataVersionWarning"));
    }

    @Test
    void auditIsolatesAFailingTopic() throws Exception {
        when(kafkaAdminService.listTopics()).thenReturn(List.of("demo.test.1", "demo.test.2"));
        when(kafkaAdminService.getTopicsSize(any())).thenReturn(Map.of("demo.test.1", 100L, "demo.test.2", 80L));
        when(schemaInferenceService.detectFormat(anyString(), any())).thenReturn(MessageFormat.JSON);
        when(schemaInferenceService.inferSchema(eq("demo.test.1"), any(), any())).thenReturn(Map.of("id", "STRING"));
        // One topic blows up during audit — it must not abort the whole run.
        when(schemaInferenceService.inferSchema(eq("demo.test.2"), any(), any())).thenThrow(new RuntimeException("boom"));
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

    @Test
    void auditRestrictsToTopicsMatchingThePrefix() throws Exception {
        when(kafkaAdminService.listTopics())
                .thenReturn(List.of("orders.created", "orders.shipped", "payments.done"));
        when(kafkaAdminService.getTopicsSize(any()))
                .thenReturn(Map.of("orders.created", 10L, "orders.shipped", 5L, "payments.done", 7L));
        when(schemaInferenceService.detectFormat(anyString(), any())).thenReturn(MessageFormat.JSON);
        when(schemaInferenceService.inferSchema(anyString(), any(), any())).thenReturn(Map.of("id", "STRING"));
        when(flinkSqlService.listTables()).thenReturn(Collections.emptyList());
        when(ddlGeneratorService.generateDdl(anyString(), any(), any()))
                .thenReturn("CREATE TABLE t (id STRING) WITH ('connector'='blackhole')");
        when(flinkSqlService.executeSql(any(QueryRequest.class)))
                .thenReturn(new QueryResult(List.of("EXPR$0"), List.of(Map.of("EXPR$0", 10L)), 10, null));

        AuditOptions opts = new AuditOptions(true, true, true, true, true, "orders.");
        auditService.runAuditAsync("pref", opts);

        AuditReport report = auditService.getAuditReport("pref");
        assertEquals(AuditStatus.COMPLETED, report.status());
        assertEquals(2, report.totalTopics(), "only the two orders.* topics should be audited");
        assertTrue(report.topicAudits().stream().allMatch(t -> t.name().startsWith("orders.")),
                "no topic outside the prefix should appear");
        assertTrue(report.topicAudits().stream().noneMatch(t -> "payments.done".equals(t.name())));
    }

    @Test
    void aFailedRunAlwaysCarriesAReadableError() throws Exception {
        // getMessage() is null on plenty of exceptions; the report must still say something.
        when(kafkaAdminService.listTopics()).thenThrow(new NullPointerException());

        auditService.runAuditAsync("boom", AuditOptions.all());

        AuditReport report = auditService.getAuditReport("boom");
        assertEquals(AuditStatus.FAILED, report.status());
        assertNotNull(report.globalStats().get("error"), "a failed run must report an error");
        assertEquals("NullPointerException", report.globalStats().get("errorType"));
    }

    @Test
    void oneSampleServesFormatDetectionSchemaInferenceAndPoisonCheck() throws Exception {
        when(kafkaAdminService.listTopics()).thenReturn(List.of("orders.created"));
        when(kafkaAdminService.getTopicsSize(any())).thenReturn(Map.of("orders.created", 3L));
        when(kafkaAdminService.getSampleMessages(eq("orders.created"), anyInt()))
                .thenReturn(List.of("{\"id\":\"1\"}", "{\"id\":\"2\"}"));
        when(schemaInferenceService.detectFormat(anyString(), any())).thenReturn(MessageFormat.JSON);
        when(schemaInferenceService.inferSchema(anyString(), any(), any())).thenReturn(Map.of("id", "STRING"));
        when(flinkSqlService.listTables()).thenReturn(List.of("orders_created"));

        // Schema + poison checks only, so no Flink count and no duplicate scan interfere.
        auditService.runAuditAsync("sample", new AuditOptions(true, true, false, false, false, null));

        assertEquals(AuditStatus.COMPLETED, auditService.getAuditReport("sample").status());
        // Previously: detectFormat, inferSchema and the poison check each opened their own consumer.
        verify(kafkaAdminService, times(1)).getSampleMessages(eq("orders.created"), anyInt());
    }

    @Test
    void poisonDetectionCatchesTruncatedJsonAndRunsWithoutSchemaInference() throws Exception {
        when(kafkaAdminService.listTopics()).thenReturn(List.of("orders.created"));
        when(kafkaAdminService.getTopicsSize(any())).thenReturn(Map.of("orders.created", 3L));
        // Third payload starts with '{' but does not parse — the old first-character check passed it.
        when(kafkaAdminService.getSampleMessages(eq("orders.created"), anyInt()))
                .thenReturn(List.of("{\"id\":\"1\"}", "{\"id\":\"2\"}", "{\"id\":"));

        // checkSchema is OFF: the format used to stay AUTO and the check found nothing at all.
        auditService.runAuditAsync("poison", new AuditOptions(false, true, false, false, false, null));

        TopicAudit audit = auditService.getAuditReport("poison").topicAudits().get(0);
        assertEquals(1, audit.poisonMessageCount(), "the truncated JSON payload is poison");
        assertEquals(HealthStatus.UNHEALTHY, audit.healthStatus());
        verifyNoInteractions(schemaInferenceService);
    }

    @Test
    void duplicateDetectionFallsBackToTheKafkaRecordKey() throws Exception {
        when(kafkaAdminService.listTopics()).thenReturn(List.of("orders.created"));
        when(kafkaAdminService.getTopicsSize(any())).thenReturn(Map.of("orders.created", 3L));
        when(kafkaAdminService.getEarliestRecords(eq("orders.created"), anyInt())).thenReturn(List.of(
                record("orders.created", 0, "k1", "not json"),
                record("orders.created", 1, "k1", "not json"),
                record("orders.created", 2, "k2", "not json")));

        // No schema pass → no id-like field. The scan used to give up and report a clean 0.
        auditService.runAuditAsync("dupes", new AuditOptions(false, false, true, false, false, null));

        TopicAudit audit = auditService.getAuditReport("dupes").topicAudits().get(0);
        assertEquals(1, audit.duplicateCount(), "k1 appears twice");
        assertTrue(audit.issues().stream().anyMatch(i -> i.contains("Kafka record key")),
                "the issue must name the key the scan grouped on: " + audit.issues());
        assertTrue(audit.issues().stream().anyMatch(i -> i.contains("first 3 message")),
                "the issue must state how many messages back the verdict: " + audit.issues());
    }

    @Test
    void anExactCountThatCouldNotRunIsReportedRatherThanHidden() throws Exception {
        when(kafkaAdminService.listTopics()).thenReturn(List.of("orders.created"));
        when(kafkaAdminService.getTopicsSize(any())).thenReturn(Map.of("orders.created", 42L));
        when(flinkSqlService.executeSql(any(QueryRequest.class)))
                .thenReturn(new QueryResult(List.of(), List.of(), 0, "Table 'orders_created' not found"));

        auditService.runAuditAsync("count", new AuditOptions(false, false, false, false, true, null));

        TopicAudit audit = auditService.getAuditReport("count").topicAudits().get(0);
        assertEquals(42L, audit.messageCount(), "falls back to the offset estimate");
        assertTrue(audit.issues().stream().anyMatch(i -> i.startsWith("Exact count unavailable")),
                "the silent fallback must be visible: " + audit.issues());
    }

    @Test
    void aCompletedReportStatesItsScopeAndTiming() throws Exception {
        when(kafkaAdminService.listTopics()).thenReturn(Collections.emptyList());
        when(kafkaAdminService.getTopicsSize(any())).thenReturn(Collections.emptyMap());

        auditService.runAuditAsync("scope", AuditOptions.all());

        Map<String, Object> stats = auditService.getAuditReport("scope").globalStats();
        assertNotNull(stats.get("durationMs"));
        assertNotNull(stats.get("startedAt"));
        assertNotNull(stats.get("options"));
        @SuppressWarnings("unchecked")
        List<String> notes = (List<String>) stats.get("scopeNotes");
        assertNotNull(notes, "bounded scans must declare their bounds");
        assertTrue(notes.stream().anyMatch(n -> n.contains("10000")),
                "the duplicate-scan cap must be stated: " + notes);
    }

    @Test
    void onlyTheLastRunsAreRetained() {
        when(kafkaAdminService.getLaggingFeatures()).thenReturn(Collections.emptyList());
        for (int i = 0; i < 25; i++) {
            auditService.runAuditAsync("run-" + i, AuditOptions.all());
        }
        assertNull(auditService.getAuditReport("run-0"), "the oldest runs must be evicted");
        assertNotNull(auditService.getAuditReport("run-24"), "the newest run must be retained");
    }

    private static ConsumerRecord<String, String> record(String topic, long offset, String key, String value) {
        return new ConsumerRecord<>(topic, 0, offset, key, value);
    }
}
