// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors
package com.compagnonsdudev.kafkasqlexplorer.mcp.guard;

/**
 * JSON-RPC error codes, taken from KIP-1318 unchanged.
 *
 * <p>Adopted rather than invented on purpose: an agent trained against the Apache Kafka MCP server
 * already knows what {@code -32041} means, and a private numbering would make this server's
 * refusals unreadable to it for no gain. The one thing a refusal must do is be understood.
 */
public enum McpErrorCode {

    UNAUTHENTICATED(-32001, "authentication required"),
    RATE_LIMITED(-32029, "rate limit exceeded"),
    TAINTED(-32040, "a value read from the cluster cannot be used as a mutating argument"),
    OUT_OF_SCOPE(-32041, "outside the configured resource scope"),
    APPROVAL_REQUIRED(-32042, "an approval token is required for this tool"),
    DEPENDENCY_UNAVAILABLE(-32043, "a dependency is unavailable"),
    POLICY_DENIED(-32044, "denied by the policy engine"),
    EXFILTRATION_BLOCKED(-32045, "blocked: the payload carries data that must not leave"),
    VALIDATION_FAILED(-32046, "the arguments are not valid"),
    QUARANTINED(-32047, "this identity is quarantined");

    private final int code;
    private final String summary;

    McpErrorCode(int code, String summary) {
        this.code = code;
        this.summary = summary;
    }

    public int code() { return code; }
    public String summary() { return summary; }
}
