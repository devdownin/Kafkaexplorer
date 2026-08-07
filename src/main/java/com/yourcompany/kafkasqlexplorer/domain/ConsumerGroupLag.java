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
}
