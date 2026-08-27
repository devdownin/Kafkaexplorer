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
import com.compagnonsdudev.kafkasqlexplorer.domain.MetricDataState;
import com.compagnonsdudev.kafkasqlexplorer.domain.MetricSuggestion;
import com.compagnonsdudev.kafkasqlexplorer.domain.MetricSuggestionRequest;
import com.compagnonsdudev.kafkasqlexplorer.domain.MetricSuggestionSource;
import com.compagnonsdudev.kafkasqlexplorer.domain.MetricSuggestions;
import com.compagnonsdudev.kafkasqlexplorer.domain.MetricTemplateType;
import com.compagnonsdudev.kafkasqlexplorer.domain.PartitionLag;
import com.compagnonsdudev.kafkasqlexplorer.domain.ProcessModelEvidence;
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
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

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
        // The store persists to Kafka; the seam keeps this suite broker-free.
        ExplorerConfig mappingStoreConfig = new ExplorerConfig();
        fieldMappingStore = new FieldMappingStore(
            new com.compagnonsdudev.kafkasqlexplorer.config.KafkaConfig(), mappingStoreConfig,
            new StartupRestore(mappingStoreConfig)) {
            @Override
            org.apache.kafka.clients.producer.Producer<String, String> createProducer() {
                return new org.apache.kafka.clients.producer.MockProducer<>(true, null,
                    new org.apache.kafka.common.serialization.StringSerializer(),
                    new org.apache.kafka.common.serialization.StringSerializer());
            }
        };

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

    // ── Measured-process-derived ─────────────────────────────────────────────

    /*
     * Le point de toute cette famille : jusqu'ici, un KPI de latence reposait soit sur une moyenne
     * calculée par l'audit sur un flux reconstruit à partir des noms de topics, soit sur une clé
     * unique tracée à la main. Un graphe de successions porte une distribution.
     */
    @Test
    void aMeasuredTransitionCarriesThresholdsTakenFromItsOwnDistribution() {
        MetricSuggestions result = service.suggest(
            new MetricSuggestionRequest(List.of(), null, measuredProcess()));

        MetricSuggestion hop = find(result, "pm:hop-latency:orders.received>orders.enriched");
        assertEquals(MetricSuggestionSource.PROCESS_MINING, hop.source());
        assertEquals(9_000.0, hop.metric().warningThreshold(), "2× the measured p95");
        assertEquals(18_000.0, hop.metric().criticalThreshold(), "4× the measured p95");
        assertTrue(hop.thresholdBasis().contains("95th percentile of 120 cases"),
            hop.thresholdBasis());
        assertTrue(hop.evidence().get(0).contains("120 case(s)"), hop.evidence().toString());
        assertTrue(hop.evidence().get(0).contains("not over a sample"), hop.evidence().toString());
    }

    /*
     * Avec moins de vingt observations, le 95e centile *est* le maximum — c'est de l'arithmétique,
     * pas un avis. Le chiffre reste le même ; ce qui ne doit pas rester, c'est de l'appeler p95.
     */
    @Test
    void belowTwentyObservationsTheFigureIsNotCalledAPercentile() {
        ProcessModelEvidence measured = new ProcessModelEvidence(
            1_700_000_000_000L, 9, 0L, 60_000L, "MAPPED_FIELD",
            List.of(new ProcessModelEvidence.MeasuredTransition(
                "orders.received", "orders.enriched", 9, 9, 500L, 2_000L, 2_000L)),
            List.of());

        MetricSuggestion hop = find(service.suggest(new MetricSuggestionRequest(List.of(), null, measured)),
            "pm:hop-latency:orders.received>orders.enriched");

        assertFalse(hop.thresholdBasis().contains("percentile of"), hop.thresholdBasis());
        assertTrue(hop.thresholdBasis().contains("worst of the 9 case(s)"), hop.thresholdBasis());
        // Le seuil est bien dérivé quand même : ce qui change est le nom du chiffre, pas son usage.
        assertEquals(4_000.0, hop.metric().warningThreshold());
    }

    /*
     * Le renversement de préséance, énoncé plutôt que subi : l'audit gagnait sur tout le monde
     * parce qu'il était construit en premier. Une distribution sur 120 cas est une meilleure
     * preuve qu'une moyenne sur un flux déduit des noms de topics.
     */
    @Test
    void theMeasuredProcessWinsOverTheAuditOnTheSameHop() {
        when(auditService.getLastAuditReport()).thenReturn(reportWithFlow());
        ProcessModelEvidence measured = new ProcessModelEvidence(
            1_700_000_000_000L, 120, 0L, 3_600_000L, "MAPPED_FIELD",
            List.of(new ProcessModelEvidence.MeasuredTransition(
                "demo.orders.in", "demo.orders.out", 300, 120, 900L, 4_500L, 41_000L)),
            List.of());

        MetricSuggestions result = service.suggest(
            new MetricSuggestionRequest(List.of(), null, measured));

        long latencyCards = result.suggestions().stream()
            .filter(s -> s.id().contains("hop-latency:demo.orders.in>demo.orders.out"))
            .count();
        assertEquals(1, latencyCards, "one hop, one card: " + result.suggestions());
        assertEquals(MetricSuggestionSource.PROCESS_MINING,
            find(result, "pm:hop-latency:demo.orders.in>demo.orders.out").source());
    }

    /*
     * Une transition entre deux statuts d'un même topic est une vraie latence et n'a pas de paire à
     * corréler : le template compare deux topics, et le pointer sur un seul comparerait un topic
     * avec lui-même. Compté dans une note, jamais transformé en requête.
     */
    @Test
    void aTransitionInsideOneTopicIsCountedInANoteRatherThanComparedWithItself() {
        ProcessModelEvidence measured = new ProcessModelEvidence(
            1_700_000_000_000L, 40, 0L, 60_000L, "MAPPED_FIELD",
            List.of(new ProcessModelEvidence.MeasuredTransition(
                "orders.events \u00b7 RECEIVED", "orders.events \u00b7 SHIPPED",
                40, 40, 500L, 2_000L, 3_000L)),
            List.of());

        MetricSuggestions result = service.suggest(
            new MetricSuggestionRequest(List.of(), null, measured));

        assertTrue(result.suggestions().stream().noneMatch(s -> s.id().startsWith("pm:hop-latency:")),
            result.suggestions().toString());
        assertTrue(result.notes().stream().anyMatch(n -> n.contains("stay inside one topic")),
            result.notes().toString());
    }

    /*
     * La carte de reprise est la seule de ce fichier proposée *sans* seuil, et c'est délibéré : la
     * mesure compte des cas repassés par une étape dans la fenêtre, la requête compte des clés
     * partagées sur un scan borné. Un seuil pris sur l'une pour l'autre aurait l'air dérivé.
     */
    @Test
    void aRepeatedStepBecomesAReworkKpiWithNoThreshold() {
        MetricSuggestion rework = find(
            service.suggest(new MetricSuggestionRequest(List.of(), null, measuredProcess())),
            "pm:duplicates:orders.received");

        assertNull(rework.thresholdBasis());
        assertNull(rework.metric().warningThreshold());
        assertNull(rework.metric().criticalThreshold());
        assertTrue(rework.evidence().get(0).contains("7 case(s) visit"), rework.evidence().toString());
        assertTrue(rework.caveats().stream().anyMatch(c -> c.contains("not the same number")),
            rework.caveats().toString());
    }

    /* « Rien n'a été mesuré » et « la mesure ne suggère rien » sont deux états différents. */
    @Test
    void noMeasuredProcessSaysWhatWouldUnlockThoseKpis() {
        MetricSuggestions result = service.suggest(null);

        assertFalse(result.processMeasured());
        assertTrue(result.notes().stream().anyMatch(n -> n.contains("No measured process")),
            result.notes().toString());
        assertTrue(service.suggest(new MetricSuggestionRequest(List.of(), null, measuredProcess()))
            .processMeasured());
    }

    /*
     * À énoncer, jamais à supposer : une latence mesurée sur l'horloge du broker est une autre
     * mesure que la même latence sur l'horodatage métier.
     */
    @Test
    void aLogOrderedByTheBrokersClockSaysSoOnTheCard() {
        ProcessModelEvidence measured = new ProcessModelEvidence(
            1_700_000_000_000L, 30, 0L, 60_000L, "RECORD_TIMESTAMP",
            List.of(new ProcessModelEvidence.MeasuredTransition(
                "orders.received", "orders.enriched", 30, 30, 500L, 2_000L, 3_000L)),
            List.of());

        MetricSuggestion hop = find(service.suggest(new MetricSuggestionRequest(List.of(), null, measured)),
            "pm:hop-latency:orders.received>orders.enriched");

        assertTrue(hop.evidence().stream().anyMatch(e -> e.contains("transport delay")),
            hop.evidence().toString());
    }

    private static ProcessModelEvidence measuredProcess() {
        return new ProcessModelEvidence(
            1_700_000_000_000L, 120, 1_700_000_000_000L, 1_700_000_060_000L, "MAPPED_FIELD",
            List.of(new ProcessModelEvidence.MeasuredTransition(
                "orders.received", "orders.enriched", 300, 120, 900L, 4_500L, 41_000L)),
            List.of(new ProcessModelEvidence.MeasuredRepeat("orders.received", 7, 3)));
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

    /**
     * Resolving a statement is a Flink parse under the runtime's read lock, taken on every load of
     * the Metrics page, so this family is capped like every other one — and the cut is by start
     * time, since the map of active jobs has no order of its own.
     */
    @Test
    void onlyTheMostRecentlyStartedJobsAreResolvedAndTheRestAreCounted() {
        Map<String, FlinkSqlService.JobInfo> jobs = new java.util.HashMap<>();
        for (int i = 0; i < 15; i++) {
            String source = "src_" + i;
            String target = "dst_" + i;
            String sql = "INSERT INTO " + target + " SELECT * FROM " + source;
            FlinkSqlService.JobInfo job = mock(FlinkSqlService.JobInfo.class);
            when(job.sql()).thenReturn(sql);
            when(job.queryId()).thenReturn("q-" + i);
            when(job.startedAt()).thenReturn(1_700_000_000_000L + i);   // the higher i, the newer
            jobs.put("q-" + i, job);
            when(lineageService.dependenciesOf(sql))
                .thenReturn(new LineageService.SqlDependencies(java.util.Set.of(source), target, true));
        }
        when(flinkSqlService.getActiveJobsDetails()).thenReturn(jobs);

        MetricSuggestions result = service.suggest(null);

        assertEquals(12, result.suggestions().stream().filter(s -> s.source() == MetricSuggestionSource.LINEAGE).count());
        // The parse is what costs, so it is the parse that is bounded: three jobs are never read.
        verify(lineageService, times(12)).dependenciesOf(anyString());
        // The newest survive the cut, the oldest are the ones dropped.
        assertTrue(result.suggestions().stream().anyMatch(s -> s.id().equals("lineage:flow-gap:src_14>dst_14")));
        assertTrue(result.suggestions().stream().noneMatch(s -> s.id().equals("lineage:flow-gap:src_0>dst_0")));
        // And what was not read is said, never silently absent.
        assertTrue(result.notes().stream().anyMatch(n -> n.contains("3 further running job")),
            result.notes().toString());
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

    // ── Les données sont-elles encore là ? ───────────────────────────────────

    /**
     * Every card this panel builds rests on an observation that has already aged — an audit read
     * back from the history topic is weeks old — so the proposals are checked against the cluster
     * before being offered. A topic that has been deleted since can only fail at every refresh.
     */
    @Test
    void aProposalOverADeletedTopicIsDroppedAndNamed() throws Exception {
        when(auditService.getLastAuditReport()).thenReturn(reportWithFlow());
        // The source survived; the target was deleted after the audit that measured the hop.
        when(kafkaAdminService.listTopics()).thenReturn(List.of("demo.orders.in"));
        when(kafkaAdminService.getTopicRecordCounts(List.of("demo.orders.in")))
            .thenReturn(Map.of("demo.orders.in", 1_000L));

        MetricSuggestions result = service.suggest(null);

        assertTrue(result.suggestions().stream().noneMatch(s -> s.id().contains("demo.orders.out")),
            "a KPI reading a topic the cluster no longer carries must not be offered: "
                + result.suggestions().stream().map(MetricSuggestion::id).toList());
        assertTrue(result.notes().stream().anyMatch(note -> note.contains("demo.orders.out")),
            "the drop must name the topic, or the card simply vanishes: " + result.notes());
    }

    /**
     * Marked and kept, not dropped: a topic emptied by retention fills again, and on a gap KPI an
     * empty target beside a populated source is the alarm the card exists to raise.
     */
    @Test
    void aProposalOverAnEmptyTopicIsMarkedRatherThanDropped() throws Exception {
        when(auditService.getLastAuditReport()).thenReturn(reportWithFlow());
        when(kafkaAdminService.listTopics()).thenReturn(List.of("demo.orders.in", "demo.orders.out"));
        when(kafkaAdminService.getTopicRecordCounts(List.of("demo.orders.in", "demo.orders.out")))
            .thenReturn(Map.of("demo.orders.in", 1_000L, "demo.orders.out", 0L));

        MetricSuggestions result = service.suggest(null);

        MetricSuggestion gap = find(result, "audit:flow-gap:demo.orders.in>demo.orders.out");
        assertEquals(MetricDataState.EMPTY, gap.dataState());
        assertTrue(gap.caveats().stream().anyMatch(c -> c.contains("demo.orders.out")
                && c.contains("no record")),
            "the card has to say *which* of its topics is empty: " + gap.caveats());
    }

    /** The ordinary case says so, so that a threshold set on the card rests on a live topic. */
    @Test
    void aProposalWhoseTopicsHoldRecordsIsReportedAsMeasurableToday() throws Exception {
        when(auditService.getLastAuditReport()).thenReturn(reportWithFlow());
        when(kafkaAdminService.listTopics()).thenReturn(List.of("demo.orders.in", "demo.orders.out"));
        when(kafkaAdminService.getTopicRecordCounts(List.of("demo.orders.in", "demo.orders.out")))
            .thenReturn(Map.of("demo.orders.in", 1_000L, "demo.orders.out", 900L));

        MetricSuggestions result = service.suggest(null);

        assertEquals(MetricDataState.POPULATED,
            find(result, "audit:flow-gap:demo.orders.in>demo.orders.out").dataState());
    }

    /**
     * "We asked and the answer is no" and "we could not ask" are different answers, and only the
     * first may drop a card. An unreachable broker must not empty the panel.
     */
    @Test
    void anUnreadableTopicListDropsNothingAndSaysTheCheckDidNotRun() throws Exception {
        when(auditService.getLastAuditReport()).thenReturn(reportWithFlow());
        when(kafkaAdminService.listTopics()).thenThrow(new java.util.concurrent.TimeoutException("no broker"));

        MetricSuggestions result = service.suggest(null);

        MetricSuggestion gap = find(result, "audit:flow-gap:demo.orders.in>demo.orders.out");
        assertEquals(MetricDataState.UNKNOWN, gap.dataState());
        assertTrue(result.notes().stream().anyMatch(note -> note.contains("topic list could not be read")),
            "an unverified panel has to say it is unverified: " + result.notes());
    }

    /**
     * The counts failing is the narrower failure: existence was established, emptiness was not.
     * A topic whose count could not be read must not be marked empty — that is the very
     * flattening {@code getTopicRecordCounts} was split out of {@code getTopicsSize} to avoid.
     */
    @Test
    void topicsThatExistButCouldNotBeCountedAreNotReportedAsEmpty() throws Exception {
        when(auditService.getLastAuditReport()).thenReturn(reportWithFlow());
        when(kafkaAdminService.listTopics()).thenReturn(List.of("demo.orders.in", "demo.orders.out"));
        when(kafkaAdminService.getTopicRecordCounts(List.of("demo.orders.in", "demo.orders.out")))
            .thenReturn(Map.of());

        MetricSuggestions result = service.suggest(null);

        MetricSuggestion gap = find(result, "audit:flow-gap:demo.orders.in>demo.orders.out");
        assertEquals(MetricDataState.UNKNOWN, gap.dataState());
        // "no record" alone would match this card's standing caveat about offsets counting what
        // was produced rather than what is readable; the marker's own phrase is what must not be
        // there.
        assertTrue(gap.caveats().stream().noneMatch(c -> c.contains("no record right now")),
            "an unmeasured topic must not be described as empty: " + gap.caveats());
        assertTrue(result.notes().stream().anyMatch(note -> note.contains("record counts")),
            "the half of the check that did not run has to be named: " + result.notes());
    }

    /**
     * The lineage family's two ends come out of Flink's parser, so they are table names. The
     * cluster carries the topic under its dotted spelling, and reading one as the other would drop
     * a card whose evidence is the strongest there is — a job the operator is running right now.
     */
    @Test
    void aLineageProposalResolvesItsFlinkTableNamesBackToTopics() throws Exception {
        runningJob("q-1", "INSERT INTO demo_orders_out SELECT * FROM demo_orders_in",
            java.util.Set.of("demo_orders_in"), "demo_orders_out", true);
        when(kafkaAdminService.listTopics()).thenReturn(List.of("demo.orders.in", "demo.orders.out"));
        when(kafkaAdminService.getTopicRecordCounts(List.of("demo.orders.in", "demo.orders.out")))
            .thenReturn(Map.of("demo.orders.in", 1_000L, "demo.orders.out", 900L));

        MetricSuggestions result = service.suggest(null);

        MetricSuggestion declared = find(result, "lineage:flow-gap:demo_orders_in>demo_orders_out");
        assertEquals(MetricDataState.POPULATED, declared.dataState());
    }

    /**
     * A name that is no topic but <em>is</em> in Flink's catalogue: a table over another
     * connector, quite possibly. What sits behind it is the connector's business, so the
     * proposal is kept — dropping it would penalise the family whose evidence is the strongest
     * there is, a job the operator is running right now.
     */
    @Test
    void aNameThatIsARegisteredFlinkTableIsUnknownRatherThanDeleted() throws Exception {
        runningJob("q-1", "INSERT INTO sink SELECT * FROM source",
            java.util.Set.of("source"), "sink", true);
        when(kafkaAdminService.listTopics()).thenReturn(List.of("demo.orders.in"));
        when(flinkSqlService.getTableSchema("source")).thenReturn(Map.of("id", "STRING"));
        when(flinkSqlService.getTableSchema("sink")).thenReturn(Map.of("id", "STRING"));

        MetricSuggestions result = service.suggest(null);

        assertEquals(MetricDataState.UNKNOWN, find(result, "lineage:flow-gap:source>sink").dataState());
    }

    /**
     * The case the spelling heuristic got wrong, and it is not exotic: a flat {@code orders} is an
     * entirely ordinary Kafka topic name, and reading "no dot, no hyphen" as "must be a Flink
     * table" kept a proposal over a topic that had been deleted. Neither the cluster nor the
     * catalogue carries it, so nothing on this deployment can read it.
     */
    @Test
    void aFlatNameThatIsNeitherATopicNorATableIsDropped() throws Exception {
        runningJob("q-1", "INSERT INTO shipped SELECT * FROM orders",
            java.util.Set.of("orders"), "shipped", true);
        when(kafkaAdminService.listTopics()).thenReturn(List.of("demo.orders.in"));
        // getTableSchema answers an empty map for a name the catalogue does not carry — the
        // default stub in setUp already does that, stated here because it is the whole point.
        when(flinkSqlService.getTableSchema(anyString())).thenReturn(Map.of());

        MetricSuggestions result = service.suggest(null);

        assertTrue(result.suggestions().stream().noneMatch(s -> s.id().contains("orders>shipped")),
            "a name neither the cluster nor Flink carries can only fail at every refresh: "
                + result.suggestions().stream().map(MetricSuggestion::id).toList());
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
