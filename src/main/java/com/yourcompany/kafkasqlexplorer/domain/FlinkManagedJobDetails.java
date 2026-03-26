// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors
package com.yourcompany.kafkasqlexplorer.domain;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public record FlinkManagedJobDetails(
    String queryId,
    String flinkJobId,
    String statementType,
    String executionMode,
    String status,
    String statusDetail,
    String sql,
    long startedAt,
    Long endedAt,
    boolean cancelRequested,
    Long cancelRequestedAt,
    String errorMessage,
    long lastUpdatedAt,
    List<FlinkJobHistoryEntry> history
) {
    public FlinkJobSummary toSummary() {
        return new FlinkJobSummary(
            queryId,
            flinkJobId,
            statementType,
            status,
            sql,
            startedAt,
            endedAt,
            cancelRequested
        );
    }
}
