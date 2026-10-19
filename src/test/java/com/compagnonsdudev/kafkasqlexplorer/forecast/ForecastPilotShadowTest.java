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

class ForecastPilotShadowTest {
  private static final String SHADOW_REVISION = "c".repeat(40);
  private final ForecastPilotStore store = mock(ForecastPilotStore.class);
  private final MetricSeriesPreparationService preparation = mock(MetricSeriesPreparationService.class);
  private final TimesFmClient client = mock(TimesFmClient.class);
  private final TimesFmClient shadowClient = mock(TimesFmClient.class);
  private final Connection connection = mock(Connection.class);
  private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
  private final long now = System.currentTimeMillis() + 100000;
  private ForecastPilotProperties props;
  private ForecastPilotProperties.Series spec;
  private ForecastPilotService pilot;
  private PreparedMetricSeries context;

  private PreparedMetricSeries context(long end) {
    var points = new ArrayList<PreparedMetricSeries.Point>();
    for (int i = 0; i < 512; i++)
      points.add(new PreparedMetricSeries.Point(end - (511 - i) * 1000L, 1d, false, 1));
    return new PreparedMetricSeries(
        PreparedMetricSeries.Status.READY, "ready", spec.seriesId(), "v1", "messages", "messages",
        end - 512 * 1000L, end, spec.profile(), "profile", "input" + end, 512, 0, 0, points);
  }

  private MetricForecast forecast(PreparedMetricSeries c, double median, String model, String revision) {
    return new MetricForecast(
        "request", c.seriesId(), c.definitionVersion(), c.inputFingerprint(), c.profileFingerprint(),
        c.outputUnit(), c.toExclusive(), model, revision, "adapter", "MEDIAN", 1,
        List.of(
            new MetricForecast.Point(c.toExclusive() + 1000, median, 0, median, 3),
            new MetricForecast.Point(c.toExclusive() + 2000, median, 0, median, 3)));
  }

  private MetricForecast timesFm(PreparedMetricSeries c, double median) {
    return forecast(c, median, TimesFmClient.MODEL_ID, TimesFmClient.MODEL_REVISION);
  }

  private MetricForecast timestral(PreparedMetricSeries c, double median, String revision) {
    return forecast(c, median, TimesFmClient.Model.SHADOW.modelId(), revision);
  }

  private ForecastRecord primary(PreparedMetricSeries c, double median) throws Exception {
    return new ForecastRecord(
        ForecastPilotService.key(spec, c), now, "READY", "TIMESFM",
        ForecastThresholdPolicy.Visibility.SHADOW, c, timesFm(c, median), null, 0, 0, Map.of(),
        "ready", ForecastQualityWindow.EMPTY);
  }

  private ShadowForecastRecord shadowRecord(
      PreparedMetricSeries c, MetricForecast f, String revision, ForecastQualityWindow window)
      throws Exception {
    return new ShadowForecastRecord(
        ForecastPilotService.key(spec, c), now, "READY", "shadow", c, f, revision,
        window.openPoints(), 0, window, window, null, null, null, null, null);
  }

  private ShadowForecastRecord withCovariateForecast(ShadowForecastRecord r, MetricForecast f, String fingerprint) {
    return new ShadowForecastRecord(
        r.key(), r.generatedAt(), r.state(), r.reason(), r.context(), r.forecast(), r.revision(),
        r.evaluatedPoints(), r.evaluatedThrough(), r.shadowWindow(), r.primaryWindow(), "READY", "covariates",
        fingerprint, f, r.trial());
  }

  /** The produce rate of the lag's own topic, on the same grid. */
  private ForecastPilotProperties.ShadowCovariate produceRate(List<String> topics) {
    return new ForecastPilotProperties.ShadowCovariate(
        spec.seriesId(), "b".repeat(64), "orders-produced", "v1", "messages/s",
        SeriesPreparationProfile.Transformation.COUNTER_RATE, topics);
  }

  private PreparedMetricSeries covariateContext(PreparedMetricSeries target, PreparedMetricSeries.Status status) {
    var points = target.points().stream()
        .map(p -> new PreparedMetricSeries.Point(p.endAt(), 7d, false, 1)).toList();
    return new PreparedMetricSeries(
        status, "covariate", "b".repeat(64), "v1", "messages/s", "messages/s", target.fromInclusive(),
        target.toExclusive(), spec.profile(), "covariate-profile", "covariate-input", 512, 0, 0, points);
  }

  private void declare(ForecastPilotProperties.ShadowCovariate covariate, PreparedMetricSeries.Status status)
      throws Exception {
    props.setShadowCovariates(List.of(covariate));
    when(preparation.prepare(eq("b".repeat(64)), eq("v1"), eq("messages/s"), anyLong(), any()))
        .thenReturn(covariateContext(context, status));
    when(shadowClient.forecast(any(PreparedMetricSeries.class), anyList(), eq(2)))
        .thenReturn(timestral(context, 1, SHADOW_REVISION));
  }

