// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors
package com.compagnonsdudev.kafkasqlexplorer.service;

import com.compagnonsdudev.kafkasqlexplorer.config.ExplorerConfig;
import com.compagnonsdudev.kafkasqlexplorer.domain.*;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class AuditServiceTest {

    private KafkaAdminService kafkaAdminService;
    private FlinkSqlService flinkSqlService;
    private SchemaInferenceService schemaInferenceService;
    private DdlGeneratorService ddlGeneratorService;
    private NamingConventionService namingConventionService;
    private com.compagnonsdudev.kafkasqlexplorer.config.KafkaConfig kafkaConfig;
    private ExplorerConfig explorerConfig;
    private AuditService auditService;

    @BeforeEach
    void setUp() {
        kafkaAdminService = mock(KafkaAdminService.class);
        flinkSqlService = mock(FlinkSqlService.class);
        schemaInferenceService = mock(SchemaInferenceService.class);
        ddlGeneratorService = mock(DdlGeneratorService.class);
        namingConventionService = new NamingConventionService();
        kafkaConfig = mock(com.compagnonsdudev.kafkasqlexplorer.config.KafkaConfig.class);
        explorerConfig = new ExplorerConfig();
        when(kafkaConfig.getKafkaProperties()).thenReturn(Map.of("bootstrap.servers", "localhost:9092"));

        auditService = new AuditService(kafkaAdminService, flinkSqlService, schemaInferenceService, ddlGeneratorService, namingConventionService, new MessageFieldExtractorService(), kafkaConfig, explorerConfig) {
            @Override
            protected void persistAuditHistory(AuditReport report) {
                // Skip Kafka persistence in unit tests
            }
        };

        // The consumer-lag check reads the cluster's groups once and derives each topic's view
        // from that snapshot, so that is the path the tests must drive.
        when(kafkaAdminService.groupSnapshot(anyInt(), any())).thenAnswer(inv -> emptySnapshot());
        // Default for the consumer-lag check, which AuditOptions.all() enables: no group reads the
        // topic. The tests that care about lag override this with groups of their own.
        when(kafkaAdminService.getTopicConsumers(anyString(), any(KafkaAdminService.GroupSnapshot.class)))
                .thenAnswer(inv -> new TopicConsumers(inv.getArgument(0), List.of(), 0, 0, 0, false, true, List.of()));
    }

    @Test
    void testStartAudit() throws Exception {
        when(kafkaAdminService.listTopics()).thenReturn(Collections.emptyList());
        AuditService.AuditStart start = auditService.startAudit(AuditOptions.all());
        assertTrue(start.started(), "the first start must be accepted");
        assertNotNull(start.auditId());
        AuditReport report = auditService.getAuditReport(start.auditId());
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
                if (req.sql().contains("demo_test_1")) return count1;
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

    /**
     * The application's own state topics are not part of the estate under exploration, and
     * auditing them manufactures findings about ourselves: {@code internal.metrics.config} is a
     * keyed store, so every edited metric reads as a duplicate key, and the run writes its own
     * report to {@code internal.audit.history} while that topic is being read. Same defect
     * {@code ExplorerConsumerGroups} removed for the groups, left standing for the topics.
     */
    @Test
    void theApplicationsOwnTopicsAreOutOfScope() throws Exception {
        when(kafkaAdminService.listTopics()).thenReturn(List.of(
                "demo.test.1", "internal.audit.history", "internal.metrics.config",
                "internal.field.mappings"));
        when(kafkaAdminService.getTopicsSize(any())).thenReturn(Map.of("demo.test.1", 10L));
        when(flinkSqlService.listTables()).thenReturn(Collections.emptyList());

        auditService.runAuditAsync("own-topics", AuditOptions.all());

        AuditReport report = auditService.getAuditReport("own-topics");
        assertTrue(report.topicAudits().stream().noneMatch(t -> t.name().startsWith("internal.")),
                "the audit reported on its own bookkeeping topics");
        assertEquals(1, report.totalTopics());
        // Narrowing the scope is never silent.
        Object notes = report.globalStats().get("scopeNotes");
        assertTrue(notes instanceof List<?> list
                        && list.stream().anyMatch(n -> String.valueOf(n).contains("its own state")),
                "the report must say which topics it left out: " + notes);
    }

    /** Naming their prefix is an explicit request, and an explicit request is honoured. */
    @Test
    void namingTheInternalPrefixAuditsThemAnyway() throws Exception {
        when(kafkaAdminService.listTopics()).thenReturn(List.of("demo.test.1", "internal.audit.history"));
        when(kafkaAdminService.getTopicsSize(any())).thenReturn(Map.of("internal.audit.history", 3L));
        when(flinkSqlService.listTables()).thenReturn(Collections.emptyList());

        auditService.runAuditAsync("explicit",
                new AuditOptions(true, true, true, true, true, true, "internal."));

        AuditReport report = auditService.getAuditReport("explicit");
        assertEquals(1, report.totalTopics());
        assertEquals("internal.audit.history", report.topicAudits().get(0).name());
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
        assertEquals(HealthStatus.CRITICAL, failed.healthStatus(),
                "a topic that could not be audited is the worst case, not a mere warning");
        assertTrue(failed.issues().stream().anyMatch(i -> i.message().contains("Audit failed")),
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

        AuditOptions opts = new AuditOptions(true, true, true, true, true, false, "orders.");
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
        auditService.runAuditAsync("sample", new AuditOptions(true, true, false, false, false, false, null));

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
        auditService.runAuditAsync("poison", new AuditOptions(false, true, false, false, false, false, null));

        TopicAudit audit = auditService.getAuditReport("poison").topicAudits().get(0);
        assertEquals(1, audit.poisonMessageCount(), "the truncated JSON payload is poison");
        assertEquals(HealthStatus.CRITICAL, audit.healthStatus(), "unparseable payloads are critical");
        verifyNoInteractions(schemaInferenceService);
    }

    @Test
    void duplicateDetectionFallsBackToTheKafkaRecordKey() throws Exception {
        when(kafkaAdminService.listTopics()).thenReturn(List.of("orders.created"));
        when(kafkaAdminService.getTopicsSize(any())).thenReturn(Map.of("orders.created", 3L));
        when(kafkaAdminService.getRecentRecords(eq("orders.created"), anyInt())).thenReturn(List.of(
                record("orders.created", 0, "k1", "not json"),
                record("orders.created", 1, "k1", "not json"),
                record("orders.created", 2, "k2", "not json")));

        // No schema pass → no id-like field. The scan used to give up and report a clean 0.
        auditService.runAuditAsync("dupes", new AuditOptions(false, false, true, false, false, false, null));

        TopicAudit audit = auditService.getAuditReport("dupes").topicAudits().get(0);
        assertEquals(1, audit.duplicateCount(), "k1 appears twice");
        assertTrue(audit.issues().stream().anyMatch(i -> i.message().contains("Kafka record key")),
                "the issue must name the key the scan grouped on: " + audit.issues());
        assertTrue(audit.issues().stream().anyMatch(i -> i.message().contains("last 3 message")),
                "the issue must state which end of the topic and how many messages: " + audit.issues());
        assertEquals(HealthStatus.WARNING, audit.healthStatus(),
                "duplicates are a signal to look at, not a defect on their own");
    }

    @Test
    void anExactCountThatCouldNotRunIsReportedRatherThanHidden() throws Exception {
        when(kafkaAdminService.listTopics()).thenReturn(List.of("orders.created"));
        when(kafkaAdminService.getTopicsSize(any())).thenReturn(Map.of("orders.created", 42L));
        when(flinkSqlService.executeSql(any(QueryRequest.class)))
                .thenReturn(new QueryResult(List.of(), List.of(), 0, "Table 'orders_created' not found"));

        auditService.runAuditAsync("count", new AuditOptions(false, false, false, false, true, false, null));

        TopicAudit audit = auditService.getAuditReport("count").topicAudits().get(0);
        assertEquals(42L, audit.messageCount(), "falls back to the offset estimate");
        assertTrue(audit.issues().stream().anyMatch(i -> i.message().startsWith("Exact count unavailable")),
                "the silent fallback must be visible: " + audit.issues());
        assertEquals(HealthStatus.WARNING, audit.healthStatus(),
                "a degraded measurement is not bad data");
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

    @Test
    void severityIsGradedAndAggregatedPerSeverity() throws Exception {
        when(kafkaAdminService.listTopics()).thenReturn(List.of("a.critical", "a.warning", "a.clean"));
        when(kafkaAdminService.getTopicsSize(any()))
                .thenReturn(Map.of("a.critical", 5L, "a.warning", 5L, "a.clean", 5L));
        // a.critical: an unparseable payload. a.warning: duplicate record keys. a.clean: neither.
        // One stubbed read per topic, because that is what the audit now performs: the poison
        // check's sample is taken from the records the duplicate scan already holds, so a fixture
        // that answered one thing to getSampleMessages and another to getRecentRecords would be
        // describing a topic whose records depend on who is asking.
        when(kafkaAdminService.getRecentRecords(eq("a.critical"), anyInt())).thenReturn(List.of(
                record("a.critical", 0, "k1", "{\"id\":\"1\"}"), record("a.critical", 1, "k2", "{\"id\":")));
        when(kafkaAdminService.getRecentRecords(eq("a.warning"), anyInt())).thenReturn(List.of(
                record("a.warning", 0, "k1", "{\"id\":\"1\"}"), record("a.warning", 1, "k1", "{\"id\":\"2\"}")));
        when(kafkaAdminService.getRecentRecords(eq("a.clean"), anyInt())).thenReturn(List.of(
                record("a.clean", 0, "k1", "{\"id\":\"1\"}"), record("a.clean", 1, "k2", "{\"id\":\"2\"}")));

        auditService.runAuditAsync("sev", new AuditOptions(false, true, true, false, false, false, null));

        AuditReport report = auditService.getAuditReport("sev");
        assertEquals(HealthStatus.CRITICAL, topic(report, "a.critical").healthStatus());
        assertEquals(HealthStatus.WARNING, topic(report, "a.warning").healthStatus());
        assertEquals(HealthStatus.HEALTHY, topic(report, "a.clean").healthStatus());
        assertEquals(1, report.criticalTopicsCount());
        assertEquals(1, report.warningTopicsCount());
        // 1 critical + half a warning over 3 topics → 1 - 1.5/3 = 0.5
        assertEquals(0.5, (double) report.globalStats().get("healthScore"), 1e-9);
    }

    @Test
    void aTopicTakesTheWorstSeverityAmongItsIssues() throws Exception {
        when(kafkaAdminService.listTopics()).thenReturn(List.of("mixed.topic"));
        when(kafkaAdminService.getTopicsSize(any())).thenReturn(Map.of("mixed.topic", 5L));
        // One read, carrying both properties: the same key twice (a duplicate, WARNING) and a
        // payload that does not parse (poison, CRITICAL).
        when(kafkaAdminService.getRecentRecords(eq("mixed.topic"), anyInt())).thenReturn(List.of(
                record("mixed.topic", 0, "k1", "{\"id\":\"1\"}"), record("mixed.topic", 1, "k1", "{\"id\":")));

        auditService.runAuditAsync("mixed", new AuditOptions(false, true, true, false, false, false, null));

        TopicAudit audit = auditService.getAuditReport("mixed").topicAudits().get(0);
        assertEquals(2, audit.issues().size(), "one warning (duplicates) and one critical (poison)");
        assertEquals(HealthStatus.CRITICAL, audit.healthStatus(), "the worst severity wins");
    }

    @Test
    void totalMessagesMatchesTheCountsShownPerTopic() throws Exception {
        when(kafkaAdminService.listTopics()).thenReturn(List.of("orders.created"));
        // Offsets say 100, but the exact Flink count says 42 — the KPI must follow the column.
        when(kafkaAdminService.getTopicsSize(any())).thenReturn(Map.of("orders.created", 100L));
        when(flinkSqlService.executeSql(any(QueryRequest.class)))
                .thenReturn(new QueryResult(List.of("EXPR$0"), List.of(Map.of("EXPR$0", 42L)), 10, null));

        auditService.runAuditAsync("totals", new AuditOptions(false, false, false, false, true, false, null));

        AuditReport report = auditService.getAuditReport("totals");
        assertEquals(42L, report.topicAudits().get(0).messageCount());
        assertEquals(42L, report.totalMessages(),
                "the KPI used to stay on the offset estimate while the column showed the exact count");
    }

    @Test
    void aSecondStartAttachesToTheRunInFlightInsteadOfQueueingOne() throws Exception {
        // listTopics blocks until released, so the first run is still in flight during the second start.
        CountDownLatch hold = new CountDownLatch(1);
        when(kafkaAdminService.listTopics()).thenAnswer(invocation -> {
            hold.await(5, TimeUnit.SECONDS);
            return Collections.emptyList();
        });

        AuditService.AuditStart first = auditService.startAudit(AuditOptions.all());
        AuditService.AuditStart second = auditService.startAudit(AuditOptions.all());

        assertTrue(first.started());
        assertFalse(second.started(), "a second full cluster scan must not be queued");
        assertEquals(first.auditId(), second.auditId(), "the caller is handed the run in flight");
        hold.countDown();
    }

    @Test
    void theRunSlotIsReleasedEvenWhenTheRunFails() throws Exception {
        when(kafkaAdminService.listTopics()).thenThrow(new RuntimeException("broker down"));

        AuditService.AuditStart first = auditService.startAudit(AuditOptions.all());
        // Wait for the failing run to finish so the slot is released.
        for (int i = 0; i < 100; i++) {
            AuditReport report = auditService.getAuditReport(first.auditId());
            if (report != null && report.status() == AuditStatus.FAILED) break;
            Thread.sleep(20);
        }

        assertTrue(auditService.startAudit(AuditOptions.all()).started(),
                "a failed run must not block every subsequent start");
    }

    /** Worker count of AuditService's per-topic pool — topics beyond this are queued. */
    private static final int TOPIC_POOL_SIZE = 4;

    @Test
    void cancellingARunStopsItAndKeepsWhatWasAlreadyAudited() throws Exception {
        // More topics than the pool has workers, so some are genuinely queued rather than started.
        // Every worker blocks in getSampleMessages until the test releases them, so at most
        // TOPIC_POOL_SIZE topics can be in flight when the cancellation lands; the queued ones
        // then hit the flag and are skipped. Sizing this below the pool made the test racy —
        // all topics started before the cancel and none was ever skipped.
        int topicCount = TOPIC_POOL_SIZE * 3;
        List<String> topicNames = java.util.stream.IntStream.range(0, topicCount)
                .mapToObj(i -> "a.topic" + i).toList();
        CountDownLatch aTopicStarted = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        when(kafkaAdminService.listTopics()).thenReturn(topicNames);
        when(kafkaAdminService.getTopicsSize(any())).thenReturn(
                topicNames.stream().collect(java.util.stream.Collectors.toMap(t -> t, t -> 1L)));
        when(kafkaAdminService.getSampleMessages(anyString(), anyInt())).thenAnswer(invocation -> {
            aTopicStarted.countDown();
            release.await(5, TimeUnit.SECONDS);
            return List.of("{\"id\":\"1\"}");
        });

        AuditService.AuditStart start = auditService.startAudit(
                new AuditOptions(false, true, false, false, false, false, null));
        assertTrue(aTopicStarted.await(5, TimeUnit.SECONDS), "the run should have reached a topic");

        assertEquals(AuditService.CancelResult.CANCELLING, auditService.cancelAudit(start.auditId()));
        release.countDown();

        AuditReport report = awaitTerminal(start.auditId());
        int audited = report.topicAudits().size();
        assertEquals(AuditStatus.CANCELLED, report.status());
        assertTrue(audited >= 1 && audited <= TOPIC_POOL_SIZE,
                "only the topics already in flight should have been audited, got " + audited);
        assertEquals(audited, report.totalTopics(),
                "totalTopics must count what was audited, not what was in scope");
        assertEquals(topicCount, report.globalStats().get("topicsInScope"));
        assertEquals(true, report.globalStats().get("cancelled"));
        @SuppressWarnings("unchecked")
        List<String> notes = (List<String>) report.globalStats().get("scopeNotes");
        assertTrue(notes.get(0).startsWith("Stopped after"),
                "a partial report must lead with the fact that it is partial: " + notes);
    }

    @Test
    void cancellingReleasesTheSlotSoANewRunCanStart() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        when(kafkaAdminService.listTopics()).thenAnswer(invocation -> {
            started.countDown();
            release.await(5, TimeUnit.SECONDS);
            return Collections.emptyList();
        });

        AuditService.AuditStart first = auditService.startAudit(AuditOptions.all());
        assertTrue(started.await(5, TimeUnit.SECONDS));
        auditService.cancelAudit(first.auditId());
        release.countDown();
        awaitTerminal(first.auditId());

        assertTrue(auditService.startAudit(AuditOptions.all()).started(),
                "a cancelled run must not hold the slot");
    }

    @Test
    void cancellingReportsWhyItDidNothing() throws Exception {
        assertEquals(AuditService.CancelResult.NOT_FOUND, auditService.cancelAudit("never-existed"));

        when(kafkaAdminService.listTopics()).thenReturn(Collections.emptyList());
        auditService.runAuditAsync("done", AuditOptions.all());
        assertEquals(AuditService.CancelResult.ALREADY_FINISHED, auditService.cancelAudit("done"),
                "cancelling a finished run is not a silent success");
    }

    @Test
    void duplicatesAreScannedFromTheEndOfTheTopicByDefault() throws Exception {
        when(kafkaAdminService.listTopics()).thenReturn(List.of("orders.created"));
        when(kafkaAdminService.getTopicsSize(any())).thenReturn(Map.of("orders.created", 3L));
        when(kafkaAdminService.getRecentRecords(eq("orders.created"), anyInt())).thenReturn(List.of(
                record("orders.created", 0, "k1", "{}"), record("orders.created", 1, "k1", "{}")));

        auditService.runAuditAsync("recent", new AuditOptions(false, false, true, false, false, false, null));

        // Every other check samples recent messages; scanning from the start judged the oldest
        // surviving records, which on a topic with retention answers a different question.
        verify(kafkaAdminService).getRecentRecords(eq("orders.created"), anyInt());
        verify(kafkaAdminService, never()).getEarliestRecords(anyString(), anyInt());
        @SuppressWarnings("unchecked")
        List<String> notes = (List<String>) auditService.getAuditReport("recent").globalStats().get("scopeNotes");
        assertTrue(notes.stream().anyMatch(n -> n.contains("from the end of each topic")),
                "the scope note must say which end was read: " + notes);
    }

    @Test
    void oneReadServesTheSampleAndTheDuplicateScanWhenBothWantRecentRecords() throws Exception {
        when(kafkaAdminService.listTopics()).thenReturn(List.of("orders.created"));
        when(kafkaAdminService.getTopicsSize(any())).thenReturn(Map.of("orders.created", 3L));
        when(kafkaAdminService.getRecentRecords(eq("orders.created"), anyInt())).thenReturn(List.of(
                record("orders.created", 0, "k1", "{\"id\":\"1\"}"),
                record("orders.created", 1, "k1", "{\"id\":")));

        // Poison detection and the duplicate scan both read the newest records of the same topic,
        // so the sample comes out of the scan's own records rather than a second consumer.
        auditService.runAuditAsync("shared", new AuditOptions(false, true, true, false, false, false, null));

        verify(kafkaAdminService, never()).getSampleMessages(eq("orders.created"), anyInt());
        verify(kafkaAdminService).getRecentRecords(eq("orders.created"), anyInt());
        TopicAudit audit = auditService.getAuditReport("shared").topicAudits().get(0);
        assertEquals(1, audit.poisonMessageCount(), "the shared sample must still reach the poison check");
        assertEquals(1L, audit.duplicateCount());
    }

    @Test
    void theSampleIsReadSeparatelyWhenTheDuplicateScanReadsTheOtherEnd() throws Exception {
        explorerConfig.setAuditDuplicateScanFrom("EARLIEST");
        when(kafkaAdminService.listTopics()).thenReturn(List.of("orders.created"));
        when(kafkaAdminService.getTopicsSize(any())).thenReturn(Map.of("orders.created", 3L));
        when(kafkaAdminService.getEarliestRecords(eq("orders.created"), anyInt())).thenReturn(List.of(
                record("orders.created", 0, "k1", "{\"id\":\"1\"}"),
                record("orders.created", 1, "k1", "{\"id\":\"2\"}")));
        when(kafkaAdminService.getSampleMessages(eq("orders.created"), anyInt()))
                .thenReturn(List.of("{\"id\":\"1\"}", "{\"id\":"));

        auditService.runAuditAsync("split", new AuditOptions(false, true, true, false, false, false, null));

        // The oldest surviving records answer a different question from the one every other check
        // here asks, so sharing across that end would change what the poison check looks at.
        verify(kafkaAdminService).getSampleMessages(eq("orders.created"), anyInt());
        TopicAudit audit = auditService.getAuditReport("split").topicAudits().get(0);
        assertEquals(1, audit.poisonMessageCount(), "the poison check must read the recent sample, not the scan");
        assertEquals(1L, audit.duplicateCount());
    }

    @Test
    void duplicateScanCanBePointedAtTheStartOfTheTopic() throws Exception {
        explorerConfig.setAuditDuplicateScanFrom("EARLIEST");
        when(kafkaAdminService.listTopics()).thenReturn(List.of("orders.created"));
        when(kafkaAdminService.getTopicsSize(any())).thenReturn(Map.of("orders.created", 3L));
        when(kafkaAdminService.getEarliestRecords(eq("orders.created"), anyInt())).thenReturn(List.of(
                record("orders.created", 0, "k1", "{}"), record("orders.created", 1, "k1", "{}")));

        auditService.runAuditAsync("earliest", new AuditOptions(false, false, true, false, false, false, null));

        verify(kafkaAdminService).getEarliestRecords(eq("orders.created"), anyInt());
        TopicAudit audit = auditService.getAuditReport("earliest").topicAudits().get(0);
        assertTrue(audit.issues().stream().anyMatch(i -> i.message().contains("first 2 message")),
                "the wording must follow the end actually read: " + audit.issues());
    }

    @Test
    void aRunThatOutlivesItsTimeBudgetStopsAndSaysSo() throws Exception {
        // Budget of 1 ms: the deadline is already past by the time the first topic is picked up,
        // so every topic is skipped and the run reports why rather than grinding on.
        explorerConfig.setAuditMaxDurationMs(1);
        when(kafkaAdminService.listTopics()).thenReturn(List.of("a.one", "a.two", "a.three"));
        when(kafkaAdminService.getTopicsSize(any()))
                .thenReturn(Map.of("a.one", 1L, "a.two", 1L, "a.three", 1L));

        AuditService.AuditStart start = auditService.startAudit(AuditOptions.all());
        AuditReport report = awaitTerminal(start.auditId());

        assertEquals(AuditStatus.CANCELLED, report.status());
        assertEquals("TIME_BUDGET", report.globalStats().get("stopReason"));
        assertEquals(3, report.globalStats().get("topicsInScope"));
        @SuppressWarnings("unchecked")
        List<String> notes = (List<String>) report.globalStats().get("scopeNotes");
        assertTrue(notes.get(0).contains("time budget was exhausted"),
                "the report must distinguish a budget stop from a cancellation: " + notes);
    }

    @Test
    void aCancellationIsReportedAsRequestedNotAsATimeout() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        when(kafkaAdminService.listTopics()).thenAnswer(invocation -> {
            started.countDown();
            release.await(5, TimeUnit.SECONDS);
            return List.of("a.one");
        });
        when(kafkaAdminService.getTopicsSize(any())).thenReturn(Map.of("a.one", 1L));

        AuditService.AuditStart start = auditService.startAudit(AuditOptions.all());
        assertTrue(started.await(5, TimeUnit.SECONDS));
        auditService.cancelAudit(start.auditId());
        release.countDown();

        AuditReport report = awaitTerminal(start.auditId());
        assertEquals("REQUESTED", report.globalStats().get("stopReason"));
    }

    @Test
    void aRunWithNoBudgetIsNotStoppedEarly() throws Exception {
        explorerConfig.setAuditMaxDurationMs(0); // 0 disables the budget
        when(kafkaAdminService.listTopics()).thenReturn(List.of("a.one"));
        when(kafkaAdminService.getTopicsSize(any())).thenReturn(Map.of("a.one", 1L));

        auditService.runAuditAsync("nobudget", new AuditOptions(false, false, false, false, false, false, null));

        AuditReport report = auditService.getAuditReport("nobudget");
        assertEquals(AuditStatus.COMPLETED, report.status());
        assertNull(report.globalStats().get("stopReason"));
    }

    // ---- consumer lag ---------------------------------------------------------------------

    private static TopicConsumers consumers(ConsumerGroupLag... groups) {
        return new TopicConsumers("orders.created", List.of(groups), groups.length, groups.length,
                groups.length, false, true, List.of());
    }

    private static ConsumerGroupLag lagging(String groupId, long totalLag, int assignedMembers,
                                            int partitionsWithoutCommit, PartitionLag... partitions) {
        return new ConsumerGroupLag(groupId, "CLASSIC", "STABLE", assignedMembers, assignedMembers,
                true, totalLag, partitionsWithoutCommit, List.of(partitions), null);
    }

    /*
     * L'intérêt de la photo : une lecture des groupes du cluster pour tout le run, pas une par
     * topic. C'est ce qui faisait 300 `ListGroups` et 60 000 `OffsetFetch` sur un cluster de 300
     * topics, pour une réponse qui ne varie pas d'un topic à l'autre.
     */
    @Test
    void readsTheClustersGroupsOnceForTheWholeRunRatherThanOncePerTopic() throws Exception {
        explorerConfig.setAuditGroupSnapshotTtlMs(0);
        when(kafkaAdminService.listTopics()).thenReturn(List.of("a.one", "b.two", "c.three"));
        when(kafkaAdminService.getTopicsSize(any())).thenReturn(Map.of());

        auditService.runAuditAsync("once", new AuditOptions(false, false, false, false, false, true, null));

        verify(kafkaAdminService, times(1)).groupSnapshot(anyInt(), any());
        verify(kafkaAdminService, times(3))
                .getTopicConsumers(anyString(), any(KafkaAdminService.GroupSnapshot.class));
    }

    /*
     * Mais pas indéfiniment : sur un run d'une demi-heure, les positions commitées de la première
     * minute comparées à des end offsets lus trente minutes plus tard donnent un retard surestimé
     * d'une demi-heure de trafic — sûr dans sa direction, inexploitable dans son ordre de grandeur.
     */
    @Test
    void reReadsTheGroupsOnceTheSnapshotHasGoneStale() throws Exception {
        explorerConfig.setAuditGroupSnapshotTtlMs(1);
        when(kafkaAdminService.listTopics()).thenReturn(List.of("a.one", "b.two", "c.three"));
        when(kafkaAdminService.getTopicsSize(any())).thenReturn(Map.of());
        /*
         * Le délai est dans la *prise de photo*, pas dans le travail qui suit — et c'est ce qui
         * rend le scénario déterministe. `current()` est synchronized : le deuxième worker attend
         * donc ces 20 ms sur le moniteur et voit forcément un TTL de 1 ms expiré. Avec le délai
         * placé après (dans getTopicConsumers), les trois workers appelaient `current()` au même
         * instant, tous dans la même milliseconde sur une machine assez rapide, et le test tenait à
         * l'ordonnancement — il est tombé une fois en CI pour cette raison.
         */
        when(kafkaAdminService.groupSnapshot(anyInt(), any())).thenAnswer(inv -> {
            Thread.sleep(20);
            return emptySnapshot();
        });

        auditService.runAuditAsync("stale", new AuditOptions(false, false, false, false, false, true, null));

        verify(kafkaAdminService, atLeast(2)).groupSnapshot(anyInt(), any());
    }

    private static KafkaAdminService.GroupSnapshot emptySnapshot() {
        return new KafkaAdminService.GroupSnapshot(0, 0, false, List.of(), Map.of(), Map.of(),
                Map.of(), Map.of(), Map.of(), List.of(), null);
    }

    private static PartitionLag lagOf(int partition, Long committed, long end, Long lag) {
        return new PartitionLag(partition, committed, end, lag, null, null, null);
    }

    @Test
    void reportsAGroupThatNothingIsDrainingAsCritical() throws Exception {
        when(kafkaAdminService.listTopics()).thenReturn(List.of("orders.created"));
        when(kafkaAdminService.getTopicsSize(any())).thenReturn(Map.of("orders.created", 500L));
        when(kafkaAdminService.getTopicConsumers(eq("orders.created"), any(KafkaAdminService.GroupSnapshot.class))).thenReturn(
                consumers(lagging("enricher", 4200L, 0, 0, lagOf(0, 100L, 4300L, 4200L))));

        auditService.runAuditAsync("lag", new AuditOptions(false, false, false, false, false, true, null));

        TopicAudit audit = auditService.getAuditReport("lag").topicAudits().get(0);
        assertEquals(HealthStatus.CRITICAL, audit.healthStatus(),
                "records waiting with nothing assigned to read them is not a warning");
        assertTrue(audit.issues().stream().anyMatch(i -> i.message().contains("enricher")
                        && i.message().contains("no member assigned")),
                "the issue must name the group and why it is stuck: " + audit.issues());
    }

    /*
     * « 4 200 messages de retard » ne se lit pas : c'est quatre secondes de trafic sur un topic et
     * quatre jours sur un autre. Le seul cas où l'audit paie une mesure plus coûteuse — une lecture
     * de record par partition en retard — est celui-là, et le constat porte alors les deux nombres.
     */
    @Test
    void datesAStalledBacklogWithTheAgeOfTheOldestWaitingMessage() throws Exception {
        when(kafkaAdminService.listTopics()).thenReturn(List.of("orders.created"));
        when(kafkaAdminService.getTopicsSize(any())).thenReturn(Map.of("orders.created", 500L));
        when(kafkaAdminService.getTopicConsumers(eq("orders.created"), any(KafkaAdminService.GroupSnapshot.class))).thenReturn(
                consumers(lagging("enricher", 4200L, 0, 0, lagOf(0, 100L, 4300L, 4200L))));
        when(kafkaAdminService.getConsumerTimeLag("orders.created", "enricher")).thenReturn(
                new TopicTimeLag("orders.created", "enricher", List.of(), 7_200_000L, 7_200_000L,
                        1, 0, 0, 0, true, null, List.of()));

        auditService.runAuditAsync("lag", new AuditOptions(false, false, false, false, false, true, null));

        AuditReport report = auditService.getAuditReport("lag");
        TopicAudit audit = report.topicAudits().get(0);
        assertTrue(audit.issues().stream().anyMatch(i -> i.message().contains("4200 message(s) behind")
                        && i.message().contains("2 h old")),
                "the count is the finding, the age is what makes it actionable: " + audit.issues());
        @SuppressWarnings("unchecked")
        List<String> notes = (List<String>) report.globalStats().get("scopeNotes");
        assertTrue(notes.stream().anyMatch(n -> n.contains("measured in time")),
                "a costlier measurement states its own bounds: " + notes);
    }

    /*
     * Une mesure impossible ne devient jamais un nombre : le constat garde son compte seul. Un
     * retard dont on n'a pas pu lire l'âge n'est pas un retard de zéro seconde.
     */
    @Test
    void leavesTheFindingUnchangedWhenTheAgeCannotBeMeasured() throws Exception {
        when(kafkaAdminService.listTopics()).thenReturn(List.of("orders.created"));
        when(kafkaAdminService.getTopicsSize(any())).thenReturn(Map.of("orders.created", 500L));
        when(kafkaAdminService.getTopicConsumers(eq("orders.created"), any(KafkaAdminService.GroupSnapshot.class))).thenReturn(
                consumers(lagging("enricher", 4200L, 0, 0, lagOf(0, 100L, 4300L, 4200L))));
        when(kafkaAdminService.getConsumerTimeLag("orders.created", "enricher")).thenReturn(
                TopicTimeLag.unavailable("orders.created", "enricher", "records could not be read"));

        auditService.runAuditAsync("lag", new AuditOptions(false, false, false, false, false, true, null));

        TopicAudit audit = auditService.getAuditReport("lag").topicAudits().get(0);
        assertTrue(audit.issues().stream().anyMatch(i -> i.message().contains("4200 message(s) behind")),
                audit.issues().toString());
        assertTrue(audit.issues().stream().noneMatch(i -> i.message().contains("old")),
                "no age could be read, so the finding claims none: " + audit.issues());
    }

    /*
     * Le même retard sans membre assigné, mais sur un groupe que le broker n'a pas su décrire :
     * les zéro membres viennent de l'absence de réponse, pas de l'absence de membre. En faire un
     * constat critique, c'était trancher sur la foi d'un appel muet — et `describeConsumerGroups`
     * est muet, entre autres, pour un groupe Kafka Streams parfaitement sain.
     */
    @Test
    void doesNotReportAGroupAsStuckWhenItsMembershipWasNeverRead() throws Exception {
        when(kafkaAdminService.listTopics()).thenReturn(List.of("orders.created"));
        when(kafkaAdminService.getTopicsSize(any())).thenReturn(Map.of("orders.created", 500L));
        ConsumerGroupLag undescribed = new ConsumerGroupLag("streams-app", "CLASSIC", "UNKNOWN",
                0, 0, false, 4200L, 0, List.of(lagOf(0, 100L, 4300L, 4200L)), null);
        when(kafkaAdminService.getTopicConsumers(eq("orders.created"), any(KafkaAdminService.GroupSnapshot.class)))
                .thenReturn(consumers(undescribed));

        auditService.runAuditAsync("lag", new AuditOptions(false, false, false, false, false, true, null));

        TopicAudit audit = auditService.getAuditReport("lag").topicAudits().get(0);
        assertEquals(HealthStatus.HEALTHY, audit.healthStatus());
        assertTrue(audit.issues().isEmpty(),
                "unknown membership cannot decide the stalled question: " + audit.issues());
    }

    /*
     * Une lecture qui échoue rendait une liste vide, donc aucun constat — indiscernable de « tous
     * les groupes de ce topic vont bien ». Une vérification qui n'a pas pu tourner le dit, comme
     * toutes les autres ici.
     */
    @Test
    void saysSoWhenTheConsumerGroupsCouldNotBeReadAtAll() throws Exception {
        when(kafkaAdminService.listTopics()).thenReturn(List.of("orders.created"));
        when(kafkaAdminService.getTopicsSize(any())).thenReturn(Map.of("orders.created", 500L));
        when(kafkaAdminService.getTopicConsumers(eq("orders.created"), any(KafkaAdminService.GroupSnapshot.class)))
                .thenReturn(TopicConsumers.unavailable("orders.created", "broker unreachable"));

        auditService.runAuditAsync("lag", new AuditOptions(false, false, false, false, false, true, null));

        TopicAudit audit = auditService.getAuditReport("lag").topicAudits().get(0);
        assertEquals(HealthStatus.WARNING, audit.healthStatus());
        assertTrue(audit.issues().stream().anyMatch(i -> i.message().contains("broker unreachable")),
                "a check that could not run must say so: " + audit.issues());
    }

    @Test
    void doesNotTurnAnOrdinaryBacklogIntoAFinding() throws Exception {
        when(kafkaAdminService.listTopics()).thenReturn(List.of("orders.created"));
        when(kafkaAdminService.getTopicsSize(any())).thenReturn(Map.of("orders.created", 500L));
        // Behind by a lot, but draining: any threshold one picked here would be arbitrary.
        when(kafkaAdminService.getTopicConsumers(eq("orders.created"), any(KafkaAdminService.GroupSnapshot.class))).thenReturn(
                consumers(lagging("orders-api", 900_000L, 3, 0, lagOf(0, 100L, 900_100L, 900_000L))));

        auditService.runAuditAsync("lag", new AuditOptions(false, false, false, false, false, true, null));

        TopicAudit audit = auditService.getAuditReport("lag").topicAudits().get(0);
        assertEquals(HealthStatus.HEALTHY, audit.healthStatus());
        assertTrue(audit.issues().isEmpty(), "a group that is reading is not a defect: " + audit.issues());
    }

    @Test
    void warnsWhenAGroupIgnoresPartOfTheTopic() throws Exception {
        when(kafkaAdminService.listTopics()).thenReturn(List.of("orders.created"));
        when(kafkaAdminService.getTopicsSize(any())).thenReturn(Map.of("orders.created", 500L));
        when(kafkaAdminService.getTopicConsumers(eq("orders.created"), any(KafkaAdminService.GroupSnapshot.class))).thenReturn(
                consumers(lagging("partial", 10L, 1, 2,
                        lagOf(0, 90L, 100L, 10L), lagOf(1, null, 100L, null), lagOf(2, null, 100L, null))));

        auditService.runAuditAsync("lag", new AuditOptions(false, false, false, false, false, true, null));

        TopicAudit audit = auditService.getAuditReport("lag").topicAudits().get(0);
        assertEquals(HealthStatus.WARNING, audit.healthStatus());
        assertTrue(audit.issues().stream().anyMatch(i -> i.message().contains("never committed on 2")),
                "the backlog it ignores is what makes this worth saying: " + audit.issues());
    }

    @Test
    void warnsWhenACommittedOffsetIsPastTheEndOfTheLog() throws Exception {
        when(kafkaAdminService.listTopics()).thenReturn(List.of("orders.created"));
        when(kafkaAdminService.getTopicsSize(any())).thenReturn(Map.of("orders.created", 500L));
        when(kafkaAdminService.getTopicConsumers(eq("orders.created"), any(KafkaAdminService.GroupSnapshot.class))).thenReturn(
                consumers(lagging("reset", -400L, 1, 0, lagOf(0, 500L, 100L, -400L))));

        auditService.runAuditAsync("lag", new AuditOptions(false, false, false, false, false, true, null));

        TopicAudit audit = auditService.getAuditReport("lag").topicAudits().get(0);
        assertEquals(HealthStatus.WARNING, audit.healthStatus());
        assertTrue(audit.issues().stream().anyMatch(i -> i.message().contains("past the end of the log")));
    }

    @Test
    void reportsThatTheGroupsCouldNotBeReadRatherThanStayingSilent() throws Exception {
        when(kafkaAdminService.listTopics()).thenReturn(List.of("orders.created"));
        when(kafkaAdminService.getTopicsSize(any())).thenReturn(Map.of("orders.created", 500L));
        when(kafkaAdminService.getTopicConsumers(eq("orders.created"), any(KafkaAdminService.GroupSnapshot.class)))
                .thenThrow(new IllegalStateException("coordinator unavailable"));

        auditService.runAuditAsync("lag", new AuditOptions(false, false, false, false, false, true, null));

        TopicAudit audit = auditService.getAuditReport("lag").topicAudits().get(0);
        // Silence here would be indistinguishable from "this topic is fine".
        assertEquals(HealthStatus.WARNING, audit.healthStatus());
        assertTrue(audit.issues().stream().anyMatch(i -> i.message().contains("coordinator unavailable")));
    }

    @Test
    void doesNotReadTheGroupsWhenTheCheckIsOff() throws Exception {
        when(kafkaAdminService.listTopics()).thenReturn(List.of("orders.created"));
        when(kafkaAdminService.getTopicsSize(any())).thenReturn(Map.of("orders.created", 500L));

        auditService.runAuditAsync("nolag", new AuditOptions(false, false, false, false, false, false, null));

        awaitTerminal("nolag");
        verify(kafkaAdminService, never()).getTopicConsumers(anyString(), anyInt());
    }

    @Test
    void statesTheBoundsOfTheConsumerLagScan() throws Exception {
        when(kafkaAdminService.listTopics()).thenReturn(List.of("orders.created"));
        when(kafkaAdminService.getTopicsSize(any())).thenReturn(Map.of("orders.created", 500L));
        when(kafkaAdminService.getTopicConsumers(eq("orders.created"), any(KafkaAdminService.GroupSnapshot.class))).thenReturn(consumers());

        auditService.runAuditAsync("lag", new AuditOptions(false, false, false, false, false, true, null));

        AuditReport report = awaitTerminal("lag");
        @SuppressWarnings("unchecked")
        List<String> notes = (List<String>) report.globalStats().get("scopeNotes");
        assertTrue(notes.stream().anyMatch(n -> n.contains("Consumer lag reads at most")),
                "the reader has to know what the check did and did not cover: " + notes);
    }

    /** Polls until the run leaves RUNNING, so the assertions do not race the audit thread. */
    private AuditReport awaitTerminal(String auditId) throws InterruptedException {
        for (int i = 0; i < 200; i++) {
            AuditReport report = auditService.getAuditReport(auditId);
            if (report != null && report.status() != AuditStatus.RUNNING) return report;
            Thread.sleep(20);
        }
        throw new AssertionError("audit " + auditId + " never reached a terminal state");
    }

    private static TopicAudit topic(AuditReport report, String name) {
        return report.topicAudits().stream()
                .filter(t -> name.equals(t.name())).findFirst().orElseThrow();
    }

    private static ConsumerRecord<String, String> record(String topic, long offset, String key, String value) {
        return new ConsumerRecord<>(topic, 0, offset, key, value);
    }
}
