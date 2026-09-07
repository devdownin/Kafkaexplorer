// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors
package com.compagnonsdudev.kafkasqlexplorer.mcp.tools;

import com.compagnonsdudev.kafkasqlexplorer.domain.ConsumerGroupLag;
import com.compagnonsdudev.kafkasqlexplorer.domain.TopicConsumers;
import com.compagnonsdudev.kafkasqlexplorer.domain.TopicDescriptor;
import com.compagnonsdudev.kafkasqlexplorer.mcp.contract.Coverage;
import com.compagnonsdudev.kafkasqlexplorer.mcp.contract.Measured;
import com.compagnonsdudev.kafkasqlexplorer.mcp.contract.StopReason;
import com.compagnonsdudev.kafkasqlexplorer.mcp.contract.ToolResult;
import com.compagnonsdudev.kafkasqlexplorer.mcp.contract.Warning;
import com.compagnonsdudev.kafkasqlexplorer.mcp.guard.McpErrorCode;
import com.compagnonsdudev.kafkasqlexplorer.mcp.guard.McpToolException;
import com.compagnonsdudev.kafkasqlexplorer.mcp.guard.ToolGuard;
import com.compagnonsdudev.kafkasqlexplorer.mcp.observability.ToolCategory;
import com.compagnonsdudev.kafkasqlexplorer.service.KafkaAdminService;
import com.compagnonsdudev.kafkasqlexplorer.service.MessageFormatterService;
import com.compagnonsdudev.kafkasqlexplorer.service.SchemaInferenceService;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * The three tools an agent starts from: what topics exist, what one of them looks like, and a
 * bounded sample of what is in it.
 *
 * <p>An adapter and nothing more. Every number comes from {@code KafkaAdminService}, on the caches
 * and timeouts the UI already uses, so an agent and an operator looking at the same cluster get the
 * same answer. The value this class adds is the envelope: what was read, what was not, and which
 * of the numbers are measurements rather than defaults.
 *
 * <p><b>{@code kex_preview_messages} is not {@code consume_messages}.</b> It is bounded by
 * {@code explorer.mcp.hard-max-records}, scrubbed, and it returns the partition and offset of every
 * record so the sample can be re-read. The unbounded consume that most Kafka MCP servers expose is
 * what makes an agent pull thousands of records into its context and correlate them itself —
 * expensively, and with nothing in the reply saying what it missed.
 */
public class TopicMcpTools implements ReadOnlyMcpTools {

    /** Same rule as the UI's {@code topicKinds.ts}: a suffix, and all three separators. */
    private static final Pattern DEAD_LETTER_SUFFIX = Pattern.compile("(?i)[._-](dlt|dlq)$");

    private final KafkaAdminService kafka;
    private final SchemaInferenceService schemas;
    private final MessageFormatterService formatter;
    private final ToolGuard guard;

    public TopicMcpTools(KafkaAdminService kafka, SchemaInferenceService schemas,
                         MessageFormatterService formatter, ToolGuard guard) {
        this.kafka = kafka;
        this.schemas = schemas;
        this.formatter = formatter;
        this.guard = guard;
    }

    @Override
    public ToolCategory category() {
        return ToolCategory.EXPLORATION;
    }

