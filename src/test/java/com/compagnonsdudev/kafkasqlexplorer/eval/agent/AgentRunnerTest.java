// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors
package com.compagnonsdudev.kafkasqlexplorer.eval.agent;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The bounded loop, driven by a scripted model against a stub server.
 *
 * <p>This is what {@link AgentModel} exists as an interface for. The loop's rules — the ceiling is
 * an assertion rather than a cut-off, a refusal goes back to the model as content, the trace is
 * captured by the client and not by the model — are all decidable without spending a token, and a
 * harness whose own loop is only exercised by runs that cost money is a harness nobody re-runs.
 */
class AgentRunnerTest {

    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    /** A model that plays a fixed script, and records what it was shown. */
    private static final class ScriptedModel implements AgentModel {
        private final Deque<Turn> script = new ArrayDeque<>();
        private List<Exchange> lastTranscript = List.of();
        private List<McpHttpClient.ToolSpec> lastTools = List.of();
        private String lastSystemPrompt = "";

        ScriptedModel(Turn... turns) {
            script.addAll(List.of(turns));
        }

        @Override
        public Turn respond(String systemPrompt, List<Exchange> transcript,
                            List<McpHttpClient.ToolSpec> tools) {
            lastSystemPrompt = systemPrompt;
            lastTranscript = transcript;
            lastTools = tools;
            // An exhausted script answers, so a loop that ran away is a failed assertion on the
            // trace rather than a test that hangs.
            return script.isEmpty() ? new Turn("done", List.of()) : script.removeFirst();
        }

        @Override
        public String describe() {
            return "scripted";
        }
    }

