// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors
package com.yourcompany.kafkasqlexplorer.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConfigurationProperties(prefix = "claude")
public class ClaudeConfig {

    public enum Provider { ANTHROPIC, OPENAI_COMPATIBLE, OLLAMA, SPECTRA }

    private Provider provider = Provider.OLLAMA;
    private String apiKey = "";
    private String baseUrl = "";
    private String model = "qwen3:4b";
    private int maxTokens = 4096;
    private int snapshotWindowSize = 100;
    private int snapshotWindowTimeoutSeconds = 30;
    /**
     * Per-call timeout for HTTP-based LLM providers (OpenAI-compatible, Ollama, SpectraLLM),
     * applied as both connect and request timeout. Local audit models can be slow, so this
     * defaults high; lower it to fail fast against a hosted endpoint.
     */
    private int requestTimeoutSeconds = 60;
    /**
     * Only used by the {@link Provider#SPECTRA} provider: when {@code true} the audit
     * question is answered with SpectraLLM's hybrid RAG retrieval over its ingested
     * corpus. Defaults to {@code false} because the Kafka audit prompt already carries
     * all the message context inline, so plain LLM generation is what we want.
     */
    private boolean useRag = false;

    public Provider getProvider() {
        return provider;
    }

    public void setProvider(Provider provider) {
        this.provider = provider;
    }

    public String getApiKey() {
        return apiKey;
    }

    public void setApiKey(String apiKey) {
        this.apiKey = apiKey;
    }

    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    public String getResolvedBaseUrl() {
        if (baseUrl != null && !baseUrl.isBlank()) {
            return baseUrl;
        }
        return defaultBaseUrl(provider);
    }

    public String getModel() {
        return model;
    }

    public void setModel(String model) {
        this.model = model;
    }

    public int getMaxTokens() {
        return maxTokens;
    }

    public void setMaxTokens(int maxTokens) {
        this.maxTokens = maxTokens;
    }

    public int getSnapshotWindowSize() {
        return snapshotWindowSize;
    }

    public void setSnapshotWindowSize(int snapshotWindowSize) {
        this.snapshotWindowSize = snapshotWindowSize;
    }

    public int getSnapshotWindowTimeoutSeconds() {
        return snapshotWindowTimeoutSeconds;
    }

    public void setSnapshotWindowTimeoutSeconds(int snapshotWindowTimeoutSeconds) {
        this.snapshotWindowTimeoutSeconds = snapshotWindowTimeoutSeconds;
    }

    public boolean isUseRag() {
        return useRag;
    }

    public void setUseRag(boolean useRag) {
        this.useRag = useRag;
    }

    public int getRequestTimeoutSeconds() {
        return requestTimeoutSeconds;
    }

    public void setRequestTimeoutSeconds(int requestTimeoutSeconds) {
        this.requestTimeoutSeconds = requestTimeoutSeconds;
    }

    public boolean isApiKeyRequired() {
        return provider == Provider.ANTHROPIC;
    }

    public boolean isApiKeyConfigured() {
        return apiKey != null && !apiKey.isBlank();
    }

    public boolean isLocalDeployment() {
        return provider == Provider.OLLAMA || isLoopbackUrl(getResolvedBaseUrl());
    }

    public String getProviderLabel() {
        return switch (provider) {
            case ANTHROPIC -> "Anthropic";
            case OPENAI_COMPATIBLE -> "OpenAI-compatible";
            case OLLAMA -> "Ollama";
            case SPECTRA -> "SpectraLLM";
        };
    }

    public static String defaultBaseUrl(Provider provider) {
        return switch (provider) {
            case ANTHROPIC -> "https://api.anthropic.com";
            case OPENAI_COMPATIBLE -> "";
            case OLLAMA -> "http://localhost:11434/v1";
            case SPECTRA -> "http://localhost:8080";
        };
    }

    private boolean isLoopbackUrl(String value) {
        if (value == null || value.isBlank()) {
            return false;
        }
        String normalized = value.toLowerCase();
        return normalized.contains("localhost")
            || normalized.contains("127.0.0.1")
            || normalized.contains("0.0.0.0");
    }
}
