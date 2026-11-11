// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors
package com.compagnonsdudev.kafkasqlexplorer.service;

import com.compagnonsdudev.kafkasqlexplorer.config.ClaudeConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Objects;
import java.util.function.Function;

/**
 * One client per configuration fingerprint, rebuilt when what it carries moves and the retired one
 * closed — the rule {@link LlmClientProvider} wrote for {@code claude.*}, now shared with
 * {@link AssistantLlm}, which had it written a second time.
 *
 * <p>What goes in the fingerprint is the caller's: a client built on the shared {@code claude.*}
 * bean reads the model per call, so the model is not in it; one built on a copy holds a snapshot,
 * so it is. Everything else — the volatile fast path, publishing the replacement before closing the
 * retired client, never letting a close fail the caller — is one implementation.
 */
final class LlmClientCache {

    private static final Logger log = LoggerFactory.getLogger(LlmClientCache.class);

    private final String consumer;
    private final Function<ClaudeConfig, LlmClient> factory;
    private final Function<ClaudeConfig, String> fingerprintOf;
    private volatile String fingerprint;
    private volatile LlmClient client;

    LlmClientCache(String consumer, Function<ClaudeConfig, LlmClient> factory,
                   Function<ClaudeConfig, String> fingerprintOf) {
        this.consumer = consumer;
        this.factory = factory;
        this.fingerprintOf = fingerprintOf;
    }

    /** What every client carries: provider, address, key (hashed — this lives in a field) and timeout. */
    static String endpointFingerprint(ClaudeConfig config) {
        String apiKey = config.getApiKey() == null ? "" : config.getApiKey();
        return config.getProvider().name()
            + '|' + config.getResolvedBaseUrl()
            + '|' + Integer.toHexString(apiKey.hashCode())
            // Read per call *and* baked in as the connect timeout: see LlmClientProvider.
            + '|' + config.getRequestTimeoutSeconds();
    }

    LlmClient get(ClaudeConfig config) {
        String current = fingerprintOf.apply(config);
        LlmClient existing = client;
        if (existing != null && Objects.equals(current, fingerprint)) return existing;
        synchronized (this) {
            if (client == null || !Objects.equals(current, fingerprint)) {
                log.info("LLM client (re)built for {} — {} at {}", consumer,
                    config.getProviderLabel(), config.getResolvedBaseUrl());
                LlmClient outgoing = client;
                client = factory.apply(config);
                fingerprint = current;
                // After the replacement is published, never before: the retired client may still
                // be finishing a call, and a reader that raced this block must find the new one.
                closeQuietly(outgoing);
            }
            return client;
        }
    }

    synchronized void seat(LlmClient seated, ClaudeConfig builtFor) {
        client = seated;
        fingerprint = fingerprintOf.apply(builtFor);
    }

    synchronized void close() {
        closeQuietly(client);
        client = null;
        fingerprint = null;
    }

    /** A client being retired must never fail the save that retired it, nor the shutdown. */
    private static void closeQuietly(LlmClient outgoing) {
        if (outgoing == null) return;
        try {
            outgoing.close();
        } catch (Exception e) {
            log.debug("Retired LLM client did not close cleanly: {}", e.toString());
        }
    }
}
