// SPDX-License-Identifier: AGPL-3.0-or-later
package com.compagnonsdudev.kafkasqlexplorer.mcp.tools;

import com.compagnonsdudev.kafkasqlexplorer.domain.MetricConfig;
import com.compagnonsdudev.kafkasqlexplorer.forecast.*;
import com.compagnonsdudev.kafkasqlexplorer.mcp.contract.*;
import com.compagnonsdudev.kafkasqlexplorer.mcp.guard.*;
import com.compagnonsdudev.kafkasqlexplorer.mcp.observability.ToolCategory;
import com.compagnonsdudev.kafkasqlexplorer.service.MetricService;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;

/**
 * All sources are authorized from operator provenance BEFORE any forecast/history read.
 *
 * <p>Every exit scrubs the same identity strings the catalogue does. The catalogue alone used to:
 * a value masked in {@code kex_list_forecastable_metrics} came back in clear from
 * {@code kex_forecast_metric} on the same series, since the interceptor scrubs parameters only.
 * The caller's quarantine is the interceptor's, keyed on the authenticated identity; a series id
 * passed to {@code checkNotQuarantined} matched nothing that is ever quarantined.
 */
public final class ForecastMcpTools implements ReadOnlyMcpTools {
  private final ForecastPilotService pilot;
  private final ToolGuard guard;
  /** The metrics' own thresholds, for {@code kex_list_forecast_threshold_reaches}; null without the service. */
  private final MetricService metrics;

  public ForecastMcpTools(ForecastPilotService pilot, ToolGuard guard) {
    this(pilot, guard, null);
  }

  public ForecastMcpTools(ForecastPilotService pilot, ToolGuard guard, MetricService metrics) {
    this.pilot = pilot;
    this.guard = guard;
    this.metrics = metrics;
  }

  @Override
  public ToolCategory category() {
    return ToolCategory.DIAGNOSTIC;
  }

  public record Metric(
      String seriesId, String metricId, String environment, int horizon, String unit,
      Sources sources) {}

  public record Sources(String definitionVersion, List<String> topics, List<String> groups,
                        boolean complete) {}

  private Sources sources(ForecastPilotProperties.Series series) {
    // A redacted identifier must never become a navigable resource name.
    var topics = series.topics().stream().filter(t -> t.equals(guard.dlp().scrub(t))).toList();
    var groups = series.groups().stream().filter(g -> g.equals(guard.dlp().scrub(g))).toList();
    String version = guard.dlp().scrub(series.definitionVersion());
    boolean complete = topics.size() == series.topics().size() && groups.size() == series.groups().size()
        && series.environment().equals(guard.dlp().scrub(series.environment()))
        && series.definitionVersion().equals(version);
    return new Sources(version, topics, groups, complete);
  }

  private ForecastPilotProperties.Series authorize(String id) {
    return authorize(pilot, guard, id);
  }

  /** One authorization for every forecast toolset: operator provenance, then scope, before any read. */
  static ForecastPilotProperties.Series authorize(ForecastPilotService pilot, ToolGuard guard, String id) {
    ForecastPilotProperties.Series s;
    try {
      s = pilot.resolve(id);
    } catch (IllegalArgumentException e) {
      throw new McpToolException(
          McpErrorCode.OUT_OF_SCOPE, "Series is outside the configured forecast pilot");
    }
    guard.checkForecastEnvironment(s.environment());
    guard.checkTopicScope(s.topics());
    guard.checkGroupScope(s.groups());
    return s;
  }

  private List<ForecastPilotProperties.Series> allowed() {
    return pilot.series().stream()
        .filter(
            s -> {
              try {
                authorize(s.seriesId());
                return true;
              } catch (McpToolException e) {
                return false;
              }
            })
        .toList();
  }

  @McpTool(
      name = "kex_list_forecastable_metrics",
      description =
          "List operator-approved metrics visible in this resource scope. Does not run SQL or"
              + " inference.",
      annotations =
          @McpTool.McpAnnotations(
              readOnlyHint = true,
              destructiveHint = false,
              openWorldHint = false))
  public ToolResult<List<Metric>> catalog() {
    var rows =
        allowed().stream()
            .map(
                s ->
                    new Metric(
                        s.seriesId(),
                        guard.dlp().scrub(s.metricId()),
                        guard.dlp().scrub(s.environment()),
                        s.horizon(),
                        guard.dlp().scrub(s.unit()),
                        sources(s)))
            .toList();
    return result(rows, Coverage.exhausted(rows.size(), 0, 0));
  }

