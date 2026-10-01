// SPDX-License-Identifier: AGPL-3.0-or-later
package com.compagnonsdudev.kafkasqlexplorer.forecast;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "explorer.forecasting.inference", name = "enabled", havingValue = "true")
public class TimesFmConfiguration {
    @Bean(destroyMethod = "close")
    TimesFmClient timesFmClient(ForecastingProperties configuration) {
        return new TimesFmClient(configuration.getInference());
    }
}
