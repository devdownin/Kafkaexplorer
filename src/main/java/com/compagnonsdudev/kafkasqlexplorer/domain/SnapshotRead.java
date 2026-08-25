// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors
package com.compagnonsdudev.kafkasqlexplorer.domain;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * What one snapshot read collected, and what it could not.
 *
 * <p>The read used to answer with a bare {@code List<PayloadDigest>}, and that list has three
 * different meanings when it comes back short: the topic holds nothing, the topic could not be
 * resolved at all, or the read stopped on its own budget with records still waiting. They call for
 * opposite actions — go and look at the cluster, fix the topic name, ask for a smaller sample — and
 * an empty list says none of them. It is the same rule the consumer-lag and activity work was done
 * for: a measurement that could not be taken must not come back looking like a measurement of zero.
 *
 * @param digests         everything the handler was given, oldest first
 * @param messagesByTopic every <em>requested</em> topic, keyed even when it contributed nothing
 * @param unreadableTopics topics whose partitions the broker did not describe — an absent topic, a
 *                        name with a typo in it, or metadata that never arrived. They cost
 *                        themselves and no longer cost the whole read
 * @param readError       the read threw and returned what it had; {@code null} when it ran to
 *                        completion
 * @param budgetExhausted the loop stopped on its wall-clock or silence budget rather than at the
 *                        end offsets, so the counts are floors
 */
public record SnapshotRead(
    List<PayloadDigest> digests,
    Map<String, Integer> messagesByTopic,
    List<String> unreadableTopics,
    String readError,
    boolean budgetExhausted
) {
    /**
     * A complete read of the given digests — the shape a caller that only has records can build,
     * and what the tests use. Counts are derived rather than asserted, so the invariant between
     * {@code digests} and {@code messagesByTopic} holds by construction.
     */
    public static SnapshotRead of(List<PayloadDigest> digests) {
        Map<String, Integer> byTopic = new LinkedHashMap<>();
        for (PayloadDigest digest : digests) {
            byTopic.merge(digest.topic(), 1, Integer::sum);
        }
        return new SnapshotRead(digests, byTopic, List.of(), null, false);
    }

    /** True when nothing at all was read, whatever the reason. */
    public boolean isEmpty() {
        return digests == null || digests.isEmpty();
    }

    /**
     * Why this read came back with nothing — three answers that send the reader to three different
     * places: a name that resolves to no topic here, a cluster with nothing in the window asked
     * for, and a read that broke. Shared by both callers, because both used to spend a model call
     * to have the answer guessed at instead.
     */
    public String emptyReadExplanation() {
        if (readError != null) {
            return "No message could be read: " + readError;
        }
        if (!unreadableTopics.isEmpty() && unreadableTopics.size() == messagesByTopic.size()) {
            return "None of the selected topics could be resolved on the cluster ("
                + String.join(", ", unreadableTopics)
                + "). Check the names, or that this deployment points at the right broker.";
        }
        String suffix = unreadableTopics.isEmpty() ? ""
            : " (" + String.join(", ", unreadableTopics) + " could not be resolved at all)";
        return "The selected topics hold no message in the window that was read" + suffix
            + ". Widen the sample, or pick topics that have recent traffic.";
    }

    /**
     * What is worth saying about this read's scope even when it did return something.
     *
     * <p>A topic that resolves to nothing and a topic that holds nothing are both invisible in
     * whatever the model then answers: it was shown no record for them, so its silence about them
     * is the absence of a question rather than a finding.
     */
    public List<String> scopeNotes() {
        List<String> notes = new ArrayList<>();
        if (readError != null) {
            notes.add("The read failed and what follows describes only what had already arrived — "
                + readError);
        }
        if (!unreadableTopics.isEmpty()) {
            notes.add("Not resolved on the cluster, so nothing was read from "
                + (unreadableTopics.size() == 1 ? "it" : "them") + ": "
                + String.join(", ", unreadableTopics));
        }
        List<String> empty = emptyTopics();
        if (!empty.isEmpty()) {
            notes.add("No message in the window read, so nothing is proposed for "
                + (empty.size() == 1 ? "it" : "them") + ": " + String.join(", ", empty));
        }
        if (budgetExhausted) {
            notes.add("The read stopped on its own time budget, so these samples are floors.");
        }
        return notes;
    }

    /** Requested topics that were resolved and yielded no record — genuinely empty, as far as read. */
    public List<String> emptyTopics() {
        return messagesByTopic.entrySet().stream()
            .filter(e -> e.getValue() == null || e.getValue() == 0)
            .map(Map.Entry::getKey)
            .filter(topic -> !unreadableTopics.contains(topic))
            .toList();
    }
}
