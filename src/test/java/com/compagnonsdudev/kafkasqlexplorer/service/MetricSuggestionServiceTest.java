// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors
package com.compagnonsdudev.kafkasqlexplorer.service;

import com.compagnonsdudev.kafkasqlexplorer.config.ExplorerConfig;
import com.compagnonsdudev.kafkasqlexplorer.domain.AuditHistory;
import com.compagnonsdudev.kafkasqlexplorer.domain.AuditReport;
import com.compagnonsdudev.kafkasqlexplorer.domain.AuditStatus;
import com.compagnonsdudev.kafkasqlexplorer.domain.ConsumerGroupLag;
import com.compagnonsdudev.kafkasqlexplorer.domain.FlowAudit;
import com.compagnonsdudev.kafkasqlexplorer.domain.FieldMapping;
import com.compagnonsdudev.kafkasqlexplorer.domain.FlowChainEvidence;
import com.compagnonsdudev.kafkasqlexplorer.domain.HealthStatus;
import com.compagnonsdudev.kafkasqlexplorer.domain.MessageFormat;
import com.compagnonsdudev.kafkasqlexplorer.domain.MetricConfig;
import com.compagnonsdudev.kafkasqlexplorer.domain.MetricSuggestion;
import com.compagnonsdudev.kafkasqlexplorer.domain.MetricSuggestionRequest;
import com.compagnonsdudev.kafkasqlexplorer.domain.MetricSuggestionSource;
import com.compagnonsdudev.kafkasqlexplorer.domain.MetricSuggestions;
import com.compagnonsdudev.kafkasqlexplorer.domain.MetricTemplateType;
import com.compagnonsdudev.kafkasqlexplorer.domain.PartitionLag;
import com.compagnonsdudev.kafkasqlexplorer.domain.TopicAudit;
import com.compagnonsdudev.kafkasqlexplorer.domain.TopicConsumers;
import com.compagnonsdudev.kafkasqlexplorer.domain.TopicIssue;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class MetricSuggestionServiceTest {

    private AuditService auditService;
    private AuditHistoryService auditHistoryService;
    private MetricService metricService;
    private FlinkSqlService flinkSqlService;
    private KafkaAdminService kafkaAdminService;
    private LineageService lineageService;
    private FieldMappingStore fieldMappingStore;
    private MetricSuggestionService service;

    @BeforeEach
    void setUp() {
        auditService = mock(AuditService.class);
        auditHistoryService = mock(AuditHistoryService.class);
        metricService = mock(MetricService.class);
        flinkSqlService = mock(FlinkSqlService.class);
        kafkaAdminService = mock(KafkaAdminService.class);
        lineageService = mock(LineageService.class);
        fieldMappingStore = new FieldMappingStore();

        when(auditHistoryService.listHistory()).thenReturn(AuditHistory.empty(List.of()));
        when(metricService.getAllMetrics()).thenReturn(List.of());
        when(flinkSqlService.getTableSchema(anyString())).thenReturn(Map.of());

        when(kafkaAdminService.getTopicConsumers(anyString(), anyInt()))
            .thenReturn(TopicConsumers.unavailable("unset", "No stub for this topic."));

        // No running job by default: the lineage family is evidence-gated like the others.
        when(flinkSqlService.getActiveJobsDetails()).thenReturn(Map.of());

        service = new MetricSuggestionService(auditService, auditHistoryService, metricService,
            flinkSqlService, kafkaAdminService, lineageService, fieldMappingStore, new ExplorerConfig());
    }

    // ── Gating ───────────────────────────────────────────────────────────────

    @Test
    void withNoAuditAndNoTraceSuggestsNothingAndSaysWhatWouldUnlockIt() {
        MetricSuggestions result = service.suggest(new MetricSuggestionRequest(null));

        assertTrue(result.suggestions().isEmpty());
        assertFalse(result.auditAvailable());
        assertEquals(0, result.flowChainsSubmitted());
        // "Nothing suggests itself" and "nothing has been measured" must not read the same.
        assertTrue(result.notes().stream().anyMatch(note -> note.contains("audit")),
            "the empty answer must say an audit would unlock proposals: " + result.notes());
        assertTrue(result.notes().stream().anyMatch(note -> note.contains("Stream Flow")),
            "the empty answer must say a trace would unlock proposals: " + result.notes());
    }

    @Test
    void aRunningAuditIsNotUsedAsEvidence() {
        when(auditService.getLastAuditReport()).thenReturn(new AuditReport(
            "run-1", AuditStatus.RUNNING, 0, 0, 0, 0, List.of(), List.of(), Map.of()));

        MetricSuggestions result = service.suggest(null);

        assertFalse(result.auditAvailable());
        assertTrue(result.notes().stream().anyMatch(note -> note.contains("An audit is running")),
            "a report in flight covers only the topics reached so far — say so: " + result.notes());
    }

    // ── Audit-derived ────────────────────────────────────────────────────────

    @Test
    void aTimedFlowHopBecomesATransitLatencyKpiWithThresholdsDerivedFromTheMeasurement() {
        when(auditService.getLastAuditReport()).thenReturn(reportWithFlow());

        MetricSuggestions result = service.suggest(null);

        MetricSuggestion latency = find(result, "audit:hop-latency:demo.orders.in>demo.orders.out");
        assertEquals(MetricSuggestionSource.AUDIT, latency.source());
        assertEquals(MetricTemplateType.TOPIC_TRANSIT_LATENCY.name(), latency.metric().templateType());
        assertEquals("GAUGE", latency.metric().type());
        assertEquals(800.0, latency.metric().warningThreshold());   // 2× the measured 400 ms
        assertEquals(1600.0, latency.metric().criticalThreshold()); // 4×
        assertNotNull(latency.thresholdBasis());
        assertTrue(latency.thresholdBasis().contains("400 ms"),
            "the basis must name the measurement it multiplies: " + latency.thresholdBasis());

        Map<String, Object> params = latency.metric().templateParams();
        assertEquals("demo.orders.in", params.get("sourceTopic"));
        assertEquals("demo.orders.out", params.get("targetTopic"));
        // The template requires both columns; a proposal that cannot run is worse than none.
        assertTrue(String.valueOf(params.get("sourceSql")).contains("AS match_key"));
        assertTrue(String.valueOf(params.get("sourceSql")).contains("event_time"));
        assertTrue(String.valueOf(params.get("targetSql")).contains("demo_orders_out"),
            "the SQL must reference the sanitized Flink table name: " + params.get("targetSql"));
    }

    @Test
    void aHopTheAuditCouldNotTimeIsNotProposedWithAnInventedThreshold() {
        FlowAudit flow = new FlowAudit("orders", List.of(
            new FlowAudit.StepInfo("demo.orders.in", 100, 100.0, null),
            new FlowAudit.StepInfo("demo.orders.out", 100, 100.0, null)), 1.0);
        when(auditService.getLastAuditReport()).thenReturn(report(List.of(), List.of(flow)));

        MetricSuggestions result = service.suggest(null);

        assertTrue(result.suggestions().stream().noneMatch(s -> s.id().startsWith("audit:hop-latency:")),
            "no latency was measured, so no latency KPI: " + result.suggestions());
    }

    @Test
    void aThroughputDropBecomesAGapKpiAndTheEvidenceCarriesBothCounts() {
        when(auditService.getLastAuditReport()).thenReturn(reportWithFlow());

        MetricSuggestion gap = find(service.suggest(null), "audit:flow-gap:demo.orders.in>demo.orders.out");

        assertEquals(MetricTemplateType.TOPIC_COUNT_DELTA.name(), gap.metric().templateType());
        assertEquals("PERCENT_GAP", gap.metric().templateParams().get("operation"));
        assertEquals(20.0, gap.metric().warningThreshold());   // 2× the measured 10 % gap
        assertEquals(40.0, gap.metric().criticalThreshold());  // 4×
        assertTrue(gap.evidence().get(0).contains("1000"), gap.evidence().toString());
        assertTrue(gap.evidence().get(0).contains("900"), gap.evidence().toString());
    }

    @Test
    void duplicatesFoundByTheAuditBecomeAKpiThatStatesTheEngineItNeeds() {
        TopicAudit topic = new TopicAudit("demo.orders.in", 1000, MessageFormat.JSON, 0, 7,
            HealthStatus.WARNING, List.of(TopicIssue.warning("7 duplicate keys")));
        when(auditService.getLastAuditReport()).thenReturn(report(List.of(topic), List.of()));

        MetricSuggestion duplicates = find(service.suggest(null), "audit:duplicates:demo.orders.in");

        assertEquals(MetricTemplateType.RAW_SQL.name(), duplicates.metric().templateType());
        assertTrue(duplicates.metric().sql().contains("COUNT(DISTINCT"), duplicates.metric().sql());
        assertEquals(7.0, duplicates.metric().warningThreshold());
        assertEquals(14.0, duplicates.metric().criticalThreshold());
        assertTrue(duplicates.caveats().stream().anyMatch(c -> c.contains("Flink planner")),
            "the aggregate does not run on the direct engine — say so: " + duplicates.caveats());
    }

    @Test
    void theKeyColumnComesFromTheRegisteredSchemaWhenThereIsOneAndIsFlaggedWhenThereIsNot() {
        Map<String, String> schema = new LinkedHashMap<>();
        schema.put("payload", "STRING");
        schema.put("order_id", "STRING");
        when(flinkSqlService.getTableSchema("demo_orders_in")).thenReturn(schema);
        when(auditService.getLastAuditReport()).thenReturn(reportWithFlow());

        MetricSuggestion latency = find(service.suggest(null), "audit:hop-latency:demo.orders.in>demo.orders.out");

        assertTrue(String.valueOf(latency.metric().templateParams().get("sourceSql")).contains("`order_id`"),
            "a column of the registered table beats the convention: " + latency.metric().templateParams());
        assertTrue(latency.caveats().stream().anyMatch(c -> c.contains("assumed on demo.orders.out")),
            "the unregistered side must say its key column is assumed: " + latency.caveats());
    }

    @Test
    void aTopicTheAuditFlaggedForLagGetsADelayInTimeKpiNamingTheGroupItMeasures() {
        when(auditService.getLastAuditReport()).thenReturn(report(List.of(lagging()), List.of()));
        when(kafkaAdminService.getTopicConsumers(eq("demo.payments"), anyInt()))
            .thenReturn(consumers(group("pay-api", 4000L, 0), group("audit-api", 12L, 0)));

        MetricSuggestion timeLag = find(service.suggest(null), "audit:time-lag:demo.payments>pay-api");

        assertEquals(MetricTemplateType.CONSUMER_TIME_LAG.name(), timeLag.metric().templateType());
        assertEquals("demo.payments", timeLag.metric().templateParams().get("topic"));
        // The worst readable backlog, and pinned: "the worst group" would move between refreshes.
        assertEquals("pay-api", timeLag.metric().templateParams().get("group"));
        assertNull(timeLag.thresholdBasis(),
            "nothing has ever measured this topic in time — a threshold would be the round number "
                + "this panel exists not to print");
        assertNull(timeLag.metric().warningThreshold());
        assertTrue(timeLag.evidence().stream().anyMatch(e -> e.contains("4000 record")),
            "the count that motivates the KPI is the evidence: " + timeLag.evidence());
        assertTrue(timeLag.caveats().stream().anyMatch(c -> c.contains("No threshold is proposed")),
            timeLag.caveats().toString());
    }

    @Test
    void aTopicWhoseGroupsAreAllCaughtUpGetsNoDelayKpi() {
        when(auditService.getLastAuditReport()).thenReturn(report(List.of(lagging()), List.of()));
        when(kafkaAdminService.getTopicConsumers(eq("demo.payments"), anyInt()))
            .thenReturn(consumers(group("pay-api", 0L, 2)));

        assertTrue(service.suggest(null).suggestions().stream()
            .noneMatch(s -> s.id().startsWith("audit:time-lag:")));
    }

    @Test
    void whenTheGroupsCannotBeReadTheKpiIsNotGuessedFromTheAuditsWording() {
        when(auditService.getLastAuditReport()).thenReturn(report(List.of(lagging()), List.of()));
        when(kafkaAdminService.getTopicConsumers(eq("demo.payments"), anyInt()))
            .thenReturn(TopicConsumers.unavailable("demo.payments", "Coordinator not available."));

        MetricSuggestions result = service.suggest(null);

        assertTrue(result.suggestions().stream().noneMatch(s -> s.id().startsWith("audit:time-lag:")));
        assertTrue(result.notes().stream().anyMatch(n -> n.contains("could not be read just now")),
            "silence would look like 'this topic needs no KPI': " + result.notes());
    }

    @Test
    void consumerLagAndPoisonFindingsBecomeNotesRatherThanInventedMetrics() {
        TopicAudit lagging = new TopicAudit("demo.payments", 10, MessageFormat.JSON, 0, 0,
            HealthStatus.CRITICAL, List.of(TopicIssue.critical("Consumer group pay-api has a lag of 4000 records")));
        TopicAudit poisoned = new TopicAudit("demo.shipments", 10, MessageFormat.JSON, 3, 0,
            HealthStatus.CRITICAL, List.of(TopicIssue.critical("3 unparseable payloads")));
        when(auditService.getLastAuditReport()).thenReturn(report(List.of(lagging, poisoned), List.of()));

        MetricSuggestions result = service.suggest(null);

        assertTrue(result.notes().stream().anyMatch(n -> n.contains("lag-metrics-topics")),
            "lag is exported from committed offsets, not derived as SQL: " + result.notes());
        assertTrue(result.notes().stream().anyMatch(n -> n.contains("unparseable")),
            "a parse failure is not something SQL counts — say it: " + result.notes());
        assertTrue(result.suggestions().stream().noneMatch(s -> s.id().contains("lag")),
            "no lag KPI is invented: " + result.suggestions());
    }

    // ── Stream-Flow-derived ──────────────────────────────────────────────────

    @Test
    void aTracedChainProducesPerHopLatencyAndAnEndToEndCompletenessKpi() {
        MetricSuggestions result = service.suggest(new MetricSuggestionRequest(List.of(chain())));

        assertEquals(1, result.flowChainsSubmitted());
        MetricSuggestion hop = find(result, "flow:hop-latency:orders.received>orders.enriched");
        assertEquals(MetricSuggestionSource.STREAM_FLOW, hop.source());
        assertEquals(1624.0, hop.metric().warningThreshold());   // 2× the traced 812 ms
        assertTrue(hop.evidence().get(0).contains("ORD-42"), hop.evidence().toString());

        MetricSuggestion completeness = find(result, "flow:completeness:orders.received>payments.settled");
        assertNull(completeness.thresholdBasis(),
            "a trace measures one key's path, never a ratio of volumes — no basis to claim");
        assertTrue(completeness.caveats().stream().anyMatch(c -> c.contains("placeholders")),
            "the placeholder thresholds must say they are placeholders: " + completeness.caveats());
    }

    @Test
    void aSingleSightingIsNotAChainAndProducesNothing() {
        FlowChainEvidence single = new FlowChainEvidence("ORD-42", null, 1L,
            List.of(new FlowChainEvidence.FlowChainHop("orders.received", 1000L, null, 1)));

        MetricSuggestions result = service.suggest(new MetricSuggestionRequest(List.of(single)));

        assertTrue(result.suggestions().isEmpty(), result.suggestions().toString());
    }

    @Test
    void aBackwardsHopIsReportedAsClockSkewAndCarriesNoThreshold() {
        FlowChainEvidence skewed = new FlowChainEvidence("ORD-42", null, 1L, List.of(
            new FlowChainEvidence.FlowChainHop("a.topic", 5000L, null, 1),
            new FlowChainEvidence.FlowChainHop("b.topic", 4000L, -1000L, 1)));

        MetricSuggestion hop = find(service.suggest(new MetricSuggestionRequest(List.of(skewed))),
            "flow:hop-latency:a.topic>b.topic");

        assertNull(hop.metric().warningThreshold(),
            "a negative hop measures clock skew, not latency — thresholding it would be nonsense");
        assertNull(hop.thresholdBasis());
        assertTrue(hop.evidence().get(0).contains("clock skew"), hop.evidence().toString());
    }

    @Test
    void theAuditWinsOverATraceDescribingTheSameHop() {
        when(auditService.getLastAuditReport()).thenReturn(reportWithFlow());
        FlowChainEvidence sameHop = new FlowChainEvidence("ORD-42", null, 1L, List.of(
            new FlowChainEvidence.FlowChainHop("demo.orders.in", 1000L, null, 1),
            new FlowChainEvidence.FlowChainHop("demo.orders.out", 2000L, 1000L, 1)));

        MetricSuggestions result = service.suggest(new MetricSuggestionRequest(List.of(sameHop)));

        long latencyCards = result.suggestions().stream()
            .filter(s -> s.id().contains("hop-latency:demo.orders.in>demo.orders.out"))
            .count();
        assertEquals(1, latencyCards, "one hop, one card: " + result.suggestions());
        assertEquals(MetricSuggestionSource.AUDIT,
            find(result, "audit:hop-latency:demo.orders.in>demo.orders.out").source());
    }

    // ── Lineage-derived ──────────────────────────────────────────────────────

    private void runningJob(String queryId, String sql, java.util.Set<String> sources,
                            String target, boolean parsed) {
        FlinkSqlService.JobInfo job = mock(FlinkSqlService.JobInfo.class);
        when(job.sql()).thenReturn(sql);
        when(job.queryId()).thenReturn(queryId);
        when(job.startedAt()).thenReturn(1_700_000_000_000L);
        when(flinkSqlService.getActiveJobsDetails()).thenReturn(Map.of(queryId, job));
        when(lineageService.dependenciesOf(sql))
            .thenReturn(new LineageService.SqlDependencies(sources, target, parsed));
    }

    @Test
    void aRunningInsertJobBecomesAGapKpiOnAnEdgeNobodyHadToGuess() {
        runningJob("q-1", "INSERT INTO orders_out SELECT * FROM orders_in",
            java.util.Set.of("orders_in"), "orders_out", true);

        MetricSuggestion gap = find(service.suggest(null), "lineage:flow-gap:orders_in>orders_out");

        assertEquals(MetricSuggestionSource.LINEAGE, gap.source());
        assertEquals(MetricTemplateType.TOPIC_COUNT_DELTA.name(), gap.metric().templateType());
        assertEquals("PERCENT_GAP", gap.metric().templateParams().get("operation"));
        // Nothing has ever measured this pair, so there is nothing to multiply.
        assertNull(gap.metric().warningThreshold());
        assertNull(gap.thresholdBasis());
        assertTrue(gap.evidence().get(0).contains("q-1"), gap.evidence().toString());
        assertTrue(gap.caveats().stream().anyMatch(c -> c.contains("filters or aggregates")),
            "a filtering job shows a permanent gap, which is correct: " + gap.caveats());
    }

    @Test
    void aJoinIsNotProposedAsAGapAndTheReasonIsStated() {
        runningJob("q-2", "INSERT INTO enriched SELECT * FROM orders JOIN customers ON …",
            java.util.Set.of("orders", "customers"), "enriched", true);

        MetricSuggestions result = service.suggest(null);

        assertTrue(result.suggestions().stream().noneMatch(s -> s.id().startsWith("lineage:")),
            "two inputs and one output have no ratio to threshold: " + result.suggestions());
        assertTrue(result.notes().stream().anyMatch(n -> n.contains("several sources")), result.notes().toString());
    }

    @Test
    void aStatementFlinkCouldNotParseIsProposedWithThatSaidOutLoud() {
        runningJob("q-3", "INSERT INTO out_t SELECT * FROM in_t",
            java.util.Set.of("in_t"), "out_t", false);

        MetricSuggestion gap = find(service.suggest(null), "lineage:flow-gap:in_t>out_t");

        assertTrue(gap.evidence().get(0).contains("could not resolve"), gap.evidence().toString());
        assertTrue(gap.caveats().stream().anyMatch(c -> c.contains("guessed from the SQL text")),
            gap.caveats().toString());
    }

    @Test
    void theAuditWinsOverLineageOnTheSamePair() {
        when(auditService.getLastAuditReport()).thenReturn(reportWithFlow());
        runningJob("q-4", "INSERT INTO x SELECT * FROM y",
            java.util.Set.of("demo.orders.in"), "demo.orders.out", true);

        MetricSuggestions result = service.suggest(null);

        long gaps = result.suggestions().stream()
            .filter(s -> s.id().contains("flow-gap:demo.orders.in>demo.orders.out"))
            .count();
        assertEquals(1, gaps, "one pair, one card: " + result.suggestions());
        assertEquals(MetricSuggestionSource.AUDIT,
            find(result, "audit:flow-gap:demo.orders.in>demo.orders.out").source());
    }

    // ── Process-Mining-derived ───────────────────────────────────────────────

    private FieldMapping mapping(Map<String, String> correlation, Map<String, String> statuses) {
        FieldMapping stored = new FieldMapping("map-1", correlation, Map.of(), statuses, Map.of());
        fieldMappingStore.put(stored);
        return stored;
    }

    @Test
    void aValidatedStatusFieldBecomesAKpiWithOneSeriesPerValue() {
        mapping(Map.of(), Map.of("demo.orders", "$.status"));

        MetricSuggestion status = find(
            service.suggest(new MetricSuggestionRequest(null, "map-1")), "pm:status:demo.orders");

        assertEquals(MetricSuggestionSource.PROCESS_MINING, status.source());
        assertTrue(status.metric().sql().contains("GROUP BY"), status.metric().sql());
        assertTrue(status.metric().sql().contains("AS metric_value"), status.metric().sql());
        // The value that matters, and above what count, is a business question nobody asked us.
        assertNull(status.metric().warningThreshold());
        assertTrue(status.caveats().stream().anyMatch(c -> c.contains("series per distinct value")),
            status.caveats().toString());
    }

    @Test
    void aNestedStatusPathIsRefusedRatherThanTurnedIntoSqlThatCannotRun() {
        mapping(Map.of(), Map.of("demo.orders", "$.order.header.status"));

        MetricSuggestions result = service.suggest(new MetricSuggestionRequest(null, "map-1"));

        assertTrue(result.suggestions().stream().noneMatch(s -> s.id().startsWith("pm:status:")));
        assertTrue(result.notes().stream().anyMatch(n -> n.contains("nested path")), result.notes().toString());
    }

    @Test
    void theValidatedCorrelationKeyBeatsTheConventionOnEveryProposalThatNeedsOne() {
        when(auditService.getLastAuditReport()).thenReturn(reportWithFlow());
        mapping(Map.of("demo.orders.in", "$.orderReference"), Map.of());

        MetricSuggestion latency = find(service.suggest(new MetricSuggestionRequest(null, "map-1")),
            "audit:hop-latency:demo.orders.in>demo.orders.out");

        assertTrue(String.valueOf(latency.metric().templateParams().get("sourceSql")).contains("`orderReference`"),
            "a validated key beats both the schema guess and the id convention: "
                + latency.metric().templateParams());
        assertTrue(latency.caveats().stream().anyMatch(c -> c.contains("validated for demo.orders.in")),
            "the card says where the key came from: " + latency.caveats());
    }

    @Test
    void aMappingTheServerNoLongerHoldsIsReportedRatherThanIgnored() {
        MetricSuggestions result = service.suggest(new MetricSuggestionRequest(null, "gone"));

        assertTrue(result.notes().stream().anyMatch(n -> n.contains("no longer held by")),
            "a restart loses the mappings, and the missing cards must have an explanation: "
                + result.notes());
    }

    @Test
    void withNoMappingTheAnswerSaysWhatValidatingOneWouldAdd() {
        MetricSuggestions result = service.suggest(null);

        assertTrue(result.notes().stream().anyMatch(n -> n.contains("Process Mining field mapping")),
            result.notes().toString());
    }

    // ── Already covered ──────────────────────────────────────────────────────

    @Test
    void aProposalAnExistingMetricCoversIsMarkedRatherThanHidden() {
        when(auditService.getLastAuditReport()).thenReturn(reportWithFlow());
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("sourceTopic", "demo.orders.in");
        params.put("targetTopic", "demo.orders.out");
        params.put("sourceSql", "SELECT id AS match_key, event_time FROM demo_orders_in");
        params.put("targetSql", "SELECT id AS match_key, event_time FROM demo_orders_out");
        when(metricService.getAllMetrics()).thenReturn(List.of(new MetricConfig(
            "id-1", "orders_latency", "GAUGE", null, null, null, null, null, null, null,
            List.of(), Map.of(), null, MetricTemplateType.TOPIC_TRANSIT_LATENCY.name(), params,
            "TEMPLATE_BOUNDED_SCAN", null, List.of())));

        MetricSuggestion latency = find(service.suggest(null),
            "audit:hop-latency:demo.orders.in>demo.orders.out");

        assertTrue(latency.alreadyConfigured(), "\"you already measure this\" is an answer, not a reason to hide");
        assertEquals("orders_latency", latency.existingMetricName());
    }

    // ── Fixtures ─────────────────────────────────────────────────────────────

    private static TopicAudit lagging() {
        return new TopicAudit("demo.payments", 10, MessageFormat.JSON, 0, 0, HealthStatus.CRITICAL,
            List.of(TopicIssue.critical("Consumer group 'pay-api' is 4000 message(s) behind with no "
                + "member assigned to this topic — nothing is draining it.")));
    }

    private static ConsumerGroupLag group(String id, long lag, int assignedMembers) {
        return new ConsumerGroupLag(id, "CLASSIC", "STABLE", assignedMembers, assignedMembers, true,
            lag, 0, List.of(new PartitionLag(0, 10L, 10L + lag, lag, null, null, null)), null);
    }

    private static TopicConsumers consumers(ConsumerGroupLag... groups) {
        return new TopicConsumers("demo.payments", List.of(groups), groups.length, groups.length,
            groups.length, false, true, List.of());
    }

    // ── Bounding and relevance ordering ──────────────────────────────────────

    /** A flow long enough to produce more proposals than the panel will show. */
    private static AuditReport longFlowReport(int steps) {
        List<FlowAudit.StepInfo> stepInfos = new java.util.ArrayList<>();
        for (int i = 0; i < steps; i++) {
            stepInfos.add(new FlowAudit.StepInfo(
                "demo.chain." + i, 1000L - (i * 10L), 100.0 - i, i == 0 ? null : 400L + i));
        }
        return report(List.of(), List.of(new FlowAudit("chain", stepInfos, 0.9)));
    }

    @Test
    void theCapKeepsTheMostRelevantAndSaysWhatItLeftOut() {
        when(auditService.getLastAuditReport()).thenReturn(longFlowReport(20));

        MetricSuggestions result = service.suggest(null);

        assertEquals(24, result.suggestions().size(),
            "the panel is bounded — an unbounded wall of cards is not a suggestion");
        String note = result.notes().stream()
            .filter(n -> n.contains("most relevant")).findFirst()
            .orElseThrow(() -> new AssertionError("the truncation must say it happened: " + result.notes()));
        // The old wording asserted the remainder were "of the same kinds, on other topics",
        // which nothing checked. This one counts them.
        assertTrue(note.contains("Left out, as least actionable:"), note);
    }

    @Test
    void whatSurvivesTheCapIsOrderedByRelevanceNotBySource() {
        when(auditService.getLastAuditReport()).thenReturn(longFlowReport(20));

        List<MetricSuggestion> kept = service.suggest(null).suggestions();

        // hop-latency outranks flow-gap, so no gap card may appear above a latency one.
        int lastLatency = -1, firstGap = Integer.MAX_VALUE;
        for (int i = 0; i < kept.size(); i++) {
            String kind = kept.get(i).id().split(":", 3)[1];
            if ("hop-latency".equals(kind)) lastLatency = i;
            if ("flow-gap".equals(kind) && i < firstGap) firstGap = i;
        }
        if (lastLatency >= 0 && firstGap != Integer.MAX_VALUE) {
            assertTrue(lastLatency < firstGap,
                "the cut must follow relevance, not the order the sources were consulted");
        }
    }

    @Test
    void theOrderIsStableAcrossIdenticalRuns() {
        when(auditService.getLastAuditReport()).thenReturn(longFlowReport(20));

        List<String> first = service.suggest(null).suggestions().stream().map(MetricSuggestion::id).toList();
        List<String> second = service.suggest(null).suggestions().stream().map(MetricSuggestion::id).toList();

        // Cards must not shuffle between two identical audits: a browser-side dismissal is keyed
        // on the id, and a list that reorders itself makes the panel look non-deterministic.
        assertEquals(first, second);
    }

    private static AuditReport report(List<TopicAudit> topics, List<FlowAudit> flows) {
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("timestamp", 1_700_000_000_000L);
        return new AuditReport("run-1", AuditStatus.COMPLETED, topics.size(), 0,
            0, 0, topics, flows, stats);
    }

    private static AuditReport reportWithFlow() {
        FlowAudit flow = new FlowAudit("orders", List.of(
            new FlowAudit.StepInfo("demo.orders.in", 1000, 100.0, null),
            new FlowAudit.StepInfo("demo.orders.out", 900, 90.0, 400L)), 0.9);
        return report(List.of(), List.of(flow));
    }

    private static FlowChainEvidence chain() {
        return new FlowChainEvidence("ORD-42", "$.orderId", 1_700_000_000_000L, List.of(
            new FlowChainEvidence.FlowChainHop("orders.received", 1_000L, null, 1),
            new FlowChainEvidence.FlowChainHop("orders.enriched", 1_812L, 812L, 1),
            new FlowChainEvidence.FlowChainHop("payments.settled", 3_000L, 1_188L, 2)));
    }

    private static MetricSuggestion find(MetricSuggestions result, String id) {
        Optional<MetricSuggestion> found = result.suggestions().stream()
            .filter(s -> s.id().equals(id))
            .findFirst();
        assertTrue(found.isPresent(), "expected a suggestion with id " + id + ", got "
            + result.suggestions().stream().map(MetricSuggestion::id).toList());
        return found.get();
    }
}
