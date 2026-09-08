// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors
package com.compagnonsdudev.kafkasqlexplorer.mcp.tools;

import com.compagnonsdudev.kafkasqlexplorer.domain.MetricConfig;
import com.compagnonsdudev.kafkasqlexplorer.domain.MetricDataState;
import com.compagnonsdudev.kafkasqlexplorer.domain.MetricSuggestion;
import com.compagnonsdudev.kafkasqlexplorer.domain.MetricSuggestionSource;
import com.compagnonsdudev.kafkasqlexplorer.domain.MetricSuggestions;
import com.compagnonsdudev.kafkasqlexplorer.mcp.McpProperties;
import com.compagnonsdudev.kafkasqlexplorer.mcp.contract.StopReason;
import com.compagnonsdudev.kafkasqlexplorer.mcp.contract.ToolResult;
import com.compagnonsdudev.kafkasqlexplorer.mcp.guard.DlpScrubber;
import com.compagnonsdudev.kafkasqlexplorer.mcp.guard.McpToolException;
import com.compagnonsdudev.kafkasqlexplorer.mcp.guard.ToolGuard;
import com.compagnonsdudev.kafkasqlexplorer.service.MetricSuggestionService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class KpiMcpToolsTest {

    @Mock MetricSuggestionService service;

    private KpiMcpTools tools() {
        McpProperties properties = new McpProperties();
        return new KpiMcpTools(service, new ToolGuard(properties, new DlpScrubber(properties)));
    }

    private static MetricConfig metric(String sql) {
        return new MetricConfig("m-1", "orders_per_minute", "GAUGE", sql, null, null, null, null,
                null, null, List.of(), Map.of(), null, null, Map.of(), null, null, List.of(),
                Map.of());
    }

    private static MetricSuggestion suggestion(String thresholdBasis, MetricDataState state,
                                               boolean alreadyConfigured, String existing) {
        return new MetricSuggestion("kpi-1", MetricSuggestionSource.AUDIT,
                "Orders per minute", "The audit counted 1.2 M records on demo.orders.",
                List.of("audit run 42 counted 1 200 000 records"), thresholdBasis,
                List.of("a burst window skews a per-minute rate"), alreadyConfigured, existing,
                state, metric("SELECT COUNT(*) AS metric_value FROM demo_orders"));
    }

    private void answer(MetricSuggestions suggestions) {
        given(service.suggest(any())).willReturn(suggestions);
    }

    private static MetricSuggestions suggestions(List<MetricSuggestion> list, String auditId,
                                                 List<String> notes) {
        return new MetricSuggestions(list, auditId != null, auditId, auditId == null ? null : 1L,
                "AUDIT", auditId == null ? 0 : 12, 0, false, notes);
    }

    @Test
    void a_proposal_with_no_measured_basis_has_no_threshold_to_publish() {
        // "Suggest KPIs" is a question a model answers fluently from nothing — p99 under 200 ms,
        // lag under 1 000 — and every number in that answer is an invention about a cluster it has
        // never read. Unmeasured is what stops it being filled in.
        answer(suggestions(List.of(suggestion(null, MetricDataState.POPULATED, false, null)),
                "run-42", List.of()));

        KpiView.Kpi kpi = tools().suggestKpis().data().kpis().get(0);

        assertThat(kpi.thresholdBasis().measured()).isFalse();
        assertThat(kpi.thresholdBasis().value()).isNull();
        assertThat(kpi.thresholdBasis().reason()).contains("set the threshold from what it reports");
    }

    @Test
    void a_blank_basis_is_treated_as_no_basis_rather_than_an_empty_measurement() {
        answer(suggestions(List.of(suggestion("   ", MetricDataState.POPULATED, false, null)),
                "run-42", List.of()));

        assertThat(tools().suggestKpis().data().kpis().get(0).thresholdBasis().measured()).isFalse();
    }

    @Test
    void a_measured_basis_is_carried_through_in_the_words_of_the_run_that_made_it() {
        answer(suggestions(List.of(suggestion("audit run 42 measured a p95 of 1 800 ms over this hop",
                MetricDataState.POPULATED, false, null)), "run-42", List.of()));

        KpiView.Kpi kpi = tools().suggestKpis().data().kpis().get(0);

        assertThat(kpi.thresholdBasis().measured()).isTrue();
        assertThat(kpi.thresholdBasis().value()).contains("p95 of 1 800 ms");
    }

    @Test
    void the_audit_run_the_proposals_rest_on_is_named_so_they_can_be_checked() {
        answer(suggestions(List.of(suggestion("x", MetricDataState.POPULATED, false, null)),
                "run-42", List.of()));

        ToolResult<KpiView.Suggestions> result = tools().suggestKpis();

        assertThat(result.data().auditRunId().value()).isEqualTo("run-42");
        assertThat(result.data().auditTopics()).isEqualTo(12);
        assertThat(result.coverage().stopReason()).isEqualTo(StopReason.EXHAUSTED);
    }

    @Test
    void without_an_audit_the_list_rests_on_nothing_scanned_and_says_so() {
        // A short list because nothing has been measured is a different answer from a short list
        // because the cluster is simple, and EXHAUSTED would license reading it as the second.
        answer(suggestions(List.of(), null, List.of("No cluster audit could be read.")));

        ToolResult<KpiView.Suggestions> result = tools().suggestKpis();

        assertThat(result.data().auditRunId().measured()).isFalse();
        assertThat(result.coverage().stopReason()).isEqualTo(StopReason.PARTIAL_FAILURE);
        assertThat(result.warnings()).extracting(w -> w.code()).contains("NO_AUDIT");
    }

    @Test
    void the_services_own_notes_travel_as_warnings_so_missing_evidence_is_visible() {
        answer(suggestions(List.of(), "run-42",
                List.of("No Stream Flow trace was recorded in this browser.")));

        assertThat(tools().suggestKpis().warnings())
                .anySatisfy(warning -> assertThat(warning.message()).contains("Stream Flow"));
    }

    @Test
    void a_proposal_over_a_topic_that_is_gone_carries_its_data_state() {
        // ABSENT means the metric would be created and never produce a value.
        answer(suggestions(List.of(suggestion("x", MetricDataState.ABSENT, false, null)),
                "run-42", List.of()));

        assertThat(tools().suggestKpis().data().kpis().get(0).dataState()).isEqualTo("ABSENT");
    }

    @Test
    void a_proposal_an_existing_metric_already_covers_names_that_metric() {
        answer(suggestions(List.of(suggestion("x", MetricDataState.POPULATED, true, "orders_rate")),
                "run-42", List.of()));

        KpiView.Kpi kpi = tools().suggestKpis().data().kpis().get(0);

        assertThat(kpi.alreadyConfigured()).isTrue();
        assertThat(kpi.existingMetric()).isEqualTo("orders_rate");
    }

    @Test
    void the_sql_the_metric_would_run_is_returned_so_the_proposal_can_be_read_not_trusted() {
        answer(suggestions(List.of(suggestion("x", MetricDataState.POPULATED, false, null)),
                "run-42", List.of()));

        assertThat(tools().suggestKpis().data().kpis().get(0).sql())
                .contains("SELECT COUNT(*) AS metric_value");
    }

    @Test
    void a_service_failure_is_reported_rather_than_answered_with_an_empty_list() {
        // An empty list would say "there is nothing worth measuring here".
        given(service.suggest(any())).willThrow(new IllegalStateException("the audit store is down"));

        assertThatThrownBy(() -> tools().suggestKpis())
                .isInstanceOf(McpToolException.class)
                .hasMessageContaining("the audit store is down");
    }
}
