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

    /**
     * Named in {@code explorer.mcp.approval-required-tools} — <b>declared, not yet enforced</b>.
     *
     * <p>The reason is carried rather than left null on purpose. No approval token is checked
     * anywhere yet (phase 5), so a badge reading "approval required" with nothing behind it would
     * be this console asserting a control that does not exist — on the screen whose whole job is to
     * tell an operator what is actually in force.
     */
    public static Visibility exposedWithApproval() {
        return new Visibility(State.EXPOSED_WITH_APPROVAL,
                "declared in explorer.mcp.approval-required-tools: every call needs a token an "
                        + "operator mints from this console, good for one call of this tool");
    }

    public static Visibility hiddenBy(String reason) {
        return new Visibility(State.HIDDEN, reason);
    }

    public boolean visibleToAgents() {
        return state != State.HIDDEN;
    }
}
