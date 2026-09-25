// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors
package com.compagnonsdudev.kafkasqlexplorer.mcp.tools;

import com.compagnonsdudev.kafkasqlexplorer.mcp.contract.Measured;

import java.util.List;

/** Measured window and explicitly bounded recent-record sample for one dead-letter topic. */
public record DeadLetterView(
        String queueTopic,
        String sourceTopic,
        Measured<Long> windowStartMs,
        Measured<Long> windowEndMs,
        Measured<Long> arrivals,
        Measured<Long> sourceProduced,
        Measured<Double> sharePercent,
        String arrivalTrend,
        Measured<Long> previousHalfArrivals,
        Measured<Long> recentHalfArrivals,
        Measured<Long> lastMessageAt,
        int recordsSampled,
        boolean sampleTruncated,
        Measured<Long> firstSampleAt,
        Measured<Long> lastSampleAt,
        List<SampleCount> errorSignatures,
        List<SampleCount> repeatedKeys,
        List<PartitionCount> sampledPartitions,
        List<String> caveats
) {
    /** Counts refer only to the bounded sample, never to every message in the window. */
    public record SampleCount(String value, int occurrences) { }

    public record PartitionCount(int partition, int occurrences) { }
}
