// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors
package com.compagnonsdudev.kafkasqlexplorer.eval.agent;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * The operator's own gestures, played from outside the agent's session.
 *
 * <p>An interface because the loop that uses it must be testable without a server — and because
 * what the harness needs of the console is exactly one verb today. The real implementation posts to
 * {@code /api/mcp/toggle/tool/{name}}, the same endpoint the console's own switch calls: a harness
 * that reached past the HTTP surface to flip a bean would be testing a gesture no operator can
 * make.
 */
interface OperatorConsole {

    /** Switches a tool off, as an operator would, mid-session. */
    void disableTool(String tool, String actor, String reason);

    /** Puts it back, so a scenario cannot leave the deployment altered for its neighbour. */
    void enableTool(String tool);

    /**
     * The real one, over HTTP.
     *
     * <p>A failure here is raised rather than swallowed: a scenario whose mid-session gesture did
     * not land is a scenario measuring an unchanged world, and reporting on the agent afterwards
     * would be reporting on the wrong run.
     */
    final class Http implements OperatorConsole {

        private final HttpClient http;
        private final URI base;

        Http(URI base, Duration timeout) {
            this.base = base;
            this.http = HttpClient.newBuilder().connectTimeout(timeout).build();
        }

        @Override
        public void disableTool(String tool, String actor, String reason) {
            post(tool, "{\"enable\":false,\"actor\":\"" + escape(actor)
                    + "\",\"reason\":\"" + escape(reason) + "\"}");
        }

        @Override
        public void enableTool(String tool) {
            post(tool, "{\"enable\":true}");
        }

        private void post(String tool, String body) {
            HttpRequest request = HttpRequest.newBuilder(
                            base.resolve("/api/mcp/toggle/tool/" + tool))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();
            try {
                HttpResponse<String> response =
                        http.send(request, HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() >= 400) {
                    throw new IllegalStateException("The console refused the switch on " + tool
                            + ": HTTP " + response.statusCode() + " " + response.body()
                            + ". Is explorer.mcp.console.allow-runtime-toggle on?");
                }
            } catch (IOException e) {
                throw new UncheckedIOException("Cannot reach the MCP console at " + base, e);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted switching " + tool, e);
            }
        }

        /** Enough for an actor and a reason the harness itself writes; not a JSON library. */
        private static String escape(String text) {
            return text == null ? "" : text.replace("\\", "\\\\").replace("\"", "\\\"");
        }
    }
}
