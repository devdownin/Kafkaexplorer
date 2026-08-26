// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors
package com.compagnonsdudev.kafkasqlexplorer.service;

import com.compagnonsdudev.kafkasqlexplorer.config.ProcessMiningConfig;
import com.compagnonsdudev.kafkasqlexplorer.domain.FieldMapping;
import com.compagnonsdudev.kafkasqlexplorer.domain.PayloadDigest;
import com.compagnonsdudev.kafkasqlexplorer.domain.ProcessModel;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Turns a window of digests into the {@link ProcessModel} — the event log's aggregate.
 *
 * <p>Pure: it reads nothing, calls nothing, and two runs over the same digests produce the same
 * model. That is the point of it existing as its own class rather than as a method on
 * {@code LlmAnalysisService}. Everything it computes used to be asked of the model, from a sample
 * that could not support the question; here it is counting, sorting and set difference, so it can be
 * pinned by tests instead of judged by reading an answer.
 *
 * <h2>What counts as an activity</h2>
 *
 * <p>A Kafka topic is the pipeline stage, so the topic is the activity — {@code
 * demo.orders.1.received} → {@code 2.validated} → {@code 3.enriched} is the process, spelled out in
 * the topic names. The exception is a topic the operator mapped a <em>status</em> for: mapping one
 * in step 3 is a deliberate statement that the status is what moves, which is the shape of an
 * event-sourced stream where every event lands on one topic and only the status separates them.
 * There the activity is {@code topic · STATUS}.
 *
 * <p>That is bounded rather than trusted. The profiling prompt defines a status as "valeurs d'un
 * ensemble fermé"; a field that turns out to carry a hundred distinct values is not a status, and
 * multiplying it into the graph would turn six nodes into six hundred and make the flowchart
 * useless. Past {@code process-mining.max-status-activities} the topic falls back to being its own
 * activity, and the model is told which topic that happened to — a silent fallback would present a
 * collapsed graph as the process.
 *
 * <h2>What it refuses to do</h2>
 *
 * <p>There is no notion of a "terminal" activity here, and no case is labelled abandoned. Which
 * activity ought to end a process is a business fact this application does not have, and deriving
 * it from the data is circular — the end activities are the ones that end cases. What is reported
 * instead is the <em>distribution</em>: how many cases ended on each activity. That 91 % end on
 * {@code enriched} and 7 % on {@code validated} is an observation; that the 7 % are stuck is the
 * reading, and the reading is the model's half of the work.
 *
 * <p>The same restraint governs the window boundary, which is the trap this whole measurement walks
 * into if nobody names it: a snapshot manufactures incomplete cases at both ends. A case whose first
 * event predates the read is missing its start; one still running when the read stopped is missing
 * its end. Neither is a process defect and both look exactly like one. The boundary is therefore
 * stated in {@code notes}, and the model is told not to read an early ending as an orphan without
 * checking it against the window.
 */
@Service
public class ProcessModelBuilder {

    private final ProcessMiningConfig config;

    public ProcessModelBuilder(ProcessMiningConfig config) {
        this.config = config;
    }

    /**
     * The case id of one record under a mapping, or {@code null} when it carries none.
     *
     * <p>Public because {@code LlmAnalysisService} groups digests by case to pick the worked
     * examples it inlines, and resolving the same value a second way is how two answers to one
     * question begin. It is the single definition of "which case does this record belong to".
     */
    public static String caseIdOf(PayloadDigest digest, FieldMapping mapping) {
        return mappedValue(digest, mapping == null ? null : mapping.correlationIdPaths());
    }

    /**
     * The business timestamp this record declares, or {@code null} when it declares none this side
     * can resolve. Kept apart from {@link #eventTimeOf} because the difference is reportable: a run
     * that fell back to the broker's clock measures produce time, not event time.
     */
    public static Long mappedEventTime(PayloadDigest digest, FieldMapping mapping) {
        return EventTime.toEpochMillis(
            mappedValue(digest, mapping == null ? null : mapping.timestampPaths()));
    }

    /** When this event happened: the mapped business time, else the broker's record timestamp. */
    public static long eventTimeOf(PayloadDigest digest, FieldMapping mapping) {
        Long mapped = mappedEventTime(digest, mapping);
        return mapped == null ? digest.timestamp() : mapped;
    }

