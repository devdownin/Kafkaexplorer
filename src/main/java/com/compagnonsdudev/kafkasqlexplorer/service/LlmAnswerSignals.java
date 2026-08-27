// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors
package com.compagnonsdudev.kafkasqlexplorer.service;

/**
 * Two things a call can tell you about itself that nothing here was reading.
 *
 * <p>Both are failures that leave no error behind, which is why they had gone unreported: the
 * pipeline carried on, produced an answer, and nothing said the answer rested on less than it
 * appeared to. For OpenRouter one of them already has a name — {@code SchemaSupport} has four
 * values rather than being a boolean precisely because a schema can be <em>accepted and ignored</em>,
 * and "a guarantee that silently is not one is worse than an outright refusal" — but that reading
 * comes from a catalogue only that provider publishes. These two are derived from what every call
 * already hands back, so they hold on any endpoint, with no second request and no per-provider
 * knowledge.
 *
 * <p>Both are deliberately <b>warnings, not verdicts</b>. The token ratio is an estimate and this
 * application does not own the tokeniser; a repaired answer is evidence about one call. Each says
 * what was observed and leaves the reading to the operator, which is the same restraint
 * {@code SchemaSupport} is documented with and the reason nothing acts on either automatically.
 */
final class LlmAnswerSignals {

    /**
     * Characters per token, deliberately optimistic — the same ratio {@code docs/check-compose.py}
     * and {@code OpenRouterModelCatalog} use, and optimistic in the direction that matters here:
     * real prompts of JSON-ish text tokenise nearer three characters per token, so a genuine count
     * lands <em>above</em> this estimate. Only a count well below it is worth a sentence.
     */
    static final int CHARS_PER_TOKEN = 4;

    /**
     * How far under the estimate a reported count has to fall before it is reported.
     *
     * <p>Half. Not a tuning knob but the point past which no tokeniser difference explains the
     * gap: an endpoint counting fewer than half the tokens a four-characters-per-token floor
     * predicts did not read what it was sent. Ollama's behaviour is exactly this — it drops the
     * oldest messages until the prompt fits and logs that at debug level, i.e. nowhere on a default
     * install — and {@code docs/LLM-PROVIDERS.md} devotes a section to it whose only remedy is two
     * environment variables set together and trusted.
     */
    static final double TRUNCATION_RATIO = 0.5;

    private LlmAnswerSignals() {
    }

    /**
     * The answer needed repairing although a schema was in force.
     *
     * <p>Constrained decoding cannot emit a reasoning block, a Markdown fence or prose around the
     * object — so if {@link LlmJsonSupport} had to remove any of that, the field went out and the
     * endpoint did not honour it. The call succeeded, the JSON was recovered, and the deployment
     * believed decoding was constrained: nothing distinguished it from a run where the schema held.
     *
     * @param schemaSent whether a schema actually travelled — see {@code LlmResponse.schemaSent},
     *                   which is what the client built rather than what was configured
     * @param rawText    the answer as it arrived
     * @param payload    what {@link LlmJsonSupport#extractJsonPayload} recovered from it
     * @return the warning, or {@code null} when there is nothing to report
     */
    static String constraintWarning(boolean schemaSent, String rawText, String payload) {
        if (!schemaSent || rawText == null || payload == null) return null;
        if (rawText.trim().equals(payload)) return null;
        return "The answer was requested under a JSON Schema and still had to be repaired before "
            + "it would parse — a constrained decoder cannot emit the reasoning block, code fence "
            + "or prose that was stripped from it. The endpoint accepted the schema and did not "
            + "apply it, so this run was no better constrained than an unconstrained one. The "
            + "result stands; what is not established is that it was bounded by the schema.";
    }

    /**
     * The endpoint counted far fewer prompt tokens than were sent to it.
     *
     * <p>The one check that catches a silently truncated prompt, on every OpenAI-shaped endpoint,
     * for free: the count is already parsed on every call and was never compared against anything.
     * An analysis reasoning on a fraction of what it was handed reads exactly like one that saw all
     * of it — and says nothing about the fraction.
     *
     * @param promptChars           what was sent, in characters
     * @param reportedPromptTokens  what the provider says it read, or {@code null} when it did not
     *                              say — in which case nothing is claimed
     */
    static String truncationWarning(int promptChars, Long reportedPromptTokens) {
        if (reportedPromptTokens == null || reportedPromptTokens <= 0 || promptChars <= 0) return null;
        long floor = promptChars / CHARS_PER_TOKEN;
        if (floor <= 0) return null;
        if (reportedPromptTokens >= floor * TRUNCATION_RATIO) return null;
        return "The endpoint reported reading " + reportedPromptTokens + " prompt tokens, where "
            + promptChars + " characters were sent — fewer than half of even an optimistic "
            + CHARS_PER_TOKEN + "-characters-per-token floor. That is the shape of a prompt "
            + "truncated to fit a context window: the analysis then reasons on part of what it was "
            + "handed and cannot say which part. Check the model's context length against "
            + "process-mining.prompt-char-budget (and, on a local engine, that the server was "
            + "started with a window that large). The ratio is an estimate — this application does "
            + "not own the tokeniser — so treat it as a reason to check, not as a measurement.";
    }
}