  @McpTool(
      name = "kex_metric_history",
      description =
          "Read at most 512 prepared history points from an existing durable forecast. Missing data"
              + " is unmeasured. Never reruns collection.",
      annotations =
          @McpTool.McpAnnotations(
              readOnlyHint = true,
              destructiveHint = false,
              openWorldHint = false))
  public ToolResult<Measured<PreparedMetricSeries>> history(
      @McpToolParam(description = "Approved series id") String seriesId) {
    authorize(seriesId);
    var r = read(seriesId);
    return result(
        r == null
            ? Measured.unmeasured(absence(seriesId))
            : Measured.of(scrub(r.context())),
        oneSeries(r == null ? 0 : r.context().points().size()));
  }

  @McpTool(
      name = "kex_forecast_metric",
      description =
          "Read an existing durable forecast. Baselines are explicitly marked and have no"
              + " calibrated confidence interval. Never starts inference.",
      annotations =
          @McpTool.McpAnnotations(
              readOnlyHint = true,
              destructiveHint = false,
              openWorldHint = false))
  public ToolResult<Measured<ForecastRecord>> get(
      @McpToolParam(description = "Approved series id") String seriesId) {
    authorize(seriesId);
    var r = read(seriesId);
    return result(
        r == null ? Measured.unmeasured(absence(seriesId)) : Measured.of(scrub(r)),
        oneSeries(r == null ? 0 : 1));
  }

  public record Quality(
      ForecastBacktestEvaluator.Evaluation timesfmMetrics,
      Map<String, Double> baselineMae,
      int evaluatedPoints,
      long evaluatedThrough,
      String currentStrategy,
      String currentState,
      ForecastPilotService.ActivationReadiness activation) {}

  @McpTool(
      name = "kex_get_forecast_quality",
      description =
          "Read realised forecast errors, baseline MAE and whether the series can be approved:"
              + " activation.eligible, the one reason that blocks it, and where the quality block"
              + " stands (points of the block, last block passed, consecutive failures before"
              + " drift). Before the first realised horizon quality is unmeasured and the reason"
              + " says what activation waits for. No evaluation is started.",
      annotations =
          @McpTool.McpAnnotations(
              readOnlyHint = true,
              destructiveHint = false,
              openWorldHint = false))
  public ToolResult<Measured<Quality>> quality(
      @McpToolParam(description = "Approved series id") String seriesId) {
    authorize(seriesId);
    var r = read(seriesId);
    // The page states why a series cannot be approved yet; an agent asking the same question
    // used to get errors and baseline scores, and no verdict.
    var activation = scrub(pilot.readiness(seriesId, r));
    return result(
        r == null || r.quality() == null
            ? Measured.unmeasured("No realised forecast quality yet; activation waits for: " + activation.reason())
            : Measured.of(
                new Quality(
                    r.quality(),
                    r.baselineMae(),
                    r.evaluatedPoints(),
                    r.evaluatedThrough(),
                    r.strategy(),
                    r.state(),
                    activation)),
        oneSeries(r == null ? 0 : 1));

  }

  @McpTool(
      name = "kex_list_predicted_threshold_breaches",
      description =
          "Evaluate the explicit threshold policy of every authorized series: one row per series"
              + " with outcome BREACH, NO_BREACH, NO_POLICY or NOT_EVALUATED and the reason. An"
              + " empty breach set is only complete when coverage is EXHAUSTED; NOT_EVALUATED"
              + " series (fallback, stale or missing forecast) are named in topicsNotReached."
              + " Includes nominal quantile confidence and activation status. No alert or"
              + " inference is triggered.",
      annotations =
          @McpTool.McpAnnotations(
              readOnlyHint = true,
              destructiveHint = false,
              openWorldHint = false))
  public ToolResult<List<ForecastPilotService.BreachEvaluation>> breaches() {
    var rows = new ArrayList<ForecastPilotService.BreachEvaluation>();
    for (var s : allowed()) {
      try {
        rows.add(scrub(pilot.evaluateBreach(s.seriesId())));
      } catch (Exception e) {
        throw unavailable();
      }
    }
    // A series without a policy was never asked about; one with a policy and no evaluable
    // forecast was, and its absence from the breaches must not read as "nothing predicted".
    var withPolicy =
        rows.stream().filter(r -> r.outcome() != ForecastPilotService.BreachOutcome.NO_POLICY).toList();
    var notReached =
        withPolicy.stream()
            .filter(r -> r.outcome() == ForecastPilotService.BreachOutcome.NOT_EVALUATED)
            .map(ForecastPilotService.BreachEvaluation::seriesId)
            .toList();
    var warnings = new ArrayList<>(List.of(LIMITS));
    Coverage coverage;
    if (notReached.isEmpty()) {
      coverage = Coverage.exhausted(withPolicy.size(), 0, 0);
    } else {
      coverage =
          Coverage.partial(
              withPolicy.size(),
              withPolicy.size() - notReached.size(),
              notReached,
              0,
              0,
              StopReason.PARTIAL_FAILURE,
              null);
      warnings.add(
          Warning.warn(
              "FORECAST_NOT_EVALUATED",
              notReached.size()
                  + " series with a threshold policy could not be evaluated; their breach status"
                  + " is unknown, not negative"));
    }
    return ToolResult.of(List.copyOf(rows), coverage, List.copyOf(warnings));
  }

