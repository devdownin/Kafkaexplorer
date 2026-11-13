// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors
package com.compagnonsdudev.kafkasqlexplorer.domain;

import java.util.List;

/**
 * A statement the assistant proposes, and what is known about it.
 *
 * <p>Two invariants the page relies on. A proposal the planner did not accept says so
 * ({@code validated == false}, with {@code validationError}) and is never shown without it. And the
 * answer says what it was built from: the tables described to the model and, <em>by name</em>, the
 * ones left out to fit the budget — "I could not see it" is a different sentence from "it does not
 * exist".
 *
 * @param status           {@code OK}, {@code NEEDS_CLARIFICATION}, {@code OUT_OF_SCOPE} (the
 *                         model's), or {@code BUSY}, {@code DISABLED}, {@code ERROR} (this side's)
 * @param sql              the proposed statement, {@code null} unless the model wrote one
 * @param explanation      the model's one or two sentences
 * @param missing          what the model says it lacks, when {@code status} is not {@code OK}
 * @param validated        whether the planner accepted {@code sql}
 * @param validationError  why it did not, {@code null} when it did or when nothing was checked
 * @param repairRounds     how many times the planner's refusal was sent back to the model
 * @param tablesConsidered the tables whose columns the model was shown
 * @param tablesOmitted    candidate tables left out, by name
 * @param model            the model that answered, as reported by the call when it reports one
 * @param usage            what the calls cost, summed, when the provider reports it
 * @param durationMs       wall time of the whole request, gate included
 * @param error            why the request failed on this side, {@code null} otherwise
 */
public record AssistantSqlResponse(
    String status,
    String sql,
    String explanation,
    String missing,
    boolean validated,
    String validationError,
    int repairRounds,
    List<String> tablesConsidered,
    List<String> tablesOmitted,
    String model,
    LlmUsage usage,
    long durationMs,
    String error
) {
    public AssistantSqlResponse {
        tablesConsidered = tablesConsidered == null ? List.of() : List.copyOf(tablesConsidered);
        tablesOmitted = tablesOmitted == null ? List.of() : List.copyOf(tablesOmitted);
    }

    /** A request this side did not send to the model at all. */
    public static AssistantSqlResponse refused(String status, String error) {
        return new AssistantSqlResponse(status, null, null, null, false, null, 0,
            List.of(), List.of(), null, null, 0L, error);
    }
}
