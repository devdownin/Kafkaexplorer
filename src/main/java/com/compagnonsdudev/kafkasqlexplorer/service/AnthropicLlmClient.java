// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors
package com.compagnonsdudev.kafkasqlexplorer.service;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.core.JsonValue;
import com.anthropic.models.messages.JsonOutputFormat;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.MessageParam;
import com.anthropic.models.messages.OutputConfig;
import com.anthropic.models.messages.TextDelta;
import com.compagnonsdudev.kafkasqlexplorer.config.ClaudeConfig;
import com.compagnonsdudev.kafkasqlexplorer.domain.LlmResponse;
import com.compagnonsdudev.kafkasqlexplorer.domain.LlmUsage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

public class AnthropicLlmClient implements LlmClient {
    private static final Logger log = LoggerFactory.getLogger(AnthropicLlmClient.class);
    private final ClaudeConfig config;
    private final AnthropicClient client;

    public AnthropicLlmClient(ClaudeConfig config) {
        this.config = config;
        var builder = AnthropicOkHttpClient.builder()
            .apiKey(config.getApiKey());

        if (config.getResolvedBaseUrl() != null && !config.getResolvedBaseUrl().isBlank()) {
            builder.baseUrl(config.getResolvedBaseUrl());
        }

        this.client = builder.build();
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
        long startedAt = System.currentTimeMillis();
        try {
            MessageCreateParams.Builder params = MessageCreateParams.builder()
                .model(config.getModel())
                .maxTokens((long) config.getMaxTokens())
                .system(systemPrompt)
                .addMessage(MessageParam.builder()
                    .role(MessageParam.Role.USER)
                    .content(userPrompt)
                    .build());

            if (schema != null && config.isStructuredOutputEnabled()) {
                params.outputConfig(OutputConfig.builder()
                    .format(JsonOutputFormat.builder()
                        .schema(toSchema(schema.schema()))
                        .build())
                    .build());
            }

            // Usage arrives split across the stream: input tokens on message_start, output tokens
            // on message_delta. Collecting both here is what makes the cost of an analysis a
            // measured number rather than a guess.
            AtomicReference<Long> inputTokens = new AtomicReference<>();
            AtomicLong outputTokens = new AtomicLong(-1);

            String text;
            try (var stream = client.messages().createStreaming(params.build())) {
                text = stream.stream()
                    .peek(event -> {
                        event.messageStart().ifPresent(start ->
                            inputTokens.set(start.message().usage().inputTokens()));
                        event.messageDelta().ifPresent(delta ->
                            outputTokens.set(delta.usage().outputTokens()));
                    })
                    .flatMap(e -> e.contentBlockDelta().stream())
                    .flatMap(d -> d.delta().text().stream())
                    .map(TextDelta::text)
                    .collect(Collectors.joining());
            }

            LlmUsage usage = new LlmUsage(
                inputTokens.get(),
                outputTokens.get() < 0 ? null : outputTokens.get(),
                // The Anthropic API prices nothing in its response, and this application keeps no
                // price table — a cost shown here is one a provider stood behind, or none.
                null,
                // Cache accounting is not read on this path: nothing here sets a cache breakpoint,
                // so a figure would only ever be zero, and a zero nobody can act on is noise.
                null,
                // Extended thinking is not requested here either, so there is no deliberation to
                // account for. Null rather than 0: nobody counted, which is not the same as none.
                null,
                System.currentTimeMillis() - startedAt,
                config.getProviderLabel(),
                config.getModel());
            log.debug("Anthropic call complete — {}", usage.summary());

            return new LlmResponse(text, List.of(), usage);
        } catch (Exception e) {
            log.error("Error calling Anthropic API: {}", e.getMessage(), e);
            // Keep the real cause in the message: callers surface it to the UI, and a bare
            // "LLM call failed" hides timeouts, auth and model errors.
            throw new RuntimeException("Anthropic API call failed: "
                + (e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName()), e);
        }
    }

    /** The SDK's schema object is a freeform property bag, so the map goes in key by key. */
    private static JsonOutputFormat.Schema toSchema(Map<String, Object> schema) {
        JsonOutputFormat.Schema.Builder builder = JsonOutputFormat.Schema.builder();
        schema.forEach((key, value) -> builder.putAdditionalProperty(key, JsonValue.from(value)));
        return builder.build();
    }
}
