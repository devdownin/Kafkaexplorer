// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors
package com.compagnonsdudev.kafkasqlexplorer.mcp.tools;

import com.compagnonsdudev.kafkasqlexplorer.domain.ConsumerGroupLag;
import com.compagnonsdudev.kafkasqlexplorer.domain.PartitionTimeLag;
import com.compagnonsdudev.kafkasqlexplorer.domain.TopicConsumers;
import com.compagnonsdudev.kafkasqlexplorer.domain.TopicTimeLag;
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
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * "Is anything falling behind, and does it matter?" — in one call, with the verdict spelled out.
 *
 * <p>An adapter over {@code KafkaAdminService}, which already computes both halves for the topic
 * panel. What this class adds is the pairing an agent otherwise gets wrong: a record lag with no
 * age beside it cannot distinguish a queue draining at ten thousand records a second from one that
 * has not moved since Friday, and the number that answers "does it matter" is the age.
 *
 * <p>The age is read only when asked for, and that is deliberate rather than lazy: it fetches the
 * record sitting at each committed offset, so it costs a partition read per partition per group
 * where the record count costs one offsets call for the lot. On a topic with fifty groups an agent
 * that wanted "who is behind" would pay for a diagnosis of every one of them.
 */
public class ConsumerLagMcpTools implements ReadOnlyMcpTools {

    /**
     * Worst first. {@code UNKNOWN} sits at the top with the failures rather than at the bottom with
     * the healthy: a group nobody could read is the one an operator most needs to see, and sorting
     * it last hides it under whatever the cap cut off.
     */
    private static final List<ConsumerGroupLag.Health> SEVERITY = List.of(
            ConsumerGroupLag.Health.UNKNOWN,
            ConsumerGroupLag.Health.AHEAD,
            ConsumerGroupLag.Health.STALLED,
            ConsumerGroupLag.Health.PARTIAL,
            ConsumerGroupLag.Health.BEHIND,
            ConsumerGroupLag.Health.CAUGHT_UP);

    private final KafkaAdminService kafka;
    private final ToolGuard guard;

    public ConsumerLagMcpTools(KafkaAdminService kafka, ToolGuard guard) {
        this.kafka = kafka;
        this.guard = guard;
    }

    @Override
    public ToolCategory category() {
        return ToolCategory.DIAGNOSTIC;
    }

