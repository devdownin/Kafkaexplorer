// SPDX-License-Identifier: AGPL-3.0-or-later
package com.compagnonsdudev.kafkasqlexplorer.forecast;

import java.util.List;

/** Internal, uncalibrated output. Not a persisted forecast or an authorization decision. */
public record MetricForecast(String requestId, String seriesId, String definitionVersion, String inputFingerprint,
                             String profileFingerprint, String outputUnit, long historyEndAt,
                             String modelId, String modelRevision, String adapterVersion,
                             String centralStatistic, long durationMillis, List<Point> points) {
    public record Point(long at, double central, double q10, double q50, double q90) {}
    public MetricForecast { points = List.copyOf(points); }
}
