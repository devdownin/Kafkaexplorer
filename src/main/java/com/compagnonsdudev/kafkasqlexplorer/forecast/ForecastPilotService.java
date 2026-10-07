// SPDX-License-Identifier: AGPL-3.0-or-later
package com.compagnonsdudev.kafkasqlexplorer.forecast;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.LoggerFactory;

/** No inference on read paths. All resource approval is deployment-owned. */
public final class ForecastPilotService {
  private final ForecastPilotProperties properties;
  private final ForecastPilotStore store;
  private final MetricSeriesPreparationService preparation;
  private final TimesFmClient client;
  private final MeterRegistry meters;
  private final AtomicBoolean running = new AtomicBoolean();
  private int nextSeries;
  private volatile long nextScheduledAt;
  /**
   * Set when TimesFM timed out or was unreachable earlier in the running cycle. A hung service cost
   * the whole timeout once per series, so a 60 s cycle reached two series and the rest went stale.
   */
  private volatile TimesFmInferenceException.State modelOutThisCycle;

  public ForecastPilotService(
      ForecastPilotProperties properties,
      ForecastPilotStore store,
      MetricSeriesPreparationService preparation,
      TimesFmClient client,
      MeterRegistry meters) {
    this.properties = properties;
    this.store = store;
    this.preparation = preparation;
    this.client = client;
    this.meters = meters;
    properties.validate();
    nextScheduledAt = System.currentTimeMillis() + properties.getInterval().toMillis();
  }

  /** Fixed-delay estimate; an in-progress cycle has no next timestamp yet. */
  public Long nextScheduledAt() { return running.get() ? null : nextScheduledAt; }

  public List<ForecastPilotProperties.Series> series() {
    return properties.getSeries();
  }

  public ForecastPilotProperties.Series resolve(String id) {
    return properties.resolve(id);
  }

  public ForecastRecord get(String id) throws Exception {
    var spec = resolve(id);
    try (var c = store.open()) {
      var r = store.latest(c, id);
      if (r == null || !r.key().equals(key(spec, r.context()))) return null;
      boolean stale =
          r.forecast() != null
              && (r.forecast().points().isEmpty()
                  || r.forecast().points().getLast().at() < System.currentTimeMillis());
      var visibility =
          !stale
                  && r.state().equals("READY")
                  && r.strategy().equals("TIMESFM")
                  && store.active(c, id)
              ? ForecastThresholdPolicy.Visibility.ACTIVE
              : ForecastThresholdPolicy.Visibility.SHADOW;
      return new ForecastRecord(
          r.key(),
          r.generatedAt(),
          stale ? "STALE" : r.state(),
          r.strategy(),
          visibility,
          r.context(),
          r.forecast(),
          r.quality(),
          r.evaluatedPoints(),
          r.evaluatedThrough(),
          r.baselineMae(),
          stale ? "No current forecast horizon" : r.reason(),
          r.qualityWindow());
    }
  }

  /** Driven by {@link ForecastPilotScheduler}, never by the application's shared scheduler. */
  public void refresh() {
    if (!running.compareAndSet(false, true)) return;
    modelOutThisCycle = null;
    try {
      var approved = series();
      long deadline =
          System.nanoTime() + Math.min(properties.getInterval().toMillis(), 60000) * 1000000;
      for (int i = 0; i < approved.size(); i++) {
        if (System.nanoTime() >= deadline) {
          counter("explorer_forecast_pilot_skipped_total", "CYCLE_BUDGET").increment();
          break;
        }
        var s = approved.get(nextSeries);
        nextSeries = (nextSeries + 1) % approved.size();
        try {
          refresh(s, System.currentTimeMillis());
        } catch (Exception e) {
          counter("explorer_forecast_pilot_failures_total", "DEPENDENCY").increment();
          LoggerFactory.getLogger(getClass()).warn("Forecast pilot dependency unavailable");
        }
      }
    } finally {
      modelOutThisCycle = null;
      nextScheduledAt = System.currentTimeMillis() + properties.getInterval().toMillis();
      running.set(false);
    }
  }

