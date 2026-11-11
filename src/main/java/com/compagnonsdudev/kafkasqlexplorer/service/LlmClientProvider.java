// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors
package com.compagnonsdudev.kafkasqlexplorer.service;

import com.compagnonsdudev.kafkasqlexplorer.config.ClaudeConfig;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

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
 * reallocate per request. The one it replaces is <b>closed</b> — a selector thread and a pool
 * apiece, leaked on every save that moved the endpoint until somebody scripted that endpoint and
 * found out.
 */
@Component
public class LlmClientProvider {

    /** The consumer label on {@code explorer_llm_requests_total} for every call made through here. */
    static final String CONSUMER = "process_mining";

    private final ClaudeConfig config;
    /**
     * The rebuild rule lives in {@link LlmClientCache}, shared with the assistant's model. Here the
     * fingerprint is the endpoint's alone: the client reads the model per call from the shared
     * {@code claude.*} bean, so a model change needs no new client.
     */
    private final LlmClientCache cache;

    /** Unmetered — for tests and the places that build a provider by hand. */
    public LlmClientProvider(ClaudeConfig config) {
        this(config, null);
    }

    @Autowired
    public LlmClientProvider(ClaudeConfig config, MeterRegistry meterRegistry) {
        this.config = config;
        this.cache = new LlmClientCache(CONSUMER,
            meterRegistry == null
                ? LlmClientFactory::create
                : c -> new MeteredLlmClient(LlmClientFactory.create(c), CONSUMER, meterRegistry),
            LlmClientCache::endpointFingerprint);
    }

    /** The client for the configuration as it stands now, rebuilt if that configuration moved. */
    public LlmClient get() {
        return cache.get(config);
    }

    /**
     * Releases the client on shutdown too — the last one built is otherwise the one that leaks.
     *
     * <p>Not through {@code ShutdownBudget}: that exists to share one deadline across the six
     * executor pools whose private waits used to add up, and this holds no pool of its own and
     * waits for nothing.
     */
    @PreDestroy
    public void shutdown() {
        cache.close();
    }

    /**
     * Test seam, in the shape {@code KafkaAdminService.setAdminClientForTest} already uses: seats
     * a client as though it had been built for {@code builtFor}, so a later change to that config
     * exercises the replacement path with something whose {@code close()} can be observed.
     */
    void setClientForTest(LlmClient seated, ClaudeConfig builtFor) {
        cache.seat(seated, builtFor);
    }
}
