// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors
package com.compagnonsdudev.kafkasqlexplorer.service;

import com.compagnonsdudev.kafkasqlexplorer.domain.LlmResponse;

import java.util.List;

public interface LlmClient {
    String generate(String systemPrompt, String userPrompt);

    /**
     * Like {@link #generate} but also returns any grounding sources (RAG citations).
     * The default carries no sources; providers that supply them (SpectraLLM) override this.
     */
    default LlmResponse generateWithMeta(String systemPrompt, String userPrompt) {
        return new LlmResponse(generate(systemPrompt, userPrompt), List.of());
    }
}
