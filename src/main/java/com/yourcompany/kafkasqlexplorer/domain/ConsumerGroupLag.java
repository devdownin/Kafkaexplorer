// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors
package com.yourcompany.kafkasqlexplorer.domain;

import java.util.List;

/**
 * One consumer group's position on one topic.
 *
 * <p>{@code totalLag} sums only the partitions the group has actually committed on, and
 * {@code partitionsWithoutCommit} counts the rest — a single number would otherwise present a
 * group reading three partitions of twelve as being nearly up to date. {@code assignedMembers} is
 * how many live members hold a partition of *this* topic, which is not the group's member count:
 * a group consuming five topics can be perfectly healthy with no member on this one only if it
 * was rebalanced away, and that distinction is the whole question when a topic stops draining.
 *
 * @param groupId                  the group id
 * @param type                     CLASSIC / CONSUMER (KIP-848) / STREAMS — see the service for why
 *                                 SHARE groups are not here
 * @param state                    STABLE / EMPTY / PREPARING_REBALANCE / DEAD / UNKNOWN
 * @param members                  members in the group, all topics together
 * @param assignedMembers          members currently holding a partition of this topic
 * @param totalLag                 sum of the per-partition lags that exist
 * @param partitionsWithoutCommit  partitions of the topic this group has never committed on
 * @param partitions               per-partition detail, ordered by partition number
 * @param error                    why this group could not be read, {@code null} when it could
 */
public record ConsumerGroupLag(
        String groupId,
        String type,
        String state,
        int members,
        int assignedMembers,
        long totalLag,
        int partitionsWithoutCommit,
        List<PartitionLag> partitions,
        String error
) {

    /** A group whose description or offsets could not be read — named, never silently dropped. */
    public static ConsumerGroupLag failed(String groupId, String type, String reason) {
        return new ConsumerGroupLag(groupId, type, "UNKNOWN", 0, 0, 0L, 0, List.of(), reason);
    }

    /**
     * What is wrong with this group, if anything — the lag number alone does not say.
     *
     * <p>Zero on a group with no member means nothing is reading and the topic is not moving,
     * which is the opposite of "up to date"; a large lag on a group that is draining is ordinary
     * on a live topic. Three situations therefore have names of their own, and they are the ones
     * the audit reports as findings. Kept in step with `healthOf` in
     * {@code components/topic/topicConsumers.ts}, which grades the same thing for the panel
     * without a round trip.
     */
    public enum Health {
        /** Committed at the end of every partition. */
        CAUGHT_UP,
        /** Reading, but not at the end — normal on a live topic. */
        BEHIND,
        /** Records waiting and no member assigned to this topic: nothing will drain them. */
        STALLED,
        /** Never committed on some partitions — the total does not count what it ignores. */
        PARTIAL,
        /** A committed offset past the end of its partition. */
        AHEAD,
        /** The group could not be read. */
        UNKNOWN
    }

    public Health health() {
        if (error != null) return Health.UNKNOWN;
        if (partitions.stream().anyMatch(p -> p.lag() != null && p.lag() < 0)) return Health.AHEAD;
        if (assignedMembers == 0 && totalLag > 0) return Health.STALLED;
        if (partitionsWithoutCommit > 0) return Health.PARTIAL;
        return totalLag > 0 ? Health.BEHIND : Health.CAUGHT_UP;
    }
}
