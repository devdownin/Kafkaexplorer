// SPDX-License-Identifier: AGPL-3.0-or-later
package com.compagnonsdudev.kafkasqlexplorer.mcp.tools;

import com.compagnonsdudev.kafkasqlexplorer.forecast.*;
import com.compagnonsdudev.kafkasqlexplorer.mcp.contract.*;
import com.compagnonsdudev.kafkasqlexplorer.mcp.guard.*;
import com.compagnonsdudev.kafkasqlexplorer.mcp.observability.ToolCategory;
import java.util.ArrayList;
import java.util.List;
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
      String seriesId, String metricId, String environment, int horizon, String unit) {}

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
                        guard.dlp().scrub(s.unit())))
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
  public ToolResult<Measured<ForecastBacktestEvaluator.Evaluation>> quality(
      @McpToolParam(description = "Approved series id") String seriesId) {
    authorize(seriesId);
    var r = read(seriesId);
    return result(
        r == null || r.quality() == null
            ? Measured.unmeasured("No realised forecast quality yet")
            : Measured.of(r.quality()));
  }

  @McpTool(
      name = "kex_list_predicted_threshold_breaches",
      description =
          "List explicit threshold breaches for authorized series. Includes nominal quantile"
              + " confidence and activation status. No alert or inference is triggered.",
      annotations =
          @McpTool.McpAnnotations(
              readOnlyHint = true,
              destructiveHint = false,
              openWorldHint = false))
  public ToolResult<List<ForecastPilotService.PredictedBreach>> breaches() {
    var rows = new ArrayList<ForecastPilotService.PredictedBreach>();
    for (var s : allowed()) {
      try {
        var b = pilot.breach(s.seriesId());
        if (b != null && b.threshold().breached()) rows.add(b);
      } catch (Exception e) {
        throw unavailable();
      }
    }
    return result(List.copyOf(rows));
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

  private <T> ToolResult<T> result(T data) {
    return ToolResult.of(
        data,
        Coverage.exhausted(0, 0, 0),
        List.of(
            Warning.info(
                "FORECAST_LIMITS",
                "Reads existing results only; nominal quantiles are not guaranteed confidence; no"
                    + " alert delivery")));
  }
}
