// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors
package com.compagnonsdudev.kafkasqlexplorer.domain;

/**
 * What the gateway says about the model this deployment is configured to call.
 *
 * <p>{@code POST /api/config/test-llm} proved that <em>something answered</em>. That is worth
 * knowing and it is not the question an operator has when Process Mining misbehaves, which is
 * whether the slug they typed can do this job at all. OpenRouter publishes the answer per model, so
 * on that provider the Test button can say it: the model emits text, its schema support, its
 * context window against the prompt budget, and whether it is obliged to reason before answering.
 *
 * <p><strong>Every field is boxed and null means "the catalogue did not say".</strong> Same rule as
 * {@link LlmUsage}, and for the same reason: this record exists to replace guesses, so a fact it
 * could not establish must not come back looking like a fact it established negatively. A model
 * that cannot be looked up at all yields {@link #unavailable}, whose {@code error} names why —
 * "we asked and the answer is no" and "we could not ask" are different answers, and only the first
 * should change what an operator does next.
 *
 * <p>The verdicts are computed here rather than in the browser. {@link #schemaSupport} and
 * {@link #promptBudgetFits} are judgements about the deployment; the page turns them into
 * sentences and nothing more. A grading rule mirrored on both sides is one that drifts.
 *
 * @param id                 the slug the catalogue answered for, which is not necessarily the slug
 *                           that was asked about — OpenRouter resolves aliases
 * @param name               the model's display name, or {@code null}
 * @param contextLength      the model's window in tokens, or {@code null} when unreported
 * @param emitsText          whether {@code text} is among the model's output modalities.
 *                           {@code false} is a real finding — an embeddings, rerank or
 *                           speech model cannot answer a Process Mining prompt whatever else is
 *                           configured — and {@code null} means the modalities were not reported,
 *                           which must not be shown as a refusal.
 * @param schemaSupport      see {@link SchemaSupport}; never null, {@link SchemaSupport#UNKNOWN}
 *                           carries the absence
 * @param reasoningMandatory whether the model refuses to have reasoning turned off. {@code true}
 *                           means part of {@code claude.max-tokens} is spent deliberating on every
 *                           call by construction, which is the failure {@code LlmJsonSupport}
 *                           reports as an unterminated reasoning block — after the run has already
 *                           failed. {@code null} for a model that publishes no reasoning block,
 *                           which is the ordinary case and not a denial.
 * @param promptBudgetTokens the floor estimate of what one Process Mining prompt claims, in
 *                           tokens: the character budget at four characters per token, plus
 *                           {@code claude.max-tokens}, since the answer shares the window. Carried
 *                           so the comparison can be checked rather than believed.
 * @param promptBudgetFits   whether {@link #promptBudgetTokens} is within {@link #contextLength}.
 *                           <strong>A floor, not a calibration</strong> — the four-characters-per-token
 *                           ratio is deliberately optimistic, the same one
 *                           {@code docs/check-compose.py} uses, so a budget this passes may still
 *                           not fit while one it rejects certainly does not. {@code null} when the
 *                           window is unknown.
 * @param error              why the lookup produced nothing, or {@code null} when it produced
 *                           something
 */
public record LlmModelCheck(
        String id,
        String name,
        Long contextLength,
        Boolean emitsText,
        SchemaSupport schemaSupport,
        Boolean reasoningMandatory,
        Long promptBudgetTokens,
        Boolean promptBudgetFits,
        String error
) {
    /** The lookup could not be made, or answered with something unusable. */
    public static LlmModelCheck unavailable(String error) {
        return new LlmModelCheck(null, null, null, null, SchemaSupport.UNKNOWN, null, null, null, error);
    }
}
