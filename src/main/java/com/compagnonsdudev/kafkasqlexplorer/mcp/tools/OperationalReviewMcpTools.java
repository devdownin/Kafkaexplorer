// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors
package com.compagnonsdudev.kafkasqlexplorer.mcp.tools;

import com.compagnonsdudev.kafkasqlexplorer.mcp.contract.Coverage;
import com.compagnonsdudev.kafkasqlexplorer.mcp.contract.Measured;
import com.compagnonsdudev.kafkasqlexplorer.mcp.contract.ToolResult;
import com.compagnonsdudev.kafkasqlexplorer.mcp.contract.Warning;
import com.compagnonsdudev.kafkasqlexplorer.mcp.McpProperties;
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

/** Read-only evidence for configuration, longitudinal lag and dead-letter reviews. */
public class OperationalReviewMcpTools implements ReadOnlyMcpTools {
    private static final List<String> CONFIG_KEYS = List.of("retention.ms", "retention.bytes",
            "cleanup.policy", "min.insync.replicas", "segment.ms", "delete.retention.ms");
    private static final long SNAPSHOT_TTL_MS = Duration.ofMinutes(30).toMillis();
    private final KafkaAdminService kafka;
    private final ConsumerLagMcpTools lag;
    private final ToolGuard guard;
    private final LagSampleStore snapshots;
    private final boolean sharedHistory;
    private final McpProperties policies;
    private final long historyTtlMs;

    public OperationalReviewMcpTools(KafkaAdminService kafka, ConsumerLagMcpTools lag, ToolGuard guard) {
        this(kafka, lag, guard, LagSampleStore.inMemory(), false, new McpProperties());
    }

