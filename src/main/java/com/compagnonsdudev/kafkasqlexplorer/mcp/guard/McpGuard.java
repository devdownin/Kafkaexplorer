// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors
package com.compagnonsdudev.kafkasqlexplorer.mcp.guard;

/**
 * Which control refused a call. Recorded beside the error code so the console can group refusals
 * by the setting that produced them: two hundred {@code RATE_LIMIT} refusals in an hour is a
 * configuration to revisit, two {@code TAINT} refusals is a security event, and both arrive as
 * "denied" without this.
 */
public enum McpGuard {
    READONLY, DENY_LIST, ALLOW_LIST, SCOPE, POLICY, TAINT, APPROVAL, RATE_LIMIT, QUARANTINE, VALIDATION
}
