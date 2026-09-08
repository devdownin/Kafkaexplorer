// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors
package com.compagnonsdudev.kafkasqlexplorer.mcp.tools;

import com.compagnonsdudev.kafkasqlexplorer.domain.AuditOptions;
import com.compagnonsdudev.kafkasqlexplorer.domain.AuditReport;
import com.compagnonsdudev.kafkasqlexplorer.domain.AuditStatus;
import com.compagnonsdudev.kafkasqlexplorer.domain.FlowAudit;
import com.compagnonsdudev.kafkasqlexplorer.domain.HealthStatus;
import com.compagnonsdudev.kafkasqlexplorer.domain.MessageFormat;
import com.compagnonsdudev.kafkasqlexplorer.domain.TopicAudit;
import com.compagnonsdudev.kafkasqlexplorer.domain.TopicIssue;
import com.compagnonsdudev.kafkasqlexplorer.mcp.McpProperties;
import com.compagnonsdudev.kafkasqlexplorer.mcp.contract.StopReason;
import com.compagnonsdudev.kafkasqlexplorer.mcp.contract.ToolResult;
import com.compagnonsdudev.kafkasqlexplorer.mcp.guard.DlpScrubber;
import com.compagnonsdudev.kafkasqlexplorer.mcp.guard.McpScopeViolationException;
import com.compagnonsdudev.kafkasqlexplorer.mcp.guard.McpToolException;
import com.compagnonsdudev.kafkasqlexplorer.mcp.guard.ToolGuard;
import com.compagnonsdudev.kafkasqlexplorer.service.AuditService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AuditMcpToolsTest {

    @Mock AuditService audit;

    private AuditMcpTools toolsScopedTo(String... prefixes) {
        McpProperties properties = new McpProperties();
        properties.setAllowedTopicPrefixes(List.of(prefixes));
        return new AuditMcpTools(audit, new ToolGuard(properties, new DlpScrubber(properties)));
    }

    private AuditMcpTools tools() {
        return toolsScopedTo("*");
    }

    private static TopicAudit topic(String name, HealthStatus health, TopicIssue... issues) {
        return new TopicAudit(name, 100L, MessageFormat.JSON, 0, 0L, health, List.of(issues));
    }

    private static Map<String, Object> stats(String prefix, Double healthScore) {
        Map<String, Object> stats = new LinkedHashMap<>();
        Map<String, Object> options = new LinkedHashMap<>();
        options.put("topicPrefix", prefix);
        stats.put("options", options);
        if (healthScore != null) {
            stats.put("healthScore", healthScore);
        }
        return stats;
    }

    private static AuditReport report(AuditStatus status, List<TopicAudit> topics,
                                      Map<String, Object> stats) {
        return new AuditReport("run-1", status, topics.size(), 500L,
                (int) topics.stream().filter(t -> t.healthStatus() == HealthStatus.CRITICAL).count(),
                (int) topics.stream().filter(t -> t.healthStatus() == HealthStatus.WARNING).count(),
                topics, List.of(), stats);
    }

    @Test
    void a_prefix_outside_the_scope_does_not_launch_a_wider_run() {
        assertThatThrownBy(() -> toolsScopedTo("demo.").runAudit("prod.", null, null, null, null, null))
                .isInstanceOf(McpScopeViolationException.class);

        verifyNoInteractions(audit);
    }

    @Test
    void an_unscoped_run_on_a_scoped_deployment_is_restricted_rather_than_refused() {
        // An unscoped run would audit exactly what the prefixes withhold; the agent gets its slice.
        given(audit.startAudit(any())).willReturn(new AuditService.AuditStart("run-1", true));

        ToolResult<AuditView.Run> result = toolsScopedTo("demo.")
                .runAudit(null, null, null, null, null, null);

        ArgumentCaptor<AuditOptions> captor = ArgumentCaptor.forClass(AuditOptions.class);
        verify(audit).startAudit(captor.capture());
        assertThat(captor.getValue().topicPrefix()).isEqualTo("demo.");
        assertThat(result.data().scope()).isEqualTo("demo.");
        assertThat(result.warnings()).extracting(w -> w.code()).contains("SCOPED_RUN");
    }

    @Test
    void several_allowed_prefixes_ask_for_one_rather_than_picking_silently() {
        // An audit run takes one prefix, so choosing for the caller would answer about a slice
        // they did not name.
        assertThatThrownBy(() -> toolsScopedTo("demo.", "sandbox.")
                .runAudit(null, null, null, null, null, null))
                .isInstanceOf(McpToolException.class)
                .hasMessageContaining("topicPrefix");

        verifyNoInteractions(audit);
    }

    @Test
    void attaching_to_a_running_audit_says_so_rather_than_reading_as_a_fresh_run() {
        // Its scope is whatever that run chose, which is not what this caller asked for.
        given(audit.startAudit(any())).willReturn(new AuditService.AuditStart("other-run", false));
        given(audit.getAuditReport("other-run"))
                .willReturn(report(AuditStatus.RUNNING, List.of(), stats("sandbox.", null)));

        ToolResult<AuditView.Run> result = tools().runAudit("demo.", null, null, null, null, null);

        assertThat(result.data().started()).isFalse();
        assertThat(result.data().runId()).isEqualTo("other-run");
        assertThat(result.data().scope()).isEqualTo("sandbox.");
        assertThat(result.warnings()).extracting(w -> w.code())
                .contains("ATTACHED_TO_RUNNING_AUDIT");
    }

    @Test
    void the_checks_default_on_and_each_can_be_turned_off_by_name() {
        given(audit.startAudit(any())).willReturn(new AuditService.AuditStart("run-1", true));

        tools().runAudit(null, false, null, null, false, null);

        ArgumentCaptor<AuditOptions> captor = ArgumentCaptor.forClass(AuditOptions.class);
        verify(audit).startAudit(captor.capture());
        AuditOptions options = captor.getValue();
        assertThat(options.checkPoisonMessages()).isFalse();
        assertThat(options.checkExactCount()).isFalse();
        assertThat(options.checkDuplicates()).isTrue();
        assertThat(options.checkFlows()).isTrue();
        assertThat(options.checkConsumerLag()).isTrue();
    }

    @Test
    void a_started_run_claims_no_coverage_because_nothing_has_been_read_yet() {
        given(audit.startAudit(any())).willReturn(new AuditService.AuditStart("run-1", true));

        ToolResult<AuditView.Run> result = tools().runAudit(null, null, null, null, null, null);

        assertThat(result.coverage().topicsScanned()).isZero();
        assertThat(result.data().started()).isTrue();
    }

    @Test
    void a_running_report_is_partial_and_says_an_absent_finding_is_not_a_finding_of_absence() {
        given(audit.getAuditReport("run-1")).willReturn(
                report(AuditStatus.RUNNING, List.of(topic("demo.orders", HealthStatus.HEALTHY)),
                        stats(null, null)));

        ToolResult<AuditView.Report> result = tools().getAudit("run-1");

        assertThat(result.data().status()).isEqualTo("RUNNING");
        assertThat(result.coverage().stopReason()).isEqualTo(StopReason.TIME_BUDGET);
        assertThat(result.coverage().resumeToken()).isEqualTo("run-1");
        assertThat(result.warnings()).extracting(w -> w.code()).contains("AUDIT_RUNNING");
        assertThat(result.truncated()).isTrue();
    }

    @Test
    void a_completed_run_is_exhausted_and_healthy_topics_are_not_listed() {
        // An empty findings list on a COMPLETED run over many topics is genuinely good news; the
        // count that makes it readable is topicsAudited.
        given(audit.getAuditReport("run-1")).willReturn(report(AuditStatus.COMPLETED,
                List.of(topic("demo.a", HealthStatus.HEALTHY), topic("demo.b", HealthStatus.HEALTHY)),
                stats(null, 0.98)));

        ToolResult<AuditView.Report> result = tools().getAudit("run-1");

        assertThat(result.data().findings()).isEmpty();
        assertThat(result.data().topicsAudited()).isEqualTo(2);
        assertThat(result.data().healthScore().value()).isEqualTo(0.98);
        assertThat(result.coverage().stopReason()).isEqualTo(StopReason.EXHAUSTED);
    }

    @Test
    void findings_are_worst_first_so_the_critical_one_is_not_below_the_cap() {
        given(audit.getAuditReport("run-1")).willReturn(report(AuditStatus.COMPLETED, List.of(
                topic("demo.warn", HealthStatus.WARNING, TopicIssue.warning("duplicate keys")),
                topic("demo.bad", HealthStatus.CRITICAL, TopicIssue.critical("payloads do not parse"))),
                stats(null, 0.4)));

        ToolResult<AuditView.Report> result = tools().getAudit("run-1");

        assertThat(result.data().findings()).extracting(AuditView.TopicFinding::topic)
                .containsExactly("demo.bad", "demo.warn");
    }

    @Test
    void a_run_with_no_score_is_unmeasured_rather_than_zero() {
        // Zero reads as "everything is broken", which is the opposite of "we have not graded it".
        given(audit.getAuditReport("run-1"))
                .willReturn(report(AuditStatus.RUNNING, List.of(), stats(null, null)));

        AuditView.Report view = tools().getAudit("run-1").data();

        assertThat(view.healthScore().measured()).isFalse();
        assertThat(view.healthScore().reason()).contains("not computed");
    }

    @Test
    void a_scoped_run_says_it_covers_a_slice_of_the_cluster() {
        // A clean report over demo. says nothing about the rest.
        given(audit.getAuditReport("run-1"))
                .willReturn(report(AuditStatus.COMPLETED, List.of(), stats("demo.", 1.0)));

        ToolResult<AuditView.Report> result = tools().getAudit("run-1");

        assertThat(result.data().scope()).isEqualTo("demo.");
        assertThat(result.warnings()).extracting(w -> w.code()).contains("SCOPED_AUDIT");
    }

    @Test
    void a_cancelled_run_reports_what_it_reached_and_says_it_is_not_the_scope_it_was_given() {
        given(audit.getAuditReport("run-1")).willReturn(report(AuditStatus.CANCELLED,
                List.of(topic("demo.a", HealthStatus.WARNING, TopicIssue.warning("x"))),
                stats(null, 0.5)));

        ToolResult<AuditView.Report> result = tools().getAudit("run-1");

        assertThat(result.coverage().stopReason()).isEqualTo(StopReason.CANCELLED);
        assertThat(result.warnings()).extracting(w -> w.code()).contains("AUDIT_CANCELLED");
    }

    @Test
    void an_unknown_run_id_says_runs_do_not_survive_a_restart() {
        given(audit.getAuditReport(anyString())).willReturn(null);

        assertThatThrownBy(() -> tools().getAudit("ghost"))
                .isInstanceOf(McpToolException.class)
                .hasMessageContaining("restart");
    }

    @Test
    void asking_for_the_last_report_before_any_run_says_to_start_one() {
        given(audit.getLastAuditReport()).willReturn(null);

        assertThatThrownBy(() -> tools().getAudit(null))
                .isInstanceOf(McpToolException.class)
                .hasMessageContaining("kex_run_audit");
    }

    @Test
    void a_flow_step_with_no_timed_pair_is_unmeasured_rather_than_zero_latency() {
        FlowAudit flow = new FlowAudit("orders → payments", List.of(
                new FlowAudit.StepInfo("demo.orders", 100L, 100.0, 250L),
                new FlowAudit.StepInfo("demo.payments", 90L, 90.0, null)), 0.9);
        given(audit.getAuditReport("run-1")).willReturn(new AuditReport("run-1",
                AuditStatus.COMPLETED, 2, 190L, 0, 0, List.of(), List.of(flow), stats(null, 0.9)));

        AuditView.FlowFinding view = tools().getAudit("run-1").data().flows().get(0);

        assertThat(view.steps().get(0).latencyMs().value()).isEqualTo(250L);
        assertThat(view.steps().get(1).latencyMs().measured()).isFalse();
        // A 0..1 ratio, not a percentage: the UI multiplies by 100 and doing it twice reports 8500 %.
        assertThat(view.healthScore()).isEqualTo(0.9);
    }
}
