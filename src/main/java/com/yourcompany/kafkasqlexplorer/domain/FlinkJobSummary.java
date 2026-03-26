// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors
package com.yourcompany.kafkasqlexplorer.domain;

public record FlinkJobSummary(
    String queryId,
    String flinkJobId,
    String statementType,
    String status,
    String sql,
    long startedAt,
    Long endedAt,
    boolean cancelRequested
) {}
