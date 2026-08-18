// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors
package com.compagnonsdudev.kafkasqlexplorer.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.compagnonsdudev.kafkasqlexplorer.config.ClaudeConfig;
import com.compagnonsdudev.kafkasqlexplorer.domain.LlmResponse;
import com.compagnonsdudev.kafkasqlexplorer.domain.LlmUsage;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The two things this pair of changes promises: the answer is <em>constrained</em> rather than
 * merely requested, and what the call cost is measured instead of guessed.
 *
 * <p>Driven against a real loopback HTTP server rather than a mock, because what matters here is
 * the bytes on the wire — whether {@code response_format} is present, and whether {@code usage} is
 * read back — and a mocked client would assert only that this test agrees with itself.
 */
class LlmStructuredOutputTest {

    private HttpServer server;
    private final List<String> requestBodies = new ArrayList<>();

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    /** Starts a stub endpoint; each call answers with the next scripted response. */
    private ClaudeConfig startServer(List<StubResponse> script) throws IOException {
        AtomicInteger call = new AtomicInteger();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            requestBodies.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            StubResponse stub = script.get(Math.min(call.getAndIncrement(), script.size() - 1));
            byte[] body = stub.body().getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(stub.status(), body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        server.start();

        ClaudeConfig config = new ClaudeConfig();
        config.setProvider(ClaudeConfig.Provider.OLLAMA);
        config.setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort() + "/v1");
        config.setModel("qwen3:4b");
        return config;
    }

    private record StubResponse(int status, String body) {
    }

    private static String okBody() {
        return "{\"choices\":[{\"message\":{\"content\":\"{}\"}}],"
            + "\"usage\":{\"prompt_tokens\":1200,\"completion_tokens\":340}}";
    }

    private static LlmOutputSchema schema() {
        return new LlmOutputSchema("process_mining_result", LlmSchemas.processMiningResult());
    }

    @Test
    void sendsTheSchemaWhenTheProviderSupportsIt() throws Exception {
        ClaudeConfig config = startServer(List.of(new StubResponse(200, okBody())));

        new OpenAiCompatibleLlmClient(config).generateWithMeta("SYS", "USR", schema());

        JsonNode sent = new ObjectMapper().readTree(requestBodies.get(0));
        assertEquals("json_schema", sent.path("response_format").path("type").asText(),
            "the answer must be constrained, not merely requested in the prompt");
        assertEquals("process_mining_result",
            sent.path("response_format").path("json_schema").path("name").asText());
        assertTrue(sent.path("response_format").path("json_schema").path("schema")
                .path("properties").has("flowchart"),
            "the schema itself must travel, not just its name");
    }

    @Test
    void readsBackWhatTheCallCost() throws Exception {
        ClaudeConfig config = startServer(List.of(new StubResponse(200, okBody())));

        LlmResponse response = new OpenAiCompatibleLlmClient(config)
            .generateWithMeta("SYS", "USR", schema());

        LlmUsage usage = response.usage();
        assertNotNull(usage, "a call whose cost is not recorded cannot be tuned against");
        assertEquals(1200L, usage.inputTokens());
        assertEquals(340L, usage.outputTokens());
        assertEquals(1540L, usage.totalTokens());
        assertTrue(usage.durationMs() >= 0);
        assertEquals("qwen3:4b", usage.model());
    }

    /** A gateway that omits `usage` leaves the counts unknown — never zero. */
    @Test
    void reportsUnknownRatherThanZeroWhenTheProviderSaysNothing() throws Exception {
        ClaudeConfig config = startServer(List.of(new StubResponse(200,
            "{\"choices\":[{\"message\":{\"content\":\"{}\"}}]}")));

        LlmUsage usage = new OpenAiCompatibleLlmClient(config)
            .generateWithMeta("SYS", "USR", schema()).usage();

        assertNull(usage.inputTokens(), "zero would claim the call was free");
        assertNull(usage.outputTokens());
        assertNull(usage.totalTokens(), "a half-known total is worse than none");
        assertTrue(usage.durationMs() >= 0, "the duration is measured here, so it is always real");
    }

    /**
     * The degradation that makes AUTO safe to ship: an endpoint that rejects `response_format`
     * gets one unconstrained retry rather than an error blamed on the operator's configuration.
     */
    @Test
    void retriesWithoutTheSchemaWhenTheEndpointRefusesIt() throws Exception {
        ClaudeConfig config = startServer(List.of(
            new StubResponse(400, "{\"error\":{\"message\":\"unknown field response_format\"}}"),
            new StubResponse(200, okBody())));

        OpenAiCompatibleLlmClient client = new OpenAiCompatibleLlmClient(config);
        LlmResponse first = client.generateWithMeta("SYS", "USR", schema());

        assertEquals("{}", first.text(), "the retry's answer is what the caller gets");
        assertEquals(2, requestBodies.size());
        assertTrue(requestBodies.get(0).contains("response_format"));
        assertFalse(requestBodies.get(1).contains("response_format"),
            "the retry must drop the field the endpoint refused");

        // And it must not probe again: the observation is remembered for this client's lifetime.
        client.generateWithMeta("SYS", "USR", schema());
        assertEquals(3, requestBodies.size());
        assertFalse(requestBodies.get(2).contains("response_format"),
            "an endpoint that refused once must not be probed on every later call");
    }

