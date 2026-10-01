// SPDX-License-Identifier: AGPL-3.0-or-later
package com.compagnonsdudev.kafkasqlexplorer.forecast;

/** Operator-owned threshold; never inferred from the model or from a global heuristic. */
public record ForecastThresholdPolicy(String seriesId, String definitionVersion, double threshold,
                                      Direction direction, int horizonPoints, double confidence,
                                      String historyQuality, Visibility visibility) {
    public enum Direction { ABOVE, BELOW }
    public enum Visibility { SHADOW, VISIBLE, ACTIVE }

    public ForecastThresholdPolicy {
        if (seriesId == null || !seriesId.matches("[a-f0-9]{64}")) throw new IllegalArgumentException("invalid series id");
        if (definitionVersion == null || definitionVersion.isBlank()) throw new IllegalArgumentException("definition version is required");
        if (!Double.isFinite(threshold)) throw new IllegalArgumentException("threshold must be finite");
        if (direction == null || horizonPoints < 1 || horizonPoints > 60) throw new IllegalArgumentException("invalid threshold horizon");
        if (!Double.isFinite(confidence) || confidence <= 0 || confidence > 1) throw new IllegalArgumentException("confidence must be in (0,1]");
        if (historyQuality == null || historyQuality.isBlank() || visibility == null) throw new IllegalArgumentException("threshold provenance is required");
    }
}
