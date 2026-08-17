// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors
package com.compagnonsdudev.kafkasqlexplorer.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.compagnonsdudev.kafkasqlexplorer.config.ClaudeConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;

public class OpenAiCompatibleLlmClient implements LlmClient {
    private static final Logger log = LoggerFactory.getLogger(OpenAiCompatibleLlmClient.class);
    private final ClaudeConfig config;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public OpenAiCompatibleLlmClient(ClaudeConfig config) {
        this.config = config;
        this.httpClient = LlmHttpSupport.newClient(config);
    }

    @Override
    public String generate(String systemPrompt, String userPrompt) {
        try {
            String baseUrl = config.getResolvedBaseUrl();
            String url = baseUrl + "/v1/chat/completions";
            if (baseUrl.endsWith("/v1")) {
                url = baseUrl + "/chat/completions";
            } else if (baseUrl.endsWith("/v1/")) {
                url = baseUrl + "chat/completions";
            }

            Map<String, Object> body = Map.of(
                "model", config.getModel(),
                "messages", List.of(
                    Map.of("role", "system", "content", systemPrompt),
                    Map.of("role", "user", "content", userPrompt)
                ),
                "max_tokens", config.getMaxTokens(),
                "temperature", 0.0,
                "stream", false
            );

            String requestBody = objectMapper.writeValueAsString(body);

            HttpRequest.Builder requestBuilder = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(requestBody));
            LlmHttpSupport.withTimeout(requestBuilder, config);

            if (config.isApiKeyConfigured()) {
                requestBuilder.header("Authorization", "Bearer " + config.getApiKey());
            }

            HttpResponse<String> response =
                LlmHttpSupport.sendWithRetry(httpClient, requestBuilder.build(), config.getProviderLabel());

            JsonNode root = objectMapper.readTree(response.body());
            return firstChoiceContent(root, response.body());

        } catch (RuntimeException e) {
            log.error("Error calling OpenAI-compatible API: {}", e.getMessage());
            throw e;
        } catch (Exception e) {
            log.error("Error calling OpenAI-compatible API: {}", e.getMessage(), e);
            throw new RuntimeException("LLM call failed: " + e.getMessage(), e);
        }
    }

    /**
     * Reads {@code choices[0].message.content}, refusing an answerless body rather than crashing on
     * it. {@code path("choices").get(0)} returns {@code null} when the array is absent or empty —
     * which is exactly what a 200-with-an-error body looks like on Ollama and on several
     * OpenAI-compatible gateways — so the NPE that followed reached the user as
     * "LLM call failed: null", the one message that names neither cause nor remedy. The provider's
     * own {@code error.message} is the useful half of such a body, so it is what gets reported.
     */
    static String firstChoiceContent(JsonNode root, String rawBody) {
        JsonNode error = root.path("error");
        if (!error.isMissingNode() && !error.isNull()) {
            String detail = error.isTextual() ? error.asText() : error.path("message").asText("");
            throw new RuntimeException("LLM provider returned an error: "
                + (detail.isBlank() ? truncate(rawBody) : detail));
        }

        JsonNode choice = root.path("choices").path(0);
        if (choice.isMissingNode()) {
            throw new RuntimeException("LLM response contained no choices "
                + "(check the model name and that the endpoint is an OpenAI-compatible "
                + "/chat/completions API): " + truncate(rawBody));
        }

        JsonNode content = choice.path("message").path("content");
        if (content.isMissingNode() || content.isNull()) {
            throw new RuntimeException("LLM response choice carried no message content: "
                + truncate(rawBody));
        }
        return content.asText();
    }

    private static String truncate(String body) {
        if (body == null) {
            return "";
        }
        return body.length() > 300 ? body.substring(0, 300) + "…" : body;
    }
}
