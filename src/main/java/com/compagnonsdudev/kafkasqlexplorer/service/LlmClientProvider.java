// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors
package com.compagnonsdudev.kafkasqlexplorer.service;

import com.compagnonsdudev.kafkasqlexplorer.config.ClaudeConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Objects;

/**
 * Hands out the {@link LlmClient} matching the <em>current</em> {@link ClaudeConfig}.
 *
 * <p>{@code POST /api/config} repoints the LLM at runtime — provider, API key, base URL — and the
 * services that call the model used to resolve their client once, in their constructor. Everything
 * read per call (base URL, model) followed a change, but the two things baked into the client did
 * not: the {@link ClaudeConfig.Provider} chosen by {@link LlmClientFactory} and the API key the
 * Anthropic SDK client is built with. So switching Ollama → Anthropic in Settings left every
 * analysis on Ollama, and {@code POST /api/config/test-llm} — which has always built a fresh client
 * — reported the new provider reachable. A settings page that confirms a change the engine ignores
 * is worse than one that refuses it.
 *
 * <p>The client is rebuilt only when the fingerprint of what is baked into it changes, so the
 * common path is a volatile read: an HTTP client (and its connection pool) is not something to
 * reallocate per request.
 */
@Component
public class LlmClientProvider {

    private static final Logger log = LoggerFactory.getLogger(LlmClientProvider.class);

    private final ClaudeConfig config;

    private volatile String fingerprint;
    private volatile LlmClient client;

    public LlmClientProvider(ClaudeConfig config) {
        this.config = config;
    }

    /** The client for the configuration as it stands now, rebuilt if that configuration moved. */
    public LlmClient get() {
        String current = fingerprintOf(config);
        LlmClient existing = client;
        if (existing != null && Objects.equals(current, fingerprint)) {
            return existing;
        }
        synchronized (this) {
            if (client == null || !Objects.equals(current, fingerprint)) {
                log.info("LLM client (re)built for provider {} at {}",
                    config.getProviderLabel(), config.getResolvedBaseUrl());
                client = LlmClientFactory.create(config);
                fingerprint = current;
            }
            return client;
        }
    }

    /**
     * What the client itself carries, as opposed to what it reads from the config on every call.
     * The API key is hashed rather than stored: this string ends up in a field of a long-lived bean.
     */
    private static String fingerprintOf(ClaudeConfig config) {
        String apiKey = config.getApiKey() == null ? "" : config.getApiKey();
        return config.getProvider().name()
            + '|' + config.getResolvedBaseUrl()
            + '|' + Integer.toHexString(apiKey.hashCode());
    }
}
