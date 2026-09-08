// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors
package com.compagnonsdudev.kafkasqlexplorer.mcp.tools;

import java.util.List;

/**
 * What {@code kex_trace_key} returns: one business key's path through the cluster.
 *
 * <p>This is the tool the whole server exists to justify. Asked "where did ORD-1042 go?", every
 * other Kafka MCP server can only offer N {@code consume_messages} calls and the model's own
 * correlation — thousands of records through a context window, and nothing in the reply saying
 * which topics were never looked at. One call answers it here, and the envelope says what it
 * covered.
 */
public final class TraceView {

    private TraceView() {
    }

    /**
     * One topic the key was found in.
     *
     * @param topic            where it was found
     * @param occurrences      how many records matched in this topic
     * @param firstTimestampMs when it first appeared here
     * @param lastTimestampMs  when it last appeared here
     * @param partition        partition of the first match — with the offset, what makes it re-readable
     * @param offset           offset of the first match
     * @param key              the record key, DLP-scrubbed
     * @param preview          an extract of the payload, DLP-scrubbed
     * @param latencyFromPreviousMs time since the previous hop, null on the first hop and whenever
     *                         the two brokers' clocks make the difference meaningless
     * @param slowest          true on the hop that took longest — the one an operator looks for
     * @param occurrencesCapped true when this topic had more matches than the per-topic cap allowed
     */
    public record Hop(
            String topic,
            int occurrences,
            long firstTimestampMs,
            long lastTimestampMs,
            int partition,
            long offset,
            String key,
            String preview,
            Long latencyFromPreviousMs,
            boolean slowest,
            boolean occurrencesCapped
    ) {}

    /**
     * The trace itself.
     *
     * @param criterion  what was searched for, echoed so a resumed trace is self-describing
     * @param hops       the chain, in the order the key travelled
     * @param clockSkew  set when a hop appears to precede the one before it, which means the
     *                   brokers disagree about the time rather than that the message went backwards
     */
    public record Trace(String criterion, List<Hop> hops, String clockSkew) {}

    /**
     * {@code kex_compare_traces}.
     *
     * <p><b>Latency differences per hop, never absolute timestamps.</b> Two keys traced at different
     * moments have every timestamp different, so a diff of those is noise that looks like signal;
     * what an operator asks is "which step got slower for this one", and that is the per-hop delta.
     *
     * @param commonTopics  topics both keys reached, in order
     * @param onlyInA       topics only the first key reached
     * @param onlyInB       topics only the second reached — where a flow diverged
     * @param hopDeltas     per common topic, B's latency minus A's, in ms; null where either side
     *                      could not measure one
     */
    public record Comparison(
            List<String> commonTopics,
            List<String> onlyInA,
            List<String> onlyInB,
            List<HopDelta> hopDeltas
    ) {}

    public record HopDelta(String topic, Long latencyDeltaMs) {}
}
