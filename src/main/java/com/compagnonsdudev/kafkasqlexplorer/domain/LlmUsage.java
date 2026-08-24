// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors
package com.compagnonsdudev.kafkasqlexplorer.domain;

import com.compagnonsdudev.kafkasqlexplorer.util.LogSafe;

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
 * @param costUsd      what the provider says the call actually cost, in USD, or {@code null} when
 *                     it does not say. Same rule as the token counts, and it matters more here:
 *                     the default provider bills per token, so a zero would read as a free call
 *                     rather than as an unpriced one. It is <em>reported</em>, never derived — no
 *                     price table is kept in this application, so a figure shown is one the
 *                     provider stood behind. OpenRouter returns it on every response; the OpenAI
 *                     API, Ollama and SpectraLLM do not, and there it stays null. Note that
 *                     {@code 0.0} is a real measurement (a free model), not an absent one.
 * @param cachedInputTokens how many of the prompt tokens were served from the provider's prompt
 *                     cache, or {@code null} when the provider does not report it. A cache read
 *                     costs a fraction of a fresh input token, so this is the measurement that says
 *                     whether caching is doing anything — and it is deliberately a measurement
 *                     rather than a promise: nothing here claims a saving, it reports what the
 *                     provider counted. {@code 0} means the prompt missed the cache, which is a
 *                     finding in itself.
 * @param reasoningTokens how many of the generated tokens the model spent deliberating before
 *                     answering, or {@code null} when the provider does not report it. Already
 *                     counted inside {@code outputTokens} — this is a breakdown of it, not an
 *                     addition — so what it buys is the <em>explanation</em> of a cost, not the
 *                     cost itself: two analyses with identical answers can differ several-fold
 *                     here, and nothing else on screen would say why. It also turns a diagnosis
 *                     into a measurement: {@code LlmJsonSupport} already reports a model that
 *                     spent its whole output budget thinking and never reached the JSON, but only
 *                     once that run has failed; this figure shows the budget being eaten on a run
 *                     that <em>succeeded</em>, in time to raise {@code claude.max-tokens} or pick
 *                     another model. Note the nullability reads the other way round from
 *                     {@link #cachedInputTokens}: {@code 0} is the ordinary case, a real
 *                     measurement meaning the model did not deliberate, and {@code null} alone
 *                     means nobody counted.
 * @param durationMs   wall-clock time of the call, always measured here
 * @param provider     the provider label the call went to
 * @param model        the model the call named
 */
public record LlmUsage(
        Long inputTokens,
        Long outputTokens,
        Double costUsd,
        Long cachedInputTokens,
        Long reasoningTokens,
        long durationMs,
        String provider,
        String model
) {
    /** Usage for a provider that reports no token counts: the duration is still worth having. */
    public static LlmUsage untokenized(long durationMs, String provider, String model) {
        return new LlmUsage(null, null, null, null, null, durationMs, provider, model);
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
        // Assaini ici plutôt qu'aux trois appels : cette chaîne ne sert qu'à être journalisée, et
        // `model` vient de la configuration — un `%0A` dedans forgerait une ligne dans le fichier
        // que quiconque colle dans un rapport de bug. `slug` et non `name` parce qu'un identifiant
        // OpenRouter s'écrit `vendor/model` : le normaliser en `vendor_model` afficherait autre
        // chose que ce que l'opérateur a saisi, dans la ligne qui existe pour le lui montrer.
        // `provider` est un libellé d'un ensemble fermé, donc la fonction n'y touche pas — elle est
        // appliquée quand même pour que le jour où ce champ porte autre chose ne soit pas un trou.
        return LogSafe.slug(provider) + '/' + LogSafe.slug(model)
            + " · in=" + (inputTokens == null ? "?" : inputTokens)
            + " out=" + (outputTokens == null ? "?" : outputTokens)
            + (costUsd == null ? "" : " · $" + formatCost(costUsd))
            + (cachedInputTokens == null ? "" : " · cached=" + cachedInputTokens)
            + (reasoningTokens == null ? "" : " · reasoning=" + reasoningTokens)
            + " · " + durationMs + "ms";
    }

    /**
     * A per-call cost is usually a fraction of a cent, so a two-decimal rendering would print
     * {@code $0.00} for every analysis and make the whole figure useless. Six decimals below a
     * cent, two above, which keeps a session total readable and a single window honest.
     */
    private static String formatCost(double value) {
        return value < 0.01
            ? String.format(java.util.Locale.ROOT, "%.6f", value)
            : String.format(java.util.Locale.ROOT, "%.2f", value);
    }
}
