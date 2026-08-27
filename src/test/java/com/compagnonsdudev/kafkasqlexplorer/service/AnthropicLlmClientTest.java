// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors
package com.compagnonsdudev.kafkasqlexplorer.service;

import com.compagnonsdudev.kafkasqlexplorer.config.ClaudeConfig;
import com.compagnonsdudev.kafkasqlexplorer.domain.LlmResponse;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Anthropic path, which had no test at all.
 *
 * <p>That is the gap this class exists to close rather than a detail: everything the audit found
 * on it — the configured timeout not applied, no remedy wording, no degrade when a schema is
 * refused, a temperature left at the vendor default — was invisible precisely because nothing ever
 * exercised the class. The SDK honours {@code baseUrl}, so the same stub-server harness the
 * OpenAI-compatible and SpectraLLM clients are driven through reaches it too; there was never
 * anything preventing this.
 */
class AnthropicLlmClientTest {

    private HttpServer server;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final List<String> requestBodies = new ArrayList<>();

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    /** One scripted answer per call: a status, and a body (an SSE stream on 200). */
    private record Stub(int status, String body) {
    }

    private ClaudeConfig startServer(List<Stub> script) throws IOException {
        AtomicInteger call = new AtomicInteger();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/messages", exchange -> {
            requestBodies.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            Stub stub = script.get(Math.min(call.getAndIncrement(), script.size() - 1));
            byte[] out = stub.body().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type",
                stub.status() == 200 ? "text/event-stream" : "application/json");
            exchange.sendResponseHeaders(stub.status(), out.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(out);
            }
        });
        server.start();

        ClaudeConfig config = new ClaudeConfig();
        config.setProvider(ClaudeConfig.Provider.ANTHROPIC);
        config.setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
        config.setApiKey("sk-ant-test");
        config.setModel("claude-3-5-sonnet-20241022");
        config.setRequestTimeoutSeconds(5);
        return config;
    }

    /** A complete streamed answer of "{}", with usage split across message_start and message_delta. */
    private static String streamedAnswer() {
        return """
            event: message_start
            data: {"type":"message_start","message":{"id":"msg_1","type":"message","role":"assistant",\
            "model":"claude-3-5-sonnet-20241022","content":[],"stop_reason":null,"stop_sequence":null,\
            "usage":{"input_tokens":1200,"output_tokens":1}}}

            event: content_block_start
            data: {"type":"content_block_start","index":0,"content_block":{"type":"text","text":""}}

            event: content_block_delta
            data: {"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"{}"}}

            event: content_block_stop
            data: {"type":"content_block_stop","index":0}

            event: message_delta
            data: {"type":"message_delta","delta":{"stop_reason":"end_turn","stop_sequence":null},\
            "usage":{"output_tokens":340}}

            event: message_stop
            data: {"type":"message_stop"}

            """;
    }

    private static LlmOutputSchema schema() {
        return new LlmOutputSchema("process_mining_result", LlmSchemas.processMiningResult());
    }

    private JsonNode sentBody(int index) throws IOException {
        return objectMapper.readTree(requestBodies.get(index));
    }

    @Test
    void constrainsTheAnswerAndReadsBackWhatTheCallCost() throws Exception {
        ClaudeConfig config = startServer(List.of(new Stub(200, streamedAnswer())));

        LlmResponse response = new AnthropicLlmClient(config)
            .generateWithMeta("SYS", "USR", schema());

        assertEquals("{}", response.text());
        JsonNode sent = sentBody(0);
        assertTrue(sent.path("output_config").path("format").path("schema").path("properties")
                .has("flowchart"),
            "the schema itself must travel, not just the request for JSON");
        assertEquals(1200L, response.usage().inputTokens());
        assertEquals(340L, response.usage().outputTokens());
        // Read, never derived: this API prices nothing, and no price table lives in this application.
        assertEquals(null, response.usage().costUsd());
    }

    /**
     * Pinned like every other provider's, and it was the one call in the pipeline left at the
     * vendor default — on an answer that is parsed as JSON.
     */
    @Test
    void asksForADeterministicAnswerLikeEveryOtherProviderHere() throws Exception {
        ClaudeConfig config = startServer(List.of(new Stub(200, streamedAnswer())));

        new AnthropicLlmClient(config).generateWithMeta("SYS", "USR", schema());

        assertEquals(0.0, sentBody(0).path("temperature").asDouble(-1));
    }

    /**
     * The degrade this path did not have.
     *
     * <p>{@code structured-output: AUTO} turns schemas <em>on</em> for ANTHROPIC, so a model, a
     * gateway in front or an account not enabled for the feature that refuses {@code output_config}
     * failed the analysis outright — where the same refusal on the OpenAI-compatible path costs one
     * retry and the run succeeds.
     */
    @Test
    void retriesWithoutTheSchemaWhenTheEndpointRefusesIt() throws Exception {
        ClaudeConfig config = startServer(List.of(
            new Stub(400, "{\"type\":\"error\",\"error\":{\"type\":\"invalid_request_error\","
                + "\"message\":\"output_config: unrecognized field\"}}"),
            new Stub(200, streamedAnswer())));

        AnthropicLlmClient client = new AnthropicLlmClient(config);
        assertEquals("{}", client.generateWithMeta("SYS", "USR", schema()).text());

        assertEquals(2, requestBodies.size());
        assertTrue(sentBody(0).has("output_config"));
        assertFalse(sentBody(1).has("output_config"), "the retry drops the field that was refused");

        // And the observation is kept, so the next call does not probe again.
        client.generateWithMeta("SYS", "USR", schema());
        assertEquals(3, requestBodies.size());
        assertFalse(sentBody(2).has("output_config"));
    }

    /** A refusal the retry disproves teaches nothing — same rule as the sibling client. */
    @Test
    void aFailureThatSurvivesDroppingTheSchemaLeavesNoConclusion() throws Exception {
        ClaudeConfig config = startServer(List.of(
            new Stub(400, "{\"type\":\"error\",\"error\":{\"type\":\"invalid_request_error\","
                + "\"message\":\"max_tokens: too large for this model\"}}"),
            new Stub(400, "{\"type\":\"error\",\"error\":{\"type\":\"invalid_request_error\","
                + "\"message\":\"max_tokens: too large for this model\"}}"),
            new Stub(200, streamedAnswer())));

        AnthropicLlmClient client = new AnthropicLlmClient(config);
        RuntimeException e = assertThrows(RuntimeException.class,
            () -> client.generateWithMeta("SYS", "USR", schema()));
        assertTrue(e.getMessage().contains("max_tokens"), e.getMessage());
        assertEquals(2, requestBodies.size(), "one probe, no more");

        client.generateWithMeta("SYS", "USR", schema());
        assertTrue(sentBody(2).has("output_config"),
            "a refusal the retry disproved must not disable structured output for this model");
    }

    /**
     * The wording every other provider's client errors carry, shared rather than written twice: a
     * 402 is an account out of credit, and no amount of checking the base URL, the model or the key
     * resolves it.
     */
    @Test
    void namesTheRealRemedyOnAMeteredAccountsError() throws Exception {
        ClaudeConfig config = startServer(List.of(
            new Stub(402, "{\"type\":\"error\",\"error\":{\"type\":\"billing_error\","
                + "\"message\":\"Your credit balance is too low\"}}")));

        RuntimeException e = assertThrows(RuntimeException.class,
            () -> new AnthropicLlmClient(config).generateWithMeta("SYS", "USR", schema()));

        assertTrue(e.getMessage().contains("402"), e.getMessage());
        assertTrue(e.getMessage().contains("out of credit"), e.getMessage());
        assertEquals(1, requestBodies.size(), "a 402 says nothing about the schema, so nothing is retried");
    }

    /** A schema is not sent at all when the deployment turned it off. */
    @Test
    void sendsNoSchemaWhenTheSettingIsOff() throws Exception {
        ClaudeConfig config = startServer(List.of(new Stub(200, streamedAnswer())));
        config.setStructuredOutput(ClaudeConfig.StructuredOutput.OFF);

        new AnthropicLlmClient(config).generateWithMeta("SYS", "USR", schema());

        assertFalse(sentBody(0).has("output_config"));
    }
}
