// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors
package com.yourcompany.kafkasqlexplorer.domain;

/**
 * How far a streamed trace has got.
 *
 * <p>A whole-cluster trace can legitimately take a minute. Emitted after every topic, this is what
 * turns that minute from a spinner into a scan the operator can watch and stop as soon as they have
 * seen enough.
 *
 * @param topicsCompleted topics read so far
 * @param topicsInScope   topics the trace intends to read
 * @param messagesScanned records examined so far
 * @param matches         records that matched so far
 * @param elapsedMs       wall-clock time since the trace started
 * @param lastTopic       the topic that just finished, null on the opening event
 */
public record StreamFlowProgress(
        int topicsCompleted,
        int topicsInScope,
        int messagesScanned,
        int matches,
        long elapsedMs,
        String lastTopic
) {
}
