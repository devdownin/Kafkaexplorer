// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors
package com.compagnonsdudev.kafkasqlexplorer.domain;

import java.util.List;
import java.util.Objects;

/**
 * One consumer group's delay, in time, on one topic.
 *
 * <p>The counts are not decoration: {@code maxLagMs} over a topic whose partitions were only half
 * measured is a floor, not a maximum, and a caller that cannot tell the two apart will publish the
 * floor as the answer. {@code partitionsMeasured} / {@code partitionsCaughtUp} /
 * {@code partitionsWithoutCommit} / {@code partitionsUnknown} together account for every partition
 * of the topic, so the reading can always be situated.
 *
 * @param topic                   the topic
 * @param groupId                 the group measured — resolved to the worst-lagging one when the
 *                                caller named none, so it is always the group the numbers describe
 * @param partitions              per-partition detail, ordered by partition number
 * @param maxLagMs                worst age among the partitions that could be measured, {@code null}
 *                                when none could
 * @param avgLagMs                mean over those same partitions, {@code null} when none could
 * @param partitionsMeasured      partitions whose age was actually read (caught-up ones included)
 * @param partitionsCaughtUp      partitions committed at the end of the log
 * @param partitionsWithoutCommit partitions this group has never committed on — their backlog is
 *                                not counted by anything here, which is why they have their own row
 * @param partitionsUnknown       partitions whose record could not be read (compaction, retention,
 *                                a spent budget); each carries its reason in {@link PartitionTimeLag}
 * @param available               false when nothing could be read at all — distinct from "no lag"
 * @param error                   why, when {@code available} is false
 * @param warnings                anything that degraded the read without emptying it
 */
public record TopicTimeLag(
    String topic,
    String groupId,
    List<PartitionTimeLag> partitions,
    Long maxLagMs,
    Long avgLagMs,
    int partitionsMeasured,
    int partitionsCaughtUp,
    int partitionsWithoutCommit,
    int partitionsUnknown,
    boolean available,
    String error,
    List<String> warnings
) {
    public static TopicTimeLag unavailable(String topic, String groupId, String error) {
        return new TopicTimeLag(topic, groupId, List.of(), null, null, 0, 0, 0, 0, false, error, List.of());
    }

    /** True when the read covered every partition of the topic — the maximum is then a maximum. */
    public boolean complete() {
        return partitionsUnknown == 0 && partitionsWithoutCommit == 0;
    }

    public TopicTimeLag {
        partitions = partitions == null ? List.of() : List.copyOf(partitions);
        warnings = warnings == null ? List.of() : List.copyOf(warnings);
        Objects.requireNonNull(topic, "topic");
    }
}
