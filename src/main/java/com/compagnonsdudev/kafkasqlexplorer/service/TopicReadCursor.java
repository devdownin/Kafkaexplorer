// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors
package com.compagnonsdudev.kafkasqlexplorer.service;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.TopicPartition;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Where a read has got to, measured by the records it was actually handed.
 *
 * <p>The one thing this class exists to replace is {@code consumer.position(tp)}. That is not a
 * consumption watermark: it is the client's <em>fetch</em> position, which the consumer advances
 * as responses are buffered in the background across every assigned partition, rather than as
 * records are returned from {@code poll()}. Measured against the demo broker, a single poll
 * delivered two records while {@code position()} reported the log end for all eighteen partitions
 * — so a loop that exits on "position reached the end offsets" stops with records already in
 * flight and reports the fraction it read as the whole.
 *
 * <p>That defect has been found and fixed twice here already, in {@code KafkaAdminService.drain}
 * and in {@code KafkaSnapshotReader}, each time with a private cursor of its own; the two startup
 * restores ({@link MetricService}, {@link FieldMappingStore}) still steered by {@code position()}
 * and could therefore lose metric configurations and field mappings at boot, silently, while
 * logging "Restored N" as though N were the total. One definition rather than a fourth copy — the
 * same argument that produced {@code SecureXml}, {@code LogSafe} and {@code EventTime}.
 *
 * <p>Pure: it asks the consumer nothing. It is seeded from the offsets the caller <em>seeked to</em>
 * and from end offsets taken once, up front, and it moves only in {@link #advance(ConsumerRecord)}.
 *
 * <p>Not thread-safe: a cursor belongs to the one thread driving its poll loop.
 */
final class TopicReadCursor {

    private final Map<TopicPartition, Long> next;
    private final Map<TopicPartition, Long> end;

    private TopicReadCursor(Map<TopicPartition, Long> next, Map<TopicPartition, Long> end) {
        this.next = next;
        this.end = end;
    }

    /**
     * A cursor over the partitions the caller seeked, against the end offsets it read.
     *
     * <p>A partition absent from {@code startOffsets} carries no bookkeeping entry rather than a
     * wrong one, and so does one absent from {@code endOffsets}: turning a missing entry into an
     * unbounded read is the opposite of the bounding this exists for. Both maps are copied — the
     * caller's may well be the consumer's own view, which moves.
     */
    static TopicReadCursor of(Map<TopicPartition, Long> startOffsets, Map<TopicPartition, Long> endOffsets) {
        return new TopicReadCursor(new LinkedHashMap<>(startOffsets), new HashMap<>(endOffsets));
    }

    /** Records that this read has been handed {@code record}, and nothing more. */
    void advance(ConsumerRecord<?, ?> record) {
        TopicPartition tp = new TopicPartition(record.topic(), record.partition());
        next.merge(tp, record.offset() + 1, Math::max);
    }

    /** True while at least one partition still holds records this read has not been handed. */
    boolean hasUnread() {
        return hasUnread(next.keySet(), end, next);
    }

    /**
     * The canonical comparison, so the copies that predate this class can delegate to it rather
     * than restate it. A partition with no end offset, or none in the cursor, counts as read.
     */
    static boolean hasUnread(Iterable<TopicPartition> partitions,
                             Map<TopicPartition, Long> endOffsets,
                             Map<TopicPartition, Long> nextOffsets) {
        for (TopicPartition tp : partitions) {
            Long endOffset = endOffsets.get(tp);
            Long nextOffset = nextOffsets.get(tp);
            if (endOffset != null && nextOffset != null && nextOffset < endOffset) return true;
        }
        return false;
    }
}