  public void refresh(ForecastPilotProperties.Series spec, long cutoff) throws Exception {
    String owner = UUID.randomUUID().toString();
    try (var c = store.open()) {
      if (!store.acquire(c, owner)) {
        c.rollback();
        counter("explorer_forecast_pilot_skipped_total", "LEASE_BUSY").increment();
        return;
      }
      store.purge(c, System.currentTimeMillis() - properties.getRetention().toMillis());
      store.checkSeriesBudget(c, spec.seriesId(), properties.getMaxSeries());
      var context =
          preparation.prepare(
              spec.seriesId(), spec.definitionVersion(), spec.unit(), cutoff, spec.profile());
      String key = key(spec, context);
      if (store.contains(c, key)) {
        c.rollback();
        return;
      }
      var previous = store.latest(c, spec.seriesId());
      if (previous != null && previous.key().equals(key)) {
        c.rollback();
        return;
      }
      if (previous != null
          && (!previous.key().equals(key(spec, previous.context()))
              || !previous.context().definitionVersion().equals(context.definitionVersion())
              || !previous.context().profileFingerprint().equals(context.profileFingerprint()))) {
        previous = null;
        store.disable(c, spec.seriesId());
      }
      ForecastBacktestEvaluator.Evaluation quality = previous == null ? null : previous.quality();
      int evaluated = previous == null ? 0 : previous.evaluatedPoints();
      long evaluatedThrough = previous == null ? 0 : previous.evaluatedThrough();
      var matured =
          store.matured(
              c,
              spec.seriesId(),
              context.toExclusive() - spec.horizon() * spec.profile().stepMillis(),
              evaluatedThrough);
      Map<String, Double> baselineScores = previous == null ? Map.of() : previous.baselineMae();
      var window = previous == null ? ForecastQualityWindow.EMPTY : previous.qualityWindow();
      boolean degraded = previous != null && previous.state().equals("DEGRADED");
      if (context.status() == PreparedMetricSeries.Status.READY
          && matured != null
          && matured.forecast() != null
          && matured.strategy().equals("TIMESFM")
          && matured.key().equals(key(spec, matured.context()))
          && matured.context().definitionVersion().equals(context.definitionVersion())
          && matured.context().profileFingerprint().equals(context.profileFingerprint())) {
        // Only the realised horizon must be observed. Requiring the whole 512-point context to be
        // free of imputation suspended evaluation for 512 steps after a single missed refresh.
        var observed = new HashMap<Long, Double>();
        context.points().stream()
            .filter(p -> !p.imputed() && p.value() != null)
            .forEach(p -> observed.put(p.endAt(), p.value()));
        var points = matured.forecast().points();
        if (points.stream().allMatch(p -> observed.containsKey(p.at()))) {
          var actual = points.stream().map(p -> observed.get(p.at())).toList();
          var training =
              matured.context().points().stream().map(PreparedMetricSeries.Point::value).toList();
          var cohortQuality =
              new ForecastBacktestEvaluator()
                  .evaluate(training, actual, points, spec.seasonLength());
          evaluated += actual.size();
          evaluatedThrough = points.getLast().at();
          var scores = new LinkedHashMap<String, Double>();
          ForecastBaselines.predict(training, actual.size(), spec.seasonLength())
              .forEach((name, p) -> scores.put(name, ForecastBaselines.mae(actual, p)));
          double level = actual.stream().mapToDouble(Math::abs).average().orElseThrow();
          window =
              window.add(
                  new ForecastQualityWindow.Cohort(
                      evaluatedThrough, actual.size(), cohortQuality, scores, level),
                  spec.minimumCoverage(),
                  spec.maxMae());
          degraded |= evaluated >= spec.minimumEvaluatedPoints() && window.drifted();
        }
      }
      if (window.quality() != null) {
        quality = window.quality();
        baselineScores = window.baselineMae();
      }
      if (degraded) {
        store.disable(c, spec.seriesId());
        if (previous == null || !previous.state().equals("DEGRADED"))
          counter("explorer_forecast_drift_total", "DEGRADED").increment();
      }
      MetricForecast forecast = null;
      String strategy = "UNAVAILABLE";
      String reason = context.reason();
      String state = degraded ? "DEGRADED" : context.status().name();
      if (context.status() == PreparedMetricSeries.Status.READY) {
        var outage = modelOutThisCycle;
        if (outage != null) {
          forecast = fallback(context, spec);
          strategy = fallbackStrategy(spec);
          reason =
              "TimesFM "
                  + outage
                  + " earlier in this cycle, not called again until the next one; point baseline"
                  + " has no calibrated interval";
          counter("explorer_forecast_pilot_skipped_total", "MODEL_OUT_THIS_CYCLE").increment();
        } else {
          try {
            forecast = client.forecast(List.of(context), spec.horizon()).getFirst();
            strategy = "TIMESFM";
          } catch (TimesFmInferenceException e) {
            // BUSY and INVALID_OUTPUT say nothing about the next series; a timeout or an
            // unreachable service does.
            if (e.state() == TimesFmInferenceException.State.TIMEOUT
                || e.state() == TimesFmInferenceException.State.UNAVAILABLE)
              modelOutThisCycle = e.state();
            forecast = fallback(context, spec);
            strategy = fallbackStrategy(spec);
            reason = "TimesFM " + e.state() + "; point baseline has no calibrated interval";
          }
        }
        state = degraded ? "DEGRADED" : "READY";
      }
      counter("explorer_forecast_strategy_total", strategy).increment();
      var visibility =
          store.active(c, spec.seriesId()) && !degraded && strategy.equals("TIMESFM")
              ? ForecastThresholdPolicy.Visibility.ACTIVE
              : ForecastThresholdPolicy.Visibility.SHADOW;
      var record =
          new ForecastRecord(
              key,
              System.currentTimeMillis(),
              state,
              strategy,
              visibility,
              context,
              forecast,
              quality,
              evaluated,
              evaluatedThrough,
              baselineScores,
              reason,
              window);
      store.save(c, spec.seriesId(), record, owner);
      store.purge(c, System.currentTimeMillis() - properties.getRetention().toMillis());
      c.commit();
    }
  }

