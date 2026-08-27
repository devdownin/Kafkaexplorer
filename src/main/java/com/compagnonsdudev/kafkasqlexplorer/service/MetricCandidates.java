// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors
package com.compagnonsdudev.kafkasqlexplorer.service;

import com.compagnonsdudev.kafkasqlexplorer.domain.ProcessModel;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The KPIs a measured process supports, named the same way on both sides of the model.
 *
 * <p>Process Mining can ask the model which of these are worth following, and the answer is an
 * <b>id</b>, never a metric: every card the suggestion panel builds carries evidence naming a run
 * and a measurement, and a KPI the model invented would be the fabricated "99.98 % availability"
 * tile the Metrics guide was stripped of. Thresholds are multiples of a measured p95 and say so; a
 * number from a model would look identical and mean nothing. So the model chooses and explains
 * among candidates the server has already built and can defend.
 *
 * <p>That only works if both sides agree on the ids, which is the whole reason this class exists
 * rather than the two building their own strings: {@code MetricSuggestionService} mints them when
 * it builds the cards, and {@code LlmAnalysisService} lists them in the prompt hours earlier. Two
 * concatenations of {@code "pm:hop-latency:" + from + ">" + to} in two files is a drift waiting to
 * happen, and it would surface as a banner naming a card that is not there.
 *
 * <p>The filters and the caps mirror {@code fromMeasuredProcess} for the same reason. A candidate
 * the panel would later cut is one the model can pick and the banner cannot show — so the cut is
 * applied here too, and the browser still drops a priority whose card is absent rather than
 * rendering a dangling name.
 */
public final class MetricCandidates {

    private MetricCandidates() {}

    /** Mirrors MetricSuggestionService's own caps, so a candidate is a card the panel will build. */
    static final int MAX_TRANSITIONS = 6;
    static final int MAX_REPEATS = 3;

    /**
     * One KPI the measured process supports.
     *
     * @param id    the id the suggestion panel will give this card
     * @param label one line for the prompt: what it would measure, and what was observed
     */
    public record Candidate(String id, String label) {}

    /** The id of the hop-latency KPI between two topics. */
    public static String hopLatencyId(String fromTopic, String toTopic) {
        return "pm:hop-latency:" + fromTopic + ">" + toTopic;
    }

    /** The id of the rework KPI on one topic. */
    public static String reworkId(String topic) {
        return "pm:duplicates:" + topic;
    }

    /** The id of the status-breakdown KPI on one topic. */
    public static String statusId(String topic) {
        return "pm:status:" + topic;
    }

    /**
     * The candidates a measured process supports, in the order the panel would propose them.
     *
     * <p>Empty when there is no event log: with no case id there are no transitions, so there is
     * nothing to choose between — which is a state rather than a failure, and the caller says so
     * instead of asking the model to rank an empty list.
     */
    public static List<Candidate> from(ProcessModel model) {
        if (model == null || !model.available() || model.edges() == null) return List.of();

        List<Candidate> out = new ArrayList<>();

        List<ProcessModel.Edge> crossing = new ArrayList<>();
        for (ProcessModel.Edge edge : model.edges()) {
            if (edge == null || edge.from() == null || edge.to() == null) continue;
            String from = ProcessModelBuilder.topicOf(edge.from());
            String to = ProcessModelBuilder.topicOf(edge.to());
            // A transition inside one topic is a status moving, not a record travelling, and the
            // latency template correlates two topics — pointing it at one compares a topic with
            // itself. Same exclusion as the panel's.
            if (from == null || from.isBlank() || to == null || to.isBlank() || from.equals(to)) continue;
            if (edge.cases() < 1) continue;
            crossing.add(edge);
        }
        crossing.sort(Comparator
            .comparingLong(ProcessModel.Edge::p95Ms).reversed()
            .thenComparing(Comparator.comparingInt(ProcessModel.Edge::cases).reversed())
            .thenComparing(ProcessModel.Edge::from)
            .thenComparing(ProcessModel.Edge::to));
        for (ProcessModel.Edge edge : crossing.stream().limit(MAX_TRANSITIONS).toList()) {
            String from = ProcessModelBuilder.topicOf(edge.from());
            String to = ProcessModelBuilder.topicOf(edge.to());
            out.add(new Candidate(
                hopLatencyId(from, to),
                "Processing latency " + from + " → " + to + " — measured p95 " + edge.p95Ms()
                    + " ms over " + edge.cases() + " case(s)"
                    + (edge.outOfOrderCount() > 0
                        ? ", " + edge.outOfOrderCount() + " of them stamped out of order" : "")));
        }

        Map<String, ProcessModel.Repeat> worstByTopic = new LinkedHashMap<>();
        if (model.repeats() != null) {
            for (ProcessModel.Repeat repeat : model.repeats()) {
                if (repeat == null || repeat.activity() == null || repeat.casesAffected() < 1) continue;
                String topic = ProcessModelBuilder.topicOf(repeat.activity());
                if (topic == null || topic.isBlank()) continue;
                worstByTopic.merge(topic, repeat,
                    (a, b) -> a.casesAffected() >= b.casesAffected() ? a : b);
            }
        }
        worstByTopic.entrySet().stream()
            .sorted(Comparator
                .comparingInt((Map.Entry<String, ProcessModel.Repeat> e)
                    -> e.getValue().casesAffected()).reversed()
                .thenComparing(Map.Entry::getKey))
            .limit(MAX_REPEATS)
            .forEach(e -> out.add(new Candidate(
                reworkId(e.getKey()),
                "Rework on " + e.getKey() + " — " + e.getValue().casesAffected()
                    + " case(s) visited " + e.getValue().activity() + " more than once")));

        return List.copyOf(out);
    }
}
