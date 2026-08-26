// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors
package com.compagnonsdudev.kafkasqlexplorer.domain;

import java.util.List;

/**
 * The measured process, as the browser kept it, carried back so a KPI can be derived from it.
 *
 * <p>Like a Stream Flow trace, a {@link ProcessModel} exists nowhere server-side: it is computed
 * for one analysis, returned, and forgotten. It is also the strongest observation this application
 * produces about a pipeline — a directly-follows graph with per-transition quantiles, counted over
 * <em>every</em> record read and grouped on a correlation id an operator validated by hand. The
 * Metrics page had no way to use it, so the panel derived its latency KPIs from an audit that
 * groups topics by their names and from a trace that follows a single key.
 *
 * <p>This is deliberately <b>not</b> {@code ProcessModel} sent back verbatim, for two reasons that
 * both matter. Every component here is boxed: Jackson binds a record through its canonical
 * constructor, so an absent property arrives as {@code null} and a primitive would fail the whole
 * request on a body that is merely partial — the same rule {@link FlowChainEvidence} is written to.
 * And it carries only what the derivation reads: the variants, the activity table and the spotlight
 * case ids are the analysis prompt's material, and shipping them back across a trust boundary to be
 * ignored is data nobody needs to hold.
 *
 * @param measuredAt     epoch millis of the analysis that produced it, null when not recorded
 * @param cases          correlation ids the log grouped the records into
 * @param windowStartMs  first event time in the log, null when unknown
 * @param windowEndMs    last event time in the log, null when unknown
 * @param eventTimeSource which clock ordered the log ({@code ProcessModel.TimeSource}), as a string
 *                        rather than the enum: an unknown value must cost this evidence, never the
 *                        whole request
 * @param transitions    the directly-follows edges, most frequent first
 * @param repeats        activities a single case visited more than once
 */
public record ProcessModelEvidence(
    Long measuredAt,
    Integer cases,
    Long windowStartMs,
    Long windowEndMs,
    String eventTimeSource,
    List<MeasuredTransition> transitions,
    List<MeasuredRepeat> repeats
) {
    /**
     * One transition and what it cost.
     *
     * <p>{@code from} and {@code to} are activity labels, which are topic names except on a topic
     * whose status the operator mapped — see {@code ProcessModelBuilder}. Resolving one back to its
     * topic is that class's rule and is applied server-side, so this record carries the label it
     * was given rather than a topic the browser would have had to derive.
     *
     * @param cases  how many distinct cases took this transition — the size of the sample the
     *               quantiles below rest on
     */
    public record MeasuredTransition(
        String from,
        String to,
        Integer occurrences,
        Integer cases,
        Long p50Ms,
        Long p95Ms,
        Long maxMs
    ) {}

    /** An activity a single case visited more than once: a redelivery, a retry or a rework loop. */
    public record MeasuredRepeat(
        String activity,
        Integer casesAffected,
        Integer maxOccurrencesInOneCase
    ) {}

    public List<MeasuredTransition> measuredTransitions() {
        return transitions == null ? List.of() : transitions;
    }

    public List<MeasuredRepeat> measuredRepeats() {
        return repeats == null ? List.of() : repeats;
    }
}
