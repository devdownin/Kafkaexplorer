// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors
package com.compagnonsdudev.kafkasqlexplorer.forecast;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import static com.compagnonsdudev.kafkasqlexplorer.forecast.PreparedMetricSeries.Status.*;
import static com.compagnonsdudev.kafkasqlexplorer.forecast.SeriesPreparationProfile.*;
import static com.compagnonsdudev.kafkasqlexplorer.forecast.SeriesPreparationProfile.Transformation.*;

/** Pure, bounded, causal reconstruction. No Kafka reads, clocks, SQL or model invocation. */
public final class MetricSeriesPreparer {
    public PreparedMetricSeries prepare(String seriesId, String definitionVersion, String unit, long cutoff,
                                        SeriesPreparationProfile profile, List<MetricObservation> input) {
        requireText(seriesId); requireText(definitionVersion); requireText(unit);
        Objects.requireNonNull(profile); Objects.requireNonNull(input);
        long from = profile.startAt(cutoff), to = profile.endAt(cutoff), readFrom = profile.readFrom(cutoff);
        String policy = policyFingerprint(seriesId, definitionVersion, unit, profile);
        // A full bounded store response might be truncated. Never certify its partial buckets.
        if (input.size() > MAX_INPUT_OBSERVATIONS) {
            return historyLimit(seriesId, definitionVersion, unit, cutoff, profile);
        }
        var rows = input.stream().filter(p -> p.observedAt() >= readFrom && p.observedAt() < to)
            .sorted(Comparator.comparingLong(MetricObservation::observedAt)
                .thenComparing(MetricObservation::observationId)).toList();
        Map<String, MetricObservation> unique = new HashMap<>();
        Map<Long, String> timestamps = new HashMap<>();
        var selected = new ArrayList<MetricObservation>();
        var status = READY;
        String reason = "Complete regular context";
        MetricObservation reference = null;
        for (var o : rows) {
            if (!seriesId.equals(o.seriesId()) || !definitionVersion.equals(o.definitionVersion())
                || !unit.equals(o.unit()) || !seriesId.equals(MetricObservation.seriesId(o.clusterId(),
                    o.definitionVersion(), o.metricId(), o.component(), o.labels()))
                || (reference != null && !reference.semanticKind().equals(o.semanticKind()))) {
                status = SCOPE_CHANGED; reason = "Series identity, definition, unit or semantics changed"; break;
            }
            reference = o;
            var previous = unique.putIfAbsent(o.observationId(), o);
            if (previous != null) {
                if (previous.equals(o)) continue;
                status = INVALID_DATA; reason = "Conflicting payloads for one observation identity"; break;
            }
            if (timestamps.putIfAbsent(o.observedAt(), o.observationId()) != null) {
                status = INVALID_DATA; reason = "Multiple collection runs share a timestamp"; break;
            }
            if (!compatible(o, profile) || (o.value() == null ? !"UNMEASURED".equals(o.qualityState())
                : !"OBSERVED".equals(o.qualityState()))) {
                status = INVALID_DATA; reason = "Unverified quality or incompatible transformation"; break;
            }
            selected.add(o);
        }
        String fingerprint = MetricObservation.digest(selected);
        if ("UNKNOWN".equalsIgnoreCase(unit) || unit.isBlank()) {
            status = INVALID_DATA; reason = "An explicit source unit is required";
        }
        if (status != READY) return result(status, reason, seriesId, definitionVersion, unit,
            from, to, profile, policy, fingerprint, List.of(), 0);

        int offset = profile.transformation() == COUNTER_RATE ? 1 : 0;
        var buckets = new ArrayList<List<MetricObservation>>();
        for (int i = 0; i < profile.contextPoints() + offset; i++) buckets.add(new ArrayList<>());
        Double previousCounter = null;
        for (var o : selected) {
            if (o.value() == null) continue;
            if (profile.transformation() == COUNTER_RATE) {
                if (o.value() < 0 || (previousCounter != null && o.value() < previousCounter)) {
                    return result(COUNTER_RESET, "Counter decreased; context crosses a reset", seriesId,
                        definitionVersion, unit, from, to, profile, policy, fingerprint, List.of(), 0);
                }
                previousCounter = o.value();
            }
            buckets.get((int) ((o.observedAt() - readFrom) / profile.stepMillis())).add(o);
        }
        var points = new ArrayList<PreparedMetricSeries.Point>();
        for (int i = 0; i < profile.contextPoints(); i++) {
            var bucket = buckets.get(i + offset);
            Double value = null;
            if (!bucket.isEmpty()) {
                value = switch (profile.transformation()) {
                    case GAUGE_LAST -> bucket.getLast().value();
                    case GAUGE_MAX -> bucket.stream().mapToDouble(MetricObservation::value).max().orElseThrow();
                    case GAUGE_MEAN -> bucket.stream().mapToDouble(p -> p.value() / bucket.size()).sum();
                    case COUNTER_RATE -> {
                        var prior = buckets.get(i);
                        if (prior.isEmpty()) yield null;
                        var a = prior.getLast(); var b = bucket.getLast();
                        yield (b.value() - a.value()) / ((b.observedAt() - a.observedAt()) / 1000.0);
                    }
                };
            }
            if (value != null && !Double.isFinite(value)) return result(INVALID_DATA,
                "Transformation produced a non-finite value", seriesId, definitionVersion, unit,
                from, to, profile, policy, fingerprint, List.of(), 0);
            points.add(new PreparedMetricSeries.Point(from + (i + 1L) * profile.stepMillis(),
                value, false, bucket.size()));
        }
        // A context that has not filled yet starts at its first value, once that leaves enough of
        // one: the empty prefix is history that does not exist, not a gap in history that does.
        int first = 0;
        while (first < points.size() && points.get(first).value() == null) first++;
        if (first > 0 && first < points.size() && points.size() - first >= MIN_CONTEXT_POINTS) {
            points = new ArrayList<>(points.subList(first, points.size()));
            from = Math.addExact(from, Math.multiplyExact((long) first, profile.stepMillis()));
        }
        int missing = (int) points.stream().filter(p -> p.value() == null).count();
        int trailing = 0;
        for (int i = points.size() - 1; i >= 0 && points.get(i).value() == null; i--) trailing++;
        int longest = 0, consecutive = 0;
        for (var p : points) {
            consecutive = p.value() == null ? consecutive + 1 : 0;
            longest = Math.max(longest, consecutive);
        }
        if (selected.isEmpty()) { status = WARMING_UP; reason = "No observations in the requested context"; }
        else if (trailing > (offset == 1 ? 0 : MAX_GAP_STEPS)) {
            status = STALE; reason = "Latest complete windows have no usable observation";
        } else if (points.getFirst().value() == null) {
            status = WARMING_UP;
            reason = "Collecting history: " + (points.size() - first) + " of the "
                + Math.min(MIN_CONTEXT_POINTS, profile.contextPoints()) + " points a first forecast needs";
        } else if (longest > MAX_GAP_STEPS || missing > points.size() * MAX_MISSING_FRACTION
            || (offset == 1 && missing > 0)) {
            status = INSUFFICIENT_HISTORY; reason = "Gaps exceed the preparation policy";
        }
        int imputed = 0;
        if (status == READY && missing > 0) {
            Double last = null;
            for (int i = 0; i < points.size(); i++) {
                var p = points.get(i);
                if (p.value() != null) last = p.value();
                else { points.set(i, new PreparedMetricSeries.Point(p.endAt(), last, true, 0)); imputed++; }
            }
            reason = "Short gauge gaps filled using past values only";
        }
        if (status == READY && points.size() < profile.contextPoints())
            reason = "Partial context: " + points.size() + " of " + profile.contextPoints()
                + " points collected so far; " + reason.substring(0, 1).toLowerCase(java.util.Locale.ROOT) + reason.substring(1);
        return result(status, reason, seriesId, definitionVersion, unit, from, to,
            profile, policy, fingerprint, points, imputed);
    }

