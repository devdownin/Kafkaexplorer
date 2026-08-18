// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors
package com.compagnonsdudev.kafkasqlexplorer.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.compagnonsdudev.kafkasqlexplorer.config.ClaudeConfig;
import com.compagnonsdudev.kafkasqlexplorer.domain.LlmResponse;
import com.compagnonsdudev.kafkasqlexplorer.domain.LlmUsage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class OpenAiCompatibleLlmClient implements LlmClient {
    private static final Logger log = LoggerFactory.getLogger(OpenAiCompatibleLlmClient.class);
    private final ClaudeConfig config;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * Set once an endpoint has refused a schema-constrained request, so the next call does not
     * repeat the mistake. Instance state rather than config: the client is rebuilt whenever the
     * provider, base URL or key changes, which is exactly the lifetime this observation is valid
     * for.
     */
    private volatile boolean structuredOutputUnsupported;

    public OpenAiCompatibleLlmClient(ClaudeConfig config) {
        this.config = config;
        this.httpClient = LlmHttpSupport.newClient(config);
    }

    @Override
    public String generate(String systemPrompt, String userPrompt) {
        return generateWithMeta(systemPrompt, userPrompt, null).text();
    }

    @Override
    public LlmResponse generateWithMeta(String systemPrompt, String userPrompt) {
        return generateWithMeta(systemPrompt, userPrompt, null);
    }

    @Override
    public LlmResponse generateWithMeta(String systemPrompt, String userPrompt,
                                        LlmOutputSchema schema) {
        boolean constrain = schema != null
            && config.isStructuredOutputEnabled()
            && !structuredOutputUnsupported;

        try {
            return call(systemPrompt, userPrompt, constrain ? schema : null);
        } catch (LlmHttpSupport.ClientErrorException e) {
            if (!constrain || !looksLikeSchemaRefusal(e.status())) {
                throw e;
            }
            // The endpoint rejected the request and a schema was the only unusual thing in it.
            // Retrying unconstrained is worth one attempt: the alternative is telling an operator
            // their gateway is broken when it merely does not implement response_format. If the
            // second call fails too, that error is the honest one to report.
            log.warn("{} refused a schema-constrained request (status {}); retrying without the "
                    + "constraint and not sending one again for this endpoint. Set "
                    + "claude.structured-output=OFF to skip this probe.",
                config.getProviderLabel(), e.status());
            structuredOutputUnsupported = true;
            return call(systemPrompt, userPrompt, null);
        }
    }

    /**
     * Whether a 4xx can plausibly mean "I do not implement {@code response_format}".
     *
     * <p>Only a rejected <em>request body</em> can: 400 and 422 are what a gateway answers to a field
     * it does not understand. The latch used to fire on any 4xx, and the two that matter are 401 and
     * 404 — a wrong key and a wrong model or path. Those disable structured output permanently for
     * the client's lifetime, and that lifetime is longer than it looks: {@link LlmClientProvider}
     * fingerprints provider, base URL and key, so correcting a mistyped <em>model</em> in Settings
     * reuses the same client. The deployment then runs unconstrained for ever, silently, because of
     * a typo that was fixed minutes later — the failure mode a fallback exists to prevent, arrived at
     * through the fallback itself.
     */
    private static boolean looksLikeSchemaRefusal(int status) {
        return status == 400 || status == 422;
    }

    private LlmResponse call(String systemPrompt, String userPrompt, LlmOutputSchema schema) {
        long startedAt = System.currentTimeMillis();
        try {
            String url = resolveChatCompletionsUrl();

            Map<String, Object> body = new LinkedHashMap<>();
            body.put("model", config.getModel());
            body.put("messages", List.of(
                Map.of("role", "system", "content", systemPrompt),
                Map.of("role", "user", "content", userPrompt)
            ));
            body.put("max_tokens", config.getMaxTokens());
            body.put("temperature", 0.0);
            body.put("stream", false);
            if (schema != null) {
                body.put("response_format", Map.of(
                    "type", "json_schema",
                    "json_schema", Map.of(
                        "name", schema.name(),
                        "strict", true,
                        "schema", schema.schema())));
            }

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
            String text = firstChoiceContent(root, response.body());
            LlmUsage usage = usageOf(root, System.currentTimeMillis() - startedAt);
            log.debug("{} call complete — {}", config.getProviderLabel(), usage.summary());
            return new LlmResponse(text, List.of(), usage);

        } catch (RuntimeException e) {
            log.error("Error calling OpenAI-compatible API: {}", e.getMessage());
            throw e;
        } catch (Exception e) {
            log.error("Error calling OpenAI-compatible API: {}", e.getMessage(), e);
            throw new RuntimeException("LLM call failed: " + e.getMessage(), e);
        }
    }

    /**
     * Reads the {@code usage} object. Both OpenAI and Ollama's compatibility layer report
     * {@code prompt_tokens} / {@code completion_tokens}, but a leaner gateway may omit the object
     * entirely — in which case the counts stay null rather than becoming a zero that would read as
     * "this call was free".
     */
    private LlmUsage usageOf(JsonNode root, long durationMs) {
        JsonNode usage = root.path("usage");
        return new LlmUsage(
            longOrNull(usage, "prompt_tokens"),
            longOrNull(usage, "completion_tokens"),
            durationMs,
            config.getProviderLabel(),
            config.getModel());
    }

    private static Long longOrNull(JsonNode parent, String field) {
        JsonNode value = parent.path(field);
        return value.isNumber() ? value.asLong() : null;
    }

    private String resolveChatCompletionsUrl() {
        String baseUrl = config.getResolvedBaseUrl();
        if (baseUrl.endsWith("/v1")) {
            return baseUrl + "/chat/completions";
        }
        if (baseUrl.endsWith("/v1/")) {
            return baseUrl + "chat/completions";
        }
        return baseUrl + "/v1/chat/completions";
    }

    /**
     * Reads {@code choices[0].message.content}, refusing an answerless body rather than crashing on
     * it. {@code path("choices").get(0)} returns {@code null} when the array is absent or empty —
     * which is precisely what a 200-with-an-error body looks like on Ollama and on several
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
