// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors
package com.compagnonsdudev.kafkasqlexplorer.domain;

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
    LlmUsage usage
) {
    public FieldProfileResult(List<TopicProfile> topics,
                              SchemaUnificationProposal unificationProposal,
                              List<String> warnings) {
        this(topics, unificationProposal, warnings, null);
    }

    /** The parsed proposal with the accounting of the call that produced it. */
    public FieldProfileResult withUsage(LlmUsage usage) {
        return new FieldProfileResult(topics, unificationProposal, warnings, usage);
    }
}
