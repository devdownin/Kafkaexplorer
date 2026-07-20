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
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * {@link LlmClient} backed by a SpectraLLM instance (https://github.com/devdownin/SpectraLLM).
 *
 * <p>SpectraLLM exposes a single-turn RAG endpoint {@code POST /api/query} that accepts a
 * {@code question} and returns an {@code answer}. Unlike the OpenAI / Anthropic chat APIs it
 * has no dedicated {@code system} role, so the audit system prompt and the message payload are
 * concatenated into one question. Retrieval is disabled by default ({@code useRag=false}) because
 * the Kafka audit prompt already carries all the message context inline; set {@code claude.use-rag}
 * to {@code true} to let SpectraLLM enrich the audit with its ingested corpus.
 */
public class SpectraLlmClient implements LlmClient {
    private static final Logger log = LoggerFactory.getLogger(SpectraLlmClient.class);
    private final ClaudeConfig config;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public SpectraLlmClient(ClaudeConfig config) {
        this.config = config;
        this.httpClient = HttpClient.newHttpClient();
    }

    @Override
    public String generate(String systemPrompt, String userPrompt) {
        try {
            String question = (systemPrompt == null || systemPrompt.isBlank())
                ? userPrompt
                : systemPrompt + "\n\n" + userPrompt;

            Map<String, Object> body = new LinkedHashMap<>();
            body.put("question", question);
            body.put("useRag", config.isUseRag());
            body.put("temperature", 0.0);

            String requestBody = objectMapper.writeValueAsString(body);

            HttpRequest.Builder requestBuilder = HttpRequest.newBuilder()
                .uri(URI.create(resolveQueryUrl()))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(requestBody));

            // SpectraLLM is API-key-less by default, but honour a bearer token if one is configured
            // (e.g. when the instance sits behind an authenticating reverse proxy).
            if (config.isApiKeyConfigured()) {
                requestBuilder.header("Authorization", "Bearer " + config.getApiKey());
            }

            HttpResponse<String> response = httpClient.send(requestBuilder.build(),
                HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                log.error("Error from SpectraLLM API: {} - {}", response.statusCode(), response.body());
                throw new RuntimeException("SpectraLLM call failed with status " + response.statusCode());
            }

            JsonNode root = objectMapper.readTree(response.body());
            JsonNode answer = root.path("answer");
            if (answer.isMissingNode() || answer.isNull()) {
                log.error("SpectraLLM response has no 'answer' field: {}", response.body());
                throw new RuntimeException("SpectraLLM response did not contain an answer");
            }
            return answer.asText();

        } catch (Exception e) {
            log.error("Error calling SpectraLLM API: {}", e.getMessage(), e);
            throw new RuntimeException("SpectraLLM call failed", e);
        }
    }

    /** Builds {@code <base-url>/api/query}, tolerating an optional trailing slash on the base URL. */
    private String resolveQueryUrl() {
        String baseUrl = config.getResolvedBaseUrl();
        if (baseUrl == null || baseUrl.isBlank()) {
            baseUrl = ClaudeConfig.defaultBaseUrl(ClaudeConfig.Provider.SPECTRA);
        }
        if (baseUrl.endsWith("/")) {
            baseUrl = baseUrl.substring(0, baseUrl.length() - 1);
        }
        return baseUrl + "/api/query";
    }
}