    PreparedMetricSeries historyLimit(String seriesId, String version, String unit, long cutoff,
                                     SeriesPreparationProfile profile) {
        return result(HISTORY_LIMIT, "Observation count or payload budget reached; no partial context is usable",
            seriesId, version, unit, profile.startAt(cutoff), profile.endAt(cutoff), profile,
            policyFingerprint(seriesId, version, unit, profile), "", List.of(), 0);
    }

    /**
     * Still {@code preparation-v1} although partial contexts are now admitted: a context whose first
     * bucket holds a value is prepared exactly as before, and a new label would have orphaned every
     * quality window already accumulated, for series whose inputs did not change.
     */
    private String policyFingerprint(String seriesId, String version, String unit, SeriesPreparationProfile profile) {
        return MetricObservation.digest(List.of("preparation-v1", seriesId, version, unit, profile,
            MAX_GAP_STEPS, MAX_MISSING_FRACTION, "causal-carry"));
    }

    private boolean compatible(MetricObservation o, SeriesPreparationProfile p) {
        if (p.transformation() == COUNTER_RATE) return "COUNTER".equals(o.semanticKind()) && "value".equals(o.component());
        boolean gauge = "GAUGE".equals(o.semanticKind()) || (!"value".equals(o.component())
            && ("SUMMARY".equals(o.semanticKind()) || "HISTOGRAM".equals(o.semanticKind())));
        // Means of window percentiles/averages do not reconstruct a global percentile/average.
        return gauge && (p.transformation() != GAUGE_MEAN
            || !(o.component().startsWith("p95") || o.component().startsWith("avg") || "matchRate".equals(o.component())));
    }

    private PreparedMetricSeries result(PreparedMetricSeries.Status status, String reason, String seriesId,
        String version, String unit, long from, long to, SeriesPreparationProfile profile, String policy,
        String fingerprint, List<PreparedMetricSeries.Point> points, int imputed) {
        int observed = (int) points.stream().filter(p -> p.value() != null && !p.imputed()).count();
        int length = points.isEmpty() ? profile.contextPoints() : points.size();
        return new PreparedMetricSeries(status, reason, seriesId, version, unit,
            profile.transformation() == COUNTER_RATE ? unit + "/second" : unit,
            from, to, profile, policy, fingerprint, observed, length - observed, imputed, points);
    }

    private static void requireText(String value) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException("Series, version and unit are required");
    }
}
