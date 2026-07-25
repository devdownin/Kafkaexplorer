// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors
package com.yourcompany.kafkasqlexplorer.domain;

import java.util.Map;

/**
 * One Kafka record as the UI sees it. Sample lists used to be bare value strings, which made it
 * impossible to search by key or to tell where a match actually lives — hence the coordinates.
 *
 * @param valueBytes size of the value before any truncation, so the UI can say "truncated"
 * @param truncated  the value was cut to keep the response small
 */
public record TopicMessage(
    int partition,
    long offset,
    long timestamp,
    String key,
    String value,
    Map<String, String> headers,
    int valueBytes,
    boolean truncated
) {
    public static TopicMessage of(int partition, long offset, long timestamp, String key,
                                   String value, Map<String, String> headers, int maxValueChars) {
        int size = value == null ? 0 : value.length();
        if (value != null && size > maxValueChars) {
            return new TopicMessage(partition, offset, timestamp, key,
                value.substring(0, maxValueChars), headers, size, true);
        }
        return new TopicMessage(partition, offset, timestamp, key, value, headers, size, false);
    }
}
