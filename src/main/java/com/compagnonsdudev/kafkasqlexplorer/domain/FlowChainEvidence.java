// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors
package com.compagnonsdudev.kafkasqlexplorer.domain;

import java.util.List;

/**
 * One Stream Flow trace, as the browser recorded it, carried back so the same generator can derive
 * KPIs from a chain and from an audit report.
 *
 * <p>Traces are run from a page and kept in {@code localStorage}: the server never sees one unless
 * the caller sends it. Deriving flow KPIs in the browser instead would have put the rule in two
 * places — the audit's flows are server-side — which is how two answers to one question start to
 * disagree.
 *
 * <p>Every component is boxed. Jackson binds a record through its canonical constructor, so an
 * absent property arrives as {@code null} and a primitive component would fail the whole request
 * on a body that is merely partial.
 *
 * @param messageKey  the key that was traced, for the evidence line
 * @param searchPath  the path or header the trace matched on, null for a key search
 * @param tracedAt    epoch millis of the run, null when the entry predates that field
 * @param hops        the chain in order, first sighting first
 */
public record FlowChainEvidence(
    String messageKey,
    String searchPath,
    Long tracedAt,
    List<FlowChainHop> hops
) {
    /**
     * One topic of a traced chain.
     *
     * @param topic                 topic the key was seen in
     * @param firstTimestamp        first sighting there (epoch millis), null when unknown
     * @param latencyFromPreviousMs delta from the previous hop, null on the first hop — and
     *                              negative when producer clocks disagree, which is reported
     *                              rather than hidden
     * @param occurrences           records matched in that topic, null when not recorded
     */
    public record FlowChainHop(
        String topic,
        Long firstTimestamp,
        Long latencyFromPreviousMs,
        Integer occurrences
    ) {}
}
