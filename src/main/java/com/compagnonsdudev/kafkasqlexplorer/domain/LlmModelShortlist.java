// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors
package com.compagnonsdudev.kafkasqlexplorer.domain;

import java.util.List;

/**
 * The models this application could be pointed at, and what was asked for to obtain them.
 *
 * <p>{@code criteria} is not decoration. A shortlist is a filtered view, and a filtered view
 * presented as "the models" is the same lie as a truncated list presented as a complete one — an
 * operator has to be able to see that schema support was required before concluding their
 * favourite model is unavailable. It is stated in the answer rather than assumed from the UI,
 * because the UI is not the only thing that will read this.
 *
 * @param available whether the catalogue could be read at all. {@code false} with an empty list is
 *                  a different answer from {@code true} with an empty list — "we could not ask"
 *                  against "nothing matches" — and only the second says anything about the
 *                  catalogue.
 * @param models    the rows, in the order the gateway returned them (cheapest first)
 * @param criteria  what was asked for, in words
 * @param error     why nothing came back, or {@code null}
 */
public record LlmModelShortlist(
        boolean available,
        List<LlmModelOption> models,
        List<String> criteria,
        String error
) {
    public static LlmModelShortlist unavailable(String error) {
        return new LlmModelShortlist(false, List.of(), List.of(), error);
    }
}
