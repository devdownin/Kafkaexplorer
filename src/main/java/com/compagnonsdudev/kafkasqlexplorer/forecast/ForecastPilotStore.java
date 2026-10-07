// SPDX-License-Identifier: AGPL-3.0-or-later
package com.compagnonsdudev.kafkasqlexplorer.forecast;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.List;
import java.util.Properties;

/**
 * Global transaction lock fences inference and publication across instances; DB time bounds lease
 * expiry.
 */
public final class ForecastPilotStore {
  private static final ObjectMapper JSON = new ObjectMapper();
  private final MetricHistoryProperties properties;
  private boolean initialized;

  /** Enough for one refresh plus a few concurrent reads; more are opened and closed on demand. */
  private static final int MAX_IDLE_CONNECTIONS = 4;

  private final ForecastConnectionPool pool;

  public ForecastPilotStore(MetricHistoryProperties properties) {
    this.properties = properties;
    this.pool = new ForecastConnectionPool(this::connect, MAX_IDLE_CONNECTIONS);
  }

  public Connection open() throws Exception {
    Connection c = pool.borrow();
    try {
      initialize(c);
      return c;
    } catch (Exception e) {
      c.close();
      throw e;
    }
  }

  private Connection connect() throws Exception {
    Properties p = new Properties();
    p.setProperty("user", properties.getUsername());
    p.setProperty("password", properties.getPassword());
    String url =
        properties.getJdbcUrl()
            + (properties.getJdbcUrl().contains("?") ? "&" : "?")
            + "connectTimeout=5&socketTimeout=10";
    return DriverManager.getConnection(url, p);
  }

  private synchronized void initialize(Connection c) throws Exception {
    if (initialized) return;
    try (var s = c.createStatement()) {
      s.setQueryTimeout(5);
      s.execute(
          "CREATE TABLE IF NOT EXISTS kex_forecast_result_v2 (result_key VARCHAR(64) PRIMARY KEY,"
              + " series_id VARCHAR(64) NOT NULL, generated_at BIGINT NOT NULL, history_end BIGINT"
              + " NOT NULL, visibility VARCHAR(16) NOT NULL, strategy VARCHAR(32) NOT NULL, payload"
              + " TEXT NOT NULL)");
      s.execute(
          "ALTER TABLE kex_forecast_result_v2 ADD COLUMN IF NOT EXISTS strategy VARCHAR(32) NOT"
              + " NULL DEFAULT 'UNAVAILABLE'");
      s.execute(
          "CREATE INDEX IF NOT EXISTS kex_forecast_result_series_v2 ON"
              + " kex_forecast_result_v2(series_id,history_end DESC,generated_at DESC)");
      s.execute(
          "CREATE TABLE IF NOT EXISTS kex_forecast_pilot_control_v2 (series_id VARCHAR(64) PRIMARY"
              + " KEY, active BOOLEAN NOT NULL DEFAULT FALSE)");
      s.execute(
          "CREATE TABLE IF NOT EXISTS kex_forecast_pilot_lease_v2 (lease_name VARCHAR(64) PRIMARY"
              + " KEY, owner VARCHAR(64) NOT NULL, expires_at TIMESTAMPTZ NOT NULL)");
    }
    initialized = true;
  }

  /** Session ends or transaction rollback always releases the lock, including process crashes. */
  public boolean acquire(Connection c, String owner) throws Exception {
    c.setAutoCommit(false);
    try (var s = c.prepareStatement("SELECT pg_try_advisory_xact_lock(684129053)")) {
      s.setQueryTimeout(5);
      try (var r = s.executeQuery()) {
        r.next();
        if (!r.getBoolean(1)) return false;
      }
    }
    try (var s =
        c.prepareStatement(
            "INSERT INTO kex_forecast_pilot_lease_v2 VALUES ('global', ?,"
                + " clock_timestamp()+INTERVAL '2 minutes') ON CONFLICT(lease_name) DO UPDATE SET"
                + " owner=EXCLUDED.owner,expires_at=EXCLUDED.expires_at")) {
      s.setQueryTimeout(5);
      s.setString(1, owner);
      s.executeUpdate();
    }
    return true;
  }

  /**
   * Called under the global lock: all instances share the retained-series quota.
   *
   * <p>Only currently approved series count. A series removed from the configuration kept its
   * rows, and so its share of the budget, until retention expired them — up to 90 days in which
   * it could refuse a series that had replaced it.
   */
  public void checkSeriesBudget(Connection c, String id, int limit, List<String> approved)
      throws Exception {
    try (var s =
        c.prepareStatement(
            "SELECT count(DISTINCT series_id),bool_or(series_id=?) FROM kex_forecast_result_v2"
                + " WHERE series_id = ANY(?)")) {
      s.setQueryTimeout(5);
      s.setString(1, id);
      s.setArray(2, c.createArrayOf("varchar", approved.toArray()));
      try (var rows = s.executeQuery()) {
        rows.next();
        if (!rows.getBoolean(2) && rows.getLong(1) >= limit)
          throw new IllegalStateException("Global forecast series budget exhausted");
      }
    }
  }

