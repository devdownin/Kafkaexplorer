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

    UNAUTHENTICATED(-32001, "authentication required", Level.PROTOCOL),
    RATE_LIMITED(-32029, "rate limit exceeded", Level.PROTOCOL),
    TAINTED(-32040, "a value read from the cluster cannot be used as a mutating argument", Level.PROTOCOL),
    OUT_OF_SCOPE(-32041, "outside the configured resource scope", Level.PROTOCOL),
    APPROVAL_REQUIRED(-32042, "an approval token is required for this tool", Level.PROTOCOL),
    DEPENDENCY_UNAVAILABLE(-32043, "a dependency is unavailable", Level.EXECUTION),
    POLICY_DENIED(-32044, "denied by the policy engine", Level.PROTOCOL),
    EXFILTRATION_BLOCKED(-32045, "blocked: the payload carries data that must not leave", Level.PROTOCOL),
    VALIDATION_FAILED(-32046, "the arguments are not valid", Level.EXECUTION),
    QUARANTINED(-32047, "this identity is quarantined", Level.PROTOCOL);

    /**
     * Which of MCP's two error channels carries this code.
     *
     * <p>The distinction is the protocol's, not ours, and it decides whether the model ever reads
     * the message. The SDK documents {@code CallToolResult.isError} as "the tool <em>execution</em>
     * failed and the content contains error information" — that result goes back to the model,
     * which can act on it. A JSON-RPC error is a failure of the call itself, and a client is
     * entitled to surface it as a transport fault without showing the model anything.
     *
     * <p>So a refusal the model must not try to talk its way around travels as a JSON-RPC error,
     * and a failure the model is expected to read and correct travels as a result.
     */
    public enum Level {

        /**
         * A permission or protocol failure: the call should not have been made. Nothing the model
         * can rewrite fixes it, and it must not be encouraged to retry variations — an agent
         * probing around a scope refusal is precisely what the guard exists to stop.
         */
        PROTOCOL,

        /**
         * The tool ran and could not produce an answer. The message is the useful part: a planner's
         * "unknown column ORDR_ID at line 1, column 8" is what turns a failed call into a correct
         * one on the next attempt, and it only helps if the model sees it.
         */
        EXECUTION
    }

    private final int code;
    private final String summary;
    private final Level level;

    McpErrorCode(int code, String summary, Level level) {
        this.code = code;
        this.summary = summary;
        this.level = level;
    }

    public int code() { return code; }
    public String summary() { return summary; }
    public Level level() { return level; }

    /** True when this code must reach the model as tool output rather than as a JSON-RPC error. */
    public boolean reportedToTheModel() { return level == Level.EXECUTION; }
}
