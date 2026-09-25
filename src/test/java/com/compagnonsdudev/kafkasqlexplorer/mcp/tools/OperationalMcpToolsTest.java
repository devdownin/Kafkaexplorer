// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors
package com.compagnonsdudev.kafkasqlexplorer.mcp.tools;

import com.compagnonsdudev.kafkasqlexplorer.domain.TopicActivity;
import com.compagnonsdudev.kafkasqlexplorer.domain.TopicActivityResponse;
import com.compagnonsdudev.kafkasqlexplorer.mcp.McpProperties;
import com.compagnonsdudev.kafkasqlexplorer.mcp.contract.Coverage;
import com.compagnonsdudev.kafkasqlexplorer.mcp.contract.Measured;
import com.compagnonsdudev.kafkasqlexplorer.mcp.contract.StopReason;
import com.compagnonsdudev.kafkasqlexplorer.mcp.contract.ToolResult;
import com.compagnonsdudev.kafkasqlexplorer.mcp.guard.DlpScrubber;
import com.compagnonsdudev.kafkasqlexplorer.mcp.guard.McpScopeViolationException;
import com.compagnonsdudev.kafkasqlexplorer.mcp.guard.ToolGuard;
import com.compagnonsdudev.kafkasqlexplorer.service.KafkaAdminService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.times;
import static org.mockito.ArgumentMatchers.eq;

@ExtendWith(MockitoExtension.class)
class OperationalMcpToolsTest {

    @Mock KafkaAdminService kafka;
    @Mock ConsumerLagMcpTools lag;

    private OperationalMcpTools tools(String... prefixes) {
        McpProperties properties = new McpProperties();
        properties.setAllowedTopicPrefixes(List.of(prefixes));
        ToolGuard guard = new ToolGuard(properties, new DlpScrubber(properties));
        return new OperationalMcpTools(kafka, lag, guard);
    }

    @Test
    void topic_activity_keeps_zero_distinct_from_unmeasured() {
        given(kafka.getTopicActivity(any(), anyLong(), anyInt(), anyInt())).willReturn(
                new TopicActivityResponse(
                        Map.of("orders", new TopicActivity(
                                "orders", 0L, 120_000L, 30_000L,
                                List.of(4L, 0L, 3L, 0L), 7L,
                                null, 1, 1, true, null)),
                        0L, 120_000L, 30_000L, 4, true, List.of()));
        given(kafka.getTopicsLastMessageTimestamps(any())).willReturn(Map.of());

        var result = tools("*").topicActivity(List.of("orders"), 120_000L, 4);

        assertThat(result.data()).singleElement().satisfies(activity -> {
            assertThat(activity.offsetsProduced()).isEqualTo(7L);
            assertThat(activity.silentBuckets()).isEqualTo(2);
            assertThat(activity.lastMessageAt().measured()).isFalse();
        });
        assertThat(result.coverage().stopReason()).isEqualTo(StopReason.EXHAUSTED);
    }

    @Test
    void missing_activity_is_named_in_coverage() {
        given(kafka.getTopicActivity(any(), anyLong(), anyInt(), anyInt())).willReturn(
                new TopicActivityResponse(
                        Map.of("orders", new TopicActivity(
                                "orders", 0L, 120_000L, 30_000L,
                                List.of(1L, 1L, 1L, 1L), 4L,
                                null, 1, 1, true, null)),
                        0L, 120_000L, 30_000L, 4, true,
                        List.of("payments was left out by the lookup budget")));
        given(kafka.getTopicsLastMessageTimestamps(any())).willReturn(Map.of());

        var result = tools("*").topicActivity(List.of("orders", "payments"), 120_000L, 4);

        assertThat(result.truncated()).isTrue();
        assertThat(result.coverage().topicsNotReached()).containsExactly("payments");
        assertThat(result.coverage().stopReason()).isEqualTo(StopReason.PARTIAL_FAILURE);
    }

