// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors
package com.yourcompany.kafkasqlexplorer.domain;

import java.util.List;

/**
 * What the SQL editor needs to draw its schema browser — and, when it cannot be drawn, why.
 *
 * <p>The endpoint used to swallow both failures in empty catch blocks ({@code // Ignore and show
 * empty list}), so an unreachable broker and a Flink runtime still starting up produced exactly the
 * same screen: "Engine offline · 0 tables · 0 topics", with nothing to tell the two apart and
 * nothing to act on. The lists stay best-effort — one side failing must not deny the other — but
 * the reason travels with them now.
 *
 * @param kafkaError why the topic list is empty, or {@code null} when Kafka answered
 * @param flinkError why the table list is empty, or {@code null} when Flink answered
 */
public record QueryInitResponse(
    List<String> topics,
    List<String> tables,
    boolean health,
    String kafkaError,
    String flinkError
) {
    /** Kept for callers that have no failure to report. */
    public QueryInitResponse(List<String> topics, List<String> tables, boolean health) {
        this(topics, tables, health, null, null);
    }
}
