// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors
package com.compagnonsdudev.kafkasqlexplorer.mcp.observability;

/**
 * Where an MCP call is kept beyond the console's memory ring.
 *
 * <p>An interface with a no-op default implementation because the two halves land in different
 * phases: the ring buffer and the metrics are phase 1, the append-only Kafka topic is phase 5. The
 * recorder is written against the interface from the start so the counter that tracks failed
 * appends — and the console field that surfaces it — exist before there is anything to fail.
 */
public interface McpAuditSink {

    void append(McpCallRecord call);

    /**
     * A sink that keeps nothing, for a deployment that has not configured the audit topic.
     *
     * <p>It reports itself as inactive rather than pretending: the console distinguishes "no calls
     * in this window" from "nothing is being persisted", and it can only do that if the sink says
     * which it is.
     */
    static McpAuditSink inactive() {
        return new McpAuditSink() {
            @Override
            public void append(McpCallRecord call) {
                // nothing to do; active() says so
            }

            @Override
            public boolean active() {
                return false;
            }
        };
    }

    /** Whether this sink actually persists. False makes the console say so instead of implying history exists. */
    default boolean active() {
        return true;
    }
}
