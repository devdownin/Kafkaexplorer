// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors
package com.compagnonsdudev.kafkasqlexplorer.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.compagnonsdudev.kafkasqlexplorer.config.ClaudeConfig;
import com.compagnonsdudev.kafkasqlexplorer.domain.LlmResponse;
import com.compagnonsdudev.kafkasqlexplorer.domain.RagSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
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
        this.httpClient = LlmHttpSupport.newClient(config);
    }

    @Override
    public String generate(String systemPrompt, String userPrompt) {
        return generateWithMeta(systemPrompt, userPrompt).text();
    }

    @Override
    public LlmResponse generateWithMeta(String systemPrompt, String userPrompt) {
        try {
            String question = (systemPrompt == null || systemPrompt.isBlank())
                ? userPrompt
                : systemPrompt + "\n\n" + userPrompt;

            Map<String, Object> body = new LinkedHashMap<>();
            body.put("question", question);
            body.put("useRag", config.isUseRag());
            body.put("temperature", 0.0);
            if (config.getCollection() != null && !config.getCollection().isBlank()) {
                body.put("collection", config.getCollection().strip());
            }

            String requestBody = objectMapper.writeValueAsString(body);

            HttpRequest.Builder requestBuilder = HttpRequest.newBuilder()
                .uri(URI.create(resolveQueryUrl()))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(requestBody));
            LlmHttpSupport.withTimeout(requestBuilder, config);

            // SpectraLLM is API-key-less by default, but honour a bearer token if one is configured
            // (e.g. when the instance sits behind an authenticating reverse proxy).
            if (config.isApiKeyConfigured()) {
                requestBuilder.header("Authorization", "Bearer " + config.getApiKey());
            }

            HttpResponse<String> response =
                LlmHttpSupport.sendWithRetry(httpClient, requestBuilder.build(), "SpectraLLM");

            JsonNode root = objectMapper.readTree(response.body());
            JsonNode answer = root.path("answer");
            if (answer.isMissingNode() || answer.isNull()) {
                log.error("SpectraLLM response has no 'answer' field: {}", response.body());
                throw new RuntimeException("SpectraLLM response did not contain an answer");
            }
            return new LlmResponse(answer.asText(), parseSources(root.path("sources")));

        } catch (RuntimeException e) {
            // Already carries a precise message (transport, status, missing answer) — propagate as-is.
            log.error("Error calling SpectraLLM API: {}", e.getMessage());
            throw e;
        } catch (Exception e) {
            log.error("Error calling SpectraLLM API: {}", e.getMessage(), e);
            throw new RuntimeException("SpectraLLM call failed: " + e.getMessage(), e);
        }
    }

    /**
     * Maps SpectraLLM's {@code sources} array into {@link RagSource}, keeping the best available
     * relevance score (rerank &gt; BM25 &gt; similarity) and truncating long passages for display.
     */
    private List<RagSource> parseSources(JsonNode sources) {
        List<RagSource> result = new ArrayList<>();
        if (sources == null || !sources.isArray()) {
            return result;
        }
        for (JsonNode s : sources) {
            String text = s.path("text").asText("");
            if (text.length() > 500) {
                text = text.substring(0, 500) + "…";
            }
            String sourceFile = s.path("sourceFile").asText(null);
            Double score = null;
            if (s.hasNonNull("rerankScore")) {
                score = s.path("rerankScore").asDouble();
            } else if (s.hasNonNull("bm25Score")) {
                score = s.path("bm25Score").asDouble();
            } else if (s.hasNonNull("distance")) {
                score = s.path("distance").asDouble();
            }
            result.add(new RagSource(text, sourceFile, score));
        }
        return result;
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
