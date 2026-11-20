// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors
package com.compagnonsdudev.kafkasqlexplorer.forecast;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/** METRICS-FORECAST-AUDIT-2.md P1: a result is decoded once, not on every poll of the list. */
class ForecastPilotStoreLatestTest {
  private static ForecastRecord record(String key) {
    var profile = SeriesPreparationProfile.initial(SeriesPreparationProfile.Transformation.GAUGE_MEAN);
    var points = new ArrayList<PreparedMetricSeries.Point>();
    for (int i = 0; i < 512; i++) points.add(new PreparedMetricSeries.Point(i * 60_000L, 1.0 * i, false, 1));
    var context = new PreparedMetricSeries(PreparedMetricSeries.Status.READY, "ok", "s", "v1", "ms", "ms", 0,
        512 * 60_000L, profile, "p", "i", 512, 0, 0, points);
    return new ForecastRecord(key, 1, "READY", "TIMESFM", ForecastThresholdPolicy.Visibility.SHADOW, context,
        null, null, 0, 0, Map.of(), "ok", null);
  }

  @Test
  void theLatestRecordIsDecodedOncePerResult() throws Exception {
    var latestKey = new AtomicReference<>("k1");
    var payloadReads = new AtomicInteger();
    var connection = mock(Connection.class);
    when(connection.prepareStatement(anyString())).thenAnswer(call -> {
      String sql = call.getArgument(0);
      var statement = mock(PreparedStatement.class);
      var rows = mock(ResultSet.class);
      when(statement.executeQuery()).thenReturn(rows);
      when(rows.next()).thenReturn(true, false);
      if (sql.startsWith("SELECT result_key")) {
        when(rows.getString(1)).thenAnswer(i -> latestKey.get());
      } else {
        assertTrue(sql.startsWith("SELECT payload"), sql);
        when(rows.getString(1)).thenAnswer(i -> {
          payloadReads.incrementAndGet();
          return new ObjectMapper().writeValueAsString(record(latestKey.get()));
        });
      }
      return statement;
    });
    var store = new ForecastPilotStore(new MetricHistoryProperties());

    assertEquals("k1", store.latest(connection, "s").key());
    assertEquals("k1", store.latest(connection, "s").key());
    assertEquals("k1", store.latest(connection, "s").key());
    assertEquals(1, payloadReads.get(), "three polls of one unchanged result decode it once");

    latestKey.set("k2"); // the next cycle wrote a new result
    assertEquals("k2", store.latest(connection, "s").key());
    assertEquals(2, payloadReads.get(), "a new result is read at once");
  }

  @Test
  void aSeriesWithNoResultHasNone() throws Exception {
    var connection = mock(Connection.class);
    var statement = mock(PreparedStatement.class);
    var rows = mock(ResultSet.class);
    when(connection.prepareStatement(anyString())).thenReturn(statement);
    when(statement.executeQuery()).thenReturn(rows);
    when(rows.next()).thenReturn(false);
    assertNull(new ForecastPilotStore(new MetricHistoryProperties()).latest(connection, "none"));
  }
}