    @Test
    void flow_health_identifies_the_largest_measured_drop() {
        given(kafka.getTopicActivity(any(), anyLong(), anyInt(), anyInt())).willReturn(
                new TopicActivityResponse(
                        Map.of(
                                "a", activity("a", 100L),
                                "b", activity("b", 80L),
                                "c", activity("c", 20L)),
                        0L, 120_000L, 30_000L, 4, true, List.of()));
        given(kafka.getTopicsLastMessageTimestamps(any())).willReturn(Map.of());

        var result = tools("*").flowHealth(List.of("a", "b", "c"), 120_000L, 4);

        assertThat(result.data().status()).isEqualTo("ERROR");
        assertThat(result.data().bottleneck()).isEqualTo("b -> c");
        assertThat(result.data().largestDropRate().value()).isEqualTo(0.75d);
    }

    @Test
    void compare_windows_uses_one_aligned_read_for_both_periods_and_stage_drops() {
        given(kafka.getTopicActivity(List.of("input", "output"), 120_000L, 4, 20_000))
                .willReturn(new TopicActivityResponse(Map.of(
                        "input", series("input", List.of(40L, 60L, 20L, 30L)),
                        "output", series("output", List.of(30L, 50L, 10L, 10L))),
                        0L, 120_000L, 30_000L, 4, true, List.of()));
        given(kafka.getTopicsLastMessageTimestamps(any())).willReturn(Map.of());

        var result = tools("*").compareWindows(List.of("input", "output"), 60_000L, 2);

        assertThat(result.data().previousStartMs()).isZero();
        assertThat(result.data().previousEndMs()).isEqualTo(60_000L);
        assertThat(result.data().recentStartMs()).isEqualTo(60_000L);
        assertThat(result.data().recentEndMs()).isEqualTo(120_000L);
        assertThat(result.data().topics()).hasSize(2);
        var input = result.data().topics().getFirst();
        assertThat(input.previous().value().offsetsProduced()).isEqualTo(100L);
        assertThat(input.recent().value().offsetsProduced()).isEqualTo(50L);
        assertThat(input.previous().value().offsetsPerSecond()).isCloseTo(100.0 / 60,
                org.assertj.core.data.Offset.offset(0.00001));
        assertThat(input.activityChangePercent().value()).isEqualTo(-50.0);
        assertThat(input.activityTrend()).isEqualTo("DECREASING");
        assertThat(input.lagChange().measured()).isFalse();
        assertThat(result.data().stages()).singleElement().satisfies(drop -> {
            assertThat(drop.previousDropPercent().value()).isEqualTo(20.0);
            assertThat(drop.recentDropPercent().value()).isEqualTo(60.0);
            assertThat(drop.changePoints().value()).isEqualTo(40.0);
        });
        verify(kafka).getTopicActivity(List.of("input", "output"), 120_000L, 4, 20_000);
    }

    @Test
    void compare_windows_keeps_zero_baseline_and_incomplete_topic_unmeasured() {
        given(kafka.getTopicActivity(any(), anyLong(), anyInt(), anyInt())).willReturn(
                new TopicActivityResponse(Map.of(
                        "input", series("input", List.of(0L, 0L, 3L, 3L)),
                        "output", new TopicActivity("output", 0L, 120_000L, 30_000L,
                                List.of(0L, 0L, 0L, 0L), 0L, null, 0, 1, true, "partition unavailable")),
                        0L, 120_000L, 30_000L, 4, true, List.of()));
        given(kafka.getTopicsLastMessageTimestamps(any())).willReturn(Map.of());

        var result = tools("*").compareWindows(List.of("input", "output"), 60_000L, 2);

        assertThat(result.truncated()).isTrue();
        assertThat(result.data().topics().getFirst().activityTrend()).isEqualTo("STARTED");
        assertThat(result.data().topics().getFirst().activityChangePercent().measured()).isFalse();
        assertThat(result.data().topics().get(1).previous().measured()).isFalse();
        assertThat(result.data().stages().getFirst().changePoints().measured()).isFalse();
    }

