// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors
package com.compagnonsdudev.kafkasqlexplorer.mcp.tools;

import com.compagnonsdudev.kafkasqlexplorer.mcp.McpProperties;
import com.compagnonsdudev.kafkasqlexplorer.mcp.contract.StopReason;
import com.compagnonsdudev.kafkasqlexplorer.mcp.contract.ToolResult;
import com.compagnonsdudev.kafkasqlexplorer.mcp.guard.DlpScrubber;
import com.compagnonsdudev.kafkasqlexplorer.mcp.guard.McpScopeViolationException;
import com.compagnonsdudev.kafkasqlexplorer.mcp.guard.ToolGuard;
import com.compagnonsdudev.kafkasqlexplorer.service.KafkaAdminService;
import com.compagnonsdudev.kafkasqlexplorer.service.MessageFormatterService;
import com.compagnonsdudev.kafkasqlexplorer.service.SchemaInferenceService;
import org.apache.kafka.clients.consumer.ConsumerRecord;
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
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verifyNoInteractions;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class TopicMcpToolsTest {

    @Mock KafkaAdminService kafka;
    @Mock SchemaInferenceService schemas;

    private TopicMcpTools toolsScopedTo(String... prefixes) {
        McpProperties properties = new McpProperties();
        properties.setAllowedTopicPrefixes(List.of(prefixes));
        ToolGuard guard = new ToolGuard(properties, new DlpScrubber(properties));
        return new TopicMcpTools(kafka, schemas, new MessageFormatterService(), guard);
    }

    @Test
    void a_topic_outside_the_scope_is_refused_before_kafka_is_touched() {
        // Placement is the whole point: a scope check that runs after the read has already
        // disclosed what it was refusing — the topic existed, it answered, it had records.
        TopicMcpTools tools = toolsScopedTo("demo.");

        assertThatThrownBy(() -> tools.describeTopic("prod.payments"))
                .isInstanceOf(McpScopeViolationException.class);

        verifyNoInteractions(kafka);
    }

    @Test
    void a_topic_the_broker_did_not_count_is_unmeasured_not_zero() throws Exception {
        // getTopicRecordCounts omits what it could not read. Turning that omission into a 0 would
        // have an agent report a live topic as empty on the strength of a broker blip.
        given(kafka.listTopics()).willReturn(List.of("demo.orders"));
        given(kafka.getTopicRecordCounts(any())).willReturn(Map.of());
        given(kafka.getTopicsLastMessageTimestamps(any())).willReturn(Map.of());
        given(kafka.getTopicDescriptor(anyString())).willThrow(new RuntimeException("timeout"));

        var result = toolsScopedTo("*").listTopics(null, null, null);

        assertThat(result.data()).singleElement().satisfies(topic -> {
            assertThat(topic.records().measured()).isFalse();
            assertThat(topic.records().value()).isNull();
            assertThat(topic.records().reason()).isNotBlank();
        });
        assertThat(result.warnings()).anySatisfy(w -> assertThat(w.code()).isEqualTo("PARTITIONS_UNREAD"));
    }

    @Test
    void a_list_cut_by_the_ceiling_names_what_it_left_out() throws Exception {
        given(kafka.listTopics()).willReturn(List.of("demo.a", "demo.b", "demo.c"));
        given(kafka.getTopicRecordCounts(any())).willReturn(Map.of("demo.a", 4L));
        given(kafka.getTopicsLastMessageTimestamps(any())).willReturn(Map.of());
        given(kafka.getTopicDescriptor(anyString())).willThrow(new RuntimeException("no"));

        var result = toolsScopedTo("*").listTopics(null, null, 1);

        assertThat(result.data()).hasSize(1);
        assertThat(result.truncated()).isTrue();
        assertThat(result.coverage().stopReason()).isEqualTo(StopReason.TOPIC_LIMIT);
        assertThat(result.coverage().topicsNotReached()).containsExactly("demo.b", "demo.c");
    }

    @Test
    void dead_letter_topics_are_excluded_unless_asked_for() throws Exception {
        given(kafka.listTopics()).willReturn(List.of("demo.orders", "demo.orders.dlt", "demo.pay-dlq"));
        given(kafka.getTopicRecordCounts(any())).willReturn(Map.of());
        given(kafka.getTopicsLastMessageTimestamps(any())).willReturn(Map.of());
        given(kafka.getTopicDescriptor(anyString())).willThrow(new RuntimeException("no"));

        TopicMcpTools tools = toolsScopedTo("*");

        assertThat(tools.listTopics(null, null, null).data())
                .extracting(TopicView.TopicSummary::name).containsExactly("demo.orders");
        assertThat(tools.listTopics(null, true, null).data())
                .extracting(TopicView.TopicSummary::name)
                .containsExactly("demo.orders", "demo.orders.dlt", "demo.pay-dlq");
    }

    @Test
    void a_full_page_of_a_preview_says_more_records_exist() {
        // A sample that filled its cap is indistinguishable from a complete read, and the
        // difference is exactly what stops "not in this sample" being read as "not in the topic".
        given(kafka.getRecentRecords(anyString(), anyInt()))
                .willReturn(List.of(record(0, 10L, "k1", "{\"a\":1}"), record(0, 11L, "k2", "{\"a\":2}")));

        ToolResult<List<TopicView.MessagePreview>> result =
                toolsScopedTo("*").previewMessages("demo.orders", 2, null);

        assertThat(result.data()).hasSize(2);
        assertThat(result.truncated()).isTrue();
        assertThat(result.coverage().stopReason()).isEqualTo(StopReason.RECORD_LIMIT);
        assertThat(result.coverage().recordsScanned()).isEqualTo(2L);
    }

    @Test
    void a_preview_is_clamped_to_the_server_ceiling_and_says_so() {
        given(kafka.getRecentRecords(anyString(), anyInt())).willReturn(List.of());

        var result = toolsScopedTo("*").previewMessages("demo.orders", 100_000, null);

        assertThat(result.warnings()).anySatisfy(w -> {
            assertThat(w.code()).isEqualTo("BUDGET_CLAMPED");
            assertThat(w.message()).contains("50");
        });
    }

    @Test
    void a_secret_in_a_sampled_record_is_redacted() {
        given(kafka.getRecentRecords(anyString(), anyInt()))
                .willReturn(List.of(record(0, 1L, "k", "{\"password\":\"hunter2\"}")));

        var result = toolsScopedTo("*").previewMessages("demo.orders", 5, null);

        assertThat(result.data()).singleElement()
                .satisfies(preview -> assertThat(preview.value()).doesNotContain("hunter2"));
    }

    @Test
    void the_dead_letter_rule_matches_all_three_separators_as_a_suffix() {
        assertThat(TopicMcpTools.isDeadLetter("orders.dlq")).isTrue();
        assertThat(TopicMcpTools.isDeadLetter("orders-DLT")).isTrue();
        assertThat(TopicMcpTools.isDeadLetter("orders_dlt")).isTrue();
        assertThat(TopicMcpTools.isDeadLetter("dlq.orders")).isFalse();
    }

    private static ConsumerRecord<String, String> record(int partition, long offset, String key, String value) {
        return new ConsumerRecord<>("demo.orders", partition, offset, key, value);
    }
}
