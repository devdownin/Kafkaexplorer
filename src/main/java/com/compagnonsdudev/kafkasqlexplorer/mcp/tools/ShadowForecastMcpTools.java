// SPDX-License-Identifier: AGPL-3.0-or-later
package com.compagnonsdudev.kafkasqlexplorer.mcp.tools;

import com.compagnonsdudev.kafkasqlexplorer.forecast.ForecastPilotService;
import com.compagnonsdudev.kafkasqlexplorer.mcp.contract.*;
import com.compagnonsdudev.kafkasqlexplorer.mcp.guard.*;
import com.compagnonsdudev.kafkasqlexplorer.mcp.observability.ToolCategory;
import java.util.List;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;

/**
 * The shadow comparison, read-only and under the forecast tools' scope.
 *
 * <p>A toolset of its own rather than a sixth method on {@link ForecastMcpTools}: those are
 * registered whenever a series is approved, and this one answers only where the shadow runs. Listed
 * beside them it would be described to every agent and refuse on most deployments, the round trip
 * registration-time withholding exists to avoid. Authorization is the same function, before any read.
 */
public final class ShadowForecastMcpTools implements ReadOnlyMcpTools {
  private final ForecastPilotService pilot;
  private final ToolGuard guard;

  public ShadowForecastMcpTools(ForecastPilotService pilot, ToolGuard guard) {
    this.pilot = pilot;
    this.guard = guard;
  }

  @Override
  public ToolCategory category() {
    return ToolCategory.DIAGNOSTIC;
  }

  @McpTool(
      name = "kex_get_shadow_comparison",
      description =
          "Compare the shadow model (Timestral) with TimesFM on the cohorts both were scored on:"
              + " pooled quality, the shared baselines, whether each last quality block passed,"
              + " and wouldQualify with the reason it does not yet. With covariates declared,"
              + " covariates compares shadow-with, shadow-without and TimesFM on cohorts all three"
              + " forecast. The shadow is never served or approved, and sends no alert; wouldQualify"
              + " answers a question only. Unmeasured until a compatible shadow forecast exists."
              + " Starts no inference.",
      annotations =
          @McpTool.McpAnnotations(
              readOnlyHint = true,
              destructiveHint = false,
              openWorldHint = false))
  public ToolResult<Measured<ForecastPilotService.ShadowComparison>> comparison(
      @McpToolParam(description = "Approved series id") String seriesId) {
    ForecastMcpTools.authorize(pilot, guard, seriesId);
    ForecastPilotService.ShadowComparison c;
    try {
      c = pilot.shadowComparison(seriesId);
    } catch (IllegalArgumentException e) {
      throw new McpToolException(McpErrorCode.OUT_OF_SCOPE, "Series is outside the configured forecast pilot");
    } catch (Exception e) {
      throw new McpToolException(McpErrorCode.DEPENDENCY_UNAVAILABLE, "Forecast persistence unavailable");
    }
    return ToolResult.of(
        c == null
            ? Measured.unmeasured("No shadow forecast compatible with this series has been scored yet")
            : Measured.of(scrub(c)),
        Coverage.exhausted(1, c == null ? 0 : 1, 0),
        List.of(NOT_SERVED));
  }

  private String scrub(String text) {
    return text == null ? null : guard.dlp().scrub(text);
  }

  private ForecastPilotService.ShadowComparison scrub(ForecastPilotService.ShadowComparison c) {
    var v = c.covariates();
    var covariates =
        v == null
            ? null
            : new ForecastPilotService.CovariateComparison(
                v.covariateMetricIds().stream().map(this::scrub).toList(), v.state(), scrub(v.reason()),
                v.evaluatedPoints(), v.blockPoints(), v.blockPointsRequired(), v.multivariateQuality(),
                v.univariateQuality(), v.primaryQuality(), v.baselineMae(), v.multivariateBlockPassed(),
                v.univariateBlockPassed(), v.primaryBlockPassed(), v.wouldQualify(),
                scrub(v.qualificationBlocker()));
    return new ForecastPilotService.ShadowComparison(
        c.seriesId(), c.state(), scrub(c.reason()), c.modelId(), c.revision(), c.generatedAt(),
        c.evaluatedPoints(), c.evaluatedThrough(), c.blockPoints(), c.blockPointsRequired(), c.shadowQuality(),
        c.primaryQuality(), c.baselineMae(), c.shadowBlockPassed(), c.primaryBlockPassed(), c.wouldQualify(),
        scrub(c.qualificationBlocker()), covariates);
  }

  private static final Warning NOT_SERVED =
      Warning.info(
          "SHADOW_NOT_SERVED",
          "Comparison only: the shadow is never served, approved or alerted on; nominal quantiles"
              + " are not guaranteed confidence");
}
