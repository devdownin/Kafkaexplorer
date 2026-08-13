// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors
package com.compagnonsdudev.kafkasqlexplorer.domain;

/**
 * Where one consumer group stands on one partition.
 *
 * <p>{@code committedOffset} and {@code lag} are boxed on purpose. A group that has never
 * committed on a partition has no position there, and reporting that as {@code 0} would read as
 * "caught up at the beginning" — the opposite of the truth, which is that this partition is not
 * being read at all. {@code null} is the only honest answer, and the UI says "never committed"
 * rather than drawing a zero.
 *
 * <p>{@code lag} can legitimately be negative: a committed offset past the end offset is what an
 * offset reset to a future position, or a topic recreated under the same name, leaves behind. It
 * is reported rather than clamped — like the negative hop latencies in Stream Flow, it is the one
 * fact that explains a reading nobody would otherwise believe. The end offsets are deliberately
 * read *after* the committed ones, so a live consumer racing the two calls cannot manufacture one.
 *
 * @param partition       partition number
 * @param committedOffset last offset committed by the group, {@code null} if it never committed
 * @param endOffset       the partition's log end offset at the moment of the read
 * @param lag             {@code endOffset - committedOffset}, {@code null} without a commit
 * @param memberId        the member currently assigned to this partition, {@code null} if none
 * @param clientId        that member's client id
 * @param host            that member's host
 */
public record PartitionLag(
        int partition,
        Long committedOffset,
        long endOffset,
        Long lag,
        String memberId,
        String clientId,
        String host
) {
}
