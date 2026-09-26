// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors
package com.compagnonsdudev.kafkasqlexplorer.mcp.tools;

import com.compagnonsdudev.kafkasqlexplorer.mcp.McpProperties;
import com.compagnonsdudev.kafkasqlexplorer.mcp.contract.Coverage;
import com.compagnonsdudev.kafkasqlexplorer.mcp.contract.Measured;
import com.compagnonsdudev.kafkasqlexplorer.mcp.contract.ToolResult;
import com.compagnonsdudev.kafkasqlexplorer.mcp.guard.DlpScrubber;
import com.compagnonsdudev.kafkasqlexplorer.mcp.guard.McpScopeViolationException;
import com.compagnonsdudev.kafkasqlexplorer.mcp.guard.ToolGuard;
import com.compagnonsdudev.kafkasqlexplorer.service.KafkaAdminService;
import com.compagnonsdudev.kafkasqlexplorer.domain.TopicConsumers;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verifyNoInteractions;

@ExtendWith(MockitoExtension.class)
class OperationalReviewMcpToolsTest {
    @Mock KafkaAdminService kafka;
    @Mock ConsumerLagMcpTools lag;

    private OperationalReviewMcpTools tools() {
        McpProperties properties = new McpProperties();
        properties.setAllowedTopicPrefixes(List.of("demo."));
        return new OperationalReviewMcpTools(kafka, lag,
                new ToolGuard(properties, new DlpScrubber(properties)));
    }

    @Test
    void topic_configuration_never_invents_missing_settings_or_exposes_extra_config() throws Exception {
        given(kafka.getTopicConfigs("demo.orders")).willReturn(Map.of("cleanup.policy", "compact",
                "password", "secret"));
        given(kafka.getTopicReplication("demo.orders")).willReturn(Map.of(0,
                new KafkaAdminService.PartitionReplication(3, 2)));
        var result = tools().topicConfiguration("demo.orders").data();
        assertThat(result.configuration()).doesNotContainKey("password");
        assertThat(result.configuration().get("retention.ms").measured()).isFalse();
        assertThat(result.replication().value().get(0).inSyncReplicas()).isEqualTo(2);
    }

    @Test
    void lag_trend_needs_two_complete_readings() throws InterruptedException {
        var group = new LagView.GroupLag("orders", "STABLE", "CLASSIC", Measured.of(1),
                Measured.of(1), Measured.of(20L), Measured.unmeasured("not requested"),
                0, "BEHIND", "reading", null, List.of(new LagView.PartitionStanding(
                        0, Measured.of(80L), 100L, Measured.of(20L),
                        Measured.unmeasured("not requested"), "member-1")));
        var laterGroup = new LagView.GroupLag("orders", "STABLE", "CLASSIC", Measured.of(1),
                Measured.of(1), Measured.of(27L), Measured.unmeasured("not requested"),
                0, "BEHIND", "reading", null, List.of(new LagView.PartitionStanding(
                        0, Measured.of(83L), 110L, Measured.of(27L),
                        Measured.unmeasured("not requested"), "member-1")));
        given(lag.consumerLag("demo.orders", "orders", false, true, null))
                .willReturn(ToolResult.of(new LagView.TopicLag("demo.orders", List.of(group), 1, 1, 1, "BEHIND"),
                        Coverage.exhausted(1, 0, 1), List.of()),
                        ToolResult.of(new LagView.TopicLag("demo.orders", List.of(laterGroup), 1, 1, 1, "BEHIND"),
                                Coverage.exhausted(1, 0, 1), List.of()));
        var review = tools();
        assertThat(review.lagTrend("demo.orders", "orders").data().lagChange().measured()).isFalse();
        Thread.sleep(5);
        var later = review.lagTrend("demo.orders", "orders").data();
        assertThat(later.lagChange().value()).isEqualTo(7L);
        assertThat(later.producerRecordsPerSecond().value())
                .isGreaterThan(later.consumerRecordsPerSecond().value());
        assertThat(later.previousAtMs().value()).isLessThan(later.measuredAtMs());
    }

    @Test
    void unauthorized_topic_is_refused_before_kafka_reads() {
        var review = tools();
        assertThatThrownBy(() -> review.topicConfiguration("production.orders"))
                .isInstanceOf(McpScopeViolationException.class);
        verifyNoInteractions(kafka, lag);
    }

    @Test
    void dlq_review_counts_only_headers_seen_in_bounded_sample() throws Exception {
        var record = new ConsumerRecord<String, String>("demo.orders.dlq", 0, 7, "key", "payload");
        record.headers().add("original-topic", "demo.orders".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        record.headers().add("error-message", "bad input".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        given(kafka.getRecentRecords("demo.orders.dlq", 20)).willReturn(List.of(record));
        given(kafka.getTopicConfigs("demo.orders.dlq")).willReturn(Map.of("retention.ms", "604800000"));
        given(kafka.getTopicConfigs("demo.orders")).willReturn(Map.of("retention.ms", "86400000"));
        given(kafka.getTopicReplication("demo.orders.dlq")).willReturn(Map.of(0,
                new KafkaAdminService.PartitionReplication(3, 3)));
        given(kafka.getTopicConsumers("demo.orders.dlq", 50)).willReturn(new TopicConsumers(
                "demo.orders.dlq", List.of(), 0, 0, 0, false, true, List.of()));

        var result = tools().deadLetterReview("demo.orders.dlq", "demo.orders").data();
        assertThat(result.sourceRetentionMs().value()).isEqualTo("86400000");
        assertThat(result.recordsSampled()).isEqualTo(1);
        assertThat(result.withOriginHeader()).isEqualTo(1);
        assertThat(result.withErrorHeader()).isEqualTo(1);
        assertThat(result.consumerGroups().value()).isZero();
        assertThat(result.caveats()).anySatisfy(caveat -> assertThat(caveat).contains("does not mean no alerting"));
    }
}
