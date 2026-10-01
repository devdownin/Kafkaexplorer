// SPDX-License-Identifier: AGPL-3.0-or-later
package com.compagnonsdudev.kafkasqlexplorer.forecast;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "explorer.forecasting.inference", name = "enabled", havingValue = "true")
public class TimesFmConfiguration {
    @Bean(destroyMethod = "close")
    TimesFmClient timesFmClient(ForecastingProperties configuration, MeterRegistry registry) {
        var inference = configuration.getInference();
        if (inference.getServiceUrl() == null) throw new IllegalArgumentException("TimesFM service URL is required");
        return new TimesFmClient(inference, registry);
    }

    @Bean
    ForecastSnapshotStore forecastSnapshotStore() {
        return new ForecastSnapshotStore();
    }

    @Bean
    ForecastOrchestrator forecastOrchestrator(TimesFmClient client, ForecastSnapshotStore snapshots) {
        return new ForecastOrchestrator(client, snapshots);
    }
}
