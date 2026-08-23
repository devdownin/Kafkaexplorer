// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors
package com.compagnonsdudev.kafkasqlexplorer.domain;

/**
 * One row of the model shortlist: a model that can do this application's job, with what it would
 * cost to use it.
 *
 * <p>Choosing an OpenRouter model meant recalling a slug from memory and typing it into a text box.
 * The gateway can filter and sort its own catalogue, so the question "which models work here" has a
 * one-request answer — and every filter behind it is a fact this application already knows about
 * itself, `promptBudgetTokens` included.
 *
 * @param id          the slug to put in {@code claude.model}
 * @param name        display name, or {@code null} when the catalogue did not give one
 * @param contextLength the model's window in tokens, or {@code null} when unreported
 * @param schemaSupport see {@link SchemaSupport} — the shortlist asks for constrained models, but
 *                    an operator may widen it, so the grade travels per row rather than being
 *                    implied by membership of the list
 * @param reasoningMandatory whether reasoning cannot be turned off, or {@code null} when the model
 *                    publishes no reasoning block
 * @param promptPriceUsdPerMillion   published price of a million prompt tokens, or {@code null}
 * @param completionPriceUsdPerMillion published price of a million generated tokens, or
 *                    {@code null}
 * @param projectedCostUsd what one Process Mining window would cost on this model.
 *                    <strong>A projection, not a measurement</strong>, and the distinction is not
 *                    pedantry here: {@link LlmUsage#costUsd} is <em>read</em> from the provider
 *                    precisely because no price table lives in this application, and this figure
 *                    is the opposite — published prices multiplied by an estimate. It rests on the
 *                    same deliberately optimistic four-characters-per-token floor as
 *                    {@link LlmModelCheck#promptBudgetTokens}, so it can understate. It is worth
 *                    showing anyway, because the alternative at pick time is no idea at all, but
 *                    it must be labelled wherever it is rendered. {@code null} when the model
 *                    publishes no price — never {@code 0}, which is a real measurement meaning a
 *                    free model.
 */
public record LlmModelOption(
        String id,
        String name,
        Long contextLength,
        SchemaSupport schemaSupport,
        Boolean reasoningMandatory,
        Double promptPriceUsdPerMillion,
        Double completionPriceUsdPerMillion,
        Double projectedCostUsd
) {
}
