// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors
package com.compagnonsdudev.kafkasqlexplorer.domain;

/**
 * What one topic of a Process Mining run actually contributed.
 *
 * <p>{@code messagesRead} and {@code messagesAnalysed} are deliberately two numbers rather than
 * one: the prompt has a global character budget, so a topic can be read in full and still reach
 * the model with a sample of its records — or, once the budget is spent, with none at all. A topic
 * that was read and not analysed is invisible in the answer, which is exactly what the model's
 * silence about it would otherwise be taken to mean.
 *
 * @param readable {@code false} when the broker described no partition for this topic: an absent
 *                 topic or a typo, not an empty one. "Nothing to say about it" and "we never
 *                 opened it" are different answers.
 */
public record TopicCoverage(
    String topic,
    int messagesRead,
    int messagesAnalysed,
    boolean readable
) {
}
