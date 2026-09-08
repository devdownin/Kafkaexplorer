// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors
package com.compagnonsdudev.kafkasqlexplorer.mcp.console;

/**
 * Generates the copyable client snippets, from the endpoint actually bound.
 *
 * <p><b>No credential is ever placed in a snippet.</b> A generated configuration is pasted into a
 * dotfile, a chat, a ticket — putting a token in it would make this console the thing that leaked
 * one, on the screen whose entire subject is controlling what an agent may see.
 * {@code McpConsoleControllerTest} asserts the snippet never contains one.
 *
 * <p>{@link McpClientConfig#tokenHint()} says what a caller needs instead — and today that is
 * <em>nothing</em>, which is the more useful thing to print. The field shipped always null while
 * its own javadoc claimed it named where a credential goes: a field asserting information it never
 * carried, which is the defect this module keeps removing. It now states the deployment's actual
 * posture, on the one screen where somebody is about to wire an agent to this endpoint and would
 * otherwise assume a token was involved.
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

    /**
     * What a client needs beyond the URL. Phase 5 replaces this with the issuer and audience once
     * OAuth 2.1 exists; until then the truthful answer is that the endpoint gates nothing, and an
     * operator about to paste this into an agent is exactly who needs to know.
     */
    static final String NO_AUTH_HINT =
            "This deployment configures no authentication: anything that can reach the endpoint can "
                    + "call these tools. No token goes in the snippet because none is checked. "
                    + "Restrict the network, or keep explorer.mcp.enabled=false.";

    public static McpClientConfig forClient(String client, String endpoint) {
        String url = endpoint == null ? UNBOUND : endpoint;
        return switch (client == null ? "" : client.toLowerCase(java.util.Locale.ROOT)) {
            case "claude-code" -> new McpClientConfig("claude-code", "shell", """
                    claude mcp add --transport http kafka-explorer %s""".formatted(url), NO_AUTH_HINT);
            case "claude-desktop" -> new McpClientConfig("claude-desktop",
                    "claude_desktop_config.json", """
                    {
                      "mcpServers": {
                        "kafka-explorer": {
                          "type": "http",
                          "url": "%s"
                        }
                      }
                    }""".formatted(url), NO_AUTH_HINT);
            default -> new McpClientConfig("generic", "JSON", """
                    {
                      "name": "kafka-explorer",
                      "transport": "http",
                      "url": "%s"
                    }""".formatted(url), NO_AUTH_HINT);
        };
    }
}