    @McpTool(name = "kex_consumer_lag", annotations = @McpTool.McpAnnotations(
            readOnlyHint = true, destructiveHint = false, openWorldHint = true),
            description = """
            Who reads a topic, how far behind each group is, and whether that is a problem.

            Read `verdict` rather than deciding from the numbers:
              CAUGHT_UP  committed at the end of every partition
              BEHIND     reading, not at the end — ordinary on a live topic
              STALLED    records waiting and NO member assigned: nothing will drain them
              PARTIAL    never committed on some partitions; the totals ignore that backlog
              AHEAD      committed past the end of the log — an offset reset or a recreated topic
              UNKNOWN    the group could not be read; every number for it is absent, not zero

            A lag of zero on a STALLED group is not "up to date". `recordLag` and `lagMs` fail
            independently: a known backlog of records can have an unknowable age when compaction or
            retention removed the record at the committed offset.

            Set `includeTimeLag` only when the age matters — it reads one record per partition per
            group, where the record count is a single offsets call for all of them.

            Set `includePartitions` when the summary is not enough — one group stuck on a single
            partition of forty is invisible in a total. It is off by default because a topic with
            fifty partitions and ten groups is five hundred rows for a question that a verdict
            usually answers.""")
    public ToolResult<LagView.TopicLag> consumerLag(
            @McpToolParam(description = "The topic to look at") String topic,
            @McpToolParam(required = false, description = "Only this consumer group")
            String groupId,
            @McpToolParam(required = false, description = "Also measure the age of the backlog. Slower. Default false.")
            Boolean includeTimeLag,
            @McpToolParam(required = false, description = "Return per-partition detail instead of only the totals. Default false.")
            Boolean includePartitions,
            @McpToolParam(required = false, description = "Maximum groups to examine; clamped by the server ceiling")
            Integer maxGroups) {

        // Before any read: a scope check that runs afterwards has already disclosed what it refused.
        guard.checkTopicScope(topic);
        guard.checkGroupScope(groupId == null || groupId.isBlank() ? null : List.of(groupId));

        if (topic == null || topic.isBlank()) {
            throw new McpToolException(McpErrorCode.VALIDATION_FAILED, McpGuard.VALIDATION,
                    "a topic is required");
        }

        long startedAt = System.currentTimeMillis();
        int cap = guard.clampGroups(maxGroups);
        List<Warning> warnings = new ArrayList<>(guard.clampWarnings("maxGroups", maxGroups, cap));

        TopicConsumers consumers;
        try {
            consumers = kafka.getTopicConsumers(topic.trim(), cap);
        } catch (Exception e) {
            throw new McpToolException(McpErrorCode.DEPENDENCY_UNAVAILABLE,
                    "Kafka was unreachable while reading the consumer groups of " + topic + ": "
                            + rootMessage(e));
        }

        // An unavailable read is a failure, not an empty answer: returning zero groups here would
        // state that nobody reads the topic on the strength of a call that never answered.
        if (!consumers.available()) {
            throw new McpToolException(McpErrorCode.DEPENDENCY_UNAVAILABLE,
                    "the consumer groups of %s could not be read: %s".formatted(
                            topic, String.join("; ", consumers.warnings())));
        }

        consumers.warnings().forEach(w -> warnings.add(Warning.warn("CONSUMER_READ", guard.dlp().scrub(w))));

        List<ConsumerGroupLag> selected = consumers.groups().stream()
                .filter(g -> groupId == null || groupId.isBlank() || groupId.trim().equals(g.groupId()))
                .filter(g -> inGroupScope(g.groupId()))
                .sorted(Comparator.comparingInt(g -> SEVERITY.indexOf(g.health())))
                .toList();

        if (groupId != null && !groupId.isBlank() && selected.isEmpty()) {
            warnings.add(Warning.warn("GROUP_NOT_FOUND",
                    ("no group %s holds a committed offset on %s among the %d examined; it may read "
                            + "other topics, or never have committed on this one")
                            .formatted(groupId, topic, consumers.groupsExamined())));
        }

        boolean withTime = Boolean.TRUE.equals(includeTimeLag);
        boolean withPartitions = Boolean.TRUE.equals(includePartitions);
        List<LagView.GroupLag> groups = selected.stream()
                .map(g -> view(topic.trim(), g, withTime, withPartitions, warnings))
                .toList();

        if (consumers.truncated()) {
            // A sentence, not a row in topicsNotReached: that field holds names, and the groups the
            // cap left out were never listed back by the service, so naming them would be invention.
            warnings.add(Warning.warn("GROUPS_NOT_EXAMINED",
                    ("%d consumer group(s) beyond the cap of %d were not examined; raise maxGroups "
                            + "or explorer.mcp.hard-max-groups to see them")
                            .formatted(consumers.groupsEligible() - consumers.groupsExamined(), cap)));
        }

        LagView.TopicLag data = new LagView.TopicLag(
                topic.trim(), groups,
                consumers.groupsExamined(), consumers.groupsEligible(), consumers.groupsInCluster(),
                selected.isEmpty() ? null : selected.get(0).health().name());

        Coverage coverage = new Coverage(
                consumers.groupsEligible(),
                consumers.groupsExamined(),
                List.of(),
                0L,
                System.currentTimeMillis() - startedAt,
                consumers.truncated() ? StopReason.TOPIC_LIMIT
                        : selected.stream().anyMatch(g -> g.error() != null)
                        ? StopReason.PARTIAL_FAILURE : StopReason.EXHAUSTED,
                null, null, null);

        return new ToolResult<>(data, coverage, warnings, consumers.truncated());
    }

    private LagView.GroupLag view(String topic, ConsumerGroupLag group, boolean withTime,
                                  boolean withPartitions, List<Warning> warnings) {
        ConsumerGroupLag.Health health = group.health();
        // Read once and used twice: the total and the per-partition rows come from the same
        // measurement, so the detail can never contradict the sum it was drawn from.
        TopicTimeLag ages = withTime ? ages(topic, group) : null;
        Measured<Long> lagMs = withTime ? timeLag(group, ages, warnings)
                : Measured.unmeasured("not requested — set includeTimeLag=true to measure the age "
                        + "of the backlog");

        return new LagView.GroupLag(
                group.groupId(),
                group.state(),
                group.type(),
                group.membersKnown() ? Measured.of(group.members())
                        : Measured.unmeasured("the group could not be described, so its member "
                                + "count is unknown rather than zero"),
                group.membersKnown() ? Measured.of(group.assignedMembers())
                        : Measured.unmeasured("the group could not be described, so its assignment "
                                + "on this topic is unknown rather than zero"),
                group.error() == null ? Measured.of(group.totalLag())
                        : Measured.unmeasured("the group's offsets could not be read"),
                lagMs,
                group.partitionsWithoutCommit(),
                health.name(),
                explain(health, group),
                group.error(),
                withPartitions ? partitions(group, ages) : List.of());
    }

