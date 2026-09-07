// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors
package com.compagnonsdudev.kafkasqlexplorer.mcp.tools;

import com.compagnonsdudev.kafkasqlexplorer.mcp.observability.ToolCategory;

/**
 * A bean holding {@code @McpTool} methods.
 *
 * <p>It exists so {@code McpServerConfiguration} can enumerate the toolsets by type rather than by
 * a hand-kept list — a list written next to the beans is a list that forgets the tool added last
 * week, and a tool missing from the catalogue is invisible on the very screen built to make the
 * surface visible.
 */
public interface McpToolset {

    /** How the console groups this set's tools. */
    ToolCategory category();
}
