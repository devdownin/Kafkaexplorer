// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors
package com.compagnonsdudev.kafkasqlexplorer.mcp.tools;

import com.compagnonsdudev.kafkasqlexplorer.mcp.contract.Coverage;
import com.compagnonsdudev.kafkasqlexplorer.mcp.contract.Measured;
import com.compagnonsdudev.kafkasqlexplorer.mcp.contract.ToolResult;
import com.compagnonsdudev.kafkasqlexplorer.mcp.contract.Warning;
import com.compagnonsdudev.kafkasqlexplorer.mcp.guard.ToolGuard;
import com.compagnonsdudev.kafkasqlexplorer.mcp.observability.ToolCategory;
import com.compagnonsdudev.kafkasqlexplorer.service.KafkaAdminService;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Read-only evidence for configuration, longitudinal lag and dead-letter reviews. */
public class OperationalReviewMcpTools implements ReadOnlyMcpTools {
    private static final List<String> CONFIG_KEYS = List.of("retention.ms", "retention.bytes",
            "cleanup.policy", "min.insync.replicas", "segment.ms", "delete.retention.ms");
    private static final long SNAPSHOT_TTL_MS = Duration.ofMinutes(30).toMillis();
    private final KafkaAdminService kafka;
    private final ConsumerLagMcpTools lag;
    private final ToolGuard guard;
    private final Map<String, LagSample> snapshots = new ConcurrentHashMap<>();

    public OperationalReviewMcpTools(KafkaAdminService kafka, ConsumerLagMcpTools lag, ToolGuard guard) {
        this.kafka = kafka;
        this.lag = lag;
        this.guard = guard;
    }

    @Override public ToolCategory category() { return ToolCategory.DIAGNOSTIC; }

    public record TopicConfiguration(String topic, Map<String, Measured<String>> configuration,
                                     Measured<Map<Integer, KafkaAdminService.PartitionReplication>> replication,
                                     List<String> caveats) { }

    @McpTool(name = "kex_topic_configuration", annotations = @McpTool.McpAnnotations(
            readOnlyHint = true, destructiveHint = false, openWorldHint = true),
            description = "Read selected effective topic settings and per-partition replica/ISR counts. "
                    + "Missing or unread settings are unmeasured, never defaults. Apply environment-specific "
                    + "rules before classifying risk; no consumer group is not proof of an orphan.")
    public ToolResult<TopicConfiguration> topicConfiguration(
            @McpToolParam(description = "Topic name") String topic) {
        guard.checkTopicScope(topic);
        long start = System.currentTimeMillis();
        Map<String, String> raw = kafka.getTopicConfigs(topic);
        Map<String, Measured<String>> settings = new LinkedHashMap<>();
        CONFIG_KEYS.forEach(key -> settings.put(key, Measured.ofNullable(raw.get(key),
                "configuration unavailable or broker did not expose this setting")));
        Measured<Map<Integer, KafkaAdminService.PartitionReplication>> replication;
        try {
            replication = Measured.of(kafka.getTopicReplication(topic));
        } catch (Exception e) {
            replication = Measured.unmeasured("topic replication could not be read");
        }
        List<Warning> warnings = new ArrayList<>();
        if (raw.isEmpty()) warnings.add(Warning.warn("TOPIC_CONFIG_UNREAD", "topic settings could not be read"));
        if (!replication.measured()) warnings.add(Warning.warn("REPLICATION_UNREAD", "partition replicas could not be read"));
        return ToolResult.of(new TopicConfiguration(topic, settings, replication, List.of(
                "replication and ISR are point-in-time observations",
                "retention and replication requirements depend on the environment and broker count")),
                Coverage.exhausted(1, 0, System.currentTimeMillis() - start), warnings);
    }

    public record GroupTrend(String groupId, String verdict, Measured<Long> currentLag,
                             Measured<Long> previousLag, Measured<Long> lagChange,
                             Measured<Double> lagChangePerSecond,
                             Measured<Double> producerRecordsPerSecond,
                             Measured<Double> consumerRecordsPerSecond, long measuredAtMs,
                             Measured<Long> previousAtMs, String caveat) { }

