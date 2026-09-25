// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors
package com.compagnonsdudev.kafkasqlexplorer.mcp.tools;

import com.compagnonsdudev.kafkasqlexplorer.domain.TopicActivityResponse;
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
import com.compagnonsdudev.kafkasqlexplorer.service.KafkaAdminService;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Operational views composed from Kafka Explorer's existing measurements.
 *
 * <p>These tools deliberately add no second monitoring engine. Topic activity is the dashboard's
 * offset-based activity read, consumer standing is ConsumerLagMcpTools, and each response keeps
 * the coverage envelope. The value here is composition around the questions an operations agent
 * asks: is this process healthy, where did the flow drop, and did the action improve anything.
 */
public class OperationalMcpTools implements ReadOnlyMcpTools {

    private static final long DEFAULT_WINDOW_MS = 15 * 60_000L;
    private static final int DEFAULT_BUCKETS = 12;
    private static final int ACTIVITY_MAX_LOOKUPS = 20_000;

    private final KafkaAdminService kafka;
    private final ConsumerLagMcpTools consumerLag;
    private final ToolGuard guard;

    public OperationalMcpTools(KafkaAdminService kafka, ConsumerLagMcpTools consumerLag, ToolGuard guard) {
        this.kafka = kafka;
        this.consumerLag = consumerLag;
        this.guard = guard;
    }

    @Override
    public ToolCategory category() {
        return ToolCategory.DIAGNOSTIC;
    }

    @McpTool(name = "kex_topic_activity", annotations = @McpTool.McpAnnotations(
            readOnlyHint = true, destructiveHint = false, openWorldHint = true),
            description = """
            Measure recent topic activity from Kafka offsets, without consuming records.

            Returns one aligned time series per topic, the last observed message timestamp,
            silent buckets, partition coverage and the exact window measured. A zero bucket is only
            a zero when the series is available; an unavailable or partial topic says why instead
            of manufacturing silence.

            Use this to distinguish an empty topic, a historically populated but now silent topic,
            and a low-volume topic.""")
    public ToolResult<List<OperationalView.TopicActivity>> topicActivity(
            @McpToolParam(description = "Topics to measure, in process order") List<String> topics,
            @McpToolParam(required = false, description = "Window in milliseconds; default 15 minutes")
            Long windowMs,
            @McpToolParam(required = false, description = "Aligned buckets in the window; default 12")
            Integer buckets) {

        List<String> selected = topics(topics);
        long startedAt = System.currentTimeMillis();
        long window = windowMs == null ? DEFAULT_WINDOW_MS : windowMs;
        int bucketCount = buckets == null ? DEFAULT_BUCKETS : buckets;

        TopicActivityResponse response = kafka.getTopicActivity(
                selected, window, bucketCount, ACTIVITY_MAX_LOOKUPS);
        if (!response.available() && response.topics().isEmpty()) {
            throw new McpToolException(McpErrorCode.DEPENDENCY_UNAVAILABLE,
                    "topic activity could not be measured: " + String.join("; ", response.warnings()));
        }

        var lastSeen = kafka.getTopicsLastMessageTimestamps(selected);
        List<OperationalView.TopicActivity> data = selected.stream()
                .filter(response.topics()::containsKey)
                .map(topic -> {
                    var measured = response.topics().get(topic);
                    return new OperationalView.TopicActivity(
                            topic,
                            measured.windowStartMs(),
                            measured.windowEndMs(),
                            measured.bucketMs(),
                            measured.counts(),
                            measured.total(),
                            Measured.ofNullable(lastSeen.get(topic),
                                    "no record timestamp was observed for this topic"),
                            (int) measured.counts().stream().filter(value -> value == 0L).count(),
                            measured.partitionsMeasured(),
                            measured.partitionsTotal(),
                            measured.complete(),
                            measured.note());
                })
                .toList();

        List<String> notReached = selected.stream()
                .filter(topic -> !response.topics().containsKey(topic))
                .toList();
        List<Warning> warnings = new ArrayList<>();
        response.warnings().forEach(w -> warnings.add(Warning.warn("TOPIC_ACTIVITY", guard.dlp().scrub(w))));
        boolean partial = !notReached.isEmpty() || data.stream().anyMatch(item -> !item.complete());
        Coverage coverage = new Coverage(
                selected.size(), data.size(), notReached, 0L,
                System.currentTimeMillis() - startedAt,
                partial ? StopReason.PARTIAL_FAILURE : StopReason.EXHAUSTED,
                java.time.Instant.ofEpochMilli(response.windowStartMs()),
                java.time.Instant.ofEpochMilli(response.windowEndMs()),
                null);
        return new ToolResult<>(data, coverage, warnings, partial);
    }

