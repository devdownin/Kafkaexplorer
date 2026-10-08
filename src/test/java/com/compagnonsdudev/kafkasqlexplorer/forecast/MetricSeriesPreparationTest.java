// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors
package com.compagnonsdudev.kafkasqlexplorer.forecast;

import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;
import static com.compagnonsdudev.kafkasqlexplorer.forecast.SeriesPreparationProfile.Transformation.*;
import static com.compagnonsdudev.kafkasqlexplorer.forecast.PreparedMetricSeries.Status.*;

class MetricSeriesPreparationTest {
    private static final long STEP = 60_000;
    private final MetricSeriesPreparer preparer = new MetricSeriesPreparer();

    private MetricObservation point(String id, long at, double value, String kind) {
        return MetricObservation.create("cluster", "metric", "v1", "value", Map.of("topic", "orders"),
            "messages", kind, id, at, value, "OBSERVED", Map.of());
    }

    private List<MetricObservation> gauges(int n) {
        var result = new ArrayList<MetricObservation>();
        for (int i = 0; i < n; i++) result.add(point("run" + i, i * STEP + 1000, i, "GAUGE"));
        return result;
    }

    private PreparedMetricSeries prepare(List<MetricObservation> input, long cutoff, int n,
                                         SeriesPreparationProfile.Transformation transformation) {
        var reference = point("ref", 0, 0, transformation == COUNTER_RATE ? "COUNTER" : "GAUGE");
        return preparer.prepare(reference.seriesId(), "v1", "messages", cutoff,
            new SeriesPreparationProfile(STEP, n, transformation), input);
    }

    @Test
    void usesCompleteUtcBucketsAndIgnoresFutureDataInBacktests() {
        var rows = new ArrayList<>(gauges(20));
        rows.add(point("second", 2000, 10, "GAUGE"));
        rows.add(rows.getFirst()); // exact retry must not weight the mean twice
        rows.add(point("future", 20 * STEP, 999, "GAUGE"));
        java.util.Collections.reverse(rows);
        var actual = prepare(rows, 20 * STEP + 1234, 20, GAUGE_MEAN);
        assertEquals(READY, actual.status());
        assertEquals(20 * STEP, actual.toExclusive());
        assertEquals(0, actual.fromInclusive());
        assertEquals(5.0, actual.points().getFirst().value());
        assertEquals(STEP, actual.points().getFirst().endAt());
        assertEquals(2, actual.points().getFirst().samples());
        assertEquals(19.0, actual.points().getLast().value());
        var withoutFuture = rows.stream().filter(p -> p.observedAt() < 20 * STEP).toList();
        assertEquals(actual, prepare(withoutFuture, 20 * STEP + 1234, 20, GAUGE_MEAN));
    }

    @Test
    void carriesOnlyPastGaugeValuesForSmallGapsAndReportsImputation() {
        var rows = new ArrayList<>(gauges(20));
        rows.remove(10);
        var actual = prepare(rows, 20 * STEP, 20, GAUGE_LAST);
        assertEquals(READY, actual.status());
        assertEquals(19, actual.observedPoints());
        assertEquals(1, actual.missingPoints());
        assertEquals(1, actual.imputedPoints());
        assertEquals(9.0, actual.points().get(10).value());
        assertTrue(actual.points().get(10).imputed());
        assertEquals(0, actual.points().get(10).samples());
    }

    @Test
    void refusesTooManyMissingPointsLongHolesAndLeadingWarmup() {
        var rows = new ArrayList<>(gauges(20));
        rows.removeIf(p -> p.observedAt() / STEP == 3 || p.observedAt() / STEP == 8);
        assertEquals(INSUFFICIENT_HISTORY, prepare(rows, 20 * STEP, 20, GAUGE_MEAN).status());
        var longer = new ArrayList<>(gauges(100));
        longer.removeIf(p -> p.observedAt() / STEP >= 10 && p.observedAt() / STEP <= 12);
        assertEquals(INSUFFICIENT_HISTORY, prepare(longer, 100 * STEP, 100, GAUGE_MEAN).status());
        assertEquals(WARMING_UP, prepare(gauges(10), 10 * STEP, 20, GAUGE_LAST).status());
        assertEquals(STALE, prepare(gauges(10), 20 * STEP, 20, GAUGE_LAST).status());
        assertEquals(WARMING_UP, prepare(List.of(), 20 * STEP, 20, GAUGE_LAST).status());
    }

    @Test
    void counterRatesUseActualElapsedTimeRatherThanNominalCadence() {
        var rows = List.of(point("a", 1000, 10, "COUNTER"),
            point("b", 61_000, 130, "COUNTER"), point("c", 123_000, 254, "COUNTER"));
        var actual = prepare(rows, 180_000, 2, COUNTER_RATE);
        assertEquals(READY, actual.status());
        assertEquals("messages/second", actual.outputUnit());
        assertEquals(List.of(2.0, 2.0), actual.points().stream().map(PreparedMetricSeries.Point::value).toList());
        assertEquals(60_000, actual.fromInclusive());
    }

