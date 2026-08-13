// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors
package com.compagnonsdudev.kafkasqlexplorer.domain;

/**
 * What an earlier pass of the same trace already covered, echoed back when it is continued.
 *
 * <p>A continued trace draws one graph out of two passes. Reporting only the second pass's numbers
 * next to that graph would state "2/2 topics scanned" under a chain that rests on two hundred and
 * fifty — the page's whole discipline is that the coverage line describes what the picture rests
 * on, so the counts are summed rather than replaced.
 *
 * <p>Client-supplied, like {@link StreamFlowRequest#priorHits()}: it is the caller's own previous
 * response handed back. There is nothing to protect here — a caller that falsifies it only
 * misleads itself — and the alternative, keeping every trace server-side between two requests,
 * would be a session store for a stateless read.
 */
public record StreamFlowCoverage(
        int topicsScanned,
        int messagesScanned,
        int matches,
        long durationMs
) {

    public static StreamFlowCoverage none() {
        return new StreamFlowCoverage(0, 0, 0, 0L);
    }

    /** Null-safe, because an absent property arrives as {@code null} through Jackson. */
    public static StreamFlowCoverage orNone(StreamFlowCoverage coverage) {
        return coverage == null ? none() : coverage;
    }
}
