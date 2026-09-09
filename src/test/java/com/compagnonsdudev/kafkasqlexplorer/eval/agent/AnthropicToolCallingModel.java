// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors
package com.compagnonsdudev.kafkasqlexplorer.eval.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Anthropic's Messages API with tool use.
 *
 * <p>Over the wire rather than through {@code anthropic-java}, which this project does depend on,
 * and the exception is the same one {@code LlmAnalysisEvalTest} already makes for reading its
 * configuration from the environment: this is a deliberate measurement of a named endpoint, not the
 * path the application takes. Two concrete reasons on top of that. The transcript shape is the
 * thing under test in half the scenarios — a tool result carrying a refusal, fed back in — and an
 * SDK that renders it for us puts a layer between the harness and what the model actually saw. And
 * {@link OpenAiToolCallingModel} beside it has no SDK to use, so one of the two would be built this
 * way regardless; two implementations written the same way are two that can be compared.
 *
 * <p>The tool schemas are the server's own, passed through untouched, for the reason given there.
 */
final class AnthropicToolCallingModel implements AgentModel {

    private static final String API_VERSION = "2023-06-01";

    private final ObjectMapper json = new ObjectMapper();
    private final HttpClient http;
    private final URI endpoint;
    private final String apiKey;
    private final String model;
    private final int maxTokens;

    AnthropicToolCallingModel(URI endpoint, String apiKey, String model, int maxTokens,
                              Duration timeout) {
        this.endpoint = endpoint;
        this.apiKey = apiKey;
        this.model = model;
        this.maxTokens = maxTokens;
        this.http = HttpClient.newBuilder().connectTimeout(timeout).build();
    }

    @Override
    public Turn respond(String systemPrompt, List<Exchange> transcript, List<McpHttpClient.ToolSpec> tools) {
        ObjectNode request = json.createObjectNode();
        request.put("model", model);
        request.put("max_tokens", maxTokens);
        request.put("temperature", 0);
        request.put("system", systemPrompt);
        request.set("messages", messages(transcript));
        if (!tools.isEmpty()) {
            request.set("tools", toolSchemas(tools));
        }

        JsonNode body = post(request);
        StringBuilder text = new StringBuilder();
        List<RequestedCall> calls = new ArrayList<>();
        for (JsonNode block : body.path("content")) {
            switch (block.path("type").asText("")) {
                case "text" -> text.append(block.path("text").asText(""));
                case "tool_use" -> calls.add(new RequestedCall(
                        block.path("id").asText(""),
                        block.path("name").asText(""),
                        arguments(block.path("input"))));
                default -> { }   // thinking blocks and anything added later: not this harness's
            }
        }
        return new Turn(text.toString(), calls);
    }

    @Override
    public String describe() {
        return model + " via " + endpoint.getHost();
    }

    /**
     * The transcript as content blocks.
     *
     * <p>Anthropic puts tool results in a <b>user</b> message, not a role of their own, and carries
     * a real {@code is_error} flag — so a refusal keeps its shape here instead of being prefixed
     * into prose the way the OpenAI shape forces. The code still goes into the text, because
     * {@code is_error} says that something went wrong and not <em>which guard</em> said so, and the
     * guard scenarios turn on the model telling a policy refusal from a broken tool.
     */
    private ArrayNode messages(List<Exchange> transcript) {
        ArrayNode messages = json.createArrayNode();
        for (Exchange entry : transcript) {
            switch (entry) {
                case Exchange.User user -> messages.addObject()
                        .put("role", "user").put("content", user.text());
                case Exchange.Assistant assistant -> {
                    ArrayNode content = messages.addObject().put("role", "assistant").putArray("content");
                    if (!assistant.text().isBlank()) {
                        content.addObject().put("type", "text").put("text", assistant.text());
                    }
                    for (RequestedCall call : assistant.calls()) {
                        ObjectNode use = content.addObject();
                        use.put("type", "tool_use").put("id", call.id()).put("name", call.name());
                        use.set("input", json.valueToTree(call.arguments()));
                    }
                }
                case Exchange.ToolResult result -> {
                    ArrayNode content = messages.addObject().put("role", "user").putArray("content");
                    ObjectNode block = content.addObject();
                    block.put("type", "tool_result").put("tool_use_id", result.callId());
                    block.put("is_error", result.refusalCode() != null);
                    block.put("content", result.refusalCode() == null
                            ? result.text()
                            : "[refused, JSON-RPC code " + result.refusalCode() + "] " + result.text());
                }
            }
        }
        return messages;
    }

    private ArrayNode toolSchemas(List<McpHttpClient.ToolSpec> tools) {
        ArrayNode schemas = json.createArrayNode();
        for (McpHttpClient.ToolSpec tool : tools) {
            ObjectNode schema = schemas.addObject();
            schema.put("name", tool.name());
            schema.put("description", tool.description());
            schema.set("input_schema", tool.inputSchema());
        }
        return schemas;
    }

    private Map<String, Object> arguments(JsonNode input) {
        Map<String, Object> arguments = new LinkedHashMap<>();
        input.properties().forEach(field ->
                arguments.put(field.getKey(), json.convertValue(field.getValue(), Object.class)));
        return arguments;
    }

    private JsonNode post(ObjectNode request) {
        HttpRequest httpRequest = HttpRequest.newBuilder(endpoint)
                .header("Content-Type", "application/json")
                .header("x-api-key", apiKey == null ? "" : apiKey)
                .header("anthropic-version", API_VERSION)
                .POST(HttpRequest.BodyPublishers.ofString(request.toString()))
                .build();
        try {
            HttpResponse<String> response =
                    http.send(httpRequest, HttpResponse.BodyHandlers.ofString());
            JsonNode body = json.readTree(response.body() == null || response.body().isBlank()
                    ? "{}" : response.body());
            if (response.statusCode() >= 400 || "error".equals(body.path("type").asText())) {
                // See OpenAiToolCallingModel: a provider error folded into an empty turn would be
                // scored as the agent failing the scenario, which blames the wrong party.
                throw new IllegalStateException("The model endpoint answered HTTP "
                        + response.statusCode() + ": " + body.path("error").toString());
            }
            return body;
        } catch (IOException e) {
            throw new UncheckedIOException("Model request to " + endpoint + " failed", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted waiting for " + endpoint, e);
        }
    }
}