    private URI serve(String toolResult) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/mcp", (HttpExchange exchange) -> {
            String request = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            String body;
            if (request.contains("\"initialize\"")) {
                body = """
                        {"jsonrpc":"2.0","id":1,"result":{"serverInfo":{"name":"stub"}}}""";
            } else if (request.contains("tools/list")) {
                body = """
                        {"jsonrpc":"2.0","id":2,"result":{"tools":[
                          {"name":"kex_list_topics","description":"d","inputSchema":{}},
                          {"name":"kex_trace_key","description":"d","inputSchema":{}}]}}""";
            } else if (request.contains("tools/call")) {
                body = toolResult;
            } else {
                exchange.sendResponseHeaders(202, -1);
                exchange.close();
                return;
            }
            byte[] payload = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, payload.length);
            exchange.getResponseBody().write(payload);
            exchange.close();
        });
        server.start();
        return URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/mcp");
    }

    private static final String OK_RESULT = """
            {"jsonrpc":"2.0","id":3,"result":{"isError":false,"content":[{"type":"text",
             "text":"{\\"coverage\\":{\\"stopReason\\":\\"EXHAUSTED\\"}}"}]}}""";

    private static AgentScenario scenario(int maxToolCalls, long budgetMs) {
        return new AgentScenario("t", "t", "t", Map.of(),
                new AgentScenario.Fixture("setup-demo.sh", List.of("demo.orders.1.received"), List.of()),
                "Did ORD-101 get delivered?", maxToolCalls, budgetMs,
                new AgentScenario.Trace(List.of(), List.of(), null, 0),
                new AgentScenario.Verdict(List.of(), List.of(), List.of(), List.of()),
                null);
    }

    private static AgentModel.Turn calling(String tool, Map<String, Object> arguments) {
        return new AgentModel.Turn("", List.of(
                new AgentModel.RequestedCall("c" + tool.hashCode(), tool, arguments)));
    }

    @Test
    @DisplayName("a call, an answer, and a trace the client captured")
    void runsOneToolAndAnswers() throws IOException {
        ScriptedModel model = new ScriptedModel(
                calling("kex_trace_key", Map.of("key", "ORD-101")),
                new AgentModel.Turn("ORD-101 reached delivery.", List.of()));

        try (McpHttpClient mcp = new McpHttpClient(serve(OK_RESULT), Duration.ofSeconds(5))) {
            mcp.initialize();
            AgentRunner.Session session = new AgentRunner(model, mcp).run(scenario(6, 60_000));

            assertThat(session.answer()).isEqualTo("ORD-101 reached delivery.");
            assertThat(session.overrun()).isNull();
            assertThat(session.trace().calls()).singleElement()
                    .satisfies(call -> assertThat(call.name()).isEqualTo("kex_trace_key"));
            assertThat(session.trace().listedTools())
                    .containsExactlyInAnyOrder("kex_list_topics", "kex_trace_key");
        }
    }

    @Test
    @DisplayName("the ceiling stops the loop and is reported, not silently absorbed")
    void stopsAtTheCeiling() throws IOException {
        // §5.2: the bound is an assertion. A run that quietly truncated and then answered would
        // report a passing trace for an agent that never finished.
        ScriptedModel model = new ScriptedModel(
                calling("kex_list_topics", Map.of()),
                calling("kex_list_topics", Map.of()),
                calling("kex_list_topics", Map.of()));

        try (McpHttpClient mcp = new McpHttpClient(serve(OK_RESULT), Duration.ofSeconds(5))) {
            mcp.initialize();
            AgentRunner.Session session = new AgentRunner(model, mcp).run(scenario(2, 60_000));

            assertThat(session.trace().calls()).hasSize(2);
            assertThat(session.overrun()).contains("more than the 2 tool calls");
        }
    }

    @Test
    @DisplayName("an exhausted budget stops the loop and says which bound was hit")
    void stopsAtTheBudget() throws IOException {
        ScriptedModel model = new ScriptedModel(
                calling("kex_list_topics", Map.of()),
                calling("kex_list_topics", Map.of()));

        try (McpHttpClient mcp = new McpHttpClient(serve(OK_RESULT), Duration.ofSeconds(5))) {
            mcp.initialize();
            // A budget already spent by the time the second call is considered.
            AgentRunner.Session session = new AgentRunner(model, mcp).run(scenario(6, 1));

            assertThat(session.overrun()).contains("ms budget");
        }
    }

    @Test
    @DisplayName("a refusal is handed back to the model rather than absorbed")
    void givesTheRefusalToTheModel() throws IOException {
        // The guard scenarios are entirely about what the agent does after being told no; a runner
        // that swallowed the refusal would be answering that question itself.
        String refusal = """
                {"jsonrpc":"2.0","id":3,"error":{"code":-32041,
                 "message":"demo.payments.dlq is outside the configured scope"}}""";
        ScriptedModel model = new ScriptedModel(
                calling("kex_list_topics", Map.of("prefix", "demo.payments.")),
                new AgentModel.Turn("The read was refused as out of scope.", List.of()));

        try (McpHttpClient mcp = new McpHttpClient(serve(refusal), Duration.ofSeconds(5))) {
            mcp.initialize();
            new AgentRunner(model, mcp).run(scenario(6, 60_000));

            assertThat(model.lastTranscript)
                    .filteredOn(AgentModel.Exchange.ToolResult.class::isInstance)
                    .singleElement()
                    .satisfies(entry -> {
                        AgentModel.Exchange.ToolResult result = (AgentModel.Exchange.ToolResult) entry;
                        assertThat(result.refusalCode()).isEqualTo(McpRefusal.OUT_OF_SCOPE);
                        assertThat(result.text()).contains("outside the configured scope");
                    });
        }
    }

    @Test
    @DisplayName("the system prompt teaches nothing the tool descriptions are meant to teach")
    void thePromptDoesNotDoTheServersJob() throws IOException {
        // The experiment only means something if the harness stays out of it: the tool descriptions
        // carry the reading rule ahead of the payload, at some cost, and a prompt that repeated the
        // rule would measure the prompt instead of the server.
        ScriptedModel model = new ScriptedModel(new AgentModel.Turn("done", List.of()));

        try (McpHttpClient mcp = new McpHttpClient(serve(OK_RESULT), Duration.ofSeconds(5))) {
            mcp.initialize();
            new AgentRunner(model, mcp).run(scenario(6, 60_000));

            assertThat(model.lastSystemPrompt.toLowerCase(java.util.Locale.ROOT))
                    .doesNotContain("coverage")
                    .doesNotContain("stopreason")
                    .doesNotContain("measured")
                    .doesNotContain("exhausted");
            assertThat(model.lastTools).hasSize(2);
        }
    }
}
