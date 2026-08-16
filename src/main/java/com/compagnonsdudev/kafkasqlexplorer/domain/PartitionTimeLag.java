// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors
package com.compagnonsdudev.kafkasqlexplorer.domain;

/**
 * How far behind <em>in time</em> a consumer group is on one partition.
 *
 * <p>A record lag says how many messages are waiting; it does not say whether that is two seconds
 * or two days of traffic. Ten thousand records on a topic doing a thousand a second is ten
 * seconds; the same ten thousand on a topic doing one an hour is a backlog nobody will clear this
 * week. Only the timestamps answer that, and this is where they are read.
 *
 * <p>{@code lagMs} is the age of the <b>oldest record still waiting</b> — the one sitting at the
 * committed offset — measured against the moment of the read. It is deliberately not
 * {@code latestTimestamp - committedTimestamp}: a producer that stops has a backlog that keeps
 * ageing, and the operator's question ("how stale is what this consumer serves?") is answered by
 * the age, not by the span the backlog covers.
 *
 * <p>Every field that could be absent is boxed, and for the same reason as {@link PartitionLag}: a
 * measurement that could not be taken is {@code null}, never {@code 0}. Zero here means "caught
 * up", which is the exact opposite of "we could not tell", and a zero exported as a gauge silences
 * the alert that should have fired.
 *
 * @param partition          partition number
 * @param committedOffset    the group's committed offset, {@code null} when it never committed
 * @param endOffset          log end offset at the moment of the read
 * @param recordLag          {@code endOffset - committedOffset}, {@code null} without a commit
 * @param lagMs              age of the oldest waiting record, {@code 0} when caught up,
 *                           {@code null} when it could not be measured
 * @param oldestWaitingTimestamp timestamp of the record at the committed offset, {@code null}
 *                           when caught up or unread
 * @param note               why {@code lagMs} is null — a compacted offset, a read that ran out of
 *                           budget — {@code null} when the measurement succeeded
 */
public record PartitionTimeLag(
    int partition,
    Long committedOffset,
    long endOffset,
    Long recordLag,
    Long lagMs,
    Long oldestWaitingTimestamp,
    String note
) {
    public static PartitionTimeLag caughtUp(int partition, long committedOffset, long endOffset) {
        return new PartitionTimeLag(partition, committedOffset, endOffset, 0L, 0L, null, null);
    }

    public static PartitionTimeLag unknown(int partition, Long committedOffset, long endOffset,
                                           Long recordLag, String note) {
        return new PartitionTimeLag(partition, committedOffset, endOffset, recordLag, null, null, note);
    }
}
