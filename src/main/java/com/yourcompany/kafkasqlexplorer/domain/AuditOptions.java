// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors
package com.yourcompany.kafkasqlexplorer.domain;

/**
 * Controls which checks are executed during an audit run.
 * Defaults (via {@link #all()}) enable every check.
 */
public record AuditOptions(
    boolean checkSchema,
    boolean checkPoisonMessages,
    boolean checkDuplicates,
    boolean checkFlows,
    boolean checkExactCount
) {
    public static AuditOptions all() {
        return new AuditOptions(true, true, true, true, true);
    }
}