    @McpTool(name = "kex_list_topics", annotations = @McpTool.McpAnnotations(
            readOnlyHint = true, destructiveHint = false, openWorldHint = true),
            description = """
            List the Kafka topics of the connected cluster with their partition count, readable
            record count and last activity.

            Always read `coverage`: when `stopReason` is not EXHAUSTED the list is a prefix of the
            cluster, not the cluster, and `topicsNotReached` names what was left out. A record
            count arrives as {"value": N, "measured": true} or as
            {"measured": false, "reason": "..."} — the second is NOT zero, it means the broker did
            not answer for that topic, and a topic reported as empty on that basis is a wrong
            answer, not a cautious one.""")
    public ToolResult<List<TopicView.TopicSummary>> listTopics(
            @McpToolParam(required = false, description = "Only topics whose name starts with this")
            String prefix,
            @McpToolParam(required = false, description = "Include dead-letter topics (*.dlt / *.dlq). Default false.")
            Boolean includeDeadLetters,
            @McpToolParam(required = false, description = "Maximum topics to return; clamped by the server ceiling")
            Integer limit) {

        long startedAt = System.currentTimeMillis();
        int cap = guard.clampTopics(limit);
        List<Warning> warnings = new ArrayList<>(guard.clampWarnings("limit", limit, cap));

        List<String> all;
        try {
            all = kafka.listTopics();
        } catch (Exception e) {
            throw dependencyUnavailable("listing topics", e);
        }

        List<String> matching = all.stream()
                .filter(name -> prefix == null || prefix.isBlank() || name.startsWith(prefix))
                .filter(name -> Boolean.TRUE.equals(includeDeadLetters) || !isDeadLetter(name))
                .sorted()
                .toList();
        // Scope is applied to what would be returned, not by refusing the call: an agent asking
        // "what is there?" on a scoped deployment should get its slice, not a -32041 it cannot act on.
        List<String> inScope = matching.stream().filter(this::inTopicScope).toList();
        List<String> selected = inScope.stream().limit(cap).toList();

        Map<String, Long> counts = kafka.getTopicRecordCounts(selected);
        Map<String, Long> lastSeen = kafka.getTopicsLastMessageTimestamps(selected);
        Map<String, Integer> partitions = partitionCounts(selected, warnings);

        List<TopicView.TopicSummary> summaries = selected.stream()
                .map(name -> new TopicView.TopicSummary(
                        name,
                        partitions.getOrDefault(name, 0),
                        Measured.ofNullable(counts.get(name),
                                "the broker did not return offsets for this topic within the read timeout"),
                        Measured.ofNullable(lastSeen.get(name),
                                "no record carried a timestamp, or the topic holds nothing readable"),
                        isDeadLetter(name)))
                .toList();

        boolean truncated = inScope.size() > selected.size();
        Coverage coverage = truncated
                ? Coverage.partial(inScope.size(), selected.size(),
                        inScope.stream().skip(selected.size()).toList(),
                        0L, System.currentTimeMillis() - startedAt, StopReason.TOPIC_LIMIT, null)
                : Coverage.exhausted(selected.size(), 0L, System.currentTimeMillis() - startedAt);

        if (truncated) {
            warnings.add(Warning.warn("TOPIC_LIMIT",
                    "%d topics matched, %d returned; the rest are named in coverage.topicsNotReached"
                            .formatted(inScope.size(), selected.size())));
        }
        return new ToolResult<>(summaries, coverage, warnings, truncated);
    }

    @McpTool(name = "kex_describe_topic", annotations = @McpTool.McpAnnotations(
            readOnlyHint = true, destructiveHint = false, openWorldHint = true),
            description = """
            Describe one topic: partitions, earliest and latest offset per partition, readable
            record count, the payload format inferred from a sample, and the consumer groups seen
            on it.

            `detectedFormat` is inferred from sampled records, not declared by the broker: on a
            topic mixing formats it reports the one that dominated the sample. `records` is
            end-minus-beginning, so retention and compaction are already applied — it is what a
            query can still read, not what was ever produced.""")
    public ToolResult<TopicView.TopicDetail> describeTopic(
            @McpToolParam(description = "Topic name") String topic) {

        long startedAt = System.currentTimeMillis();
        guard.checkTopicScope(topic);

        TopicDescriptor descriptor;
        try {
            descriptor = kafka.getTopicDescriptor(topic);
        } catch (Exception e) {
            throw dependencyUnavailable("describing topic " + topic, e);
        }

        List<Warning> warnings = new ArrayList<>();
        Map<String, Long> counts = kafka.getTopicRecordCounts(List.of(topic));

        List<String> groups = List.of();
        try {
            TopicConsumers consumers = kafka.getTopicConsumers(topic, 50);
            groups = consumers == null || !consumers.available()
                    ? List.of()
                    : consumers.groups().stream().map(ConsumerGroupLag::groupId).toList();
        } catch (RuntimeException e) {
            // A group read that failed must not empty the description: the offsets are the answer,
            // the groups are context, and an empty list here would read as "nobody consumes this".
            warnings.add(Warning.warn("GROUPS_UNREAD",
                    "consumer groups could not be read for " + topic + ": " + rootMessage(e)));
        }

        TopicView.TopicDetail detail = new TopicView.TopicDetail(
                descriptor.name(),
                descriptor.partitions(),
                descriptor.minOffsets(),
                descriptor.maxOffsets(),
                Measured.ofNullable(counts.get(topic),
                        "the broker did not return offsets for this topic within the read timeout"),
                descriptor.detectedFormat() == null ? null : descriptor.detectedFormat().name(),
                groups,
                isDeadLetter(topic));

        return ToolResult.of(detail,
                Coverage.exhausted(1, 0L, System.currentTimeMillis() - startedAt), warnings);
    }