  /**
   * Activation is explicit and blocked until a judged block shows TimesFM no worse than every
   * baseline on realised measurements, with no failed block since.
   */
  public void activate(String id, boolean active) throws Exception {
    var spec = resolve(id);
    try (var c = store.open()) {
      if (!store.acquire(c, UUID.randomUUID().toString()))
        throw new IllegalArgumentException("Pilot is refreshing; retry activation");
      var r = store.latest(c, id);
      if (active
          && (r == null
              || !r.key().equals(key(spec, r.context()))
              || !r.hasRealisedQuality()
              || !r.state().equals("READY")
              || r.forecast() == null
              || r.forecast().points().getLast().at() < System.currentTimeMillis()
              || r.evaluatedPoints() < spec.minimumEvaluatedPoints()
              || r.qualityWindow().lastBlock() == null
              || r.qualityWindow().failedBlocks() > 0
              || !r.qualityWindow().lastBlock().eligibleForActivation(spec.maxMae())))
        throw new IllegalArgumentException(
            "Activation requires realised quality and all four baseline comparisons");
      store.activate(c, id, active);
      c.commit();
    }
  }

  public record PredictedBreach(
      ForecastThresholdEvaluator.Breach threshold,
      String resultKey,
      long evaluatedAt,
      long windowEndAt,
      long generatedAt,
      long historyEndAt,
      String modelId,
      String modelRevision,
      String inputFingerprint,
      String profileFingerprint) {}

  public enum BreachOutcome { BREACH, NO_BREACH, NO_POLICY, NOT_EVALUATED }

  /**
   * Why a series has or has not a predicted breach. "No breach" is only said of a forecast that was
   * actually evaluated: a fallback, a stale horizon or a missing record is {@code NOT_EVALUATED},
   * because reporting it as a silent absence answered "nothing predicted" exactly while TimesFM
   * was down.
   */
  public record BreachEvaluation(
      String seriesId, BreachOutcome outcome, String reason, PredictedBreach breach) {
    static BreachEvaluation notEvaluated(String id, String reason) {
      return new BreachEvaluation(id, BreachOutcome.NOT_EVALUATED, reason, null);
    }
  }

  public PredictedBreach breach(String id) throws Exception {
    return evaluateBreach(id).breach();
  }

