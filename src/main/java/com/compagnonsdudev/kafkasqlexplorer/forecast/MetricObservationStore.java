// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors
package com.compagnonsdudev.kafkasqlexplorer.forecast;

import java.util.List;

/** No forecasting or cluster access is permitted at this persistence boundary. */
public interface MetricObservationStore {
    /** Bounded internal window; public adapters must enforce their own response budgets. */
    int MAX_READ_OBSERVATIONS = 4097;
    int MAX_READ_PAYLOAD_CHARACTERS = 4 * 1024 * 1024;
    final class ReadLimitExceededException extends Exception {
        public ReadLimitExceededException() { super("Observation payload exceeds the bounded read budget"); }
    }
    void append(List<MetricObservation> observations) throws Exception;
    List<MetricObservation> read(String seriesId, long fromInclusive, long toExclusive, int limit) throws Exception;
    int purgeBefore(long cutoff, int limit) throws Exception;
}
