// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors
package com.compagnonsdudev.kafkasqlexplorer.forecast;

import java.util.Objects;

/** Explicit source semantics: a Micrometer type alone is not a forecasting policy. */
public record SeriesPreparationProfile(long stepMillis, int contextPoints, Transformation transformation) {
    public enum Transformation { GAUGE_MEAN, GAUGE_MAX, GAUGE_LAST, COUNTER_RATE }
    public static final int MAX_INPUT_OBSERVATIONS = MetricObservationStore.MAX_READ_OBSERVATIONS - 1;
    public static final int MAX_GAP_STEPS = 2;
    public static final double MAX_MISSING_FRACTION = 0.05;

    public SeriesPreparationProfile {
        if (stepMillis < 1000 || stepMillis > 86_400_000 || contextPoints < 2 || contextPoints > 512) {
            throw new IllegalArgumentException("Preparation requires 2–512 points and a 1s–1d cadence");
        }
        Objects.requireNonNull(transformation, "transformation");
    }

    public static SeriesPreparationProfile initial(Transformation transformation) {
        return new SeriesPreparationProfile(60_000, 512, transformation);
    }

    public long endAt(long cutoff) {
        return Math.multiplyExact(Math.floorDiv(cutoff, stepMillis), stepMillis);
    }

    public long startAt(long cutoff) {
        return Math.subtractExact(endAt(cutoff), Math.multiplyExact(stepMillis, contextPoints));
    }

    /** The extra bucket supplies the counter's predecessor without using a future observation. */
    public long readFrom(long cutoff) {
        return transformation == Transformation.COUNTER_RATE
            ? Math.subtractExact(startAt(cutoff), stepMillis) : startAt(cutoff);
    }
}