    /**
     * The order the events of one case happened in — the single definition of it.
     *
     * <p>Partition and offset break a timestamp tie, so two runs over one window order the log
     * identically. Without them a burst sharing a millisecond draws a different graph each time,
     * which is the defect the Stream Flow chain rule was written for. {@code LlmAnalysisService}
     * sorts the traces it inlines with this same comparator: a worked example that contradicted the
     * graph above it would be worse than no example at all.
     */
    public static Comparator<PayloadDigest> chronological(FieldMapping mapping) {
        return Comparator.comparingLong((PayloadDigest d) -> eventTimeOf(d, mapping))
            .thenComparingInt(PayloadDigest::partition)
            .thenComparingLong(PayloadDigest::offset);
    }

    /**
     * Groups records into cases, dropping those that carry no correlation id.
     *
     * <p>Shared with {@code LlmAnalysisService}, which needs the same grouping to inline the worked
     * examples this model nominates.
     */
    public static Map<String, List<PayloadDigest>> groupByCase(List<PayloadDigest> digests,
                                                                FieldMapping mapping) {
        Map<String, List<PayloadDigest>> byCase = new LinkedHashMap<>();
        for (PayloadDigest digest : digests == null ? List.<PayloadDigest>of() : digests) {
            String caseId = caseIdOf(digest, mapping);
            if (caseId != null) {
                byCase.computeIfAbsent(caseId, c -> new ArrayList<>()).add(digest);
            }
        }
        byCase.values().forEach(trace -> trace.sort(chronological(mapping)));
        return byCase;
    }

