// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors
package com.compagnonsdudev.kafkasqlexplorer.forecast;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

/** Real file-backed JDBC storage. PostgreSQL execution is covered separately, not inferred from H2. */
class JdbcMetricObservationStoreTest {
    @TempDir Path dir;

    private JdbcMetricObservationStore store(String url) {
        return new JdbcMetricObservationStore(() -> DriverManager.getConnection(url));
    }

    private MetricObservation point(String run, long at, Double value, String version) {
        return MetricObservation.create("cluster", "m1", version, "value", Map.of("topic", "orders"),
            "messages", "GAUGE", run, at, value, value == null ? "UNMEASURED" : "OBSERVED", Map.of());
    }

    @Test
    void connectivityProbeDoesNotCreateHistorySchema() throws Exception {
        String url = "jdbc:h2:mem:" + java.util.UUID.randomUUID() + ";DB_CLOSE_DELAY=-1;MODE=PostgreSQL";
        assertTrue(store(url).checkConnection());
        try (var connection = DriverManager.getConnection(url);
             var tables = connection.getMetaData().getTables(null, null, "KEX_METRIC_OBSERVATION_V1", null)) {
            assertFalse(tables.next());
        }
    }

    @Test
    void survivesReopeningIsIdempotentAndPreservesUnknownVersusZero() throws Exception {
        String url = "jdbc:h2:file:" + dir.resolve("history") + ";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE";
        var first = store(url);
        var zero = point("run1", 1000, 0.0, "v1");
        var missing = point("run2", 2000, null, "v1");
        first.append(List.of(missing, zero, zero));
        var reopened = store(url);
        var actual = reopened.read(zero.seriesId(), 0, 3000, 100);
        assertEquals(2, actual.size());
        assertEquals(0.0, actual.get(0).value());
        assertNull(actual.get(1).value());
        assertEquals("UNMEASURED", actual.get(1).qualityState());
        assertEquals(Map.of("topic", "orders"), actual.get(0).labels());
    }

    @Test
    void isolatesVersionsAndBoundsReadsAndRetention() throws Exception {
        String url = "jdbc:h2:mem:" + java.util.UUID.randomUUID() + ";DB_CLOSE_DELAY=-1;MODE=PostgreSQL";
        var store = store(url);
        var v1 = point("run1", 1000, 10.0, "v1");
        var v2 = point("run2", 2000, 20.0, "v2");
        store.append(List.of(v1, point("run3", 1500, 15.0, "v1"), v2));
        assertEquals(1, store.read(v1.seriesId(), 0, 3000, 1).size());
        assertEquals(20.0, store.read(v2.seriesId(), 0, 3000, 10).getFirst().value());
        assertEquals(1, store.purgeBefore(1800, 1));
        assertEquals(1, store.read(v1.seriesId(), 0, 3000, 10).size());
        assertEquals(1, store.purgeBefore(1800, 10));
        assertTrue(store.read(v1.seriesId(), 0, 3000, 10).isEmpty());
    }
}
