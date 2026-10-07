// SPDX-License-Identifier: AGPL-3.0-or-later
package com.compagnonsdudev.kafkasqlexplorer.forecast;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Run against an isolated real PostgreSQL: TIMESFM_TEST_POSTGRES_URL. Never substitute H2 for
 * locks.
 */
class ForecastPilotPostgresTest {
  @Test
  void fencesConcurrentInstancesAndSurvivesReopen() throws Exception {
    String url = System.getenv("TIMESFM_TEST_POSTGRES_URL");
    assumeTrue(url != null, "isolated PostgreSQL not supplied");
    exercise(url, System.getenv().getOrDefault("TIMESFM_TEST_POSTGRES_USER", "postgres"), "");
  }

  void exercise(String url, String user, String password) throws Exception {
    var props = new MetricHistoryProperties();
    props.setJdbcUrl(url);
    props.setUsername(user);
    props.setPassword(password);
    var a = new ForecastPilotStore(props);
    var b = new ForecastPilotStore(props);
    String owner = UUID.randomUUID().toString();
    String id = "a".repeat(64);
    var profile =
        new SeriesPreparationProfile(
            60000, 512, SeriesPreparationProfile.Transformation.GAUGE_LAST);
    var context =
        new PreparedMetricSeries(
            PreparedMetricSeries.Status.READY,
            "ready",
            id,
            "v1",
            "messages",
            "messages",
            0,
            60000,
            profile,
            "profile",
            "input",
            512,
            0,
            0,
            List.of(new PreparedMetricSeries.Point(60000, 1d, false, 1)));
    var record =
        new ForecastRecord(
            "b".repeat(64),
            60000,
            "READY",
            "LAST_VALUE",
            ForecastThresholdPolicy.Visibility.SHADOW,
            context,
            null,
            null,
            0,
            0,
            Map.of(),
            "baseline",
            null);
    try (var c = a.open();
        var d = b.open()) {
      assertTrue(a.acquire(c, owner));
      assertFalse(b.acquire(d, "other"));
      d.rollback();
      a.save(c, id, record, owner);
      c.commit();
      assertEquals(record, b.latest(id));
      assertTrue(b.acquire(d, "other"));
      assertThrows(IllegalStateException.class, () -> b.save(d, id, record, owner));
      d.rollback();
      assertTrue(a.acquire(c, owner));
      try (var s = c.createStatement()) {
        s.executeUpdate(
            "UPDATE kex_forecast_pilot_lease_v2 SET expires_at=clock_timestamp()-INTERVAL '1"
                + " second'");
      }
      var second =
          new ForecastRecord(
              "c".repeat(64),
              60000,
              "READY",
              "LAST_VALUE",
              ForecastThresholdPolicy.Visibility.SHADOW,
              context,
              null,
              null,
              0,
              0,
              Map.of(),
              "baseline",
              null);
      assertThrows(IllegalStateException.class, () -> a.save(c, id, second, owner));
      c.rollback();
      assertTrue(b.acquire(d, "new-owner"));
      b.activate(d, id, true);
      d.commit();
      try (var reopened = a.open()) {
        assertTrue(a.active(reopened, id));
      }
    }
  }
}