    /** Builds the model, or reports why there is none. */
    public ProcessModel build(List<PayloadDigest> digests, FieldMapping mapping) {
        List<PayloadDigest> records = digests == null ? List.of() : digests;
        if (mapping == null || mapping.correlationIdPaths() == null
                || mapping.correlationIdPaths().isEmpty()) {
            return ProcessModel.unavailable(
                "No correlation id is mapped, so the records cannot be grouped into cases. "
                    + "Validate a field mapping (step 3) to obtain sequences, latencies and "
                    + "incomplete flows.", records.size());
        }

        // ---- 1. group into cases, ordered; note what did not resolve
        Map<String, List<PayloadDigest>> byCase = groupByCase(records, mapping);
        int eventCount = byCase.values().stream().mapToInt(List::size).sum();
        int withoutCase = records.size() - eventCount;

        Map<String, Set<String>> statusesByTopic = new LinkedHashMap<>();
        int mappedTimes = 0;
        boolean anyTimestampMapped = mapping.timestampPaths() != null
            && !mapping.timestampPaths().isEmpty();
        for (List<PayloadDigest> trace : byCase.values()) {
            for (PayloadDigest digest : trace) {
                String status = canonicalStatus(mappedValue(digest, mapping.statusPaths()),
                    digest.topic(), mapping);
                if (status != null) {
                    statusesByTopic.computeIfAbsent(digest.topic(), t -> new LinkedHashSet<>())
                        .add(status);
                }
                if (mappedEventTime(digest, mapping) != null) {
                    mappedTimes++;
                }
            }
        }

        if (byCase.isEmpty()) {
            return ProcessModel.unavailable(
                "A correlation id is mapped, but no record carried a value at that path. "
                    + "Check the mapping against the payloads — the analysis can describe topics, "
                    + "not flows, until it can group records into cases.", withoutCase);
        }

        List<String> notes = new ArrayList<>();

        // ---- 2. decide, per topic, whether the status refines the activity
        Set<String> statusTopics = new LinkedHashSet<>();
        List<String> collapsed = new ArrayList<>();
        statusesByTopic.forEach((topic, statuses) -> {
            if (statuses.size() <= config.getMaxStatusActivities()) {
                statusTopics.add(topic);
            } else {
                collapsed.add(topic + " (" + statuses.size() + " distinct values)");
            }
        });
        if (!collapsed.isEmpty()) {
            notes.add("The mapped status carries too many distinct values to be a process step on "
                + String.join(", ", collapsed)
                + "; those topics are one activity each, so their internal transitions are not in "
                + "this graph.");
        }

        // ---- 3. count
        Map<String, int[]> activityCounts = new LinkedHashMap<>();      // name -> [occurrences]
        Map<String, Set<String>> activityCases = new LinkedHashMap<>();
        Map<String, EdgeStats> edgeStats = new LinkedHashMap<>();
        Map<String, VariantStats> variantStats = new LinkedHashMap<>();
        Map<String, Integer> startCounts = new TreeMap<>();
        Map<String, Integer> endCounts = new TreeMap<>();
        Map<String, RepeatStats> repeatStats = new LinkedHashMap<>();
        long windowStart = Long.MAX_VALUE;
        long windowEnd = Long.MIN_VALUE;

        for (Map.Entry<String, List<PayloadDigest>> entry : byCase.entrySet()) {
            String caseId = entry.getKey();
            List<PayloadDigest> trace = entry.getValue();
            List<String> path = new ArrayList<>(trace.size());
            long[] times = new long[trace.size()];
            Map<String, Integer> seenInCase = new LinkedHashMap<>();

            for (int i = 0; i < trace.size(); i++) {
                String activity = activityOf(trace.get(i), mapping, statusTopics);
                times[i] = eventTimeOf(trace.get(i), mapping);
                path.add(activity);
                activityCounts.computeIfAbsent(activity, a -> new int[1])[0]++;
                activityCases.computeIfAbsent(activity, a -> new LinkedHashSet<>()).add(caseId);
                seenInCase.merge(activity, 1, Integer::sum);
                windowStart = Math.min(windowStart, times[i]);
                windowEnd = Math.max(windowEnd, times[i]);
            }

            for (int i = 1; i < trace.size(); i++) {
                String from = path.get(i - 1);
                String to = path.get(i);
                // The composite key is joined on NUL — no activity name can contain one — written
                // as the escape and never as the byte: a literal NUL in a source file makes `file`
                // report it as data and takes it out of every `grep -r`, this repository's own doc
                // checks included.
                edgeStats.computeIfAbsent(from + '\0' + to, k -> new EdgeStats(from, to))
                    .add(times[i] - times[i - 1], caseId,
                        trace.get(i).timestamp() < trace.get(i - 1).timestamp());
            }

            startCounts.merge(path.get(0), 1, Integer::sum);
            endCounts.merge(path.get(path.size() - 1), 1, Integer::sum);

            seenInCase.forEach((activity, count) -> {
                if (count > 1) {
                    repeatStats.computeIfAbsent(activity, RepeatStats::new).add(count);
                }
            });

            variantStats.computeIfAbsent(String.join(" → ", path), k -> new VariantStats(path))
                .add(caseId);
        }

        // ---- 5. rank, cap, and say what the cap dropped
        List<ProcessModel.Activity> activities = activityCounts.entrySet().stream()
            .map(e -> new ProcessModel.Activity(e.getKey(), e.getValue()[0],
                activityCases.get(e.getKey()).size()))
            .sorted(Comparator.comparingInt(ProcessModel.Activity::occurrences).reversed()
                .thenComparing(ProcessModel.Activity::name))
            .limit(config.getMaxEdgesInPrompt())
            .toList();

        List<EdgeStats> rankedEdges = edgeStats.values().stream()
            .sorted(Comparator.comparingInt((EdgeStats e) -> e.occurrences).reversed()
                .thenComparing(e -> e.from + e.to))
            .toList();
        List<ProcessModel.Edge> edges = rankedEdges.stream()
            .limit(config.getMaxEdgesInPrompt())
            .map(EdgeStats::toEdge)
            .toList();

        List<VariantStats> rankedVariants = variantStats.values().stream()
            .sorted(Comparator.comparingInt((VariantStats v) -> v.cases.size()).reversed()
                .thenComparing(v -> String.join("→", v.path)))
            .toList();
        List<ProcessModel.Variant> variants = selectVariants(rankedVariants).stream()
            .map(v -> new ProcessModel.Variant(v.path, v.cases.size(), v.cases.get(0)))
            .toList();

        ProcessModel.TimeSource timeSource = !anyTimestampMapped || mappedTimes == 0
            ? ProcessModel.TimeSource.RECORD_TIMESTAMP
            : (mappedTimes == eventCount
                ? ProcessModel.TimeSource.MAPPED_FIELD
                : ProcessModel.TimeSource.MIXED);

        notes.addAll(timeNotes(timeSource, eventCount - mappedTimes));
        notes.add("The window is a slice: a case whose first event predates it is missing its "
            + "start, and one still running when the read stopped is missing its end. Neither is a "
            + "process defect, and both look like one — check an early ending against the window "
            + "bounds before calling it an orphan.");
        if (withoutCase > 0) {
            notes.add(withoutCase + " record(s) carried no value at the mapped correlation path and "
                + "are outside this log entirely — they are not counted anywhere above.");
        }

        return new ProcessModel(true, null,
            byCase.size(), eventCount, withoutCase,
            windowStart, windowEnd, timeSource,
            activities, edges, variants,
            endpoints(startCounts), endpoints(endCounts),
            repeatStats.values().stream()
                .map(RepeatStats::toRepeat)
                .sorted(Comparator.comparingInt(ProcessModel.Repeat::casesAffected).reversed()
                    .thenComparing(ProcessModel.Repeat::activity))
                .toList(),
            spotlightCases(rankedVariants),
            Math.max(0, rankedVariants.size() - variants.size()),
            Math.max(0, rankedEdges.size() - edges.size()),
            List.copyOf(notes));
    }