  /** Why a series has no reach: said, never folded into "nothing predicted". */
  public enum ReachOutcome { REACH, NO_REACH, NO_THRESHOLDS, NOT_COMPARABLE, NOT_EVALUATED }

  /**
   * One authorized series read against its metric's own warning and critical thresholds. {@code at}
   * is the first predicted point at or past the threshold, {@code inSeconds} the time from now to it
   * (never below zero); both null unless {@code outcome} is {@code REACH}.
   */
  public record ThresholdReach(String seriesId, String metricId, ReachOutcome outcome, String level,
                               String likelihood, Double threshold, Long at, Long inSeconds, String reason) {}

  @McpTool(
      name = "kex_list_forecast_threshold_reaches",
      description =
          "For every authorized series, when its forecast reaches the metric's OWN warning or"
              + " critical threshold — the reading the Metrics page card shows and"
              + " explorer_forecast_threshold_eta_seconds publishes. Not the series' threshold policy"
              + " (that is kex_list_predicted_threshold_breaches). One row per series: REACH (level,"
              + " likelihood expected on the median or possible on the 80% band edge, the instant and"
              + " the seconds until it), NO_REACH, NO_THRESHOLDS, NOT_COMPARABLE (a counter's rate"
              + " forecast against thresholds set on the counter) or NOT_EVALUATED (no forecast yet)."
              + " An empty REACH set is only complete when coverage is EXHAUSTED. No alert or"
              + " inference is triggered.",
      annotations =
          @McpTool.McpAnnotations(
              readOnlyHint = true,
              destructiveHint = false,
              openWorldHint = false))
  public ToolResult<List<ThresholdReach>> thresholdReaches() {
    if (metrics == null) {
      throw new McpToolException(McpErrorCode.DEPENDENCY_UNAVAILABLE, "Metric thresholds unavailable");
    }
    var byId = new java.util.HashMap<String, MetricConfig>();
    for (var m : metrics.getAllMetrics()) byId.putIfAbsent(m.id(), m);
    long now = System.currentTimeMillis();
    var rows = new ArrayList<ThresholdReach>();
    for (var s : allowed()) {
      String metricId = scrub(s.metricId());
      MetricConfig metric = byId.get(s.metricId());
      if (metric == null || (metric.warningThreshold() == null && metric.criticalThreshold() == null)) {
        rows.add(new ThresholdReach(s.seriesId(), metricId, ReachOutcome.NO_THRESHOLDS, null, null, null, null, null,
            metric == null ? "The metric is not defined in this Explorer" : "The metric has no warning or critical threshold"));
        continue;
      }
      var record = read(s.seriesId());
      if (record == null || record.forecast() == null) {
        rows.add(new ThresholdReach(s.seriesId(), metricId, ReachOutcome.NOT_EVALUATED, null, null, null, null, null,
            record == null ? scrub(absence(s.seriesId())) : record.reason() == null ? "No forecast in the latest result" : "No forecast in the latest result: " + scrub(record.reason())));
        continue;
      }
      if (record.context() != null && record.context().profile() != null
          && record.context().profile().transformation() == SeriesPreparationProfile.Transformation.COUNTER_RATE) {
        rows.add(new ThresholdReach(s.seriesId(), metricId, ReachOutcome.NOT_COMPARABLE, null, null, null, null, null,
            "A counter's forecast is a rate per second; its thresholds are on the counter's own reading"));
        continue;
      }
      var reach = ForecastThresholdReach.reachFor(record, metric.warningThreshold(), metric.criticalThreshold());
      rows.add(reach == null
          ? new ThresholdReach(s.seriesId(), metricId, ReachOutcome.NO_REACH, null, null, null, null, null,
              "No threshold is reached within the forecast horizon")
          : new ThresholdReach(s.seriesId(), metricId, ReachOutcome.REACH,
              reach.level().name().toLowerCase(java.util.Locale.ROOT),
              reach.likelihood().name().toLowerCase(java.util.Locale.ROOT), reach.threshold(), reach.at(),
              Math.max(0, (reach.at() - now) / 1000), null));
    }
    var notReached = rows.stream().filter(r -> r.outcome() == ReachOutcome.NOT_EVALUATED)
        .map(ThresholdReach::seriesId).toList();
    var warnings = new ArrayList<>(List.of(LIMITS));
    Coverage coverage;
    if (notReached.isEmpty()) {
      coverage = Coverage.exhausted(rows.size(), 0, 0);
    } else {
      coverage = Coverage.partial(rows.size(), rows.size() - notReached.size(), notReached, 0, 0,
          StopReason.PARTIAL_FAILURE, null);
      warnings.add(Warning.warn("FORECAST_NOT_EVALUATED", notReached.size()
          + " series have no forecast to read against their thresholds; their reach is unknown, not negative"));
    }
    return ToolResult.of(List.copyOf(rows), coverage, List.copyOf(warnings));
  }

