// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors
package com.compagnonsdudev.kafkasqlexplorer.mcp.console;

import com.compagnonsdudev.kafkasqlexplorer.mcp.guard.McpRuntimeSwitches;

import java.time.Instant;

/**
 * One live runtime override, as the console's banner shows it.
 *
 * <p>{@code ageMs} travels rather than being computed in the browser from {@code since}: the banner
 * exists to make a stale derogation impossible to ignore, and a client clock that is hours off
 * would render the one number that matters as wrong in whichever direction reassures.
 *
 * @param kind   READONLY / TOOL / QUARANTINE
 * @param target the tool or identity, {@code null} for the global read-only lock
 * @param actor  who set it, as claimed — this application authenticates nobody
 * @param reason why, in their words
 * @param since  when it was set
 * @param ageMs  how long it has been in force, measured on the server
 */
public record McpOverrideView(
        String kind,
        String target,
        String actor,
        String reason,
        Instant since,
        long ageMs
) {

    static McpOverrideView of(McpRuntimeSwitches.Override override) {
        return new McpOverrideView(override.kind().name(), override.target(), override.actor(),
                override.reason(), override.since(), override.ageMs());
    }
}
