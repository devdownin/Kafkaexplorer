// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors
package com.yourcompany.kafkasqlexplorer.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConfigurationProperties(prefix = "claude")
public class ClaudeConfig {

    public enum Provider { ANTHROPIC, OPENAI_COMPATIBLE }

    private Provider provider = Provider.ANTHROPIC;
    private String apiKey = "";
    private String baseUrl = "https://api.anthropic.com";
    private String model = "claude-3-5-sonnet-20241022";
    private int maxTokens = 4096;
    private int snapshotWindowSize = 100;
    private int snapshotWindowTimeoutSeconds = 30;

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
}
