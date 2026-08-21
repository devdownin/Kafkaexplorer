// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors
package com.compagnonsdudev.kafkasqlexplorer.domain;

import java.util.List;
import java.util.Map;

/**
 * {@code GET /api/dashboard/activity} — one series per topic asked for, over one shared window.
 *
 * <p>The window is shared and stated here rather than being restated per topic: every series is
 * bucketed on the same boundaries, which is what lets the dashboard put twenty-five sparklines in
 * one column and have them mean the same thing.
 *
 * <p>A topic that was asked for and is missing from {@code topics} was not measured — the request
 * was cut to the lookup budget, or the topic no longer exists. {@code warnings} says which, since
 * an absent curve otherwise reads as a topic that produced nothing.
 *
 * @param topics        series by topic name; a topic that could not be read carries
 *                      {@code available: false} with its reason rather than a row of zeros
 * @param windowStartMs first bucket's start, epoch millis (inclusive)
 * @param windowEndMs   last bucket's end, epoch millis (exclusive)
 * @param bucketMs      width of one bucket
 * @param buckets       number of buckets in every series
 * @param available     false when the read failed outright — no topic could be measured
 * @param warnings      what degraded the read without emptying it (a truncated topic list, a
 *                      topic the cluster does not know)
 */
public record TopicActivityResponse(
    Map<String, TopicActivity> topics,
    long windowStartMs,
    long windowEndMs,
    long bucketMs,
    int buckets,
    boolean available,
    List<String> warnings
) {
    public TopicActivityResponse {
        topics = topics == null ? Map.of() : Map.copyOf(topics);
        warnings = warnings == null ? List.of() : List.copyOf(warnings);
    }

    public static TopicActivityResponse unavailable(long windowStartMs, long windowEndMs,
                                                    long bucketMs, int buckets, String error) {
        return new TopicActivityResponse(Map.of(), windowStartMs, windowEndMs, bucketMs, buckets,
                false, List.of(error));
    }
}