    @Test
    void detectsCounterResetAndNeverFillsCounterGaps() {
        var reset = List.of(point("a", 1000, 100, "COUNTER"),
            point("b", 61_000, 10, "COUNTER"), point("c", 123_000, 20, "COUNTER"));
        assertEquals(COUNTER_RESET, prepare(reset, 180_000, 2, COUNTER_RATE).status());
        var rows = new ArrayList<MetricObservation>();
        for (int i = 0; i <= 20; i++) rows.add(point("run" + i, i * STEP + 1000, i * 10, "COUNTER"));
        rows.remove(10);
        var gap = prepare(rows, 21 * STEP, 20, COUNTER_RATE);
        assertEquals(INSUFFICIENT_HISTORY, gap.status());
        assertEquals(0, gap.imputedPoints());
        assertNull(gap.points().get(9).value());
        assertNull(gap.points().get(10).value());
        // A reset hidden inside one bucket must also invalidate the context.
        var hidden = new ArrayList<>(reset);
        hidden.set(1, point("b", 61_000, 110, "COUNTER"));
        hidden.set(2, point("c", 123_000, 130, "COUNTER"));
        hidden.add(point("reset", 62_000, 5, "COUNTER"));
        assertEquals(COUNTER_RESET, prepare(hidden, 180_000, 2, COUNTER_RATE).status());
    }

    @Test
    void separatesScopeChangesUnverifiedQualityUnknownUnitAndSemanticMismatch() {
        var rows = gauges(20);
        var first = rows.getFirst();
        var changed = new ArrayList<>(rows);
        changed.set(1, MetricObservation.create("cluster", "metric", "v2", "value", first.labels(),
            "messages", "GAUGE", "edited", STEP + 1000, 42.0, "OBSERVED", Map.of()));
        assertEquals(SCOPE_CHANGED, prepare(changed, 20 * STEP, 20, GAUGE_LAST).status());
        var limited = new ArrayList<>(rows);
        limited.set(1, MetricObservation.create("cluster", "metric", "v1", "value", first.labels(),
            "messages", "GAUGE", "limited", STEP + 1000, 42.0, "LIMITED_SCOPE", Map.of()));
        assertEquals(INVALID_DATA, prepare(limited, 20 * STEP, 20, GAUGE_LAST).status());
        assertEquals(INVALID_DATA, preparer.prepare(first.seriesId(), "v1", "UNKNOWN", 20 * STEP,
            new SeriesPreparationProfile(STEP, 20, GAUGE_LAST), rows).status());
        assertEquals(INVALID_DATA, prepare(rows, 20 * STEP, 20, COUNTER_RATE).status());
    }

    @Test
    void distinguishesMaximumLastAndZeroAndFingerprintsPolicyAndInputs() {
        var rows = new ArrayList<>(gauges(20));
        rows.add(point("spike", 2000, 50, "GAUGE"));
        rows.add(point("zero", 3000, 0, "GAUGE"));
        var max = prepare(rows, 20 * STEP, 20, GAUGE_MAX);
        var last = prepare(rows, 20 * STEP, 20, GAUGE_LAST);
        assertEquals(50.0, max.points().getFirst().value());
        assertEquals(0.0, last.points().getFirst().value());
        assertNotEquals(max.profileFingerprint(), last.profileFingerprint());
        assertEquals(max.inputFingerprint(), last.inputFingerprint());
        assertNotEquals(max.inputFingerprint(), prepare(gauges(20), 20 * STEP, 20, GAUGE_MAX).inputFingerprint());
        assertThrows(UnsupportedOperationException.class, () -> max.points().clear());
    }

    @Test
    void rejectsAmbiguousSameTimestampAndConflictingRetryPayloads() {
        var rows = new ArrayList<>(gauges(20));
        rows.add(point("different", 1000, 99, "GAUGE"));
        assertEquals(INVALID_DATA, prepare(rows, 20 * STEP, 20, GAUGE_LAST).status());
        rows.removeLast();
        rows.add(point("run0", 1000, 99, "GAUGE"));
        assertEquals(INVALID_DATA, prepare(rows, 20 * STEP, 20, GAUGE_LAST).status());
    }

    @Test
    void acceptsExactlyTwoCausalGapStepsAtFivePercentIncludingTrailingGaps() {
        var rows = new ArrayList<>(gauges(40));
        rows.removeIf(p -> p.observedAt() / STEP == 10 || p.observedAt() / STEP == 11);
        var interior = prepare(rows, 40 * STEP, 40, GAUGE_LAST);
        assertEquals(READY, interior.status());
        assertEquals(2, interior.imputedPoints());
        assertEquals(9.0, interior.points().get(11).value());
        var trailing = prepare(gauges(38), 40 * STEP, 40, GAUGE_LAST);
        assertEquals(READY, trailing.status());
        assertEquals(37.0, trailing.points().getLast().value());
        assertTrue(trailing.points().getLast().imputed());
    }

