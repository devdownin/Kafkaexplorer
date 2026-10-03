// SPDX-License-Identifier: AGPL-3.0-or-later
package com.compagnonsdudev.kafkasqlexplorer.forecast;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

@Configuration(proxyBeanMethods = false)
@EnableScheduling
@ConditionalOnProperty(
    prefix = "explorer.forecasting.pilot",
    name = "enabled",
    havingValue = "true")
public class ForecastPilotConfiguration {
  @Bean
  ForecastPilotProperties forecastPilotProperties(ForecastingProperties root) {
    if (!root.getHistory().isEnabled() || !root.getInference().isEnabled())
      throw new IllegalArgumentException("Pilot requires enabled history and inference");
    root.getPilot().validate();
    if (root.getPilot().getSeries().stream()
        .anyMatch(s -> !root.getHistory().getMetricIds().contains(s.metricId())))
      throw new IllegalArgumentException("Pilot metrics must be enrolled in durable history");
    return root.getPilot();
  }

  @Bean
  ForecastPilotStore forecastPilotStore(ForecastingProperties root) {
    return new ForecastPilotStore(root.getHistory());
  }

  @Bean
  ForecastPilotService forecastPilotService(
      ForecastPilotProperties p,
      ForecastPilotStore s,
      MetricSeriesPreparationService preparation,
      TimesFmClient client,
      MeterRegistry meters) {
    return new ForecastPilotService(p, s, preparation, client, meters);
  }
}
