// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors
package com.compagnonsdudev.kafkasqlexplorer.mcp.security;

/**
 * Request-local authenticated MCP identity used by server-side continuation state.
 *
 * <p>The HTTP filter installs the identity before Spring AI dispatches a tool. Keeping it request
 * local means a resume token can be bound to the credential that created it without putting the
 * credential into the tool arguments, the token itself, or the audit trail.
 */
public final class McpCallerContext {

    private static final String LOCAL = "local";
    private static final ThreadLocal<String> CURRENT = new ThreadLocal<>();

    private McpCallerContext() {
    }

    public static void set(String identity) {
        CURRENT.set(identity == null || identity.isBlank() ? LOCAL : identity);
    }

    public static String identity() {
        String identity = CURRENT.get();
        return identity == null || identity.isBlank() ? LOCAL : identity;
    }

    public static void clear() {
        CURRENT.remove();
    }
}