  private ShadowForecastRecord savedShadow() throws Exception {
    var capture = ArgumentCaptor.forClass(ShadowForecastRecord.class);
    verify(store).saveShadow(eq(connection), eq(spec.seriesId()), capture.capture(), anyString());
    return capture.getValue();
  }

  /** TimesFM's previous forecast matured: median 2 against realised 1, so its MAE is 1. */
  private PreparedMetricSeries maturedPrimary() throws Exception {
    var old = context(now - 2000);
    when(store.latest(connection, spec.seriesId())).thenReturn(primary(old, 2));
    when(store.matured(eq(connection), eq(spec.seriesId()), anyLong(), anyLong()))
        .thenReturn(primary(old, 2));
    return old;
  }

  @BeforeEach
  void setup() throws Exception {
    spec =
        new ForecastPilotProperties.Series(
            "a".repeat(64), "metric", "production", "v1", "messages", List.of("orders"), List.of(),
            new SeriesPreparationProfile(1000, 512, SeriesPreparationProfile.Transformation.GAUGE_LAST),
            2, 1, null, .5, .8, 2);
    props = new ForecastPilotProperties();
    props.setEnabled(true);
    props.setSeries(List.of(spec));
    context = context(now);
    pilot =
        new ForecastPilotService(
            props, store, preparation, client, new ShadowForecastClient(shadowClient), meters);
    when(store.open()).thenReturn(connection);
    when(store.acquire(eq(connection), anyString())).thenReturn(true);
    when(preparation.prepare(eq(spec.seriesId()), eq("v1"), eq("messages"), anyLong(), eq(spec.profile())))
        .thenReturn(context);
    when(client.forecast(anyList(), eq(2))).thenReturn(List.of(timesFm(context, 1)));
    when(shadowClient.forecast(anyList(), eq(2)))
        .thenReturn(List.of(timestral(context, 1, SHADOW_REVISION)));
  }

  @Test
  void withoutAShadowClientNothingShadowIsReadOrWritten() throws Exception {
    pilot = new ForecastPilotService(props, store, preparation, client, meters);
    pilot.refresh(spec, now);
    verify(store).save(eq(connection), eq(spec.seriesId()), any(), anyString());
    verify(store, never()).latestShadow(any(), anyString());
    verify(store, never()).saveShadow(any(), anyString(), any(), anyString());
    assertFalse(pilot.shadowEnabled());
  }

  @Test
  void theShadowRunsOnlyAfterTimesFmsRecordIsCommittedAndNeverTouchesIt() throws Exception {
    pilot.refresh(spec, now);
    var order = inOrder(store, connection, shadowClient);
    order.verify(store).save(eq(connection), eq(spec.seriesId()), any(), anyString());
    order.verify(connection).commit();
    order.verify(shadowClient).forecast(anyList(), eq(2));
    order.verify(store).saveShadow(eq(connection), eq(spec.seriesId()), any(), anyString());
    order.verify(connection).commit();
    var r = savedShadow();
    assertEquals("READY", r.state());
    assertEquals(SHADOW_REVISION, r.forecast().modelRevision());
    assertEquals(ForecastPilotService.key(spec, context), r.key());
    verify(store, never()).activate(any(), anyString(), anyBoolean());
  }

  @Test
  void aShadowFailureRollsBackOnlyItself() throws Exception {
    doThrow(new IllegalStateException("Shadow publication fenced or duplicate"))
        .when(store).saveShadow(any(), anyString(), any(), anyString());
    pilot.refresh(spec, now);
    var order = inOrder(store, connection);
    order.verify(store).save(eq(connection), eq(spec.seriesId()), any(), anyString());
    order.verify(connection).commit();
    order.verify(connection).rollback();
    assertEquals(1.0, meters.get("explorer_forecast_pilot_failures_total").tag("state", "SHADOW").counter().count());
  }

  @Test
  void aShadowOutageIsLatchedOnItsOwnAndNeverTakesTimesFmOut() throws Exception {
    when(shadowClient.forecast(anyList(), eq(2)))
        .thenThrow(new TimesFmInferenceException(TimesFmInferenceException.State.TIMEOUT));
    pilot.refresh(spec, now);
    assertEquals("UNAVAILABLE", savedShadow().state());
    var next = context(now + 1000);
    when(preparation.prepare(eq(spec.seriesId()), eq("v1"), eq("messages"), anyLong(), eq(spec.profile())))
        .thenReturn(next);
    when(client.forecast(anyList(), eq(2))).thenReturn(List.of(timesFm(next, 1)));
    pilot.refresh(spec, now + 1000);
    verify(client, times(2)).forecast(anyList(), eq(2));
    verify(shadowClient, times(1)).forecast(anyList(), eq(2));
    assertEquals(
        1.0,
        meters.get("explorer_forecast_pilot_skipped_total").tag("state", "SHADOW_OUT_THIS_CYCLE").counter().count());
  }

