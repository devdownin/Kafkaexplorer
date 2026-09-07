// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors
package com.compagnonsdudev.kafkasqlexplorer.mcp.contract;

/**
 * A caveat that degraded a result without emptying it.
 *
 * <p>The {@code code} is stable and machine-readable so the console can aggregate ("budget clamped
 * 400 times this hour" is a configuration to revise, not an incident); the {@code message} is the
 * sentence a model or an operator reads.
 */
public record Warning(Severity severity, String code, String message) {

    public enum Severity { INFO, WARN, CRITICAL }

    public static Warning info(String code, String message) {
        return new Warning(Severity.INFO, code, message);
    }

    public static Warning warn(String code, String message) {
        return new Warning(Severity.WARN, code, message);
    }

    public static Warning critical(String code, String message) {
        return new Warning(Severity.CRITICAL, code, message);
    }
}
