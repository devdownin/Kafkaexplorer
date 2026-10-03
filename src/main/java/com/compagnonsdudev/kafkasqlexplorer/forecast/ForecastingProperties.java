// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors
package com.compagnonsdudev.kafkasqlexplorer.forecast;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** Separately gated history capture, inference and production pilot. */
@Configuration(proxyBeanMethods = false)
@ConfigurationProperties(prefix = "explorer.forecasting")
public class ForecastingProperties {
    private MetricHistoryProperties history = new MetricHistoryProperties();
    private ForecastPilotProperties pilot = new ForecastPilotProperties();
    private TimesFmInferenceProperties inference = new TimesFmInferenceProperties();
    public ForecastPilotProperties getPilot() { return pilot; }
    public void setPilot(ForecastPilotProperties v) { pilot = v; }
    public MetricHistoryProperties getHistory() { return history; }
    public void setHistory(MetricHistoryProperties value) { history = value; }
    public TimesFmInferenceProperties getInference() { return inference; }
    public void setInference(TimesFmInferenceProperties value) { inference = value; }
}
