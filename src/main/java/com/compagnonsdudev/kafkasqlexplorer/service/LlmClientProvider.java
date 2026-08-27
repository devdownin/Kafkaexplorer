// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors
package com.compagnonsdudev.kafkasqlexplorer.service;

import com.compagnonsdudev.kafkasqlexplorer.config.ClaudeConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import jakarta.annotation.PreDestroy;
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
 * reallocate per request. The one it replaces is <b>closed</b> — a selector thread and a pool
 * apiece, leaked on every save that moved the endpoint until somebody scripted that endpoint and
 * found out.
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
                LlmClient outgoing = client;
                client = LlmClientFactory.create(config);
                fingerprint = current;
                // After the replacement is published, never before: the retired client may still
                // be finishing a call, and a reader that raced this block must find the new one
                // rather than one being shut down.
                closeQuietly(outgoing);
            }
            return client;
        }
    }

    /**
     * Releases the client on shutdown too — the last one built is otherwise the one that leaks.
     *
     * <p>Not through {@code ShutdownBudget}: that exists to share one deadline across the six
     * executor pools whose private waits used to add up, and this holds no pool of its own and
     * waits for nothing.
     */
    @PreDestroy
    public synchronized void shutdown() {
        closeQuietly(client);
        client = null;
        fingerprint = null;
    }

    /**
     * A client being retired must never be able to fail the save that retired it, nor the
     * application's shutdown. Whatever it says on the way out is a debug line and nothing more.
     */
    private static void closeQuietly(LlmClient outgoing) {
        if (outgoing == null) return;
        try {
            outgoing.close();
        } catch (Exception e) {
            log.debug("Retired LLM client did not close cleanly: {}", e.toString());
        }
    }

    /**
     * Test seam, in the shape {@code KafkaAdminService.setAdminClientForTest} already uses: seats
     * a client as though it had been built for {@code builtFor}, so a later change to that config
     * exercises the replacement path with something whose {@code close()} can be observed.
     */
    void setClientForTest(LlmClient seated, ClaudeConfig builtFor) {
        synchronized (this) {
            this.client = seated;
            this.fingerprint = fingerprintOf(builtFor);
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
            + '|' + Integer.toHexString(apiKey.hashCode())
            // The request timeout is split across the two categories this class exists to
            // distinguish: it is read per call (`withTimeout`) *and* baked into the client as the
            // connect timeout (`newClient`). Raising it in Settings therefore moved one half and
            // left the other at the old value until the client happened to be rebuilt for an
            // unrelated reason — the very "what the client carries versus what it reads per call"
            // defect this fingerprint was written to fix, in the one field added to it afterwards.
            + '|' + config.getRequestTimeoutSeconds();
    }
}
