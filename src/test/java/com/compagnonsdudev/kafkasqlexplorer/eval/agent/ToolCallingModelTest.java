// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors
package com.compagnonsdudev.kafkasqlexplorer.eval.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Both tool-calling clients, against a stub that answers like the provider.
 *
 * <p>What each case asserts is the <b>request</b> as much as the answer, because the request is
 * where this harness could quietly stop measuring what it claims to. Two things in particular: the
 * tool schemas have to be the server's own, passed through untouched — a schema rewritten here
 * would mean the model chose against a description the harness wrote, which is exactly the variable
 * a run holds fixed — and a refusal has to reach the model carrying <i>which</i> refusal it was, or
 * every guard scenario is asking a question the transcript never posed.
 */
class ToolCallingModelTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private HttpServer server;
    private final AtomicReference<JsonNode> lastRequest = new AtomicReference<>();

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    private URI serve(String path, int status, String body) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(path, (HttpExchange exchange) -> {
            lastRequest.set(JSON.readTree(exchange.getRequestBody()));
            byte[] payload = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, payload.length);
            exchange.getResponseBody().write(payload);
            exchange.close();
        });
        server.start();
        return URI.create("http://127.0.0.1:" + server.getAddress().getPort() + path);
    }

    private static List<McpHttpClient.ToolSpec> tools() throws IOException {
        return List.of(new McpHttpClient.ToolSpec("kex_list_topics", "List the topics",
                JSON.readTree("{\"type\":\"object\",\"properties\":{\"prefix\":{\"type\":\"string\"}}}")));
    }

    /** A conversation that has already made a call and been refused — the guard scenarios' shape. */
    private static List<AgentModel.Exchange> refusedTranscript() {
        return List.of(
                new AgentModel.Exchange.User("How many messages in demo.payments.dlq?"),
                new AgentModel.Exchange.Assistant("", List.of(
                        new AgentModel.RequestedCall("call_1", "kex_list_topics",
                                Map.of("prefix", "demo.payments.")))),
                new AgentModel.Exchange.ToolResult("call_1", "kex_list_topics",
                        "demo.payments.dlq is outside the configured scope",
                        McpRefusal.OUT_OF_SCOPE));
    }

    @Nested
    class OpenAiCompatible {

        @Test
        @DisplayName("a tool call is read back with its id and its arguments")
        void readsAToolCall() throws IOException {
            URI endpoint = serve("/chat/completions", 200, """
                    {"choices":[{"message":{"content":"","tool_calls":[
                      {"id":"call_1","type":"function","function":{
                        "name":"kex_list_topics","arguments":"{\\"prefix\\":\\"demo.\\",\\"limit\\":5}"}}]}}]}""");

            AgentModel.Turn turn = new OpenAiToolCallingModel(endpoint, "sk-test", "a/b",
                    Duration.ofSeconds(5))
                    .respond("system", List.of(new AgentModel.Exchange.User("go")), tools());

            assertThat(turn.isFinal()).isFalse();
            assertThat(turn.calls()).singleElement().satisfies(call -> {
                assertThat(call.id()).isEqualTo("call_1");
                assertThat(call.name()).isEqualTo("kex_list_topics");
                assertThat(call.arguments()).containsEntry("prefix", "demo.").containsEntry("limit", 5);
            });
        }

        @Test
        @DisplayName("a turn with no call is the model's own signal that it has answered")
        void readsAFinalTurn() throws IOException {
            URI endpoint = serve("/chat/completions", 200,
                    """
                    {"choices":[{"message":{"content":"ORD-101 reached delivery."}}]}""");

            AgentModel.Turn turn = new OpenAiToolCallingModel(endpoint, "k", "m", Duration.ofSeconds(5))
                    .respond("system", List.of(new AgentModel.Exchange.User("go")), tools());

            assertThat(turn.isFinal()).isTrue();
            assertThat(turn.text()).isEqualTo("ORD-101 reached delivery.");
        }

        @Test
        @DisplayName("the tool schema sent is the server's own, not one the harness wrote")
        void passesTheServersSchemaThrough() throws IOException {
            URI endpoint = serve("/chat/completions", 200,
                    """
                    {"choices":[{"message":{"content":"done"}}]}""");

            new OpenAiToolCallingModel(endpoint, "k", "m", Duration.ofSeconds(5))
                    .respond("system", List.of(new AgentModel.Exchange.User("go")), tools());

            JsonNode function = lastRequest.get().path("tools").path(0).path("function");
            assertThat(function.path("name").asText()).isEqualTo("kex_list_topics");
            assertThat(function.path("description").asText()).isEqualTo("List the topics");
            assertThat(function.path("parameters").path("properties").has("prefix")).isTrue();
            assertThat(lastRequest.get().path("temperature").asInt()).isZero();
        }

        @Test
        @DisplayName("a refusal reaches the model with its code, since the API has no field for one")
        void carriesTheRefusalCodeIntoTheTranscript() throws IOException {
            URI endpoint = serve("/chat/completions", 200,
                    """
                    {"choices":[{"message":{"content":"The read was refused."}}]}""");

            new OpenAiToolCallingModel(endpoint, "k", "m", Duration.ofSeconds(5))
                    .respond("system", refusedTranscript(), tools());

            JsonNode messages = lastRequest.get().path("messages");
            JsonNode toolMessage = messages.get(messages.size() - 1);
            assertThat(toolMessage.path("role").asText()).isEqualTo("tool");
            assertThat(toolMessage.path("tool_call_id").asText()).isEqualTo("call_1");
            assertThat(toolMessage.path("content").asText())
                    .contains("-32041")
                    .contains("outside the configured scope");
        }

        @Test
        @DisplayName("a provider error is raised, never folded into an empty turn")
        void raisesAProviderError() throws IOException {
            // Folded in, it would score as the agent failing the scenario — blaming the wrong party,
            // which is the one report this harness must never produce.
            URI endpoint = serve("/chat/completions", 429,
                    """
                    {"error":{"message":"rate limited"}}""");

            assertThatThrownBy(() -> new OpenAiToolCallingModel(endpoint, "k", "m", Duration.ofSeconds(5))
                    .respond("s", List.of(new AgentModel.Exchange.User("go")), tools()))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("429");
        }
    }

    @Nested
    class Anthropic {

        @Test
        @DisplayName("text and tool_use blocks are both read out of one answer")
        void readsBothBlockKinds() throws IOException {
            URI endpoint = serve("/messages", 200, """
                    {"content":[
                      {"type":"text","text":"Let me look."},
                      {"type":"tool_use","id":"toolu_1","name":"kex_list_topics",
                       "input":{"prefix":"demo.","limit":5}}]}""");

            AgentModel.Turn turn = new AnthropicToolCallingModel(endpoint, "sk-ant", "claude-x",
                    4096, Duration.ofSeconds(5))
                    .respond("system", List.of(new AgentModel.Exchange.User("go")), tools());

            assertThat(turn.text()).isEqualTo("Let me look.");
            assertThat(turn.calls()).singleElement().satisfies(call -> {
                assertThat(call.id()).isEqualTo("toolu_1");
                assertThat(call.arguments()).containsEntry("limit", 5);
            });
        }

        @Test
        @DisplayName("the schema goes in as input_schema, still the server's own")
        void passesTheServersSchemaThrough() throws IOException {
            URI endpoint = serve("/messages", 200,
                    """
                    {"content":[{"type":"text","text":"done"}]}""");

            new AnthropicToolCallingModel(endpoint, "k", "m", 4096, Duration.ofSeconds(5))
                    .respond("system", List.of(new AgentModel.Exchange.User("go")), tools());

            JsonNode tool = lastRequest.get().path("tools").path(0);
            assertThat(tool.path("name").asText()).isEqualTo("kex_list_topics");
            assertThat(tool.path("input_schema").path("properties").has("prefix")).isTrue();
            assertThat(lastRequest.get().path("system").asText()).isEqualTo("system");
        }

        @Test
        @DisplayName("a refusal is a tool_result with is_error AND the code in its text")
        void carriesBothHalvesOfARefusal() throws IOException {
            // is_error says something went wrong; it does not say *which guard* said so, and the
            // guard scenarios turn on the model telling a policy refusal from a broken tool.
            URI endpoint = serve("/messages", 200,
                    """
                    {"content":[{"type":"text","text":"Refused."}]}""");

            new AnthropicToolCallingModel(endpoint, "k", "m", 4096, Duration.ofSeconds(5))
                    .respond("system", refusedTranscript(), tools());

            JsonNode messages = lastRequest.get().path("messages");
            JsonNode block = messages.get(messages.size() - 1).path("content").path(0);
            assertThat(block.path("type").asText()).isEqualTo("tool_result");
            assertThat(block.path("tool_use_id").asText()).isEqualTo("call_1");
            assertThat(block.path("is_error").asBoolean()).isTrue();
            assertThat(block.path("content").asText()).contains("-32041");
        }

        @Test
        @DisplayName("an assistant turn replays its tool_use blocks, so the ids still line up")
        void replaysTheAssistantsCalls() throws IOException {
            URI endpoint = serve("/messages", 200,
                    """
                    {"content":[{"type":"text","text":"ok"}]}""");

            new AnthropicToolCallingModel(endpoint, "k", "m", 4096, Duration.ofSeconds(5))
                    .respond("system", refusedTranscript(), tools());

            JsonNode assistant = lastRequest.get().path("messages").get(1);
            assertThat(assistant.path("role").asText()).isEqualTo("assistant");
            assertThat(assistant.path("content").path(0).path("type").asText()).isEqualTo("tool_use");
            assertThat(assistant.path("content").path(0).path("id").asText()).isEqualTo("call_1");
        }

        @Test
        @DisplayName("a block kind this harness does not know is skipped, not guessed at")
        void ignoresAnUnknownBlockKind() throws IOException {
            URI endpoint = serve("/messages", 200, """
                    {"content":[
                      {"type":"thinking","thinking":"..."},
                      {"type":"text","text":"answer"}]}""");

            AgentModel.Turn turn = new AnthropicToolCallingModel(endpoint, "k", "m", 4096,
                    Duration.ofSeconds(5))
                    .respond("s", List.of(new AgentModel.Exchange.User("go")), tools());

            assertThat(turn.text()).isEqualTo("answer");
            assertThat(turn.isFinal()).isTrue();
        }

        @Test
        @DisplayName("a provider error is raised, never folded into an empty turn")
        void raisesAProviderError() throws IOException {
            URI endpoint = serve("/messages", 400,
                    """
                    {"type":"error","error":{"type":"invalid_request_error","message":"bad model"}}""");

            assertThatThrownBy(() -> new AnthropicToolCallingModel(endpoint, "k", "m", 4096,
                    Duration.ofSeconds(5))
                    .respond("s", List.of(new AgentModel.Exchange.User("go")), tools()))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("400");
        }
    }
}
