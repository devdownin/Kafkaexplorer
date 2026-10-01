// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors
package com.compagnonsdudev.kafkasqlexplorer.forecast;

import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import java.sql.DriverManager;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

/** Exercises PostgreSQL's actual transaction, conflict and purge semantics when Docker is available. */
@Testcontainers(disabledWithoutDocker = true)
class MetricObservationPostgresTest {
    @Container
    static final GenericContainer<?> postgres = new GenericContainer<>("postgres:17.6-alpine")
        .withEnv("POSTGRES_DB", "history")
        .withEnv("POSTGRES_PASSWORD", "integration-test-only")
        .withExposedPorts(5432)
        .waitingFor(Wait.forLogMessage(".*database system is ready to accept connections.*\\n", 2)
            .withStartupTimeout(Duration.ofSeconds(60)));

    @Test
    void durableAppendConflictAndPurgeWorkOnPostgres() throws Exception {
        String url = "jdbc:postgresql://" + postgres.getHost() + ":" + postgres.getMappedPort(5432) + "/history";
        JdbcMetricObservationStore.Connections connections = () ->
            DriverManager.getConnection(url, "postgres", "integration-test-only");
        var point = MetricObservation.create("lab", "m1", "v1", "value", Map.of(),
            "messages", "GAUGE", "one-run", 1000, 0.0, "OBSERVED", Map.of());
        var first = new JdbcMetricObservationStore(connections);
        first.append(List.of(point, point));
        var second = new JdbcMetricObservationStore(connections);
        second.append(List.of(point));
        assertEquals(1, second.read(point.seriesId(), 0, 2000, 10).size());
        assertEquals(1, second.purgeBefore(1500, 10));
        assertTrue(first.read(point.seriesId(), 0, 2000, 10).isEmpty());
    }
}
