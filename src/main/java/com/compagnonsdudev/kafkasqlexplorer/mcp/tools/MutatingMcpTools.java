// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors
package com.compagnonsdudev.kafkasqlexplorer.mcp.tools;

import com.compagnonsdudev.kafkasqlexplorer.mcp.observability.ToolCategory;

/**
 * A toolset that changes something. Registered only when {@code explorer.mcp.readonly=false}.
 *
 * <p>The guard is at <b>registration</b>, not invocation: Spring AI scans {@code @McpTool} methods
 * on every bean in the context, so the only way to make a tool genuinely unavailable is for its
 * bean not to exist. A check inside the method would still leave the tool listed by
 * {@code tools/list}, which is an invitation with a rejection attached — and one more thing for a
 * prompt to argue with.
 */
public interface MutatingMcpTools extends McpToolset {

    @Override
    default ToolCategory category() {
        return ToolCategory.WRITE;
    }
}