    @McpTool(name = "kex_consumer_lag_trend", annotations = @McpTool.McpAnnotations(
            readOnlyHint = true, destructiveHint = false, openWorldHint = true),
            description = "Compare a group's lag with its previous complete reading on this server "
                    + "(30 minute TTL). First call, restart, incomplete read or expired sample yields "
                    + "an unmeasured trend. A growing backlog is a symptom, not a proven cause.")
    public ToolResult<GroupTrend> lagTrend(@McpToolParam(description = "Topic name") String topic,
                                           @McpToolParam(description = "Consumer group") String groupId) {
        guard.checkTopicScope(topic);
        if (groupId == null || groupId.isBlank()) throw new IllegalArgumentException("consumer group required");
        guard.checkGroupScope(List.of(groupId));
        long start = System.currentTimeMillis();
        ToolResult<LagView.TopicLag> result = lag.consumerLag(topic, groupId, false, true, null);
        long now = System.currentTimeMillis();
        LagView.GroupLag group = result.data().groups().stream()
                .filter(item -> item.groupId().equals(groupId)).findFirst().orElse(null);
        Measured<Long> current = group == null ? Measured.unmeasured("group not found") : group.recordLag();
        boolean complete = group != null && current.measured() && group.partitionsWithoutCommit() == 0
                && result.coverage().complete() && !result.truncated()
                && !group.partitions().isEmpty()
                && group.partitions().stream().allMatch(p -> p.committedOffset().measured());
        String key = topic + "\u0000" + groupId;
        snapshots.entrySet().removeIf(entry -> now - entry.getValue().atMs > SNAPSHOT_TTL_MS);
        if (snapshots.size() >= 1000 && !snapshots.containsKey(key)) snapshots.clear();
        Map<Integer, Long> ends = new LinkedHashMap<>();
        Map<Integer, Long> committed = new LinkedHashMap<>();
        if (complete) group.partitions().forEach(p -> {
            ends.put(p.partition(), p.endOffset());
            committed.put(p.partition(), p.committedOffset().value());
        });
        LagSample old = complete ? snapshots.put(key, new LagSample(current.value(), now, ends, committed))
                : snapshots.remove(key);
        boolean comparable = complete && old != null && now > old.atMs && now - old.atMs <= SNAPSHOT_TTL_MS
                && old.endOffsets.keySet().equals(ends.keySet());
        Measured<Long> previous = comparable ? Measured.of(old.lag) : Measured.unmeasured("no recent complete baseline");
        Measured<Long> change = comparable ? Measured.of(current.value() - old.lag)
                : Measured.unmeasured("two complete readings required");
        Measured<Double> rate = comparable ? Measured.of(1000.0 * change.value() / (now - old.atMs))
                : Measured.unmeasured("two complete readings required");
        long produced = comparable ? ends.entrySet().stream()
                .mapToLong(entry -> entry.getValue() - old.endOffsets.get(entry.getKey())).sum() : 0;
        long consumed = comparable ? committed.entrySet().stream()
                .mapToLong(entry -> entry.getValue() - old.committedOffsets.get(entry.getKey())).sum() : 0;
        boolean monotonic = comparable && produced >= 0 && consumed >= 0;
        Measured<Double> producerRate = monotonic ? Measured.of(1000.0 * produced / (now - old.atMs))
                : Measured.unmeasured("two complete comparable offset readings required; an offset may have reset");
        Measured<Double> consumerRate = monotonic ? Measured.of(1000.0 * consumed / (now - old.atMs))
                : Measured.unmeasured("two complete comparable commit readings required; an offset may have reset");
        GroupTrend data = new GroupTrend(groupId, group == null ? "UNKNOWN" : group.verdict(), current,
                previous, change, rate, producerRate, consumerRate, now,
                comparable ? Measured.of(old.atMs) : Measured.unmeasured("no recent complete baseline"),
                "single-process snapshot, lost on restart; rates are offset/commit deltas, not application throughput");
        return ToolResult.of(data, result.coverage(), result.warnings());
    }

