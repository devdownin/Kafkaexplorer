// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors
package com.compagnonsdudev.kafkasqlexplorer.forecast;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** Root namespace reserved for history first, then the separately gated inference service. */
@Configuration(proxyBeanMethods = false)
@ConfigurationProperties(prefix = "explorer.forecasting")
public class ForecastingProperties {
    private MetricHistoryProperties history = new MetricHistoryProperties();
    private TimesFmInferenceProperties inference = new TimesFmInferenceProperties();
    public MetricHistoryProperties getHistory() { return history; }
    public void setHistory(MetricHistoryProperties value) { history = value; }
    public TimesFmInferenceProperties getInference() { return inference; }
    public void setInference(TimesFmInferenceProperties value) { inference = value; }
}
