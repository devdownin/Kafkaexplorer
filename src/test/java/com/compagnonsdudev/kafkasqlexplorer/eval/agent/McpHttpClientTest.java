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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * What the harness's MCP client reads off the wire, against a stub that answers like the server.
 *
 * <p>A stub rather than the real endpoint, and the line is where it always is in this tree: what is
 * under test here is how a client parses an answer, so a MiniCluster behind a broker behind a Spring
 * context would cost minutes and prove nothing extra. {@code McpServerBootTest} is where a claim
 * about what Spring AI actually registers belongs, and the agent eval itself is where the two meet.
 *
 * <p>The shapes below are the ones that cost something when read wrong: an SSE frame versus plain
 * JSON (the transport chooses, and a client that reads one works until the day it is reconfigured);
 * a tool answer, which is a JSON document inside a JSON string; and MCP's <b>two</b> error channels,
 * where reading only the JSON-RPC one makes an execution refusal look like a successful call.
 */
class McpHttpClientTest {

    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    /** Starts a stub that answers each JSON-RPC method with whatever {@code answers} returns. */
    private URI serve(Function<String, String> answers, boolean asSse) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/mcp", (HttpExchange exchange) -> {
            String request = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            String method = request.replaceAll("(?s).*\"method\"\\s*:\\s*\"([^\"]+)\".*", "$1");
            String body = answers.apply(method);
            if (body == null) {                       // a notification: 202, no body
                exchange.sendResponseHeaders(202, -1);
                exchange.close();
                return;
            }
            // An SSE frame carries its payload on ONE `data:` line, so the stub collapses the
            // pretty-printed JSON below exactly as a real server's serializer would. Framing it
            // across lines would make the stub, not the client, the thing under test.
            String oneLine = body.replaceAll("\\s*\\n\\s*", " ");
            byte[] payload = (asSse ? "event: message\ndata: " + oneLine + "\n\n" : oneLine)
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type",
                    asSse ? "text/event-stream" : "application/json");
            if ("initialize".equals(method)) {
                exchange.getResponseHeaders().add("Mcp-Session-Id", "stub-session");
            }
            exchange.sendResponseHeaders(200, payload.length);
            exchange.getResponseBody().write(payload);
            exchange.close();
        });
        server.start();
        return URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/mcp");
    }

    private static String handshake(String method) {
        return switch (method) {
            case "initialize" -> """
                    {"jsonrpc":"2.0","id":1,"result":{"protocolVersion":"2025-06-18",
                     "serverInfo":{"name":"kafka-explorer-mcp","version":"0.1.0"}}}""";
            case "notifications/initialized" -> null;
            case "tools/list" -> """
                    {"jsonrpc":"2.0","id":2,"result":{"tools":[
                      {"name":"kex_list_topics","description":"List topics","inputSchema":{"type":"object"}},
                      {"name":"kex_trace_key","description":"Trace a key","inputSchema":{"type":"object"}}]}}""";
            default -> null;
        };
    }

    private static String withCall(String method, String callResult) {
        return "tools/call".equals(method) ? callResult : handshake(method);
    }

    @Test
    @DisplayName("the handshake works over an SSE frame")
    void handshakesOverSse() throws IOException {
        try (McpHttpClient client = new McpHttpClient(serve(McpHttpClientTest::handshake, true),
                Duration.ofSeconds(5))) {
            assertThat(client.initialize()).isEqualTo("kafka-explorer-mcp");
            assertThat(client.listTools()).extracting(McpHttpClient.ToolSpec::name)
                    .containsExactly("kex_list_topics", "kex_trace_key");
        }
    }

    @Test
    @DisplayName("the handshake works over plain JSON")
    void handshakesOverPlainJson() throws IOException {
        // The transport chooses, so a client that reads one shape works until it is reconfigured.
        try (McpHttpClient client = new McpHttpClient(serve(McpHttpClientTest::handshake, false),
                Duration.ofSeconds(5))) {
            assertThat(client.initialize()).isEqualTo("kafka-explorer-mcp");
            assertThat(client.listTools()).hasSize(2);
        }
    }

    @Test
    @DisplayName("a coverage envelope inside the tool's text is read, not searched for")
    void readsTheSignalsOutOfTheToolPayload() throws IOException {
        String answer = """
                {"jsonrpc":"2.0","id":3,"result":{"isError":false,"content":[{"type":"text",
                 "text":"{\\"topics\\":[],\\"coverage\\":{\\"stopReason\\":\\"TIME_BUDGET\\",
                 \\"resumeToken\\":\\"tok-7\\"}}"}]}}""";
        try (McpHttpClient client = new McpHttpClient(
                serve(m -> withCall(m, answer), true), Duration.ofSeconds(5))) {
            client.initialize();
            McpHttpClient.ToolAnswer result =
                    client.callTool("kex_list_topics", Map.of("prefix", "demo."));

            assertThat(result.refusalCode()).isNull();
            assertThat(result.resumeToken()).isEqualTo("tok-7");
            assertThat(client.trace()).singleElement().satisfies(call -> {
                assertThat(call.ordinal()).isEqualTo(1);
                assertThat(call.name()).isEqualTo("kex_list_topics");
                assertThat(call.arguments()).containsEntry("prefix", "demo.");
                assertThat(call.resumeToken()).isEqualTo("tok-7");
                assertThat(call.finishedAtMs()).isGreaterThanOrEqualTo(call.startedAtMs());
            });
        }
    }

    @Test
    @DisplayName("a JSON-RPC refusal is returned with its code, not thrown")
    void readsAProtocolRefusal() throws IOException {
        // Returned rather than thrown on purpose: the agent is expected to read a refusal and
        // decide, and a client that threw would be deciding for it.
        String refusal = """
                {"jsonrpc":"2.0","id":3,"error":{"code":-32029,"message":"rate limit exceeded",
                 "data":{"retryAfterMs":4200}}}""";
        try (McpHttpClient client = new McpHttpClient(
                serve(m -> withCall(m, refusal), true), Duration.ofSeconds(5))) {
            client.initialize();
            McpHttpClient.ToolAnswer result = client.callTool("kex_list_topics", Map.of());

            assertThat(result.refusalCode()).isEqualTo(McpRefusal.RATE_LIMITED);
            assertThat(result.retryAfterMs()).isEqualTo(4200L);
            assertThat(client.trace()).singleElement()
                    .satisfies(call -> assertThat(call.refused()).isTrue());
        }
    }

    @Test
    @DisplayName("an execution refusal inside a successful response is a refusal too")
    void readsAnExecutionRefusal() throws IOException {
        // MCP's second error channel. A client that only read the JSON-RPC one would record this
        // as a call that ran, and every trace assertion about what followed would be about the
        // wrong thing.
        String refusal = """
                {"jsonrpc":"2.0","id":3,"result":{"isError":true,"content":[{"type":"text",
                 "text":"{\\"code\\":-32041,\\"message\\":\\"demo.payments.dlq is outside the scope\\"}"}]}}""";
        try (McpHttpClient client = new McpHttpClient(
                serve(m -> withCall(m, refusal), true), Duration.ofSeconds(5))) {
            client.initialize();

            assertThat(client.callTool("kex_list_topics", Map.of()).refusalCode())
                    .isEqualTo(McpRefusal.OUT_OF_SCOPE);
        }
    }

    @Test
    @DisplayName("a tool that answered prose is an answer, not a client failure")
    void tolerantOfANonJsonPayload() throws IOException {
        String prose = """
                {"jsonrpc":"2.0","id":3,"result":{"isError":false,
                 "content":[{"type":"text","text":"nothing to report"}]}}""";
        try (McpHttpClient client = new McpHttpClient(
                serve(m -> withCall(m, prose), true), Duration.ofSeconds(5))) {
            client.initialize();
            McpHttpClient.ToolAnswer result = client.callTool("kex_list_topics", Map.of());

            assertThat(result.text()).isEqualTo("nothing to report");
            assertThat(result.resumeToken()).isNull();
        }
    }

    @Test
    @DisplayName("a refused handshake says so rather than continuing against nothing")
    void refusesToProceedOnABrokenHandshake() throws IOException {
        String refusal = """
                {"jsonrpc":"2.0","id":1,"error":{"code":-32601,"message":"Method not found"}}""";
        try (McpHttpClient client = new McpHttpClient(
                serve(m -> refusal, true), Duration.ofSeconds(5))) {
            assertThatThrownBy(client::initialize)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("initialize was refused");
        }
    }

    @Test
    @DisplayName("the trace numbers calls in the order they were made")
    void numbersTheTrace() throws IOException {
        String ok = """
                {"jsonrpc":"2.0","id":3,"result":{"isError":false,
                 "content":[{"type":"text","text":"{}"}]}}""";
        List<String> made = new ArrayList<>();
        try (McpHttpClient client = new McpHttpClient(
                serve(m -> withCall(m, ok), true), Duration.ofSeconds(5))) {
            client.initialize();
            client.callTool("kex_list_topics", Map.of());
            client.callTool("kex_trace_key", Map.of("key", "ORD-101"));
            client.trace().forEach(call -> made.add(call.ordinal() + ":" + call.name()));
        }
        assertThat(made).containsExactly("1:kex_list_topics", "2:kex_trace_key");
    }
}