  private String scrub(String text) {
    return guard.dlp().scrub(text);
  }

  private ForecastPilotService.ActivationReadiness scrub(ForecastPilotService.ActivationReadiness a) {
    return new ForecastPilotService.ActivationReadiness(
        a.eligible(), scrub(a.reason()), a.blockPoints(), a.blockPointsRequired(), a.lastBlockPassed(),
        a.failedBlocks(), a.failedBlocksToDegrade());
  }

  private PreparedMetricSeries scrub(PreparedMetricSeries c) {
    return new PreparedMetricSeries(
        c.status(), scrub(c.reason()), c.seriesId(), scrub(c.definitionVersion()),
        scrub(c.sourceUnit()), scrub(c.outputUnit()), c.fromInclusive(), c.toExclusive(),
        c.profile(), c.profileFingerprint(), c.inputFingerprint(), c.observedPoints(),
        c.missingPoints(), c.imputedPoints(), c.points());
  }

  private MetricForecast scrub(MetricForecast f) {
    return f == null
        ? null
        : new MetricForecast(
            f.requestId(), f.seriesId(), scrub(f.definitionVersion()), f.inputFingerprint(),
            f.profileFingerprint(), scrub(f.outputUnit()), f.historyEndAt(), f.modelId(),
            f.modelRevision(), f.adapterVersion(), f.centralStatistic(), f.durationMillis(),
            f.points());
  }

  private ForecastRecord scrub(ForecastRecord r) {
    return new ForecastRecord(
        r.key(), r.generatedAt(), r.state(), r.strategy(), r.visibility(), scrub(r.context()),
        scrub(r.forecast()), r.quality(), r.evaluatedPoints(), r.evaluatedThrough(),
        r.baselineMae(), scrub(r.reason()), r.qualityWindow());
  }

  private ForecastPilotService.BreachEvaluation scrub(ForecastPilotService.BreachEvaluation e) {
    var p = e.breach();
    var scrubbed =
        p == null
            ? null
            : new ForecastPilotService.PredictedBreach(
                scrub(p.threshold()), p.resultKey(), p.evaluatedAt(), p.windowEndAt(),
                p.generatedAt(), p.historyEndAt(), p.modelId(), p.modelRevision(),
                p.inputFingerprint(), p.profileFingerprint());
    return new ForecastPilotService.BreachEvaluation(
        e.seriesId(), e.outcome(), scrub(e.reason()), scrubbed);
  }

  private ForecastThresholdEvaluator.Breach scrub(ForecastThresholdEvaluator.Breach b) {
    return new ForecastThresholdEvaluator.Breach(
        b.seriesId(), scrub(b.definitionVersion()), b.breached(), b.threshold(), b.direction(),
        b.horizonPoints(), b.confidence(), b.confidenceBound(), scrub(b.historyQuality()),
        b.visibility(), b.forecastStatus(), b.basis());
  }

  private String absence(String id) {
    try {
      return pilot.absenceReason(id);
    } catch (Exception e) {
      throw unavailable();
    }
  }

  private ForecastRecord read(String id) {
    try {
      return pilot.get(id);
    } catch (Exception e) {
      throw unavailable();
    }
  }

  private McpToolException unavailable() {
    return new McpToolException(
        McpErrorCode.DEPENDENCY_UNAVAILABLE, "Forecast persistence unavailable");
  }

  private static final Warning LIMITS =
      Warning.info(
          "FORECAST_LIMITS",
          "Reads existing results only; nominal quantiles are not guaranteed confidence; no"
              + " alert delivery");

  /**
   * One approved series read in full, and how many persisted records or history points came back.
   * Every read used to report a pass over zero sources having read zero records, true of none of
   * them; a series with nothing persisted is still a complete read whose answer is absence.
   */
  private static Coverage oneSeries(long records) {
    return Coverage.exhausted(1, records, 0);
  }

  private <T> ToolResult<T> result(T data, Coverage coverage) {
    return ToolResult.of(data, coverage, List.of(LIMITS));
  }
}
