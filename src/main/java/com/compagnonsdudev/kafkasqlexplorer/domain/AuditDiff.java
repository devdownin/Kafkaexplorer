// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors
package com.compagnonsdudev.kafkasqlexplorer.domain;

import java.util.List;

/**
 * Topic-by-topic comparison of two audit runs — "what changed, and did it get better or worse?".
 *
 * <p>{@link #topics()} lists only the topics whose health moved; {@link #unchanged()} counts the
 * rest rather than listing them. On a large cluster the unchanged topics are the overwhelming
 * majority and printing them would hide the handful that matter.
 *
 * @param fromAuditId      baseline run (the older of the two)
 * @param toAuditId        later run
 * @param healthScoreDelta later minus baseline, null when either run did not record a score
 * @param warnings         anything that limits how much the comparison can be trusted
 */
public record AuditDiff(
    String fromAuditId,
    String toAuditId,
    long fromTimestamp,
    long toTimestamp,
    int added,
    int removed,
    int regressed,
    int improved,
    int issuesChanged,
    int unchanged,
    Double healthScoreDelta,
    List<TopicDiff> topics,
    List<String> warnings
) {}
