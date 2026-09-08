// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors
package com.compagnonsdudev.kafkasqlexplorer.mcp.tools;

import com.compagnonsdudev.kafkasqlexplorer.mcp.contract.Measured;

import java.util.List;

/** What {@code kex_consumer_lag} returns. */
public final class LagView {

    private LagView() {
    }

    /**
     * One consumer group's standing on one topic.
     *
     * <p>{@code verdict} is the domain's own {@code ConsumerGroupLag.Health}, carried as a string
     * so the model reads a word rather than inferring one from numbers it will get wrong: a lag of
     * zero on a group with no assigned member is <em>not</em> "up to date", it is nothing reading a
     * topic that is not moving, and that is the reading a model asked to judge for itself produces
     * every time.
     *
     * <p>{@code recordLag} and {@code lagMs} are both {@link Measured} and they fail
     * independently. Records are counted from committed offsets, which every broker answers; the
     * age needs the record <em>at</em> the committed offset, which compaction or retention may have
     * removed. A group can therefore have a perfectly known backlog of 40 000 records and no
     * knowable age, and the pair says so instead of reporting a zero for the half that failed.
     *
     * @param groupId                 the group
     * @param state                   STABLE / EMPTY / PREPARING_REBALANCE / DEAD / UNKNOWN
     * @param type                    CLASSIC / CONSUMER (KIP-848) / STREAMS
     * @param members                 members in the group, unmeasured when it could not be described
     * @param assignedMembers         members holding a partition of this topic, same caveat
     * @param recordLag               records committed-to-end, summed over the partitions that have
     *                                a commit
     * @param lagMs                   age of the oldest waiting record, when it could be read
     * @param partitionsWithoutCommit partitions the group has never committed on — their backlog is
     *                                in neither number above, which is why the count is here
     * @param verdict                 CAUGHT_UP / BEHIND / STALLED / PARTIAL / AHEAD / UNKNOWN
     * @param explanation             the verdict in a sentence, naming what to do about it
     * @param error                   why this group could not be read, {@code null} when it could
     * @param partitions              per-partition detail when {@code includePartitions} asked for
     *                                it, empty otherwise — a topic with fifty partitions and ten
     *                                groups is five hundred rows, and the summary above answers the
     *                                question that was asked in almost every case
     */
    public record GroupLag(
            String groupId,
            String state,
            String type,
            Measured<Integer> members,
            Measured<Integer> assignedMembers,
            Measured<Long> recordLag,
            Measured<Long> lagMs,
            int partitionsWithoutCommit,
            String verdict,
            String explanation,
            String error,
            List<PartitionStanding> partitions
    ) {
    }

    /**
     * Where one group stands on one partition.
     *
     * <p>{@code committedOffset} and {@code recordLag} are {@link Measured} because a group that
     * has never committed here has no position at all, and a {@code 0} would read as "caught up at
     * the beginning" — the exact opposite of the truth, which is that this partition is not being
     * read. A negative lag is reported rather than clamped: a committed offset past the end of the
     * log is what an offset reset to a future position, or a topic recreated under the same name,
     * leaves behind, and it is the one fact that explains a reading nobody would otherwise believe.
     *
     * @param partition       partition number
     * @param committedOffset last offset committed here, unmeasured when the group never committed
     * @param endOffset       the log end offset at the moment of the read
     * @param recordLag       {@code endOffset - committedOffset}, unmeasured without a commit
     * @param lagMs           age of the oldest waiting record here, unmeasured when it could not be
     *                        read or was not requested
     * @param assignedTo      the member currently holding this partition, {@code null} if none
     */
    public record PartitionStanding(
            int partition,
            Measured<Long> committedOffset,
            long endOffset,
            Measured<Long> recordLag,
            Measured<Long> lagMs,
            String assignedTo
    ) {
    }

    /**
     * The groups reading one topic, worst first.
     *
     * <p>{@code groupsEligible} is the honest denominator for {@code groupsExamined}: the cluster's
     * group count includes share groups and this application's own consumers, which are excluded
     * before the cap applies, so comparing what was read against the raw cluster total understates
     * the coverage every time.
     *
     * @param topic           the topic
     * @param groups          the groups, worst lag first
     * @param groupsExamined  groups whose offsets were actually read
     * @param groupsEligible  groups left after exclusions, before the cap
     * @param groupsInCluster groups the broker listed at all
     * @param worstVerdict    the most severe verdict among the groups — the one-line answer
     */
    public record TopicLag(
            String topic,
            List<GroupLag> groups,
            int groupsExamined,
            int groupsEligible,
            int groupsInCluster,
            String worstVerdict
    ) {
    }
}
