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
      points.add(
          new PreparedMetricSeries.Point(
              end - (511 - i) * spec.profile().stepMillis(), 1d, false, 1));
    return new PreparedMetricSeries(
        PreparedMetricSeries.Status.READY,
        "ready",
        "a".repeat(64),
        "v1",
        "messages",
        "messages",
        end - 512 * spec.profile().stepMillis(),
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
        BASELINES_AT_ZERO,
        "ready",
        new ForecastQualityWindow(List.of(), passedBlock(), 0));
  }

  private static final Map<String, Double> BASELINES_AT_ZERO =
      Map.of("LAST_VALUE", 0d, "MOVING_AVERAGE", 0d, "SEASONAL_NAIVE", 0d, "LINEAR_TREND", 0d);

  private ForecastQualityWindow.Block passedBlock() {
    return new ForecastQualityWindow.Block(
        now, 60, 120, new ForecastBacktestEvaluator.Evaluation(0, null, 0, 1, 3),
        BASELINES_AT_ZERO, 1, .7, true);
  }

  private ForecastRecord withWindow(ForecastRecord r, ForecastQualityWindow window) {
    return new ForecastRecord(
        r.key(), r.generatedAt(), r.state(), r.strategy(), r.visibility(), r.context(),
        r.forecast(), r.quality(), r.evaluatedPoints(), r.evaluatedThrough(), r.baselineMae(),
        r.reason(), window);
  }

  private PreparedMetricSeries withImputedPoint(PreparedMetricSeries c, int index) {
    var points = new ArrayList<>(c.points());
    var p = points.get(index);
    points.set(index, new PreparedMetricSeries.Point(p.endAt(), p.value(), true, 0));
    return new PreparedMetricSeries(
        c.status(), c.reason(), c.seriesId(), c.definitionVersion(), c.sourceUnit(),
        c.outputUnit(), c.fromInclusive(), c.toExclusive(), c.profile(), c.profileFingerprint(),
        c.inputFingerprint(), c.observedPoints() - 1, c.missingPoints() + 1, 1, points);
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
  void oneBadCohortNeitherDegradesNorDisables() throws Exception {
    var old = withWindow(record(context(now - 2000), 2, "READY"), ForecastQualityWindow.EMPTY);
    when(store.latest(connection, spec.seriesId())).thenReturn(old);
    when(store.matured(eq(connection), eq(spec.seriesId()), anyLong(), anyLong())).thenReturn(old);
    pilot.refresh(spec, now);
    verify(store, never()).disable(any(), anyString());
    var capture = ArgumentCaptor.forClass(ForecastRecord.class);
    verify(store).save(eq(connection), eq(spec.seriesId()), capture.capture(), anyString());
    var r = capture.getValue();
    assertEquals("READY", r.state());
    assertEquals(1, r.quality().mae());
    assertEquals(1, r.qualityWindow().open().size());
    assertNull(r.qualityWindow().lastBlock());
  }

  @Test
  void secondConsecutiveFailedBlockDisablesActivationAndRetainsRecord() throws Exception {
    var filling =
        new ForecastQualityWindow.Cohort(
            now - 4000, 118, new ForecastBacktestEvaluator.Evaluation(1, null, 1, 0, 3),
            BASELINES_AT_ZERO, 1);
    var old =
        withWindow(
            record(context(now - 2000), 2, "READY"),
            new ForecastQualityWindow(List.of(filling), null, 1));
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
    assertEquals(2, r.qualityWindow().failedBlocks());
    assertEquals(120, r.qualityWindow().lastBlock().points());
    assertEquals(ForecastThresholdPolicy.Visibility.SHADOW, r.visibility());
  }

  @Test
  void imputedPointOutsideTheRealisedHorizonStillEvaluates() throws Exception {
    context = withImputedPoint(context, 0);
    when(preparation.prepare(
            eq(spec.seriesId()), eq("v1"), eq("messages"), anyLong(), eq(spec.profile())))
        .thenReturn(context);
    when(client.forecast(anyList(), eq(2))).thenReturn(List.of(forecast(context, 1)));
    var old = withWindow(record(context(now - 2000), 1, "READY"), ForecastQualityWindow.EMPTY);
    when(store.latest(connection, spec.seriesId())).thenReturn(old);
    when(store.matured(eq(connection), eq(spec.seriesId()), anyLong(), anyLong())).thenReturn(old);
    pilot.refresh(spec, now);
    var capture = ArgumentCaptor.forClass(ForecastRecord.class);
    verify(store).save(eq(connection), eq(spec.seriesId()), capture.capture(), anyString());
    assertEquals(4, capture.getValue().evaluatedPoints());
    assertEquals(now, capture.getValue().evaluatedThrough());
  }

  @Test
  void imputedPointInsideTheRealisedHorizonIsNeverScored() throws Exception {
    context = withImputedPoint(context, 511);
    when(preparation.prepare(
            eq(spec.seriesId()), eq("v1"), eq("messages"), anyLong(), eq(spec.profile())))
        .thenReturn(context);
    when(client.forecast(anyList(), eq(2))).thenReturn(List.of(forecast(context, 1)));
    var old = withWindow(record(context(now - 2000), 1, "READY"), ForecastQualityWindow.EMPTY);
    when(store.latest(connection, spec.seriesId())).thenReturn(old);
    when(store.matured(eq(connection), eq(spec.seriesId()), anyLong(), anyLong())).thenReturn(old);
    pilot.refresh(spec, now);
    var capture = ArgumentCaptor.forClass(ForecastRecord.class);
    verify(store).save(eq(connection), eq(spec.seriesId()), capture.capture(), anyString());
    assertEquals(2, capture.getValue().evaluatedPoints());
    assertTrue(capture.getValue().qualityWindow().open().isEmpty());
  }

  @Test
  void activationRequiresEvidenceAndPersistsOnlyAfterExplicitCall() throws Exception {
    assertThrows(IllegalArgumentException.class, () -> pilot.activate(spec.seriesId(), true));
    verify(store, never()).activate(any(), anyString(), anyBoolean());
    var unjudged =
        withWindow(record(context, 1, "READY"), ForecastQualityWindow.EMPTY);
    when(store.latest(connection, spec.seriesId())).thenReturn(unjudged);
    assertThrows(IllegalArgumentException.class, () -> pilot.activate(spec.seriesId(), true));
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

  @Test
  void changedApprovalNeverExposesOldContextOrQuality() throws Exception {
    var old = record(context, 1, "READY");
    var incompatible =
        new ForecastRecord(
            "b".repeat(64),
            old.generatedAt(),
            old.state(),
            old.strategy(),
            old.visibility(),
            old.context(),
            old.forecast(),
            old.quality(),
            old.evaluatedPoints(),
            old.evaluatedThrough(),
            old.baselineMae(),
            old.reason(),
            old.qualityWindow());
    when(store.latest(connection, spec.seriesId())).thenReturn(incompatible);
    assertNull(pilot.get(spec.seriesId()));
    assertNull(pilot.breach(spec.seriesId()));
    verify(client, never()).forecast(anyList(), anyInt());
  }

  @Test
  void breachIgnoresExpiredPointsWithoutExtendingPolicyHorizon() throws Exception {
    var threshold =
        new ForecastThresholdPolicy(
            spec.seriesId(),
            "v1",
            .5,
            ForecastThresholdPolicy.Direction.ABOVE,
            2,
            .9,
            "READY",
            ForecastThresholdPolicy.Visibility.SHADOW);
    spec =
        new ForecastPilotProperties.Series(
            spec.seriesId(),
            spec.metricId(),
            spec.environment(),
            "v1",
            "messages",
            spec.topics(),
            spec.groups(),
            new SeriesPreparationProfile(
                60000, 512, SeriesPreparationProfile.Transformation.GAUGE_LAST),
            2,
            1,
            threshold,
            .5,
            .8,
            2);
    var props = new ForecastPilotProperties();
    props.setEnabled(true);
    props.setSeries(List.of(spec));
    pilot = new ForecastPilotService(props, store, preparation, client, meters);
    long end = System.currentTimeMillis() - 90000;
    var oldContext = context(end);
    var base = forecast(oldContext, 1);
    var predicted =
        new MetricForecast(
            base.requestId(),
            base.seriesId(),
            base.definitionVersion(),
            base.inputFingerprint(),
            base.profileFingerprint(),
            base.outputUnit(),
            end,
            base.modelId(),
            base.modelRevision(),
            base.adapterVersion(),
            base.centralStatistic(),
            1,
            List.of(
                new MetricForecast.Point(end + 60000, 2, 1, 2, 3),
                new MetricForecast.Point(end + 120000, 1, 0, 1, 2)));
    var old =
        new ForecastRecord(
            ForecastPilotService.key(spec, oldContext),
            now,
            "READY",
            "TIMESFM",
            ForecastThresholdPolicy.Visibility.SHADOW,
            oldContext,
            predicted,
            null,
            0,
            0,
            Map.of(),
            "ready",
            null);
    when(store.latest(connection, spec.seriesId())).thenReturn(old);
    var result = pilot.breach(spec.seriesId());
    assertNotNull(result);
    assertFalse(result.threshold().breached());
    assertEquals(1, result.threshold().horizonPoints());
    assertEquals(end + 120000, result.windowEndAt());
    assertTrue(result.evaluatedAt() > end + 60000);
    verify(client, never()).forecast(anyList(), anyInt());
  }

  private ForecastPilotService pilotOf(ForecastPilotProperties.Series... series) {
    var props = new ForecastPilotProperties();
    props.setEnabled(true);
    props.setSeries(List.of(series));
    return new ForecastPilotService(props, store, preparation, client, meters);
  }

  private ForecastPilotProperties.Series withId(ForecastPilotProperties.Series s, String id) {
    return new ForecastPilotProperties.Series(
        id, s.metricId(), s.environment(), s.definitionVersion(), s.unit(), s.topics(),
        s.groups(), s.profile(), s.horizon(), s.seasonLength(), null, s.maxMae(),
        s.minimumCoverage(), s.minimumEvaluatedPoints());
  }

  private ForecastPilotProperties.Series withThreshold(double threshold) {
    var policy =
        new ForecastThresholdPolicy(
            spec.seriesId(), "v1", threshold, ForecastThresholdPolicy.Direction.ABOVE, 2, .9,
            "READY", ForecastThresholdPolicy.Visibility.SHADOW);
    return new ForecastPilotProperties.Series(
        spec.seriesId(), spec.metricId(), spec.environment(), "v1", "messages", spec.topics(),
        spec.groups(), spec.profile(), 2, 1, policy, spec.maxMae(), spec.minimumCoverage(),
        spec.minimumEvaluatedPoints());
  }

  private ForecastRecord withStrategy(ForecastRecord r, String strategy) {
    return new ForecastRecord(
        r.key(), r.generatedAt(), r.state(), strategy, r.visibility(), r.context(), r.forecast(),
        r.quality(), r.evaluatedPoints(), r.evaluatedThrough(), r.baselineMae(), r.reason(),
        r.qualityWindow());
  }

  private com.compagnonsdudev.kafkasqlexplorer.mcp.tools.ForecastMcpTools tools(
      ForecastPilotService service) {
    var policy = new com.compagnonsdudev.kafkasqlexplorer.mcp.McpProperties();
    policy.setAllowedForecastEnvironments(List.of("production"));
    return new com.compagnonsdudev.kafkasqlexplorer.mcp.tools.ForecastMcpTools(
        service,
        new com.compagnonsdudev.kafkasqlexplorer.mcp.guard.ToolGuard(
            policy, new com.compagnonsdudev.kafkasqlexplorer.mcp.guard.DlpScrubber(policy)));
  }

  @Test
  void aTimeoutSendsTheRestOfTheCycleToTheFallbackAndTheNextCycleRetries() throws Exception {
    when(preparation.prepare(anyString(), eq("v1"), eq("messages"), anyLong(), eq(spec.profile())))
        .thenReturn(context);
    when(client.forecast(anyList(), eq(2)))
        .thenThrow(new TimesFmInferenceException(TimesFmInferenceException.State.TIMEOUT));
    var cycle = pilotOf(spec, withId(spec, "b".repeat(64)));
    cycle.refresh();
    verify(client, times(1)).forecast(anyList(), eq(2));
    var capture = ArgumentCaptor.forClass(ForecastRecord.class);
    verify(store, times(2)).save(eq(connection), anyString(), capture.capture(), anyString());
    assertEquals("LAST_VALUE", capture.getAllValues().get(1).strategy());
    assertTrue(capture.getAllValues().get(1).reason().contains("TIMEOUT earlier in this cycle"));
    cycle.refresh();
    verify(client, times(2)).forecast(anyList(), eq(2));
  }

  @Test
  void aBusyModelIsNotTakenForAnOutage() throws Exception {
    when(preparation.prepare(anyString(), eq("v1"), eq("messages"), anyLong(), eq(spec.profile())))
        .thenReturn(context);
    when(client.forecast(anyList(), eq(2)))
        .thenThrow(new TimesFmInferenceException(TimesFmInferenceException.State.BUSY));
    pilotOf(spec, withId(spec, "b".repeat(64))).refresh();
    verify(client, times(2)).forecast(anyList(), eq(2));
  }

  @Test
  void anEvaluatedForecastBelowThresholdIsACompleteNegative() throws Exception {
    spec = withThreshold(5);
    when(store.latest(connection, spec.seriesId())).thenReturn(record(context, 1, "READY"));
    var result = tools(pilotOf(spec)).breaches();
    assertEquals(ForecastPilotService.BreachOutcome.NO_BREACH, result.data().getFirst().outcome());
    assertNotNull(result.data().getFirst().breach());
    assertTrue(result.coverage().complete());
    assertEquals(1, result.coverage().topicsRequested());
  }

  @Test
  void aFallbackForecastIsNotEvaluatedAndNamedInCoverage() throws Exception {
    spec = withThreshold(5);
    when(store.latest(connection, spec.seriesId()))
        .thenReturn(withStrategy(record(context, 1, "READY"), "LAST_VALUE"));
    var result = tools(pilotOf(spec)).breaches();
    var row = result.data().getFirst();
    assertEquals(ForecastPilotService.BreachOutcome.NOT_EVALUATED, row.outcome());
    assertTrue(row.reason().contains("LAST_VALUE fallback"));
    assertFalse(result.coverage().complete());
    assertEquals(List.of(spec.seriesId()), result.coverage().topicsNotReached());
    assertTrue(result.warnings().stream().anyMatch(w -> w.code().equals("FORECAST_NOT_EVALUATED")));
  }

  @Test
  void aSeriesWithoutPolicyIsReportedButNotCountedAsUnreached() throws Exception {
    var result = tools(pilotOf(spec)).breaches();
    assertEquals(ForecastPilotService.BreachOutcome.NO_POLICY, result.data().getFirst().outcome());
    assertTrue(result.coverage().complete());
    assertEquals(0, result.coverage().topicsRequested());
    verify(store, never()).open();
  }
}
