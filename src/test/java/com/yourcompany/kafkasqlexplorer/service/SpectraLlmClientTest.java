// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors
package com.yourcompany.kafkasqlexplorer.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import com.yourcompany.kafkasqlexplorer.config.ClaudeConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class SpectraLlmClientTest {

    private HttpServer server;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final AtomicReference<String> lastRequestBody = new AtomicReference<>();
    private final AtomicReference<String> lastRequestPath = new AtomicReference<>();

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    private ClaudeConfig startServer(int status, String responseBody) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/query", exchange -> {
            lastRequestPath.set(exchange.getRequestURI().getPath());
            lastRequestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] out = responseBody.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, out.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(out);
            }
        });
        server.start();

        ClaudeConfig config = new ClaudeConfig();
        config.setProvider(ClaudeConfig.Provider.SPECTRA);
        config.setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
        return config;
    }

    @Test
    void returnsAnswerFieldFromSpectraResponse() throws Exception {
        ClaudeConfig config = startServer(200, "{\"answer\":\"No anomalies detected\",\"sources\":[]}");

        SpectraLlmClient client = new SpectraLlmClient(config);
        String result = client.generate("You are an auditor", "Audit these messages");

        assertEquals("No anomalies detected", result);
    }

    @Test
    void concatenatesSystemAndUserPromptAndSendsUseRag() throws Exception {
        ClaudeConfig config = startServer(200, "{\"answer\":\"ok\"}");
        config.setUseRag(false);

        SpectraLlmClient client = new SpectraLlmClient(config);
        client.generate("SYS", "USR");

        assertEquals("/api/query", lastRequestPath.get());
        JsonNode sent = objectMapper.readTree(lastRequestBody.get());
        assertEquals("SYS\n\nUSR", sent.path("question").asText());
        assertFalse(sent.path("useRag").asBoolean());
    }

    @Test
    void forwardsUseRagTrueWhenConfigured() throws Exception {
        ClaudeConfig config = startServer(200, "{\"answer\":\"ok\"}");
        config.setUseRag(true);

        new SpectraLlmClient(config).generate("SYS", "USR");

        JsonNode sent = objectMapper.readTree(lastRequestBody.get());
        assertTrue(sent.path("useRag").asBoolean());
    }

    @Test
    void trailingSlashInBaseUrlIsHandled() throws Exception {
        ClaudeConfig config = startServer(200, "{\"answer\":\"ok\"}");
        config.setBaseUrl(config.getBaseUrl() + "/");

        String result = new SpectraLlmClient(config).generate(null, "USR");

        assertEquals("ok", result);
        assertEquals("/api/query", lastRequestPath.get());
    }

    @Test
    void nonBlankSystemPromptOnlyWhenPresent() throws Exception {
        ClaudeConfig config = startServer(200, "{\"answer\":\"ok\"}");

        new SpectraLlmClient(config).generate("   ", "USR");

        JsonNode sent = objectMapper.readTree(lastRequestBody.get());
        assertEquals("USR", sent.path("question").asText());
    }

    @Test
    void throwsOnNonOkStatus() throws Exception {
        ClaudeConfig config = startServer(500, "boom");

        SpectraLlmClient client = new SpectraLlmClient(config);
        RuntimeException ex = assertThrows(RuntimeException.class,
            () -> client.generate("SYS", "USR"));
        assertTrue(ex.getMessage().contains("SpectraLLM call failed"));
    }

    @Test
    void throwsWhenAnswerMissing() throws Exception {
        ClaudeConfig config = startServer(200, "{\"sources\":[]}");

        SpectraLlmClient client = new SpectraLlmClient(config);
        assertThrows(RuntimeException.class, () -> client.generate("SYS", "USR"));
    }
}
