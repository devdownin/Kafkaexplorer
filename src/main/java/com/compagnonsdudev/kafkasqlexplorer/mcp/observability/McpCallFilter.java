// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors
package com.compagnonsdudev.kafkasqlexplorer.mcp.observability;

import java.time.Instant;
import java.util.function.Predicate;

/**
 * The supervision screen's filter, as a value the console URL can round-trip.
 *
 * <p>A record rather than a lambda because the screen's state travels in the URL — "look at what
 * the agent did between 14:00 and 15:00" has to be a link, as it already is on Stream Flow and
 * Dead Letter — and a predicate cannot be put in a query string.
 *
 * <p>Every field is nullable and null means "no restriction", so the default is the unfiltered
 * feed rather than an empty one.
 */
public record McpCallFilter(
        Instant since,
        String tool,
        McpCallRecord.Outcome outcome,
        String identity,
        McpCallRecord.Origin origin
) implements Predicate<McpCallRecord> {

    public static McpCallFilter all() {
        return new McpCallFilter(null, null, null, null, null);
    }

    public static McpCallFilter since(Instant since) {
        return new McpCallFilter(since, null, null, null, null);
    }

    @Override
    public boolean test(McpCallRecord call) {
        if (since != null && call.startedAt().isBefore(since)) {
            return false;
        }
        if (tool != null && !tool.equals(call.tool())) {
            return false;
        }
        if (outcome != null && outcome != call.outcome()) {
            return false;
        }
        if (identity != null && !identity.equals(call.identity())) {
            return false;
        }
        return origin == null || origin == call.origin();
    }
}
