// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors
package com.compagnonsdudev.kafkasqlexplorer.mcp.console;

import java.util.List;

/**
 * The six facts the console's status banner shows, read from the runtime rather than from the YAML.
 *
 * <p>The distinction matters most for {@code endpoint}: the property says what was asked for, the
 * bound address says what an agent can actually reach, and an operator copying a client
 * configuration needs the second. They differ whenever the port was chosen at random, mapped by a
 * container, or the app was started with an override.
 *
 * @param enabled              whether the MCP server is on at all
 * @param transports           what is actually listening, e.g. {@code HTTP (streamable)}
 * @param endpoint             the effective endpoint, null when the server is off or unbound
 * @param readonly             whether the write surface is closed
 * @param writeSurfaceOpen     whether any mutating tool is actually registered — the amber badge
 * @param mutatingToolsExposed which ones, so the badge can name them on hover
 * @param authentication       how a caller is identified, in the operator's words
 * @param topicScope           the configured topic prefixes, or a single {@code *}
 * @param groupScope           the configured consumer-group prefixes
 */
public record McpStatusView(
        boolean enabled,
        List<String> transports,
        String endpoint,
        boolean readonly,
        boolean writeSurfaceOpen,
        List<String> mutatingToolsExposed,
        String authentication,
        List<String> topicScope,
        List<String> groupScope
) {

    public McpStatusView {
        transports = transports == null ? List.of() : List.copyOf(transports);
        mutatingToolsExposed = mutatingToolsExposed == null ? List.of() : List.copyOf(mutatingToolsExposed);
        topicScope = topicScope == null ? List.of() : List.copyOf(topicScope);
        groupScope = groupScope == null ? List.of() : List.copyOf(groupScope);
    }

    /** The banner shown when {@code explorer.mcp.enabled} is false: off, and saying only that. */
    public static McpStatusView disabled() {
        return new McpStatusView(false, List.of(), null, true, false, List.of(),
                "not applicable — the server is disabled", List.of(), List.of());
    }
}
