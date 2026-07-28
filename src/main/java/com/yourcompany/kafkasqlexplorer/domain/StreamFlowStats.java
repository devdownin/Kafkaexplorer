// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors
package com.yourcompany.kafkasqlexplorer.domain;

/**
 * What the trace actually covered.
 *
 * <p>A stream-flow trace is a bounded scan: it reads the last N records of each topic in scope,
 * under a wall-clock budget. Without these numbers "no flow found" is indistinguishable from "the
 * budget ran out before the topic holding the key was reached", and a partial pipeline reads as a
 * complete one. Every field here exists so the UI can state the bounds of what it drew.
 *
 * @param topicsInScope       topics the trace intended to read
 * @param topicsScanned       topics actually read
 * @param topicsSkipped       topics dropped because the time budget was spent
 * @param topicsFailed        topics whose read raised an error
 * @param messagesScanned     records examined across every topic
 * @param matches             records that matched the criterion
 * @param durationMs          wall-clock duration of the trace
 * @param truncated           true when at least one topic hit its per-topic record cap, so older
 *                            matches may exist beyond what was read
 * @param stopReason          COMPLETE or TIME_BUDGET
 * @param maxMessagesPerTopic per-topic record cap effectively applied
 * @param timeLimitMinutes    time window applied, null when the scan simply read the most recent
 *                            records of each topic
 */
public record StreamFlowStats(
        int topicsInScope,
        int topicsScanned,
        int topicsSkipped,
        int topicsFailed,
        int messagesScanned,
        int matches,
        long durationMs,
        boolean truncated,
        String stopReason,
        int maxMessagesPerTopic,
        Integer timeLimitMinutes
) {
}
