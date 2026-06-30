// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors
package com.yourcompany.kafkasqlexplorer.domain;

public record QueryRequest(
    String sql,
    String topic,
    Integer maxRows,
    Long timeout,
    String readMode
) {
    public static QueryRequest sql(String sql, Integer maxRows, Long timeout, String readMode) {
        return new QueryRequest(sql, null, maxRows, timeout, readMode);
    }

    public static QueryRequest ddl(String sql, Long timeout) {
        return new QueryRequest(sql, null, 1, timeout, null);
    }
}
