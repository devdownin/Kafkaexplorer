// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors
package com.compagnonsdudev.kafkasqlexplorer.forecast;

import com.compagnonsdudev.kafkasqlexplorer.domain.MetricConfig;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.CopyOnWriteArrayList;
import static org.junit.jupiter.api.Assertions.*;

class MetricObservationJournalTest {
    private MetricHistoryProperties config() {
        var p = new MetricHistoryProperties();
        p.setEnabled(true); p.setClusterId("lab"); p.setCollectorId("explorer-a");
        p.setJdbcUrl("jdbc:postgresql://localhost/history"); p.setMetricIds(Set.of("m1"));
        return p;
    }
    private MetricConfig metric() {
        return new MetricConfig("m1", "Lag", "GAUGE", "SELECT 1 AS metric_value", null,
            null, null, null, null, null, List.of(), Map.of(), null, "CONSUMER_TIME_LAG",
            Map.of("topic", "orders", "group", "billing"), "TEMPLATE_BOUNDED_SCAN", null, List.of());
    }
    private static class Store implements MetricObservationStore {
        final List<MetricObservation> points = new CopyOnWriteArrayList<>();
        final CountDownLatch stored = new CountDownLatch(1);
        @Override public void append(List<MetricObservation> p) throws Exception { points.addAll(p); stored.countDown(); }
        @Override public List<MetricObservation> read(String s, long f, long t, int n) { return List.of(); }
        @Override public int purgeBefore(long t, int n) { return 0; }
    }

    @Test
    void capturesLabelsAndComponentsWithoutReusingFailedValues() throws Exception {
        var store = new Store(); var registry = new SimpleMeterRegistry();
        var journal = new MetricObservationJournal(config(), store, registry);
        journal.start();
        try {
            journal.capture(metric(), "broker:9092", List.of(new MetricObservationJournal.Sample(
                Map.of("topic", "orders", "group", "billing"), 1200.0)), Map.of("maxLagMs", 1200), false, 1000);
            assertTrue(store.stored.await(2, TimeUnit.SECONDS));
            journal.capture(metric(), "broker:9092", List.of(), Map.of(), true, 2000);
        } finally { journal.close(); }
        assertEquals(3, store.points.size());
        assertEquals(Map.of("topic", "orders", "group", "billing"), store.points.get(0).labels());
        assertEquals("milliseconds", store.points.get(0).unit());
        assertEquals("maxLagMs", store.points.get(1).component());
        assertNull(store.points.get(2).value());
        assertEquals("UNMEASURED", store.points.get(2).qualityState());
        assertEquals(2000, store.points.get(2).observedAt());
    }

    @Test
    void aBlockedDatabaseDoesNotBlockCaptureAndSaturationIsCounted() throws Exception {
        var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
        var store = new Store() {
            @Override public void append(List<MetricObservation> p) throws Exception {
                entered.countDown(); assertTrue(release.await(5, TimeUnit.SECONDS)); super.append(p);
            }
        };
        var registry = new SimpleMeterRegistry(); var p = config(); p.setQueueCapacity(1);
        var journal = new MetricObservationJournal(p, store, registry);
        var samples = List.of(new MetricObservationJournal.Sample(Map.of(), 0.0));
        journal.start();
        try {
            journal.capture(metric(), "broker", samples, Map.of(), false, 1000);
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            assertTimeoutPreemptively(java.time.Duration.ofSeconds(1), () -> {
                journal.capture(metric(), "broker", samples, Map.of(), false, 2000);
                journal.capture(metric(), "broker", samples, Map.of(), false, 3000);
            });
            assertEquals(1, registry.get("explorer_forecast_history_dropped_total").counter().count());
        } finally { release.countDown(); journal.close(); }
        assertEquals(2, store.points.size());
    }

    @Test
    void validatesOptInWithoutRequiringADatabaseWhenDisabled() {
        new MetricHistoryProperties().validateEnabled();
        var p = new MetricHistoryProperties(); p.setEnabled(true);
        assertThrows(IllegalArgumentException.class, p::validateEnabled);
        var valid = config(); assertDoesNotThrow(valid::validateEnabled);
    }

    private static MetricConfig lag(String id) {
        return new MetricConfig(id, "Lag " + id, "GAUGE", null, null, null, null, null, null, null, List.of(),
            Map.of(), null, "CONSUMER_TIME_LAG", Map.of("topic", "orders", "group", id), "TEMPLATE_BOUNDED_SCAN",
            null, List.of());
    }

    @Test
    void anEligibleMetricIsRecordedWithoutBeingListedAndRawSqlIsNot() {
        var journal = new MetricObservationJournal(config(), new Store(), new SimpleMeterRegistry());
        var raw = new MetricConfig("raw", "Raw", "GAUGE", "SELECT 1 AS metric_value", null, null, null, null,
            null, null, List.of(), Map.of(), null, "RAW_SQL", Map.of(), "SQL", null, List.of());
        assertTrue(journal.selects(lag("unlisted")));
        assertFalse(journal.selects(raw));
        var listedOnly = config(); listedOnly.setEnrollEligible(false);
        assertFalse(new MetricObservationJournal(listedOnly, new Store(), new SimpleMeterRegistry()).selects(lag("unlisted")));
    }

    @Test
    void eligibleEnrollmentStopsAtTheBoundItSharesWithListedMetrics() {
        var journal = new MetricObservationJournal(config(), new Store(), new SimpleMeterRegistry());
        for (int i = 0; i < MetricHistoryProperties.MAX_METRICS - 1; i++) assertTrue(journal.selects(lag("e" + i)));
        assertFalse(journal.selects(lag("one-too-many")));
        assertTrue(journal.selects(lag("e0")), "An enrolled metric stays enrolled");
        assertTrue(journal.selects(metric()), "A listed metric is never crowded out");
    }

    @Test
    void historyNeedsNoListedMetricWhenEligibleOnesAreRecorded() {
        var p = config(); p.setMetricIds(Set.of());
        assertDoesNotThrow(p::validateEnabled);
        p.setEnrollEligible(false);
        assertThrows(IllegalArgumentException.class, p::validateEnabled);
    }
}
