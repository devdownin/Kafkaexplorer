// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors
package com.compagnonsdudev.kafkasqlexplorer.service;

import com.compagnonsdudev.kafkasqlexplorer.config.AssistantProperties;
import com.compagnonsdudev.kafkasqlexplorer.config.ClaudeConfig;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.Objects;
import java.util.function.Function;

/**
 * The model the assistant talks to: {@code claude.*}, with {@code explorer.assistant.*}'s
 * overrides applied on a copy.
 *
 * <p>Why not {@link LlmClientProvider}: that one is wired to the shared {@link ClaudeConfig} and
 * every client it builds reads the model from it per call, so a second model is a second client
 * holding a second configuration. The copy is re-derived on every call, which is what keeps a
 * Settings change on {@code claude.*} effective here too, and the client is rebuilt only when what
 * it carries moved — the same fingerprint rule, with the model in it, since this client's
 * configuration is a snapshot.
 *
 * <p><b>The API key does not follow the endpoint.</b> When an override sends the assistant
 * somewhere other than {@code claude.*}'s address, the key configured for that address is dropped
 * rather than sent along: {@code ClaudeConfig.probeCopy} records what that costs — pointing a copy
 * at another host exfiltrated the operator's key in one call. An override is operator
 * configuration, not a request field, but the same rule costs nothing to keep: a hosted override
 * that needs a key then says so through {@code configurationProblem()}.
 */
@Component
@EnableConfigurationProperties(AssistantProperties.class)
public class AssistantLlm {

    private final ClaudeConfig claudeConfig;
    private final AssistantProperties properties;

    /**
     * Shared rebuild rule; the model is in this fingerprint because, unlike {@code claude.*}'s
     * client, this one holds a copy of the configuration rather than reading the shared bean.
     */
    private final LlmClientCache cache;

    /** The consumer label on {@code explorer_llm_requests_total} for every call made through here. */
    static final String CONSUMER = "assistant";

    /** Unmetered — for tests and the eval. */
    public AssistantLlm(ClaudeConfig claudeConfig, AssistantProperties properties) {
        this(claudeConfig, properties, LlmClientFactory::create);
    }

    @Autowired
    public AssistantLlm(ClaudeConfig claudeConfig, AssistantProperties properties, MeterRegistry meterRegistry) {
        this(claudeConfig, properties,
            c -> new MeteredLlmClient(LlmClientFactory.create(c), CONSUMER, meterRegistry));
    }

    AssistantLlm(ClaudeConfig claudeConfig, AssistantProperties properties,
                 Function<ClaudeConfig, LlmClient> factory) {
        this.claudeConfig = claudeConfig;
        this.properties = properties;
        this.cache = new LlmClientCache(CONSUMER, factory,
            c -> LlmClientCache.endpointFingerprint(c) + '|' + c.getModel());
    }

    /**
     * The configuration the assistant's calls use, derived now.
     *
     * @throws IllegalArgumentException when {@code explorer.assistant.provider} names no provider
     */
    public ClaudeConfig effectiveConfig() {
        ClaudeConfig copy = claudeConfig.probeCopy(properties.getModel());
        if (!properties.getProvider().isBlank()) {
            String name = properties.getProvider().toUpperCase(Locale.ROOT).replace('-', '_');
            try {
                copy.setProvider(ClaudeConfig.Provider.valueOf(name));
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("explorer.assistant.provider '"
                    + properties.getProvider() + "' is not a provider — use one of ANTHROPIC, "
                    + "OPENAI_COMPATIBLE, LLAMA_CPP, OPENROUTER.", e);
            }
            // A provider switch with no address of its own gets that provider's default address,
            // not the inherited one, which belongs to another dialect.
            if (properties.getBaseUrl().isBlank()) copy.setBaseUrl("");
        }
        if (!properties.getBaseUrl().isBlank()) copy.setBaseUrl(properties.getBaseUrl());
        if (!Objects.equals(copy.getResolvedBaseUrl(), claudeConfig.getResolvedBaseUrl())) {
            copy.setApiKey("");
            // The declared window is the claude.* endpoint's; another server has its own.
            copy.setContextWindowTokens(0);
        }
        if (properties.getRequestTimeoutSeconds() > 0) {
            copy.setRequestTimeoutSeconds(properties.getRequestTimeoutSeconds());
        }
        return copy;
    }

    /** Why the assistant cannot answer now, or {@code null} when it can. */
    public String problem() {
        if (!properties.isEnabled()) {
            return "The assistant is off — set explorer.assistant.enabled=true "
                + "(EXPLORER_ASSISTANT_ENABLED) to offer it.";
        }
        ClaudeConfig effective;
        try {
            effective = effectiveConfig();
        } catch (IllegalArgumentException e) {
            return e.getMessage();
        }
        if (effective.getProvider() == ClaudeConfig.Provider.SPECTRA) {
            // SpectraLLM's query API constrains nothing and answers about its own corpus; it is
            // the wrong tool for writing a statement against this catalogue.
            return "SpectraLLM cannot serve the assistant — set explorer.assistant.provider to "
                + "LLAMA_CPP, OPENAI_COMPATIBLE, OPENROUTER or ANTHROPIC.";
        }
        return effective.configurationProblem();
    }

    /** The client for the configuration as it stands now, rebuilt if what it carries moved. */
    public LlmClient client(ClaudeConfig effective) {
        return cache.get(effective);
    }

    @PreDestroy
    public void shutdown() {
        cache.close();
    }
}