    @Test
    void compare_windows_rejects_out_of_scope_topics_before_kafka_io() {
        assertThatThrownBy(() -> tools("demo.").compareWindows(
                List.of("demo.input", "prod.output"), 60_000L, 2))
                .isInstanceOf(McpScopeViolationException.class);
        verifyNoInteractions(kafka, lag);
    }

    @Test
    void compare_windows_names_unread_topic_without_reporting_zero() {
        given(kafka.getTopicActivity(any(), anyLong(), anyInt(), anyInt())).willReturn(
                new TopicActivityResponse(Map.of("input", series("input", List.of(1L, 1L, 1L, 1L))),
                        0L, 120_000L, 30_000L, 4, true,
                        List.of("output not reached within lookup budget")));
        given(kafka.getTopicsLastMessageTimestamps(any())).willReturn(Map.of());

        var result = tools("*").compareWindows(List.of("input", "output"), 60_000L, 2);

        assertThat(result.coverage().topicsNotReached()).containsExactly("output");
        assertThat(result.data().topics().get(1).previous().measured()).isFalse();
        assertThat(result.data().topics().get(1).recent().measured()).isFalse();
        assertThat(result.data().topics().get(1).activityTrend()).isEqualTo("UNKNOWN");
    }

    @Test
    void compare_process_state_never_calls_an_unknown_fresh_state_an_improvement() {
        given(kafka.getTopicActivity(any(), anyLong(), anyInt(), anyInt())).willReturn(
                new TopicActivityResponse(
                        Map.of("orders", new TopicActivity(
                                "orders", 0L, 120_000L, 30_000L,
                                List.of(), 0L, null, 0, 1, false, "partition read failed")),
                        0L, 120_000L, 30_000L, 4, true, List.of()));
        given(kafka.getTopicsLastMessageTimestamps(any())).willReturn(Map.of());

        var result = tools("*").compareProcessState(
                "orders", List.of("orders"), List.of(), "ERROR", 100L, 0L, 120_000L);

        assertThat(result.data().afterStatus()).isEqualTo("UNKNOWN");
        assertThat(result.data().verdict()).isEqualTo("UNKNOWN");
    }

    @Test
    void identified_baseline_compares_two_distinct_complete_windows() {
        given(kafka.getTopicActivity(any(), anyLong(), anyInt(), anyInt())).willReturn(
                response("orders", 0L, 120_000L, 0L),
                response("orders", 120_001L, 240_001L, 8L));
        given(kafka.getTopicsLastMessageTimestamps(any())).willReturn(Map.of());
        var operational = tools("*");
        var before = operational.processHealth("orders", List.of("orders"), List.of(), 120_000L, 4);
        assertThat(before.data().measurementId()).isNotBlank();

        var after = operational.compareProcessState("orders", null, List.of("orders"), List.of(),
                null, null, null, 120_000L, before.data().measurementId());

        assertThat(after.data().beforeMeasurementId()).isEqualTo(before.data().measurementId());
        assertThat(after.data().afterMeasurementId()).isNotEqualTo(before.data().measurementId());
        assertThat(after.data().verdict()).isEqualTo("RESOLVED");
        verify(kafka, times(2)).getTopicActivity(any(), anyLong(), anyInt(), anyInt());
    }

    @Test
    void snapshot_from_another_process_is_refused_without_a_new_measurement() {
        given(kafka.getTopicActivity(any(), anyLong(), anyInt(), anyInt()))
                .willReturn(response("orders", 0L, 120_000L, 2L));
        given(kafka.getTopicsLastMessageTimestamps(any())).willReturn(Map.of());
        var operational = tools("*");
        String id = operational.processHealth("orders", List.of("orders"), List.of(), 120_000L, 4)
                .data().measurementId();

        assertThatThrownBy(() -> operational.compareProcessState("other", null,
                List.of("orders"), List.of(), null, null, null, 120_000L, id))
                .hasMessageContaining("different process");
        verify(kafka).getTopicActivity(any(), anyLong(), anyInt(), anyInt());
    }

