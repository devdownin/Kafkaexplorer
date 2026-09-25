// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors
package com.compagnonsdudev.kafkasqlexplorer.mcp.tools;

import com.compagnonsdudev.kafkasqlexplorer.mcp.contract.Coverage;
import com.compagnonsdudev.kafkasqlexplorer.mcp.contract.Measured;
import com.compagnonsdudev.kafkasqlexplorer.mcp.contract.StopReason;
import com.compagnonsdudev.kafkasqlexplorer.mcp.contract.ToolResult;
import com.compagnonsdudev.kafkasqlexplorer.mcp.contract.Warning;
import com.compagnonsdudev.kafkasqlexplorer.mcp.guard.McpErrorCode;
import com.compagnonsdudev.kafkasqlexplorer.mcp.guard.McpGuard;
import com.compagnonsdudev.kafkasqlexplorer.mcp.guard.McpToolException;
import com.compagnonsdudev.kafkasqlexplorer.mcp.guard.ToolGuard;
import com.compagnonsdudev.kafkasqlexplorer.mcp.observability.ToolCategory;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** A DLQ diagnosis composed from the same offset reads and bounded preview used by the UI. */
public class DeadLetterMcpTools implements ReadOnlyMcpTools {

    private static final int BUCKETS = 12;
    private static final int MAX_SAMPLE = 20;
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final List<String> REASON_FIELDS = List.of(
            "failure_reason", "failureReason", "exception", "error", "cause");

    private final OperationalMcpTools operational;
    private final TopicMcpTools topics;
    private final ToolGuard guard;

    public DeadLetterMcpTools(OperationalMcpTools operational, TopicMcpTools topics, ToolGuard guard) {
        this.operational = operational;
        this.topics = topics;
        this.guard = guard;
    }

    @Override
    public ToolCategory category() {
        return ToolCategory.DIAGNOSTIC;
    }