    @McpTool(name = "kex_preview_messages", annotations = @McpTool.McpAnnotations(
            readOnlyHint = true, destructiveHint = false, openWorldHint = true),
            description = """
            Return a small, bounded sample of records from one topic, pretty-printed, with
            credentials and obvious personal data redacted.

            This is a sample, never the topic: `coverage.recordsScanned` says how many records were
            read and `stopReason` RECORD_LIMIT means more exist that were not looked at. Do not
            conclude a value is absent from a topic because it is absent from this sample — use
            kex_sql_query for a filtered read, which scans rather than samples.

            Each record carries its partition and offset, so any of them can be re-read exactly.""")
    public ToolResult<List<TopicView.MessagePreview>> previewMessages(
            @McpToolParam(description = "Topic name") String topic,
            @McpToolParam(required = false, description = "How many records; clamped by the server ceiling")
            Integer n,
            @McpToolParam(required = false, description = "latest (default) or earliest")
            String readMode) {

        long startedAt = System.currentTimeMillis();
        guard.checkTopicScope(topic);

        int cap = guard.clampRecords(n);
        List<Warning> warnings = new ArrayList<>(guard.clampWarnings("n", n, cap));
        boolean earliest = "earliest".equalsIgnoreCase(readMode);

        List<ConsumerRecord<String, String>> records;
        try {
            records = earliest ? kafka.getEarliestRecords(topic, cap) : kafka.getRecentRecords(topic, cap);
        } catch (Exception e) {
            throw dependencyUnavailable("reading records from " + topic, e);
        }

        List<TopicView.MessagePreview> preview = records.stream()
                .map(record -> new TopicView.MessagePreview(
                        record.partition(),
                        record.offset(),
                        record.timestamp(),
                        guard.dlp().scrub(record.key()),
                        guard.dlp().scrub(formatter.format(record.value()))))
                .toList();

        // A full page is indistinguishable from a complete read, and the difference is the whole
        // point: at the cap there is always more, so the stop reason says so rather than implying
        // the topic held exactly this much.
        boolean atCap = preview.size() >= cap;
        long elapsed = System.currentTimeMillis() - startedAt;
        Coverage coverage = atCap
                ? Coverage.partial(1, 1, List.of(), preview.size(), elapsed, StopReason.RECORD_LIMIT, null)
                : Coverage.exhausted(1, preview.size(), elapsed);

        return new ToolResult<>(preview, coverage, warnings, atCap);
    }

    /** True when the topic is inside {@code explorer.mcp.allowed-topic-prefixes}. */
    private boolean inTopicScope(String topic) {
        try {
            guard.checkTopicScope(topic);
            return true;
        } catch (McpToolException e) {
            return false;
        }
    }

    private Map<String, Integer> partitionCounts(List<String> topics, List<Warning> warnings) {
        Map<String, Integer> counts = new java.util.HashMap<>();
        for (String topic : topics) {
            try {
                counts.put(topic, kafka.getTopicDescriptor(topic).partitions());
            } catch (Exception e) {
                warnings.add(Warning.warn("PARTITIONS_UNREAD",
                        "partition count unavailable for " + topic + ": " + rootMessage(e)));
            }
        }
        return counts;
    }

    static boolean isDeadLetter(String topic) {
        return topic != null && DEAD_LETTER_SUFFIX.matcher(topic).find();
    }

    private static McpToolException dependencyUnavailable(String what, Exception e) {
        return new McpToolException(McpErrorCode.DEPENDENCY_UNAVAILABLE,
                "Kafka was unreachable while " + what + ": " + rootMessage(e));
    }

    private static String rootMessage(Throwable e) {
        Throwable cause = e;
        while (cause.getCause() != null && cause.getCause() != cause) {
            cause = cause.getCause();
        }
        return cause.getMessage() == null ? cause.getClass().getSimpleName() : cause.getMessage();
    }
}
