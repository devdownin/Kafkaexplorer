// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors
package com.yourcompany.kafkasqlexplorer.domain;

import java.util.List;

/**
 * How one topic changed between two audit runs.
 *
 * <p>Only topics whose <em>health</em> moved are reported — message counts change on every live
 * topic, so listing volume deltas for a 2 000-topic cluster would bury the signal under noise.
 * The counts are carried for the topics that did change, where they help explain why.
 *
 * @param change         what happened to this topic between the two runs
 * @param fromStatus     severity in the baseline run, null when the topic is new
 * @param toStatus       severity in the later run, null when the topic is gone
 * @param newIssues      findings present in the later run and absent from the baseline
 * @param resolvedIssues findings present in the baseline and absent from the later run
 */
public record TopicDiff(
    String name,
    ChangeKind change,
    HealthStatus fromStatus,
    HealthStatus toStatus,
    long fromMessageCount,
    long toMessageCount,
    long fromDuplicateCount,
    long toDuplicateCount,
    int fromPoisonCount,
    int toPoisonCount,
    List<String> newIssues,
    List<String> resolvedIssues
) {
    public enum ChangeKind {
        /** Present in the later run only — a new topic, or one that came into scope. */
        ADDED,
        /** Present in the baseline only — deleted, or out of scope for the later run. */
        REMOVED,
        /** Severity got worse. */
        REGRESSED,
        /** Severity got better. */
        IMPROVED,
        /** Same severity, but the findings themselves differ. */
        ISSUES_CHANGED,
    }
}
