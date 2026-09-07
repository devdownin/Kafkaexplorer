// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors
package com.compagnonsdudev.kafkasqlexplorer.mcp.observability;

import java.util.function.Supplier;

/**
 * Who initiated the call currently being served on this thread.
 *
 * <p>A {@link ThreadLocal}, and the alternative was worse. The interception point is the SDK's
 * {@code BiFunction<McpSyncServerExchange, CallToolRequest, CallToolResult>} — a signature this
 * application does not own and cannot add a parameter to. Everything between the console's
 * controller and the tool body is synchronous and in-process, so the thread genuinely is the
 * request here; the value is set around one call and always cleared.
 *
 * <p>Why it matters at all: a console "Try it" runs the real tool through the real guard, so it
 * really does cost the cluster a read — it belongs in the load figures. But it is not agent
 * traffic, and an operator asking "what has my agent been doing?" must not be shown their own
 * clicks. One column, two meanings, kept apart.
 */
public final class McpCallOrigin {

    private static final ThreadLocal<McpCallRecord.Origin> CURRENT = new ThreadLocal<>();

    private McpCallOrigin() {
    }

    /** The origin in force, {@code AGENT} unless something said otherwise. */
    public static McpCallRecord.Origin current() {
        McpCallRecord.Origin origin = CURRENT.get();
        return origin == null ? McpCallRecord.Origin.AGENT : origin;
    }

    /**
     * Runs {@code action} with this origin in force, restoring the previous value afterwards.
     *
     * <p>Restores rather than clears, so a nesting — a prompt invoking a tool from inside a console
     * call — leaves the outer attribution intact instead of silently downgrading it to AGENT.
     */
    public static <T> T as(McpCallRecord.Origin origin, Supplier<T> action) {
        McpCallRecord.Origin previous = CURRENT.get();
        CURRENT.set(origin);
        try {
            return action.get();
        } finally {
            if (previous == null) {
                CURRENT.remove();
            } else {
                CURRENT.set(previous);
            }
        }
    }
}
