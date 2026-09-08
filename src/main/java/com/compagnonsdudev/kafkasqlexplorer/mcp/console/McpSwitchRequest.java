// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors
package com.compagnonsdudev.kafkasqlexplorer.mcp.console;

/**
 * What the console sends when an operator throws a switch.
 *
 * <p>{@code actor} and {@code reason} are <b>claimed, not verified</b>: this application
 * authenticates nobody, so the name in the banner is whatever was typed. That is still worth
 * recording — a derogation with a name and a sentence beside it gets lifted, and one with neither
 * becomes the permanent configuration nobody remembers choosing — and the console labels the
 * column as declared rather than as identified, so nothing here reads as an audited identity.
 *
 * @param enable true to apply the restriction, false to lift it
 * @param actor  who is throwing the switch, as claimed
 * @param reason why, in their own words
 */
public record McpSwitchRequest(boolean enable, String actor, String reason) {

    public String actorOrAnonymous() {
        return actor == null || actor.isBlank() ? "an unnamed operator" : actor.trim();
    }

    /**
     * The reason, or a sentence saying none was given.
     *
     * <p>Not an empty string: the banner renders this verbatim, and an override whose reason column
     * is blank reads as a bug in the page rather than as a question for whoever set it.
     */
    public String reasonOrNone() {
        return reason == null || reason.isBlank() ? "no reason was given" : reason.trim();
    }
}
