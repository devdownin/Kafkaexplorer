// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors
package com.compagnonsdudev.kafkasqlexplorer.forecast;

/** Internal-only adapter; source authorization is required before adding any public endpoint. */
public final class MetricSeriesPreparationService {
    private final MetricObservationStore store;
    private final MetricSeriesPreparer preparer;

    public MetricSeriesPreparationService(MetricObservationStore store, MetricSeriesPreparer preparer) {
        this.store = store; this.preparer = preparer;
    }

    public PreparedMetricSeries prepare(String seriesId, String definitionVersion, String unit, long cutoff,
                                       SeriesPreparationProfile profile) throws Exception {
        try {
            var rows = store.read(seriesId, profile.readFrom(cutoff), profile.endAt(cutoff),
                MetricObservationStore.MAX_READ_OBSERVATIONS);
            return preparer.prepare(seriesId, definitionVersion, unit, cutoff, profile, rows);
        } catch (MetricObservationStore.ReadLimitExceededException e) {
            return preparer.historyLimit(seriesId, definitionVersion, unit, cutoff, profile);
        }
    }
}
