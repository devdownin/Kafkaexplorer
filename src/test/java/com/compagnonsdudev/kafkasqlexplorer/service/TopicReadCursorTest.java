// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors
package com.compagnonsdudev.kafkasqlexplorer.service;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The cursor exists to replace {@code consumer.position()}, so what is pinned here is that it
 * answers about records <em>delivered</em> and about nothing else.
 */
class TopicReadCursorTest {

    private static final TopicPartition P0 = new TopicPartition("t", 0);
    private static final TopicPartition P1 = new TopicPartition("t", 1);

    private static ConsumerRecord<String, String> record(TopicPartition tp, long offset) {
        return new ConsumerRecord<>(tp.topic(), tp.partition(), offset, "k", "v");
    }

    @Test
    void aPartitionWithRecordsBeforeItsEndIsUnread() {
        TopicReadCursor cursor = TopicReadCursor.of(Map.of(P0, 0L), Map.of(P0, 3L));

        assertTrue(cursor.hasUnread());
    }

    @Test
    void onlyDeliveredRecordsMoveIt() {
        TopicReadCursor cursor = TopicReadCursor.of(Map.of(P0, 0L), Map.of(P0, 2L));

        cursor.advance(record(P0, 0));
        assertTrue(cursor.hasUnread(), "one of two records is not the whole partition");

        cursor.advance(record(P0, 1));
        assertFalse(cursor.hasUnread());
    }

    /** One partition finished says nothing about its neighbour — that is the multi-topic case. */
    @Test
    void oneFinishedPartitionDoesNotFinishTheRead() {
        TopicReadCursor cursor = TopicReadCursor.of(Map.of(P0, 0L, P1, 0L), Map.of(P0, 1L, P1, 1L));

        cursor.advance(record(P0, 0));

        assertTrue(cursor.hasUnread());
        cursor.advance(record(P1, 0));
        assertFalse(cursor.hasUnread());
    }

    /** A partition seeked to its end carries no work, and an empty one is finished on arrival. */
    @Test
    void aPartitionSeekedToItsEndIsAlreadyRead() {
        assertFalse(TopicReadCursor.of(Map.of(P0, 4L), Map.of(P0, 4L)).hasUnread());
        assertFalse(TopicReadCursor.of(Map.of(P0, 0L), Map.of(P0, 0L)).hasUnread());
    }

    /**
     * A missing end offset counts as read. Turning "we do not know where this partition ends" into
     * an unbounded read is the opposite of the bounding the cursor exists for — the same rule
     * {@code KafkaAdminService} states for the partition it seeks to the end.
     */
    @Test
    void aPartitionWithNoKnownEndCountsAsRead() {
        TopicReadCursor cursor = TopicReadCursor.of(Map.of(P0, 0L, P1, 0L), Map.of(P0, 0L));

        assertFalse(cursor.hasUnread());
    }

    /** Out-of-order delivery must not walk the cursor backwards. */
    @Test
    void theCursorNeverGoesBackwards() {
        TopicReadCursor cursor = TopicReadCursor.of(Map.of(P0, 0L), Map.of(P0, 3L));

        cursor.advance(record(P0, 2));
        cursor.advance(record(P0, 0));

        assertFalse(cursor.hasUnread());
    }
}
