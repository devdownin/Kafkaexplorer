// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors
package com.compagnonsdudev.kafkasqlexplorer.mcp.observability;

/**
 * Whether an agent can see a tool, and when it cannot, why.
 *
 * <p>The reason is the whole reason this type exists. An operator whose agent cannot find
 * {@code kex_produce_message} has one question, and a catalogue that simply omits the row answers
 * it with silence — leaving them to read the YAML and guess which of four settings did it. A row
 * saying {@code HIDDEN — read-only mode} costs nothing and ends the search.
 *
 * @param state  what the agent sees
 * @param reason why it is hidden, in the operator's language; null unless {@code state} is HIDDEN
 */
public record Visibility(State state, String reason) {

    public enum State { EXPOSED, EXPOSED_WITH_APPROVAL, HIDDEN }

    public static Visibility exposed() {
        return new Visibility(State.EXPOSED, null);
    }

    public static Visibility exposedWithApproval() {
        return new Visibility(State.EXPOSED_WITH_APPROVAL, null);
    }

    public static Visibility hiddenBy(String reason) {
        return new Visibility(State.HIDDEN, reason);
    }

    public boolean visibleToAgents() {
        return state != State.HIDDEN;
    }
}
