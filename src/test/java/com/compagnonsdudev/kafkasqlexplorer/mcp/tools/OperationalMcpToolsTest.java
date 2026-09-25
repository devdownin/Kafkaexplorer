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
import static org.mockito.Mockito.verifyNoInteractions;

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
}
