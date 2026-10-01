// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors
package com.compagnonsdudev.kafkasqlexplorer.forecast;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.sql.DriverManager;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static com.compagnonsdudev.kafkasqlexplorer.forecast.SeriesPreparationProfile.Transformation.*;

class MetricSeriesPreparationServiceTest {
    private MetricObservation point(String run, long at, double value, String kind) {
        return MetricObservation.create("cluster", "m", "v1", "value", Map.of(), "messages", kind,
            run, at, value, "OBSERVED", Map.of());
    }

    @Test
    void reconstructsFromJdbcAndExportsAReproducibleVersionedCorpus() throws Exception {
        String url = "jdbc:h2:mem:" + java.util.UUID.randomUUID() + ";DB_CLOSE_DELAY=-1;MODE=PostgreSQL";
        var store = new JdbcMetricObservationStore(() -> DriverManager.getConnection(url));
        var rows = new ArrayList<MetricObservation>();
        for (int i = 0; i < 20; i++) if (i != 10) rows.add(point("run" + i, i * 60_000L + 1000, i, "GAUGE"));
        rows.add(point("future", 1_200_000, 999, "GAUGE"));
        store.append(rows);
        var service = new MetricSeriesPreparationService(store, new MetricSeriesPreparer());
        var profile = new SeriesPreparationProfile(60_000, 20, GAUGE_LAST);
        var prepared = service.prepare(rows.getFirst().seriesId(), "v1", "messages", 1_200_123, profile);
        String json = MetricSeriesCorpusExporter.toJson(prepared);
        var tree = new ObjectMapper().readTree(json);
        assertEquals(1, tree.path("schemaVersion").intValue());
        assertEquals("PREPARED_CONTEXT", tree.path("kind").textValue());
        assertEquals("READY", tree.path("series").path("status").textValue());
        assertEquals(20, tree.path("series").path("points").size());
        var filled = tree.path("series").path("points").get(10);
        assertEquals(9, filled.path("value").doubleValue());
        assertTrue(filled.path("imputed").booleanValue());
        assertEquals(1_200_000, tree.path("series").path("toExclusive").longValue());
        assertEquals(prepared, new ObjectMapper().treeToValue(tree.path("series"), PreparedMetricSeries.class));
        var reopened = new MetricSeriesPreparationService(new JdbcMetricObservationStore(() -> DriverManager.getConnection(url)),
            new MetricSeriesPreparer());
        assertEquals(json, MetricSeriesCorpusExporter.toJson(reopened.prepare(rows.getFirst().seriesId(),
            "v1", "messages", 1_200_123, profile)));
        var insufficient = service.prepare(rows.getFirst().seriesId(), "v1", "messages", 1_200_123,
            SeriesPreparationProfile.initial(GAUGE_LAST));
        assertThrows(IllegalArgumentException.class, () -> MetricSeriesCorpusExporter.toJson(insufficient));
    }

    @Test
    void initialProfileAcceptsTheDefaultThirtySecondCollectionCadence() throws Exception {
        String url = "jdbc:h2:mem:" + java.util.UUID.randomUUID() + ";DB_CLOSE_DELAY=-1;MODE=PostgreSQL";
        var store = new JdbcMetricObservationStore(() -> DriverManager.getConnection(url));
        var rows = new ArrayList<MetricObservation>();
        for (int i = 0; i < 1024; i++) rows.add(point("run" + i, i * 30_000L + 1000, i, "GAUGE"));
        store.append(rows);
        var service = new MetricSeriesPreparationService(store, new MetricSeriesPreparer());
        var actual = service.prepare(rows.getFirst().seriesId(), "v1", "messages", 512 * 60_000L,
            SeriesPreparationProfile.initial(GAUGE_MEAN));
        assertEquals(PreparedMetricSeries.Status.READY, actual.status());
        assertEquals(512, actual.observedPoints());
        assertEquals(0.5, actual.points().getFirst().value());
        assertEquals(1022.5, actual.points().getLast().value());
        assertEquals(2, actual.points().getLast().samples());
    }

    @Test
    void refusesOversizedRawPayloadsWithoutExportingAPartialContext() throws Exception {
        String url = "jdbc:h2:mem:" + java.util.UUID.randomUUID() + ";DB_CLOSE_DELAY=-1;MODE=PostgreSQL";
        var store = new JdbcMetricObservationStore(() -> DriverManager.getConnection(url));
        var rows = new ArrayList<MetricObservation>();
        var labels = Map.of("account", "x".repeat(80_000));
        for (int i = 0; i < 60; i++) rows.add(MetricObservation.create("cluster", "m", "v1", "value", labels,
            "messages", "GAUGE", "run" + i, i * 20_000L + 1000, (double) i, "OBSERVED", Map.of()));
        store.append(rows);
        var service = new MetricSeriesPreparationService(store, new MetricSeriesPreparer());
        var actual = service.prepare(rows.getFirst().seriesId(), "v1", "messages", 1_200_000,
            new SeriesPreparationProfile(60_000, 20, GAUGE_LAST));
        assertEquals(PreparedMetricSeries.Status.HISTORY_LIMIT, actual.status());
        assertTrue(actual.points().isEmpty());
        assertThrows(IllegalArgumentException.class, () -> MetricSeriesCorpusExporter.toJson(actual));
    }

    @Test
    void readsOneBoundedWindowWithCounterPredecessorAndPropagatesStoreFailures() throws Exception {
        var store = mock(MetricObservationStore.class);
        var service = new MetricSeriesPreparationService(store, new MetricSeriesPreparer());
        var reference = point("ref", 0, 0, "COUNTER");
        var profile = new SeriesPreparationProfile(60_000, 20, COUNTER_RATE);
        when(store.read(reference.seriesId(), -60_000, 1_200_000, 4097)).thenReturn(List.of());
        assertEquals(PreparedMetricSeries.Status.WARMING_UP,
            service.prepare(reference.seriesId(), "v1", "messages", 1_200_123, profile).status());
        verify(store).read(reference.seriesId(), -60_000, 1_200_000, 4097);
        verifyNoMoreInteractions(store);
        when(store.read(reference.seriesId(), -60_000, 1_200_000, 4097)).thenThrow(new java.sql.SQLException("offline"));
        assertThrows(java.sql.SQLException.class,
            () -> service.prepare(reference.seriesId(), "v1", "messages", 1_200_123, profile));
    }
}
