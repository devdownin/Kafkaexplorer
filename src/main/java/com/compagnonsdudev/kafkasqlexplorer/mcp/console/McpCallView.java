// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors
package com.compagnonsdudev.kafkasqlexplorer.mcp.console;

import com.compagnonsdudev.kafkasqlexplorer.mcp.contract.Measured;
import com.compagnonsdudev.kafkasqlexplorer.mcp.contract.StopReason;
import com.compagnonsdudev.kafkasqlexplorer.mcp.observability.McpCallRecord;

import java.time.Instant;
import java.util.Map;

/**
 * One row of the live call feed.
 *
 * <p>A view of {@link McpCallRecord} rather than the record itself, because the two answer to
 * different constraints: the record is what the audit topic will carry, and this is what a browser
 * renders. Keeping them separate is what lets the audit shape change without breaking a page, and
 * what keeps a field the page never shows from being sent to it.
 *
 * <p>{@code partialCoverage} is precomputed rather than left to the page. It is the column an
 * operator scans for — a response the agent received incomplete is where a false conclusion starts
 * — and deriving it in the browser would put the rule in two languages.
 *
 * @param partialCoverage true when the tool answered but did not cover what it was asked
 */
public record McpCallView(
        String correlationId,
        Instant startedAt,
        long durationMs,
        McpCallRecord.Origin origin,
        String identity,
        String clientInfo,
        String tool,
        Map<String, Object> redactedParams,
        McpCallRecord.Outcome outcome,
        Integer jsonRpcErrorCode,
        String deniedByGuard,
        Measured<Long> recordsScanned,
        StopReason stopReason,
        boolean partialCoverage,
        boolean truncated,
        Measured<Long> outputBytes
) {

    /** The parameters are already redacted in the record — this never re-reads a raw value. */
    public static McpCallView from(McpCallRecord call) {
        return new McpCallView(
                call.correlationId(),
                call.startedAt(),
                call.durationMs(),
                call.origin(),
                call.identity(),
                call.clientInfo(),
                call.tool(),
                call.redactedParams(),
                call.outcome(),
                call.jsonRpcErrorCode(),
                call.deniedByGuard() == null ? null : call.deniedByGuard().name(),
                call.recordsScanned(),
                call.stopReason(),
                call.partialCoverage(),
                call.truncated(),
                call.outputBytes());
    }
}
