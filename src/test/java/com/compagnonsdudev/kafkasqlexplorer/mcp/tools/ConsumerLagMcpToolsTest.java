// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors
package com.compagnonsdudev.kafkasqlexplorer.mcp.tools;

import com.compagnonsdudev.kafkasqlexplorer.domain.ConsumerGroupLag;
import com.compagnonsdudev.kafkasqlexplorer.domain.PartitionLag;
import com.compagnonsdudev.kafkasqlexplorer.domain.PartitionTimeLag;
import com.compagnonsdudev.kafkasqlexplorer.domain.TopicConsumers;
import com.compagnonsdudev.kafkasqlexplorer.domain.TopicTimeLag;
import com.compagnonsdudev.kafkasqlexplorer.mcp.McpProperties;
import com.compagnonsdudev.kafkasqlexplorer.mcp.contract.StopReason;
import com.compagnonsdudev.kafkasqlexplorer.mcp.contract.ToolResult;
import com.compagnonsdudev.kafkasqlexplorer.mcp.guard.DlpScrubber;
import com.compagnonsdudev.kafkasqlexplorer.mcp.guard.McpScopeViolationException;
import com.compagnonsdudev.kafkasqlexplorer.mcp.guard.McpToolException;
import com.compagnonsdudev.kafkasqlexplorer.mcp.guard.ToolGuard;
import com.compagnonsdudev.kafkasqlexplorer.service.KafkaAdminService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verifyNoInteractions;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ConsumerLagMcpToolsTest {

    @Mock KafkaAdminService kafka;

    private ConsumerLagMcpTools toolsScopedTo(String... topicPrefixes) {
        McpProperties properties = new McpProperties();
        properties.setAllowedTopicPrefixes(List.of(topicPrefixes));
        return new ConsumerLagMcpTools(kafka, new ToolGuard(properties, new DlpScrubber(properties)));
    }

    private ConsumerLagMcpTools tools() {
        return toolsScopedTo("*");
    }

    private static ConsumerGroupLag group(String id, int assignedMembers, long lag,
                                          int withoutCommit) {
        return new ConsumerGroupLag(id, "CLASSIC", "STABLE", 2, assignedMembers, true, lag,
                withoutCommit, List.of(new PartitionLag(0, 10L, 10L + lag, lag, "m", "c", "h")), null);
    }

    private void answerConsumers(TopicConsumers consumers) {
        given(kafka.getTopicConsumers(anyString(), anyInt())).willReturn(consumers);
    }

    private static TopicConsumers consumers(List<ConsumerGroupLag> groups, boolean truncated,
                                            int examined, int eligible, int inCluster) {
        return new TopicConsumers("orders", groups, examined, eligible, inCluster, truncated,
                true, List.of());
    }

    @Test
    void a_topic_outside_the_scope_is_refused_before_kafka_is_touched() {
        assertThatThrownBy(() -> toolsScopedTo("demo.").consumerLag("prod.payments", null, null, null, null))
                .isInstanceOf(McpScopeViolationException.class);

        verifyNoInteractions(kafka);
    }

    @Test
    void a_read_that_failed_is_a_failure_not_a_topic_nobody_reads() {
        // An empty list here would state that no group reads the topic on the strength of a call
        // that never answered.
        answerConsumers(TopicConsumers.unavailable("orders", "the broker timed out"));

        assertThatThrownBy(() -> tools().consumerLag("orders", null, null, null, null))
                .isInstanceOf(McpToolException.class)
                .hasMessageContaining("the broker timed out");
    }

    @Test
    void a_stalled_group_is_named_as_such_rather_than_left_to_the_numbers() {
        answerConsumers(consumers(List.of(group("etl", 0, 40_000L, 0)), false, 1, 1, 3));

        ToolResult<LagView.TopicLag> result = tools().consumerLag("orders", null, null, null, null);

        LagView.GroupLag lag = result.data().groups().get(0);
        assertThat(lag.verdict()).isEqualTo("STALLED");
        assertThat(lag.explanation()).contains("no member is assigned");
        assertThat(result.data().worstVerdict()).isEqualTo("STALLED");
    }

    @Test
    void the_worst_verdict_leads_and_an_unreadable_group_outranks_a_healthy_one() {
        ConsumerGroupLag broken = ConsumerGroupLag.failed("ghost", "CLASSIC", "describe timed out");
        answerConsumers(consumers(List.of(group("ok", 2, 0L, 0), broken), false, 2, 2, 2));

        ToolResult<LagView.TopicLag> result = tools().consumerLag("orders", null, null, null, null);

        assertThat(result.data().groups()).extracting(LagView.GroupLag::groupId)
                .containsExactly("ghost", "ok");
        assertThat(result.coverage().stopReason()).isEqualTo(StopReason.PARTIAL_FAILURE);
    }

    @Test
    void a_group_that_could_not_be_described_has_unknown_members_not_zero() {
        ConsumerGroupLag undescribed = new ConsumerGroupLag("streams-app", "STREAMS", "UNKNOWN",
                0, 0, false, 12L, 0, List.of(), null);
        answerConsumers(consumers(List.of(undescribed), false, 1, 1, 1));

        LagView.GroupLag lag = tools().consumerLag("orders", null, null, null, null).data().groups().get(0);

        assertThat(lag.members().measured()).isFalse();
        assertThat(lag.assignedMembers().measured()).isFalse();
        assertThat(lag.assignedMembers().reason()).contains("unknown rather than zero");
        // Not STALLED: the membership that would decide it was never read.
        assertThat(lag.verdict()).isEqualTo("BEHIND");
    }

    @Test
    void the_age_is_unmeasured_and_says_why_when_it_was_not_asked_for() {
        answerConsumers(consumers(List.of(group("etl", 2, 500L, 0)), false, 1, 1, 1));

        LagView.GroupLag lag = tools().consumerLag("orders", null, null, null, null).data().groups().get(0);

        assertThat(lag.lagMs().measured()).isFalse();
        assertThat(lag.lagMs().reason()).contains("includeTimeLag");
        assertThat(lag.recordLag().value()).isEqualTo(500L);
        verifyNoInteractions0();
    }

    private void verifyNoInteractions0() {
        org.mockito.Mockito.verify(kafka, org.mockito.Mockito.never())
                .getConsumerTimeLag(anyString(), anyString());
    }

    @Test
    void a_known_backlog_with_an_unreadable_age_keeps_the_half_that_worked() {
        answerConsumers(consumers(List.of(group("etl", 2, 40_000L, 0)), false, 1, 1, 1));
        given(kafka.getConsumerTimeLag(anyString(), anyString()))
                .willReturn(TopicTimeLag.unavailable("orders", "etl", "every committed offset was compacted away"));

        LagView.GroupLag lag = tools().consumerLag("orders", null, true, null, null).data().groups().get(0);

        assertThat(lag.recordLag().value()).isEqualTo(40_000L);
        assertThat(lag.lagMs().measured()).isFalse();
        assertThat(lag.lagMs().reason()).contains("compacted");
    }

    @Test
    void a_partially_measured_age_is_flagged_as_a_floor() {
        answerConsumers(consumers(List.of(group("etl", 2, 40_000L, 0)), false, 1, 1, 1));
        given(kafka.getConsumerTimeLag(anyString(), anyString())).willReturn(new TopicTimeLag(
                "orders", "etl", List.of(PartitionTimeLag.unknown(1, 5L, 90L, 85L, "compacted")),
                90_000L, 90_000L, 1, 0, 0, 1, true, null, List.of()));

        ToolResult<LagView.TopicLag> result = tools().consumerLag("orders", null, true, null, null);

        assertThat(result.data().groups().get(0).lagMs().value()).isEqualTo(90_000L);
        assertThat(result.warnings()).extracting(w -> w.code()).contains("TIME_LAG_PARTIAL");
    }

    @Test
    void a_failed_age_read_degrades_one_value_not_the_whole_diagnosis() {
        answerConsumers(consumers(List.of(group("etl", 2, 7L, 0)), false, 1, 1, 1));
        given(kafka.getConsumerTimeLag(anyString(), anyString()))
                .willThrow(new IllegalStateException("consumer read timed out"));

        LagView.GroupLag lag = tools().consumerLag("orders", null, true, null, null).data().groups().get(0);

        assertThat(lag.recordLag().value()).isEqualTo(7L);
        assertThat(lag.lagMs().reason()).contains("consumer read timed out");
    }

    @Test
    void a_capped_scan_stops_short_and_counts_what_it_did_not_examine() {
        answerConsumers(consumers(List.of(group("etl", 2, 1L, 0)), true, 1, 40, 60));

        ToolResult<LagView.TopicLag> result = tools().consumerLag("orders", null, null, null, null);

        assertThat(result.coverage().stopReason()).isEqualTo(StopReason.TOPIC_LIMIT);
        assertThat(result.coverage().topicsScanned()).isEqualTo(1);
        assertThat(result.coverage().topicsRequested()).isEqualTo(40);
        assertThat(result.warnings()).filteredOn(w -> w.code().equals("GROUPS_NOT_EXAMINED"))
                .singleElement().satisfies(w -> assertThat(w.message())
                        .contains("39 consumer group(s) beyond the cap"));
        assertThat(result.truncated()).isTrue();
    }

    @Test
    void a_named_group_that_holds_no_offset_is_said_to_be_missing_not_silently_empty() {
        answerConsumers(consumers(List.of(group("etl", 2, 1L, 0)), false, 1, 1, 1));

        ToolResult<LagView.TopicLag> result = tools().consumerLag("orders", "billing", null, null, null);

        assertThat(result.data().groups()).isEmpty();
        assertThat(result.warnings()).extracting(w -> w.code()).contains("GROUP_NOT_FOUND");
        assertThat(result.data().worstVerdict()).isNull();
    }

    @Test
    void a_group_outside_the_group_scope_is_refused_before_kafka_is_touched() {
        McpProperties properties = new McpProperties();
        properties.setAllowedGroupPrefixes(List.of("app."));
        ConsumerLagMcpTools tools = new ConsumerLagMcpTools(kafka,
                new ToolGuard(properties, new DlpScrubber(properties)));

        assertThatThrownBy(() -> tools.consumerLag("orders", "internal.audit", null, null, null))
                .isInstanceOf(McpScopeViolationException.class);

        verifyNoInteractions(kafka);
    }

    @Test
    void per_partition_detail_is_withheld_until_it_is_asked_for() {
        // Fifty partitions across ten groups is five hundred rows for a question the verdict
        // usually answers.
        answerConsumers(consumers(List.of(group("etl", 2, 5L, 0)), false, 1, 1, 1));

        LagView.GroupLag lag = tools().consumerLag("orders", null, null, null, null)
                .data().groups().get(0);

        assertThat(lag.partitions()).isEmpty();
    }

    @Test
    void a_partition_with_no_commit_holds_no_position_rather_than_offset_zero() {
        // Where the summary misleads most: it contributes nothing to the total, so a group stuck on
        // one partition of forty reads as very slightly behind.
        ConsumerGroupLag stuck = new ConsumerGroupLag("etl", "CLASSIC", "STABLE", 2, 2, true, 4L, 1,
                List.of(new PartitionLag(0, 6L, 10L, 4L, "m-1", "c", "h"),
                        new PartitionLag(1, null, 900L, null, null, null, null)), null);
        answerConsumers(consumers(List.of(stuck), false, 1, 1, 1));

        List<LagView.PartitionStanding> partitions = tools()
                .consumerLag("orders", null, null, true, null).data().groups().get(0).partitions();

        assertThat(partitions).hasSize(2);
        assertThat(partitions.get(0).recordLag().value()).isEqualTo(4L);
        assertThat(partitions.get(0).assignedTo()).isEqualTo("m-1");
        assertThat(partitions.get(1).committedOffset().measured()).isFalse();
        assertThat(partitions.get(1).committedOffset().reason()).contains("never committed");
        assertThat(partitions.get(1).recordLag().measured()).isFalse();
    }

    @Test
    void a_per_partition_age_is_read_once_and_cannot_contradict_the_total() {
        answerConsumers(consumers(List.of(group("etl", 2, 4L, 0)), false, 1, 1, 1));
        given(kafka.getConsumerTimeLag(anyString(), anyString())).willReturn(new TopicTimeLag(
                "orders", "etl",
                List.of(new PartitionTimeLag(0, 6L, 10L, 4L, 90_000L, 1L, null)),
                90_000L, 90_000L, 1, 0, 0, 0, true, null, List.of()));

        LagView.GroupLag lag = tools().consumerLag("orders", null, true, true, null)
                .data().groups().get(0);

        assertThat(lag.lagMs().value()).isEqualTo(90_000L);
        assertThat(lag.partitions().get(0).lagMs().value()).isEqualTo(90_000L);
        // One read, used twice — the detail is drawn from the same measurement as the sum.
        org.mockito.Mockito.verify(kafka, org.mockito.Mockito.times(1))
                .getConsumerTimeLag(anyString(), anyString());
    }

    @Test
    void a_partition_whose_record_was_compacted_keeps_its_offsets_and_loses_only_its_age() {
        answerConsumers(consumers(List.of(group("etl", 2, 4L, 0)), false, 1, 1, 1));
        given(kafka.getConsumerTimeLag(anyString(), anyString())).willReturn(new TopicTimeLag(
                "orders", "etl",
                List.of(PartitionTimeLag.unknown(0, 6L, 10L, 4L, "the record at offset 6 was compacted")),
                null, null, 0, 0, 0, 1, true, null, List.of()));

        LagView.PartitionStanding standing = tools().consumerLag("orders", null, true, true, null)
                .data().groups().get(0).partitions().get(0);

        assertThat(standing.recordLag().value()).isEqualTo(4L);
        assertThat(standing.lagMs().measured()).isFalse();
        assertThat(standing.lagMs().reason()).contains("compacted");
    }

    @Test
    void partitions_with_no_commit_are_counted_and_the_total_is_called_a_floor() {
        answerConsumers(consumers(List.of(group("etl", 2, 12L, 5)), false, 1, 1, 1));

        LagView.GroupLag lag = tools().consumerLag("orders", null, null, null, null).data().groups().get(0);

        assertThat(lag.verdict()).isEqualTo("PARTIAL");
        assertThat(lag.partitionsWithoutCommit()).isEqualTo(5);
        assertThat(lag.explanation()).contains("floor");
    }
}
