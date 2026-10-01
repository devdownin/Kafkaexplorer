// SPDX-License-Identifier: AGPL-3.0-or-later
package com.compagnonsdudev.kafkasqlexplorer.mcp.tools;

import com.compagnonsdudev.kafkasqlexplorer.forecast.ForecastSnapshotStore;
import com.compagnonsdudev.kafkasqlexplorer.forecast.MetricForecast;
import com.compagnonsdudev.kafkasqlexplorer.mcp.contract.Coverage;
import com.compagnonsdudev.kafkasqlexplorer.mcp.contract.ToolResult;
import com.compagnonsdudev.kafkasqlexplorer.mcp.contract.Warning;
import com.compagnonsdudev.kafkasqlexplorer.mcp.guard.ToolGuard;
import com.compagnonsdudev.kafkasqlexplorer.mcp.observability.ToolCategory;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;

import java.util.List;

/** Read-only forecast views. No MCP method starts inference or changes activation state. */
public final class ForecastMcpTools implements ReadOnlyMcpTools {
    private final ForecastSnapshotStore snapshots;
    private final ToolGuard guard;

    public ForecastMcpTools(ForecastSnapshotStore snapshots, ToolGuard guard) {
        this.snapshots = snapshots;
        this.guard = guard;
    }

    @Override public ToolCategory category() { return ToolCategory.DIAGNOSTIC; }

    public record ForecastSummary(String seriesId, String modelId, String modelRevision,
                                  String outputUnit, long historyEndAt, int horizon,
                                  String centralStatistic, boolean shadowOnly) { }

    @McpTool(name = "kex_forecast_catalog", annotations = @McpTool.McpAnnotations(
            readOnlyHint = true, destructiveHint = false, openWorldHint = false),
            description = "List forecasts already produced in the bounded shadow read model. This never runs inference.")
    public ToolResult<List<ForecastSummary>> catalog() {
        List<ForecastSummary> data = snapshots.all().stream().map(this::summary).toList();
        return ToolResult.of(data, Coverage.exhausted(data.size(), 0, 0), List.of(
                Warning.info("SHADOW_ONLY", "forecast snapshots are process-local and observational only")));
    }

    @McpTool(name = "kex_forecast_get", annotations = @McpTool.McpAnnotations(
            readOnlyHint = true, destructiveHint = false, openWorldHint = false),
            description = "Read one existing forecast by its authorized series id. No inference is started.")
    public ToolResult<MetricForecast> get(
            @McpToolParam(description = "64-character authorized series id") String seriesId) {
        checkSeriesId(seriesId);
        MetricForecast forecast = snapshots.get(seriesId);
        if (forecast == null) return ToolResult.of(null, Coverage.exhausted(0, 0, 0), List.of(
                Warning.warn("NOT_FOUND", "no forecast snapshot exists for this series")));
        return ToolResult.of(forecast, Coverage.exhausted(1, 0, 0), List.of(
                Warning.info("SHADOW_ONLY", "this output is not persisted and does not drive alerts")));
    }

    @McpTool(name = "kex_forecast_latest", annotations = @McpTool.McpAnnotations(
            readOnlyHint = true, destructiveHint = false, openWorldHint = false),
            description = "Read the latest bounded forecast for a series, if one exists.")
    public ToolResult<MetricForecast> latest(
            @McpToolParam(description = "64-character authorized series id") String seriesId) {
        return get(seriesId);
    }

    @McpTool(name = "kex_forecast_metadata", annotations = @McpTool.McpAnnotations(
            readOnlyHint = true, destructiveHint = false, openWorldHint = false),
            description = "Return model, revision, adapter, provenance and quality metadata for a forecast.")
    public ToolResult<ForecastMetadata> metadata(
            @McpToolParam(description = "64-character authorized series id") String seriesId) {
        checkSeriesId(seriesId);
        MetricForecast f = snapshots.get(seriesId);
        if (f == null) return ToolResult.of(null, Coverage.exhausted(0, 0, 0), List.of(Warning.warn("NOT_FOUND", "no forecast snapshot exists")));
        return ToolResult.complete(new ForecastMetadata(f.seriesId(), f.definitionVersion(), f.inputFingerprint(),
                f.profileFingerprint(), f.modelId(), f.modelRevision(), f.adapterVersion(), f.centralStatistic(), f.outputUnit(), true),
                Coverage.exhausted(1, 0, 0));
    }

    @McpTool(name = "kex_forecast_limits", annotations = @McpTool.McpAnnotations(
            readOnlyHint = true, destructiveHint = false, openWorldHint = false),
            description = "Explain the current forecast safety limits and what the MCP surface does not do.")
    public ToolResult<ForecastLimits> limits() {
        return ToolResult.complete(new ForecastLimits(4, 60, 256, true,
                List.of("no scheduling", "no persistence", "no alerts", "no activation mutation")), Coverage.exhausted(1, 0, 0));
    }

    public record ForecastMetadata(String seriesId, String definitionVersion, String inputFingerprint,
            String profileFingerprint, String modelId, String modelRevision, String adapterVersion,
            String centralStatistic, String outputUnit, boolean shadowOnly) { }
    public record ForecastLimits(int maxSeriesPerCall, int maxHorizonPoints, int maxSnapshots,
                                 boolean shadowOnly, List<String> excludedCapabilities) { }

    private ForecastSummary summary(MetricForecast f) {
        return new ForecastSummary(f.seriesId(), f.modelId(), f.modelRevision(), f.outputUnit(),
                f.historyEndAt(), f.points().size(), f.centralStatistic(), true);
    }

    private static void checkSeriesId(String seriesId) {
        if (seriesId == null || !seriesId.matches("[a-f0-9]{64}")) {
            throw new IllegalArgumentException("seriesId must be a 64-character hexadecimal id");
        }
    }
}
