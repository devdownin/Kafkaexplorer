// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors
package com.compagnonsdudev.kafkasqlexplorer.mcp.console;

/**
 * A minted approval token, or why none was minted.
 *
 * <p>The token is returned once and kept nowhere the console can read again: it is a bearer
 * credential, and an endpoint that could re-read it would turn a single-use approval into a
 * standing one for anyone who can reach this application.
 *
 * @param token     the token to pass as {@code _approvalToken}, {@code null} when none was minted
 * @param tool      the tool it is good for — one call of that tool and no other
 * @param boundTo   the caller it is good for, or {@code null} when any caller may spend it
 * @param expiresInMinutes how long it stays usable
 * @param message   why no token was minted, {@code null} when one was
 */
public record McpApprovalResult(String token, String tool, String boundTo, long expiresInMinutes,
                                String message) {

    static McpApprovalResult minted(String token, String tool, String boundTo, long expiresInMinutes) {
        return new McpApprovalResult(token, tool, boundTo, expiresInMinutes, null);
    }

    static McpApprovalResult refused(String message) {
        return new McpApprovalResult(null, null, null, 0L, message);
    }
}
