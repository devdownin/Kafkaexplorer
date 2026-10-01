// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors
package com.compagnonsdudev.kafkasqlexplorer.forecast;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.*;

class MetricHistoryConfigurationTest {
    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties
    static class Binding {}

    @Test
    void bindsNestedHistoryButDoesNotCreateDatabaseOrWriterByDefault() {
        new ApplicationContextRunner()
            .withUserConfiguration(Binding.class, ForecastingProperties.class, MetricHistoryConfiguration.class)
            .withPropertyValues("explorer.forecasting.history.cluster-id=lab",
                "explorer.forecasting.history.metric-ids=m1,m2")
            .run(context -> {
                assertNull(context.getStartupFailure());
                var p = context.getBean(ForecastingProperties.class).getHistory();
                assertEquals("lab", p.getClusterId());
                assertEquals(Set.of("m1", "m2"), p.getMetricIds());
                assertFalse(p.isEnabled());
                assertTrue(context.getBeansOfType(MetricObservationStore.class).isEmpty());
                assertTrue(context.getBeansOfType(MetricObservationJournal.class).isEmpty());
                assertTrue(context.getBeansOfType(MetricSeriesPreparationService.class).isEmpty());
            });
    }
}
