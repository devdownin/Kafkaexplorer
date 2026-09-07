// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors
package com.compagnonsdudev.kafkasqlexplorer.mcp.console;

/**
 * Generates the copyable client snippets, from the endpoint actually bound.
 *
 * <p><b>No credential is ever placed in a snippet.</b> A generated configuration is pasted into a
 * dotfile, a chat, a ticket — putting a token in it would make this console the thing that leaked
 * one, on the screen whose entire subject is controlling what an agent may see.
 * {@link McpClientConfig#tokenHint()} says where a credential goes instead, and
 * {@code McpConsoleControllerTest} asserts the snippet never contains one.
 *
 * <p>The host is {@code localhost} because this process genuinely does not know the name an agent
 * will reach it by — a reverse proxy, a container host, a service DNS record. Inventing one would
 * put a wrong URL into something meant to be pasted unread; the caption says to substitute it.
 */
public final class McpClientConfigFactory {

    private McpClientConfigFactory() {
    }

    /** The endpoint shown when nothing is bound yet, so the snippet stays readable. */
    static final String UNBOUND = "http://localhost:8080/mcp";

    public static McpClientConfig forClient(String client, String endpoint) {
        String url = endpoint == null ? UNBOUND : endpoint;
        return switch (client == null ? "" : client.toLowerCase(java.util.Locale.ROOT)) {
            case "claude-code" -> new McpClientConfig("claude-code", "shell", """
                    claude mcp add --transport http kafka-explorer %s""".formatted(url), null);
            case "claude-desktop" -> new McpClientConfig("claude-desktop",
                    "claude_desktop_config.json", """
                    {
                      "mcpServers": {
                        "kafka-explorer": {
                          "type": "http",
                          "url": "%s"
                        }
                      }
                    }""".formatted(url), null);
            default -> new McpClientConfig("generic", "JSON", """
                    {
                      "name": "kafka-explorer",
                      "transport": "http",
                      "url": "%s"
                    }""".formatted(url), null);
        };
    }
}
