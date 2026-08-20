// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors
package com.compagnonsdudev.kafkasqlexplorer.domain;

/**
 * What {@code GET /api/data-model/limits} answers: the bounds the Data Model page has to respect.
 *
 * <p>It exists so the browser stops carrying its own copy of the number. The page used to declare
 * {@code MAX_TOPICS = 30} beside the Java constant of the same value — two hand-kept copies of one
 * rule, which is the drift this codebase keeps removing. Now the ceiling is configurable
 * ({@code explorer.data-model-max-topics}) and the page reads it, so raising it in the deployment
 * raises what the UI offers, with nothing to edit twice.
 *
 * <p>{@code perTopicTimeoutMs} and {@code inferenceThreads} are served for the same reason, and
 * they answer a different question: <em>how long may this request take?</em> A run is one POST
 * that answers when every topic is inferred, so the browser has to decide how long to wait — and
 * it was waiting a flat two minutes, which was survivable while the ceiling was 30 and is not at
 * 100. Deriving that wait needs the per-topic budget and how many topics run at once; both are
 * server constants, and a UI that copies a server constant lies the day it changes. So they
 * travel. What the page does with them is its own business: this record states facts, not a
 * deadline.
 *
 * @param maxTopics         the hard ceiling one request may analyse
 * @param defaultMaxTopics  what a request analyses when it does not ask for a number
 * @param perTopicTimeoutMs how long one topic's inference may take before it is given up on
 * @param inferenceThreads  how many topics are inferred at once
 */
public record DataModelLimits(int maxTopics, int defaultMaxTopics,
                              long perTopicTimeoutMs, int inferenceThreads) {
}