    /**
     * Which variants to report, and — the part that matters — from both ends of the distribution.
     *
     * <p>Ranking by frequency and taking the top N is the obvious rule and it drops precisely what
     * an audit is looking for: the nominal path is the one variant everybody already knows, while
     * the deviation that four cases in nine hundred took is the finding. So half the budget goes to
     * the most frequent variants and half to the rarest, which is also what makes the worked
     * examples in the prompt cover "how it usually runs" and "how it goes wrong" rather than eleven
     * near-identical happy paths.
     */
    private List<VariantStats> selectVariants(List<VariantStats> ranked) {
        int cap = config.getMaxVariantsInPrompt();
        if (ranked.size() <= cap) {
            return ranked;
        }
        int head = (cap + 1) / 2;
        int tail = cap - head;
        List<VariantStats> selected = new ArrayList<>(ranked.subList(0, head));
        selected.addAll(ranked.subList(ranked.size() - tail, ranked.size()));
        return selected;
    }

    /**
     * The case ids whose full traces the prompt inlines — W2's half of the change.
     *
     * <p>One case per selected variant, in the same order, so the examples are diverse by
     * construction: a variant <em>is</em> a distinct path, so covering variants covers the branch
     * that skipped a step, the case that looped, and the one that stopped early, without any of
     * them needing to be detected first.
     */
    private List<String> spotlightCases(List<VariantStats> ranked) {
        List<String> cases = new ArrayList<>();
        for (VariantStats variant : selectVariants(ranked)) {
            if (cases.size() >= config.getMaxTraceCasesInPrompt()) {
                break;
            }
            cases.add(variant.cases.get(0));
        }
        return List.copyOf(cases);
    }

    private static List<ProcessModel.Endpoint> endpoints(Map<String, Integer> counts) {
        return counts.entrySet().stream()
            .map(e -> new ProcessModel.Endpoint(e.getKey(), e.getValue()))
            .sorted(Comparator.comparingInt(ProcessModel.Endpoint::cases).reversed()
                .thenComparing(ProcessModel.Endpoint::activity))
            .toList();
    }

    private static List<String> timeNotes(ProcessModel.TimeSource source, int fallbacks) {
        return switch (source) {
            case MAPPED_FIELD -> List.of();
            case RECORD_TIMESTAMP -> List.of(
                "No business timestamp resolved, so the log is ordered by the broker's record "
                    + "timestamp — produce time, not event time. Latencies below are transport "
                    + "delays between stages and not necessarily the process's own durations.");
            case MIXED -> List.of(fallbacks + " event(s) had no resolvable value at the mapped "
                + "timestamp path and fell back to the broker's record timestamp, so a few "
                + "latencies mix event time with produce time.");
        };
    }

    /**
     * What separates a topic from the status that refines it, in an activity label.
     *
     * <p>Public because the label travels: an activity reaches the Metrics page and comes back as
     * evidence for a KPI, which needs the topic rather than the label. Reading it apart there would
     * be a second copy of this rule, and a second copy is how the two come to disagree — the same
     * argument that produced {@code SecureXml} and {@code EventTime}.
     */
    public static final String ACTIVITY_SEPARATOR = " \u00b7 ";

    /** The graph node this record lands on — see the class comment for why a status may refine it. */
    private static String activityOf(PayloadDigest digest, FieldMapping mapping,
                                     Set<String> statusTopics) {
        if (!statusTopics.contains(digest.topic())) {
            return digest.topic();
        }
        String status = canonicalStatus(mappedValue(digest, mapping.statusPaths()),
            digest.topic(), mapping);
        return status == null ? digest.topic() : digest.topic() + ACTIVITY_SEPARATOR + status;
    }

    /**
     * The topic an activity label names.
     *
     * <p>A topic name cannot contain the separator (Kafka accepts {@code [a-zA-Z0-9._-]} only), so
     * the split is unambiguous in the direction that matters. A label with no separator is already
     * a topic and is returned as it is.
     */
    public static String topicOf(String activity) {
        if (activity == null) return null;
        int at = activity.indexOf(ACTIVITY_SEPARATOR);
        return at < 0 ? activity : activity.substring(0, at);
    }