    @Test
    void a_non_later_window_cannot_verify_an_action() {
        given(kafka.getTopicActivity(any(), anyLong(), anyInt(), anyInt()))
                .willReturn(response("orders", 0L, 120_000L, 2L));
        given(kafka.getTopicsLastMessageTimestamps(any())).willReturn(Map.of());
        var operational = tools("*");
        String id = operational.processHealth("orders", List.of("orders"), List.of(), 120_000L, 4)
                .data().measurementId();

        var comparison = operational.compareProcessState("orders", null, List.of("orders"),
                List.of(), null, null, null, 120_000L, id);
        assertThat(comparison.data().verdict()).isEqualTo("UNKNOWN");
    }

    @Test
    void typed_process_stages_bind_each_consumer_to_its_topic() {
        given(kafka.getTopicActivity(any(), anyLong(), anyInt(), anyInt())).willReturn(
                new TopicActivityResponse(
                        Map.of("orders.in", activity("orders.in", 10L),
                               "orders.out", activity("orders.out", 10L)),
                        0L, 120_000L, 30_000L, 4, true, List.of()));
        given(kafka.getTopicsLastMessageTimestamps(any())).willReturn(Map.of());

        LagView.GroupLag group = new LagView.GroupLag(
                "orders-worker", "STABLE", "CLASSIC",
                Measured.of(1), Measured.of(1), Measured.of(0L), Measured.of(0L),
                0, "CAUGHT_UP", "caught up", null, List.of());
        given(lag.consumerLag(eq("orders.out"), eq("orders-worker"), eq(true), eq(false), eq(Integer.MAX_VALUE)))
                .willReturn(new ToolResult<>(
                        new LagView.TopicLag("orders.out", List.of(group), 1, 1, 1, "CAUGHT_UP"),
                        new Coverage(1, 1, List.of(), 0L, 1L, StopReason.EXHAUSTED, null, null, null),
                        List.of(), false));

        var result = tools("*").processHealth(
                "orders",
                List.of(
                        new OperationalView.ProcessStage("input", "orders.in", null),
                        new OperationalView.ProcessStage("output", "orders.out", "orders-worker")),
                null, null, 120_000L, 4);

        assertThat(result.data().consumers()).singleElement()
                .satisfies(diagnosis -> {
                    assertThat(diagnosis.topic()).isEqualTo("orders.out");
                    assertThat(diagnosis.groupId()).isEqualTo("orders-worker");
                });
        verify(lag).consumerLag("orders.out", "orders-worker", true, false, Integer.MAX_VALUE);
    }

    @Test
    void typed_and_legacy_process_contracts_cannot_be_mixed() {
        assertThatThrownBy(() -> tools("*").processHealth(
                "orders",
                List.of(new OperationalView.ProcessStage("input", "orders.in", null)),
                List.of("legacy-topic"), null, null, null))
                .isInstanceOf(com.compagnonsdudev.kafkasqlexplorer.mcp.guard.McpToolException.class)
                .hasMessageContaining("either typed stages or legacy");
    }

    @Test
    void scope_is_checked_before_any_operational_measurement() {
        assertThatThrownBy(() -> tools("demo.").topicActivity(List.of("prod.orders"), null, null))
                .isInstanceOf(McpScopeViolationException.class);

        verifyNoInteractions(kafka, lag);
    }

    private static TopicActivity activity(String topic, long total) {
        return new TopicActivity(topic, 0L, 120_000L, 30_000L,
                List.of(total / 4, total / 4, total / 4, total - 3 * (total / 4)),
                total, null, 1, 1, true, null);
    }

    private static TopicActivity series(String topic, List<Long> counts) {
        return new TopicActivity(topic, 0L, 120_000L, 30_000L, counts,
                counts.stream().mapToLong(Long::longValue).sum(), null, 1, 1, true, null);
    }

    private static TopicActivityResponse response(String topic, long start, long end, long count) {
        return new TopicActivityResponse(Map.of(topic,
                new TopicActivity(topic, start, end, 30_000L, List.of(count), count,
                        null, 1, 1, true, null)), start, end, 30_000L, 4, true, List.of());
    }
}
