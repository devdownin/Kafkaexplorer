// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors
package com.compagnonsdudev.kafkasqlexplorer.mcp.observability;

import com.compagnonsdudev.kafkasqlexplorer.mcp.contract.Measured;
import com.compagnonsdudev.kafkasqlexplorer.mcp.contract.StopReason;
import com.compagnonsdudev.kafkasqlexplorer.mcp.contract.ToolResult;

import java.util.Map;

/**
 * Reads the coverage envelope back out of a tool's response, for the console row.
 *
 * <p><b>It reads a {@link Map}, not a {@link ToolResult}, and that is not a shortcut.</b> By the
 * time the interception layer sees the response, Spring AI has already serialised the tool's return
 * value to JSON and parsed it back as a plain {@code Object} — that round trip is how MCP structured
 * content is produced. The first version of this class tested {@code instanceof ToolResult}, which
 * cannot ever match there: every call would have been recorded as "this tool does not count
 * records", and the induced-load column would have read empty on a server doing real work. A
 * self-inflicted instance of exactly the failure the envelope exists to prevent.
 *
 * <p>Every read is defensive. A tool may legitimately return something with no envelope, and a
 * missing field must cost one column of one console row, never the call that produced it.
 */
final class McpCallContext {

    private McpCallContext() {
    }

    /**
     * How many records the tool reports having read, {@link Measured#unmeasured} when it does not
     * say.
     *
     * <p>Unmeasured rather than zero, for the reason the whole module exists applied to its own
     * bookkeeping: {@code kex_list_tables} reads no Kafka record at all, and a zero for it would
     * sit in the same column as a tool that read nothing because the topic was empty. The "induced
     * load" figure an operator uses to answer "is the agent hurting the cluster?" is that column's
     * sum, and one meaning has to be one number.
     */
    static Measured<Long> recordsScanned(Object payload) {
        Object scanned = fromCoverage(payload, "recordsScanned");
        if (scanned instanceof Number n) {
            return Measured.of(n.longValue());
        }
        return Measured.unmeasured("this tool reported no coverage envelope");
    }

    /** The pass's stop reason, null when the payload carries no envelope or names an unknown one. */
    static StopReason stopReason(Object payload) {
        if (!(fromCoverage(payload, "stopReason") instanceof String name)) {
            return null;
        }
        try {
            return StopReason.valueOf(name);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** Whether a ceiling inside the tool cut its own payload. */
    static boolean truncated(Object payload) {
        return payload instanceof Map<?, ?> map && Boolean.TRUE.equals(map.get("truncated"));
    }

    private static Object fromCoverage(Object payload, String field) {
        if (payload instanceof Map<?, ?> map && map.get("coverage") instanceof Map<?, ?> coverage) {
            return coverage.get(field);
        }
        return null;
    }
}