    /**
     * Per-partition rows, each fact carrying its own measured-ness.
     *
     * <p>A partition with no commit is where the summary misleads most: it contributes nothing to
     * the total, so a group stuck on one partition of forty reads as very slightly behind. Here it
     * is a row whose offset and lag are both explicitly unmeasured.
     */
    private static List<LagView.PartitionStanding> partitions(ConsumerGroupLag group, TopicTimeLag ages) {
        Map<Integer, PartitionTimeLag> byPartition = ages == null || !ages.available()
                ? Map.of()
                : ages.partitions().stream().collect(Collectors.toMap(
                        PartitionTimeLag::partition, Function.identity(), (a, b) -> a));

        return group.partitions().stream()
                .map(p -> new LagView.PartitionStanding(
                        p.partition(),
                        Measured.ofNullable(p.committedOffset(),
                                "this group has never committed on this partition, so it holds no "
                                        + "position here at all"),
                        p.endOffset(),
                        Measured.ofNullable(p.lag(),
                                "without a commit there is no distance to the end to measure"),
                        age(byPartition.get(p.partition()), ages),
                        p.memberId()))
                .toList();
    }

    private static Measured<Long> age(PartitionTimeLag measured, TopicTimeLag ages) {
        if (ages == null) {
            return Measured.unmeasured("not requested — set includeTimeLag=true");
        }
        if (measured == null) {
            return Measured.unmeasured("this partition was not among those the age read covered");
        }
        return Measured.ofNullable(measured.lagMs(),
                measured.note() == null
                        ? "the record at the committed offset could not be read"
                        : measured.note());
    }

    /**
     * Reads the age, or returns an unavailable reading carrying the reason.
     *
     * <p>An exception degrades the one value rather than the call: the record lag beside it is
     * valid, and throwing here would discard a whole topic's diagnosis over one group's age.
     */
    private TopicTimeLag ages(String topic, ConsumerGroupLag group) {
        try {
            return kafka.getConsumerTimeLag(topic, group.groupId());
        } catch (Exception e) {
            return TopicTimeLag.unavailable(topic, group.groupId(),
                    "the age could not be read: " + rootMessage(e));
        }
    }

    private Measured<Long> timeLag(ConsumerGroupLag group, TopicTimeLag lag, List<Warning> warnings) {
        if (!lag.available()) {
            return Measured.unmeasured(lag.error() == null ? "the age could not be read" : lag.error());
        }
        lag.warnings().forEach(w -> warnings.add(Warning.info("TIME_LAG",
                "%s: %s".formatted(group.groupId(), guard.dlp().scrub(w)))));
        if (lag.maxLagMs() == null) {
            return Measured.unmeasured("no partition's age could be measured: the record at each "
                    + "committed offset has been compacted or aged out");
        }
        // A partial measurement is reported as measured but flagged: it is a floor, and a caller
        // that reads it as the maximum will publish an understated backlog age.
        if (!lag.complete()) {
            warnings.add(Warning.warn("TIME_LAG_PARTIAL",
                    ("%s: the age is a floor, not a maximum — %d partition(s) could not be measured "
                            + "and %d have no commit at all")
                            .formatted(group.groupId(), lag.partitionsUnknown(),
                                    lag.partitionsWithoutCommit())));
        }
        return Measured.of(lag.maxLagMs());
    }

    /** The verdict in a sentence, naming what to do — the numbers alone do not say. */
    private static String explain(ConsumerGroupLag.Health health, ConsumerGroupLag group) {
        return switch (health) {
            case CAUGHT_UP -> "committed at the end of every partition of this topic";
            case BEHIND -> ("%d record(s) waiting and members are assigned; ordinary on a live "
                    + "topic — compare the age over two calls to tell draining from stuck")
                    .formatted(group.totalLag());
            case STALLED -> ("%d record(s) waiting and no member is assigned to this topic. Nothing "
                    + "will drain them until a consumer joins the group.").formatted(group.totalLag());
            case PARTIAL -> ("never committed on %d partition(s), whose backlog is counted by "
                    + "nothing here — the %d reported is a floor")
                    .formatted(group.partitionsWithoutCommit(), group.totalLag());
            case AHEAD -> "a committed offset lies past the end of its partition: an offset reset "
                    + "to a future position, or a topic recreated under the same name";
            case UNKNOWN -> "the group could not be read, so nothing here is a measurement";
        };
    }

    private boolean inGroupScope(String group) {
        try {
            guard.checkGroupScope(List.of(group));
            return true;
        } catch (McpToolException e) {
            return false;
        }
    }

    private static String rootMessage(Throwable e) {
        Throwable cause = e;
        while (cause.getCause() != null && cause.getCause() != cause) {
            cause = cause.getCause();
        }
        return cause.getMessage() == null ? cause.getClass().getSimpleName() : cause.getMessage();
    }
}