  public boolean active(Connection c, String id) throws Exception {
    try (var s =
        c.prepareStatement("SELECT active FROM kex_forecast_pilot_control_v2 WHERE series_id=?")) {
      s.setQueryTimeout(5);
      s.setString(1, id);
      try (var r = s.executeQuery()) {
        return r.next() && r.getBoolean(1);
      }
    }
  }

  public void activate(String id, boolean active) throws Exception {
    try (var c = open()) {
      activate(c, id, active);
    }
  }

  public void activate(Connection c, String id, boolean active) throws Exception {
    try (var s =
        c.prepareStatement(
            "INSERT INTO kex_forecast_pilot_control_v2 VALUES (?,?) ON CONFLICT(series_id) DO"
                + " UPDATE SET active=EXCLUDED.active")) {
      s.setQueryTimeout(5);
      s.setString(1, id);
      s.setBoolean(2, active);
      s.executeUpdate();
    }
  }

  public boolean contains(Connection c, String key) throws Exception {
    try (var s = c.prepareStatement("SELECT 1 FROM kex_forecast_result_v2 WHERE result_key=?")) {
      s.setQueryTimeout(5);
      s.setString(1, key);
      try (var rows = s.executeQuery()) {
        return rows.next();
      }
    }
  }

  public ForecastRecord latest(String id) throws Exception {
    try (var c = open()) {
      return latest(c, id);
    }
  }

  public ForecastRecord latest(Connection c, String id) throws Exception {
    try (var s =
        c.prepareStatement(
            "SELECT payload FROM kex_forecast_result_v2 WHERE series_id=? ORDER BY history_end"
                + " DESC,generated_at DESC LIMIT 1")) {
      s.setQueryTimeout(5);
      s.setString(1, id);
      try (var r = s.executeQuery()) {
        return r.next() ? decode(r.getString(1)) : null;
      }
    }
  }

  public ForecastRecord matured(Connection c, String id, long before, long after) throws Exception {
    try (var s =
        c.prepareStatement(
            "SELECT payload FROM kex_forecast_result_v2 WHERE series_id=? AND strategy='TIMESFM'"
                + " AND history_end<=? AND history_end>=? ORDER BY history_end DESC LIMIT 1")) {
      s.setQueryTimeout(5);
      s.setString(1, id);
      s.setLong(2, before);
      s.setLong(3, after);
      try (var r = s.executeQuery()) {
        return r.next() ? decode(r.getString(1)) : null;
      }
    }
  }

  private ForecastRecord decode(String payload) throws Exception {
    if (payload.length() > 262144)
      throw new IllegalStateException("Forecast payload exceeds budget");
    return JSON.readValue(payload, ForecastRecord.class);
  }

  public void save(Connection c, String id, ForecastRecord record, String owner) throws Exception {
    String payload = JSON.writeValueAsString(record);
    if (payload.length() > 262144)
      throw new IllegalArgumentException("Forecast payload exceeds budget");
    try (var s =
        c.prepareStatement(
            "INSERT INTO kex_forecast_result_v2"
                + " (result_key,series_id,generated_at,history_end,visibility,strategy,payload)"
                + " SELECT ?,?,?,?,?,?,? WHERE EXISTS (SELECT 1 FROM kex_forecast_pilot_lease_v2"
                + " WHERE lease_name='global' AND owner=? AND expires_at>clock_timestamp()) ON"
                + " CONFLICT(result_key) DO NOTHING")) {
      s.setQueryTimeout(5);
      s.setString(1, record.key());
      s.setString(2, id);
      s.setLong(3, record.generatedAt());
      s.setLong(4, record.context().toExclusive());
      s.setString(5, record.visibility().name());
      s.setString(6, record.strategy());
      s.setString(7, payload);
      s.setString(8, owner);
      if (s.executeUpdate() != 1)
        throw new IllegalStateException("Forecast publication fenced or duplicate");
    }
  }

  public void disable(Connection c, String id) throws Exception {
    try (var s =
        c.prepareStatement(
            "UPDATE kex_forecast_pilot_control_v2 SET active=FALSE WHERE series_id=?")) {
      s.setQueryTimeout(5);
      s.setString(1, id);
      s.executeUpdate();
    }
  }

  public void purge(Connection c, long cutoff) throws Exception {
    try (var s =
        c.prepareStatement(
            "DELETE FROM kex_forecast_result_v2 WHERE result_key IN (SELECT result_key FROM"
                + " kex_forecast_result_v2 WHERE generated_at<? ORDER BY generated_at LIMIT"
                + " 1000)")) {
      s.setQueryTimeout(5);
      s.setLong(1, cutoff);
      s.executeUpdate();
    }
  }
}
