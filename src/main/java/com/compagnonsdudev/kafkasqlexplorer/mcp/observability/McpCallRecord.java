// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors
package com.compagnonsdudev.kafkasqlexplorer.mcp.observability;

import com.compagnonsdudev.kafkasqlexplorer.mcp.contract.Measured;
import com.compagnonsdudev.kafkasqlexplorer.mcp.contract.StopReason;
import com.compagnonsdudev.kafkasqlexplorer.mcp.guard.McpGuard;

import java.time.Instant;
import java.util.Map;

/**
 * One MCP call, as the console shows it and the audit topic keeps it.
 *
 * <p>Refusals are recorded in the same shape as successes, which is why {@link Outcome} has three
 * values rather than a boolean: a control that blocks silently is a control nobody ever tunes, and
 * the operator asking "why does my agent get nothing back?" has to find the answer here.
 *
 * <p>{@code recordsScanned} is {@link Measured} rather than a {@code long} for the reason the whole
 * module exists: several tools do not count records at all, and a zero for those would put "read
 * nothing" and "does not count" in the same column of the same table.
 *
 * @param correlationId    ties this call to its response and to the audit entry
 * @param startedAt        when the call was accepted
 * @param durationMs       wall clock, refusals included — a fast refusal is a signal
 * @param origin           who initiated it; console calls count as load but are not agent traffic
 * @param identity         OAuth subject, or {@code local (stdio)}
 * @param clientInfo       what the client declared at {@code initialize}, e.g. {@code claude-code/1.4.2}
 * @param tool             the tool name as the agent named it
 * @param redactedParams   arguments <b>after</b> DLP — never the raw ones, see {@code DlpScrubber}
 * @param outcome          OK, DENIED or ERROR
 * @param jsonRpcErrorCode the KIP-1318 code returned, null when OK
 * @param deniedByGuard    which control refused, null when it was not a refusal
 * @param recordsScanned   records read, unmeasured for the tools that do not count
 * @param stopReason       the coverage's stop reason, null for a tool with no envelope
 * @param truncated        whether a ceiling cut the payload
 * @param outputBytes      size of the serialised response, unmeasured when it could not be
 *                         computed — a size that is unknown is not a size of zero, and the console
 *                         column it feeds is read as induced load
 */
public record McpCallRecord(
        String correlationId,
        Instant startedAt,
        long durationMs,
        Origin origin,
        String identity,
        String clientInfo,
        String tool,
        Map<String, Object> redactedParams,
        Outcome outcome,
        Integer jsonRpcErrorCode,
        McpGuard deniedByGuard,
        Measured<Long> recordsScanned,
        StopReason stopReason,
        boolean truncated,
        Measured<Long> outputBytes
) {

    public enum Origin { AGENT, CONSOLE, PROMPT }

    public enum Outcome { OK, DENIED, ERROR }

    public McpCallRecord {
        redactedParams = redactedParams == null ? Map.of() : Map.copyOf(redactedParams);
    }

    /** True when the tool answered but did not cover everything it was asked to. */
    public boolean partialCoverage() {
        return stopReason != null && stopReason != StopReason.EXHAUSTED;
    }
}