    @Test
    void nullMeasurementIsAGapAndFlatCountersProduceRealZeroRates() {
        var rows = new ArrayList<>(gauges(20));
        rows.set(10, MetricObservation.create("cluster", "metric", "v1", "value", rows.getFirst().labels(),
            "messages", "GAUGE", "failed", 10 * STEP + 1000, null, "UNMEASURED", Map.of()));
        var actual = prepare(rows, 20 * STEP, 20, GAUGE_LAST);
        assertEquals(READY, actual.status());
        assertTrue(actual.points().get(10).imputed());
        assertEquals(9.0, actual.points().get(10).value());
        var counters = List.of(point("a", 1000, 10, "COUNTER"),
            point("b", 61_000, 10, "COUNTER"), point("c", 123_000, 10, "COUNTER"));
        assertEquals(List.of(0.0, 0.0), prepare(counters, 180_000, 2, COUNTER_RATE).points()
            .stream().map(PreparedMetricSeries.Point::value).toList());
        assertEquals(WARMING_UP, prepare(counters.subList(1, 3), 180_000, 2, COUNTER_RATE).status());
    }

    @Test
    void neverAveragesWindowPercentilesAndTreatsThemAsExplicitSampledComponents() {
        var rows = new ArrayList<MetricObservation>();
        for (int i = 0; i < 20; i++) rows.add(MetricObservation.create("cluster", "metric", "v1", "p95LatencyMs",
            Map.of(), "milliseconds", "SUMMARY", "run" + i, i * STEP + 1000, 100.0 + i, "OBSERVED", Map.of()));
        var id = rows.getFirst().seriesId();
        assertEquals(INVALID_DATA, preparer.prepare(id, "v1", "milliseconds", 20 * STEP,
            new SeriesPreparationProfile(STEP, 20, GAUGE_MEAN), rows).status());
        var last = preparer.prepare(id, "v1", "milliseconds", 20 * STEP,
            new SeriesPreparationProfile(STEP, 20, GAUGE_LAST), rows);
        assertEquals(READY, last.status());
        assertEquals(119.0, last.points().getLast().value());
        assertEquals("milliseconds", last.outputUnit());
    }

    @Test
    void rejectsUnitChangesEvenWhenStoredIdentityWasNotUpdated() {
        var rows = new ArrayList<>(gauges(20));
        rows.set(1, MetricObservation.create("cluster", "metric", "v1", "value", rows.getFirst().labels(),
            "seconds", "GAUGE", "changed-unit", STEP + 1000, 42.0, "OBSERVED", Map.of()));
        assertEquals(SCOPE_CHANGED, prepare(rows, 20 * STEP, 20, GAUGE_LAST).status());
    }

    @Test
    void validatesBoundsAndDoesNotAcceptSilentlyTruncatedHistory() {
        assertThrows(IllegalArgumentException.class, () -> new SeriesPreparationProfile(0, 512, GAUGE_LAST));
        assertThrows(IllegalArgumentException.class, () -> new SeriesPreparationProfile(STEP, 513, GAUGE_LAST));
        var rows = new ArrayList<MetricObservation>();
        for (int i = 0; i < MetricObservationStore.MAX_READ_OBSERVATIONS; i++) {
            rows.add(point("run" + i, 1000 + i, i, "GAUGE"));
        }
        assertEquals(HISTORY_LIMIT, prepare(rows, 20 * STEP, 20, GAUGE_MEAN).status());
    }

    private List<MetricObservation> gaugesFrom(int first, int last) {
        var result = new ArrayList<MetricObservation>();
        for (int i = first; i <= last; i++) result.add(point("run" + i, i * STEP + 1000, i, "GAUGE"));
        return result;
    }

    @Test
    void aContextStillFillingStartsAtItsFirstValueOnceItHoldsTheFloor() {
        var actual = prepare(gaugesFrom(312, 511), 512 * STEP, 512, GAUGE_MEAN);
        assertEquals(READY, actual.status());
        assertEquals(200, actual.points().size());
        assertEquals(312 * STEP, actual.fromInclusive());
        assertEquals(512 * STEP, actual.toExclusive());
        assertEquals(200, actual.observedPoints());
        assertEquals(0, actual.missingPoints(), "The prefix not yet collected is not a gap");
        assertTrue(actual.reason().startsWith("Partial context: 200 of 512 points"), actual.reason());
    }

    @Test
    void belowTheFloorAContextWaitsAndSaysHowFarItHasGot() {
        var actual = prepare(gaugesFrom(450, 511), 512 * STEP, 512, GAUGE_MEAN);
        assertEquals(WARMING_UP, actual.status());
        assertEquals("Collecting history: 62 of the 128 points a first forecast needs", actual.reason());
    }

    @Test
    void aFullContextIsPreparedExactlyAsBefore() {
        var full = prepare(gauges(512), 512 * STEP, 512, GAUGE_MEAN);
        assertEquals(READY, full.status());
        assertEquals(512, full.points().size());
        assertEquals("Complete regular context", full.reason());
    }
}
