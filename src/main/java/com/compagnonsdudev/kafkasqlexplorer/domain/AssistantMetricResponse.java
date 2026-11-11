// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors
package com.compagnonsdudev.kafkasqlexplorer.domain;

/**
 * A metric draft for the Metrics editor — {@code POST /api/assistant/metric}.
 *
 * <p>The form is pre-filled, never saved: the editor's own preview decides. No threshold is
 * proposed, since the model has seen no value of this metric.
 *
 * @param proposal    the statement and everything known about it
 * @param name        a proposed metric name, or {@code null}
 * @param type        {@code GAUGE}, {@code COUNTER}, {@code HISTOGRAM} or {@code SUMMARY}
 * @param description one sentence on what the metric measures, or {@code null}
 */
public record AssistantMetricResponse(
    AssistantSqlResponse proposal,
    String name,
    String type,
    String description
) {
}
