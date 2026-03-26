// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors
package com.yourcompany.kafkasqlexplorer.domain;

import lombok.Builder;

@Builder
public record QueryRequest(
    String sql,
    String topic,
    Integer maxRows,
    Long timeout,
    String readMode
) {
    public static QueryRequest sql(String sql, Integer maxRows, Long timeout, String readMode) {
        return QueryRequest.builder()
            .sql(sql)
            .maxRows(maxRows)
            .timeout(timeout)
            .readMode(readMode)
            .build();
    }

    public static QueryRequest ddl(String sql, Long timeout) {
        return QueryRequest.builder()
            .sql(sql)
            .maxRows(1)
            .timeout(timeout)
            .build();
    }
}