    @McpTool(name = "kex_diagnose_consumer", annotations = @McpTool.McpAnnotations(
            readOnlyHint = true, destructiveHint = false, openWorldHint = true),
            description = """
            Diagnose one consumer group on one topic using Kafka Explorer's measured lag verdict.

            The result says whether the group is caught up, merely behind, stalled with no assigned
            member, partial because some partitions were never committed, ahead of the log, in a
            rebalance, or unreadable. Do not infer those states from a lag number alone.""")
    public ToolResult<OperationalView.ConsumerDiagnosis> diagnoseConsumer(
            @McpToolParam(description = "Topic read by the group") String topic,
            @McpToolParam(description = "Consumer group id") String groupId,
            @McpToolParam(required = false, description = "Measure backlog age too; default true")
            Boolean includeTimeLag) {

        guard.checkTopicScope(topic);
        guard.checkGroupScope(groupId == null || groupId.isBlank() ? null : List.of(groupId));
        if (groupId == null || groupId.isBlank()) {
            throw new McpToolException(McpErrorCode.VALIDATION_FAILED, McpGuard.VALIDATION,
                    "a consumer group is required");
        }

        ToolResult<LagView.TopicLag> lag = consumerLag.consumerLag(
                topic, groupId, includeTimeLag == null || includeTimeLag, false, 1);
        LagView.GroupLag group = lag.data().groups().stream().findFirst().orElse(null);
        if (group == null) {
            OperationalView.ConsumerDiagnosis missing = new OperationalView.ConsumerDiagnosis(
                    topic, groupId, "UNKNOWN",
                    "the group was not found among the groups reading this topic",
                    "UNKNOWN",
                    Measured.unmeasured("no committed offsets for this group on this topic were found"),
                    Measured.unmeasured("no group standing was available"),
                    Measured.unmeasured("the group standing was unavailable"),
                    0);
            return new ToolResult<>(missing, lag.coverage(), lag.warnings(), true);
        }

        OperationalView.ConsumerDiagnosis data = new OperationalView.ConsumerDiagnosis(
                topic, group.groupId(), operationalVerdict(group), diagnosis(group), group.state(),
                group.recordLag(), group.lagMs(), group.assignedMembers(),
                group.partitionsWithoutCommit());
        return new ToolResult<>(data, lag.coverage(), lag.warnings(), lag.truncated());
    }

    @McpTool(name = "kex_process_health", annotations = @McpTool.McpAnnotations(
            readOnlyHint = true, destructiveHint = false, openWorldHint = true),
            description = """
            Give one operational health view for a process described by its ordered Kafka topics and
            optional consumer groups.

            The status is derived only from measured topic activity and consumer verdicts:
            ERROR for stalled/partial/ahead consumers, WARNING for ordinary backlog or silent stages,
            UNKNOWN when required evidence could not be measured, otherwise OK. The explanation
            names the evidence used, and coverage still states what was not read.""")
    public ToolResult<OperationalView.ProcessHealth> processHealth(
            @McpToolParam(description = "Process name used in the returned evidence") String process,
            @McpToolParam(description = "Ordered topics that form the process") List<String> topics,
            @McpToolParam(required = false, description = "Consumer groups to check on each topic")
            List<String> groupIds,
            @McpToolParam(required = false, description = "Activity window in milliseconds; default 15 minutes")
            Long windowMs,
            @McpToolParam(required = false, description = "Activity buckets; default 12")
            Integer buckets) {

        List<String> selected = topics(topics);
        ToolResult<List<OperationalView.TopicActivity>> activity = topicActivity(selected, windowMs, buckets);
        List<OperationalView.ConsumerDiagnosis> consumers = new ArrayList<>();
        List<Warning> warnings = new ArrayList<>(activity.warnings());
        List<String> groups = groups(groupIds);
        boolean truncated = activity.truncated();

        for (String topic : selected) {
            for (String group : groups) {
                ToolResult<OperationalView.ConsumerDiagnosis> diagnosis =
                        diagnoseConsumer(topic, group, true);
                consumers.add(diagnosis.data());
                warnings.addAll(diagnosis.warnings());
                truncated |= diagnosis.truncated();
            }
        }

        String status = healthStatus(activity.data(), consumers);
        String explanation = healthExplanation(status, activity.data(), consumers);
        Long last = activity.data().stream()
                .map(OperationalView.TopicActivity::lastMessageAt)
                .filter(Measured::measured)
                .map(Measured::value)
                .max(Long::compareTo)
                .orElse(null);
        long total = activity.data().stream().mapToLong(OperationalView.TopicActivity::offsetsProduced).sum();
        Long worstLag = consumers.stream()
                .map(OperationalView.ConsumerDiagnosis::recordLag)
                .filter(Measured::measured)
                .map(Measured::value)
                .max(Long::compareTo)
                .orElse(null);

        OperationalView.ProcessHealth data = new OperationalView.ProcessHealth(
                process == null || process.isBlank() ? String.join(" -> ", selected) : process,
                status, explanation, selected, activity.data(), consumers,
                Measured.ofNullable(last, "no topic returned a last-message timestamp"),
                Measured.of(total),
                Measured.ofNullable(worstLag, groups.isEmpty()
                        ? "no consumer groups were requested"
                        : "no consumer lag could be measured"));

        return new ToolResult<>(data, activity.coverage(), warnings, truncated);
    }

