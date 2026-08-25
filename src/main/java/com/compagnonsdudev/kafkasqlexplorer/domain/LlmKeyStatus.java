// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors
package com.compagnonsdudev.kafkasqlexplorer.domain;

/**
 * What the gateway says about the API key itself — how much of it is left.
 *
 * <p>The credit was consulted nowhere. A key running out is answered with a 402, which
 * {@code LlmHttpSupport.remedyFor} already reads correctly ("the account is out of credit or past a
 * spending cap — topping it up is the fix, not the configuration"), but only <em>after</em> an
 * analysis has failed. It is published, so the Test button can say it beforehand, on the same press
 * that already asks what the model can do.
 *
 * <p>It is also the number {@code claude.session-cost-limit-usd} has been missing. That cap ships
 * disabled because "any figure chosen here would be arbitrary" — arbitrary is what a cap is when
 * nothing on screen says what the budget actually is.
 *
 * <p><strong>Every field is boxed, and null means the gateway did not say.</strong> One case
 * deserves care above the others: a key with no spending limit reports {@code limitUsd} null while
 * {@code usageUsd} is perfectly known. That is a real state — an unlimited grant — and it must not
 * be rendered as "0 left", nor confused with {@link #unavailable}, which is the different statement
 * that the question could not be asked at all.
 *
 * @param usageUsd     what this key has spent, in USD
 * @param limitUsd     its spending limit, or {@code null} for a key that has none
 * @param remainingUsd what is left — {@code null} whenever the limit is, because "unlimited minus
 *                     what you spent" is not a number
 * @param freeTier     whether the account is on the free tier, or {@code null} when unreported
 * @param error        why nothing could be read, or {@code null} when something was
 */
public record LlmKeyStatus(
    Double usageUsd,
    Double limitUsd,
    Double remainingUsd,
    Boolean freeTier,
    String error
) {
    /** The question could not be asked, or was answered with something unusable. */
    public static LlmKeyStatus unavailable(String error) {
        return new LlmKeyStatus(null, null, null, null, error);
    }

    /** True when a limit is published and nothing is left of it — a 402 waiting to happen. */
    public boolean isExhausted() {
        return remainingUsd != null && remainingUsd <= 0;
    }
}
