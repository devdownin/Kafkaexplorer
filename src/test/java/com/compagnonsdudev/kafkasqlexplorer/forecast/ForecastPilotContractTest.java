// SPDX-License-Identifier: AGPL-3.0-or-later
package com.compagnonsdudev.kafkasqlexplorer.forecast;

import static org.junit.jupiter.api.Assertions.*;

import com.compagnonsdudev.kafkasqlexplorer.mcp.McpProperties;
import com.compagnonsdudev.kafkasqlexplorer.mcp.guard.*;
import com.compagnonsdudev.kafkasqlexplorer.mcp.tools.ForecastMcpTools;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;

class ForecastPilotContractTest {
  private ForecastPilotProperties.Series series() {
    return new ForecastPilotProperties.Series(
        "a".repeat(64),
        "metric",
        "production",
        "v1",
        "messages",
        List.of("orders"),
        List.of("consumer"),
        SeriesPreparationProfile.initial(SeriesPreparationProfile.Transformation.GAUGE_LAST),
        10,
        1,
        null,
        10d,
        .8,
        100);
  }

  private ForecastMcpTools tools(McpProperties properties) {
    var p = new ForecastPilotProperties();
    p.setSeries(List.of(series()));
    // Null dependencies prove denied reads cannot touch persistence or inference.
    var service = new ForecastPilotService(p, null, null, null, null);
    return new ForecastMcpTools(service, new ToolGuard(properties, new DlpScrubber(properties)));
  }

  @Test
  void exactlyFiveReadOnlyCanonicalTools() {
    var annotations =
        Arrays.stream(ForecastMcpTools.class.getDeclaredMethods())
            .map(m -> m.getAnnotation(McpTool.class))
            .filter(a -> a != null)
            .toList();
    assertEquals(
        Set.of(
            "kex_list_forecastable_metrics",
            "kex_metric_history",
            "kex_forecast_metric",
            "kex_get_forecast_quality",
            "kex_list_predicted_threshold_breaches"),
        annotations.stream().map(McpTool::name).collect(Collectors.toSet()));
    assertEquals(5, annotations.size());
    annotations.forEach(a -> assertTrue(a.annotations().readOnlyHint()));
  }

  @Test
  void environmentAndEveryResourceAreDeniedBeforeReads() {
    var p = new McpProperties();
    assertEquals(
        McpErrorCode.OUT_OF_SCOPE,
        assertThrows(McpToolException.class, () -> tools(p).get("a".repeat(64))).errorCode());
    p.setAllowedForecastEnvironments(List.of("production"));
    p.setAllowedTopicPrefixes(List.of("other"));
    assertEquals(
        McpErrorCode.OUT_OF_SCOPE,
        assertThrows(McpToolException.class, () -> tools(p).history("a".repeat(64))).errorCode());
    p.setAllowedTopicPrefixes(List.of("orders"));
    p.setAllowedGroupPrefixes(List.of("other"));
    assertEquals(
        McpErrorCode.OUT_OF_SCOPE,
        assertThrows(McpToolException.class, () -> tools(p).quality("a".repeat(64))).errorCode());
    assertEquals(
        McpErrorCode.OUT_OF_SCOPE,
        assertThrows(McpToolException.class, () -> tools(p).get("b".repeat(64))).errorCode());
  }

  @Test
  void disabledConfigurationHasNoPilotBeans() {
    try (var context = new AnnotationConfigApplicationContext()) {
      context
          .getEnvironment()
          .getPropertySources()
          .addFirst(
              new MapPropertySource("test", Map.of("explorer.forecasting.pilot.enabled", "false")));
      context.register(ForecastPilotConfiguration.class);
      context.refresh();
      assertTrue(context.getBeansOfType(ForecastPilotService.class).isEmpty());
      assertTrue(context.getBeansOfType(ForecastPilotStore.class).isEmpty());
    }
  }

  @Test
  void rejectsUnknownSemanticsAndGlobalSeriesBudget() {
    var p = new ForecastPilotProperties();
    p.setEnabled(true);
    p.setSeries(List.of(series()));
    p.validate();
    p.setSeries(List.of(series(), series()));
    assertThrows(IllegalArgumentException.class, p::validate);
    p.setSeries(List.of(series()));
    p.setMaxSeries(101);
    assertThrows(IllegalArgumentException.class, p::validate);
  }

  @Test
  void bindsExplicitSeriesAndSchedulerSettings() {
    var values = new java.util.LinkedHashMap<String, String>();
    values.put("explorer.forecasting.pilot.enabled", "true");
    values.put("explorer.forecasting.pilot.interval", "2m");
    String prefix = "explorer.forecasting.pilot.series[0].";
    values.put(prefix + "series-id", "a".repeat(64));
    values.put(prefix + "metric-id", "metric");
    values.put(prefix + "environment", "production");
    values.put(prefix + "definition-version", "v1");
    values.put(prefix + "unit", "messages");
    values.put(prefix + "topics[0]", "orders");
    values.put(prefix + "profile.step-millis", "60000");
    values.put(prefix + "profile.context-points", "512");
    values.put(prefix + "profile.transformation", "GAUGE_LAST");
    values.put(prefix + "horizon", "10");
    values.put(prefix + "season-length", "1");
    values.put(prefix + "minimum-coverage", "0.8");
    values.put(prefix + "minimum-evaluated-points", "100");
    var binder =
        new org.springframework.boot.context.properties.bind.Binder(
            new org.springframework.boot.context.properties.source.MapConfigurationPropertySource(
                values));
    var root =
        binder
            .bind(
                "explorer.forecasting",
                org.springframework.boot.context.properties.bind.Bindable.of(
                    ForecastingProperties.class))
            .get();
    root.getPilot().validate();
    assertEquals(java.time.Duration.ofMinutes(2), root.getPilot().getInterval());
    assertEquals("production", root.getPilot().getSeries().getFirst().environment());
    assertEquals(512, root.getPilot().getSeries().getFirst().profile().contextPoints());
  }
}
