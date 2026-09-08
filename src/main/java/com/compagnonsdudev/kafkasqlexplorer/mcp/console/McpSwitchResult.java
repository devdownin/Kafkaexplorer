// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors
package com.compagnonsdudev.kafkasqlexplorer.mcp.console;

import com.compagnonsdudev.kafkasqlexplorer.mcp.guard.McpRuntimeSwitches;

/**
 * What throwing a switch actually did.
 *
 * <p>{@code applied} false is the field that matters. Locking a surface that configuration already
 * holds read-only, or lifting a lock that was never set, changes nothing — and a response that said
 * "done" either way would leave an operator believing a control took effect when it did not. The
 * message says which of the two happened, in a sentence.
 *
 * @param applied  true when the state actually changed
 * @param message  what happened, or why nothing did
 * @param override the override now in force, {@code null} when the call lifted one or changed nothing
 */
public record McpSwitchResult(boolean applied, String message, McpOverrideView override) {

    static McpSwitchResult applied(String message, McpRuntimeSwitches.Override override) {
        return new McpSwitchResult(true, message,
                override == null ? null : McpOverrideView.of(override));
    }

    static McpSwitchResult noop(String message) {
        return new McpSwitchResult(false, message, null);
    }
}