  @Test
  void bothModelsAreScoredOnTheSameRealisedValues() throws Exception {
    var old = maturedPrimary();
    when(store.shadowAt(connection, spec.seriesId(), old.toExclusive()))
        .thenReturn(shadowRecord(old, timestral(old, 1, SHADOW_REVISION), null, ForecastQualityWindow.EMPTY));
    pilot.refresh(spec, now);
    var r = savedShadow();
    assertEquals(0, r.shadowWindow().quality().mae());
    assertEquals(1, r.primaryWindow().quality().mae());
    assertEquals(r.shadowWindow().baselineMae(), r.primaryWindow().baselineMae());
    assertEquals(2, r.evaluatedPoints());
    assertEquals(now, r.evaluatedThrough());
    assertEquals(SHADOW_REVISION, r.revision());
  }

  @Test
  void aShadowForecastOfAnotherSpecificationIsNotScored() throws Exception {
    var old = maturedPrimary();
    var stale = shadowRecord(old, timestral(old, 1, SHADOW_REVISION), null, ForecastQualityWindow.EMPTY);
    when(store.shadowAt(connection, spec.seriesId(), old.toExclusive()))
        .thenReturn(new ShadowForecastRecord(
            "f".repeat(64), now, "READY", "shadow", old, stale.forecast(), null, 0, 0, null, null,
            null, null, null, null, null));
    pilot.refresh(spec, now);
    assertEquals(0, savedShadow().evaluatedPoints());
  }

  @Test
  void aNewShadowPinStartsItsEvaluationOver() throws Exception {
    var old = maturedPrimary();
    var earlier =
        new ForecastQualityWindow.Cohort(
            now - 5000, 2, new ForecastBacktestEvaluator.Evaluation(5, null, 5, 0, 3), Map.of("LAST_VALUE", 0d), 1);
    var window = ForecastQualityWindow.EMPTY.add(earlier, .5, null);
    when(store.latestShadow(connection, spec.seriesId()))
        .thenReturn(shadowRecord(old, timestral(old, 1, SHADOW_REVISION), "d".repeat(40), window));
    when(store.shadowAt(connection, spec.seriesId(), old.toExclusive()))
        .thenReturn(shadowRecord(old, timestral(old, 1, SHADOW_REVISION), "d".repeat(40), window));
    pilot.refresh(spec, now);
    var r = savedShadow();
    assertEquals(SHADOW_REVISION, r.revision());
    assertEquals(1, r.shadowWindow().open().size());
    assertEquals(2, r.evaluatedPoints());
  }

  @Test
  void theComparisonSaysWhatTheSharedBlockStillNeeds() throws Exception {
    var cohort =
        new ForecastQualityWindow.Cohort(
            now, 2, new ForecastBacktestEvaluator.Evaluation(0, null, 0, 1, 3), Map.of("LAST_VALUE", 1d), 1);
    var window = ForecastQualityWindow.EMPTY.add(cohort, .5, null);
    when(store.latestShadow(connection, spec.seriesId()))
        .thenReturn(shadowRecord(context, timestral(context, 1, SHADOW_REVISION), SHADOW_REVISION, window));
    var comparison = pilot.shadowComparison(spec.seriesId());
    assertEquals("mistralai/Timestral-28M", comparison.modelId());
    assertEquals(SHADOW_REVISION, comparison.revision());
    assertFalse(comparison.wouldQualify());
    assertEquals("The first shared quality block holds 2 of 120 realised points", comparison.qualificationBlocker());
    assertEquals(0, comparison.shadowQuality().mae());
  }

  @Test
  void aDeclaredCovariateIsSentOnTheTargetsGridBesideTheUnivariateRun() throws Exception {
    declare(produceRate(List.of("orders")), PreparedMetricSeries.Status.READY);
    pilot.refresh(spec, now);
    var sent = ArgumentCaptor.forClass(List.class);
    verify(shadowClient).forecast(eq(context), sent.capture(), eq(2));
    var covariate = (PreparedMetricSeries) sent.getValue().getFirst();
    assertEquals(
        context.points().stream().map(PreparedMetricSeries.Point::endAt).toList(),
        covariate.points().stream().map(PreparedMetricSeries.Point::endAt).toList());
    verify(shadowClient).forecast(List.of(context), 2);
    verify(client, never()).forecast(any(PreparedMetricSeries.class), anyList(), anyInt());
    var r = savedShadow();
    assertEquals("READY", r.state());
    assertEquals("READY", r.covariateState());
    assertEquals(ForecastPilotService.covariateFingerprint(props.covariatesFor(spec.seriesId())), r.covariateFingerprint());
  }

