// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors
package com.compagnonsdudev.kafkasqlexplorer.mcp.contract;

import java.time.Instant;
import java.util.List;

/**
 * What a tool actually read, and what it did not.
 *
 * <p>This is the envelope every MCP tool response carries, and it exists because of the failure
 * mode that defines this module: an agent that receives {@code []} concludes "it does not exist"
 * when the truthful sentence is "it was not in the part I looked at". A raw {@code consume}
 * cannot tell those apart — nothing in its response says what was scanned — and that is the main
 * hallucination vector of every Kafka MCP server surveyed in {@code SPEC-MCP.md} §3(b).
 *
 * <p>{@code topicsNotReached} is <b>named, never counted</b>. "38 topics not scanned" lets a model
 * decide the remainder was unimportant; the list lets it — or the operator reading the console —
 * see that the one topic the question was about is in it.
 *
 * @param topicsRequested  how many topics the call meant to cover
 * @param topicsScanned    how many it actually reached
 * @param topicsNotReached the ones it did not, by name; empty when the pass was complete
 * @param recordsScanned   records read across every source
 * @param elapsedMs        wall clock spent
 * @param stopReason       why it stopped — see {@link StopReason}
 * @param windowStart      start of the time window actually covered, null when unbounded
 * @param windowEnd        end of that window, null when unbounded
 * @param resumeToken      opaque token to continue this pass, null when there is nothing left
 */
public record Coverage(
        int topicsRequested,
        int topicsScanned,
        List<String> topicsNotReached,
        long recordsScanned,
        long elapsedMs,
        StopReason stopReason,
        Instant windowStart,
        Instant windowEnd,
        String resumeToken
) {

    public Coverage {
        topicsNotReached = topicsNotReached == null ? List.of() : List.copyOf(topicsNotReached);
    }

    /** True when the pass read everything it set out to read — the only case a zero is a zero. */
    public boolean complete() {
        return stopReason == StopReason.EXHAUSTED;
    }

    /** A complete pass over {@code topics} sources having read {@code records} records. */
    public static Coverage exhausted(int topics, long records, long elapsedMs) {
        return new Coverage(topics, topics, List.of(), records, elapsedMs,
                StopReason.EXHAUSTED, null, null, null);
    }

    /** A complete pass over a single source. */
    public static Coverage exhausted(long records, long elapsedMs) {
        return exhausted(1, records, elapsedMs);
    }

    /**
     * A pass that stopped early. {@code topicsNotReached} is required rather than optional: a
     * partial pass whose remainder is unnamed is exactly the response this record exists to
     * prevent.
     */
    public static Coverage partial(int requested, int scanned, List<String> notReached,
                                   long records, long elapsedMs, StopReason reason, String resumeToken) {
        return new Coverage(requested, scanned, notReached, records, elapsedMs, reason, null, null, resumeToken);
    }
}
