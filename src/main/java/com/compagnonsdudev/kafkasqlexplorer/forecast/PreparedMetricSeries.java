// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors
package com.compagnonsdudev.kafkasqlexplorer.forecast;

import java.util.List;

/** JSON-serializable corpus contract; only READY points may be submitted for inference. */
public record PreparedMetricSeries(
    Status status, String reason, String seriesId, String definitionVersion, String sourceUnit,
    String outputUnit, long fromInclusive, long toExclusive, SeriesPreparationProfile profile,
    String profileFingerprint, String inputFingerprint, int observedPoints, int missingPoints,
    int imputedPoints, List<Point> points
) {
    public enum Status {
        READY, WARMING_UP, INSUFFICIENT_HISTORY, STALE, INVALID_DATA, SCOPE_CHANGED,
        COUNTER_RESET, HISTORY_LIMIT
    }

    public record Point(long endAt, Double value, boolean imputed, int samples) {}

    public PreparedMetricSeries { points = List.copyOf(points); }
}