    public record DeadLetterReview(String queueTopic, String sourceTopic, Measured<String> retentionMs,
                                   Measured<String> sourceRetentionMs,
                                   Measured<Map<Integer, KafkaAdminService.PartitionReplication>> replication,
                                   Measured<Integer> consumerGroups, int recordsSampled,
                                   int withOriginHeader, int withErrorHeader, List<String> caveats) { }

    @McpTool(name = "kex_dlq_review", annotations = @McpTool.McpAnnotations(
            readOnlyHint = true, destructiveHint = false, openWorldHint = true),
            description = "Read DLQ retention, replication, committed consumer groups and bounded "
                    + "sample header completeness. Missing groups do not establish missing monitoring; "
                    + "missing sample headers do not establish the status of all records. No reprocessing.")
    public ToolResult<DeadLetterReview> deadLetterReview(
            @McpToolParam(description = "DLQ or DLT topic") String queueTopic,
            @McpToolParam(required = false, description = "Source topic for retention comparison") String sourceTopic) {
        guard.checkTopicScope(queueTopic);
        if (sourceTopic != null && !sourceTopic.isBlank()) guard.checkTopicScope(sourceTopic);
        if (!TopicMcpTools.isDeadLetter(queueTopic)) throw new IllegalArgumentException("topic must end in .dlq or .dlt");
        long start = System.currentTimeMillis();
        List<Warning> warnings = new ArrayList<>();
        Map<String, String> config = kafka.getTopicConfigs(queueTopic);
        Map<String, String> sourceConfig = sourceTopic == null || sourceTopic.isBlank()
                ? Map.of() : kafka.getTopicConfigs(sourceTopic);
        Measured<Map<Integer, KafkaAdminService.PartitionReplication>> replication;
        try { replication = Measured.of(kafka.getTopicReplication(queueTopic)); }
        catch (Exception e) { replication = Measured.unmeasured("replica metadata unavailable"); }
        Measured<Integer> groups;
        try {
            var consumers = kafka.getTopicConsumers(queueTopic, guard.clampGroups(null));
            groups = consumers.available() && !consumers.truncated()
                    ? Measured.of(consumers.groups().size())
                    : Measured.unmeasured("consumer groups unavailable or capped");
        } catch (RuntimeException e) { groups = Measured.unmeasured("consumer group read failed"); }
        int origin = 0, error = 0;
        List<ConsumerRecord<String, String>> records;
        try { records = kafka.getRecentRecords(queueTopic, guard.clampRecords(20)); }
        catch (Exception e) {
            records = List.of();
            warnings.add(Warning.warn("SAMPLE_UNREAD", "recent DLQ records could not be sampled"));
        }
        for (var record : records) {
            for (var header : record.headers()) {
                String name = header.key().toLowerCase(java.util.Locale.ROOT);
                if (name.contains("original") && name.contains("topic")) { origin++; break; }
            }
            for (var header : record.headers()) {
                String name = header.key().toLowerCase(java.util.Locale.ROOT);
                if (name.contains("error") || name.contains("exception")) { error++; break; }
            }
        }
        DeadLetterReview review = new DeadLetterReview(queueTopic, sourceTopic,
                Measured.ofNullable(config.get("retention.ms"), "topic configuration unavailable"),
                Measured.ofNullable(sourceConfig.get("retention.ms"),
                        sourceTopic == null || sourceTopic.isBlank() ? "sourceTopic was not supplied"
                                : "source topic configuration unavailable"),
                replication, groups, records.size(), origin, error, List.of(
                "header counts describe only the bounded recent sample; payload metadata was not inspected",
                "zero committed consumer groups does not mean no alerting or monitoring",
                "retry and reprocessing paths require application or connector configuration inspection"));
        if (config.isEmpty()) warnings.add(Warning.warn("TOPIC_CONFIG_UNREAD", "topic settings could not be read"));
        return ToolResult.of(review, Coverage.exhausted(1, records.size(), System.currentTimeMillis() - start), warnings);
    }

    private record LagSample(long lag, long atMs, Map<Integer, Long> endOffsets,
                             Map<Integer, Long> committedOffsets) { }
}
