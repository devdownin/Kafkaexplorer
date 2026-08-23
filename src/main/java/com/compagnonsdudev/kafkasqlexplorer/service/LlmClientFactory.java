// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors
package com.compagnonsdudev.kafkasqlexplorer.service;

import com.compagnonsdudev.kafkasqlexplorer.config.ClaudeConfig;

/**
 * Resolves the {@link LlmClient} implementation matching the configured
 * {@link ClaudeConfig.Provider}. Centralised here so every consumer (audit, profiling…)
 * selects the backend the same way.
 */
public final class LlmClientFactory {

    private LlmClientFactory() {
    }

    public static LlmClient create(ClaudeConfig config) {
        return switch (config.getProvider()) {
            case ANTHROPIC -> new AnthropicLlmClient(config);
            case SPECTRA -> new SpectraLlmClient(config);
            // OpenRouter speaks the OpenAI /chat/completions API verbatim; what is specific to it
            // (its base URL, its required key, its attribution headers) is carried by the config
            // and by that client, not by a class of its own.
            case OPENAI_COMPATIBLE, OLLAMA, OPENROUTER -> new OpenAiCompatibleLlmClient(config);
        };
    }
}
