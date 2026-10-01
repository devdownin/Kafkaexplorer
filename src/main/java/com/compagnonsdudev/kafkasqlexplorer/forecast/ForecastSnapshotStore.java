// SPDX-License-Identifier: AGPL-3.0-or-later
package com.compagnonsdudev.kafkasqlexplorer.forecast;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Bounded process-local read model; persistence and alerting are intentionally separate. */
public final class ForecastSnapshotStore {
    private static final int MAX_SNAPSHOTS = 256;
    private final Map<String, MetricForecast> snapshots = new ConcurrentHashMap<>();

    public void put(MetricForecast forecast) {
        if (snapshots.size() >= MAX_SNAPSHOTS && !snapshots.containsKey(forecast.seriesId())) {
            snapshots.keySet().stream().sorted().findFirst().ifPresent(snapshots::remove);
        }
        snapshots.put(forecast.seriesId(), forecast);
    }

    public MetricForecast get(String seriesId) { return snapshots.get(seriesId); }

    public List<MetricForecast> all() { return snapshots.values().stream().sorted(java.util.Comparator.comparing(MetricForecast::seriesId)).toList(); }

    public void clear() { snapshots.clear(); }
}
