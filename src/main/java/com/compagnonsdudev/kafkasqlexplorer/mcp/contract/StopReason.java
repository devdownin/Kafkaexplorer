// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors
package com.compagnonsdudev.kafkasqlexplorer.mcp.contract;

/**
 * Why a pass stopped. The one value that means "the answer is complete" is {@link #EXHAUSTED};
 * every other value turns an empty result from a fact into an absence of evidence.
 *
 * <p>It is carried in {@link Coverage} rather than inferred from a count, because "zero rows" and
 * "zero rows so far" are indistinguishable once the reason is dropped — and a language model
 * reading the payload will resolve that ambiguity in the confident direction unless told.
 */
public enum StopReason {

    /** Everything the request asked for was read. Only here is an empty result a negative answer. */
    EXHAUSTED,

    /** The time budget ran out. What was not reached is named in {@link Coverage#topicsNotReached()}. */
    TIME_BUDGET,

    /** The per-call topic ceiling was hit before the topic list was finished. */
    TOPIC_LIMIT,

    /** The record ceiling was hit. Later records exist and were not looked at. */
    RECORD_LIMIT,

    /** The caller or the server cancelled the pass. */
    CANCELLED,

    /** Some sources answered and some failed; the failures are named in the coverage. */
    PARTIAL_FAILURE
}