    @McpTool(name = "kex_flow_health", annotations = @McpTool.McpAnnotations(
            readOnlyHint = true, destructiveHint = false, openWorldHint = true),
            description = """
            Compare activity across an ordered chain of topics and identify the largest measured
            drop between adjacent stages.

            Counts are offsets produced in the same aligned window, not lifetime record counts.
            largestDropRate is unmeasured when the upstream stage produced zero, because dividing
            by zero would turn no input into a fake loss percentage. A bottleneck is a candidate
            stage boundary, not a causal conclusion.""")
    public ToolResult<OperationalView.FlowHealth> flowHealth(
            @McpToolParam(description = "Ordered topics from source to sink") List<String> topics,
            @McpToolParam(required = false, description = "Activity window in milliseconds; default 15 minutes")
            Long windowMs,
            @McpToolParam(required = false, description = "Activity buckets; default 12")
            Integer buckets) {

        List<String> selected = topics(topics);
        ToolResult<List<OperationalView.TopicActivity>> activity = topicActivity(selected, windowMs, buckets);
        List<OperationalView.FlowStage> stages = activity.data().stream()
                .map(item -> new OperationalView.FlowStage(
                        item.topic(), item.offsetsProduced(), item.lastMessageAt(), item.complete()))
                .toList();

        Double largestDrop = null;
        String bottleneck = null;
        for (int i = 1; i < stages.size(); i++) {
            long before = stages.get(i - 1).offsetsProduced();
            long after = stages.get(i).offsetsProduced();
            if (before <= 0 || after >= before) continue;
            double drop = (before - after) / (double) before;
            if (largestDrop == null || drop > largestDrop) {
                largestDrop = drop;
                bottleneck = stages.get(i - 1).topic() + " -> " + stages.get(i).topic();
            }
        }

        boolean incomplete = activity.truncated() || stages.stream().anyMatch(stage -> !stage.complete());
        String status = incomplete ? "UNKNOWN"
                : largestDrop != null && largestDrop >= 0.5 ? "ERROR"
                : largestDrop != null && largestDrop >= 0.1 ? "WARNING"
                : "OK";
        String explanation = largestDrop == null
                ? "no measurable downstream drop was observed in the aligned window"
                : "largest observed output drop is %.1f%% at %s; this is correlation, not proof of cause"
                        .formatted(largestDrop * 100.0, bottleneck);

        OperationalView.FlowHealth data = new OperationalView.FlowHealth(
                status, bottleneck, stages,
                largestDrop == null
                        ? Measured.unmeasured("no adjacent pair had a positive upstream count and lower downstream count")
                        : Measured.of(largestDrop),
                explanation);
        return new ToolResult<>(data, activity.coverage(), activity.warnings(), incomplete);
    }

