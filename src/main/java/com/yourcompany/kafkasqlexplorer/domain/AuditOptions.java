// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors
package com.yourcompany.kafkasqlexplorer.domain;

/**
 * Controls which checks are executed during an audit run, and optionally
 * restricts the audit to topics whose name starts with {@code topicPrefix}.
 * Defaults (via {@link #all()}) enable every check over all topics.
 */
public record AuditOptions(
    boolean checkSchema,
    boolean checkPoisonMessages,
    boolean checkDuplicates,
    boolean checkFlows,
    boolean checkExactCount,
    /**
     * Reads each topic's consumer groups and reports the ones nothing will drain. Costs several
     * coordinator round trips per topic, hence its own switch — but it is the check that answers
     * "why is this piling up?", which none of the others could.
     */
    boolean checkConsumerLag,
    String topicPrefix
) {
    public static AuditOptions all() {
        return new AuditOptions(true, true, true, true, true, true, null);
    }

    /** Trimmed prefix, or {@code null} when no topic filter should apply. */
    public String normalizedPrefix() {
        return (topicPrefix == null || topicPrefix.isBlank()) ? null : topicPrefix.trim();
    }
}