    @McpTool(name = "kex_dlq_diagnosis", annotations = @McpTool.McpAnnotations(
            readOnlyHint = true, destructiveHint = false, openWorldHint = true),
            description = """
            Diagnose a DLQ/DLT from an aligned offset activity window and at most 20 recent records.
            Supply sourceTopic explicitly to measure its share of source traffic; no source is
            guessed from the queue name. Error signatures, repeated keys and affected partitions
            describe only the bounded recent sample, never the whole window or root cause.
            An incomplete read, absent source or zero source throughput yields unmeasured values,
            never a fabricated zero failure rate.""")
    public ToolResult<DeadLetterView> diagnose(
            @McpToolParam(description = "Dead-letter topic ending in .dlq or .dlt") String queueTopic,
            @McpToolParam(required = false, description = "Explicit source topic, if known; required for a measured failure share")
            String sourceTopic,
            @McpToolParam(required = false, description = "Activity window in milliseconds; default 15 minutes")
            Long windowMs,
            @McpToolParam(required = false, description = "Recent records to sample, at most 20; default 20")
            Integer sampleSize) {

        if (queueTopic == null || !TopicMcpTools.isDeadLetter(queueTopic)) {
            throw new McpToolException(McpErrorCode.VALIDATION_FAILED, McpGuard.VALIDATION,
                    "queueTopic must end in .dlq or .dlt");
        }
        guard.checkTopicScope(queueTopic);
        String source = sourceTopic == null || sourceTopic.isBlank() ? null : sourceTopic.trim();
        if (source != null) {
            guard.checkTopicScope(source);
            if (source.equals(queueTopic)) {
                throw new McpToolException(McpErrorCode.VALIDATION_FAILED, McpGuard.VALIDATION,
                        "sourceTopic must differ from queueTopic");
            }
        }
        if (sampleSize != null && sampleSize < 1) {
            throw new McpToolException(McpErrorCode.VALIDATION_FAILED, McpGuard.VALIDATION,
                    "sampleSize must be positive");
        }

        List<String> selected = source == null ? List.of(queueTopic) : List.of(queueTopic, source);
        ToolResult<List<OperationalView.TopicActivity>> activity =
                operational.topicActivity(selected, windowMs, BUCKETS);
        Map<String, OperationalView.TopicActivity> measured = new HashMap<>();
        activity.data().forEach(item -> measured.put(item.topic(), item));
        OperationalView.TopicActivity queue = measured.get(queueTopic);
        OperationalView.TopicActivity input = source == null ? null : measured.get(source);
        boolean queueComplete = queue != null && queue.complete();
        boolean sourceComplete = input != null && input.complete()
                && queueComplete && queue.windowStartMs() == input.windowStartMs()
                && queue.windowEndMs() == input.windowEndMs()
                && queue.bucketMs() == input.bucketMs()
                && queue.counts().size() == input.counts().size();

        Measured<Long> arrivals = queueComplete ? Measured.of(queue.offsetsProduced())
                : Measured.unmeasured("DLQ activity is unavailable or incomplete");
        Measured<Long> produced = sourceComplete ? Measured.of(input.offsetsProduced())
                : Measured.unmeasured(source == null ? "sourceTopic was not supplied"
                        : "source activity is unavailable, incomplete or not aligned with the DLQ");
        Measured<Double> share = !arrivals.measured() || !produced.measured()
                ? Measured.unmeasured("a complete aligned DLQ and source window is required")
                : produced.value() == 0L
                    ? Measured.unmeasured("the source produced no offsets in the measured window")
                    : Measured.of(100.0 * arrivals.value() / produced.value());

        List<Long> counts = queueComplete ? queue.counts() : List.of();
        int middle = counts.size() / 2;
        boolean halves = counts.size() >= 2 && counts.size() % 2 == 0;
        long previous = halves ? counts.subList(0, middle).stream().mapToLong(Long::longValue).sum() : 0L;
        long recent = halves ? counts.subList(middle, counts.size()).stream().mapToLong(Long::longValue).sum() : 0L;
        String trend = !halves ? "UNKNOWN" : recent > previous ? "INCREASING"
                : recent < previous ? "DECREASING" : "STABLE";

        // The preview already enforces the configured MCP record ceiling and scrubs key/value.
        ToolResult<List<TopicView.MessagePreview>> preview =
                topics.previewMessages(queueTopic, sampleSize == null ? MAX_SAMPLE
                        : Math.min(sampleSize, MAX_SAMPLE), "latest");
        List<TopicView.MessagePreview> records = preview.data();
        List<String> caveats = new ArrayList<>();
        caveats.add("arrival counts are produced offsets, not a count of pending records");
        caveats.add("signatures, keys and partitions describe only the recent record sample");
        if (source == null) caveats.add("source not supplied; failure share cannot be measured");
        if (share.measured() && share.value() > 100) {
            caveats.add("DLQ arrivals exceed source production: redelivery or an incorrect source may explain this");
        }
        if (activity.truncated()) caveats.add("topic activity coverage is incomplete");

        Map<String, Integer> reasons = new LinkedHashMap<>();
        Map<String, Integer> keys = new LinkedHashMap<>();
        Map<Integer, Integer> partitions = new LinkedHashMap<>();
        for (TopicView.MessagePreview record : records) {
            String reason = reason(record.value());
            reasons.merge(reason == null ? "unclassified in sample" : reason, 1, Integer::sum);
            if (record.key() != null && !record.key().isBlank()) {
                keys.merge(record.key(), 1, Integer::sum);
            }
            partitions.merge(record.partition(), 1, Integer::sum);
        }
        List<DeadLetterView.SampleCount> signatures = counts(reasons);
        List<DeadLetterView.SampleCount> repeated = counts(keys).stream()
                .filter(item -> item.occurrences() > 1).toList();
        List<DeadLetterView.PartitionCount> sampledPartitions = partitions.entrySet().stream()
                .map(item -> new DeadLetterView.PartitionCount(item.getKey(), item.getValue()))
                .sorted(Comparator.comparingInt(DeadLetterView.PartitionCount::partition)).toList();
        Long first = records.stream().map(TopicView.MessagePreview::timestampMs).min(Long::compareTo).orElse(null);
        Long last = records.stream().map(TopicView.MessagePreview::timestampMs).max(Long::compareTo).orElse(null);
        DeadLetterView data = new DeadLetterView(queueTopic, source,
                queueComplete ? Measured.of(queue.windowStartMs()) : Measured.unmeasured("DLQ activity is incomplete"),
                queueComplete ? Measured.of(queue.windowEndMs()) : Measured.unmeasured("DLQ activity is incomplete"),
                arrivals, produced, share, trend,
                halves ? Measured.of(previous) : Measured.unmeasured("two complete aligned half-windows are required"),
                halves ? Measured.of(recent) : Measured.unmeasured("two complete aligned half-windows are required"),
                queueComplete ? queue.lastMessageAt() : Measured.unmeasured("DLQ activity is incomplete"),
                records.size(), preview.truncated(),
                Measured.ofNullable(first, "no timestamped record in the sample"),
                Measured.ofNullable(last, "no timestamped record in the sample"),
                signatures, repeated, sampledPartitions, caveats);

        Coverage base = activity.coverage();
        Coverage coverage = new Coverage(base.topicsRequested(), base.topicsScanned(),
                base.topicsNotReached(), preview.coverage().recordsScanned(),
                base.elapsedMs() + preview.coverage().elapsedMs(),
                base.complete() && preview.truncated() ? StopReason.RECORD_LIMIT : base.stopReason(),
                base.windowStart(), base.windowEnd(), base.resumeToken());
        List<Warning> warnings = new ArrayList<>(activity.warnings());
        warnings.addAll(preview.warnings());
        return new ToolResult<>(data, coverage, warnings, activity.truncated() || preview.truncated());
    }

    private static List<DeadLetterView.SampleCount> counts(Map<String, Integer> values) {
        return values.entrySet().stream()
                .map(entry -> new DeadLetterView.SampleCount(entry.getKey(), entry.getValue()))
                .sorted(Comparator.comparingInt(DeadLetterView.SampleCount::occurrences).reversed()
                        .thenComparing(DeadLetterView.SampleCount::value)).toList();
    }

    private static String reason(String value) {
        if (value == null || !value.trim().startsWith("{")) return null;
        try {
            JsonNode body = JSON.readTree(value);
            if (body == null || !body.isObject()) return null;
            for (String field : REASON_FIELDS) {
                JsonNode found = body.get(field);
                if (found != null && found.isTextual() && !found.asText().isBlank()) {
                    return found.asText().length() > 160 ? found.asText().substring(0, 160) : found.asText();
                }
            }
        } catch (com.fasterxml.jackson.core.JsonProcessingException ignored) {
            // A malformed payload is a routine reason for a dead-letter record.
        }
        return null;
    }
}