    /** A 4xx that has nothing to do with the schema still surfaces — it is not swallowed by the retry. */
    @Test
    void stillReportsAGenuineClientError() throws Exception {
        ClaudeConfig config = startServer(List.of(
            new StubResponse(404, "{\"error\":{\"message\":\"model not found\"}}")));

        OpenAiCompatibleLlmClient client = new OpenAiCompatibleLlmClient(config);
        RuntimeException e = assertThrows(RuntimeException.class,
            () -> client.generateWithMeta("SYS", "USR", schema()));

        assertTrue(e.getMessage().contains("model not found"), e.getMessage());
    }

    /**
     * A 4xx that cannot mean "I do not implement {@code response_format}" must not disable
     * structured output.
     *
     * <p>The latch used to fire on any 4xx, and the two that matter are 401 and 404 — a wrong key
     * and a wrong model or path. Those are configuration mistakes an operator fixes within the
     * minute, but the latch outlives the fix: {@link LlmClientProvider} fingerprints provider, base
     * URL and key, and the <em>model</em> is in none of them, so correcting a mistyped model reuses
     * this very client. The deployment then ran unconstrained for ever, silently, because of a typo
     * that had already been corrected.
     */
    @Test
    void aWrongModelDoesNotDisableStructuredOutputForGood() throws Exception {
        ClaudeConfig config = startServer(List.of(
            new StubResponse(404, "{\"error\":{\"message\":\"model not found\"}}"),
            new StubResponse(200, okBody())));

        OpenAiCompatibleLlmClient client = new OpenAiCompatibleLlmClient(config);
        assertThrows(RuntimeException.class, () -> client.generateWithMeta("SYS", "USR", schema()));
        assertEquals(1, requestBodies.size(),
            "a 404 says nothing about response_format, so there is nothing to retry without it");

        // The operator corrects the model name; the client is the same one, and must still constrain.
        client.generateWithMeta("SYS", "USR", schema());
        assertEquals(2, requestBodies.size());
        assertTrue(requestBodies.get(1).contains("response_format"),
            "structured output must survive a failure that was never about the schema");
    }

    /** 422 is the other way a gateway says "I do not understand this field". */
    @Test
    void treatsUnprocessableEntityAsASchemaRefusalToo() throws Exception {
        ClaudeConfig config = startServer(List.of(
            new StubResponse(422, "{\"error\":{\"message\":\"response_format not supported\"}}"),
            new StubResponse(200, okBody())));

        new OpenAiCompatibleLlmClient(config).generateWithMeta("SYS", "USR", schema());

        assertEquals(2, requestBodies.size());
        assertFalse(requestBodies.get(1).contains("response_format"));
    }

    @Test
    void doesNotConstrainWhenTheSettingIsOff() throws Exception {
        ClaudeConfig config = startServer(List.of(new StubResponse(200, okBody())));
        config.setStructuredOutput(ClaudeConfig.StructuredOutput.OFF);

        new OpenAiCompatibleLlmClient(config).generateWithMeta("SYS", "USR", schema());

        assertFalse(requestBodies.get(0).contains("response_format"));
    }

    /**
     * AUTO is not "on everywhere": an arbitrary OpenAI-compatible gateway is left alone, because
     * turning a working deployment into a failing one is the wrong default.
     */
    @Test
    void autoLeavesAnUnknownGatewayAlone() throws Exception {
        ClaudeConfig config = startServer(List.of(new StubResponse(200, okBody())));
        config.setProvider(ClaudeConfig.Provider.OPENAI_COMPATIBLE);

        assertFalse(config.isStructuredOutputEnabled());
        new OpenAiCompatibleLlmClient(config).generateWithMeta("SYS", "USR", schema());
        assertFalse(requestBodies.get(0).contains("response_format"));

        config.setStructuredOutput(ClaudeConfig.StructuredOutput.ON);
        assertTrue(config.isStructuredOutputEnabled(), "ON is how an operator opts a known gateway in");
    }

    @Test
    void schemasAreValidJsonSchemaObjects() {
        for (Map<String, Object> schema : List.of(
                LlmSchemas.processMiningResult(), LlmSchemas.fieldProfileResult())) {
            assertEquals("object", schema.get("type"));
            assertEquals(Boolean.FALSE, schema.get("additionalProperties"),
                "strict mode requires additionalProperties:false on the root");
            assertNotNull(schema.get("properties"));
            assertNotNull(schema.get("required"));
        }
    }
}
