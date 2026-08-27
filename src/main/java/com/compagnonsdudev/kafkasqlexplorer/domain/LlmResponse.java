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
 * @param schemaSent whether a JSON Schema actually travelled with this request.
 *
 *                <p>Not "whether one was configured": {@code claude.structured-output} may be
 *                {@code AUTO} on a provider outside the known-good set, or the per-model latch may
 *                have learned that this model refuses one, or this may be the unconstrained retry
 *                that follows a refusal. Only the client that built the body knows, and it is the
 *                one fact needed to tell a constraint that held from one that silently did not —
 *                for OpenRouter that distinction has a name ({@code ACCEPTED_UNCONSTRAINED}, on
 *                the reasoning that "a guarantee that silently is not one is worse than an
 *                outright refusal") and everywhere else it was invisible.
 */
public record LlmResponse(
        String text,
        List<RagSource> sources,
        LlmUsage usage,
        boolean schemaSent
) {
    public LlmResponse {
        sources = sources == null ? List.of() : List.copyOf(sources);
    }

    /**
     * Three-arg form for a client that constrains nothing — SpectraLLM's query API has no notion
     * of a schema — and for the tests that predate the flag. Same idiom as {@code MetricConfig}'s
     * compat constructors: a record has no default values.
     */
    public LlmResponse(String text, List<RagSource> sources, LlmUsage usage) {
        this(text, sources, usage, false);
    }

    /** Kept for callers (and tests) that only care about the text and its citations. */
    public LlmResponse(String text, List<RagSource> sources) {
        this(text, sources, null, false);
    }

    /** Returns a copy carrying the given usage. */
    public LlmResponse withUsage(LlmUsage measured) {
        return new LlmResponse(text, sources, measured, schemaSent);
    }
}