  public BreachEvaluation evaluateBreach(String id) throws Exception {
    var spec = resolve(id);
    if (spec.threshold() == null)
      return new BreachEvaluation(id, BreachOutcome.NO_POLICY, "No threshold policy is configured", null);
    var r = get(id);
    if (r == null) return BreachEvaluation.notEvaluated(id, "No forecast compatible with the current specification");
    if (r.forecast() == null)
      return BreachEvaluation.notEvaluated(id, "Latest result has no forecast (" + r.state() + "): " + r.reason());
    if (!r.strategy().equals("TIMESFM"))
      return BreachEvaluation.notEvaluated(
          id, "Latest forecast is a " + r.strategy() + " fallback without an interval: " + r.reason());
    if (!r.state().equals("READY"))
      return BreachEvaluation.notEvaluated(id, "Forecast is " + r.state() + ": " + r.reason());
    var p = spec.threshold();
    long evaluatedAt = System.currentTimeMillis();
    var window = r.forecast().points().subList(0, p.horizonPoints());
    var future = window.stream().filter(point -> point.at() > evaluatedAt).toList();
    if (future.isEmpty())
      return BreachEvaluation.notEvaluated(id, "Every point of the policy horizon has elapsed");
    var original = r.forecast();
    var current =
        new MetricForecast(
            original.requestId(),
            original.seriesId(),
            original.definitionVersion(),
            original.inputFingerprint(),
            original.profileFingerprint(),
            original.outputUnit(),
            original.historyEndAt(),
            original.modelId(),
            original.modelRevision(),
            original.adapterVersion(),
            original.centralStatistic(),
            original.durationMillis(),
            future);
    var effective =
        new ForecastThresholdPolicy(
            p.seriesId(),
            p.definitionVersion(),
            p.threshold(),
            p.direction(),
            p.horizonPoints(),
            .9,
            r.context().status().name(),
            r.visibility());
    var breach = new ForecastThresholdEvaluator().evaluate(current, effective);
    var predicted = new PredictedBreach(
        breach,
        r.key(),
        evaluatedAt,
        window.getLast().at(),
        r.generatedAt(),
        r.context().toExclusive(),
        r.forecast().modelId(),
        r.forecast().modelRevision(),
        r.forecast().inputFingerprint(),
        r.forecast().profileFingerprint());
    return new BreachEvaluation(
        id,
        breach.breached() ? BreachOutcome.BREACH : BreachOutcome.NO_BREACH,
        breach.breached() ? "Conservative bound crosses the threshold" : "Conservative bound stays within the threshold",
        predicted);
  }

  private Counter counter(String name, String state) {
    return Counter.builder(name).tag("state", state).register(meters);
  }

  public static String key(ForecastPilotProperties.Series s, PreparedMetricSeries c)
      throws Exception {
    String raw =
        s.toString()
            + "|"
            + s.definitionVersion()
            + "|"
            + c.inputFingerprint()
            + "|"
            + c.profileFingerprint()
            + "|"
            + c.toExclusive()
            + "|"
            + s.horizon()
            + "|"
            + TimesFmClient.MODEL_REVISION;
    return java.util.HexFormat.of()
        .formatHex(
            MessageDigest.getInstance("SHA-256").digest(raw.getBytes(StandardCharsets.UTF_8)));
  }

  private static String fallbackStrategy(ForecastPilotProperties.Series s) {
    return s.seasonLength() > 1 ? "SEASONAL_NAIVE" : "LAST_VALUE";
  }

  private static MetricForecast fallback(PreparedMetricSeries c, ForecastPilotProperties.Series s) {
    var train = c.points().stream().map(PreparedMetricSeries.Point::value).toList();
    var values =
        ForecastBaselines.predict(train, s.horizon(), s.seasonLength())
            .get(fallbackStrategy(s));
    var points = new java.util.ArrayList<MetricForecast.Point>();
    for (int i = 0; i < values.size(); i++) {
      double v = values.get(i);
      points.add(
          new MetricForecast.Point(
              c.toExclusive() + (i + 1) * c.profile().stepMillis(), v, v, v, v));
    }
    return new MetricForecast(
        UUID.randomUUID().toString(),
        c.seriesId(),
        c.definitionVersion(),
        c.inputFingerprint(),
        c.profileFingerprint(),
        c.outputUnit(),
        c.toExclusive(),
        "BASELINE",
        "v1",
        "kex-baseline-v1",
        "POINT",
        0,
        points);
  }
}
