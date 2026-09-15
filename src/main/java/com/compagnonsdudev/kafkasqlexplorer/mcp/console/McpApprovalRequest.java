// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors
package com.compagnonsdudev.kafkasqlexplorer.mcp.console;

/**
 * What the console sends when an operator approves one call of one tool.
 *
 * <p>Its own record rather than {@link McpSwitchRequest}, which the toggles use: a switch records
 * <em>why</em> it was thrown, and an approval names <em>who</em> it is for. Carrying one field on
 * the other's record would put a component on every toggle that means nothing there.
 *
 * @param actor    who approved, as claimed — this application authenticates no operator
 * @param identity the MCP caller the token is minted for, as the console's client rows show it;
 *                 {@code null} or blank mints one any caller may spend, which the answer says
 *                 rather than leaves to be discovered
 */
public record McpApprovalRequest(String actor, String identity) {

    public String actorOrAnonymous() {
        return actor == null || actor.isBlank() ? "an unnamed operator" : actor.trim();
    }

    /** The caller this approval is for, or {@code null} for any. */
    public String boundIdentity() {
        return identity == null || identity.isBlank() ? null : identity.trim();
    }
}
