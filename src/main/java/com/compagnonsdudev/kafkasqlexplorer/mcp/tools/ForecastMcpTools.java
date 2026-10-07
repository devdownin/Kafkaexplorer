// SPDX-License-Identifier: AGPL-3.0-or-later
package com.compagnonsdudev.kafkasqlexplorer.mcp.tools;

import com.compagnonsdudev.kafkasqlexplorer.forecast.*;
import com.compagnonsdudev.kafkasqlexplorer.mcp.contract.*;
import com.compagnonsdudev.kafkasqlexplorer.mcp.guard.*;
import com.compagnonsdudev.kafkasqlexplorer.mcp.observability.ToolCategory;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;

/** All sources are authorized from operator provenance BEFORE any forecast/history read. */
public final class ForecastMcpTools implements ReadOnlyMcpTools {
  private final ForecastPilotService pilot;
  private final ToolGuard guard;

  public ForecastMcpTools(ForecastPilotService pilot, ToolGuard guard) {
    this.pilot = pilot;
    this.guard = guard;
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
    ForecastPilotProperties.Series s;
    try {
      s = pilot.resolve(id);
    } catch (IllegalArgumentException e) {
      throw new McpToolException(
          McpErrorCode.OUT_OF_SCOPE, "Series is outside the configured forecast pilot");
    }
    guard.checkNotQuarantined(id);
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
    return result(rows);
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
            ? Measured.unmeasured("No prepared history has been persisted")
            : Measured.of(r.context()));
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
        r == null ? Measured.unmeasured("No forecast has been persisted") : Measured.of(r));
  }

  public record Quality(
      ForecastBacktestEvaluator.Evaluation timesfmMetrics,
      Map<String, Double> baselineMae,
      int evaluatedPoints,
      long evaluatedThrough,
      String currentStrategy,
      String currentState) {}

  @McpTool(
      name = "kex_get_forecast_quality",
      description =
          "Read realised forecast errors and baseline MAE. Before forecast expiry quality is"
              + " unmeasured. No evaluation is started.",
      annotations =
          @McpTool.McpAnnotations(
              readOnlyHint = true,
              destructiveHint = false,
              openWorldHint = false))
  public ToolResult<Measured<Quality>> quality(
      @McpToolParam(description = "Approved series id") String seriesId) {
    authorize(seriesId);
    var r = read(seriesId);
    return result(
        r == null || r.quality() == null
            ? Measured.unmeasured("No realised forecast quality yet")
            : Measured.of(
                new Quality(
                    r.quality(),
                    r.baselineMae(),
                    r.evaluatedPoints(),
                    r.evaluatedThrough(),
                    r.strategy(),
                    r.state())));
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
        rows.add(pilot.evaluateBreach(s.seriesId()));
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

  private <T> ToolResult<T> result(T data) {
    return ToolResult.of(data, Coverage.exhausted(0, 0, 0), List.of(LIMITS));
  }
}
