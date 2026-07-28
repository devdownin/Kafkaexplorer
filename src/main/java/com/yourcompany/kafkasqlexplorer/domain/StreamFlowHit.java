// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors
package com.yourcompany.kafkasqlexplorer.domain;

/**
 * One topic where the traced key was found, with the evidence behind the node drawn in the graph.
 *
 * <p>The graph alone cannot be checked: a box labelled with a topic name says the key was seen
 * there, but not how many times, when, or in which record. Every hit therefore carries the
 * coordinates of its first match (partition / offset / timestamp) and a bounded preview of the
 * payload, so an operator can jump straight to the message instead of taking the picture on faith.
 *
 * @param topic                topic the key was found in
 * @param occurrences          matching records within the scanned window
 * @param firstTimestamp       record timestamp of the earliest match (ms), 0 when the broker
 *                             reported no timestamp
 * @param lastTimestamp        record timestamp of the latest match (ms)
 * @param firstPartition       partition of the earliest match
 * @param firstOffset          offset of the earliest match
 * @param firstKey             Kafka record key of the earliest match, null when the record has none
 * @param preview              bounded preview of the earliest matching value
 * @param latencyFromPreviousMs delay from the previous topic in the chain, null on the first hop
 * @param occurrencesCapped    the topic holds more matches than the scan kept, so {@code occurrences}
 *                             is a floor rather than an exact count
 */
public record StreamFlowHit(
        String topic,
        int occurrences,
        long firstTimestamp,
        long lastTimestamp,
        int firstPartition,
        long firstOffset,
        String firstKey,
        String preview,
        Long latencyFromPreviousMs,
        boolean occurrencesCapped
) {
}
