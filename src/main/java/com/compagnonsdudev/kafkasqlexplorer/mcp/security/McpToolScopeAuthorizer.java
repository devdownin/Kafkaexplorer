// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors
package com.compagnonsdudev.kafkasqlexplorer.mcp.security;

import com.compagnonsdudev.kafkasqlexplorer.mcp.McpProperties;
import com.compagnonsdudev.kafkasqlexplorer.mcp.guard.McpErrorCode;
import com.compagnonsdudev.kafkasqlexplorer.mcp.guard.McpGuard;
import com.compagnonsdudev.kafkasqlexplorer.mcp.guard.McpToolException;

import java.util.Set;

/**
 * Enforces verified OAuth scopes at the actual MCP tool dispatch boundary.
 *
 * <p>HTTP /mcp multiplexes all tool names; route scopes alone cannot express tool permissions.
 * A tool with an explicitly configured extra scope must have BOTH mcp.read (or the configured
 * read-scope) and its own scope. The extra scope does not open write-capable toolsets:
 * OAuth still requires readonly=true at startup.
 *
 * <p>Only the JWT verification filter can populate request-local verifiedScopes. Missing
 * context (such as a local stdio call during an OAuth deployment) is a denial, not a bypass.
 */
public final class McpToolScopeAuthorizer {
    private final McpProperties properties;

    public McpToolScopeAuthorizer(McpProperties properties) {
        this.properties = properties;
    }

    public void requirePermission(String tool) {
        McpProperties.OAuth oauth = properties.getOauth();
        if (oauth == null || !oauth.isEnabled()) return;

        Set<String> verified = McpCallerContext.verifiedScopes();
        if (!verified.contains(oauth.getReadScope())) {
            refuse(tool, oauth.getReadScope());
        }
        String additional = oauth.getToolScopes().get(tool);
        if (additional != null && !verified.contains(additional)) {
            refuse(tool, additional);
        }
    }

    private static void refuse(String tool, String scope) {
        throw new McpToolException(McpErrorCode.POLICY_DENIED, McpGuard.POLICY,
                "MCP tool " + tool + " requires the verified OAuth scope " + scope
                        + "; no execution or approval-token consumption occurred");
    }
}
