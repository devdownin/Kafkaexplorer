// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors
package com.yourcompany.kafkasqlexplorer.domain;

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
 * @param topic           the topic these positions are on
 * @param groups          groups holding at least one committed offset on it, worst lag first
 * @param groupsExamined  groups whose offsets were actually read
 * @param groupsInCluster groups the broker listed, before the cap
 * @param truncated       true when the cap stopped the scan short
 * @param warnings        human-readable notes on what bounded or degraded this read
 */
public record TopicConsumers(
        String topic,
        List<ConsumerGroupLag> groups,
        int groupsExamined,
        int groupsInCluster,
        boolean truncated,
        List<String> warnings
) {

    /** The broker could not be asked at all — an empty list would claim nobody reads the topic. */
    public static TopicConsumers unavailable(String topic, String reason) {
        return new TopicConsumers(topic, List.of(), 0, 0, false, List.of(reason));
    }
}
