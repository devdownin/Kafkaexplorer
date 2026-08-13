// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors
package com.compagnonsdudev.kafkasqlexplorer.domain;

import java.util.List;

/**
 * An LLM generation together with any grounding sources.
 *
 * <p>Most providers return only text; SpectraLLM (with RAG on) also returns the passages it
 * cited. This carrier lets callers access both without the base {@code generate} contract
 * having to change.
 *
 * @param text    the generated text
 * @param sources RAG citations, empty when the provider does not supply them
 */
public record LlmResponse(
        String text,
        List<RagSource> sources
) {
    public LlmResponse {
        sources = sources == null ? List.of() : List.copyOf(sources);
    }
}
