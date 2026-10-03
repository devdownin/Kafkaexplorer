// SPDX-License-Identifier: AGPL-3.0-or-later
package com.compagnonsdudev.kafkasqlexplorer.forecast;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.sql.Connection;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class ForecastPilotServiceTest {
  private final ForecastPilotStore store = mock(ForecastPilotStore.class);
  private final MetricSeriesPreparationService preparation =
      mock(MetricSeriesPreparationService.class);
  private final TimesFmClient client = mock(TimesFmClient.class);
  private final Connection connection = mock(Connection.class);
  private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
  private final long now = System.currentTimeMillis() + 100000;
  private ForecastPilotService pilot;
  private ForecastPilotProperties.Series spec;
  private PreparedMetricSeries context;

  private PreparedMetricSeries context(long end) {
    var points = new ArrayList<PreparedMetricSeries.Point>();
    for (int i = 0; i < 512; i++)
      points.add(new PreparedMetricSeries.Point(end - (511 - i) * 1000, 1d, false, 1));
    return new PreparedMetricSeries(
        PreparedMetricSeries.Status.READY,
        "ready",
        "a".repeat(64),
        "v1",
        "messages",
        "messages",
        end - 512000,
        end,
        spec.profile(),
        "profile",
        "input" + end,
        512,
        0,
        0,
        points);
  }

  private MetricForecast forecast(PreparedMetricSeries c, double median) {
    return new MetricForecast(
        "request",
        c.seriesId(),
        c.definitionVersion(),
        c.inputFingerprint(),
        c.profileFingerprint(),
        c.outputUnit(),
        c.toExclusive(),
        TimesFmClient.MODEL_ID,
        TimesFmClient.MODEL_REVISION,
        TimesFmClient.ADAPTER_VERSION,
        "MEDIAN",
        1,
        List.of(
            new MetricForecast.Point(c.toExclusive() + 1000, median, 0, median, 3),
            new MetricForecast.Point(c.toExclusive() + 2000, median, 0, median, 3)));
  }

  private ForecastRecord record(PreparedMetricSeries c, double median, String state)
      throws Exception {
    return new ForecastRecord(
        ForecastPilotService.key(spec, c),
        now,
        state,
        "TIMESFM",
        ForecastThresholdPolicy.Visibility.SHADOW,
        c,
        forecast(c, median),
        new ForecastBacktestEvaluator.Evaluation(0, null, 0, 1, 3),
        2,
        0,
        Map.of("LAST_VALUE", 0d, "MOVING_AVERAGE", 0d, "SEASONAL_NAIVE", 0d, "LINEAR_TREND", 0d),
        "ready");
  }

  @BeforeEach
  void setup() throws Exception {
    spec =
        new ForecastPilotProperties.Series(
            "a".repeat(64),
            "metric",
            "production",
            "v1",
            "messages",
            List.of("orders"),
            List.of(),
            new SeriesPreparationProfile(
                1000, 512, SeriesPreparationProfile.Transformation.GAUGE_LAST),
            2,
            1,
            null,
            .5,
            .8,
            2);
    var props = new ForecastPilotProperties();
    props.setEnabled(true);
    props.setSeries(List.of(spec));
    context = context(now);
    pilot = new ForecastPilotService(props, store, preparation, client, meters);
    when(store.open()).thenReturn(connection);
    when(store.acquire(eq(connection), anyString())).thenReturn(true);
    when(preparation.prepare(
            eq(spec.seriesId()), eq("v1"), eq("messages"), anyLong(), eq(spec.profile())))
        .thenReturn(context);
    when(client.forecast(anyList(), eq(2))).thenReturn(List.of(forecast(context, 1)));
  }

  @Test
  void contentionNeverStartsPreparationOrInference() throws Exception {
    when(store.acquire(eq(connection), anyString())).thenReturn(false);
    pilot.refresh(spec, now);
    verifyNoInteractions(preparation, client);
    verify(connection).rollback();
    verify(store, never()).save(any(), anyString(), any(), anyString());
  }

  @Test
  void identicalInputSkipsInferenceAndPublication() throws Exception {
    when(store.latest(connection, spec.seriesId())).thenReturn(record(context, 1, "READY"));
    pilot.refresh(spec, now);
    verifyNoInteractions(client);
    verify(connection).rollback();
    verify(store, never()).save(any(), anyString(), any(), anyString());
  }

  @Test
  void outagePublishesExplicitPointFallbackAndNeverActive() throws Exception {
    when(client.forecast(anyList(), eq(2)))
        .thenThrow(new TimesFmInferenceException(TimesFmInferenceException.State.TIMEOUT));
    when(store.active(connection, spec.seriesId())).thenReturn(true);
    pilot.refresh(spec, now);
    var capture = ArgumentCaptor.forClass(ForecastRecord.class);
    verify(store).save(eq(connection), eq(spec.seriesId()), capture.capture(), anyString());
    var r = capture.getValue();
    assertEquals("LAST_VALUE", r.strategy());
    assertEquals("POINT", r.forecast().centralStatistic());
    assertEquals(ForecastThresholdPolicy.Visibility.SHADOW, r.visibility());
    assertNull(r.quality());
    verify(connection).commit();
  }

  @Test
  void realisedErrorDisablesActivationAndRetainsRecord() throws Exception {
    var old = record(context(now - 2000), 2, "READY");
    when(store.latest(connection, spec.seriesId())).thenReturn(old);
    when(store.matured(eq(connection), eq(spec.seriesId()), anyLong(), anyLong())).thenReturn(old);
    when(store.active(connection, spec.seriesId())).thenReturn(true);
    pilot.refresh(spec, now);
    verify(store).disable(connection, spec.seriesId());
    var capture = ArgumentCaptor.forClass(ForecastRecord.class);
    verify(store).save(eq(connection), eq(spec.seriesId()), capture.capture(), anyString());
    var r = capture.getValue();
    assertEquals("DEGRADED", r.state());
    assertEquals(1, r.quality().mae());
    assertEquals(4, r.evaluatedPoints());
    assertEquals(now, r.evaluatedThrough());
    assertEquals(4, r.baselineMae().size());
    assertEquals(ForecastThresholdPolicy.Visibility.SHADOW, r.visibility());
  }

  @Test
  void activationRequiresEvidenceAndPersistsOnlyAfterExplicitCall() throws Exception {
    assertThrows(IllegalArgumentException.class, () -> pilot.activate(spec.seriesId(), true));
    verify(store, never()).activate(any(), anyString(), anyBoolean());
    when(store.latest(connection, spec.seriesId())).thenReturn(record(context, 1, "READY"));
    pilot.activate(spec.seriesId(), true);
    verify(store).activate(connection, spec.seriesId(), true);
    verify(connection).commit();
  }

  @Test
  void expiredForecastCannotRemainActiveOrProduceBreach() throws Exception {
    when(store.latest(connection, spec.seriesId()))
        .thenReturn(record(context(System.currentTimeMillis() - 10000), 1, "READY"));
    var r = pilot.get(spec.seriesId());
    assertEquals("STALE", r.state());
    assertEquals(ForecastThresholdPolicy.Visibility.SHADOW, r.visibility());
    assertNull(pilot.breach(spec.seriesId()));
  }

  @Test
  void qualityMcpReturnsBaselineEvidenceWithoutInference() throws Exception {
    when(store.latest(connection, spec.seriesId())).thenReturn(record(context, 1, "READY"));
    var policy = new com.compagnonsdudev.kafkasqlexplorer.mcp.McpProperties();
    policy.setAllowedForecastEnvironments(List.of("production"));
    var guard =
        new com.compagnonsdudev.kafkasqlexplorer.mcp.guard.ToolGuard(
            policy, new com.compagnonsdudev.kafkasqlexplorer.mcp.guard.DlpScrubber(policy));
    var tools = new com.compagnonsdudev.kafkasqlexplorer.mcp.tools.ForecastMcpTools(pilot, guard);
    var measured = tools.quality(spec.seriesId()).data();
    assertTrue(measured.measured());
    assertEquals(4, measured.value().baselineMae().size());
    assertEquals(2, measured.value().evaluatedPoints());
    assertEquals("TIMESFM", measured.value().currentStrategy());
    verify(client, never()).forecast(anyList(), anyInt());
  }
}
