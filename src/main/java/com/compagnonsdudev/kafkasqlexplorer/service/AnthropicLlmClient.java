// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors
package com.compagnonsdudev.kafkasqlexplorer.service;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.MessageParam;
import com.anthropic.models.messages.TextDelta;
import com.compagnonsdudev.kafkasqlexplorer.config.ClaudeConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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
        try {

            MessageCreateParams params = MessageCreateParams.builder()
                .model(config.getModel())
                .maxTokens((long) config.getMaxTokens())
                .system(systemPrompt)
                .addMessage(MessageParam.builder()
                    .role(MessageParam.Role.USER)
                    .content(userPrompt)
                    .build())
                .build();

            try (var stream = client.messages().createStreaming(params)) {
                return stream.stream()
                    .flatMap(e -> e.contentBlockDelta().stream())
                    .flatMap(d -> d.delta().text().stream())
                    .map(TextDelta::text)
                    .collect(Collectors.joining());
            }
        } catch (Exception e) {
            log.error("Error calling Anthropic API: {}", e.getMessage(), e);
            // Keep the real cause in the message: callers surface e.getMessage() to the UI,
            // and a bare "LLM call failed" hides timeouts, auth and model errors.
            throw new RuntimeException("Anthropic API call failed: "
                + (e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName()), e);
        }
    }
}
