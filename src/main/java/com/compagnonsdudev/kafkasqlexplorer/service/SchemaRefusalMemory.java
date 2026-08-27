// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors
package com.compagnonsdudev.kafkasqlexplorer.service;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Which models this endpoint has refused a schema-constrained request for, so the next call does
 * not repeat the mistake.
 *
 * <p>One definition, because there are two clients now. It lived on
 * {@link OpenAiCompatibleLlmClient} while that was the only path that sent a schema; the Anthropic
 * one sends {@code output_config} and had no fallback at all, so a model — or a gateway, or an
 * account not enabled for the feature — that refuses it failed the analysis outright where the same
 * refusal on the other path costs one retry. Two copies of this rule is how one of them comes to
 * latch on a status the other does not.
 *
 * <p>Instance state rather than configuration: a client is rebuilt whenever the provider, base URL
 * or key changes, which is exactly the lifetime this observation is valid for.
 *
 * <p><strong>Keyed by model</strong>, not one flag per client. On a gateway that routes — OpenRouter
 * above all — schema support is a property of the model and of the upstream provider serving it,
 * not of the endpoint. One flag meant a model that cannot be constrained disabled constrained
 * decoding for every model chosen afterwards, silently and for the client's whole lifetime:
 * {@link LlmClientProvider} fingerprints provider, base URL and key, and the model is in none of
 * them, so changing the model in Settings reuses that very client.
 */
final class SchemaRefusalMemory {

    /** A guard against an unbounded field on a long-lived bean, not a policy. */
    private static final int MAX_REMEMBERED_MODELS = 64;

    private final Set<String> models = ConcurrentHashMap.newKeySet();

    /** The configured model, normalised so a null or blank one still keys the set. */
    static String modelKey(String model) {
        return model == null || model.isBlank() ? "" : model.strip();
    }

    /**
     * Whether a status can plausibly mean "I do not implement the schema field".
     *
     * <p>Only a rejected <em>request body</em> can: 400 and 422 are what an endpoint answers to a
     * field it does not understand. It used to fire on any 4xx, and the two that matter there are
     * 401 and 404 — a wrong key and a wrong model or path — which are configuration mistakes an
     * operator fixes within the minute, while the conclusion drawn from them outlived the fix.
     *
     * <p>Note what this decides and what it does not: it selects what is worth <em>retrying</em>
     * unconstrained. Whether the schema was actually the cause is settled by that retry — see
     * {@link #remember}.
     */
    static boolean looksLikeRefusal(int status) {
        return status == 400 || status == 422;
    }

    boolean refuses(String model) {
        return models.contains(modelKey(model));
    }

    /**
     * Records that this model cannot be constrained. Call it only once the unconstrained retry has
     * <em>succeeded</em>: a 400 says the body was not understood and not which field of it, and a
     * request that fails with and without the schema alike was refused over something else.
     */
    void remember(String model) {
        if (models.size() >= MAX_REMEMBERED_MODELS) {
            // Nothing here is worth an eviction policy: forget the lot and re-probe. Sixty-four
            // models on one client means the operator has been switching all afternoon, and one
            // extra request per model is cheaper than a field that grows without bound.
            models.clear();
        }
        models.add(modelKey(model));
    }
}
