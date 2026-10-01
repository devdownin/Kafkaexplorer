// SPDX-License-Identifier: AGPL-3.0-or-later
package com.compagnonsdudev.kafkasqlexplorer.forecast;

import java.util.List;
import java.util.Objects;

/** Explicit, bounded forecast execution seam. It has no scheduler and defaults to SHADOW. */
public final class ForecastOrchestrator {
    private final TimesFmClient client;
    private final ForecastSnapshotStore snapshots;

    public ForecastOrchestrator(TimesFmClient client, ForecastSnapshotStore snapshots) {
        this.client = Objects.requireNonNull(client);
        this.snapshots = Objects.requireNonNull(snapshots);
    }

    public ForecastBatch run(List<PreparedMetricSeries> contexts, int horizon, ForecastRunMode mode) {
        if (mode == null) throw new IllegalArgumentException("run mode is required");
        List<MetricForecast> forecasts = client.forecast(contexts, horizon);
        forecasts.forEach(snapshots::put);
        return new ForecastBatch(mode, forecasts, mode == ForecastRunMode.SHADOW,
                "Forecasts are observational only; no persistence, scheduling or alert is performed");
    }

    public record ForecastBatch(ForecastRunMode mode, List<MetricForecast> forecasts,
                                boolean shadow, String caveat) {
        public ForecastBatch { forecasts = List.copyOf(forecasts); }
    }
}