    /** The value this record carries at the path the mapping declares for its topic. */
    private static String mappedValue(PayloadDigest digest, Map<String, String> pathsByTopic) {
        if (pathsByTopic == null || digest.fields() == null) {
            return null;
        }
        String rawPath = pathsByTopic.get(digest.topic());
        if (rawPath == null || rawPath.isBlank()) {
            return null;
        }
        String value = digest.fields().get(PayloadDigestService.normalizePath(rawPath));
        return value == null || value.isBlank() ? null : value;
    }

    /**
     * A status, normalised for comparison.
     *
     * <p>Case and surrounding space are the ordinary noise ({@code shipped} / {@code SHIPPED} are one
     * step, and being told they are two is worse than useless). {@code statusEquivalences} is
     * honoured on top of that. Its declared shape is ambiguous — the record's comment says
     * "canonical → aliases" over a doubly-nested map — and nothing in the UI produces one today, so
     * both readings are accepted rather than one being guessed at: an outer key naming a topic
     * scopes the aliases to it, any other outer key is treated as global.
     */
    private static String canonicalStatus(String status, String topic, FieldMapping mapping) {
        if (status == null) {
            return null;
        }
        String normalised = status.trim().toUpperCase(java.util.Locale.ROOT);
        Map<String, Map<String, List<String>>> equivalences = mapping.statusEquivalences();
        if (equivalences == null || equivalences.isEmpty()) {
            return normalised;
        }
        for (Map.Entry<String, Map<String, List<String>>> scope : equivalences.entrySet()) {
            boolean scoped = scope.getKey() != null
                && mapping.statusPaths() != null
                && mapping.statusPaths().containsKey(scope.getKey());
            if (scoped && !scope.getKey().equals(topic)) {
                continue;
            }
            if (scope.getValue() == null) {
                continue;
            }
            for (Map.Entry<String, List<String>> group : scope.getValue().entrySet()) {
                if (group.getValue() == null) {
                    continue;
                }
                for (String alias : group.getValue()) {
                    if (alias != null && alias.trim().equalsIgnoreCase(normalised)) {
                        return group.getKey().trim().toUpperCase(java.util.Locale.ROOT);
                    }
                }
            }
        }
        return normalised;
    }

    // ────────────────────────────────────────────────────────────────── accumulators


    private static final class EdgeStats {
        private final String from;
        private final String to;
        private final List<Long> latencies = new ArrayList<>();
        private final Set<String> cases = new LinkedHashSet<>();
        private int occurrences;
        private int outOfOrder;

        EdgeStats(String from, String to) {
            this.from = from;
            this.to = to;
        }

        void add(long latencyMs, String caseId, boolean producedOutOfOrder) {
            occurrences++;
            latencies.add(latencyMs);
            cases.add(caseId);
            if (producedOutOfOrder) {
                outOfOrder++;
            }
        }

        ProcessModel.Edge toEdge() {
            long[] sorted = latencies.stream().mapToLong(Long::longValue).sorted().toArray();
            return new ProcessModel.Edge(from, to, occurrences, cases.size(),
                percentile(sorted, 50), percentile(sorted, 95), sorted[sorted.length - 1],
                outOfOrder);
        }

        /** Nearest-rank over the observations as they are; no interpolation, no trimming. */
        private static long percentile(long[] sorted, int percentile) {
            int rank = (int) Math.ceil(percentile / 100.0 * sorted.length);
            return sorted[Math.min(sorted.length - 1, Math.max(0, rank - 1))];
        }
    }

    private static final class VariantStats {
        private final List<String> path;
        private final List<String> cases = new ArrayList<>();

        VariantStats(List<String> path) {
            this.path = List.copyOf(path);
        }

        void add(String caseId) {
            cases.add(caseId);
        }
    }

    private static final class RepeatStats {
        private final String activity;
        private int casesAffected;
        private int maxInOneCase;

        RepeatStats(String activity) {
            this.activity = activity;
        }

        void add(int occurrencesInOneCase) {
            casesAffected++;
            maxInOneCase = Math.max(maxInOneCase, occurrencesInOneCase);
        }

        ProcessModel.Repeat toRepeat() {
            return new ProcessModel.Repeat(activity, casesAffected, maxInOneCase);
        }
    }
}
