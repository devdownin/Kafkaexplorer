// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors
package com.compagnonsdudev.kafkasqlexplorer.mcp.tools;

import com.compagnonsdudev.kafkasqlexplorer.domain.StreamFlowHit;
import com.compagnonsdudev.kafkasqlexplorer.domain.StreamFlowRequest;
import com.compagnonsdudev.kafkasqlexplorer.domain.StreamFlowResponse;
import com.compagnonsdudev.kafkasqlexplorer.domain.StreamFlowStats;
import com.compagnonsdudev.kafkasqlexplorer.mcp.McpProperties;
import com.compagnonsdudev.kafkasqlexplorer.mcp.contract.StopReason;
import com.compagnonsdudev.kafkasqlexplorer.mcp.contract.ToolResult;
import com.compagnonsdudev.kafkasqlexplorer.mcp.guard.DlpScrubber;
import com.compagnonsdudev.kafkasqlexplorer.mcp.guard.McpScopeViolationException;
import com.compagnonsdudev.kafkasqlexplorer.mcp.guard.McpToolException;
import com.compagnonsdudev.kafkasqlexplorer.mcp.guard.ToolGuard;
import com.compagnonsdudev.kafkasqlexplorer.service.StreamFlowService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class StreamFlowMcpToolsTest {

    @Mock StreamFlowService streamFlow;

    private final McpTraceStore traces = new McpTraceStore();

    private StreamFlowMcpTools toolsScopedTo(String... prefixes) {
        McpProperties properties = new McpProperties();
        properties.setAllowedTopicPrefixes(List.of(prefixes));
        return new StreamFlowMcpTools(streamFlow, new ToolGuard(properties, new DlpScrubber(properties)), traces);
    }

    private StreamFlowMcpTools tools() {
        return toolsScopedTo("*");
    }

    private static StreamFlowHit hit(String topic, long first, Long latency) {
        return new StreamFlowHit(topic, 1, first, first, 0, 0L, "ORD-1042", "{}", latency, false);
    }

    private static StreamFlowStats stats(int inScope, int scanned, List<String> skipped, String stopReason) {
        return stats(inScope, scanned, skipped, List.of(), stopReason);
    }

    private static StreamFlowStats stats(int inScope, int scanned, List<String> skipped,
                                         List<String> failed, String stopReason) {
        return new StreamFlowStats(inScope, scanned, skipped.size(), failed.size(), skipped, failed,
                120, 2, 900L, false, stopReason, 500, null);
    }

    private void answer(List<StreamFlowHit> hits, StreamFlowStats stats) {
        given(streamFlow.getStreamFlow(any()))
                .willReturn(new StreamFlowResponse(List.of(), List.of(), hits, stats, List.of()));
    }

    @Test
    void a_topic_outside_the_scope_is_refused_before_the_service_is_touched() {
        StreamFlowMcpTools tools = toolsScopedTo("demo.");

        assertThatThrownBy(() -> tools.traceKey("ORD-1042", "ANY", null, null,
                List.of("prod.payments"), null, null))
                .isInstanceOf(McpScopeViolationException.class);

        verifyNoInteractions(streamFlow);
    }

    @Test
    void topics_never_reached_are_named_and_carry_a_resume_token() {
        answer(List.of(hit("orders", 1_000L, null)),
                stats(9, 4, List.of("shipments", "invoices"), "TIME_BUDGET"));

        ToolResult<TraceView.Trace> result = tools().traceKey("ORD-1042", "ANY", null, null,
                null, null, null);

        assertThat(result.coverage().stopReason()).isEqualTo(StopReason.TIME_BUDGET);
        assertThat(result.coverage().topicsNotReached()).containsExactly("shipments", "invoices");
        assertThat(result.coverage().resumeToken()).isNotNull();
        assertThat(result.coverage().complete()).isFalse();
    }

    @Test
    void a_scan_that_finished_but_could_not_read_every_topic_is_not_exhausted() {
        // EXHAUSTED is the one value that licenses reading an empty result as "does not exist".
        answer(List.of(), stats(9, 5, List.of("invoices"), "COMPLETE"));

        ToolResult<TraceView.Trace> result = tools().traceKey("ORD-1042", null, null, null,
                null, null, null);

        assertThat(result.data().hops()).isEmpty();
        assertThat(result.coverage().stopReason()).isEqualTo(StopReason.PARTIAL_FAILURE);
    }

    @Test
    void a_topic_that_failed_is_not_reached_either_and_can_be_resumed() {
        // Leaving a failed topic out of the coverage lets an empty trace look like a complete
        // negative answer over a topic nobody managed to read.
        answer(List.of(), stats(4, 3, List.of(), List.of("invoices"), "COMPLETE"));

        ToolResult<TraceView.Trace> result = tools().traceKey("ORD-1042", null, null, null,
                null, null, null);

        assertThat(result.coverage().topicsNotReached()).containsExactly("invoices");
        assertThat(result.coverage().resumeToken()).isNotNull();
    }

    @Test
    void a_complete_scan_of_everything_is_exhausted() {
        answer(List.of(hit("orders", 1_000L, null)), stats(3, 3, List.of(), "COMPLETE"));

        ToolResult<TraceView.Trace> result = tools().traceKey("ORD-1042", null, null, null,
                null, null, null);

        assertThat(result.coverage().stopReason()).isEqualTo(StopReason.EXHAUSTED);
        assertThat(result.coverage().resumeToken()).isNull();
    }

    @Test
    void the_slowest_hop_is_marked_and_a_backwards_hop_is_named_as_clock_skew() {
        answer(List.of(hit("orders", 1_000L, null), hit("shipments", 900L, 4_000L),
                        hit("invoices", 5_000L, 100L)),
                stats(3, 3, List.of(), "COMPLETE"));

        TraceView.Trace trace = tools().traceKey("ORD-1042", null, null, null, null, null, null).data();

        assertThat(trace.hops()).extracting(TraceView.Hop::slowest)
                .containsExactly(false, true, false);
        // Reported, never corrected: a model told only the numbers reasons about a negative delay.
        assertThat(trace.clockSkew()).contains("shipments").contains("orders");
    }

    @Test
    void an_exact_key_search_sets_exact_key_and_leaves_the_search_path_empty() {
        answer(List.of(), stats(1, 1, List.of(), "COMPLETE"));

        tools().traceKey("ORD-1042", "exact_key", null, null, List.of("orders"), null, null);

        ArgumentCaptor<StreamFlowRequest> captor = ArgumentCaptor.forClass(StreamFlowRequest.class);
        verify(streamFlow).getStreamFlow(captor.capture());
        assertThat(captor.getValue().isExactKey()).isTrue();
        assertThat(captor.getValue().searchPath()).isNull();
        assertThat(captor.getValue().isSearchHeaders()).isFalse();
    }

    @Test
    void a_field_search_passes_the_path_through_untouched() {
        // The service infers dotted / JSONPath / XPath from the shape, so one mode covers all three.
        answer(List.of(), stats(1, 1, List.of(), "COMPLETE"));

        tools().traceKey("ORD-1042", "FIELD", null, "$.items[0].sku", List.of("orders"), null, null);

        ArgumentCaptor<StreamFlowRequest> captor = ArgumentCaptor.forClass(StreamFlowRequest.class);
        verify(streamFlow).getStreamFlow(captor.capture());
        assertThat(captor.getValue().searchPath()).isEqualTo("$.items[0].sku");
    }

    @Test
    void a_header_search_carries_the_header_prefix_the_service_expects() {
        answer(List.of(), stats(1, 1, List.of(), "COMPLETE"));

        tools().traceKey("ORD-1042", "HEADER", "x-correlation-id", null, List.of("orders"), null, null);

        ArgumentCaptor<StreamFlowRequest> captor = ArgumentCaptor.forClass(StreamFlowRequest.class);
        verify(streamFlow).getStreamFlow(captor.capture());
        assertThat(captor.getValue().searchPath()).isEqualTo("header:x-correlation-id");
    }

    @Test
    void a_field_search_with_no_path_is_refused_rather_than_widened_to_the_whole_record() {
        assertThatThrownBy(() -> tools().traceKey("ORD-1042", "FIELD", null, null, null, null, null))
                .isInstanceOf(McpToolException.class)
                .hasMessageContaining("path");

        verifyNoInteractions(streamFlow);
    }

    @Test
    void a_locator_given_for_a_mode_that_has_none_is_refused_not_ignored() {
        // Ignoring it scans the whole record for the value while the caller believes one field is
        // being read: a plausible, wider answer with nothing in it that contradicts them.
        assertThatThrownBy(() -> tools().traceKey("ORD-1042", "ANY", null, "order.id", null, null, null))
                .isInstanceOf(McpToolException.class)
                .hasMessageContaining("neither headerName nor path");

        verifyNoInteractions(streamFlow);
    }

    @Test
    void an_unknown_mode_names_the_ones_that_exist() {
        assertThatThrownBy(() -> tools().traceKey("ORD-1042", "JSONPATH", null, "$.id", null, null, null))
                .isInstanceOf(McpToolException.class)
                .hasMessageContaining("EXACT_KEY, FIELD, HEADER or ANY");
    }

    @Test
    void an_unknown_resume_token_is_reported_rather_than_answered_with_an_empty_second_pass() {
        assertThatThrownBy(() -> tools().resumeTrace("rt-deadbeef"))
                .isInstanceOf(McpToolException.class)
                .hasMessageContaining("unknown or has expired");

        verifyNoInteractions(streamFlow);
    }

    @Test
    void a_resume_carries_the_prior_hits_and_only_the_topics_that_were_never_read() {
        answer(List.of(hit("orders", 1_000L, null)),
                stats(9, 4, List.of("shipments", "invoices"), "TIME_BUDGET"));
        StreamFlowMcpTools tools = tools();
        String token = tools.traceKey("ORD-1042", null, null, null, null, null, null)
                .coverage().resumeToken();

        answer(List.of(hit("orders", 1_000L, null), hit("shipments", 2_000L, 1_000L)),
                stats(2, 2, List.of(), "COMPLETE"));
        tools.resumeTrace(token);

        ArgumentCaptor<StreamFlowRequest> captor = ArgumentCaptor.forClass(StreamFlowRequest.class);
        verify(streamFlow, org.mockito.Mockito.times(2)).getStreamFlow(captor.capture());
        StreamFlowRequest resumed = captor.getAllValues().get(1);
        assertThat(resumed.targetTopics()).containsExactly("shipments", "invoices");
        assertThat(resumed.priorHits()).hasSize(1);
        assertThat(resumed.messageKey()).isEqualTo("ORD-1042");
    }

    @Test
    void a_token_is_single_use_so_a_replayed_resume_does_not_re_scan() {
        answer(List.of(), stats(9, 4, List.of("invoices"), "TIME_BUDGET"));
        StreamFlowMcpTools tools = tools();
        String token = tools.traceKey("ORD-1042", null, null, null, null, null, null)
                .coverage().resumeToken();

        answer(List.of(), stats(1, 1, List.of(), "COMPLETE"));
        tools.resumeTrace(token);

        assertThatThrownBy(() -> tools.resumeTrace(token)).isInstanceOf(McpToolException.class);
    }

    @Test
    void a_comparison_reports_deltas_and_the_topics_only_one_key_reached() {
        given(streamFlow.getStreamFlow(any()))
                .willReturn(new StreamFlowResponse(List.of(), List.of(),
                        List.of(hit("orders", 1_000L, null), hit("shipments", 2_000L, 1_000L)),
                        stats(3, 3, List.of(), "COMPLETE"), List.of()))
                .willReturn(new StreamFlowResponse(List.of(), List.of(),
                        List.of(hit("orders", 5_000L, null), hit("dlq", 9_000L, 4_000L)),
                        stats(3, 3, List.of(), "COMPLETE"), List.of()));

        ToolResult<TraceView.Comparison> result = tools().compareTraces("ORD-1042", "ORD-1043",
                null, null, null, null);

        assertThat(result.data().commonTopics()).containsExactly("orders");
        assertThat(result.data().onlyInA()).containsExactly("shipments");
        assertThat(result.data().onlyInB()).containsExactly("dlq");
        // Never a resume token: continuing one of the two halves leaves the comparison
        // half-refreshed, and a caller cannot tell which half.
        assertThat(result.coverage().resumeToken()).isNull();
    }

    @Test
    void a_comparison_whose_halves_did_not_finish_says_a_divergence_may_be_a_spent_budget() {
        answer(List.of(hit("orders", 1_000L, null)), stats(9, 3, List.of("dlq"), "TIME_BUDGET"));

        ToolResult<TraceView.Comparison> result = tools().compareTraces("ORD-1042", "ORD-1043",
                null, null, null, null);

        assertThat(result.warnings()).extracting(w -> w.code()).contains("PARTIAL_COMPARISON");
    }

    @Test
    void a_bad_path_is_the_callers_fault_and_keeps_the_services_own_message() {
        given(streamFlow.getStreamFlow(any()))
                .willThrow(new IllegalArgumentException("A header name is required after \"header:\"."));

        assertThatThrownBy(() -> tools().traceKey("ORD-1042", null, null, null, null, null, null))
                .isInstanceOf(McpToolException.class)
                .hasMessageContaining("A header name is required");
    }
}
