// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors
package com.compagnonsdudev.kafkasqlexplorer.domain;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.ArrayList;
import java.util.List;

/**
 * What the profiling call proposed, and what it cost.
 *
 * <p>{@code usage} is the second half and was missing: the Process Mining pipeline makes
 * <em>two</em> model calls — this one, then the analysis — and only the second reported anything.
 * The profiling call was logged and never shown, so the figure on screen understated every run's
 * real bill. That is the rule {@code totalCostUsd} enforces between windows ("a partial sum
 * understates a real bill with the assurance of an exact one") applied within the analysis and
 * ignored between the two steps of the same pipeline.
 *
 * <p>It is null when the model was never reached — a failed profiling run still burned nothing —
 * and its own token and cost fields stay null on a provider that reports none, by
 * {@link LlmUsage}'s rule. The LLM's own JSON is deserialized into this record, so nothing here is
 * ever supplied by the model: the field is attached afterwards by the service.
 */
public record FieldProfileResult(
    List<TopicProfile> topics,
    SchemaUnificationProposal unificationProposal,
    List<String> warnings,
    // Serialized, never deserialized: this record is what the model's own JSON is parsed into, and
    // a measurement of what the call cost is not the model's to state. The javadoc above said so
    // before anything enforced it.
    @JsonProperty(access = JsonProperty.Access.READ_ONLY)
    LlmUsage usage,
    String error
) {
    public FieldProfileResult(List<TopicProfile> topics,
                              SchemaUnificationProposal unificationProposal,
                              List<String> warnings) {
        this(topics, unificationProposal, warnings, null, null);
    }

    public FieldProfileResult(List<TopicProfile> topics,
                              SchemaUnificationProposal unificationProposal,
                              List<String> warnings,
                              LlmUsage usage) {
        this(topics, unificationProposal, warnings, usage, null);
    }

    /**
     * A profiling run that did not happen, as opposed to one that found nothing.
     *
     * <p>Every failure path used to answer 200 with an empty {@code topics} list and the reason in
     * {@code warnings} — the same shape as a genuine profiling of topics that hold no messages. The
     * two call for opposite actions: one is a cluster to go and look at, the other an endpoint, a
     * model or a key to go and fix. It is the distinction {@code ProcessMiningResult.error} already
     * draws for the analysis half of this pipeline, and it was missing from the profiling half —
     * which is how, in this session, three model failures were read as failed reads and chased
     * through two rebuilds of the consumer.
     *
     * <p>When {@code error} is set nothing else in the record is a finding. The usage still travels
     * where it is known: a run that burned tokens and then failed to parse is not a free one.
     */
    public static FieldProfileResult failed(String message) {
        return new FieldProfileResult(List.of(), null, List.of(message), null, message);
    }

    /** As above, for a failure that reached the model and therefore cost something. */
    public static FieldProfileResult failed(String message, LlmUsage usage) {
        return new FieldProfileResult(List.of(), null, List.of(message), usage, message);
    }

    /** The parsed proposal with the accounting of the call that produced it. */
    public FieldProfileResult withUsage(LlmUsage usage) {
        return new FieldProfileResult(topics, unificationProposal, warnings, usage, error);
    }

    /**
     * The same proposal, saying what the read behind it could not cover.
     *
     * <p>Prepended rather than appended: these notes explain an absence — a topic that resolves to
     * nothing here, or that held no message — which the model's own warnings cannot know about,
     * since it was never shown the topic in the first place. A failed run keeps its message alone;
     * there is no proposal for scope notes to qualify.
     */
    public FieldProfileResult withScopeNotes(List<String> notes) {
        if (notes == null || notes.isEmpty() || error != null) {
            return this;
        }
        List<String> merged = new ArrayList<>(notes);
        if (warnings != null) {
            merged.addAll(warnings);
        }
        return new FieldProfileResult(topics, unificationProposal, List.copyOf(merged), usage, error);
    }
}
