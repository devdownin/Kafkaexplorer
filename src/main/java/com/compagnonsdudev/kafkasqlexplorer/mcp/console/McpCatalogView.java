// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors
package com.compagnonsdudev.kafkasqlexplorer.mcp.console;

import java.util.List;

/**
 * What this server offers an agent right now, with the counters attached and the window they were
 * counted over.
 *
 * @param tools          every tool, exposed and withheld alike
 * @param observedWindow what the counters actually rest on
 */
public record McpCatalogView(List<McpToolRow> tools, ObservedWindow observedWindow) {

    public McpCatalogView {
        tools = tools == null ? List.of() : List.copyOf(tools);
    }
}