  @Test
  void aCovariateNotYetReadyWaitsWithoutHoldingTheUnivariateRun() throws Exception {
    declare(produceRate(List.of("orders")), PreparedMetricSeries.Status.WARMING_UP);
    pilot.refresh(spec, now);
    verify(shadowClient, never()).forecast(any(PreparedMetricSeries.class), anyList(), anyInt());
    var r = savedShadow();
    assertEquals("READY", r.state());
    assertEquals("WAITING", r.covariateState());
    assertTrue(r.covariateReason().contains("orders-produced is WARMING_UP"));
    assertNull(r.covariateFingerprint());
  }

  @Test
  void aCovariateOutsideTheTargetsTopicsIsRefusedBeforeAnyRead() throws Exception {
    declare(produceRate(List.of("payments")), PreparedMetricSeries.Status.READY);
    pilot.refresh(spec, now);
    verify(preparation, never()).prepare(eq("b".repeat(64)), anyString(), anyString(), anyLong(), any());
    assertEquals("REFUSED", savedShadow().covariateState());
  }

  @Test
  void threeModelsAreScoredOnTheSameCohortWhenAllThreeForecastIt() throws Exception {
    declare(produceRate(List.of("orders")), PreparedMetricSeries.Status.READY);
    var old = maturedPrimary();
    var fingerprint = ForecastPilotService.covariateFingerprint(props.covariatesFor(spec.seriesId()));
    var scored = shadowRecord(old, timestral(old, 1.5, SHADOW_REVISION), null, ForecastQualityWindow.EMPTY);
    when(store.shadowAt(connection, spec.seriesId(), old.toExclusive()))
        .thenReturn(withCovariateForecast(scored, timestral(old, 1, SHADOW_REVISION), fingerprint));
    pilot.refresh(spec, now);
    var t = savedShadow().trial();
    assertEquals(0, t.multivariateWindow().quality().mae());
    assertEquals(.5, t.univariateWindow().quality().mae());
    assertEquals(1, t.primaryWindow().quality().mae());
    assertEquals(2, t.evaluatedPoints());
    assertEquals(fingerprint, t.fingerprint());
  }

  @Test
  void aForecastMadeUnderAnotherDeclarationIsNotTrialEvidence() throws Exception {
    declare(produceRate(List.of("orders")), PreparedMetricSeries.Status.READY);
    var old = maturedPrimary();
    var scored = shadowRecord(old, timestral(old, 1, SHADOW_REVISION), null, ForecastQualityWindow.EMPTY);
    when(store.shadowAt(connection, spec.seriesId(), old.toExclusive()))
        .thenReturn(withCovariateForecast(scored, timestral(old, 1, SHADOW_REVISION), "e".repeat(64)));
    pilot.refresh(spec, now);
    var r = savedShadow();
    assertEquals(0, r.trial().evaluatedPoints());
    assertEquals(2, r.evaluatedPoints());
  }

  @Test
  void covariateDeclarationsAreBounded() {
    var p = new ForecastPilotProperties();
    p.setSeries(List.of(spec));
    p.setShadowCovariates(List.of(produceRate(List.of("payments"))));
    assertThrows(IllegalArgumentException.class, p::validate);
    var five = java.util.stream.IntStream.range(0, 5)
        .mapToObj(i -> new ForecastPilotProperties.ShadowCovariate(
            spec.seriesId(), Integer.toHexString(i + 11).repeat(64).substring(0, 64), "m" + i, "v1", "u",
            SeriesPreparationProfile.Transformation.GAUGE_MEAN, List.of("orders")))
        .toList();
    p.setShadowCovariates(five);
    assertThrows(IllegalArgumentException.class, p::validate);
    assertThrows(IllegalArgumentException.class, () -> new ForecastPilotProperties.ShadowCovariate(
        spec.seriesId(), spec.seriesId(), "self", "v1", "u", SeriesPreparationProfile.Transformation.GAUGE_MEAN,
        List.of("orders")));
  }

  @Test
  void theComparisonCarriesTheThreeWayTrialOnlyWhenCovariatesAreDeclared() throws Exception {
    when(store.latestShadow(connection, spec.seriesId()))
        .thenReturn(shadowRecord(context, timestral(context, 1, SHADOW_REVISION), SHADOW_REVISION, ForecastQualityWindow.EMPTY));
    assertNull(pilot.shadowComparison(spec.seriesId()).covariates());
    props.setShadowCovariates(List.of(produceRate(List.of("orders"))));
    var trial = pilot.shadowComparison(spec.seriesId()).covariates();
    assertEquals(List.of("orders-produced"), trial.covariateMetricIds());
    assertFalse(trial.wouldQualify());
    assertEquals(0, trial.evaluatedPoints());
  }
}