    public OperationalReviewMcpTools(KafkaAdminService kafka, ConsumerLagMcpTools lag, ToolGuard guard,
                                     LagSampleStore snapshots, boolean sharedHistory, McpProperties policies) {
        this.kafka = kafka;
        this.lag = lag;
        this.guard = guard;
        this.snapshots = snapshots;
        this.sharedHistory = sharedHistory;
        this.policies = policies;
        this.historyTtlMs = sharedHistory ? policies.getLagHistoryTtlMs() : SNAPSHOT_TTL_MS;
        if (historyTtlMs < 1) throw new IllegalArgumentException("lag history TTL must be positive");
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

    public record PolicyFinding(String property, String status, String observed, String expected) { }
    public record TopicPolicyReview(String topic, String environment, String status,
                                    List<PolicyFinding> findings, List<String> caveats) { }

    @McpTool(name = "kex_topic_policy_review", annotations = @McpTool.McpAnnotations(
            readOnlyHint = true, destructiveHint = false, openWorldHint = true),
            description = "Compare a topic's measured configuration with the operator-defined "
                    + "policy for the requested environment. Missing policy is NOT_CONFIGURED; "
                    + "unread Kafka values are UNMEASURED, never assumed compliant.")
    public ToolResult<TopicPolicyReview> topicPolicyReview(
            @McpToolParam(description = "Topic name") String topic,
            @McpToolParam(description = "Environment name configured in explorer.mcp.topic-policies")
            String environment) {
        guard.checkTopicScope(topic);
        if (environment == null || environment.isBlank()) throw new IllegalArgumentException("environment required");
        List<McpProperties.TopicPolicy> matched = policies.getTopicPolicies().stream()
                .filter(p -> environment.equals(p.getEnvironment())).toList();
        if (matched.size() != 1) {
            return ToolResult.of(new TopicPolicyReview(topic, environment,
                            matched.isEmpty() ? "NOT_CONFIGURED" : "INVALID_POLICY", List.of(),
                            List.of(matched.isEmpty() ? "no policy configured for environment"
                                    : "multiple policies configured for environment")),
                    Coverage.exhausted(1, 0, 0), List.of());
        }
        var policy = matched.getFirst();
        if (invalidPolicy(policy)) {
            return ToolResult.of(new TopicPolicyReview(topic, environment, "INVALID_POLICY", List.of(),
                    List.of("minimums must be positive, retention bounds nonnegative and ordered")),
                    Coverage.exhausted(1, 0, 0), List.of());
        }
        var evidence = topicConfiguration(topic);
        var data = evidence.data();
        List<PolicyFinding> findings = new ArrayList<>();
        if (policy.getMinReplicas() != null) {
            evaluateReplica(findings, "replicas", data.replication(), policy.getMinReplicas(), false);
        }
        if (policy.getMinInSyncReplicas() != null) {
            evaluateReplica(findings, "inSyncReplicas", data.replication(), policy.getMinInSyncReplicas(), true);
        }
        if (policy.getMinRetentionMs() != null) {
            evaluateRetention(findings, data.configuration().get("retention.ms"), policy.getMinRetentionMs(), true);
        }
        if (policy.getMaxRetentionMs() != null) {
            evaluateRetention(findings, data.configuration().get("retention.ms"), policy.getMaxRetentionMs(), false);
        }
        if (policy.getCleanupPolicy() != null && !policy.getCleanupPolicy().isBlank()) {
            var current = data.configuration().get("cleanup.policy");
            findings.add(new PolicyFinding("cleanup.policy",
                    !current.measured() ? "UNMEASURED"
                            : java.util.Arrays.asList(current.value().split(",")).contains(policy.getCleanupPolicy())
                            ? "PASS" : "FAIL",
                    current.measured() ? current.value() : null, policy.getCleanupPolicy()));
        }
        String status = findings.stream().anyMatch(f -> f.status().equals("FAIL")) ? "FAIL"
                : findings.stream().anyMatch(f -> f.status().equals("UNMEASURED")) ? "UNMEASURED"
                : findings.isEmpty() ? "NOT_CONFIGURED" : "PASS";
        return ToolResult.of(new TopicPolicyReview(topic, environment, status, findings,
                        List.of("policy thresholds are operator-supplied; replica and ISR counts are point-in-time")),
                evidence.coverage(), evidence.warnings());
    }

    private static boolean invalidPolicy(McpProperties.TopicPolicy policy) {
        return policy.getMinReplicas() != null && policy.getMinReplicas() < 1
                || policy.getMinInSyncReplicas() != null && policy.getMinInSyncReplicas() < 1
                || policy.getMinRetentionMs() != null && policy.getMinRetentionMs() < 0
                || policy.getMaxRetentionMs() != null && policy.getMaxRetentionMs() < 0
                || policy.getMinRetentionMs() != null && policy.getMaxRetentionMs() != null
                    && policy.getMinRetentionMs() > policy.getMaxRetentionMs();
    }

    private static void evaluateReplica(List<PolicyFinding> findings, String name,
            Measured<Map<Integer, KafkaAdminService.PartitionReplication>> data, int minimum, boolean inSync) {
        if (!data.measured() || data.value().isEmpty()) {
            findings.add(new PolicyFinding(name, "UNMEASURED", null, ">=" + minimum));
            return;
        }
        data.value().forEach((partition, replica) -> {
            int count = inSync ? replica.inSyncReplicas() : replica.replicas();
            findings.add(new PolicyFinding("partition[" + partition + "]." + name,
                    count >= minimum ? "PASS" : "FAIL", String.valueOf(count), ">=" + minimum));
        });
    }

    private static void evaluateRetention(List<PolicyFinding> findings, Measured<String> data,
                                          long threshold, boolean minimum) {
        String expected = (minimum ? ">=" : "<=") + threshold;
        if (!data.measured()) {
            findings.add(new PolicyFinding("retention.ms", "UNMEASURED", null, expected));
            return;
        }
        try {
            long value = Long.parseLong(data.value());
            boolean pass = minimum ? value == -1 || value >= threshold
                    : value >= 0 && value <= threshold;
            findings.add(new PolicyFinding("retention.ms", pass ? "PASS" : "FAIL", data.value(), expected));
        } catch (NumberFormatException e) {
            findings.add(new PolicyFinding("retention.ms", "UNMEASURED", data.value(), expected));
        }
    }

    public record GroupTrend(String groupId, String verdict, Measured<Long> currentLag,
                             Measured<Long> previousLag, Measured<Long> lagChange,
                             Measured<Double> lagChangePerSecond,
                             Measured<Double> producerRecordsPerSecond,
                             Measured<Double> consumerRecordsPerSecond, long measuredAtMs,
                             Measured<Long> previousAtMs, String caveat) { }

    @McpTool(name = "kex_consumer_lag_trend", annotations = @McpTool.McpAnnotations(
            readOnlyHint = true, destructiveHint = false, openWorldHint = true),
            description = "Compare a group's lag with its previous complete reading. "
                    + "A configured shared directory persists across instances and restarts. "
                    + "First call, incomplete read or expired sample yields "
                    + "an unmeasured trend. A growing backlog is a symptom, not a proven cause.")
    public ToolResult<GroupTrend> lagTrend(@McpToolParam(description = "Topic name") String topic,
                                           @McpToolParam(description = "Consumer group") String groupId) {
        guard.checkTopicScope(topic);
        if (groupId == null || groupId.isBlank()) throw new IllegalArgumentException("consumer group required");
        guard.checkGroupScope(List.of(groupId));
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
        Map<Integer, Long> ends = new LinkedHashMap<>();
        Map<Integer, Long> committed = new LinkedHashMap<>();
        if (complete) group.partitions().forEach(p -> {
            ends.put(p.partition(), p.endOffset());
            committed.put(p.partition(), p.committedOffset().value());
        });
        LagSampleStore.Sample old = null;
        List<Warning> warnings = new ArrayList<>(result.warnings());
        if (complete) try {
            old = snapshots.swap(key, new LagSampleStore.Sample(current.value(), now, ends, committed),
                    historyTtlMs);
        } catch (java.io.IOException e) {
            warnings.add(Warning.warn("LAG_HISTORY_UNAVAILABLE", "lag history could not be stored or read"));
        }
        boolean comparable = complete && old != null && now > old.atMs()
                && old.endOffsets().keySet().equals(ends.keySet());
        Measured<Long> previous = comparable ? Measured.of(old.lag()) : Measured.unmeasured("no recent complete baseline");
        Measured<Long> change = comparable ? Measured.of(current.value() - old.lag())
                : Measured.unmeasured("two complete readings required");
        Measured<Double> rate = comparable ? Measured.of(1000.0 * change.value() / (now - old.atMs()))
                : Measured.unmeasured("two complete readings required");
        LagSampleStore.Sample baseline = old;
        long produced = comparable ? ends.entrySet().stream()
                .mapToLong(entry -> entry.getValue() - baseline.endOffsets().get(entry.getKey())).sum() : 0;
        long consumed = comparable ? committed.entrySet().stream()
                .mapToLong(entry -> entry.getValue() - baseline.committedOffsets().get(entry.getKey())).sum() : 0;
        boolean monotonic = comparable && produced >= 0 && consumed >= 0;
        Measured<Double> producerRate = monotonic ? Measured.of(1000.0 * produced / (now - old.atMs()))
                : Measured.unmeasured("two complete comparable offset readings required; an offset may have reset");
        Measured<Double> consumerRate = monotonic ? Measured.of(1000.0 * consumed / (now - old.atMs()))
                : Measured.unmeasured("two complete comparable commit readings required; an offset may have reset");
        GroupTrend data = new GroupTrend(groupId, group == null ? "UNKNOWN" : group.verdict(), current,
                previous, change, rate, producerRate, consumerRate, now,
                comparable ? Measured.of(old.atMs()) : Measured.unmeasured("no recent complete baseline"),
                (sharedHistory ? "shared persistent snapshot" : "single-process snapshot, lost on restart")
                        + "; rates are offset/commit deltas, not application throughput");
        return ToolResult.of(data, result.coverage(), warnings);
    }

    public record DeadLetterReview(String queueTopic, String sourceTopic, Measured<String> retentionMs,
                                   Measured<String> sourceRetentionMs, Measured<Boolean> retentionAtLeastSource,
                                   Measured<Map<Integer, KafkaAdminService.PartitionReplication>> replication,
                                   Measured<Integer> consumerGroups, int recordsSampled,
                                   int withOriginHeader, int withMatchingSourceHeader, int withErrorHeader,
                                   String sourceStatus, List<RetryTopic> retries,
                                   String connectorName, String monitoringReference,
                                   String replayRunbook, List<String> caveats) { }
    public record RetryTopic(String topic, String status, Measured<Integer> consumerGroups,
                             Measured<Long> readableRecords) { }

    @McpTool(name = "kex_dlq_review", annotations = @McpTool.McpAnnotations(
            readOnlyHint = true, destructiveHint = false, openWorldHint = true),
            description = "Review a DLQ against its explicitly configured source, retry topics, "
                    + "monitoring reference and replay runbook. Topic existence/configuration and "
                    + "bounded header sample are measured; connector, alert and replay links are "
                    + "operator declarations, not verified execution. No reprocessing.")
    public ToolResult<DeadLetterReview> deadLetterReview(
            @McpToolParam(description = "DLQ or DLT topic") String queueTopic,
            @McpToolParam(required = false, description = "Source topic for retention comparison") String sourceTopic) {
        guard.checkTopicScope(queueTopic);
        if (sourceTopic != null && !sourceTopic.isBlank()) guard.checkTopicScope(sourceTopic);
        if (!TopicMcpTools.isDeadLetter(queueTopic)) throw new IllegalArgumentException("topic must end in .dlq or .dlt");
        List<McpProperties.DlqRoute> routes = policies.getDlqRoutes().stream()
                .filter(item -> queueTopic.equals(item.getQueueTopic())).toList();
        if (routes.size() > 1) throw new IllegalArgumentException("multiple DLQ routes configured for topic");
        McpProperties.DlqRoute route = routes.isEmpty() ? null : routes.getFirst();
        if (route != null && route.getSourceTopic() != null && !route.getSourceTopic().isBlank()) {
            if (sourceTopic != null && !sourceTopic.isBlank() && !sourceTopic.equals(route.getSourceTopic())) {
                throw new IllegalArgumentException("sourceTopic conflicts with configured DLQ route");
            }
            sourceTopic = route.getSourceTopic();
            guard.checkTopicScope(sourceTopic);
        }
        List<String> retryNames = route == null || route.getRetryTopics() == null
                ? List.of() : route.getRetryTopics();
        if (retryNames.size() > 10) throw new IllegalArgumentException("at most 10 retry topics per route");
        retryNames.forEach(guard::checkTopicScope);
        long start = System.currentTimeMillis();
        List<Warning> warnings = new ArrayList<>();
        Map<String, String> config = kafka.getTopicConfigs(queueTopic);
        Map<String, String> sourceConfig = sourceTopic == null || sourceTopic.isBlank()
                ? Map.of() : kafka.getTopicConfigs(sourceTopic);
        Measured<Map<Integer, KafkaAdminService.PartitionReplication>> replication;
        try { replication = Measured.of(kafka.getTopicReplication(queueTopic)); }
        catch (Exception e) { replication = Measured.unmeasured("replica metadata unavailable"); }
        String sourceStatus = "NOT_DECLARED";
        if (sourceTopic != null && !sourceTopic.isBlank()) {
            try {
                sourceStatus = kafka.getTopicReplication(sourceTopic).isEmpty() ? "UNMEASURED" : "OBSERVED";
            } catch (Exception e) { sourceStatus = "UNMEASURED"; }
        }
        List<RetryTopic> retries = new ArrayList<>();
        for (String retry : retryNames) {
            String status;
            try { status = kafka.getTopicReplication(retry).isEmpty() ? "UNMEASURED" : "OBSERVED"; }
            catch (Exception e) { status = "UNMEASURED"; }
            Measured<Integer> retryGroups;
            try {
                var consumers = kafka.getTopicConsumers(retry, guard.clampGroups(null));
                retryGroups = consumers.available() && !consumers.truncated()
                        ? Measured.of(consumers.groups().size()) : Measured.unmeasured("retry consumer groups unavailable or capped");
            } catch (Exception e) { retryGroups = Measured.unmeasured("retry consumer groups unavailable"); }
            Measured<Long> records;
            try { records = Measured.ofNullable(kafka.getTopicRecordCounts(List.of(retry)).get(retry),
                    "retry offsets unavailable"); }
            catch (Exception e) { records = Measured.unmeasured("retry offsets unavailable"); }
            retries.add(new RetryTopic(retry, status, retryGroups, records));
        }
        Measured<Integer> groups;
        try {
            var consumers = kafka.getTopicConsumers(queueTopic, guard.clampGroups(null));
            groups = consumers.available() && !consumers.truncated()
                    ? Measured.of(consumers.groups().size())
                    : Measured.unmeasured("consumer groups unavailable or capped");
        } catch (RuntimeException e) { groups = Measured.unmeasured("consumer group read failed"); }
        int origin = 0, matchingOrigin = 0, error = 0;
        List<ConsumerRecord<String, String>> records;
        try { records = kafka.getRecentRecords(queueTopic, guard.clampRecords(20)); }
        catch (Exception e) {
            records = List.of();
            warnings.add(Warning.warn("SAMPLE_UNREAD", "recent DLQ records could not be sampled"));
        }
        for (var record : records) {
            for (var header : record.headers()) {
                String name = header.key().toLowerCase(java.util.Locale.ROOT);
                if (name.contains("original") && name.contains("topic")) {
                    origin++;
                    if (sourceTopic != null && header.value() != null
                            && sourceTopic.equals(new String(header.value(), java.nio.charset.StandardCharsets.UTF_8))) {
                        matchingOrigin++;
                    }
                    break;
                }
            }
            for (var header : record.headers()) {
                String name = header.key().toLowerCase(java.util.Locale.ROOT);
                if (name.contains("error") || name.contains("exception")) { error++; break; }
            }
        }
        Measured<Boolean> retentionComparison = retentionCompared(config.get("retention.ms"),
                sourceConfig.get("retention.ms"));
        DeadLetterReview review = new DeadLetterReview(queueTopic, sourceTopic,
                Measured.ofNullable(config.get("retention.ms"), "topic configuration unavailable"),
                Measured.ofNullable(sourceConfig.get("retention.ms"),
                        sourceTopic == null || sourceTopic.isBlank() ? "sourceTopic was not supplied"
                                : "source topic configuration unavailable"),
                retentionComparison, replication, groups, records.size(), origin, matchingOrigin, error,
                sourceStatus, retries,
                route == null ? null : route.getConnectorName(),
                route == null ? null : route.getMonitoringReference(),
                route == null ? null : route.getReplayRunbook(), List.of(
                "header counts describe only the bounded recent sample; payload metadata was not inspected",
                "zero committed consumer groups does not mean no alerting or monitoring",
                "connector, monitoring and replay references are operator declarations, not live checks",
                "retry topics are checked for broker metadata, not for an active retry processor"));
        if (config.isEmpty()) warnings.add(Warning.warn("TOPIC_CONFIG_UNREAD", "topic settings could not be read"));
        return ToolResult.of(review, Coverage.exhausted(1, records.size(), System.currentTimeMillis() - start), warnings);
    }

    private static Measured<Boolean> retentionCompared(String queue, String source) {
        if (queue == null || source == null) return Measured.unmeasured("both retention settings required");
        try {
            long dlqMs = Long.parseLong(queue), sourceMs = Long.parseLong(source);
            if (dlqMs < -1 || sourceMs < -1) return Measured.unmeasured("invalid retention setting");
            return Measured.of(dlqMs == -1 || sourceMs != -1 && dlqMs >= sourceMs);
        } catch (NumberFormatException e) {
            return Measured.unmeasured("retention setting is not numeric");
        }
    }

}
