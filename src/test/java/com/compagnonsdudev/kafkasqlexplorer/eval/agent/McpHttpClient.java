// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors
package com.compagnonsdudev.kafkasqlexplorer.eval.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The harness's own MCP client: streamable HTTP against the running application's {@code /mcp}.
 *
 * <p><b>A third-party client, deliberately.</b> {@code SPECAGENT.md} §5.1 puts the whole point of
 * the harness on this: what is under test is the surface an outside agent sees, so the harness must
 * not reach for a bean, a mock, or the server's own SDK types. Everything below speaks JSON-RPC over
 * HTTP and reads the answers as text, exactly as a foreign client would — which is what makes it
 * able to notice the Jackson 3 transport losing a {@code Measured} that the REST surface's Jackson 2
 * still serialises.
 *
 * <p><b>It also captures verdict 1.</b> Every call goes through {@link #callTool} and lands in
 * {@link #trace()} as a {@link ToolCall}, with the timings the rate-limit assertion needs and the
 * three answer signals §2.1 reasons about. The model never touches this record, which is what makes
 * the trace assertions deterministic.
 */
final class McpHttpClient implements AutoCloseable {

    private static final String PROTOCOL_VERSION = "2025-06-18";

    private final ObjectMapper json = new ObjectMapper();
    private final HttpClient http;
    private final URI endpoint;
    private final AtomicInteger nextId = new AtomicInteger(1);
    private final List<ToolCall> calls = new ArrayList<>();

    private String sessionId;

    McpHttpClient(URI endpoint, Duration timeout) {
        this.endpoint = endpoint;
        this.http = HttpClient.newBuilder().connectTimeout(timeout).build();
    }

    /** One tool as {@code tools/list} describes it — the name, and the schema to hand the model. */
    record ToolSpec(String name, String description, JsonNode inputSchema) {
    }

    /**
     * What a {@code tools/call} came back as.
     *
     * <p>{@code text} is the tool's payload as the model would read it; {@code refusalCode} is set
     * when the call was refused at either MCP layer, and the distinction between them is not made
     * here on purpose. §2.1 asserts over the code, and MCP's two error channels (a JSON-RPC error,
     * and {@code isError} inside a successful response) both carry one — a harness that only read
     * the first would call an execution refusal a successful call.
     */
    record ToolAnswer(String text,
                      Integer refusalCode,
                      String resumeToken,
                      String auditStatus,
                      Long retryAfterMs) {
    }

    /** The handshake, in the order the protocol requires it. Returns the server's own name. */
    String initialize() {
        ObjectNode params = json.createObjectNode();
        params.put("protocolVersion", PROTOCOL_VERSION);
        params.putObject("capabilities");
        params.putObject("clientInfo").put("name", "kex-agent-harness").put("version", "1");

        HttpResponse<String> response = post(request("initialize", params));
        // The session id travels in a header, and a stateless deployment returns none — both are
        // valid, so its absence is not an error and every later request simply omits it.
        response.headers().firstValue("mcp-session-id").ifPresent(id -> sessionId = id);

        JsonNode result = resultOf(body(response), "initialize");
        post(HttpRequest.BodyPublishers.ofString(
                notification("notifications/initialized").toString()));
        return result.path("serverInfo").path("name").asText("(unnamed)");
    }

    List<ToolSpec> listTools() {
        JsonNode result = resultOf(body(post(request("tools/list", json.createObjectNode()))),
                "tools/list");
        List<ToolSpec> tools = new ArrayList<>();
        for (JsonNode tool : result.path("tools")) {
            tools.add(new ToolSpec(tool.path("name").asText(),
                    tool.path("description").asText(""),
                    tool.path("inputSchema")));
        }
        return List.copyOf(tools);
    }

    /**
     * Invokes a tool and records the call.
     *
     * <p>A refusal is returned rather than thrown, and that is the design: the agent is expected to
     * read a refusal and decide what to do, and a harness that threw would be answering for it.
     */
    ToolAnswer callTool(String name, Map<String, Object> arguments) {
        ObjectNode params = json.createObjectNode();
        params.put("name", name);
        params.set("arguments", json.valueToTree(arguments == null ? Map.of() : arguments));

        long startedAt = System.currentTimeMillis();
        JsonNode envelope = body(post(request("tools/call", params)));
        long finishedAt = System.currentTimeMillis();

        ToolAnswer answer = readAnswer(envelope);
        calls.add(new ToolCall(calls.size() + 1, name, arguments, answer.refusalCode(),
                answer.resumeToken(), answer.auditStatus(), answer.retryAfterMs(),
                startedAt, finishedAt));
        return answer;
    }

    /** The trace so far, for {@link ToolCallTrace}. */
    List<ToolCall> trace() {
        return List.copyOf(calls);
    }

    /**
     * Reads a {@code tools/call} envelope into the four facts §2.1 asserts over.
     *
     * <p>The payload arrives as a JSON document inside a JSON string ({@code content[0].text}), so
     * it is re-parsed rather than searched: a substring match for {@code "stopReason"} would also
     * find the word inside the tool's own description of what a stop reason means, which several of
     * these tools carry.
     */
    private ToolAnswer readAnswer(JsonNode envelope) {
        JsonNode error = envelope.path("error");
        if (!error.isMissingNode() && !error.isNull()) {
            return new ToolAnswer(error.path("message").asText(""),
                    error.path("code").isNumber() ? error.path("code").asInt() : null,
                    null, null, retryAfterMs(error));
        }
        JsonNode result = envelope.path("result");
        String text = result.path("content").path(0).path("text").asText("");
        JsonNode payload = reparse(text);

        Integer refusal = result.path("isError").asBoolean(false)
                ? codeIn(payload).orElse(null) : null;
        String resumeToken = text(payload.path("coverage").path("resumeToken"));
        String auditStatus = firstText(payload, "status", "runStatus", "auditStatus");
        return new ToolAnswer(text, refusal, resumeToken, auditStatus, retryAfterMs(payload));
    }

    /** A code carried inside an execution error's payload, when the tool put one there. */
    private Optional<Integer> codeIn(JsonNode payload) {
        JsonNode code = payload.path("code");
        return code.isNumber() ? Optional.of(code.asInt()) : Optional.empty();
    }

    private Long retryAfterMs(JsonNode node) {
        for (String field : List.of("retryAfterMs", "retry_after_ms", "retryAfter")) {
            JsonNode value = node.path("data").path(field).isMissingNode()
                    ? node.path(field) : node.path("data").path(field);
            if (value.isNumber()) {
                return value.asLong();
            }
        }
        return null;
    }

    private String firstText(JsonNode payload, String... fields) {
        for (String field : fields) {
            String value = text(payload.path(field));
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    private static String text(JsonNode node) {
        return node.isTextual() && !node.asText().isBlank() ? node.asText() : null;
    }

    private JsonNode reparse(String text) {
        try {
            return text.isBlank() ? json.createObjectNode() : json.readTree(text);
        } catch (IOException e) {
            // A tool that answered prose rather than JSON is a real answer, not a client failure:
            // the model reads it, and the signal fields simply stay absent.
            return json.createObjectNode();
        }
    }

    private ObjectNode notification(String method) {
        ObjectNode message = json.createObjectNode();
        message.put("jsonrpc", "2.0");
        message.put("method", method);
        return message;
    }

    private HttpRequest.BodyPublisher request(String method, ObjectNode params) {
        ObjectNode message = notification(method);
        message.put("id", nextId.getAndIncrement());
        message.set("params", params);
        return HttpRequest.BodyPublishers.ofString(message.toString());
    }

    private HttpResponse<String> post(HttpRequest.BodyPublisher payload) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(endpoint)
                .header("Content-Type", "application/json")
                // Both media types, which the spec requires and Spring AI enforces: a request
                // offering only one is refused outright.
                .header("Accept", "application/json, text/event-stream")
                .header("MCP-Protocol-Version", PROTOCOL_VERSION)
                .POST(payload);
        if (sessionId != null) {
            builder.header("Mcp-Session-Id", sessionId);
        }
        try {
            return http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            throw new UncheckedIOException("MCP request to " + endpoint + " failed", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted waiting for " + endpoint, e);
        }
    }

    /**
     * The JSON-RPC envelope, whether it arrived as JSON or wrapped in an SSE frame.
     *
     * <p>The SSE reading is the field's, not a convenience: a real frame carries {@code id:} and
     * {@code event:} lines beside the payload, and the space after {@code data:} is <b>optional</b>
     * in the specification. This client was first written against a stub that emitted
     * {@code "data: "} with a space and nothing else, and it worked until it met the server — which
     * emits an {@code id:} line and no space, so every answer came back as "not JSON". A parser
     * shaped by its own fixture is the failure a stub cannot show you.
     */
    private JsonNode body(HttpResponse<String> response) {
        String raw = response.body() == null ? "" : response.body();
        if (raw.startsWith("data:") || raw.contains("\ndata:")) {
            StringBuilder data = new StringBuilder();
            for (String line : raw.split("\r?\n")) {
                if (line.startsWith("data:")) {
                    // Exactly one optional space, per the SSE grammar: anything beyond it is
                    // payload, and trimming would corrupt a JSON string that starts with one.
                    String payload = line.substring("data:".length());
                    data.append(payload.startsWith(" ") ? payload.substring(1) : payload);
                }
            }
            raw = data.toString();
        }
        if (raw.isBlank()) {
            return json.createObjectNode();   // a notification's 202, which carries no body
        }
        try {
            return json.readTree(raw);
        } catch (IOException e) {
            throw new IllegalStateException(
                    "The MCP endpoint answered HTTP " + response.statusCode()
                            + " with something that is not JSON: " + abbreviate(raw), e);
        }
    }

    private JsonNode resultOf(JsonNode envelope, String method) {
        JsonNode error = envelope.path("error");
        if (!error.isMissingNode() && !error.isNull()) {
            // The whole envelope, not just message and code: a server is free to put its reason
            // anywhere in the error object, and a refusal rendered as "was refused:  (code ?)" —
            // which is what an empty message produces — tells the reader nothing at all.
            throw new IllegalStateException(method + " was refused: " + abbreviate(envelope.toString()));
        }
        JsonNode result = envelope.path("result");
        if (result.isMissingNode()) {
            throw new IllegalStateException(
                    method + " answered without a result: " + abbreviate(envelope.toString()));
        }
        return result;
    }

    private static String abbreviate(String text) {
        return text.length() <= 400 ? text : text.substring(0, 400) + "…";
    }

    @Override
    public void close() {
        http.close();
    }
}
