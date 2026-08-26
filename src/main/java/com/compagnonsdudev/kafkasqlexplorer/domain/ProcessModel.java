// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors
package com.compagnonsdudev.kafkasqlexplorer.domain;

import java.util.List;

/**
 * The process, measured — an event log's aggregate, computed from the digests rather than inferred
 * from them by a model.
 *
 * <p>This exists because of what the analysis prompt was asking for and could not show. Four of the
 * five entries in {@code AuditPromptCatalog} are questions about a <em>case</em> — ordering,
 * orphans, latency, duplicates are each "par correlation id" — while
 * {@code LlmAnalysisService} sampled its messages <em>per topic, independently, on offset order</em>.
 * Whether one case survived in two topics' samples at once was an accident of those topics carrying
 * the same cases, in the same order, at comparable volume, which is what stops being true the moment
 * a pipeline branches, filters or retries. On this repository's own seed data the overlap is nil by
 * construction: {@code demo.payments.*} and {@code demo.shipments.*} are correlated to the orders by
 * header only and never carry the order id in the payload. What a small model can still do with such
 * a sample is infer a plausible pipeline from the <em>topic names</em>, and nothing in the answer
 * distinguishes that from an observation.
 *
 * <p>Every field below is a count, a sort or a set difference over data
 * {@link PayloadDigest#fields()} already carried per record. It cannot be wrong, it is reproducible
 * between two runs on the same window, and it fits in a couple of kilobytes of prompt where the
 * per-message lines it replaces spent a hundred and twenty thousand characters. What is left to the
 * model is the half it cannot be replaced on: saying what the numbers <em>mean</em>, and drawing
 * the Mermaid from an edge list it was handed rather than from a guess.
 *
 * <p><strong>Not available is a state, not an empty model.</strong> Without a validated field
 * mapping there is no case id, so there is no event log — and that is reported as
 * {@code available = false} with a reason, never as a process with zero cases. Same rule as
 * {@code SnapshotRead}, {@code TopicConsumers} and the activity series: a measurement that could not
 * be taken must not come back looking like a measurement of zero.
 *
 * @param available          false when no case id could be resolved for any record
 * @param unavailableReason  why, when {@code available} is false; null otherwise
 * @param cases              distinct correlation ids observed
 * @param events             records that carried one, i.e. the size of the event log
 * @param eventsWithoutCase  records that carried none — read, digested, and outside the log
 * @param windowStartMs      earliest event time in the log
 * @param windowEndMs        latest event time in the log
 * @param eventTimeSource    where the timestamps came from — see {@link TimeSource}
 * @param activities         every activity, most frequent first
 * @param edges              directly-follows relations with their latency distribution
 * @param variants           distinct end-to-end paths, most frequent first
 * @param starts             the activity each case began on, with case counts
 * @param ends               the activity each case last reached, with case counts
 * @param repeats            activities a case visited more than once — redelivery, or rework
 * @param spotlightCases     case ids chosen as worked examples for the prompt (see the builder)
 * @param variantsOmitted    variants past the reporting cap; their existence is stated, not hidden
 * @param edgesOmitted       edges past the reporting cap
 * @param notes              what the reader must not over-read — boundary effects, fallbacks, caps
 */
public record ProcessModel(
    boolean available,
    String unavailableReason,
    int cases,
    int events,
    int eventsWithoutCase,
    long windowStartMs,
    long windowEndMs,
    TimeSource eventTimeSource,
    List<Activity> activities,
    List<Edge> edges,
    List<Variant> variants,
    List<Endpoint> starts,
    List<Endpoint> ends,
    List<Repeat> repeats,
    List<String> spotlightCases,
    int variantsOmitted,
    int edgesOmitted,
    List<String> notes
) {

    /**
     * Which clock the event log was ordered by.
     *
     * <p>Worth reporting rather than assuming: a mapped business timestamp is what the process
     * actually happened at, while the broker's record timestamp is when it was produced, and the two
     * differ by exactly the amount that makes a latency finding interesting or meaningless. A run
     * that silently fell back to the second would present produce-time lag as process duration.
     */
    public enum TimeSource {
        /** The timestamp path declared in the field mapping, resolved for every event. */
        MAPPED_FIELD,
        /** The mapping declared one, but some events fell back to the broker's record timestamp. */
        MIXED,
        /** No timestamp was mapped, or none resolved: ordering is by Kafka record timestamp. */
        RECORD_TIMESTAMP
    }

    /** One node of the graph: a pipeline stage, optionally refined by its status. */
    public record Activity(String name, int occurrences, int cases) {
    }

    /**
     * One directly-follows relation, with what it cost.
     *
     * @param occurrences   how many times {@code from} was directly followed by {@code to}
     * @param cases         distinct cases that took this edge
     * @param p50Ms         median latency across those occurrences
     * @param p95Ms         95th percentile — the number an SLA is set against
     * @param maxMs         worst observed
     * @param outOfOrderCount occurrences the broker saw in the opposite order — the later event by
     *                      business time was produced first. Latencies here are non-negative by
     *                      construction, the log being sorted by event time, so a backwards hop
     *                      cannot show up as a negative duration; what it shows up as is this
     *                      disagreement between the two clocks. It is a finding about the estate
     *                      (a skewed producer, a back-dated event) rather than a glitch to hide,
     *                      which is why it is counted and reported rather than smoothed away —
     *                      the same call the Stream Flow trace makes for a backwards hop. Always
     *                      zero when the log is ordered by the record timestamp itself, since then
     *                      the two clocks are one.
     */
    public record Edge(String from, String to, int occurrences, int cases,
                       long p50Ms, long p95Ms, long maxMs, int outOfOrderCount) {
    }

    /**
     * One distinct path through the process, and how many cases took it.
     *
     * @param path  activities in order, first to last
     * @param cases how many cases followed exactly this sequence
     * @param example a case id that took it, so the claim is checkable against the cluster
     */
    public record Variant(List<String> path, int cases, String example) {
    }

    /** An activity that started or ended a case, with how many cases it accounted for. */
    public record Endpoint(String activity, int cases) {
    }

    /**
     * An activity some case visited more than once.
     *
     * <p>Deliberately named for what was observed rather than for what it means: the same
     * {@code (case, activity)} pair twice is a redelivery, a retry or a legitimate rework loop, and
     * which one it is depends on the business — that is the model's half of the job, not this
     * one's.
     */
    public record Repeat(String activity, int casesAffected, int maxOccurrencesInOneCase) {
    }

    /** The shape a run with no resolvable case id reports, so its reason travels with it. */
    public static ProcessModel unavailable(String reason, int eventsWithoutCase) {
        return new ProcessModel(false, reason, 0, 0, eventsWithoutCase, 0L, 0L,
            TimeSource.RECORD_TIMESTAMP, List.of(), List.of(), List.of(), List.of(), List.of(),
            List.of(), List.of(), 0, 0, List.of());
    }
}
