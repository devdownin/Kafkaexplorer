// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors
package com.compagnonsdudev.kafkasqlexplorer.domain;

import java.util.List;

/**
 * An LLM generation together with any grounding sources and what the call cost.
 *
 * <p>Most providers return only text; SpectraLLM (with RAG on) also returns the passages it
 * cited. This carrier lets callers access all of it without the base {@code generate} contract
 * having to change.
 *
 * @param text    the generated text
 * @param sources RAG citations, empty when the provider does not supply them
 * @param usage   what the call cost, or {@code null} when the client did not record it
 */
public record LlmResponse(
        String text,
        List<RagSource> sources,
        LlmUsage usage
) {
    public LlmResponse {
        sources = sources == null ? List.of() : List.copyOf(sources);
    }

    /** Kept for callers (and tests) that only care about the text and its citations. */
    public LlmResponse(String text, List<RagSource> sources) {
        this(text, sources, null);
    }

    /** Returns a copy carrying the given usage. */
    public LlmResponse withUsage(LlmUsage measured) {
        return new LlmResponse(text, sources, measured);
    }
}
