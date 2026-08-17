// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors
package com.compagnonsdudev.kafkasqlexplorer.domain;

/** What a suggested metric was derived from — the evidence the panel has to name. */
public enum MetricSuggestionSource {
    /** A cluster audit report: its flow steps, per-topic counts and findings. */
    AUDIT,
    /** A Stream Flow trace the operator ran, carried back from the browser. */
    STREAM_FLOW,
    /** A pipeline edge the user declared: a running INSERT job, as Flink's parser resolves it. */
    LINEAGE,
    /** A field mapping the Process Mining pipeline validated — where a real business key lives. */
    PROCESS_MINING
}