    @McpTool(name = "kex_compare_process_state", annotations = @McpTool.McpAnnotations(
            readOnlyHint = true, destructiveHint = false, openWorldHint = true),
            description = """
            Verify a process after an action by comparing a caller-supplied baseline with a fresh
            Kafka measurement.

            The baseline is explicit because Kafka Explorer does not invent historical state.
            verdict is RESOLVED, IMPROVED, UNCHANGED, DEGRADED or UNKNOWN. UNKNOWN wins whenever
            the fresh process state is not fully measurable.""")
    public ToolResult<OperationalView.ProcessComparison> compareProcessState(
            @McpToolParam(description = "Process name") String process,
            @McpToolParam(description = "Ordered topics that form the process") List<String> topics,
            @McpToolParam(required = false, description = "Consumer groups to check") List<String> groupIds,
            @McpToolParam(description = "Baseline status: OK | WARNING | ERROR | UNKNOWN") String beforeStatus,
            @McpToolParam(required = false, description = "Worst baseline record lag, if measured")
            Long beforeRecordLag,
            @McpToolParam(required = false, description = "Baseline offsets produced in an equivalent window")
            Long beforeOffsetsProduced,
            @McpToolParam(required = false, description = "Activity window in milliseconds; default 15 minutes")
            Long windowMs) {

        ToolResult<OperationalView.ProcessHealth> current =
                processHealth(process, topics, groupIds, windowMs, DEFAULT_BUCKETS);
        OperationalView.ProcessHealth after = current.data();
        Long afterLag = after.worstRecordLag().measured() ? after.worstRecordLag().value() : null;
        Long afterProduced = after.offsetsProduced().measured() ? after.offsetsProduced().value() : null;

        String verdict = compare(beforeStatus, after.status(), beforeRecordLag, afterLag,
                beforeOffsetsProduced, afterProduced);
        String explanation = switch (verdict) {
            case "RESOLVED" -> "the process is now OK after a non-OK baseline";
            case "IMPROVED" -> "the fresh state is better by status, lag, or observed activity";
            case "DEGRADED" -> "the fresh state is worse by status, lag, or observed activity";
            case "UNCHANGED" -> "no material change is visible in the measured indicators";
            default -> "the fresh state is incomplete, so no before/after conclusion is justified";
        };

        OperationalView.ProcessComparison data = new OperationalView.ProcessComparison(
                after.process(), verdict, normalizeStatus(beforeStatus), after.status(),
                Measured.ofNullable(beforeRecordLag, "baseline lag was not supplied"),
                after.worstRecordLag(),
                Measured.ofNullable(beforeOffsetsProduced, "baseline activity was not supplied"),
                after.offsetsProduced(),
                explanation);
        return new ToolResult<>(data, current.coverage(), current.warnings(), current.truncated());
    }

    @McpTool(name = "kex_incident_evidence", annotations = @McpTool.McpAnnotations(
            readOnlyHint = true, destructiveHint = false, openWorldHint = true),
            description = """
            Build one compact evidence packet for an incident: process health, aligned topic
            activity, consumer diagnoses, measurement time, and machine-readable facts.

            This is designed for an agent evidence drawer. Facts repeat only measured observations;
            hypotheses and remediation do not appear here, so a caller can keep facts separate from
            its own reasoning.""")
    public ToolResult<OperationalView.IncidentEvidence> incidentEvidence(
            @McpToolParam(description = "Process name") String process,
            @McpToolParam(description = "Ordered topics that form the process") List<String> topics,
            @McpToolParam(required = false, description = "Consumer groups to diagnose") List<String> groupIds,
            @McpToolParam(required = false, description = "Activity window in milliseconds; default 15 minutes")
            Long windowMs) {

        ToolResult<OperationalView.ProcessHealth> health =
                processHealth(process, topics, groupIds, windowMs, DEFAULT_BUCKETS);
        List<String> facts = new ArrayList<>();
        for (OperationalView.TopicActivity item : health.data().activity()) {
            facts.add("%s produced %d offset(s) in the measured window"
                    .formatted(item.topic(), item.offsetsProduced()));
            if (item.lastMessageAt().measured()) {
                facts.add("%s last observed a timestamped record at %d"
                        .formatted(item.topic(), item.lastMessageAt().value()));
            }
            if (item.silentBuckets() > 0) {
                facts.add("%s had %d silent bucket(s) in the measured window"
                        .formatted(item.topic(), item.silentBuckets()));
            }
        }
        for (OperationalView.ConsumerDiagnosis item : health.data().consumers()) {
            facts.add("%s on %s: %s — %s"
                    .formatted(item.groupId(), item.topic(), item.verdict(), item.diagnosis()));
        }

        OperationalView.IncidentEvidence data = new OperationalView.IncidentEvidence(
                health.data().process(), System.currentTimeMillis(), health.data(),
                health.data().activity(), health.data().consumers(), facts);
        return new ToolResult<>(data, health.coverage(), health.warnings(), health.truncated());
    }

    private List<String> topics(List<String> topics) {
        if (topics == null || topics.isEmpty()) {
            throw new McpToolException(McpErrorCode.VALIDATION_FAILED, McpGuard.VALIDATION,
                    "at least one topic is required");
        }
        List<String> selected = topics.stream()
                .filter(Objects::nonNull)
                .map(String::trim)
                .filter(topic -> !topic.isBlank())
                .distinct()
                .toList();
        if (selected.isEmpty()) {
            throw new McpToolException(McpErrorCode.VALIDATION_FAILED, McpGuard.VALIDATION,
                    "at least one non-blank topic is required");
        }
        guard.checkTopicScope(selected);
        return selected;
    }

