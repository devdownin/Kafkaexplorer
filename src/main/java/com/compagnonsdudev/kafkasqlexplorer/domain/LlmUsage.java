// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors
package com.compagnonsdudev.kafkasqlexplorer.domain;

/**
 * What one call to a model actually cost.
 *
 * <p>Nothing measured any of this before, which meant every tuning decision in the ingestion
 * pipeline — the prompt character budget, the per-topic message sample, the digest caps — rested on
 * reasoning rather than on a number. You could not say what a snapshot cost, nor whether a budget of
 * 120 000 characters was generous or stingy for the model actually configured.
 *
 * <p><strong>Token counts are boxed, and null means "not reported".</strong> SpectraLLM's query API
 * returns no token accounting at all, and an OpenAI-compatible gateway may omit the {@code usage}
 * object. Zero would say the call was free, which is the same class of lie this codebase spent an
 * audit removing elsewhere: a measurement that could not be taken must not read as a measurement
 * that came back empty. {@code durationMs} is always real — it is measured on our side.
 *
 * @param inputTokens  prompt tokens, or {@code null} when the provider does not report them
 * @param outputTokens generated tokens, or {@code null} when the provider does not report them
 * @param durationMs   wall-clock time of the call, always measured here
 * @param provider     the provider label the call went to
 * @param model        the model the call named
 */
public record LlmUsage(
        Long inputTokens,
        Long outputTokens,
        long durationMs,
        String provider,
        String model
) {
    /** Usage for a provider that reports no token counts: the duration is still worth having. */
    public static LlmUsage untokenized(long durationMs, String provider, String model) {
        return new LlmUsage(null, null, durationMs, provider, model);
    }

    /** Null when either half is unreported — a partial sum would be worse than no sum. */
    public Long totalTokens() {
        if (inputTokens == null || outputTokens == null) {
            return null;
        }
        return inputTokens + outputTokens;
    }

    /** One-line rendering for logs; says "?" where the provider said nothing. */
    public String summary() {
        return provider + '/' + model
            + " · in=" + (inputTokens == null ? "?" : inputTokens)
            + " out=" + (outputTokens == null ? "?" : outputTokens)
            + " · " + durationMs + "ms";
    }
}
