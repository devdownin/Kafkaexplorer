// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors
package com.yourcompany.kafkasqlexplorer.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yourcompany.kafkasqlexplorer.config.ClaudeConfig;
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
            return root.path("choices").get(0).path("message").path("content").asText();

        } catch (RuntimeException e) {
            log.error("Error calling OpenAI-compatible API: {}", e.getMessage());
            throw e;
        } catch (Exception e) {
            log.error("Error calling OpenAI-compatible API: {}", e.getMessage(), e);
            throw new RuntimeException("LLM call failed: " + e.getMessage(), e);
        }
    }
}