    private List<String> groups(List<String> groupIds) {
        if (groupIds == null) return List.of();
        List<String> selected = groupIds.stream()
                .filter(Objects::nonNull)
                .map(String::trim)
                .filter(group -> !group.isBlank())
                .distinct()
                .toList();
        guard.checkGroupScope(selected.isEmpty() ? null : selected);
        return selected;
    }

    private static String operationalVerdict(LagView.GroupLag group) {
        if ("PREPARING_REBALANCE".equals(group.state()) || "COMPLETING_REBALANCE".equals(group.state())) {
            return "REBALANCING";
        }
        return group.verdict();
    }

    private static String diagnosis(LagView.GroupLag group) {
        if ("PREPARING_REBALANCE".equals(group.state()) || "COMPLETING_REBALANCE".equals(group.state())) {
            return "the group is rebalancing; assignments are transient, so lag should be rechecked after it stabilizes";
        }
        return group.explanation();
    }

    private static String healthStatus(List<OperationalView.TopicActivity> activity,
                                       List<OperationalView.ConsumerDiagnosis> consumers) {
        if (activity.isEmpty() || activity.stream().anyMatch(item -> !item.complete())) return "UNKNOWN";
        Set<String> errors = Set.of("STALLED", "PARTIAL", "AHEAD");
        if (consumers.stream().anyMatch(item -> errors.contains(item.verdict()))) return "ERROR";
        if (consumers.stream().anyMatch(item -> "UNKNOWN".equals(item.verdict()))) return "UNKNOWN";
        if (consumers.stream().anyMatch(item -> Set.of("BEHIND", "REBALANCING").contains(item.verdict()))) {
            return "WARNING";
        }
        long active = activity.stream().filter(item -> item.offsetsProduced() > 0).count();
        if (active < activity.size()) return "WARNING";
        return "OK";
    }

    private static String healthExplanation(String status,
                                            List<OperationalView.TopicActivity> activity,
                                            List<OperationalView.ConsumerDiagnosis> consumers) {
        return switch (status) {
            case "ERROR" -> consumers.stream()
                    .filter(item -> Set.of("STALLED", "PARTIAL", "AHEAD").contains(item.verdict()))
                    .findFirst()
                    .map(item -> "%s on %s is %s: %s"
                            .formatted(item.groupId(), item.topic(), item.verdict(), item.diagnosis()))
                    .orElse("a measured consumer condition is critical");
            case "UNKNOWN" -> "at least one required activity or consumer measurement is incomplete";
            case "WARNING" -> {
                long silent = activity.stream().filter(item -> item.offsetsProduced() == 0).count();
                yield silent > 0
                        ? "%d of %d process topic(s) produced no offsets in the measured window"
                                .formatted(silent, activity.size())
                        : "a consumer is behind or rebalancing while the process remains observable";
            }
            default -> "every measured topic was active and no requested consumer had an unhealthy verdict";
        };
    }

    private static String compare(String beforeStatus, String afterStatus,
                                  Long beforeLag, Long afterLag,
                                  Long beforeProduced, Long afterProduced) {
        String before = normalizeStatus(beforeStatus);
        if ("UNKNOWN".equals(afterStatus)) return "UNKNOWN";
        if (!"OK".equals(before) && "OK".equals(afterStatus)) return "RESOLVED";

        int beforeRank = rank(before);
        int afterRank = rank(afterStatus);
        if (afterRank < beforeRank) return "IMPROVED";
        if (afterRank > beforeRank) return "DEGRADED";

        if (beforeLag != null && afterLag != null) {
            if (afterLag < beforeLag) return "IMPROVED";
            if (afterLag > beforeLag) return "DEGRADED";
        }
        if (beforeProduced != null && afterProduced != null) {
            if (beforeProduced == 0 && afterProduced > 0) return "IMPROVED";
            if (beforeProduced > 0 && afterProduced == 0) return "DEGRADED";
        }
        return "UNCHANGED";
    }

    private static int rank(String status) {
        return switch (normalizeStatus(status)) {
            case "OK" -> 0;
            case "WARNING" -> 1;
            case "ERROR" -> 2;
            default -> 3;
        };
    }

    private static String normalizeStatus(String status) {
        if (status == null) return "UNKNOWN";
        String normalized = status.trim().toUpperCase(java.util.Locale.ROOT);
        return Set.of("OK", "WARNING", "ERROR", "UNKNOWN").contains(normalized) ? normalized : "UNKNOWN";
    }
}
