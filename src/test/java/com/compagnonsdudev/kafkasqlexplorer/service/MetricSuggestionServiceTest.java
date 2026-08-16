// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors
package com.compagnonsdudev.kafkasqlexplorer.service;

import com.compagnonsdudev.kafkasqlexplorer.domain.AuditHistory;
import com.compagnonsdudev.kafkasqlexplorer.domain.AuditReport;
import com.compagnonsdudev.kafkasqlexplorer.domain.AuditStatus;
import com.compagnonsdudev.kafkasqlexplorer.domain.FlowAudit;
import com.compagnonsdudev.kafkasqlexplorer.domain.FlowChainEvidence;
import com.compagnonsdudev.kafkasqlexplorer.domain.HealthStatus;
import com.compagnonsdudev.kafkasqlexplorer.domain.MessageFormat;
import com.compagnonsdudev.kafkasqlexplorer.domain.MetricConfig;
import com.compagnonsdudev.kafkasqlexplorer.domain.MetricSuggestion;
import com.compagnonsdudev.kafkasqlexplorer.domain.MetricSuggestionRequest;
import com.compagnonsdudev.kafkasqlexplorer.domain.MetricSuggestionSource;
import com.compagnonsdudev.kafkasqlexplorer.domain.MetricSuggestions;
import com.compagnonsdudev.kafkasqlexplorer.domain.MetricTemplateType;
import com.compagnonsdudev.kafkasqlexplorer.domain.TopicAudit;
import com.compagnonsdudev.kafkasqlexplorer.domain.TopicIssue;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class MetricSuggestionServiceTest {

    private AuditService auditService;
    private AuditHistoryService auditHistoryService;
    private MetricService metricService;
    private FlinkSqlService flinkSqlService;
    private MetricSuggestionService service;

    @BeforeEach
    void setUp() {
        auditService = mock(AuditService.class);
        auditHistoryService = mock(AuditHistoryService.class);
        metricService = mock(MetricService.class);
        flinkSqlService = mock(FlinkSqlService.class);

        when(auditHistoryService.listHistory()).thenReturn(AuditHistory.empty(List.of()));
        when(metricService.getAllMetrics()).thenReturn(List.of());
        when(flinkSqlService.getTableSchema(anyString())).thenReturn(Map.of());

        service = new MetricSuggestionService(auditService, auditHistoryService, metricService, flinkSqlService);
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
