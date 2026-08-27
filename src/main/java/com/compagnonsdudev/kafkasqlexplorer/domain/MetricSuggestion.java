// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors
package com.compagnonsdudev.kafkasqlexplorer.domain;

import java.util.List;

/**
 * One contextual KPI proposed for this cluster, together with what it was derived from.
 *
 * <p>A suggestion is never applied on its own: it carries a ready {@link MetricConfig} the
 * operator opens in the editor, previews and saves. Everything else on this record exists so the
 * proposal can be judged before that — a number nobody can trace back to a measurement is exactly
 * the kind of literal the Metrics guide was stripped of.
 *
 * @param id                stable across runs (it names the topics it is about, not the audit that
 *                          produced it), so dismissing one in the browser sticks when the next
 *                          audit re-derives the same proposal
 * @param source            the evidence family — audit report or Stream Flow trace
 * @param title             short operator-facing name of the KPI
 * @param rationale         why this KPI is worth having *here*, in one sentence
 * @param evidence          the measurements it rests on, each stating its own scope ("the audit of
 *                          2026-08-16 measured a 812 ms average hop"). Never empty: a suggestion
 *                          with nothing behind it has no business being on the page
 * @param thresholdBasis    how the proposed warning / critical thresholds were derived from those
 *                          measurements, null when the metric is proposed without thresholds
 * @param caveats           what the proposal does not know — an inferred key column, an aggregate
 *                          only the Flink planner can run — so the operator checks it in preview
 * @param alreadyConfigured true when an existing metric already covers this ground; the panel says
 *                          so instead of hiding the card, since "already measured" is an answer
 * @param existingMetricName the metric that covers it, null unless {@code alreadyConfigured}
 * @param dataState         whether the topics it reads still hold anything, asked of the cluster
 *                          when the proposal was made — see {@link MetricDataState}. Every other
 *                          field here describes a past observation, and a proposal is only worth
 *                          offering if it can still be measured now: without this the panel
 *                          proposed KPIs over topics that had been deleted, with thresholds taken
 *                          from a count retention had since erased
 * @param metric            the pre-filled configuration, valid as-is for {@code POST /api/metrics}
 */
public record MetricSuggestion(
    String id,
    MetricSuggestionSource source,
    String title,
    String rationale,
    List<String> evidence,
    String thresholdBasis,
    List<String> caveats,
    boolean alreadyConfigured,
    String existingMetricName,
    MetricDataState dataState,
    MetricConfig metric
) {
    /**
     * The proposal as its family builds it, before the cluster has been asked.
     *
     * <p>{@link MetricDataState#UNKNOWN} is the only honest value at that point, and it is also
     * what a proposal keeps when the check cannot run — so the families construct one shape and
     * the verification pass is the single place that can say otherwise. Same idiom as
     * {@code MetricConfig}'s compat constructors, and for the same reason: a record has no default
     * values, and every one of these call sites would otherwise repeat the same literal.
     */
    public MetricSuggestion(String id, MetricSuggestionSource source, String title,
                            String rationale, List<String> evidence, String thresholdBasis,
                            List<String> caveats, boolean alreadyConfigured,
                            String existingMetricName, MetricConfig metric) {
        this(id, source, title, rationale, evidence, thresholdBasis, caveats, alreadyConfigured,
             existingMetricName, MetricDataState.UNKNOWN, metric);
    }
}
