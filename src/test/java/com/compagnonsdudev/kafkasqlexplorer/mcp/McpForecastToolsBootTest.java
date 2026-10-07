// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors
package com.compagnonsdudev.kafkasqlexplorer.mcp;

import com.compagnonsdudev.kafkasqlexplorer.ExplorerContextTest;
import com.compagnonsdudev.kafkasqlexplorer.mcp.observability.McpCatalogService;
import com.compagnonsdudev.kafkasqlexplorer.mcp.observability.ToolDescriptor;
import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The forecast tools, put in front of Spring AI's scanner.
 *
 * <p>They are registered only once a series is approved, so {@link McpServerBootTest} — the shipped
 * posture, no series — no longer derives their schemas. A return type the scanner cannot express
 * would then surface on the first deployment that approves a series. This context approves one; the
 * history database and the model are configured but never reached, since nothing here refreshes.
 */
@ExplorerContextTest
@TestPropertySource(properties = {
    "explorer.mcp.enabled=true",
    "explorer.mcp.readonly=true",
    "explorer.forecasting.history.enabled=true",
    "explorer.forecasting.history.cluster-id=test",
    "explorer.forecasting.history.collector-id=test",
    "explorer.forecasting.history.jdbc-url=jdbc:postgresql://127.0.0.1:1/unused",
    "explorer.forecasting.history.username=unused",
    "explorer.forecasting.history.metric-ids[0]=metric",
    "explorer.forecasting.inference.enabled=true",
    "explorer.forecasting.inference.token=test-token-that-is-not-a-secret-xxxxxx",
    "explorer.forecasting.pilot.series[0].series-id=aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
    "explorer.forecasting.pilot.series[0].metric-id=metric",
    "explorer.forecasting.pilot.series[0].environment=production",
    "explorer.forecasting.pilot.series[0].definition-version=v1",
    "explorer.forecasting.pilot.series[0].unit=messages",
    "explorer.forecasting.pilot.series[0].topics[0]=orders",
    "explorer.forecasting.pilot.series[0].profile.step-millis=60000",
    "explorer.forecasting.pilot.series[0].profile.context-points=512",
    "explorer.forecasting.pilot.series[0].profile.transformation=GAUGE_LAST",
    "explorer.forecasting.pilot.series[0].horizon=10",
    "explorer.forecasting.pilot.series[0].season-length=1",
    "explorer.forecasting.pilot.series[0].minimum-coverage=0.8",
    "explorer.forecasting.pilot.series[0].minimum-evaluated-points=100",
})
class McpForecastToolsBootTest {

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("kafka.bootstrap-servers", () -> "localhost:9092");
    }

    @Autowired List<SyncToolSpecification> toolSpecs;
    @Autowired McpCatalogService catalog;

    @Test
    void an_approved_series_registers_the_five_forecast_tools_with_schemas() {
        var forecast = toolSpecs.stream()
                .filter(spec -> McpServerBootTest.FORECAST.contains(spec.tool().name()))
                .toList();
        assertThat(forecast).extracting(spec -> spec.tool().name())
                .containsExactlyInAnyOrderElementsOf(McpServerBootTest.FORECAST);
        assertThat(forecast).allSatisfy(spec -> {
            assertThat(spec.tool().description()).isNotBlank();
            assertThat(spec.tool().inputSchema()).isNotNull();
        });
        assertThat(catalog.exposed()).extracting(ToolDescriptor::name)
                .containsAll(McpServerBootTest.FORECAST);
    }
}
