// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors
package com.compagnonsdudev.kafkasqlexplorer.service;

import com.compagnonsdudev.kafkasqlexplorer.domain.LlmResponse;

import java.util.List;

public interface LlmClient extends AutoCloseable {
    String generate(String systemPrompt, String userPrompt);

    /**
     * Like {@link #generate} but also returns any grounding sources (RAG citations) and what the
     * call cost. The default carries neither; providers that supply them override this.
     */
    default LlmResponse generateWithMeta(String systemPrompt, String userPrompt) {
        return new LlmResponse(generate(systemPrompt, userPrompt), List.of());
    }

    /**
     * Same, asking the provider to constrain the answer to {@code schema}.
     *
     * <p>The default ignores the schema, which is the honest behaviour for a provider that cannot
     * constrain its output (SpectraLLM's query API has no such notion): the caller's prompt still
     * asks for JSON and the caller still parses defensively. A client only overrides this when the
     * constraint is actually enforced end to end.
     */
    default LlmResponse generateWithMeta(String systemPrompt, String userPrompt,
                                         LlmOutputSchema schema) {
        return generateWithMeta(systemPrompt, userPrompt);
    }

    /**
     * Releases whatever this client holds open — a connection pool, a selector thread.
     *
     * <p>{@link LlmClientProvider} replaces the client whenever the deployment is repointed, and
     * dropped one on every such save: two of the three hold a {@link java.net.http.HttpClient},
     * which keeps a selector thread and a pool alive until it is collected, and the Anthropic SDK
     * wraps an OkHttp client with a dispatcher of its own.
     *
     * <p>Narrowed from {@link AutoCloseable} to throw nothing: a client being retired must not be
     * able to fail the settings save that retired it. The default does nothing, which is the right
     * behaviour for an implementation that holds no resource.
     */
    @Override
    default void close() {
    }
}
