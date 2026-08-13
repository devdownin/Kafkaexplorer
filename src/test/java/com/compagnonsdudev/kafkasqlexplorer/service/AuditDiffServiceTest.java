package com.compagnonsdudev.kafkasqlexplorer.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.compagnonsdudev.kafkasqlexplorer.domain.AuditDiff;
import com.compagnonsdudev.kafkasqlexplorer.domain.AuditReport;
import com.compagnonsdudev.kafkasqlexplorer.domain.AuditStatus;
import com.compagnonsdudev.kafkasqlexplorer.domain.HealthStatus;
import com.compagnonsdudev.kafkasqlexplorer.domain.MessageFormat;
import com.compagnonsdudev.kafkasqlexplorer.domain.TopicAudit;
import com.compagnonsdudev.kafkasqlexplorer.domain.TopicDiff;
import com.compagnonsdudev.kafkasqlexplorer.domain.TopicIssue;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AuditDiffServiceTest {

    private AuditService auditService;
    private AuditHistoryService auditHistoryService;
    private AuditDiffService service;

    @BeforeEach
    void setUp() {
        auditService = mock(AuditService.class);
        auditHistoryService = mock(AuditHistoryService.class);
        service = new AuditDiffService(auditService, auditHistoryService);
    }

    private static TopicAudit topic(String name, HealthStatus status, String... issues) {
        List<TopicIssue> list = java.util.Arrays.stream(issues)
                .map(m -> new TopicIssue(m, status == HealthStatus.CRITICAL
                        ? HealthStatus.CRITICAL : HealthStatus.WARNING))
                .toList();
        return new TopicAudit(name, 100, MessageFormat.JSON, 0, 0, status, list);
    }

    private static AuditReport report(String id, long timestamp, Double healthScore, TopicAudit... topics) {
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("timestamp", timestamp);
        if (healthScore != null) stats.put("healthScore", healthScore);
        Map<String, Object> options = new LinkedHashMap<>();
        options.put("checkSchema", true);
        options.put("checkDuplicates", true);
        stats.put("options", options);
        int critical = (int) java.util.Arrays.stream(topics).filter(t -> t.healthStatus() == HealthStatus.CRITICAL).count();
        int warning = (int) java.util.Arrays.stream(topics).filter(t -> t.healthStatus() == HealthStatus.WARNING).count();
        return new AuditReport(id, AuditStatus.COMPLETED, topics.length, 0, critical, warning,
                List.of(topics), List.of(), stats);
    }

    private void inMemory(AuditReport... reports) {
        for (AuditReport r : reports) when(auditService.getAuditReport(r.auditId())).thenReturn(r);
    }

    @Test
    void reportsOnlyTheTopicsWhoseHealthMoved() {
        inMemory(
            report("old", 1_000L, 0.6,
                topic("a.stable", HealthStatus.HEALTHY),
                topic("a.regressing", HealthStatus.HEALTHY),
                topic("a.improving", HealthStatus.CRITICAL, "bad payloads"),
                topic("a.gone", HealthStatus.WARNING, "duplicates")),
            report("new", 2_000L, 0.8,
                topic("a.stable", HealthStatus.HEALTHY),
                topic("a.regressing", HealthStatus.CRITICAL, "bad payloads"),
                topic("a.improving", HealthStatus.HEALTHY),
                topic("a.fresh", HealthStatus.WARNING, "duplicates")));

        AuditDiff diff = service.compare("old", "new").diff();

        assertNotNull(diff);
        // a.stable is identical on both sides: counted, not listed.
        assertEquals(1, diff.unchanged());
        assertEquals(List.of("a.regressing", "a.fresh", "a.gone", "a.improving"),
                diff.topics().stream().map(TopicDiff::name).toList(),
                "regressions first — that is what the reader is looking for");
        assertEquals(1, diff.regressed());
        assertEquals(1, diff.improved());
        assertEquals(1, diff.added());
        assertEquals(1, diff.removed());
        assertEquals(0.2, diff.healthScoreDelta(), 1e-9);
    }

    @Test
    void carriesWhichFindingsAppearedAndWhichWereResolved() {
        inMemory(
            report("old", 1_000L, 0.5, topic("a.one", HealthStatus.WARNING, "duplicates", "slow count")),
            report("new", 2_000L, 0.5, topic("a.one", HealthStatus.WARNING, "duplicates", "poison")));

        TopicDiff diff = service.compare("old", "new").diff().topics().get(0);

        assertEquals(TopicDiff.ChangeKind.ISSUES_CHANGED, diff.change(),
                "same severity, different findings — still worth showing");
        assertEquals(List.of("poison"), diff.newIssues());
        assertEquals(List.of("slow count"), diff.resolvedIssues());
    }

    @Test
    void aTopicWhoseVolumeChangedButHealthDidNotIsNotAFinding() {
        TopicAudit before = new TopicAudit("a.one", 100, MessageFormat.JSON, 0, 0, HealthStatus.HEALTHY, List.of());
        TopicAudit after = new TopicAudit("a.one", 999_999, MessageFormat.JSON, 0, 0, HealthStatus.HEALTHY, List.of());
        inMemory(report("old", 1_000L, 1.0, before), report("new", 2_000L, 1.0, after));

        AuditDiff diff = service.compare("old", "new").diff();

        assertTrue(diff.topics().isEmpty(), "message counts move on every live topic");
        assertEquals(1, diff.unchanged());
    }

    @Test
    void warnsWhenTheTwoRunsDidNotEnableTheSameChecks() {
        AuditReport old = report("old", 1_000L, 1.0, topic("a.one", HealthStatus.HEALTHY));
        Map<String, Object> stats = new LinkedHashMap<>(old.globalStats());
        stats.put("options", Map.of("checkSchema", true, "checkDuplicates", false));
        AuditReport narrowed = new AuditReport("new", AuditStatus.COMPLETED, 1, 0, 0, 0,
                List.of(topic("a.one", HealthStatus.HEALTHY)), List.of(), stats);
        inMemory(old, narrowed);

        AuditDiff diff = service.compare("old", "new").diff();

        assertTrue(diff.warnings().stream().anyMatch(w -> w.contains("did not enable the same checks")),
                "differences may come from the options, not the cluster: " + diff.warnings());
    }

    @Test
    void refusesToCompareAgainstTheRetiredBinaryScale() throws Exception {
        inMemory(report("new", 2_000L, 0.8, topic("a.one", HealthStatus.HEALTHY)));
        // Shape written before graded severity.
        when(auditHistoryService.findReport("legacy")).thenReturn(new ObjectMapper().readTree("""
            {"auditId":"legacy","status":"COMPLETED","totalTopics":1,"unhealthyTopicsCount":1,
             "topicAudits":[{"name":"a.one","healthStatus":"UNHEALTHY","issues":["something"]}],
             "globalStats":{"timestamp":1000}}
            """));

        AuditDiffService.DiffResult result = service.compare("legacy", "new");

        assertNull(result.diff());
        assertEquals(AuditDiffService.DiffError.LEGACY_SHAPE, result.error());
        assertTrue(result.message().contains("graded severity"),
                "the refusal must say why: " + result.message());
    }

    @Test
    void fallsBackToTheHistoryTopicWhenARunIsNoLongerInMemory() throws Exception {
        inMemory(report("new", 2_000L, 0.9, topic("a.one", HealthStatus.HEALTHY)));
        when(auditHistoryService.findReport("archived")).thenReturn(new ObjectMapper().valueToTree(
                report("archived", 1_000L, 0.4, topic("a.one", HealthStatus.CRITICAL, "bad payloads"))));

        AuditDiff diff = service.compare("archived", "new").diff();

        assertNotNull(diff, "an evicted run must still be comparable from the history topic");
        assertEquals(1, diff.improved());
        assertEquals(List.of("bad payloads"), diff.topics().get(0).resolvedIssues());
    }

    @Test
    void saysWhichSideIsMissingRatherThanReturningAnEmptyDiff() {
        inMemory(report("new", 2_000L, 0.9, topic("a.one", HealthStatus.HEALTHY)));

        AuditDiffService.DiffResult result = service.compare("nowhere", "new");

        assertNull(result.diff());
        assertEquals(AuditDiffService.DiffError.FROM_NOT_FOUND, result.error());
        assertTrue(result.message().contains("nowhere"));

        assertEquals(AuditDiffService.DiffError.TO_NOT_FOUND,
                service.compare("new", "nowhere").error());
    }
}
