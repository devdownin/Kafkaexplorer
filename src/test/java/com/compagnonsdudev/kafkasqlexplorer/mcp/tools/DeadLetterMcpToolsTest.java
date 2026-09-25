// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors
package com.compagnonsdudev.kafkasqlexplorer.mcp.tools;

import com.compagnonsdudev.kafkasqlexplorer.mcp.McpProperties;
import com.compagnonsdudev.kafkasqlexplorer.mcp.contract.Coverage;
import com.compagnonsdudev.kafkasqlexplorer.mcp.contract.Measured;
import com.compagnonsdudev.kafkasqlexplorer.mcp.contract.StopReason;
import com.compagnonsdudev.kafkasqlexplorer.mcp.contract.ToolResult;
import com.compagnonsdudev.kafkasqlexplorer.mcp.guard.DlpScrubber;
import com.compagnonsdudev.kafkasqlexplorer.mcp.guard.McpScopeViolationException;
import com.compagnonsdudev.kafkasqlexplorer.mcp.guard.ToolGuard;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verifyNoInteractions;

@ExtendWith(MockitoExtension.class)
class DeadLetterMcpToolsTest {

    @Mock OperationalMcpTools operational;
    @Mock TopicMcpTools topics;

    private DeadLetterMcpTools tools(String prefix) {
        McpProperties properties = new McpProperties();
        properties.setAllowedTopicPrefixes(List.of(prefix));
        return new DeadLetterMcpTools(operational, topics,
                new ToolGuard(properties, new DlpScrubber(properties)));
    }

    @Test
    void compares_aligned_complete_windows_and_marks_sample_counts_as_bounded() {
        var queue = activity("demo.orders.dlt", List.of(1L, 2L, 3L, 4L), true);
        var source = activity("demo.orders", List.of(100L, 100L, 100L, 100L), true);
        given(operational.topicActivity(List.of("demo.orders.dlt", "demo.orders"), 120_000L, 12))
                .willReturn(result(List.of(queue, source), true));
        given(topics.previewMessages("demo.orders.dlt", 20, "latest"))
                .willReturn(sample(List.of(
                        new TopicView.MessagePreview(1, 7, 50L, "order-1", "{\"failure_reason\":\"timeout\"}"),
                        new TopicView.MessagePreview(1, 8, 60L, "order-1", "{\"failure_reason\":\"timeout\"}"),
                        new TopicView.MessagePreview(2, 9, 70L, "order-2", "malformed")), false));

        var result = tools("demo.").diagnose("demo.orders.dlt", "demo.orders", 120_000L, null);

        assertThat(result.data().arrivals().value()).isEqualTo(10L);
        assertThat(result.data().sharePercent().value()).isEqualTo(2.5);
        assertThat(result.data().arrivalTrend()).isEqualTo("INCREASING");
        assertThat(result.data().previousHalfArrivals().value()).isEqualTo(3L);
        assertThat(result.data().recentHalfArrivals().value()).isEqualTo(7L);
        assertThat(result.data().errorSignatures()).containsExactly(
                new DeadLetterView.SampleCount("timeout", 2),
                new DeadLetterView.SampleCount("unclassified in sample", 1));
        assertThat(result.data().repeatedKeys()).containsExactly(new DeadLetterView.SampleCount("order-1", 2));
        assertThat(result.data().sampledPartitions()).containsExactly(
                new DeadLetterView.PartitionCount(1, 2), new DeadLetterView.PartitionCount(2, 1));
        assertThat(result.data().firstSampleAt().value()).isEqualTo(50L);
        assertThat(result.coverage().recordsScanned()).isEqualTo(3L);
    }

    @Test
    void no_source_or_zero_source_never_becomes_zero_failure_share() {
        var queue = activity("demo.orders.dlq", List.of(0L, 0L), true);
        given(operational.topicActivity(List.of("demo.orders.dlq"), null, 12))
                .willReturn(result(List.of(queue), true));
        given(topics.previewMessages("demo.orders.dlq", 20, "latest"))
                .willReturn(sample(List.of(), false));
        var absent = tools("demo.").diagnose("demo.orders.dlq", null, null, null);
        assertThat(absent.data().arrivals().value()).isZero();
        assertThat(absent.data().sourceProduced().measured()).isFalse();
        assertThat(absent.data().sharePercent().measured()).isFalse();
        assertThat(absent.data().firstSampleAt().measured()).isFalse();

        var zero = activity("demo.orders", List.of(0L, 0L), true);
        given(operational.topicActivity(List.of("demo.orders.dlq", "demo.orders"), null, 12))
                .willReturn(result(List.of(queue, zero), true));
        var withZeroSource = tools("demo.").diagnose("demo.orders.dlq", "demo.orders", null, null);
        assertThat(withZeroSource.data().sourceProduced().value()).isZero();
        assertThat(withZeroSource.data().sharePercent().measured()).isFalse();
    }

    @Test
    void incomplete_queue_is_not_silently_counted_as_zero() {
        var incomplete = activity("demo.orders.dlq", List.of(0L, 0L), false);
        given(operational.topicActivity(List.of("demo.orders.dlq"), null, 12))
                .willReturn(result(List.of(incomplete), false));
        given(topics.previewMessages("demo.orders.dlq", 20, "latest"))
                .willReturn(sample(List.of(), false));

        var result = tools("demo.").diagnose("demo.orders.dlq", null, null, null);
        assertThat(result.data().arrivals().measured()).isFalse();
        assertThat(result.data().windowStartMs().measured()).isFalse();
        assertThat(result.data().arrivalTrend()).isEqualTo("UNKNOWN");
    }

    @Test
    void scope_is_checked_before_any_measurement_or_preview() {
        assertThatThrownBy(() -> tools("demo.").diagnose(
                "demo.orders.dlq", "production.orders", null, null))
                .isInstanceOf(McpScopeViolationException.class);
        verifyNoInteractions(operational, topics);
    }

    private static OperationalView.TopicActivity activity(String topic, List<Long> counts, boolean complete) {
        return new OperationalView.TopicActivity(topic, 0L, 120_000L, 30_000L, counts,
                counts.stream().mapToLong(Long::longValue).sum(),
                Measured.unmeasured("no timestamp"), 0, complete ? 1 : 0, 1, complete, null);
    }

    private static ToolResult<List<OperationalView.TopicActivity>> result(
            List<OperationalView.TopicActivity> activities, boolean complete) {
        var coverage = new Coverage(activities.size(), activities.size(), List.of(), 0L, 1L,
                complete ? StopReason.EXHAUSTED : StopReason.PARTIAL_FAILURE,
                Instant.EPOCH, Instant.ofEpochMilli(120_000L), null);
        return new ToolResult<>(activities, coverage, List.of(), !complete);
    }

    private static ToolResult<List<TopicView.MessagePreview>> sample(
            List<TopicView.MessagePreview> records, boolean truncated) {
        var coverage = new Coverage(1, 1, List.of(), records.size(), 1L,
                truncated ? StopReason.RECORD_LIMIT : StopReason.EXHAUSTED, null, null, null);
        return new ToolResult<>(records, coverage, List.of(), truncated);
    }
}
