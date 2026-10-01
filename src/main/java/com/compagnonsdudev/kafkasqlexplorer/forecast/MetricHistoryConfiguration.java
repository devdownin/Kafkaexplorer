// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors
package com.compagnonsdudev.kafkasqlexplorer.forecast;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import java.sql.DriverManager;
import java.util.Properties;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "explorer.forecasting.history", name = "enabled", havingValue = "true")
public class MetricHistoryConfiguration {
    @Bean
    MetricObservationStore metricObservationStore(ForecastingProperties configuration) {
        MetricHistoryProperties p = configuration.getHistory();
        p.validateEnabled();
        return new JdbcMetricObservationStore(() -> {
            Properties connection = new Properties();
            connection.setProperty("user", p.getUsername());
            connection.setProperty("password", p.getPassword());
            connection.setProperty("connectTimeout", "5");
            connection.setProperty("socketTimeout", "5");
            // PostgreSQL gives URL parameters precedence over Properties. Append the bounds last
            // so an operator-supplied socketTimeout=0 cannot accidentally make the writer unbounded.
            String url = p.getJdbcUrl() + (p.getJdbcUrl().contains("?") ? "&" : "?")
                + "connectTimeout=5&socketTimeout=5";
            return DriverManager.getConnection(url, connection);
        });
    }

    @Bean
    MetricSeriesPreparationService metricSeriesPreparationService(MetricObservationStore store) {
        return new MetricSeriesPreparationService(store, new MetricSeriesPreparer());
    }

    @Bean
    MetricObservationJournal metricObservationJournal(ForecastingProperties configuration, MetricObservationStore store,
                                                       MeterRegistry registry) {
        return new MetricObservationJournal(configuration.getHistory(), store, registry);
    }
}
