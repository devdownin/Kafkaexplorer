// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors
package com.compagnonsdudev.kafkasqlexplorer.mcp.guard;

/**
 * A refusal, carrying the KIP-1318 code the client sees and the control that produced it.
 *
 * <p>The two are separate fields because they answer different questions. The code is for the
 * agent — it is what tells a model to stop retrying rather than rephrase. The {@link McpGuard} is
 * for the operator reading the supervision screen, who needs to know *which* setting to change;
 * several guards share a code, and collapsing them would leave the console saying "denied,
 * -32041" over a screen with four different places to fix it.
 */
public class McpToolException extends RuntimeException {

    private final McpErrorCode errorCode;
    private final McpGuard guard;

    public McpToolException(McpErrorCode errorCode, McpGuard guard, String message) {
        super(message);
        this.errorCode = errorCode;
        this.guard = guard;
    }

    /**
     * A failure that is not a refusal — a dependency that did not answer, an argument that does not
     * parse. {@code guard} is null because no control blocked this, and the console must not file
     * it under one: a Kafka outage counted as a scope denial sends an operator to loosen a setting
     * that was never involved.
     */
    public McpToolException(McpErrorCode errorCode, String message) {
        this(errorCode, null, message);
    }

    public McpErrorCode errorCode() { return errorCode; }
    public McpGuard guard() { return guard; }
    public int jsonRpcCode() { return errorCode.code(); }
}
