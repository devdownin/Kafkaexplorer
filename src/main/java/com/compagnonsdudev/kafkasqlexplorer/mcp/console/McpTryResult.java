// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors
package com.compagnonsdudev.kafkasqlexplorer.mcp.console;

import io.modelcontextprotocol.spec.McpSchema.CallToolResult;

/**
 * What a console "Try it" produced — the agent's answer, unedited.
 *
 * <p>The raw structured content is handed back rather than a rendering of it, coverage envelope
 * included. The panel's whole value is showing what a model would actually receive, and a
 * prettified summary would hide precisely the thing an operator is there to inspect: whether the
 * response says what it did not read.
 *
 * @param tool              the tool that ran
 * @param invoked           false when the tool is not registered at all
 * @param isError           true when the tool reported an execution failure the model would read
 * @param jsonRpcErrorCode  set when a guard refused the call outright, null otherwise
 * @param message           the refusal or failure text, null on success
 * @param structuredContent the response payload, null when there was none
 */
public record McpTryResult(
        String tool,
        boolean invoked,
        boolean isError,
        Integer jsonRpcErrorCode,
        String message,
        Object structuredContent
) {

    static McpTryResult of(String tool, CallToolResult result) {
        boolean failed = Boolean.TRUE.equals(result.isError());
        String text = failed && result.content() != null && !result.content().isEmpty()
                ? result.content().getFirst().toString()
                : null;
        return new McpTryResult(tool, true, failed, null, text, result.structuredContent());
    }

    static McpTryResult refused(String tool, int code, String message) {
        return new McpTryResult(tool, true, true, code, message, null);
    }

    static McpTryResult notInvocable(String tool, String why) {
        return new McpTryResult(tool, false, true, null, why, null);
    }
}
