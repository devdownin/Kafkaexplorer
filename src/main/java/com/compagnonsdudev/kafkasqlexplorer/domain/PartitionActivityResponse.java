// SPDX-License-Identifier: AGPL-3.0-or-later
package com.compagnonsdudev.kafkasqlexplorer.domain;

import java.util.List;

/** Bounded, read-only partition activity of one selected topic for the Strata focus view. */
public record PartitionActivityResponse(String topic, long windowStartMs, long windowEndMs,
        long bucketMs, int buckets, int partitionsTotal, int partitionsMeasured,
        boolean truncated, List<PartitionActivity> partitions, List<String> warnings) {
    public PartitionActivityResponse {
        partitions = partitions == null ? List.of() : List.copyOf(partitions);
        warnings = warnings == null ? List.of() : List.copyOf(warnings);
    }
}
