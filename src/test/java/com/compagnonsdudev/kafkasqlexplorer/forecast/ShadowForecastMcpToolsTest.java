// SPDX-License-Identifier: AGPL-3.0-or-later
package com.compagnonsdudev.kafkasqlexplorer.forecast;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.compagnonsdudev.kafkasqlexplorer.mcp.McpProperties;
import com.compagnonsdudev.kafkasqlexplorer.mcp.guard.*;
import com.compagnonsdudev.kafkasqlexplorer.mcp.tools.ShadowForecastMcpTools;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.sql.Connection;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.mock.env.MockEnvironment;

class ShadowForecastMcpToolsTest {
  private final ForecastPilotStore store = mock(ForecastPilotStore.class);
  private final Connection connection = mock(Connection.class);
  private final McpProperties mcp = new McpProperties();
  private ForecastPilotProperties props;
  private ForecastPilotProperties.Series spec;

  @BeforeEach
  void setup() throws Exception {
    spec = new ForecastPilotProperties.Series(
        "a".repeat(64), "metric", "production", "v1", "messages", List.of("orders"), List.of(),
        new SeriesPreparationProfile(1000, 512, SeriesPreparationProfile.Transformation.GAUGE_LAST),
        2, 1, null, null, .8, 2);
    props = new ForecastPilotProperties();
    props.setSeries(List.of(spec));
    when(store.open()).thenReturn(connection);
    mcp.setAllowedForecastEnvironments(List.of("production"));
  }

  private ShadowForecastMcpTools tools() {
    var pilot = new ForecastPilotService(props, store, null, null, null, new SimpleMeterRegistry());
    return new ShadowForecastMcpTools(pilot, new ToolGuard(mcp, new DlpScrubber(mcp)));
  }

  private PreparedMetricSeries context() {
    var points = new ArrayList<PreparedMetricSeries.Point>();
    for (int i = 0; i < 512; i++) points.add(new PreparedMetricSeries.Point(i * 1000L, 1d, false, 1));
    return new PreparedMetricSeries(PreparedMetricSeries.Status.READY, "ready", spec.seriesId(), "v1", "messages",
        "messages", 0, 512_000, spec.profile(), "profile", "input", 512, 0, 0, points);
  }

  @Test
  void oneReadOnlyTool() {
    var tools = Arrays.stream(ShadowForecastMcpTools.class.getDeclaredMethods())
        .map(m -> m.getAnnotation(McpTool.class)).filter(a -> a != null).toList();
    assertEquals(List.of("kex_get_shadow_comparison"), tools.stream().map(McpTool::name).toList());
    assertTrue(tools.getFirst().annotations().readOnlyHint());
  }

  @Test
  void aSeriesOutsideTheCallersScopeIsRefusedBeforeAnyRead() {
    mcp.setAllowedTopicPrefixes(List.of("payments"));
    assertEquals(McpErrorCode.OUT_OF_SCOPE,
        assertThrows(McpToolException.class, () -> tools().comparison(spec.seriesId())).errorCode());
    assertEquals(McpErrorCode.OUT_OF_SCOPE,
        assertThrows(McpToolException.class, () -> tools().comparison("b".repeat(64))).errorCode());
    verifyNoInteractions(store);
  }

  @Test
  void nothingScoredYetIsUnmeasuredWithItsReasonNotAnEmptyVerdict() throws Exception {
    var result = tools().comparison(spec.seriesId());
    assertFalse(result.data().measured());
    assertTrue(result.data().reason().contains("No shadow forecast compatible"));
    assertEquals("SHADOW_NOT_SERVED", result.warnings().getFirst().code());
  }

  @Test
  void identityStringsAreScrubbedOnTheWayOut() throws Exception {
    props.setShadowCovariates(List.of(new ForecastPilotProperties.ShadowCovariate(
        spec.seriesId(), "b".repeat(64), "produced-by-ops@example.com", "v1", "messages",
        SeriesPreparationProfile.Transformation.COUNTER_RATE, List.of("orders"))));
    var c = context();
    when(store.latestShadow(connection, spec.seriesId())).thenReturn(new ShadowForecastRecord(
        ForecastPilotService.key(spec, c), 1, "READY", "Escalate to owner@example.com", c, null, null, 0, 0,
        null, null, "WAITING", "Covariate produced-by-ops@example.com is WARMING_UP", null, null, null));
    var data = tools().comparison(spec.seriesId()).data();
    assertTrue(data.measured());
    assertFalse(data.value().reason().contains("@example.com"));
    assertFalse(data.value().covariates().covariateMetricIds().getFirst().contains("@example.com"));
    assertFalse(data.value().covariates().reason().contains("@example.com"));
  }

  @Test
  void theConditionNamesTheSettingThatWithholdsTheTool() {
    var env = new MockEnvironment();
    assertTrue(ShadowComparisonCondition.withheldReason(env).contains("explorer.forecasting.pilot.series"));
    env.setProperty("explorer.forecasting.pilot.runtime-approval", "true");
    assertTrue(ShadowComparisonCondition.withheldReason(env).contains("shadow.enabled"));
    env.setProperty("explorer.forecasting.inference.enabled", "true");
    env.setProperty("explorer.forecasting.inference.shadow.enabled", "true");
    assertNull(ShadowComparisonCondition.withheldReason(env));
    env.setProperty("explorer.forecasting.pilot.enabled", "false");
    assertTrue(ShadowComparisonCondition.withheldReason(env).contains("pilot.enabled"));
  }
}
