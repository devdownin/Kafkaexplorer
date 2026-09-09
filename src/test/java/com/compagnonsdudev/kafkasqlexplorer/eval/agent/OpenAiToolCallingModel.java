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
 * The OpenAI {@code /chat/completions} tool-calling API — which is what OpenRouter, Ollama and every
 * OpenAI-compatible endpoint speak.
 *
 * <p>Written against the wire rather than through an SDK, for the reason {@link McpHttpClient}
 * gives: the harness is a client of things it does not own, and a dependency that renders a
 * transcript for it is one more place a bug can be indistinguishable from the model's own
 * behaviour. It also keeps the harness's only new dependency at zero.
 *
 * <p><b>The tool schemas are the server's own</b>, passed through from {@code tools/list} untouched.
 * Rewriting them here would mean the model was choosing against a description this harness wrote,
 * which is precisely the variable the run is trying to hold fixed.
 */
final class OpenAiToolCallingModel implements AgentModel {

    private final ObjectMapper json = new ObjectMapper();
    private final HttpClient http;
    private final URI endpoint;
    private final String apiKey;
    private final String model;

    OpenAiToolCallingModel(URI endpoint, String apiKey, String model, Duration timeout) {
        this.endpoint = endpoint;
        this.apiKey = apiKey;
        this.model = model;
        this.http = HttpClient.newBuilder().connectTimeout(timeout).build();
    }

    @Override
    public Turn respond(String systemPrompt, List<Exchange> transcript, List<McpHttpClient.ToolSpec> tools) {
        ObjectNode request = json.createObjectNode();
        request.put("model", model);
        // Zero, because a scenario is measuring what the tool descriptions teach, and sampling noise
        // would be measured as part of it. AGENT_EVAL_REPEAT is how variance is measured instead —
        // deliberately, and reported rather than averaged away.
        request.put("temperature", 0);
        request.set("messages", messages(systemPrompt, transcript));
        if (!tools.isEmpty()) {
            request.set("tools", toolSchemas(tools));
        }

        JsonNode choice = post(request).path("choices").path(0).path("message");
        List<RequestedCall> calls = new ArrayList<>();
        for (JsonNode call : choice.path("tool_calls")) {
            calls.add(new RequestedCall(
                    call.path("id").asText(""),
                    call.path("function").path("name").asText(""),
                    arguments(call.path("function").path("arguments").asText("{}"))));
        }
        return new Turn(choice.path("content").asText(""), calls);
    }

    @Override
    public String describe() {
        return model + " via " + endpoint.getHost();
    }

    private ArrayNode messages(String systemPrompt, List<Exchange> transcript) {
        ArrayNode messages = json.createArrayNode();
        messages.addObject().put("role", "system").put("content", systemPrompt);
        for (Exchange entry : transcript) {
            switch (entry) {
                case Exchange.User user -> messages.addObject().put("role", "user").put("content", user.text());
                case Exchange.Assistant assistant -> {
                    ObjectNode message = messages.addObject().put("role", "assistant");
                    message.put("content", assistant.text());
                    ArrayNode calls = message.putArray("tool_calls");
                    for (RequestedCall call : assistant.calls()) {
                        ObjectNode entryNode = calls.addObject();
                        entryNode.put("id", call.id()).put("type", "function");
                        entryNode.putObject("function")
                                .put("name", call.name())
                                .put("arguments", writeArguments(call.arguments()));
                    }
                }
                // The refusal code is prefixed rather than dropped: the API has no field for it,
                // and a model told only "outside the configured scope" cannot tell a guard from a
                // broken tool — which is the distinction every guard scenario turns on.
                case Exchange.ToolResult result -> messages.addObject()
                        .put("role", "tool")
                        .put("tool_call_id", result.callId())
                        .put("content", result.refusalCode() == null
                                ? result.text()
                                : "[refused, JSON-RPC code " + result.refusalCode() + "] " + result.text());
            }
        }
        return messages;
    }

    private ArrayNode toolSchemas(List<McpHttpClient.ToolSpec> tools) {
        ArrayNode schemas = json.createArrayNode();
        for (McpHttpClient.ToolSpec tool : tools) {
            ObjectNode function = schemas.addObject().put("type", "function").putObject("function");
            function.put("name", tool.name());
            function.put("description", tool.description());
            function.set("parameters", tool.inputSchema());
        }
        return schemas;
    }

    /** The arguments arrive as a JSON *string*; a model that produced something else sends none. */
    private Map<String, Object> arguments(String raw) {
        try {
            JsonNode parsed = json.readTree(raw.isBlank() ? "{}" : raw);
            Map<String, Object> arguments = new LinkedHashMap<>();
            parsed.properties().forEach(field ->
                    arguments.put(field.getKey(), json.convertValue(field.getValue(), Object.class)));
            return arguments;
        } catch (IOException e) {
            return Map.of();
        }
    }

    private String writeArguments(Map<String, Object> arguments) {
        try {
            return json.writeValueAsString(arguments);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private JsonNode post(ObjectNode request) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(endpoint)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(request.toString()));
        if (apiKey != null && !apiKey.isBlank()) {
            builder.header("Authorization", "Bearer " + apiKey);
        }
        try {
            HttpResponse<String> response =
                    http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
            JsonNode body = json.readTree(response.body() == null || response.body().isBlank()
                    ? "{}" : response.body());
            if (response.statusCode() >= 400 || body.has("error")) {
                // Raised rather than folded into an empty turn: a provider error read as "the model
                // said nothing" would score as a scenario the agent failed, which blames the wrong
                // party and is the one report this harness must never produce.
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
