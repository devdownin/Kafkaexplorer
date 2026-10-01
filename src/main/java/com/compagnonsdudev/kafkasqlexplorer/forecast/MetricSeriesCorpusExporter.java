// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors
package com.compagnonsdudev.kafkasqlexplorer.forecast;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.util.Map;

/** Internal offline corpus export. The caller controls storage and source authorization. */
public final class MetricSeriesCorpusExporter {
    private static final ObjectMapper MAPPER = new ObjectMapper()
        .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);

    private MetricSeriesCorpusExporter() {}

    public static String toJson(PreparedMetricSeries series) throws JsonProcessingException {
        if (series.status() != PreparedMetricSeries.Status.READY) {
            throw new IllegalArgumentException("Only admissible contexts may enter the inference corpus");
        }
        return MAPPER.writeValueAsString(Map.of("schemaVersion", 1, "kind", "PREPARED_CONTEXT", "series", series));
    }
}
