// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors
package com.compagnonsdudev.kafkasqlexplorer.domain;

import java.util.List;

/**
 * Who reads a topic, and how far behind they are.
 *
 * <p>The counts exist because "no consumer group reads this topic" and "we looked at two hundred
 * of the cluster's three thousand groups" are different answers that would otherwise render
 * identically as an empty list. {@code groupsExamined} / {@code groupsInCluster} state the scope,
 * {@code truncated} says the cap was hit, and {@code warnings} carries whatever else bounded or
 * degraded the read — the same discipline the topic search and the audit already follow.
 *
 * <p>{@code available} is the one that cannot be derived from the numbers. A failed read and a
 * cluster with no group at all both come back as zero groups out of zero examined, and the reader —
 * the panel, the Prometheus exporter — has no way to tell "we could not ask" from "we asked, the
 * answer is nobody". The panel rendered the first as <em>The cluster has no client group at all</em>
 * above an empty state reading <em>No consumer group reads this topic</em>: two confident claims
 * about a question that was never answered. The exporter, for its part, must keep a gauge's last
 * value on the first case and drop it on the second.
 *
 * @param topic           the topic these positions are on
 * @param groups          groups holding at least one committed offset on it, worst lag first
 * @param groupsExamined  groups whose offsets were actually read
 * @param groupsEligible  groups left after share groups and this application's own were excluded,
 *                        before the cap — the honest denominator for {@code groupsExamined}
 * @param groupsInCluster groups the broker listed, before any exclusion or cap
 * @param truncated       true when the cap stopped the scan short
 * @param available       false when the read failed; then every count above is "unknown", not zero
 * @param warnings        human-readable notes on what bounded or degraded this read
 */
public record TopicConsumers(
        String topic,
        List<ConsumerGroupLag> groups,
        int groupsExamined,
        int groupsEligible,
        int groupsInCluster,
        boolean truncated,
        boolean available,
        List<String> warnings
) {

    /** The broker could not be asked at all — an empty list would claim nobody reads the topic. */
    public static TopicConsumers unavailable(String topic, String reason) {
        return new TopicConsumers(topic, List.of(), 0, 0, 0, false, false, List.of(reason));
    }
}
