// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors
package com.compagnonsdudev.kafkasqlexplorer.web;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.compagnonsdudev.kafkasqlexplorer.forecast.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

class ForecastControllerTest {
  private final ForecastPilotService pilot = mock(ForecastPilotService.class);

  @SuppressWarnings("unchecked")
  private ForecastController controller() {
    ObjectProvider<ForecastPilotService> provider = mock(ObjectProvider.class);
    when(provider.getIfAvailable()).thenReturn(pilot);
    return new ForecastController(provider);
  }

  @Test
  void aRefusalAnswers409WithItsReason() throws Exception {
    doThrow(new IllegalArgumentException("The last quality block failed"))
        .when(pilot)
        .activate("id", true);
    var response = controller().activate("id", new ForecastController.Activation(true, true));
    assertEquals(409, response.getStatusCode().value());
    assertEquals("The last quality block failed", response.getBody().reason());
  }

  @Test
  void anAcceptedActivationAnswers204WithoutBody() throws Exception {
    var response = controller().activate("id", new ForecastController.Activation(false, false));
    assertEquals(204, response.getStatusCode().value());
    assertNull(response.getBody());
    verify(pilot).activate("id", false);
  }

  @Test
  void theShadowComparisonIs404WhenOffAnd204BeforeAFirstRow() throws Exception {
    assertEquals(404, controller().shadow("id").getStatusCode().value());
    verify(pilot, never()).shadowComparison(anyString());
    when(pilot.shadowEnabled()).thenReturn(true);
    assertEquals(204, controller().shadow("id").getStatusCode().value());
    doThrow(new IllegalArgumentException("Series is not configured for this pilot"))
        .when(pilot)
        .shadowComparison("unknown");
    assertEquals(404, controller().shadow("unknown").getStatusCode().value());
  }

  private static ForecastPilotProperties.Series series(String id) {
    return new ForecastPilotProperties.Series(id, "metric-" + id.charAt(0), "local", "v1", "ms",
        java.util.List.of("orders"), java.util.List.of(),
        SeriesPreparationProfile.initial(SeriesPreparationProfile.Transformation.GAUGE_MEAN), 30, 1, null, null, .8, 30);
  }

  private static ForecastRecord record(String id, int points) {
    var profile = SeriesPreparationProfile.initial(SeriesPreparationProfile.Transformation.GAUGE_MEAN);
    var history = new java.util.ArrayList<PreparedMetricSeries.Point>();
    for (int i = 0; i < points; i++) history.add(new PreparedMetricSeries.Point(i * 60_000L, 1.0 * i, false, 1));
    var context = new PreparedMetricSeries(PreparedMetricSeries.Status.READY, "ok", id, "v1", "ms", "ms", 0,
        points * 60_000L, profile, "p", "i", points, 512 - points, 0, history);
    return new ForecastRecord("k", 1, "READY", "TIMESFM", ForecastThresholdPolicy.Visibility.SHADOW, context,
        null, null, 0, 0, java.util.Map.of(), "ok", null);
  }

  /** METRICS-FORECAST-UX-AUDIT.md B2: one unreadable series no longer blanks the list. */
  @Test
  void oneUnreadableSeriesIsAnErrorRowNotA503() throws Exception {
    var good = series("a".repeat(64));
    var bad = series("b".repeat(64));
    when(pilot.series()).thenReturn(java.util.List.of(good, bad));
    when(pilot.get(good.seriesId())).thenReturn(record(good.seriesId(), 3));
    when(pilot.get(bad.seriesId())).thenThrow(new IllegalStateException("payload cannot be decoded"));

    var response = controller().status();

    assertEquals(200, response.getStatusCode().value());
    var rows = response.getBody().series();
    assertEquals(2, rows.size());
    assertNull(rows.get(0).error());
    assertNull(rows.get(1).result());
    org.junit.jupiter.api.Assertions.assertTrue(rows.get(1).error().contains("IllegalStateException"), rows.get(1).error());
    org.junit.jupiter.api.Assertions.assertFalse(rows.get(1).error().contains("payload cannot be decoded"));
  }

  /** METRICS-FORECAST-UX-AUDIT.md P1: the list carries no history points; the detail does. */
  @Test
  void theListOmitsHistoryPointsAndTheDetailKeepsThem() throws Exception {
    var s = series("a".repeat(64));
    when(pilot.series()).thenReturn(java.util.List.of(s));
    when(pilot.resolve(s.seriesId())).thenReturn(s);
    when(pilot.get(s.seriesId())).thenReturn(record(s.seriesId(), 512));

    var listed = controller().status().getBody().series().getFirst().result();
    assertEquals(0, listed.context().points().size());
    assertEquals(512, listed.context().observedPoints());
    assertEquals(512, controller().series(s.seriesId()).getBody().result().context().points().size());

    when(pilot.resolve("unknown")).thenThrow(new IllegalArgumentException("unknown"));
    assertEquals(404, controller().series("unknown").getStatusCode().value());
  }

  /** PAGES-SIMPLICITY-AUDIT.md X1: the list says whether a shadow exists, so the panel need not ask. */
  @Test
  void theListSaysWhetherAShadowIsConfigured() {
    when(pilot.series()).thenReturn(java.util.List.of());
    when(pilot.shadowEnabled()).thenReturn(false);
    org.junit.jupiter.api.Assertions.assertFalse(controller().status().getBody().shadowEnabled());
    when(pilot.shadowEnabled()).thenReturn(true);
    org.junit.jupiter.api.Assertions.assertTrue(controller().status().getBody().shadowEnabled());
  }
}
