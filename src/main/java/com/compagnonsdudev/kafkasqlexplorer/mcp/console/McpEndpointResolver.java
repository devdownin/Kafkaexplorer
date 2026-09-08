// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors
package com.compagnonsdudev.kafkasqlexplorer.mcp.console;

import org.springframework.boot.web.server.context.WebServerInitializedEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.core.env.Environment;

/**
 * The address an agent can actually reach, as against the one the YAML asked for.
 *
 * <p>The two differ more often than they look: {@code server.port=0} picks a port at boot, a
 * container maps it, a deployment overrides it on the command line. The console's banner and the
 * client-configuration snippet both feed an operator who is about to paste an address into a tool,
 * so printing the property would hand them something that does not answer — and they would find
 * out from a client that fails to connect, with the console still confidently showing the wrong
 * value.
 *
 * <p>The port is captured from {@link WebServerInitializedEvent} — which lives in
 * {@code org.springframework.boot.web.server.context} as of Spring Boot 4, having moved from
 * {@code ...boot.web.context} — and which fires once the server is listening, carrying the real
 * port. Before it fires there is nothing to report, and this says so
 * with a null rather than falling back to the configured value — which would be the same wrong
 * answer, arrived at more slowly.
 */
public class McpEndpointResolver implements ApplicationListener<WebServerInitializedEvent> {

    private final Environment environment;

    private volatile Integer boundPort;

    public McpEndpointResolver(Environment environment) {
        this.environment = environment;
    }

    @Override
    public void onApplicationEvent(WebServerInitializedEvent event) {
        this.boundPort = event.getWebServer().getPort();
    }

    /**
     * The MCP endpoint, or null when nothing is bound yet.
     *
     * <p>The host is deliberately {@code localhost}: this process cannot know the name an agent
     * will reach it by — a reverse proxy, a container host, a service DNS entry — and inventing one
     * would put a wrong URL in a snippet meant to be pasted. The console captions it as the local
     * address so an operator substitutes their own rather than trusting it.
     */
    public String endpoint() {
        Integer port = boundPort;
        if (port == null) {
            return null;
        }
        String path = environment.getProperty("spring.ai.mcp.server.streamable-http.mcp-endpoint", "/mcp");
        return "http://localhost:" + port + path;
    }

    public Integer boundPort() {
        return boundPort;
    }
}
