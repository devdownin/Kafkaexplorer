// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors
package com.compagnonsdudev.kafkasqlexplorer.domain;

import java.util.List;
import java.util.Objects;

/**
 * What one topic produced over a window, bucket by bucket — the series behind the dashboard's
 * sparkline.
 *
 * <p>It is measured from <b>offsets, not records</b>: each bucket boundary is resolved to an
 * offset per partition through {@code listOffsets(forTimestamp)} and consecutive offsets are
 * subtracted. Nothing is read from the log, which is what makes a curve per row affordable on a
 * page showing twenty-five topics. The cost is that a bucket counts offsets produced — a
 * transaction marker takes one, and a record later removed by compaction still counts, because it
 * was produced. That is the right answer for "was this topic busy", and the wrong one for "how
 * many records are in there now": {@code topicSizes} answers that second question.
 *
 * <p>Two things are stated rather than left to be assumed, on the rule this codebase applies
 * everywhere else — a measurement that could not be taken must not come back as a zero:
 *
 * <ul>
 *   <li>{@code coveredFromMs} is the instant from which the series is complete. Retention deletes
 *       the oldest records, and a boundary older than the log's first surviving record resolves to
 *       that record: the buckets before it would read as a flat zero, which is indistinguishable
 *       from a quiet night. Non-null means "the counts before this instant are floors, the rest of
 *       what was produced then is gone".</li>
 *   <li>{@code partitionsMeasured} against {@code partitionsTotal}: a partition whose offsets could
 *       not be read contributes nothing, so a series short of a partition is a floor too.</li>
 * </ul>
 *
 * @param topic              the topic these counts describe
 * @param windowStartMs      first bucket's start, epoch millis (inclusive)
 * @param windowEndMs        last bucket's end, epoch millis (exclusive)
 * @param bucketMs           width of one bucket
 * @param counts             one count per bucket, oldest first, {@code counts.size()} buckets
 * @param total              sum of the buckets — offsets produced over the window
 * @param coveredFromMs      instant from which every partition covers the window, {@code null}
 *                           when the whole window is covered
 * @param partitionsMeasured partitions whose offsets were actually read
 * @param partitionsTotal    partitions the topic has
 * @param available          false when nothing could be read — distinct from "no traffic"
 * @param note               why, when the series is partial or absent; {@code null} otherwise
 */
public record TopicActivity(
    String topic,
    long windowStartMs,
    long windowEndMs,
    long bucketMs,
    List<Long> counts,
    long total,
    Long coveredFromMs,
    int partitionsMeasured,
    int partitionsTotal,
    boolean available,
    String note
) {
    public TopicActivity {
        counts = counts == null ? List.of() : List.copyOf(counts);
        Objects.requireNonNull(topic, "topic");
    }

    /** No series at all, and the reason. Never a row of zeros, which would read as "quiet". */
    public static TopicActivity unavailable(String topic, long windowStartMs, long windowEndMs,
                                            long bucketMs, String note) {
        return new TopicActivity(topic, windowStartMs, windowEndMs, bucketMs,
                List.of(), 0L, null, 0, 0, false, note);
    }

    /** True when every partition answered and retention did not eat into the window. */
    public boolean complete() {
        return available && coveredFromMs == null && partitionsMeasured == partitionsTotal;
    }
}
