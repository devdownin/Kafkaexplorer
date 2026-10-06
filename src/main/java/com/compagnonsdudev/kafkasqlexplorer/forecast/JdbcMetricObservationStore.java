// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors
package com.compagnonsdudev.kafkasqlexplorer.forecast;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Types;
import java.util.ArrayList;
import java.util.List;

/** PostgreSQL journal, independent of Boot's datasource auto-configuration. */
public final class JdbcMetricObservationStore implements MetricObservationStore {
    @FunctionalInterface
    public interface Connections { Connection open() throws SQLException; }
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private final Connections connections;
    private volatile boolean initialized;

    public JdbcMetricObservationStore(Connections connections) { this.connections = connections; }

    @Override
    public boolean checkConnection() throws SQLException {
        try (var c = connections.open()) { return c.isValid(2); }
    }

    private synchronized void initialize(Connection c) throws SQLException {
        if (initialized) return;
        try (var s = c.createStatement()) {
            s.setQueryTimeout(5);
            s.execute("CREATE TABLE IF NOT EXISTS kex_metric_observation_v1 ("
                + "observation_id VARCHAR(64) PRIMARY KEY, series_id VARCHAR(64) NOT NULL, "
                + "observed_at BIGINT NOT NULL, metric_value DOUBLE PRECISION, payload TEXT NOT NULL)");
            s.execute("CREATE INDEX IF NOT EXISTS kex_observation_series_time_v1 ON "
                + "kex_metric_observation_v1(series_id, observed_at, observation_id)");
            s.execute("CREATE INDEX IF NOT EXISTS kex_observation_retention_v1 ON "
                + "kex_metric_observation_v1(observed_at, observation_id)");
        }
        // DDL has been autocommitted before data transactions; no cached success after rollback.
        initialized = true;
    }

    @Override
    public void append(List<MetricObservation> observations) throws Exception {
        if (observations.isEmpty()) return;
        try (var c = connections.open()) {
            initialize(c);
            c.setAutoCommit(false);
            try (var s = c.prepareStatement("INSERT INTO kex_metric_observation_v1 "
                + "(observation_id, series_id, observed_at, metric_value, payload) VALUES (?, ?, ?, ?, ?) "
                + "ON CONFLICT DO NOTHING")) {
                s.setQueryTimeout(5);
                for (var o : observations) {
                    s.setString(1, o.observationId()); s.setString(2, o.seriesId()); s.setLong(3, o.observedAt());
                    if (o.value() == null) s.setNull(4, Types.DOUBLE); else s.setDouble(4, o.value());
                    s.setString(5, MAPPER.writeValueAsString(o)); s.addBatch();
                }
                s.executeBatch();
                c.commit();
            } catch (Exception e) {
                c.rollback();
                throw e;
            }
        }
    }

    @Override
    public List<MetricObservation> read(String seriesId, long from, long to, int limit) throws Exception {
        if (from >= to || limit < 1 || limit > MAX_READ_OBSERVATIONS) throw new IllegalArgumentException("Invalid history bounds");
        try (var c = connections.open()) {
            initialize(c);
            // PostgreSQL only honors fetchSize as a cursor inside a transaction. Avoid buffering
            // the entire result in the driver before the payload budget can be checked.
            c.setReadOnly(true);
            c.setAutoCommit(false);
            try (var s = c.prepareStatement("SELECT payload FROM kex_metric_observation_v1 "
                + "WHERE series_id = ? AND observed_at >= ? AND observed_at < ? "
                + "ORDER BY observed_at, observation_id LIMIT ?")) {
                s.setQueryTimeout(5);
                s.setFetchSize(64);
                s.setString(1, seriesId); s.setLong(2, from); s.setLong(3, to); s.setInt(4, limit);
                List<MetricObservation> result = new ArrayList<>();
                long characters = 0;
                try (var r = s.executeQuery()) {
                    while (r.next()) {
                        String payload = r.getString(1);
                        characters += payload.length();
                        if (characters > MAX_READ_PAYLOAD_CHARACTERS) throw new ReadLimitExceededException();
                        result.add(MAPPER.readValue(payload, MetricObservation.class));
                    }
                }
                c.commit();
                return List.copyOf(result);
            }
        }
    }

    @Override
    public int purgeBefore(long cutoff, int limit) throws Exception {
        if (limit < 1 || limit > 10000) throw new IllegalArgumentException("Invalid purge bound");
        try (var c = connections.open()) {
            initialize(c);
            try (var s = c.prepareStatement("DELETE FROM kex_metric_observation_v1 WHERE observation_id IN "
                + "(SELECT observation_id FROM kex_metric_observation_v1 WHERE observed_at < ? "
                + "ORDER BY observed_at, observation_id LIMIT ?)")) {
                s.setQueryTimeout(5);
                s.setLong(1, cutoff); s.setInt(2, limit);
                return s.executeUpdate();
            }
        }
    }
}
